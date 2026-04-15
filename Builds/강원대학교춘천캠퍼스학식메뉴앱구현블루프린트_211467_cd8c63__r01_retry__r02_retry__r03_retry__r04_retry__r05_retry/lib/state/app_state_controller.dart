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
    required this.menuItems,
    this.restaurantName,
    this.cornerTitle,
  });

  final String sectionTitle;
  final String mealTime;
  final List<String> menuItems;
  final String? restaurantName;
  final String? cornerTitle;

  String get effectiveRestaurantName {
    final direct = (restaurantName ?? '').trim();
    if (direct.isNotEmpty) {
      return direct;
    }
    final base = sectionTitle.split(' · ').first.trim();
    return base;
  }

  Map<String, dynamic> toJson() {
    return <String, dynamic>{
      'sectionTitle': sectionTitle,
      'mealTime': mealTime,
      'menuItems': menuItems,
      'restaurantName': restaurantName,
      'cornerTitle': cornerTitle,
    };
  }

  factory MealItem.fromJson(Map<String, dynamic> json) {
    final rawSectionTitle = (json['sectionTitle'] as String? ?? '').trim();
    final rawRestaurantName = (json['restaurantName'] as String?)?.trim();
    final rawCornerTitle = (json['cornerTitle'] as String?)?.trim();

    String? fallbackRestaurantName;
    String? fallbackCornerTitle;
    if (rawSectionTitle.contains(' · ')) {
      final parts = rawSectionTitle
          .split(' · ')
          .map((e) => e.trim())
          .where((e) => e.isNotEmpty)
          .toList();
      if (parts.isNotEmpty) {
        fallbackRestaurantName = parts.first;
      }
      if (parts.length > 1) {
        fallbackCornerTitle = parts.sublist(1).join(' · ');
      }
    } else if (rawSectionTitle.isNotEmpty) {
      fallbackRestaurantName = rawSectionTitle;
    }

    return MealItem(
      sectionTitle: rawSectionTitle,
      mealTime: (json['mealTime'] as String? ?? '').trim(),
      menuItems: ((json['menuItems'] as List<dynamic>?) ?? const <dynamic>[])
          .map((dynamic item) => item.toString().trim())
          .where((String item) => item.isNotEmpty)
          .toList(),
      restaurantName: (rawRestaurantName != null && rawRestaurantName.isNotEmpty)
          ? rawRestaurantName
          : fallbackRestaurantName,
      cornerTitle: (rawCornerTitle != null && rawCornerTitle.isNotEmpty)
          ? rawCornerTitle
          : fallbackCornerTitle,
    );
  }
}

class RestaurantOption {
  const RestaurantOption({
    required this.name,
    required this.cafeteriaCode,
  });

  final String name;
  final String cafeteriaCode;
}

class AppStateController extends ChangeNotifier {
  static const String _officialListUrl =
      'https://kangwon.ac.kr/ko/extn/37/wkmenu-mngr/list.do';
  static const String _cacheMealsKey = 'cached_meals_v2';
  static const String _cacheRestaurantsKey = 'cached_restaurants_v2';
  static const String _cacheSelectedRestaurantKey = 'cached_selected_restaurant_v2';
  static const String _campusCode = '1';

  final List<MealItem> _allMeals = <MealItem>[];
  final List<String> _restaurants = <String>[];
  final Map<String, String> _restaurantCodeByName = <String, String>{};

  bool _isLoading = false;
  String? _errorMessage;
  String? _selectedRestaurant;
  DateTime? _lastUpdated;

  bool get isLoading => _isLoading;
  String? get errorMessage => _errorMessage;
  String? get selectedRestaurant => _selectedRestaurant;
  DateTime? get lastUpdated => _lastUpdated;
  List<String> get restaurants => List<String>.unmodifiable(_restaurants);
  List<MealItem> get visibleMeals {
    final selected = (_selectedRestaurant ?? '').trim();
    if (selected.isEmpty) {
      return List<MealItem>.unmodifiable(_allMeals);
    }
    return List<MealItem>.unmodifiable(
      _allMeals.where((MealItem meal) => meal.effectiveRestaurantName == selected),
    );
  }

  Future<void> initialize() async {
    await _restoreCache();
    try {
      await refresh();
    } catch (_) {
      rethrow;
    }
  }

