import 'dart:convert';

import 'package:html/parser.dart' as html_parser;
import 'package:http/http.dart' as http;
import 'package:xml/xml.dart';

import '../models/feed_item.dart';
import '../models/sync_status.dart';
import 'crash_handler.dart';

class FeedSyncResult {
  final List<FeedItem> items;
  final SyncStatus syncStatus;
  final bool usedCache;
  final bool success;
  final String? message;
  final bool hasNewItem;
  final FeedItem? latestItem;

  const FeedSyncResult({
    required this.items,
    required this.syncStatus,
    required this.usedCache,
    required this.success,
    required this.message,
    required this.hasNewItem,
    required this.latestItem,
  });
}

class ProbeAndFetchResult {
  final List<FeedItem> items;
  final String parserStrategy;
  final String sourceUrl;

  const ProbeAndFetchResult({
    required this.items,
    required this.parserStrategy,
    required this.sourceUrl,
  });
}

class FeedService {
  static const String primaryUrl = 'https://news.hada.io/';
  static const String rssUrl = 'https://news.hada.io/rss';
  static const String rssNewsUrl = 'https://news.hada.io/rss/news';
  static const Duration timeout = Duration(seconds: 15);
  static const int minimumRecords = 10;
  static const int ttlMinutes = 60;

  Future<FeedSyncResult> syncFeed({
    required List<FeedItem> existingCache,
    required SyncStatus previousStatus,
    required bool forceRefresh,
  }) async {
    try {
      final shouldUseCache =
          !forceRefresh &&
          previousStatus.lastSuccessfulSyncAt != null &&
          DateTime.now().difference(previousStatus.lastSuccessfulSyncAt!).inMinutes <
              ttlMinutes &&
          existingCache.isNotEmpty;

      if (shouldUseCache) {
        return FeedSyncResult(
          items: existingCache,
          syncStatus: previousStatus,
          usedCache: true,
          success: true,
          message: '최근 동기화된 캐시를 표시합니다.',
          hasNewItem: false,
          latestItem: existingCache.isNotEmpty ? existingCache.first : null,
        );
      }

      final probe = await probeAndFetch();
      final latestItem = probe.items.isNotEmpty ? probe.items.first : null;
      final hasNewItem = detectNewItem(
        previousLatestItemId: previousStatus.lastCheckedLatestItemId,
        currentLatestItemId: latestItem?.publishedAtOrId,
      );

      final syncStatus = previousStatus.copyWith(
        lastSuccessfulSyncAt: DateTime.now(),
        lastCheckedLatestItemId:
            latestItem?.publishedAtOrId ?? previousStatus.lastCheckedLatestItemId,
        currentSourceKind: 'api_or_rss_or_web',
        sourceUrl: probe.sourceUrl,
        parserStrategy: probe.parserStrategy,
        clearError: true,
      );

      return FeedSyncResult(
        items: probe.items,
        syncStatus: syncStatus,
        usedCache: false,
        success: true,
        message: probe.items.isEmpty ? '최신 글이 없습니다.' : null,
        hasNewItem: hasNewItem,
        latestItem: latestItem,
      );
    } catch (error, stackTrace) {
      CrashHandler.recordError(
        error,
        stackTrace,
        context: 'FeedService.syncFeed',
      );
      return FeedSyncResult(
        items: existingCache,
        syncStatus: previousStatus.copyWith(
          currentSourceKind: 'api_or_rss_or_web',
          sourceUrl: primaryUrl,
          parserStrategy: previousStatus.parserStrategy,
          errorState: '최신 데이터를 가져오지 못했습니다.',
        ),
        usedCache: existingCache.isNotEmpty,
        success: false,
        message: existingCache.isNotEmpty
            ? '원격 조회에 실패하여 최근 캐시를 표시합니다.'
            : '데이터 소스 확인에 실패했습니다.',
        hasNewItem: false,
        latestItem: existingCache.isNotEmpty ? existingCache.first : null,
      );
    }
  }

  Future<ProbeAndFetchResult> probeAndFetch() async {
    final jsonItems = await tryJsonApi();
    if (jsonItems != null) {
      return ProbeAndFetchResult(
        items: jsonItems,
        parserStrategy: 'json_api',
        sourceUrl: primaryUrl,
      );
    }

    final rssItems = await tryRssXml();
    if (rssItems != null) {
      return ProbeAndFetchResult(
        items: rssItems,
        parserStrategy: 'rss_xml',
        sourceUrl: rssNewsUrl,
      );
    }

    final htmlItems = await tryHtmlListParsing();
    if (htmlItems != null) {
      return ProbeAndFetchResult(
        items: htmlItems,
        parserStrategy: 'html_list_parsing',
        sourceUrl: primaryUrl,
      );
    }

    throw Exception('모든 소스 전략이 실패했습니다.');
  }

