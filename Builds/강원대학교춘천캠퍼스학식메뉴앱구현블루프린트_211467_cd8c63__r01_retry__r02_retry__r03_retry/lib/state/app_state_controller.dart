import 'dart:convert';

import 'package:flutter/foundation.dart';
import 'package:html/dom.dart';
import 'package:html/parser.dart' as html_parser;
import 'package:http/http.dart' as http;
import 'package:shared_preferences/shared_preferences.dart';

class MealEntry {
  const MealEntry({
    required this.sectionTitle,
    required this.mealTime,
    required this.menu,
    required this.dateLabel,
  });

  final String sectionTitle;
  final String mealTime;
  final String menu;
  final String dateLabel;

  Map<String, dynamic> toJson() => <String, dynamic>{
        'sectionTitle': sectionTitle,
        'mealTime': mealTime,
        'menu': menu,
        'dateLabel': dateLabel,
      };

  factory MealEntry.fromJson(Map<String, dynamic> json) {
    return MealEntry(
      sectionTitle: (json['sectionTitle'] as String?) ?? '',
      mealTime: (json['mealTime'] as String?) ?? '',
      menu: (json['menu'] as String?) ?? '',
      dateLabel: (json['dateLabel'] as String?) ?? '',
    );
  }
}

class AppStateController extends ChangeNotifier {
  static const String sourceUrl = 'https://kangwon.ac.kr/ko/extn/37/wkmenu-mngr/list.do';
  static const String _cacheRestaurantKey = 'selected_restaurant';
  static const String _cacheSectionKey = 'selected_section';
  static const String _cacheMealsKey = 'cached_meals';
  static const String _cacheSourceKey = 'cached_source_url';

  bool isLoading = false;
  String? errorMessage;
  String? selectedRestaurant;
  String? selectedSectionTitle;
  String? sourceLabel;

  List<MealEntry> _allMeals = <MealEntry>[];

  List<String> restaurants = <String>[];

  Future<void> initialize() async {
    await _restoreCache();
    await refresh();
  }

  List<String> get availableSections {
    final sections = _allMeals
        .where((meal) => meal.sectionTitle.trim().isNotEmpty)
        .map((meal) => meal.sectionTitle)
        .toSet()
        .toList()
      ..sort();
    return sections;
  }

  List<MealEntry> get visibleMeals {
    return _allMeals.where((meal) {
      final restaurantOk = selectedRestaurant == null || selectedRestaurant == meal.sectionTitle;
      final sectionOk = selectedSectionTitle == null || selectedSectionTitle == meal.sectionTitle;
      return restaurantOk && sectionOk;
    }).toList();
  }

  void selectRestaurant(String? value) {
    selectedRestaurant = value;
    selectedSectionTitle = value;
    _persistSelection();
    notifyListeners();
  }

  void selectSection(String? value) {
    selectedSectionTitle = value;
    selectedRestaurant = value;
    _persistSelection();
    notifyListeners();
  }

  Future<void> refresh() async {
    isLoading = true;
    errorMessage = null;
    notifyListeners();

    try {
      final http.Response response = await http.get(Uri.parse(sourceUrl));
      if (response.statusCode != 200) {
        throw Exception('공식 페이지 응답 코드가 ${response.statusCode}입니다.');
      }

      final List<MealEntry> parsedMeals = _parseMeals(response.body);
      if (parsedMeals.isEmpty) {
        throw Exception('공식 페이지에서 오늘 메뉴를 추출하지 못했습니다.');
      }

      _allMeals = parsedMeals;
      restaurants = _allMeals.map((meal) => meal.sectionTitle).toSet().toList();
      restaurants.sort();
      selectedRestaurant = restaurants.contains(selectedRestaurant) ? selectedRestaurant : restaurants.firstOrNull;
      selectedSectionTitle = selectedRestaurant;
      sourceLabel = sourceUrl;
      await _persistCache();
    } catch (error) {
      if (_allMeals.isEmpty) {
        errorMessage = '메뉴를 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.';
      } else {
        errorMessage = '실시간 데이터를 불러오지 못해 마지막 저장 메뉴를 표시합니다.';
      }
    } finally {
      isLoading = false;
      notifyListeners();
    }
  }

  List<MealEntry> _parseMeals(String html) {
    final document = html_parser.parse(html);
    final List<MealEntry> meals = <MealEntry>[];
    final int? todayColumnIndex = _findTodayColumnIndex(document);
    if (todayColumnIndex == null) {
      return meals;
    }

    final Element? chuncheonTab = _findChuncheonCampusTab(document);
    final String? activeRestaurant = _extractRestaurantName(chuncheonTab);
    if (activeRestaurant == null || !_isChuncheonRestaurant(activeRestaurant)) {
      return meals;
    }

    final tables = document.querySelectorAll('table');
    for (final table in tables) {
      final headers = table
          .querySelectorAll('thead th, tr th')
          .map((element) => _normalizeText(element.text))
          .where((text) => text.isNotEmpty)
          .toList();

      if (headers.length < 2 || headers.first != '구분') {
        continue;
      }
      if (todayColumnIndex >= headers.length) {
        continue;
      }

      final String cornerTitle = _extractCornerTitle(table);
      final String sectionTitle = cornerTitle.isEmpty ? activeRestaurant : '$activeRestaurant · $cornerTitle';
      final rows = table.querySelectorAll('tbody tr, tr');

      for (final row in rows) {
        final cells = row
            .querySelectorAll('th, td')
            .map((element) => _normalizeText(element.text))
            .toList();
        if (cells.length < 2 || cells.first == '구분') {
          continue;
        }
        if (todayColumnIndex >= cells.length) {
          continue;
        }

        final mealTime = cells.first;
        final menu = cells[todayColumnIndex];
        if (menu.isEmpty) {
          continue;
        }

        meals.add(
          MealEntry(
            sectionTitle: sectionTitle,
            mealTime: mealTime,
            menu: menu,
            dateLabel: headers[todayColumnIndex],
          ),
        );
      }
    }

    return meals;
  }

