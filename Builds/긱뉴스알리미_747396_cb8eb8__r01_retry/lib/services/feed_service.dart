import 'dart:convert';
import 'dart:io';

import 'package:html/dom.dart';
import 'package:html/parser.dart' as html_parser;
import 'package:http/http.dart' as http;

import '../models/feed_item.dart';
import '../models/sync_status.dart';
import 'crash_handler.dart';

class FeedService {
  FeedService({http.Client? client}) : _client = client ?? http.Client();

  final http.Client _client;

  static const String primaryUrl = 'https://news.hada.io/';
  static const List<String> candidateUrls = <String>[
    'https://news.hada.io/',
    'https://news.hada.io/rss',
  ];

  static const List<String> _jsonApiCandidates = <String>[
    'https://news.hada.io/api',
    'https://news.hada.io/api/feed',
    'https://news.hada.io/feed.json',
    'https://news.hada.io/rss.json',
  ];

  Future<FeedSyncResult> syncFeed({
    required List<FeedItem> existingCache,
    required SyncStatus previousStatus,
    required bool forceRefresh,
  }) async {
    try {
      final ProbeAndFetchResult fetched = await probeAndFetch();
      final List<FeedItem> items = fetched.items;
      final FeedItem? latestItem = items.isNotEmpty ? items.first : null;
      final String latestKey = latestItem?.publishedAtOrId.trim().isNotEmpty == true
          ? latestItem!.publishedAtOrId.trim()
          : (latestItem?.linkUrl ?? '');
      final String previousKey = existingCache.isNotEmpty
          ? (existingCache.first.publishedAtOrId.trim().isNotEmpty
              ? existingCache.first.publishedAtOrId.trim()
              : existingCache.first.linkUrl)
          : '';
      final bool hasNewItem = latestKey.isNotEmpty && latestKey != previousKey;

      final SyncStatus nextStatus = previousStatus.copyWith(
        lastSuccessfulSyncAt: DateTime.now().toUtc(),
        sourceUrl: fetched.sourceUrl,
        parserStrategy: fetched.parserStrategy,
        currentSourceKind: fetched.sourceKind,
        errorState: null,
      );

      return FeedSyncResult(
        success: true,
        usedCache: false,
        hasNewItem: hasNewItem,
        latestItem: latestItem,
        items: items,
        syncStatus: nextStatus,
        message: items.isEmpty ? '가져오기는 성공했지만 표시할 항목이 없습니다.' : null,
      );
    } catch (error, stackTrace) {
      CrashHandler.recordError(
        error,
        stackTrace,
        context: 'FeedService.syncFeed',
      );

      final String message = existingCache.isNotEmpty
          ? '최신 데이터를 가져오지 못해 최근 캐시를 표시합니다.'
          : '데이터 소스 확인에 실패했습니다.';

      return FeedSyncResult(
        success: false,
        usedCache: existingCache.isNotEmpty,
        hasNewItem: false,
        latestItem: existingCache.isNotEmpty ? existingCache.first : null,
        items: existingCache,
        syncStatus: previousStatus.copyWith(
          errorState: message,
        ),
        message: message,
      );
    }
  }

