import 'dart:io';

import '../../core/constants/source_config.dart';

class RemoteResponse {
  final bool success;
  final bool networkFailed;
  final int? statusCode;
  final String body;
  final String message;

  const RemoteResponse({
    required this.success,
    required this.networkFailed,
    required this.statusCode,
    required this.body,
    required this.message,
  });
}

class RemoteMenuDataSource {
  Future<RemoteResponse> fetchMenuDocument() async {
    if (!SourceConfig.hasOfficialUrl) {
      return const RemoteResponse(
        success: false,
        networkFailed: false,
        statusCode: null,
        body: '',
        message: '공식 메뉴 주소가 아직 설정되지 않았습니다.',
      );
    }

    HttpClient? client;
    try {
      client = HttpClient();
      final request = await client.getUrl(Uri.parse(SourceConfig.officialMenuUrl));
      final response = await request.close();
      final body = await response.transform(SystemEncoding().decoder).join();
      if (response.statusCode < 200 || response.statusCode >= 300) {
        return RemoteResponse(
          success: false,
          networkFailed: true,
          statusCode: response.statusCode,
          body: body,
          message: '메뉴 요청에 실패했습니다.',
        );
      }
      if (body.trim().isEmpty) {
        return RemoteResponse(
          success: false,
          networkFailed: false,
          statusCode: response.statusCode,
          body: body,
          message: '응답 본문이 비어 있습니다.',
        );
      }
      return RemoteResponse(
        success: true,
        networkFailed: false,
        statusCode: response.statusCode,
        body: body,
        message: '성공',
      );
    } catch (_) {
      return const RemoteResponse(
        success: false,
        networkFailed: true,
        statusCode: null,
        body: '',
        message: '네트워크 요청 중 오류가 발생했습니다.',
      );
    } finally {
      client?.close(force: true);
    }
  }
}