  Element? _findChuncheonCampusTab(Document document) {
    for (final element in document.querySelectorAll('.campus-tab, [data-campus-cd]')) {
      final text = _normalizeText(element.text);
      if (text.contains('춘천')) {
        return element;
      }
    }
    return null;
  }

  String? _extractRestaurantName(Element? campusTab) {
    if (campusTab == null) {
      return null;
    }

    Element? current = campusTab.parent;
    while (current != null) {
      final restaurantTabs = current.querySelectorAll('.caftr-tab, [data-caftr-cd]');
      if (restaurantTabs.isNotEmpty) {
        for (final tab in restaurantTabs) {
          final className = tab.className;
          final text = _normalizeText(tab.text);
          if (text.isEmpty) {
            continue;
          }
          if (className.contains('active') || className.contains('on') || tab.attributes['aria-selected'] == 'true') {
            return text;
          }
        }
        return _normalizeText(restaurantTabs.first.text);
      }
      current = current.parent;
    }

    final scriptText = documentScriptText(_findDocument(campusTab));
    final match = RegExp(r"var\s+caftrNm\s*=\s*'([^']+)'").firstMatch(scriptText);
    return match == null ? null : _normalizeText(match.group(1) ?? '');
  }

  Document? _findDocument(Node node) {
    Node? current = node;
    while (current != null) {
      if (current is Document) {
        return current;
      }
      current = current.parent;
    }
    return null;
  }

  String _extractCornerTitle(Element table) {
    Element? current = table.previousElementSibling;
    while (current != null) {
      final text = _normalizeText(current.text);
      if (_looksLikeCornerTitle(text)) {
        return text;
      }
      current = current.previousElementSibling;
    }
    return '';
  }

  bool _looksLikeCornerTitle(String text) {
    if (text.isEmpty) {
      return false;
    }
    if (text == '구분') {
      return false;
    }
    if (text.contains('arrow_circle')) {
      return false;
    }
    if (text.contains('금주의 식단') || text.contains('이번 주')) {
      return false;
    }
    if (text.contains('학생 생활에 필요한')) {
      return false;
    }
    return text.contains('원') || text.contains('식') || text.contains('코너') || text.contains('아침밥');
  }

  bool _isChuncheonRestaurant(String name) {
    final normalized = _normalizeText(name);
    if (normalized.isEmpty) {
      return false;
    }
    if (normalized.contains('삼척') || normalized.contains('도계') || normalized.contains('원주') || normalized.contains('강릉')) {
      return false;
    }
    return true;
  }

  int? _findTodayColumnIndex(Document document) {
    final now = DateTime.now();
    final month = now.month.toString().padLeft(2, '0');
    final day = now.day.toString().padLeft(2, '0');
    final token = '$month.$day';

    for (final table in document.querySelectorAll('table')) {
      final headers = table
          .querySelectorAll('thead th, tr th')
          .map((element) => _normalizeText(element.text))
          .where((text) => text.isNotEmpty)
          .toList();
      if (headers.length < 2 || headers.first != '구분') {
        continue;
      }
      for (int i = 1; i < headers.length; i++) {
        if (headers[i].contains(token)) {
          return i;
        }
      }
    }

    return null;
  }

  String _normalizeText(String text) {
    return text.replaceAll(RegExp(r'\s+'), ' ').trim();
  }

  Future<void> _restoreCache() async {
    final prefs = await SharedPreferences.getInstance();
    selectedRestaurant = prefs.getString(_cacheRestaurantKey);
    selectedSectionTitle = prefs.getString(_cacheSectionKey);
    sourceLabel = prefs.getString(_cacheSourceKey);

    final cachedMeals = prefs.getString(_cacheMealsKey);
    if (cachedMeals == null || cachedMeals.isEmpty) {
      return;
    }

    final decoded = jsonDecode(cachedMeals);
    if (decoded is! List) {
      return;
    }

    _allMeals = decoded
        .whereType<Map>()
        .map((item) => MealEntry.fromJson(Map<String, dynamic>.from(item)))
        .toList();
    restaurants = _allMeals.map((meal) => meal.sectionTitle).toSet().toList()..sort();
  }

  Future<void> _persistCache() async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setString(_cacheMealsKey, jsonEncode(_allMeals.map((meal) => meal.toJson()).toList()));
    await prefs.setString(_cacheSourceKey, sourceUrl);
    await _persistSelection();
  }

  Future<void> _persistSelection() async {
    final prefs = await SharedPreferences.getInstance();
    if (selectedRestaurant != null) {
      await prefs.setString(_cacheRestaurantKey, selectedRestaurant!);
    }
    if (selectedSectionTitle != null) {
      await prefs.setString(_cacheSectionKey, selectedSectionTitle!);
    }
  }
}

String documentScriptText(Document? document) {
  if (document == null) {
    return '';
  }
  return document
      .querySelectorAll('script')
      .map((script) => script.text)
      .join('\n');
}

extension on List<String> {
  String? get firstOrNull => isEmpty ? null : first;
}
