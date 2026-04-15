import 'dart:async';

import 'package:html/parser.dart' as html_parser;
import 'package:html/dom.dart';
import 'package:http/http.dart' as http;

import '../core/constants/source_constants.dart';
import '../models/menu_models.dart';

class MenuScraperService {
  Future<WeeklyMenu> fetchCurrentWeek() async {
    final primaryEntries = await _fetchFromUrl(SourceConstants.primaryUrl);
    if (primaryEntries.isNotEmpty) {
      return WeeklyMenu(entries: primaryEntries);
    }

    if (SourceConstants.secondaryUrl.isNotEmpty &&
        SourceConstants.secondaryUrl != SourceConstants.primaryUrl) {
      final secondaryEntries = await _fetchFromUrl(SourceConstants.secondaryUrl);
      if (secondaryEntries.isNotEmpty) {
        return WeeklyMenu(entries: secondaryEntries);
      }
    }

    throw Exception('식단 정보를 파싱하지 못했습니다.');
  }

  Future<List<DailyMenu>> _fetchFromUrl(String url) async {
    final response = await http
        .get(Uri.parse(url))
        .timeout(const Duration(seconds: 10));

    if (response.statusCode < 200 || response.statusCode >= 300) {
      throw Exception('식단 페이지 요청에 실패했습니다.');
    }

    final document = html_parser.parse(response.body);
    final entries = _parseDocument(document);
    return _deduplicate(entries);
  }

  List<DailyMenu> _parseDocument(Document document) {
    final parsed = <DailyMenu>[];

    parsed.addAll(_parseTableRows(document));
    if (parsed.isNotEmpty) {
      return parsed;
    }

    parsed.addAll(_parseDefinitionLists(document));
    if (parsed.isNotEmpty) {
      return parsed;
    }

    parsed.addAll(_parseListBlocks(document));
    return parsed;
  }

  List<DailyMenu> _parseTableRows(Document document) {
    final rows = document.querySelectorAll('table tbody tr, table tr');
    final parsed = <DailyMenu>[];
    String currentDate = '';
    String currentRestaurant = '';

    for (final row in rows) {
      final cells = row.querySelectorAll('th, td');
      if (cells.isEmpty) {
        continue;
      }

      final texts = cells
          .map((cell) => _cleanText(cell.text))
          .where((text) => text.isNotEmpty)
          .toList();

      if (texts.isEmpty) {
        continue;
      }

      final detectedDate = texts.cast<String?>().firstWhere(
            (text) => text != null && _looksLikeDate(text),
            orElse: () => null,
          );
      if (detectedDate != null) {
        currentDate = _normalizeDate(detectedDate);
      }

      final detectedSection = texts.cast<String?>().firstWhere(
            (text) => text != null && _looksLikeSection(text),
            orElse: () => null,
          );

      final detectedRestaurant = texts.cast<String?>().firstWhere(
            (text) =>
                text != null &&
                !_looksLikeDate(text) &&
                !_looksLikeSection(text) &&
                _looksLikeRestaurant(text),
            orElse: () => null,
          );
      if (detectedRestaurant != null) {
        currentRestaurant = detectedRestaurant;
      }

      final menuSource = texts.where((text) {
        return !_looksLikeDate(text) &&
            !_looksLikeSection(text) &&
            text != currentRestaurant;
      }).join(' ');

      final items = _splitMenuItems(menuSource);
      if (currentDate.isEmpty || detectedSection == null || items.isEmpty) {
        continue;
      }

      parsed.add(DailyMenu(
        date: currentDate,
        restaurant: currentRestaurant.isEmpty ? '학생식당' : currentRestaurant,
        section: _normalizeSection(detectedSection),
        items: items,
      ));
    }

    return parsed;
  }

  List<DailyMenu> _parseDefinitionLists(Document document) {
    final blocks = document.querySelectorAll('dl');
    final parsed = <DailyMenu>[];

    for (final block in blocks) {
      final title = _cleanText(block.querySelector('dt')?.text ?? '');
      final body = _cleanText(block.querySelector('dd')?.text ?? block.text);
      final combined = _cleanText('$title $body');
      final entry = _buildEntryFromText(combined);
      if (entry != null) {
        parsed.add(entry);
      }
    }

    return parsed;
  }

