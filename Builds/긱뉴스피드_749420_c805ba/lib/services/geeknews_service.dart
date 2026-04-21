import 'dart:io';

import 'package:html/parser.dart' as html_parser;
import 'package:http/http.dart' as http;

import '../models/feed_item.dart';
import '../models/sync_metadata.dart';

class GeekNewsFetchResult {
  const GeekNewsFetchResult({
    required this.items,
    required this.metadata,
    required this.sampleCount,
  });

  final List<FeedItem> items;
  final SyncMetadata metadata;
  final int sampleCount;
}

class GeekNewsService {
  static const String primaryUrl = 'https://news.hada.io/new';
  static const List<String> candidateUrls = <String>[
    'https://news.hada.io/new',
    'https://news.hada.io/',
  ];
  static const String parserStrategy = 'latest_posts_list_parser_with_link_extraction';

  Future<GeekNewsFetchResult> fetchLatestFeed() async {
    Object? lastError;

    for (final url in candidateUrls) {
      try {
        final response = await http.get(Uri.parse(url));
        if (response.statusCode < 200 || response.statusCode >= 300) {
          lastError = HttpException('비정상 응답 코드: ${response.statusCode}');
          continue;
        }

        final body = response.body;
        final finalUrl = response.request?.url.toString() ?? url;
        if (!finalUrl.contains('news.hada.io') || !body.contains('GeekNews')) {
          lastError = Exception('선택된 소스가 GeekNews 최신 글 목록이 아닙니다.');
          continue;
        }
        if (body.contains('마이그레이션') || body.contains('migration')) {
          lastError = Exception('소스에 마이그레이션 또는 구조 변경 공지가 감지되었습니다.');
          continue;
        }

        final items = parseHtmlToFeedItems(body, fetchedAtIso: DateTime.now().toIso8601String());
        if (items.isEmpty) {
          lastError = Exception('유효한 최신 글 레코드를 찾지 못했습니다. 구조 변경 가능성이 있습니다.');
          continue;
        }

        final metadata = SyncMetadata(
          selectedSourceUrl: finalUrl,
          parserStrategy: parserStrategy,
          lastSyncAt: DateTime.now().toIso8601String(),
          syncSuccess: true,
          errorMessage: items.length < 10 ? '유효 항목 ${items.length}개를 확보했습니다. 목표 10개 이상을 계속 시도할 수 있습니다.' : null,
        );

        return GeekNewsFetchResult(
          items: items,
          metadata: metadata,
          sampleCount: items.length,
        );
      } catch (error) {
        lastError = error;
      }
    }

    throw Exception('긱뉴스 최신 피드를 불러오지 못했습니다. ${lastError ?? ''}'.trim());
  }

  List<FeedItem> parseHtmlToFeedItems(String html, {required String fetchedAtIso}) {
    final document = html_parser.parse(html);
    final anchors = document.querySelectorAll('a');
    final seen = <String>{};
    final items = <FeedItem>[];

    for (final anchor in anchors) {
      final href = anchor.attributes['href']?.trim();
      final text = anchor.text.replaceAll(RegExp(r'\s+'), ' ').trim();
      if (href == null || href.isEmpty || text.isEmpty) {
        continue;
      }
      if (text == 'GeekNews' || text == '최신글' || text == '예전글' || text == '댓글') {
        continue;
      }

      Uri? uri;
      try {
        uri = Uri.parse(href);
      } catch (_) {
        continue;
      }
      if (!uri.hasScheme) {
        uri = Uri.parse('https://news.hada.io/').resolveUri(uri);
      }
      if (!(uri.scheme == 'http' || uri.scheme == 'https')) {
        continue;
      }
      if (uri.host == 'news.hada.io' && !uri.path.startsWith('/topic')) {
        continue;
      }

      final parentText = anchor.parent?.text.replaceAll(RegExp(r'\s+'), ' ').trim() ?? '';
      final metaMatch = RegExp(r'(\d+\s*points?.*?|\d+시간전.*?|\d+일전.*?)($|\|)').firstMatch(parentText);
      final key = '$text|${uri.toString()}';
      if (seen.contains(key)) {
        continue;
      }
      seen.add(key);
      items.add(
        FeedItem(
          title: text,
          postLink: uri.toString(),
          timeOrScore: metaMatch?.group(1)?.trim(),
          fetchedAt: fetchedAtIso,
          sortOrder: items.length,
          cacheStatus: 'cached',
        ),
      );
      if (items.length >= 30) {
        break;
      }
    }

    return items;
  }
}