  Future<ProbeAndFetchResult> probeAndFetch() async {
    try {
      final List<FeedItem>? htmlItems = await tryHtmlListParsing();
      if (htmlItems != null && htmlItems.length >= 10) {
        final List<FeedItem> validated = _validateRecords(htmlItems);
        if (validated.length >= 10) {
          return ProbeAndFetchResult(
            items: validated,
            sourceUrl: primaryUrl,
            parserStrategy: 'html_list_parsing',
            sourceKind: 'public_web_page',
          );
        }
      }
    } catch (error, stackTrace) {
      CrashHandler.recordError(
        error,
        stackTrace,
        context: 'HTML 목록 파싱 실패',
      );
    }

    try {
      final _JsonProbeResult? jsonResult = await _tryJsonApi();
      if (jsonResult != null && jsonResult.items.length >= 10) {
        final List<FeedItem> validated = _validateRecords(jsonResult.items);
        if (validated.length >= 10) {
          return ProbeAndFetchResult(
            items: validated,
            sourceUrl: jsonResult.sourceUrl,
            parserStrategy: 'json_api',
            sourceKind: 'public_api',
          );
        }
      }
    } catch (error, stackTrace) {
      CrashHandler.recordError(
        error,
        stackTrace,
        context: 'JSON 피드 탐색 실패',
      );
    }

    try {
      final List<FeedItem>? rssItems = await tryRssXml();
      if (rssItems != null && rssItems.length >= 10) {
        final List<FeedItem> validated = _validateRecords(rssItems);
        if (validated.length >= 10) {
          return ProbeAndFetchResult(
            items: validated,
            sourceUrl: 'https://news.hada.io/rss',
            parserStrategy: 'rss_xml',
            sourceKind: 'rss_feed',
          );
        }
      }
    } catch (error, stackTrace) {
      CrashHandler.recordError(
        error,
        stackTrace,
        context: 'RSS 피드 파싱 실패',
      );
    }

    throw const FeedFetchException('피드 데이터를 확인할 수 없습니다. 잠시 후 다시 시도해 주세요.');
  }

  Future<_JsonProbeResult?> _tryJsonApi() async {
    for (final String url in _jsonApiCandidates) {
      try {
        final http.Response response = await _client.get(
          Uri.parse(url),
          headers: <String, String>{
            HttpHeaders.acceptHeader: 'application/json, text/plain;q=0.9, */*;q=0.8',
          },
        );

        if (response.statusCode != 200) {
          continue;
        }

        final String body = response.body.trim();
        if (body.isEmpty) {
          continue;
        }

        final String contentType = response.headers[HttpHeaders.contentTypeHeader] ?? '';
        final bool looksLikeJson = contentType.contains('application/json') ||
            body.startsWith('{') ||
            body.startsWith('[');
        if (!looksLikeJson) {
          continue;
        }

        final dynamic decoded = jsonDecode(body);
        final List<dynamic> rawItems = _extractJsonItemList(decoded);
        if (rawItems.isEmpty) {
          continue;
        }

        final List<FeedItem> items = <FeedItem>[];
        for (final dynamic raw in rawItems) {
          if (raw is! Map) {
            continue;
          }
          final Map<String, dynamic> map = raw.map(
            (dynamic key, dynamic value) => MapEntry(key.toString(), value),
          );
          final FeedItem? item = _feedItemFromJsonMap(map);
          if (item != null) {
            items.add(item);
          }
        }

        final List<FeedItem> validated = _validateRecords(items);
        if (validated.length >= 10) {
          return _JsonProbeResult(sourceUrl: url, items: validated);
        }
      } catch (_) {
        continue;
      }
    }
    return null;
  }

  List<dynamic> _extractJsonItemList(dynamic decoded) {
    if (decoded is List) {
      return decoded;
    }
    if (decoded is! Map) {
      return const <dynamic>[];
    }

    final Map<String, dynamic> map = decoded.map(
      (dynamic key, dynamic value) => MapEntry(key.toString(), value),
    );

    const List<String> directKeys = <String>[
      'items',
      'list',
      'data',
      'results',
      'posts',
      'news',
    ];
    for (final String key in directKeys) {
      final dynamic value = map[key];
      if (value is List) {
        return value;
      }
      if (value is Map) {
        final List<dynamic> nested = _extractJsonItemList(value);
        if (nested.isNotEmpty) {
          return nested;
        }
      }
    }

    final dynamic channel = map['channel'];
    if (channel is Map) {
      final dynamic item = channel['item'];
      if (item is List) {
        return item;
      }
    }

    return const <dynamic>[];
  }

  FeedItem? _feedItemFromJsonMap(Map<String, dynamic> map) {
    final String title = _firstNonEmptyString(<dynamic>[
      map['title'],
      map['name'],
      map['subject'],
    ]);
    final String linkUrl = _normalizeUrl(_firstNonEmptyString(<dynamic>[
      map['link'],
      map['url'],
      map['href'],
      map['permalink'],
    ]));
    final String publishedAtOrId = _firstNonEmptyString(<dynamic>[
      map['published_at'],
      map['pubDate'],
      map['created_at'],
      map['date'],
      map['updated_at'],
      map['id'],
      map['guid'],
      map['uuid'],
      map['slug'],
    ]);

    if (title.isEmpty || linkUrl.isEmpty) {
      return null;
    }

    return FeedItem(
      title: title,
      linkUrl: linkUrl,
      publishedAtOrId: publishedAtOrId.isNotEmpty ? publishedAtOrId : linkUrl,
      fetchedAt: DateTime.now().toUtc(),
    );
  }

