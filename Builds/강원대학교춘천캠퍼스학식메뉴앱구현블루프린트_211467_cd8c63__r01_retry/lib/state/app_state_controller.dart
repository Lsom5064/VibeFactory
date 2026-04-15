import 'dart:convert';
import 'dart:io';

import 'package:flutter/foundation.dart';
import 'package:http/http.dart' as http;

class MealDay {
  const MealDay({required this.label, required this.menu});

  final String label;
  final String menu;

  Map<String, dynamic> toJson() => {
        'label': label,
        'menu': menu,
      };

  factory MealDay.fromJson(Map<String, dynamic> json) => MealDay(
        label: json['label'] as String? ?? '',
        menu: json['menu'] as String? ?? '',
      );
}

class MealSection {
  const MealSection({
    required this.title,
    required this.mealTime,
    required this.days,
  });

  final String title;
  final String mealTime;
  final List<MealDay> days;

  Map<String, dynamic> toJson() => {
        'title': title,
        'mealTime': mealTime,
        'days': days.map((e) => e.toJson()).toList(),
      };

  factory MealSection.fromJson(Map<String, dynamic> json) => MealSection(
        title: json['title'] as String? ?? '',
        mealTime: json['mealTime'] as String? ?? '',
        days: ((json['days'] as List?) ?? const [])
            .whereType<Map>()
            .map((e) => MealDay.fromJson(Map<String, dynamic>.from(e)))
            .toList(),
      );
}

class MealData {
  const MealData({
    required this.sourceUrl,
    required this.fetchedAt,
    required this.pageTitle,
    required this.restaurantName,
    required this.weekRange,
    required this.sections,
  });

  final String sourceUrl;
  final DateTime fetchedAt;
  final String pageTitle;
  final String restaurantName;
  final String weekRange;
  final List<MealSection> sections;

  Map<String, dynamic> toJson() => {
        'sourceUrl': sourceUrl,
        'fetchedAt': fetchedAt.toIso8601String(),
        'pageTitle': pageTitle,
        'restaurantName': restaurantName,
        'weekRange': weekRange,
        'sections': sections.map((e) => e.toJson()).toList(),
      };

  factory MealData.fromJson(Map<String, dynamic> json) => MealData(
        sourceUrl: json['sourceUrl'] as String? ?? '',
        fetchedAt: DateTime.tryParse(json['fetchedAt'] as String? ?? '') ?? DateTime.fromMillisecondsSinceEpoch(0),
        pageTitle: json['pageTitle'] as String? ?? '',
        restaurantName: json['restaurantName'] as String? ?? '',
        weekRange: json['weekRange'] as String? ?? '',
        sections: ((json['sections'] as List?) ?? const [])
            .whereType<Map>()
            .map((e) => MealSection.fromJson(Map<String, dynamic>.from(e)))
            .toList(),
      );
}

class AppStateController extends ChangeNotifier {
  static const String sourceUrl = 'https://kangwon.ac.kr/ko/extn/37/wkmenu-mngr/list.do';

  bool _isInitialized = false;
  bool _isLoading = false;
  String? _errorMessage;
  MealData? _mealData;

  bool get isInitialized => _isInitialized;
  bool get isLoading => _isLoading;
  String? get errorMessage => _errorMessage;
  MealData? get mealData => _mealData;
  bool get hasData => _mealData != null && _mealData!.sections.isNotEmpty;

  Future<void> initialize() async {
    if (_isInitialized) {
      return;
    }
    _isInitialized = true;
    await _loadCache();
    await refresh();
    notifyListeners();
  }

  Future<void> refresh() async {
    _isLoading = true;
    _errorMessage = null;
    notifyListeners();

    try {
      final response = await http.get(Uri.parse(sourceUrl), headers: const {
        'User-Agent': 'Mozilla/5.0',
        'Accept': 'text/html,application/xhtml+xml',
      });

      if (response.statusCode != 200) {
        throw HttpException('식단 페이지 응답 실패: ${response.statusCode}');
      }

      final html = utf8.decode(response.bodyBytes);
      final parsed = _parseMealHtml(html);
      if (parsed.sections.isEmpty) {
        throw const FormatException('HTML 테이블에서 식단 표본을 추출하지 못했습니다.');
      }

      _mealData = parsed;
      await _saveCache(parsed);
    } catch (e) {
      _errorMessage = '실시간 식단을 불러오지 못했습니다.';
      if (_mealData == null) {
        await _loadCache();
      }
      if (_mealData == null) {
        _errorMessage = '식단 데이터가 없습니다. 잠시 후 다시 시도해 주세요.';
      }
    } finally {
      _isLoading = false;
      notifyListeners();
    }
  }