  Future<void> refresh() async {
    _isLoading = true;
    _errorMessage = null;
    notifyListeners();

    try {
      final listResponse = await http.get(Uri.parse(_officialListUrl));
      if (listResponse.statusCode != 200) {
        throw Exception('식당 목록을 불러오지 못했습니다.');
      }

      final listDocument = html_parser.parse(listResponse.body);
      final options = _extractRestaurantOptions(listDocument);
      if (options.isEmpty) {
        throw Exception('춘천캠퍼스 식당 목록을 찾지 못했습니다.');
      }

      _restaurants
        ..clear()
        ..addAll(options.map((RestaurantOption e) => e.name));
      _restaurantCodeByName
        ..clear()
        ..addEntries(options.map((RestaurantOption e) => MapEntry<String, String>(e.name, e.cafeteriaCode)));

      if (_selectedRestaurant == null || !_restaurants.contains(_selectedRestaurant)) {
        _selectedRestaurant = _restaurants.first;
      }

      final List<MealItem> fetchedMeals = <MealItem>[];
      final targetDate = _formatTargetDate(DateTime.now());

      for (final restaurant in options) {
        final response = await http.post(
          Uri.parse(_officialListUrl),
          headers: <String, String>{
            'Content-Type': 'application/x-www-form-urlencoded; charset=UTF-8',
          },
          body: <String, String>{
            'campusCd': _campusCode,
            'caftrCd': restaurant.cafeteriaCode,
            'targetDate': targetDate,
          },
        );

        if (response.statusCode != 200) {
          continue;
        }

        final document = html_parser.parse(response.body);
        final meals = _parseMealsFromTables(document, restaurant.name);
        fetchedMeals.addAll(meals);
      }

      _allMeals
        ..clear()
        ..addAll(fetchedMeals);
      _lastUpdated = DateTime.now();
      await _saveCache();
    } catch (error, stackTrace) {
      _errorMessage = error.toString();
      FlutterError.reportError(
        FlutterErrorDetails(
          exception: error,
          stack: stackTrace,
          library: 'app_state_controller',
          context: ErrorDescription('메뉴 새로고침 중 오류'),
        ),
      );
      rethrow;
    } finally {
      _isLoading = false;
      notifyListeners();
    }
  }

  void selectRestaurant(String? restaurant) {
    if (restaurant == null || restaurant.trim().isEmpty) {
      return;
    }
    _selectedRestaurant = restaurant;
    notifyListeners();
    _saveCache();
  }

  Future<void> _restoreCache() async {
    try {
      final prefs = await SharedPreferences.getInstance();
      final mealsJson = prefs.getString(_cacheMealsKey);
      final restaurantsJson = prefs.getString(_cacheRestaurantsKey);
      final selectedRestaurant = prefs.getString(_cacheSelectedRestaurantKey);

      if (mealsJson != null && mealsJson.isNotEmpty) {
        final decoded = jsonDecode(mealsJson) as List<dynamic>;
        _allMeals
          ..clear()
          ..addAll(
            decoded
                .map((dynamic item) => item is Map
                    ? Map<String, dynamic>.from(item as Map)
                    : <String, dynamic>{})
                .where((Map<String, dynamic> item) => item.isNotEmpty)
                .map((Map<String, dynamic> item) => MealItem.fromJson(item)),
          );
      }

      if (restaurantsJson != null && restaurantsJson.isNotEmpty) {
        final decoded = jsonDecode(restaurantsJson) as List<dynamic>;
        _restaurants
          ..clear()
          ..addAll(
            decoded.map((dynamic item) => item.toString().trim()).where((String item) => item.isNotEmpty),
          );
      }

      if (_restaurants.isEmpty && _allMeals.isNotEmpty) {
        final restored = _allMeals
            .map((MealItem meal) => meal.effectiveRestaurantName)
            .where((String name) => name.isNotEmpty)
            .toSet()
            .toList()
          ..sort();
        _restaurants
          ..clear()
          ..addAll(restored);
      }

      if (selectedRestaurant != null && _restaurants.contains(selectedRestaurant)) {
        _selectedRestaurant = selectedRestaurant;
      } else if (_restaurants.isNotEmpty) {
        _selectedRestaurant = _restaurants.first;
      }
      notifyListeners();
    } catch (error, stackTrace) {
      FlutterError.reportError(
        FlutterErrorDetails(
          exception: error,
          stack: stackTrace,
          library: 'app_state_controller',
          context: ErrorDescription('캐시 복원 중 오류'),
        ),
      );
      rethrow;
    }
  }

