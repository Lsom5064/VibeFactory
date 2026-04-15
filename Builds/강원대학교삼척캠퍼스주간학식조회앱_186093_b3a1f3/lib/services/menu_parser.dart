import '../crash_handler.dart';
import '../models/menu_models.dart';
import '../utils/date_utils.dart';

class MenuParser {
  WeeklyMenu parse(String html) {
    try {
      final normalizedHtml = _normalizeWhitespace(html);
      final range = _extractWeekRange(normalizedHtml);
      final parsedDays = _extractDailyMenus(normalizedHtml);
      final now = DateTime.now();
      final start = range.$1 ?? startOfWeek(now);
      final end = range.$2 ?? endOfWeek(now);
      return WeeklyMenu(
        startDate: start,
        endDate: end,
        updatedAt: now,
        days: parsedDays,
      );
    } catch (error, stackTrace) {
      CrashHandler.recordError(error, stackTrace, reason: '식단 HTML 파싱 실패');
      rethrow;
    }
  }

  (DateTime?, DateTime?) _extractWeekRange(String html) {
    final rangeRegexes = [
      RegExp(r'(\d{4})[./-]\s*(\d{1,2})[./-]\s*(\d{1,2})\s*~\s*(\d{4})[./-]\s*(\d{1,2})[./-]\s*(\d{1,2})'),
      RegExp(r'(\d{4})년\s*(\d{1,2})월\s*(\d{1,2})일\s*~\s*(\d{4})년\s*(\d{1,2})월\s*(\d{1,2})일'),
    ];
    for (final regex in rangeRegexes) {
      final match = regex.firstMatch(html);
      if (match != null) {
        final start = _safeDate(match.group(1), match.group(2), match.group(3));
        final end = _safeDate(match.group(4), match.group(5), match.group(6));
        if (start != null && end != null) {
          return (start, end);
        }
      }
    }
    return (null, null);
  }

  List<DailyMenu> _extractDailyMenus(String html) {
    final now = DateTime.now();
    final currentWeekStart = startOfWeek(now);
    final dateRegex = RegExp(r'(\d{4})[./-](\d{1,2})[./-](\d{1,2})\s*(?:\(([^)]+)\))?');
    final matches = dateRegex.allMatches(html).toList();
    final List<DailyMenu> days = [];

    if (matches.isEmpty) {
      return days;
    }

    for (var i = 0; i < matches.length; i++) {
      final match = matches[i];
      final nextStart = i + 1 < matches.length ? matches[i + 1].start : html.length;
      final block = html.substring(match.start, nextStart);
      final date = _safeDate(match.group(1), match.group(2), match.group(3));
      if (date == null) {
        continue;
      }
      final weekday = _normalizeText(match.group(4) ?? weekdayKorean(date));
      final cafeterias = _extractCafeterias(block);
      days.add(DailyMenu(
        date: date,
        weekday: weekday,
        order: date.difference(currentWeekStart).inDays,
        cafeterias: cafeterias,
      ));
    }

    return days;
  }

  List<CafeteriaMenu> _extractCafeterias(String block) {
    final lines = block
        .split(RegExp(r'<br\s*/?>|\n|\r'))
        .map(_stripTags)
        .map(_normalizeText)
        .where((e) => e.isNotEmpty)
        .toList();

    final List<CafeteriaMenu> cafeterias = [];
    String currentName = '학생식당';
    final List<MealSection> sections = [];

    for (final line in lines) {
      if (_looksLikeCafeteriaName(line)) {
        if (sections.isNotEmpty) {
          cafeterias.add(CafeteriaMenu(name: currentName, sections: List<MealSection>.from(sections)));
          sections.clear();
        }
        currentName = line;
        continue;
      }

      final section = _parseMealSection(line);
      if (section != null) {
        sections.add(section);
      }
    }

    if (sections.isNotEmpty) {
      cafeterias.add(CafeteriaMenu(name: currentName, sections: List<MealSection>.from(sections)));
    }

    if (cafeterias.isEmpty && lines.isNotEmpty) {
      cafeterias.add(
        CafeteriaMenu(
          name: currentName,
          sections: [
            MealSection(title: '메뉴', items: lines),
          ],
        ),
      );
    }

    return cafeterias;
  }

  MealSection? _parseMealSection(String line) {
    final sectionRegex = RegExp(r'^(조식|중식|석식|아침|점심|저녁|브런치|분식|특식|메뉴)\s*[:：-]?\s*(.*)$');
    final match = sectionRegex.firstMatch(line);
    if (match != null) {
      final title = _normalizeText(match.group(1) ?? '메뉴');
      final body = _normalizeText(match.group(2) ?? '');
      return MealSection(title: title, items: _splitMenuItems(body));
    }

    if (line.isNotEmpty) {
      return MealSection(title: '메뉴', items: _splitMenuItems(line));
    }
    return null;
  }

  List<String> _splitMenuItems(String raw) {
    final cleaned = _normalizeText(raw);
    if (cleaned.isEmpty) {
      return const [];
    }
    final parts = cleaned
        .split(RegExp(r'\s*[•·/,]|\s{2,}|\s*\|\s*'))
        .map(_normalizeText)
        .where((e) => e.isNotEmpty)
        .toList();
    return parts.isEmpty ? [cleaned] : parts;
  }

  bool _looksLikeCafeteriaName(String line) {
    return line.contains('식당') || line.contains('코너') || line.contains('카페') || line.contains('학생회관');
  }

  DateTime? _safeDate(String? y, String? m, String? d) {
    try {
      if (y == null || m == null || d == null) {
        return null;
      }
      return DateTime(int.parse(y), int.parse(m), int.parse(d));
    } catch (error, stackTrace) {
      CrashHandler.recordError(error, stackTrace, reason: '날짜 변환 실패');
      return null;
    }
  }

  String _stripTags(String input) {
    return input.replaceAll(RegExp(r'<[^>]*>'), ' ');
  }

  String _normalizeWhitespace(String input) {
    return input.replaceAll('&nbsp;', ' ').replaceAll(RegExp(r'\s+'), ' ');
  }

  String _normalizeText(String input) {
    return input.replaceAll(RegExp(r'\s+'), ' ').trim();
  }
}