  MealData _parseMealHtml(String html) {
    final titleMatch = RegExp(r'<title>(.*?)</title>', caseSensitive: false, dotAll: true).firstMatch(html);
    final pageTitle = _cleanText(titleMatch?.group(1) ?? '');

    final weekMatch = RegExp(r'(\d{4}\.\d{2}\.\d{2}~\d{4}\.\d{2}\.\d{2})').firstMatch(html);
    final weekRange = weekMatch?.group(1) ?? '';

    final restaurantMatch = RegExp(r"var\s+caftrNm\s*=\s*'([^']+)'", caseSensitive: false).firstMatch(html);
    final restaurantName = _cleanText(restaurantMatch?.group(1) ?? '');

    final sectionPattern = RegExp(
      r'<div[^>]*class="meal-title-area"[^>]*>.*?<strong[^>]*>(.*?)</strong>.*?</div>\s*<div[^>]*class="table-container meal"[^>]*>.*?<table[^>]*>(.*?)</table>',
      caseSensitive: false,
      dotAll: true,
    );

    final sections = <MealSection>[];
    for (final match in sectionPattern.allMatches(html)) {
      final sectionTitle = _cleanText(match.group(1) ?? '');
      final tableHtml = match.group(2) ?? '';
      final parsedSection = _parseTable(sectionTitle, tableHtml);
      if (parsedSection != null && parsedSection.days.isNotEmpty) {
        sections.add(parsedSection);
      }
    }

    if (sections.isEmpty) {
      final fallbackTables = RegExp(r'<table[^>]*>(.*?)</table>', caseSensitive: false, dotAll: true).allMatches(html).toList();
      for (var i = 0; i < fallbackTables.length; i++) {
        final parsedSection = _parseTable('식단 ${i + 1}', fallbackTables[i].group(1) ?? '');
        if (parsedSection != null && parsedSection.days.isNotEmpty) {
          sections.add(parsedSection);
        }
      }
    }

    return MealData(
      sourceUrl: sourceUrl,
      fetchedAt: DateTime.now(),
      pageTitle: pageTitle,
      restaurantName: restaurantName,
      weekRange: weekRange,
      sections: sections,
    );
  }

  MealSection? _parseTable(String title, String tableHtml) {
    final rowMatches = RegExp(r'<tr[^>]*>(.*?)</tr>', caseSensitive: false, dotAll: true).allMatches(tableHtml).toList();
    if (rowMatches.length < 2) {
      return null;
    }

    final headerCells = _extractCells(rowMatches.first.group(1) ?? '');
    final dataCells = _extractCells(rowMatches[1].group(1) ?? '');
    if (headerCells.length < 2 || dataCells.length < 2) {
      return null;
    }

    final mealTime = dataCells.first;
    final days = <MealDay>[];
    final count = headerCells.length < dataCells.length ? headerCells.length : dataCells.length;
    for (var i = 1; i < count; i++) {
      final label = headerCells[i];
      final menu = dataCells[i];
      if (label.isNotEmpty && menu.isNotEmpty) {
        days.add(MealDay(label: label, menu: menu));
      }
    }

    if (days.isEmpty) {
      return null;
    }

    return MealSection(title: title, mealTime: mealTime, days: days);
  }

  List<String> _extractCells(String rowHtml) {
    return RegExp(r'<t[hd][^>]*>(.*?)</t[hd]>', caseSensitive: false, dotAll: true)
        .allMatches(rowHtml)
        .map((e) => _cleanText(e.group(1) ?? ''))
        .where((e) => e.isNotEmpty)
        .toList();
  }

  String _cleanText(String input) {
    return input
        .replaceAll(RegExp(r'<br\s*/?>', caseSensitive: false), '\n')
        .replaceAll(RegExp(r'<[^>]+>'), ' ')
        .replaceAll('&nbsp;', ' ')
        .replaceAll('&amp;', '&')
        .replaceAll('&lt;', '<')
        .replaceAll('&gt;', '>')
        .replaceAll(RegExp(r'\s+'), ' ')
        .trim();
  }

  Future<File> _cacheFile() async {
    final dir = Directory.systemTemp;
    return File('${dir.path}${Platform.pathSeparator}kangwon_meal_menu_cache.json');
  }

  Future<void> _saveCache(MealData data) async {
    final file = await _cacheFile();
    await file.writeAsString(jsonEncode(data.toJson()), flush: true);
  }

  Future<void> _loadCache() async {
    try {
      final file = await _cacheFile();
      if (!await file.exists()) {
        return;
      }
      final raw = await file.readAsString();
      final decoded = jsonDecode(raw);
      if (decoded is Map<String, dynamic>) {
        _mealData = MealData.fromJson(decoded);
      } else if (decoded is Map) {
        _mealData = MealData.fromJson(Map<String, dynamic>.from(decoded));
      }
    } catch (_) {
      // 캐시 손상 시 무시
    }
  }
}