  Future<void> _saveCache() async {
    try {
      final prefs = await SharedPreferences.getInstance();
      await prefs.setString(
        _cacheMealsKey,
        jsonEncode(_allMeals.map((MealItem meal) => meal.toJson()).toList()),
      );
      await prefs.setString(_cacheRestaurantsKey, jsonEncode(_restaurants));
      if (_selectedRestaurant != null) {
        await prefs.setString(_cacheSelectedRestaurantKey, _selectedRestaurant!);
      }
    } catch (error, stackTrace) {
      FlutterError.reportError(
        FlutterErrorDetails(
          exception: error,
          stack: stackTrace,
          library: 'app_state_controller',
          context: ErrorDescription('캐시 저장 중 오류'),
        ),
      );
      rethrow;
    }
  }

  List<RestaurantOption> _extractRestaurantOptions(dom.Document document) {
    final Map<String, RestaurantOption> unique = <String, RestaurantOption>{};

    void addOption(String? name, String? campusCd, String? caftrCd) {
      final normalizedName = (name ?? '').replaceAll(RegExp(r'\s+'), ' ').trim();
      final normalizedCampus = (campusCd ?? '').trim();
      final normalizedCode = (caftrCd ?? '').trim();
      if (normalizedCampus != _campusCode || normalizedName.isEmpty || normalizedCode.isEmpty) {
        return;
      }
      unique.putIfAbsent(
        normalizedName,
        () => RestaurantOption(name: normalizedName, cafeteriaCode: normalizedCode),
      );
    }

    for (final element in document.querySelectorAll('.caftr-tab, .caftr-tab li, .caftr-tab button, .caftr-tab a, button[data-caftr-cd], a[data-caftr-cd]')) {
      addOption(
        _extractElementLabel(element),
        element.attributes['data-campus-cd'] ??
            element.attributes['data-campusCd'] ??
            element.attributes['campusCd'] ??
            element.attributes['campuscd'],
        element.attributes['data-caftr-cd'] ??
            element.attributes['data-caftrCd'] ??
            element.attributes['caftrCd'] ??
            element.attributes['caftrcd'],
      );
    }

    for (final element in document.querySelectorAll('[data-campus-cd][data-caftr-cd], [data-campusCd][data-caftrCd], [campuscd][caftrcd], [campusCd][caftrCd]')) {
      addOption(
        _extractElementLabel(element),
        element.attributes['data-campus-cd'] ??
            element.attributes['data-campusCd'] ??
            element.attributes['campusCd'] ??
            element.attributes['campuscd'],
        element.attributes['data-caftr-cd'] ??
            element.attributes['data-caftrCd'] ??
            element.attributes['caftrCd'] ??
            element.attributes['caftrcd'],
      );
    }

    return unique.values.toList();
  }

  String _extractElementLabel(dom.Element element) {
    final candidates = <String?>[
      element.text,
      element.attributes['aria-label'],
      element.attributes['title'],
      element.attributes['value'],
      element.attributes['data-caftr-nm'],
    ];
    for (final candidate in candidates) {
      final normalized = (candidate ?? '').replaceAll(RegExp(r'\s+'), ' ').trim();
      if (normalized.isNotEmpty) {
        return normalized;
      }
    }
    return '';
  }