  Future<List<FeedItem>?> tryRssXml() async {
    for (final String url in <String>['https://news.hada.io/rss', 'https://news.hada.io/rss/news']) {
      try {
        final http.Response response = await _client.get(Uri.parse(url));
        if (response.statusCode != 200) {
          continue;
        }

        final String body = response.body;
        final String contentType = response.headers[HttpHeaders.contentTypeHeader] ?? '';
        final bool looksLikeXml = contentType.contains('xml') ||
            contentType.contains('rss') ||
            contentType.contains('atom') ||
            body.contains('<rss') ||
            body.contains('<feed') ||
            body.contains('<item>');
        if (!looksLikeXml) {
          continue;
        }

        if (_containsMigrationNotice(body)) {
          continue;
        }

        final List<FeedItem> items = _parseRssItems(body);
        final List<FeedItem> validated = _validateRecords(items);
        if (validated.length >= 10) {
          return validated;
        }
      } catch (error, stackTrace) {
        CrashHandler.recordError(
          error,
          stackTrace,
          context: 'RSS 후보 파싱 실패: $url',
        );
      }
    }
    return null;
  }

  Future<List<FeedItem>?> tryHtmlListParsing() async {
    final http.Response response = await _client.get(
      Uri.parse(primaryUrl),
      headers: <String, String>{
        HttpHeaders.acceptHeader: 'text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8',
        HttpHeaders.userAgentHeader:
            'Mozilla/5.0 (Android; Mobile; rv:124.0) Gecko/124.0 Firefox/124.0',
        HttpHeaders.cacheControlHeader: 'no-cache',
      },
    );
    if (response.statusCode != 200) {
      return null;
    }

    final String body = response.body;
    if (body.trim().isEmpty || _containsBlockingMigrationNotice(body)) {
      return null;
    }

    final Document document = html_parser.parse(body);

    final List<FeedItem> directAnchorItems = _parseDirectArticleAnchors(document);
    final List<FeedItem> validatedDirect = _validateRecords(directAnchorItems);
    if (validatedDirect.length >= 10) {
      return validatedDirect;
    }

    final List<FeedItem> structuredItems = _parseStructuredHtmlItems(document);
    final List<FeedItem> validatedStructured = _validateRecords(structuredItems);
    if (validatedStructured.length >= 10) {
      return validatedStructured;
    }

    final List<FeedItem> fallbackItems = _parseAnchorFallbackItems(document);
    final List<FeedItem> validatedFallback = _validateRecords(fallbackItems);
    if (validatedFallback.length >= 10) {
      return validatedFallback;
    }

    final List<FeedItem> textPatternItems = _parseTextPatternItems(body);
    final List<FeedItem> validatedTextPattern = _validateRecords(textPatternItems);
    return validatedTextPattern.length >= 10 ? validatedTextPattern : null;
  }

  List<FeedItem> _parseDirectArticleAnchors(Document document) {
    final List<FeedItem> items = <FeedItem>[];
    final Set<String> seenLinks = <String>{};

    for (final Element link in document.querySelectorAll('a[href]')) {
      final String href = _normalizeUrl(link.attributes['href'] ?? '');
      if (!_isExternalArticleUrl(href)) {
        continue;
      }

      final String title = _cleanTitle(link.text);
      if (!_looksLikeFeedTitle(title)) {
        continue;
      }

      final String publishedAtOrId = _extractTopicIdNearElement(link) ?? href;
      if (seenLinks.add(href)) {
        items.add(
          FeedItem(
            title: title,
            linkUrl: href,
            publishedAtOrId: publishedAtOrId,
            fetchedAt: DateTime.now().toUtc(),
          ),
        );
      }
    }

    return items;
  }

