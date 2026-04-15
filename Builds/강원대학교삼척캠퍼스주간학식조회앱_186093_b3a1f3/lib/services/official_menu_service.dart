import 'dart:async';
import 'dart:convert';
import 'dart:io';

import '../crash_handler.dart';

class OfficialMenuService {
  static const String sourceUrl = 'https://wwwk.kangwon.ac.kr/www/selecttnMenuListWU.do?key=1578';

  Future<String> fetchHtml() async {
    HttpClient? client;
    try {
      client = HttpClient();
      client.connectionTimeout = const Duration(seconds: 10);
      final request = await client.getUrl(Uri.parse(sourceUrl)).timeout(const Duration(seconds: 10));
      request.headers.set(HttpHeaders.acceptHeader, 'text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8');
      final response = await request.close().timeout(const Duration(seconds: 15));
      if (response.statusCode < 200 || response.statusCode >= 300) {
        throw HttpException('비정상 응답 상태 코드: ${response.statusCode}');
      }
      final bytes = await consolidateBytes(response).timeout(const Duration(seconds: 15));
      if (bytes.isEmpty) {
        throw const FormatException('응답 본문이 비어 있습니다.');
      }
      return _decodeHtml(bytes);
    } catch (error, stackTrace) {
      CrashHandler.recordError(error, stackTrace, reason: '공식 식단 페이지 조회 실패');
      rethrow;
    } finally {
      client?.close(force: true);
    }
  }

  Future<List<int>> consolidateBytes(HttpClientResponse response) async {
    final builder = BytesBuilder(copy: false);
    await for (final chunk in response) {
      builder.add(chunk);
    }
    return builder.takeBytes();
  }

  String _decodeHtml(List<int> bytes) {
    try {
      final utf8Text = utf8.decode(bytes, allowMalformed: true);
      if (_looksBrokenKorean(utf8Text)) {
        return latin1.decode(bytes, allowInvalid: true);
      }
      return utf8Text;
    } catch (_) {
      try {
        return latin1.decode(bytes, allowInvalid: true);
      } catch (error, stackTrace) {
        CrashHandler.recordError(error, stackTrace, reason: 'HTML 디코딩 실패');
        rethrow;
      }
    }
  }

  bool _looksBrokenKorean(String text) {
    return text.contains('�') || text.contains('占') || text.trim().isEmpty;
  }
}
