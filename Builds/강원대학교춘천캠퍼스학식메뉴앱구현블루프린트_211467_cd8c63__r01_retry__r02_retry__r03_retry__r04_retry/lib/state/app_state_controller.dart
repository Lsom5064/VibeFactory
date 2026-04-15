import 'dart:async';
import 'dart:convert';

import 'package:flutter/foundation.dart';
import 'package:http/http.dart' as http;
import 'package:html/dom.dart' as dom;
import 'package:html/parser.dart' as html_parser;
import 'package:shared_preferences/shared_preferences.dart';

class MealItem {
  const MealItem({
    required this.sectionTitle,
    required this.mealTime,
    required this.dateLabel,
    required this.menu,
  });

  final String sectionTitle;
  final String mealTime;
  final String dateLabel;
  final String menu;

  Map<String, dynamic> toJson() {
    return <String, dynamic>{
      'sectionTitle': sectionTitle,
      'mealTime': mealTime,
      'dateLabel': dateLabel,
      'menu': menu,
    };
  }

  factory MealItem.fromJson(Map<String, dynamic> json) {
    return MealItem(
      sectionTitle: json['sectionTitle'] as String? ?? '',
      mealTime: json['mealTime'] as String? ?? '',
      dateLabel: json['dateLabel'] as String? ?? '',
      menu: json['menu'] as String? ?? '',
    );
  }
}

class _RestaurantOption {
  const _RestaurantOption({
    required this.name,
    required this.campusCd,
    required this.caftrCd,
  });

  final String name;
  final String campusCd;
  final String caftrCd;
}

class AppStateController extends ChangeNotifier {
  static const String _sourceUrl = 'https://kangwon.ac.kr/ko/extn/37/wkmenu-mngr/list.do';
  static const String _cacheMealsKey = 'cached_meals';
  static const String _cacheRestaurantKey = 'cached_restaurant';
  static const String _cacheSourceLabelKey = 'cached_source_label';
  static const String _chuncheonCampusCd = '1';

  bool _isLoading = false;
  String? _errorMessage;
  String? _sourceLabel;
  String? _selectedRestaurant;
  List<String> _restaurants = <String>[];
  List<MealItem> _allMeals = <MealItem>[];
  Map<String, _RestaurantOption> _restaurantOptions = <String, _RestaurantOption>{};

  bool get isLoading => _isLoading;
  String? get errorMessage => _errorMessage;
  String? get sourceLabel => _sourceLabel;
  String? get selectedRestaurant => _selectedRestaurant;
  List<String> get restaurants => List<String>.unmodifiable(_restaurants);

  List<MealItem> get visibleMeals {
    final selected = _selectedRestaurant;
    if (selected == null || selected.isEmpty) {
      return List<MealItem>.unmodifiable(_allMeals);
    }
    return List<MealItem>.unmodifiable(
      _allMeals.where((MealItem meal) => meal.sectionTitle == selected),
    );
  }

  Future<void> initialize() async {
    await _restoreCache();
    await refresh();
  }

  Future<void> refresh() async {
    _isLoading = true;
    _errorMessage = null;
    notifyListeners();

    try {
      final baseResponse = await http.get(Uri.parse(_sourceUrl));
      if (baseResponse.statusCode != 200) {
        throw Exception('식단 페이지 요청에 실패했습니다. (${baseResponse.statusCode})');
      }

      final baseDocument = html_parser.parse(baseResponse.body);
      final restaurantOptions = _extractRestaurantOptions(baseDocument);
      if (restaurantOptions.isEmpty) {
        throw Exception('춘천캠퍼스 식당 목록을 찾지 못했습니다.');
      }

      _restaurantOptions = <String, _RestaurantOption>{
        for (final _RestaurantOption option in restaurantOptions) option.name: option,
      };
      _restaurants = restaurantOptions.map((option) => option.name).toList();
      _selectedRestaurant = _normalizeSelectedRestaurant(_selectedRestaurant);

      final selectedName = _selectedRestaurant;
      final selectedOption = selectedName == null ? null : _restaurantOptions[selectedName];
      if (selectedOption == null) {
        throw Exception('선택한 춘천캠퍼스 식당 정보를 찾지 못했습니다.');
      }

      final response = await http.post(
        Uri.parse(_sourceUrl),
        headers: <String, String>{
          'Content-Type': 'application/x-www-form-urlencoded',
        },
        body: <String, String>{
          'campusCd': selectedOption.campusCd,
          'caftrCd': selectedOption.caftrCd,
          'targetDate': _formatTargetDate(DateTime.now()),
        },
      );
      if (response.statusCode != 200) {
        throw Exception('선택 식당 식단 요청에 실패했습니다. (${response.statusCode})');
      }

      final meals = _parseMeals(response.body, selectedOption.name);
      if (meals.isEmpty) {
        throw Exception('공식 페이지에서 오늘 식단 데이터를 찾지 못했습니다.');
      }

      _allMeals = meals;
      _sourceLabel = '출처: $_sourceUrl';
      _errorMessage = null;
      await _saveCache();
    } catch (error) {
      _errorMessage = '메뉴를 불러오지 못했습니다. 저장된 정보가 있으면 이를 표시합니다.';
      if (_allMeals.isEmpty) {
        await _restoreCache();
      }
    } finally {
      _isLoading = false;
      notifyListeners();
    }
  }