  List<FeedItem> _parseStructuredHtmlItems(Document document) {
    final List<FeedItem> items = <FeedItem>[];
    final Set<String> seenKeys = <String>{};

    final List<Element> topicLinks = document.querySelectorAll('a[href*="/topic?id="]');
    for (final Element topicLink in topicLinks) {
      final String topicHref = _normalizeUrl(topicLink.attributes['href'] ?? '');
      final String? topicId = _extractTopicIdFromUrl(topicHref);
      if (topicId == null || topicId.isEmpty) {
        continue;
      }

      final Element? container = _findTopicContainer(topicLink);
      if (container == null) {
        continue;
      }

      final Element? externalLink = _findPrimaryExternalLink(container, topicId);
      if (externalLink == null) {
        continue;
      }

      final String linkUrl = _normalizeUrl(externalLink.attributes['href'] ?? '');
      final String title = _cleanTitle(externalLink.text);
      if (!_isExternalArticleUrl(linkUrl) || !_looksLikeFeedTitle(title)) {
        continue;
      }

      if (seenKeys.add(topicId)) {
        items.add(
          FeedItem(
            title: title,
            linkUrl: linkUrl,
            publishedAtOrId: topicId,
            fetchedAt: DateTime.now().toUtc(),
          ),
        );
      }
    }

    return items;
  }

  Element? _findTopicContainer(Element element) {
    Element? current = element.parent;
    for (int depth = 0; depth < 6 && current != null; depth++) {
      final List<Element> topicLinks = current.querySelectorAll('a[href*="/topic?id="]');
      final List<Element> externalLinks = current.querySelectorAll('a[href]');
      final bool hasTopicLink = topicLinks.isNotEmpty;
      final bool hasExternalLink = externalLinks.any(
        (Element link) => _isExternalArticleUrl(_normalizeUrl(link.attributes['href'] ?? '')),
      );
      if (hasTopicLink && hasExternalLink) {
        return current;
      }
      current = current.parent;
    }
    return null;
  }

  Element? _findPrimaryExternalLink(Element container, String topicId) {
    for (final Element link in container.querySelectorAll('a[href]')) {
      final String href = _normalizeUrl(link.attributes['href'] ?? '');
      final String title = _cleanTitle(link.text);
      if (!_isExternalArticleUrl(href)) {
        continue;
      }
      if (!_looksLikeFeedTitle(title)) {
        continue;
      }
      final String? linkedTopicId = _extractTopicIdNearElement(link);
      if (linkedTopicId == null || linkedTopicId == topicId) {
        return link;
      }
    }
    return null;
  }

  List<FeedItem> _parseAnchorFallbackItems(Document document) {
    final List<FeedItem> items = <FeedItem>[];
    final Set<String> seenLinks = <String>{};

    for (final Element element in document.querySelectorAll('a[href]')) {
      final String href = _normalizeUrl(element.attributes['href'] ?? '');
      if (!_isExternalArticleUrl(href)) {
        continue;
      }

      final String title = _cleanTitle(element.text);
      if (!_looksLikeFeedTitle(title)) {
        continue;
      }

      final String publishedAtOrId = _extractTopicIdNearElement(element) ?? href;
      if (seenLinks.add(href)) {
        items.add(
          FeedItem(
            title: title,
            linkUrl: href,
            publishedAtOrId: publishedAtOrId,
            fetchedAt: DateTime.now().toUtc(),
          ),
        );
      }
    }

    return items;
  }

  List<FeedItem> _parseTextPatternItems(String body) {
    final List<FeedItem> items = <FeedItem>[];
    final Set<String> seenLinks = <String>{};
    final RegExp pattern = RegExp(
      r'(\d+)\s*▲\s*(.+?)\s*\((https?:\/\/[^\s)]+|[^\s)]+\.[^\s)]+)\)',
      multiLine: true,
    );

    for (final RegExpMatch match in pattern.allMatches(_collapseWhitespace(body))) {
      final String topicId = (match.group(1) ?? '').trim();
      final String title = _cleanTitle(match.group(2) ?? '');
      final String rawUrl = (match.group(3) ?? '').trim();
      final String linkUrl = _normalizeUrl(
        rawUrl.startsWith('http://') || rawUrl.startsWith('https://') ? rawUrl : 'https://$rawUrl',
      );

      if (topicId.isEmpty || !_isExternalArticleUrl(linkUrl) || !_looksLikeFeedTitle(title)) {
        continue;
      }

      if (seenLinks.add(linkUrl)) {
        items.add(
          FeedItem(
            title: title,
            linkUrl: linkUrl,
            publishedAtOrId: topicId,
            fetchedAt: DateTime.now().toUtc(),
          ),
        );
      }
    }

    return items;
  }

