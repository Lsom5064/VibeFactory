import 'package:html/parser.dart' as html_parser;

import '../models/menu_item.dart';

class MenuParser {
  static const sourceUrl = 'https://kangwon.ac.kr/ko/extn/337/wkmenu-mngr/list.do';

  List<MenuItem> parseHtmlTable(String html, String targetDate) {
    final document = html_parser.parse(html);
    final records = <String>[];

    for (final table in document.querySelectorAll('table')) {
      for (final row in table.querySelectorAll('tr')) {
        final cells = row.querySelectorAll('th, td');
        if (cells.length < 4) {
          continue;
        }
        final values = cells
            .map((cell) => cell.text.replaceAll(RegExp(r'\s+'), ' ').trim())
            .where((text) => text.isNotEmpty)
            .toList();
        if (values.length < 4) {
          continue;
        }
        final record = values.take(4).join(' | ');
        records.add(record);
      }
    }

    return records
        .map((record) => normalizeRecord(record, targetDate))
        .whereType<MenuItem>()
        .toList();
  }

  List<MenuItem> parseTextPattern(String html, String targetDate) {
    final normalizedHtml = html.replaceAll(RegExp(r'\s+'), ' ');
    final regex = RegExp(
      r'([가-힣A-Za-z0-9]+-[^|]+?)\s*\|\s*([^|]+?\([^)]+\))\s*\|\s*([^|]+?\([^)]+\))\s*\|\s*([^<]+?)(?=(?:[가-힣A-Za-z0-9]+-[^|]+?\s*\|)|$)',
      multiLine: true,
    );

    return regex
        .allMatches(normalizedHtml)
        .map((match) => normalizeRecord(match.group(0)?.trim() ?? '', targetDate))
        .whereType<MenuItem>()
        .toList();
  }

  MenuItem? normalizeRecord(String raw, String targetDate) {
    final parts = raw
        .split(' | ')
        .map((part) => part.replaceAll(RegExp(r'\s+'), ' ').trim())
        .toList();
    if (parts.length != 4) {
      return null;
    }

    final campusRestaurant = parts[0].split('-');
    if (campusRestaurant.length != 2) {
      return null;
    }
    final campusName = campusRestaurant[0].trim();
    final restaurantName = campusRestaurant[1].trim();
    if (campusName.isEmpty || restaurantName.isEmpty) {
      return null;
    }

    final menuMatch = RegExp(r'^(.*)\((.*)\)$').firstMatch(parts[1]);
    if (menuMatch == null) {
      return null;
    }
    final menuCategoryName = (menuMatch.group(1) ?? '').trim();
    final mealType = (menuMatch.group(2) ?? '').trim();
    if (menuCategoryName.isEmpty || mealType.isEmpty) {
      return null;
    }

    final dateLabel = parts[2].trim();
    if (dateLabel.isEmpty) {
      return null;
    }
    final dayMatch = RegExp(r'\(([^)]+)\)').firstMatch(dateLabel);
    final dayOfWeek = (dayMatch?.group(1) ?? '').trim();
    if (dayOfWeek.isEmpty) {
      return null;
    }

    final menuBody = parts[3].replaceAll(RegExp(r'\s+'), ' ').trim();
    if (menuBody.isEmpty) {
      return null;
    }

    return MenuItem(
      campusName: campusName,
      restaurantName: restaurantName,
      menuCategoryName: menuCategoryName,
      mealType: mealType,
      dateLabel: dateLabel,
      dayOfWeek: dayOfWeek,
      menuBody: menuBody,
      targetDate: targetDate,
      sourceUrl: sourceUrl,
    );
  }
}