  void selectRestaurant(String? restaurant) {
    _selectedRestaurant = _normalizeSelectedRestaurant(restaurant);
    unawaited(_saveCache());
    notifyListeners();
    unawaited(refresh());
  }

  List<MealItem> _parseMeals(String body, String restaurantName) {
    final document = html_parser.parse(body);
    return _parseMealsFromTables(document, restaurantName);
  }

  List<_RestaurantOption> _extractRestaurantOptions(dom.Document document) {
    final List<_RestaurantOption> options = <_RestaurantOption>[];
    for (final element in document.querySelectorAll('.caftr-tab')) {
      final campusCd = element.attributes['data-campus-cd']?.trim() ?? '';
      final caftrCd = element.attributes['data-caftr-cd']?.trim() ?? '';
      final name = _cleanCellText(element.text);
      if (campusCd != _chuncheonCampusCd || caftrCd.isEmpty || name.isEmpty) {
        continue;
      }
      options.add(
        _RestaurantOption(
          name: name,
          campusCd: campusCd,
          caftrCd: caftrCd,
        ),
      );
    }

    final unique = <String, _RestaurantOption>{};
    for (final option in options) {
      unique[option.name] = option;
    }
    return unique.values.toList()..sort((a, b) => a.name.compareTo(b.name));
  }

  List<MealItem> _parseMealsFromTables(dom.Document document, String restaurantName) {
    final List<MealItem> meals = <MealItem>[];
    final tables = document.querySelectorAll('table');
    if (tables.isEmpty) {
      return meals;
    }

    String currentCorner = '';
    final todayLabel = _todayTableLabel(DateTime.now());

    for (final table in tables) {
      final headers = table
          .querySelectorAll('th')
          .map((dom.Element cell) => _cleanCellText(cell.text))
          .where((String text) => text.isNotEmpty)
          .toList();

      if (headers.length < 2 || headers.first != '구분') {
        continue;
      }

      final dateHeaders = headers.skip(1).toList();
      if (dateHeaders.isEmpty || !dateHeaders.any(_looksLikeDate)) {
        continue;
      }

      final cornerTitle = _extractCornerTitle(table);
      if (cornerTitle.isNotEmpty) {
        currentCorner = cornerTitle;
      }

      final rows = table.querySelectorAll('tr');
      for (final row in rows) {
        final cells = row
            .querySelectorAll('th, td')
            .map((dom.Element cell) => _cleanCellText(cell.text))
            .toList();

        if (cells.isEmpty || cells.first == '구분' || cells.length < 2) {
          continue;
        }

        final mealTime = _normalizeMealTime(cells.first);
        final values = cells.skip(1).toList();
        final count = values.length < dateHeaders.length ? values.length : dateHeaders.length;

        for (var i = 0; i < count; i++) {
          final menu = values[i].trim();
          final dateLabel = dateHeaders[i].trim();
          if (menu.isEmpty || dateLabel.isEmpty) {
            continue;
          }
          if (dateLabel != todayLabel) {
            continue;
          }
          meals.add(
            MealItem(
              sectionTitle: currentCorner.isNotEmpty ? '$restaurantName · $currentCorner' : restaurantName,
              mealTime: mealTime,
              dateLabel: dateLabel,
              menu: menu,
            ),
          );
        }
      }
    }

    if (meals.isNotEmpty) {
      return meals;
    }

    for (final table in tables) {
      final headers = table
          .querySelectorAll('th')
          .map((dom.Element cell) => _cleanCellText(cell.text))
          .where((String text) => text.isNotEmpty)
          .toList();
      if (headers.length < 2 || headers.first != '구분') {
        continue;
      }
      final dateHeaders = headers.skip(1).toList();
      final cornerTitle = _extractCornerTitle(table);
      if (cornerTitle.isNotEmpty) {
        currentCorner = cornerTitle;
      }
      final rows = table.querySelectorAll('tr');
      for (final row in rows) {
        final cells = row
            .querySelectorAll('th, td')
            .map((dom.Element cell) => _cleanCellText(cell.text))
            .toList();
        if (cells.isEmpty || cells.first == '구분' || cells.length < 2) {
          continue;
        }
        final mealTime = _normalizeMealTime(cells.first);
        final values = cells.skip(1).toList();
        final count = values.length < dateHeaders.length ? values.length : dateHeaders.length;
        for (var i = 0; i < count; i++) {
          final menu = values[i].trim();
          final dateLabel = dateHeaders[i].trim();
          if (menu.isEmpty || dateLabel.isEmpty) {
            continue;
          }
          meals.add(
            MealItem(
              sectionTitle: currentCorner.isNotEmpty ? '$restaurantName · $currentCorner' : restaurantName,
              mealTime: mealTime,
              dateLabel: dateLabel,
              menu: menu,
            ),
          );
        }
      }
    }

    return meals;
  }