  List<FeedItem> _parseRssItems(String xml) {
    final List<FeedItem> items = <FeedItem>[];
    final RegExp itemExp = RegExp(r'<item\b[\s\S]*?</item>', caseSensitive: false);
    final Iterable<RegExpMatch> matches = itemExp.allMatches(xml);

    for (final RegExpMatch match in matches) {
      final String block = match.group(0) ?? '';
      final String title = _cleanTitle(_readTag(block, 'title'));
      final String linkUrl = _normalizeUrl(_readTag(block, 'link'));
      final String guid = _readTag(block, 'guid');
      final String pubDate = _readTag(block, 'pubDate');
      if (title.isEmpty || linkUrl.isEmpty) {
        continue;
      }
      items.add(
        FeedItem(
          title: title,
          linkUrl: linkUrl,
          publishedAtOrId: pubDate.isNotEmpty ? pubDate : (guid.isNotEmpty ? guid : linkUrl),
          fetchedAt: DateTime.now().toUtc(),
        ),
      );
    }

    return items;
  }

  List<FeedItem> _validateRecords(List<FeedItem> items) {
    final Map<String, FeedItem> deduped = <String, FeedItem>{};
    for (final FeedItem item in items) {
      final String title = item.title.trim();
      final String linkUrl = _normalizeUrl(item.linkUrl);
      if (title.isEmpty || linkUrl.isEmpty) {
        continue;
      }
      deduped[linkUrl] = FeedItem(
        title: title,
        linkUrl: linkUrl,
        publishedAtOrId: item.publishedAtOrId.trim().isNotEmpty
            ? item.publishedAtOrId.trim()
            : linkUrl,
        fetchedAt: item.fetchedAt,
      );
    }

    final List<FeedItem> validated = deduped.values.toList();
    validated.sort((FeedItem a, FeedItem b) {
      final DateTime? aDate = _tryParseDate(a.publishedAtOrId);
      final DateTime? bDate = _tryParseDate(b.publishedAtOrId);
      if (aDate != null && bDate != null) {
        return bDate.compareTo(aDate);
      }

      final int? aNumeric = int.tryParse(a.publishedAtOrId);
      final int? bNumeric = int.tryParse(b.publishedAtOrId);
      if (aNumeric != null && bNumeric != null) {
        return bNumeric.compareTo(aNumeric);
      }

      return b.publishedAtOrId.compareTo(a.publishedAtOrId);
    });
    return validated;
  }

  DateTime? _tryParseDate(String value) {
    final String trimmed = value.trim();
    if (trimmed.isEmpty) {
      return null;
    }
    try {
      return HttpDate.parse(trimmed).toUtc();
    } catch (_) {
      try {
        return DateTime.parse(trimmed).toUtc();
      } catch (_) {
        return null;
      }
    }
  }

  bool _containsMigrationNotice(String body) {
    final String lower = body.toLowerCase();
    return lower.contains('moved permanently') ||
        lower.contains('temporarily unavailable') ||
        lower.contains('migration') ||
        lower.contains('redirecting') ||
        lower.contains('redirected') ||
        lower.contains('location.href') ||
        lower.contains('window.location') ||
        lower.contains('http-equiv="refresh"') ||
        lower.contains('http-equiv=\'refresh\'') ||
        lower.contains('페이지 이동') ||
        lower.contains('이전되었습니다') ||
        lower.contains('잠시 후 이동') ||
        lower.contains('리다이렉트');
  }

