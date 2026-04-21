import 'dart:core';

import 'package:html/parser.dart' as html_parser;

import '../models/menu_cache_item.dart';

class MenuParser {
  List<MenuCacheItem> parseTodayMenus(String html, {DateTime? now}) {
    final DateTime current = now ?? DateTime.now();
    final String today = _formatDate(current);
    final List<MenuCacheItem> allRecords = extractMenuRecords(html, now: current);
    return allRecords.where((MenuCacheItem item) => item.date == today).toList();
  }

  List<MenuCacheItem> extractMenuRecords(String html, {DateTime? now}) {
    final DateTime current = now ?? DateTime.now();
    final List<MenuCacheItem> domItems = _parseWithDom(html, current);
    if (domItems.isNotEmpty) {
      return _dedupe(domItems);
    }
    return _dedupe(_parseWithRegex(html, current));
  }

  List<String> extractNormalizedDates(String html, {DateTime? now}) {
    return extractMenuRecords(html, now: now)
        .map((MenuCacheItem item) => item.date)
        .toSet()
        .toList()
      ..sort();
  }

  bool supportsMinimumSampleDays(String html, {int minimumDays = 3, DateTime? now}) {
    return extractNormalizedDates(html, now: now).length >= minimumDays;
  }

  bool hasMigrationNotice(String html) {
    final String lower = html.toLowerCase();
    return lower.contains('이전') ||
        lower.contains('변경') ||
        lower.contains('점검') ||
        lower.contains('공지') ||
        lower.contains('안내');
  }

  String normalizeDate(String raw, {DateTime? now}) {
    final DateTime current = now ?? DateTime.now();
    final String cleaned = raw.replaceAll(RegExp(r'\s+'), ' ').trim();
    if (cleaned.isEmpty) {
      return '';
    }

    final RegExp fullDate = RegExp(r'(20\d{2})[^\d]?(\d{1,2})[^\d]?(\d{1,2})');
    final RegExpMatch? fullMatch = fullDate.firstMatch(cleaned);
    if (fullMatch != null) {
      final String year = fullMatch.group(1)!.padLeft(4, '0');
      final String month = fullMatch.group(2)!.padLeft(2, '0');
      final String day = fullMatch.group(3)!.padLeft(2, '0');
      return '$year-$month-$day';
    }

    final RegExp monthDay = RegExp(r'(\d{1,2})[^\d]?(\d{1,2})');
    final RegExpMatch? monthDayMatch = monthDay.firstMatch(cleaned);
    if (monthDayMatch != null) {
      final String year = current.year.toString().padLeft(4, '0');
      final String month = monthDayMatch.group(1)!.padLeft(2, '0');
      final String day = monthDayMatch.group(2)!.padLeft(2, '0');
      return '$year-$month-$day';
    }

    return '';
  }

  String normalizeRestaurantName(String raw) {
    return raw.replaceAll(RegExp(r'\s+'), ' ').trim();
  }

  String normalizeMenuText(String raw) {
    return raw
        .replaceAll(RegExp(r'\r\n|\r|\n'), '\n')
        .replaceAll(RegExp(r'[ \t]+'), ' ')
        .replaceAll(RegExp(r'\n\s+'), '\n')
        .replaceAll(RegExp(r'\n{2,}'), '\n')
        .trim();
  }

  List<MenuCacheItem> _parseWithDom(String html, DateTime now) {
    final document = html_parser.parse(html);
    final List<MenuCacheItem> items = <MenuCacheItem>[];
    final List<dynamic> candidates = <dynamic>[
      ...document.querySelectorAll('tr'),
      ...document.querySelectorAll('li'),
      ...document.querySelectorAll('div'),
      ...document.querySelectorAll('section'),
    ];

    for (final dynamic node in candidates) {
      final String text = (node.text ?? '').toString().trim();
      if (text.isEmpty) {
        continue;
      }
      final String normalizedDate = normalizeDate(text, now: now);
      if (normalizedDate.isEmpty) {
        continue;
      }

      final String restaurantName = _extractRestaurantName(node.text?.toString() ?? '');
      final String menuText = _extractMenuText(node.text?.toString() ?? '', restaurantName, normalizedDate);
      final MenuCacheItem? item = _buildValidatedItem(
        date: normalizedDate,
        restaurantName: restaurantName,
        menuText: menuText,
        now: now,
      );
      if (item != null) {
        items.add(item);
      }
    }
    return items;
  }

  List<MenuCacheItem> _parseWithRegex(String html, DateTime now) {
    final List<MenuCacheItem> items = <MenuCacheItem>[];
    final RegExp blockPattern = RegExp(
      r'((?:20\d{2}[./-]\d{1,2}[./-]\d{1,2}|\d{1,2}[./-]\d{1,2}).{0,400})',
      dotAll: true,
    );

    for (final RegExpMatch match in blockPattern.allMatches(html)) {
      final String block = match.group(1)?.replaceAll(RegExp(r'<[^>]+>'), ' ') ?? '';
      final String normalizedDate = normalizeDate(block, now: now);
      final String restaurantName = _extractRestaurantName(block);
      final String menuText = _extractMenuText(block, restaurantName, normalizedDate);
      final MenuCacheItem? item = _buildValidatedItem(
        date: normalizedDate,
        restaurantName: restaurantName,
        menuText: menuText,
        now: now,
      );
      if (item != null) {
        items.add(item);
      }
    }
    return items;
  }

  MenuCacheItem? _buildValidatedItem({
    required String date,
    required String restaurantName,
    required String menuText,
    required DateTime now,
  }) {
    final String normalizedRestaurant = normalizeRestaurantName(restaurantName);
    final String normalizedMenu = normalizeMenuText(menuText);
    if (date.isEmpty || normalizedRestaurant.isEmpty || normalizedMenu.isEmpty) {
      return null;
    }
    if (date != _formatDate(now)) {
      return null;
    }
    return MenuCacheItem(
      date: date,
      restaurantName: normalizedRestaurant,
      menuText: normalizedMenu,
    );
  }

  String _extractRestaurantName(String text) {
    final List<String> lines = text
        .split(RegExp(r'[\n|]'))
        .map(normalizeRestaurantName)
        .where((String line) => line.isNotEmpty)
        .toList();

    for (final String line in lines) {
      if (line.contains('식당') || line.contains('학생') || line.contains('교직원') || line.contains('카페')) {
        return line;
      }
    }
    return lines.isNotEmpty ? lines.first : '';
  }

  String _extractMenuText(String text, String restaurantName, String date) {
    String result = text.replaceAll(restaurantName, ' ');
    result = result.replaceAll(date, ' ');
    result = result.replaceAll(RegExp(r'20\d{2}[./-]\d{1,2}[./-]\d{1,2}'), ' ');
    result = result.replaceAll(RegExp(r'\d{1,2}[./-]\d{1,2}'), ' ');
    return normalizeMenuText(result);
  }

  List<MenuCacheItem> _dedupe(List<MenuCacheItem> items) {
    final Map<String, MenuCacheItem> map = <String, MenuCacheItem>{};
    for (final MenuCacheItem item in items) {
      final String key = '${item.date}__${item.restaurantName}';
      map[key] = item;
    }
    return map.values.toList();
  }

  String _formatDate(DateTime dateTime) {
    final String year = dateTime.year.toString().padLeft(4, '0');
    final String month = dateTime.month.toString().padLeft(2, '0');
    final String day = dateTime.day.toString().padLeft(2, '0');
    return '$year-$month-$day';
  }
}
