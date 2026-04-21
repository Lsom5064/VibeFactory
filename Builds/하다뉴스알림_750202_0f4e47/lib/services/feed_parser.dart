import 'package:html/parser.dart' as html_parser;

import '../models/article_item.dart';

class FeedParser {
  List<Map<String, String>> parseRss(String body, String sourceUrl) {
    final document = html_parser.parse(body);
    final items = document.querySelectorAll('item');
    return items
        .map(
          (item) => {
            'title': item.querySelector('title')?.text.trim() ?? '',
            'link': item.querySelector('link')?.text.trim() ?? '',
            'summary': item.querySelector('description')?.text.trim() ?? '',
            'sort_key': item.querySelector('pubDate')?.text.trim() ?? '',
            'source_url': sourceUrl,
          },
        )
        .toList();
  }

  List<Map<String, String>> parseAtom(String body, String sourceUrl) {
    final document = html_parser.parse(body);
    final entries = document.querySelectorAll('entry');
    return entries
        .map(
          (entry) => {
            'title': entry.querySelector('title')?.text.trim() ?? '',
            'link': entry.querySelector('link[href]')?.attributes['href']?.trim() ?? '',
            'summary': entry.querySelector('summary')?.text.trim() ??
                entry.querySelector('content')?.text.trim() ??
                '',
            'sort_key': entry.querySelector('updated')?.text.trim() ??
                entry.querySelector('published')?.text.trim() ??
                '',
            'source_url': sourceUrl,
          },
        )
        .toList();
  }

  List<Map<String, String>> parseHtmlList(String body, String sourceUrl) {
    final document = html_parser.parse(body);
    final records = <Map<String, String>>[];
    final anchors = document.querySelectorAll('a[href^="/topic/"]');

    for (final anchor in anchors) {
      final title = anchor.text.trim();
      final href = anchor.attributes['href']?.trim() ?? '';
      if (title.isEmpty || href.isEmpty) {
        continue;
      }

      final absoluteLink = href.startsWith('http')
          ? href
          : 'https://news.hada.io$href';
      final parentText = anchor.parent?.parent?.text ?? anchor.parent?.text ?? '';
      final summary = _extractSummary(parentText, title);
      final sortKey = _extractSortKey(parentText);

      records.add({
        'title': title,
        'link': absoluteLink,
        'summary': summary,
        'sort_key': sortKey,
        'source_url': sourceUrl,
      });
    }

    return records;
  }

  List<ArticleItem> normalizeRecords(
    List<Map<String, String>> records,
    String sourceUrl,
  ) {
    final fetchedAt = DateTime.now().toUtc().toIso8601String();
    return records
        .map((record) {
          final title = (record['title'] ?? '').trim();
          final link = _normalizeLink(record['link'] ?? '');
          final sortKey = (record['sort_key'] ?? '').trim();
          final fallbackId = link.isNotEmpty ? link : sortKey;
          return ArticleItem(
            id: fallbackId,
            title: title,
            link: link,
            sortKey: sortKey,
            summary: (record['summary'] ?? '').trim(),
            sourceUrl: sourceUrl,
            fetchedAt: fetchedAt,
            isRead: false,
          );
        })
        .toList();
  }

  List<ArticleItem> validateRecords(List<ArticleItem> records) {
    final seen = <String>{};
    final valid = <ArticleItem>[];

    for (final item in records) {
      if (item.title.trim().isEmpty) {
        continue;
      }
      if (item.link.trim().isEmpty) {
        continue;
      }
      if (item.id.trim().isEmpty && item.sortKey.trim().isEmpty) {
        continue;
      }
      final id = item.id.trim().isNotEmpty ? item.id.trim() : item.sortKey.trim();
      if (seen.contains(id)) {
        continue;
      }
      seen.add(id);
      valid.add(item.copyWith(id: id));
    }

    valid.sort((a, b) => b.sortKey.compareTo(a.sortKey));
    return valid;
  }

  String _normalizeLink(String raw) {
    final trimmed = raw.trim();
    if (trimmed.isEmpty) {
      return '';
    }
    if (trimmed.startsWith('http://') || trimmed.startsWith('https://')) {
      return trimmed;
    }
    if (trimmed.startsWith('/')) {
      return 'https://news.hada.io$trimmed';
    }
    return 'https://news.hada.io/$trimmed';
  }

  String _extractSummary(String text, String title) {
    final normalized = text.replaceAll(RegExp(r'\s+'), ' ').trim();
    if (normalized.isEmpty) {
      return '';
    }
    final withoutTitle = normalized.replaceFirst(title, '').trim();
    final summary = withoutTitle.length > 180
        ? withoutTitle.substring(0, 180)
        : withoutTitle;
    return summary;
  }

  String _extractSortKey(String text) {
    final normalized = text.replaceAll(RegExp(r'\s+'), ' ').trim();
    final match = RegExp(r'(\d+\s*(분|시간|일)전)').firstMatch(normalized);
    if (match != null) {
      return match.group(1) ?? normalized;
    }
    return normalized;
  }
}