  bool _containsBlockingMigrationNotice(String body) {
    final String lower = body.toLowerCase();
    final bool hasRedirectSignal = lower.contains('http-equiv="refresh"') ||
        lower.contains('http-equiv=\'refresh\'') ||
        lower.contains('window.location') ||
        lower.contains('location.href') ||
        lower.contains('moved permanently') ||
        lower.contains('temporarily unavailable') ||
        lower.contains('redirecting') ||
        lower.contains('redirected') ||
        lower.contains('페이지 이동') ||
        lower.contains('이전되었습니다') ||
        lower.contains('잠시 후 이동') ||
        lower.contains('리다이렉트');
    final bool hasFeedMarkers = lower.contains('geeknews') &&
        (lower.contains('최신글') || lower.contains('/topic?id='));
    return hasRedirectSignal && !hasFeedMarkers;
  }

  String _readTag(String source, String tag) {
    final RegExp exp = RegExp('<$tag\\b[^>]*>([\\s\\S]*?)</$tag>', caseSensitive: false);
    final RegExpMatch? match = exp.firstMatch(source);
    if (match == null) {
      return '';
    }
    return _decodeXml(_stripHtml(match.group(1) ?? '')).trim();
  }

  String _decodeXml(String input) {
    return input
        .replaceAll('&amp;', '&')
        .replaceAll('&lt;', '<')
        .replaceAll('&gt;', '>')
        .replaceAll('&quot;', '"')
        .replaceAll('&#39;', "'")
        .replaceAll('&apos;', "'");
  }

  String _stripHtml(String input) {
    return input.replaceAll(RegExp(r'<[^>]*>'), ' ');
  }

  String _cleanTitle(String input) {
    return _decodeXml(input).replaceAll(RegExp(r'\s+'), ' ').trim();
  }

  String _collapseWhitespace(String input) {
    return input.replaceAll(RegExp(r'<[^>]*>'), ' ').replaceAll(RegExp(r'\s+'), ' ').trim();
  }

  String _normalizeUrl(String input) {
    final String trimmed = input.trim();
    if (trimmed.isEmpty) {
      return '';
    }
    if (trimmed.startsWith('//')) {
      return 'https:$trimmed';
    }
    if (trimmed.startsWith('http://') || trimmed.startsWith('https://')) {
      return trimmed;
    }
    if (trimmed.startsWith('/')) {
      return 'https://news.hada.io$trimmed';
    }
    return trimmed;
  }

  bool _isExternalArticleUrl(String url) {
    if (url.isEmpty) {
      return false;
    }
    if (!url.startsWith('http://') && !url.startsWith('https://')) {
      return false;
    }
    if (url.startsWith('https://news.hada.io/topic') ||
        url.startsWith('http://news.hada.io/topic') ||
        url.startsWith('https://news.hada.io/@') ||
        url.startsWith('http://news.hada.io/@') ||
        url.startsWith('https://news.hada.io/login') ||
        url.startsWith('http://news.hada.io/login') ||
        url.startsWith('https://news.hada.io/write') ||
        url.startsWith('http://news.hada.io/write') ||
        url.startsWith('https://news.hada.io/new') ||
        url.startsWith('http://news.hada.io/new') ||
        url.startsWith('https://news.hada.io/past') ||
        url.startsWith('http://news.hada.io/past') ||
        url.startsWith('https://news.hada.io/comments') ||
        url.startsWith('http://news.hada.io/comments') ||
        url.startsWith('https://news.hada.io/threads') ||
        url.startsWith('http://news.hada.io/threads') ||
        url.startsWith('https://news.hada.io/ask') ||
        url.startsWith('http://news.hada.io/ask') ||
        url.startsWith('https://news.hada.io/show') ||
        url.startsWith('http://news.hada.io/show') ||
        url.startsWith('https://news.hada.io/plus') ||
        url.startsWith('http://news.hada.io/plus') ||
        url.startsWith('https://news.hada.io/weekly') ||
        url.startsWith('http://news.hada.io/weekly') ||
        url.startsWith('https://news.hada.io/search') ||
        url.startsWith('http://news.hada.io/search') ||
        url.startsWith('https://news.hada.io/releases') ||
        url.startsWith('http://news.hada.io/releases') ||
        url.startsWith('https://news.hada.io/blog') ||
        url.startsWith('http://news.hada.io/blog') ||
        url.startsWith('https://news.hada.io/lists') ||
        url.startsWith('http://news.hada.io/lists') ||
        url.startsWith('https://news.hada.io/faq') ||
        url.startsWith('http://news.hada.io/faq') ||
        url.startsWith('https://news.hada.io/about') ||
        url.startsWith('http://news.hada.io/about') ||
        url.startsWith('https://news.hada.io/bookmarklet') ||
        url.startsWith('http://news.hada.io/bookmarklet') ||
        url.startsWith('https://twitter.com/') ||
        url.startsWith('https://x.com/') ||
        url.startsWith('https://facebook.com/')) {
      return false;
    }
    return !url.startsWith('javascript:');
  }