  List<DailyMenu> _parseListBlocks(Document document) {
    final items = document.querySelectorAll('ul li, ol li');
    final parsed = <DailyMenu>[];

    for (final item in items) {
      final combined = _cleanText(item.text);
      final entry = _buildEntryFromText(combined);
      if (entry != null) {
        parsed.add(entry);
      }
    }

    return parsed;
  }

  DailyMenu? _buildEntryFromText(String text) {
    if (text.isEmpty || !_looksLikeDate(text) || !_looksLikeSection(text)) {
      return null;
    }

    final dateMatch = RegExp(r'(월|화|수|목|금|토|일)(요일)?').firstMatch(text);
    final sectionMatch = RegExp(r'(조식|중식|석식)').firstMatch(text);

    if (dateMatch == null || sectionMatch == null) {
      return null;
    }

    final date = _normalizeDate(dateMatch.group(0)!);
    final section = _normalizeSection(sectionMatch.group(0)!);
    final restaurant = _extractRestaurant(text);
    final menuText = text
        .replaceFirst(dateMatch.group(0)!, ' ')
        .replaceFirst(sectionMatch.group(0)!, ' ')
        .replaceAll(restaurant, ' ');
    final items = _splitMenuItems(menuText);

    if (items.isEmpty) {
      return null;
    }

    return DailyMenu(
      date: date,
      restaurant: restaurant.isEmpty ? '학생식당' : restaurant,
      section: section,
      items: items,
    );
  }

  String _extractRestaurant(String text) {
    final restaurantMatch = RegExp(
      r'(학생식당|천지관|백록관|교직원식당|생활관식당|기숙사식당)',
    ).firstMatch(text);
    return _cleanText(restaurantMatch?.group(0) ?? '');
  }

  List<DailyMenu> _deduplicate(List<DailyMenu> entries) {
    final seen = <String>{};
    final result = <DailyMenu>[];

    for (final entry in entries) {
      final key = '${entry.date}|${entry.restaurant}|${entry.section}|${entry.items.join(',')}';
      if (seen.add(key)) {
        result.add(entry);
      }
    }

    return result;
  }

  List<String> _splitMenuItems(String raw) {
    final normalized = raw
        .replaceAll(RegExp(r'\s+'), ' ')
        .replaceAll('·', '|')
        .replaceAll('/', '|')
        .replaceAll(',', '|')
        .replaceAll(' / ', '|')
        .replaceAll(' | ', '|')
        .trim();

    return normalized
        .split(RegExp(r'[|\n]'))
        .map(_cleanText)
        .where((item) =>
            item.isNotEmpty &&
            !_looksLikeDate(item) &&
            !_looksLikeSection(item) &&
            !_looksLikeRestaurant(item))
        .toList();
  }

  String _normalizeDate(String raw) {
    final text = _cleanText(raw);
    if (text.contains('월')) return '월요일';
    if (text.contains('화')) return '화요일';
    if (text.contains('수')) return '수요일';
    if (text.contains('목')) return '목요일';
    if (text.contains('금')) return '금요일';
    if (text.contains('토')) return '토요일';
    if (text.contains('일')) return '일요일';
    return text;
  }

  String _normalizeSection(String raw) {
    final text = _cleanText(raw);
    if (text.contains('조식')) return '조식';
    if (text.contains('중식')) return '중식';
    if (text.contains('석식')) return '석식';
    return text;
  }

  bool _looksLikeDate(String text) {
    return RegExp(r'(월|화|수|목|금|토|일)(요일)?').hasMatch(text);
  }

  bool _looksLikeSection(String text) {
    return RegExp(r'(조식|중식|석식)').hasMatch(text);
  }

  bool _looksLikeRestaurant(String text) {
    return RegExp(r'(식당|천지관|백록관|생활관|기숙사)').hasMatch(text);
  }

  String _cleanText(String text) {
    return text.replaceAll(RegExp(r'\s+'), ' ').trim();
  }
}