  String _extractCornerTitle(dom.Element table) {
    dom.Element? current = table.previousElementSibling;
    while (current != null) {
      final text = _cleanCellText(current.text);
      if (text.isNotEmpty &&
          !text.contains('arrow_circle') &&
          !text.contains('구분') &&
          !text.contains('04.') &&
          !text.contains('202')) {
        return text;
      }
      current = current.previousElementSibling;
    }
    return '';
  }

  String _cleanCellText(String value) {
    return value.replaceAll(RegExp(r'\s+'), ' ').trim();
  }

  String _normalizeMealTime(String value) {
    if (value.contains('조식') || value.contains('아침')) {
      return '아침';
    }
    if (value.contains('중식') || value.contains('점심')) {
      return '점심';
    }
    if (value.contains('석식') || value.contains('저녁')) {
      return '저녁';
    }
    return value.trim().isEmpty ? '식단' : value.trim();
  }

  String? _normalizeSelectedRestaurant(String? restaurant) {
    if (_restaurants.isEmpty) {
      return null;
    }
    if (restaurant != null && _restaurants.contains(restaurant)) {
      return restaurant;
    }
    return _restaurants.first;
  }

  bool _looksLikeDate(String line) {
    return RegExp(r'(20\d{2}[./-]\d{1,2}[./-]\d{1,2})|(\d{1,2}[./-]\d{1,2}(\([월화수목금토일]\))?)|(월|화|수|목|금|토|일)요일')
        .hasMatch(line);
  }

  String _formatTargetDate(DateTime date) {
    final local = date.toLocal();
    final month = local.month.toString().padLeft(2, '0');
    final day = local.day.toString().padLeft(2, '0');
    return '${local.year}$month$day';
  }

  String _todayTableLabel(DateTime date) {
    const weekdays = <String>['월', '화', '수', '목', '금', '토', '일'];
    final local = date.toLocal();
    final month = local.month.toString().padLeft(2, '0');
    final day = local.day.toString().padLeft(2, '0');
    return '$month.$day(${weekdays[local.weekday - 1]})';
  }

  Future<void> _saveCache() async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setString(
      _cacheMealsKey,
      jsonEncode(_allMeals.map((MealItem meal) => meal.toJson()).toList()),
    );
    await prefs.setString(_cacheRestaurantKey, _selectedRestaurant ?? '');
    await prefs.setString(_cacheSourceLabelKey, _sourceLabel ?? '');
  }

  Future<void> _restoreCache() async {
    final prefs = await SharedPreferences.getInstance();
    final mealsJson = prefs.getString(_cacheMealsKey);
    if (mealsJson != null && mealsJson.isNotEmpty) {
      final decoded = jsonDecode(mealsJson);
      if (decoded is List) {
        _allMeals = decoded
            .whereType<Map>()
            .map((Map item) => MealItem.fromJson(Map<String, dynamic>.from(item)))
            .toList();
      }
    }

    _restaurants = _allMeals
        .map((MealItem meal) => meal.sectionTitle)
        .where((String title) => title.isNotEmpty)
        .toSet()
        .toList()
      ..sort();
    final cachedRestaurant = prefs.getString(_cacheRestaurantKey);
    _selectedRestaurant = _normalizeSelectedRestaurant(cachedRestaurant);

    final cachedSourceLabel = prefs.getString(_cacheSourceLabelKey);
    if (cachedSourceLabel != null && cachedSourceLabel.isNotEmpty) {
      _sourceLabel = cachedSourceLabel;
    }
  }
}