  bool _looksLikeFeedTitle(String title) {
    if (title.isEmpty) {
      return false;
    }
    if (title.length < 8) {
      return false;
    }
    const Set<String> blocked = <String>{
      'GeekNews',
      '최신글',
      '예전글',
      '쓰레드',
      '댓글',
      'Ask',
      'Show',
      'GN⁺',
      'Weekly',
      '글등록',
      '로그인',
      'RSS',
      'Blog',
      'Lists',
      'FAQ',
      'About',
      '시작하기',
      '이용법',
      '약관',
      '개인정보 처리방침',
      '숨기기',
      '검색',
      '댓글과 토론',
    };
    if (blocked.contains(title)) {
      return false;
    }
    if (RegExp(r'^댓글\s*\d+개$').hasMatch(title)) {
      return false;
    }
    if (RegExp(r'^\d+\s*points?$').hasMatch(title)) {
      return false;
    }
    if (RegExp(r'^\d+$').hasMatch(title)) {
      return false;
    }
    return true;
  }

  String? _extractTopicIdNearElement(Element element) {
    Element? current = element;
    for (int depth = 0; depth < 6 && current != null; depth++) {
      final String? ownHref = current.attributes['href'];
      if (ownHref != null) {
        final String? ownTopicId = _extractTopicIdFromUrl(_normalizeUrl(ownHref));
        if (ownTopicId != null) {
          return ownTopicId;
        }
      }

      final List<Element> links = current.querySelectorAll('a[href]');
      for (final Element link in links) {
        final String href = _normalizeUrl(link.attributes['href'] ?? '');
        final String? topicId = _extractTopicIdFromUrl(href);
        if (topicId != null) {
          return topicId;
        }
      }
      current = current.parent;
    }
    return null;
  }

  String? _extractTopicIdFromUrl(String url) {
    if (url.isEmpty) {
      return null;
    }
    final Uri? uri = Uri.tryParse(url);
    if (uri == null) {
      return null;
    }
    if (!uri.path.contains('/topic')) {
      return null;
    }
    final String id = uri.queryParameters['id']?.trim() ?? '';
    return id.isNotEmpty ? id : null;
  }

  String _firstNonEmptyString(List<dynamic> values) {
    for (final dynamic value in values) {
      final String text = value?.toString().trim() ?? '';
      if (text.isNotEmpty) {
        return text;
      }
    }
    return '';
  }
}

class FeedSyncResult {
  const FeedSyncResult({
    required this.success,
    required this.usedCache,
    required this.hasNewItem,
    required this.latestItem,
    required this.items,
    required this.syncStatus,
    required this.message,
  });

  final bool success;
  final bool usedCache;
  final bool hasNewItem;
  final FeedItem? latestItem;
  final List<FeedItem> items;
  final SyncStatus syncStatus;
  final String? message;
}

class ProbeAndFetchResult {
  const ProbeAndFetchResult({
    required this.items,
    required this.sourceUrl,
    required this.parserStrategy,
    required this.sourceKind,
  });

  final List<FeedItem> items;
  final String sourceUrl;
  final String parserStrategy;
  final String sourceKind;
}

class FeedFetchException implements Exception {
  const FeedFetchException(this.message);

  final String message;

  @override
  String toString() => message;
}

class _JsonProbeResult {
  const _JsonProbeResult({required this.sourceUrl, required this.items});

  final String sourceUrl;
  final List<FeedItem> items;
}