  Future<List<FeedItem>?> tryJsonApi() async {
    try {
      final response = await http.get(Uri.parse(primaryUrl)).timeout(timeout);
      if (response.statusCode != 200) {
        return null;
      }
      final contentType = response.headers['content-type'] ?? '';
      if (!contentType.contains('application/json')) {
        return null;
      }
      final decoded = jsonDecode(response.body);
      if (decoded is! Map<String, dynamic>) {
        return null;
      }
      final records = decoded['items'];
      if (records is! List) {
        return null;
      }
      final now = DateTime.now();
      final items = records.map((record) {
        final map = record as Map<String, dynamic>;
        return FeedItem(
          title: (map['title'] as String? ?? '').trim(),
          linkUrl: map['url'] as String? ?? '',
          publishedAtOrId: (map['id'] ?? map['published_at'] ?? '').toString(),
          fetchedAt: now,
        );
      }).toList();
      return validateRecords(items);
    } catch (error, stackTrace) {
      CrashHandler.recordError(
        error,
        stackTrace,
        context: 'FeedService.tryJsonApi',
      );
      return null;
    }
  }

  Future<List<FeedItem>?> tryRssXml() async {
    try {
      http.Response response;
      try {
        response = await http.get(Uri.parse(rssUrl)).timeout(timeout);
      } catch (_) {
        response = await http.get(Uri.parse(rssNewsUrl)).timeout(timeout);
      }

      if (response.statusCode != 200) {
        return null;
      }

      final body = response.body;
      if (_hasMigrationNotice(body)) {
        return null;
      }

      final document = XmlDocument.parse(body);
      final itemNodes = document.findAllElements('item').toList();
      final now = DateTime.now();
      final items = itemNodes.map((node) {
        final title = node.getElement('title')?.innerText.trim() ?? '';
        final link = node.getElement('link')?.innerText.trim() ?? '';
        final guid = node.getElement('guid')?.innerText.trim() ?? '';
        final pubDate = node.getElement('pubDate')?.innerText.trim() ?? '';
        return FeedItem(
          title: title,
          linkUrl: _normalizeAbsoluteUrl(link),
          publishedAtOrId: pubDate.isNotEmpty ? pubDate : guid,
          fetchedAt: now,
        );
      }).toList();
      return validateRecords(items);
    } catch (error, stackTrace) {
      CrashHandler.recordError(
        error,
        stackTrace,
        context: 'FeedService.tryRssXml',
      );
      return null;
    }
  }

  Future<List<FeedItem>?> tryHtmlListParsing() async {
    try {
      final response = await http.get(Uri.parse(primaryUrl)).timeout(timeout);
      if (response.statusCode != 200) {
        return null;
      }
      final body = response.body;
      if (_hasMigrationNotice(body)) {
        return null;
      }

      final document = html_parser.parse(body);
      final links = document.querySelectorAll('a[href*="/topic?id="]');
      final now = DateTime.now();
      final items = <FeedItem>[];
      final seen = <String>{};

      for (final linkElement in links) {
        final href = linkElement.attributes['href'] ?? '';
        final title = linkElement.text.trim();
        final absoluteUrl = _normalizeAbsoluteUrl(href);
        final id = Uri.tryParse(absoluteUrl)?.queryParameters['id'] ?? '';
        final key = '$absoluteUrl|$id';
        if (seen.contains(key)) {
          continue;
        }
        seen.add(key);
        items.add(
          FeedItem(
            title: title,
            linkUrl: absoluteUrl,
            publishedAtOrId: id,
            fetchedAt: now,
          ),
        );
      }

      return validateRecords(items);
    } catch (error, stackTrace) {
      CrashHandler.recordError(
        error,
        stackTrace,
        context: 'FeedService.tryHtmlListParsing',
      );
      return null;
    }
  }

  List<FeedItem>? validateRecords(List<FeedItem> items) {
    final deduped = <String, FeedItem>{};
    for (final item in items) {
      final normalized = item.copyWith(
        title: item.title.trim(),
        linkUrl: _normalizeAbsoluteUrl(item.linkUrl),
        publishedAtOrId: item.publishedAtOrId.trim(),
      );
      if (normalized.title.isEmpty ||
          normalized.linkUrl.isEmpty ||
          normalized.publishedAtOrId.isEmpty) {
        continue;
      }
      deduped[normalized.cacheKey] = normalized;
    }

    final validated = deduped.values.toList()
      ..sort((a, b) => b.publishedAtOrId.compareTo(a.publishedAtOrId));

    if (validated.length < minimumRecords) {
      return null;
    }
    return validated;
  }

  bool detectNewItem({
    required String? previousLatestItemId,
    required String? currentLatestItemId,
  }) {
    if (previousLatestItemId == null || previousLatestItemId.isEmpty) {
      return false;
    }
    if (currentLatestItemId == null || currentLatestItemId.isEmpty) {
      return false;
    }
    return previousLatestItemId != currentLatestItemId;
  }

  bool _hasMigrationNotice(String body) {
    final lower = body.toLowerCase();
    return lower.contains('moved permanently') ||
        lower.contains('migration') ||
        lower.contains('이전') ||
        lower.contains('migrat');
  }

  String _normalizeAbsoluteUrl(String value) {
    final trimmed = value.trim();
    if (trimmed.isEmpty) {
      return '';
    }
    final uri = Uri.tryParse(trimmed);
    if (uri != null && uri.hasScheme) {
      return uri.toString();
    }
    return Uri.parse(primaryUrl).resolve(trimmed).toString();
  }
}
