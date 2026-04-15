import '../core/utils/date_utils.dart';
import '../models/daily_menu.dart';
import '../models/weekly_menu.dart';

class MenuParserException implements Exception {
  final String message;

  MenuParserException(this.message);

  @override
  String toString() => message;
}

class MenuParser {
  WeeklyMenu parseWeeklyMenu({
    required String html,
    required DateTime weekStart,
    required DateTime weekEnd,
    required String sourceUrl,
  }) {
    final normalizedHtml = _normalizeHtml(html);
    final notices = _extractNotices(normalizedHtml);
    final rows = _extractRows(normalizedHtml);

    if (rows.isEmpty) {
      throw MenuParserException('필수 표 구조를 찾지 못했습니다.');
    }

    final days = <DailyMenu>[];
    for (final row in rows) {
      final cells = _extractCells(row);
      if (cells.length < 5) {
        continue;
      }

      final parsedDate = _parseDateCell(cells[0], weekStart);
      if (parsedDate == null) {
        continue;
      }

      days.add(
        DailyMenu(
          date: parsedDate,
          weekdayLabel: _normalizeText(cells[1]),
          breakfastMenus: _splitMenus(cells[2]),
          lunchMenus: _splitMenus(cells[3]),
          dinnerMenus: _splitMenus(cells[4]),
          note: cells.length > 5 ? _normalizeText(cells[5]) : '',
        ),
      );
    }

    if (days.isEmpty) {
      throw MenuParserException('날짜 행 파싱에 실패했습니다.');
    }

    return WeeklyMenu(
      weekStart: AppDateUtils.dateOnly(weekStart),
      weekEnd: AppDateUtils.dateOnly(weekEnd),
      fetchedAt: DateTime.now(),
      sourceUrl: sourceUrl,
      notices: notices,
      days: days,
    );
  }

  bool smokeTest(String html) {
    try {
      final now = DateTime.now();
      final start = AppDateUtils.startOfWeek(now);
      final end = AppDateUtils.endOfWeek(now);
      final result = parseWeeklyMenu(
        html: html,
        weekStart: start,
        weekEnd: end,
        sourceUrl: '스모크 테스트',
      );
      return result.days.isNotEmpty;
    } catch (_) {
      return false;
    }
  }

  String _normalizeHtml(String html) {
    return html.replaceAll('\r', '').replaceAll('&nbsp;', ' ');
  }

  List<String> _extractRows(String html) {
    final rowRegex = RegExp(r'<tr[^>]*>([\s\S]*?)</tr>', caseSensitive: false);
    return rowRegex.allMatches(html).map((e) => e.group(1) ?? '').toList();
  }

  List<String> _extractCells(String rowHtml) {
    final cellRegex = RegExp(r'<t[dh][^>]*>([\s\S]*?)</t[dh]>', caseSensitive: false);
    return cellRegex.allMatches(rowHtml).map((e) => _stripTags(e.group(1) ?? '')).toList();
  }

  List<String> _extractNotices(String html) {
    final noticeRegex = RegExp(r'<p[^>]*class="?[^"]*?(notice|guide|info)[^"]*"?[^>]*>([\s\S]*?)</p>', caseSensitive: false);
    final values = noticeRegex
        .allMatches(html)
        .map((e) => _normalizeText(_stripTags(e.group(2) ?? '')))
        .where((e) => e.isNotEmpty)
        .toList();
    return values;
  }

  DateTime? _parseDateCell(String raw, DateTime weekStart) {
    final text = _normalizeText(raw);
    final match = RegExp(r'(\d{1,2})[./-](\d{1,2})').firstMatch(text);
    if (match != null) {
      final month = int.tryParse(match.group(1) ?? '');
      final day = int.tryParse(match.group(2) ?? '');
      if (month != null && day != null) {
        return DateTime(weekStart.year, month, day);
      }
    }

    final dayOnly = RegExp(r'\b(\d{1,2})\b').firstMatch(text);
    if (dayOnly != null) {
      final day = int.tryParse(dayOnly.group(1) ?? '');
      if (day != null) {
        return DateTime(weekStart.year, weekStart.month, day);
      }
    }
    return null;
  }

  List<String> _splitMenus(String raw) {
    final normalized = _normalizeText(raw)
        .replaceAll('/', '\n')
        .replaceAll('·', '\n')
        .replaceAll('•', '\n');
    return normalized
        .split(RegExp(r'\n+'))
        .map((e) => e.trim())
        .where((e) => e.isNotEmpty && e != '-')
        .toList();
  }

  String _stripTags(String value) {
    return value
        .replaceAll(RegExp(r'<br\s*/?>', caseSensitive: false), '\n')
        .replaceAll(RegExp(r'<[^>]+>'), ' ');
  }

  String _normalizeText(String value) {
    return value.replaceAll(RegExp(r'[ \t]+'), ' ').replaceAll(RegExp(r'\n\s+'), '\n').trim();
  }
}