  List<MealItem> _parseMealsFromTables(dom.Document document, String restaurantName) {
    final List<MealItem> todayMeals = <MealItem>[];
    final List<MealItem> weeklyMeals = <MealItem>[];
    final today = DateTime.now();

    for (final table in document.querySelectorAll('table')) {
      final rows = table.querySelectorAll('tr');
      if (rows.length < 2) {
        continue;
      }

      final headerCells = rows.first.querySelectorAll('th, td');
      if (headerCells.length < 2) {
        continue;
      }

      final firstHeader = headerCells.first.text.replaceAll(RegExp(r'\s+'), ' ').trim();
      if (!firstHeader.contains('구분')) {
        continue;
      }

      final dateHeaders = headerCells
          .skip(1)
          .map((dom.Element e) => e.text.replaceAll(RegExp(r'\s+'), ' ').trim())
          .toList();
      if (dateHeaders.isEmpty || !dateHeaders.any(_looksLikeDate)) {
        continue;
      }

      final cornerTitle = _extractCornerTitle(table);

      for (final row in rows.skip(1)) {
        final cells = row.querySelectorAll('th, td');
        if (cells.length < 2) {
          continue;
        }

        final mealTime = cells.first.text.replaceAll(RegExp(r'\s+'), ' ').trim();
        if (mealTime.isEmpty) {
          continue;
        }

        for (int i = 1; i < cells.length && i <= dateHeaders.length; i++) {
          final dateHeader = dateHeaders[i - 1];
          if (!_looksLikeDate(dateHeader)) {
            continue;
          }

          final menuItems = _extractMenuItems(cells[i]);
          if (menuItems.isEmpty) {
            continue;
          }

          final sectionTitle = cornerTitle == null || cornerTitle.isEmpty
              ? restaurantName
              : '$restaurantName · $cornerTitle';

          final meal = MealItem(
            sectionTitle: sectionTitle,
            mealTime: mealTime,
            menuItems: menuItems,
            restaurantName: restaurantName,
            cornerTitle: cornerTitle,
          );

          if (_isTodayHeader(dateHeader, today)) {
            todayMeals.add(meal);
          } else {
            weeklyMeals.add(meal);
          }
        }
      }
    }

    return todayMeals.isNotEmpty ? todayMeals : weeklyMeals;
  }

  List<String> _extractMenuItems(dom.Element cell) {
    final html = cell.innerHtml
        .replaceAll(RegExp(r'<br\s*/?>', caseSensitive: false), '\n')
        .replaceAll('&nbsp;', ' ');
    final text = html_parser.parseFragment(html).text ?? '';
    return text
        .split(RegExp(r'\n+'))
        .map((String item) => item.replaceAll(RegExp(r'\s+'), ' ').trim())
        .where((String item) => item.isNotEmpty && item != '-')
        .toList();
  }

  bool _looksLikeDate(String text) {
    final normalized = text.replaceAll(RegExp(r'\s+'), ' ').trim();
    return RegExp(r'(\d{1,2}[./-]\d{1,2}|\d{4}[./-]\d{1,2}[./-]\d{1,2}|월|화|수|목|금|토|일)').hasMatch(normalized);
  }

  bool _isTodayHeader(String header, DateTime today) {
    final mmdd = '${today.month}.${today.day}';
    final mmSlashDd = '${today.month}/${today.day}';
    final yyyyMmDd = '${today.year}.${today.month}.${today.day}';
    final yyyySlashMmDd = '${today.year}/${today.month}/${today.day}';
    final weekdayMap = <int, String>{1: '월', 2: '화', 3: '수', 4: '목', 5: '금', 6: '토', 7: '일'};
    final normalized = header.replaceAll(RegExp(r'\s+'), ' ').trim();
    return normalized.contains(mmdd) ||
        normalized.contains(mmSlashDd) ||
        normalized.contains(yyyyMmDd) ||
        normalized.contains(yyyySlashMmDd) ||
        normalized.contains(weekdayMap[today.weekday]!);
  }

  String? _extractCornerTitle(dom.Element table) {
    dom.Element? current = table.previousElementSibling;
    while (current != null) {
      final tag = current.localName?.toLowerCase();
      final text = current.text.replaceAll(RegExp(r'\s+'), ' ').trim();
      final isTitleTag = <String>{'h1', 'h2', 'h3', 'h4', 'h5', 'h6', 'strong', 'p', 'div'}.contains(tag);
      final looksInvalid = text.isEmpty ||
          text.contains('구분') ||
          text.contains('arrow_circle') ||
          text.contains('~') ||
          _looksLikeDate(text) ||
          text.length > 40;
      if (isTitleTag && !looksInvalid) {
        return text;
      }
      current = current.previousElementSibling;
    }
    return null;
  }

  String _formatTargetDate(DateTime date) {
    final month = date.month.toString().padLeft(2, '0');
    final day = date.day.toString().padLeft(2, '0');
    return '${date.year}$month$day';
  }
}
