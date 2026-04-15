import 'dart:async';
import 'dart:convert';
import 'dart:io';

import '../models/article_item.dart';

class ArticleService {
  static const String _geekNewsRssUrl = 'https://feeds.feedburner.com/geeknews-feed';

  Future<List<ArticleItem>> fetchLatestArticles() async {
    HttpClient? client;
    try {
      client = HttpClient();
      client.connectionTimeout = const Duration(seconds: 10);
      final Uri uri = Uri.parse(_geekNewsRssUrl);
      final HttpClientRequest request = await client.getUrl(uri);
      request.headers.set(HttpHeaders.acceptHeader, 'application/rss+xml, application/xml, text/xml');
      final HttpClientResponse response = await request.close();

      if (response.statusCode < 200 || response.statusCode >= 300) {
        throw HttpException('잘못된 응답 상태: ${response.statusCode}', uri: uri);
      }

      final String xml = await response.transform(utf8.decoder).join();
      return _parseRss(xml);
    } catch (_) {
      rethrow;
    } finally {
      client?.close(force: true);
    }
  }

  List<ArticleItem> _parseRss(String xml) {
    if (xml.trim().isEmpty) {
      return <ArticleItem>[];
    }

    final RegExp itemExp = RegExp(r'<item\b[^>]*>([\s\S]*?)</item>', caseSensitive: false);
    final Iterable<RegExpMatch> itemMatches = itemExp.allMatches(xml);
    final List<ArticleItem> items = <ArticleItem>[];
    int index = 0;

    for (final RegExpMatch match in itemMatches) {
      final String block = match.group(1) ?? '';
      final Map<String, dynamic> localMap = <String, dynamic>{
        'title': _decodeXml(_firstTagValue(block, 'title')),
        'url': _decodeXml(_firstTagValue(block, 'link')),
        'published_time': _decodeXml(_firstTagValue(block, 'pubDate')),
        'source_or_author': _decodeXml(_firstTagValue(block, 'author')),
      };

      final ArticleItem? item = ArticleItem.fromLocalMap(localMap, index);
      if (item != null) {
        items.add(item);
        index += 1;
      }
    }

    return items;
  }

  String _firstTagValue(String source, String tagName) {
    final RegExp exp = RegExp(
      '<$tagName\\b[^>]*>([\\s\\S]*?)</$tagName>',
      caseSensitive: false,
    );
    final RegExpMatch? match = exp.firstMatch(source);
    return match?.group(1)?.trim() ?? '';
  }

  String _decodeXml(String input) {
    return input
        .replaceAll('<![CDATA[', '')
        .replaceAll(']]>', '')
        .replaceAll('&amp;', '&')
        .replaceAll('&lt;', '<')
        .replaceAll('&gt;', '>')
        .replaceAll('&quot;', '"')
        .replaceAll('&apos;', "'")
        .trim();
  }
}
