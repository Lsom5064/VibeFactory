import 'dart:async';
import 'dart:io';

import '../core/constants/restaurant_constants.dart';
import '../models/weekly_menu.dart';
import 'menu_parser.dart';

class MenuApiException implements Exception {
  final String type;
  final String message;

  MenuApiException(this.type, this.message);

  @override
  String toString() => '$type: $message';
}

class MenuApiService {
  MenuApiService({MenuParser? parser}) : _parser = parser ?? MenuParser();

  final MenuParser _parser;

  Future<WeeklyMenu> fetchWeeklyMenu({
    required String restaurantId,
    required DateTime weekStart,
    required DateTime weekEnd,
  }) async {
    final uri = RestaurantConstants.buildWeeklyMenuUri(
      restaurantId: restaurantId,
      weekStart: weekStart,
    );

    final client = HttpClient();
    client.connectionTimeout = const Duration(seconds: 8);

    try {
      final request = await client.getUrl(uri).timeout(const Duration(seconds: 8));
      final response = await request.close().timeout(const Duration(seconds: 8));
      if (response.statusCode < 200 || response.statusCode >= 300) {
        throw MenuApiException('server', '비정상 응답 코드: ${response.statusCode}');
      }

      final html = await response.transform(SystemEncoding().decoder).join();
      if (html.trim().isEmpty) {
        throw MenuApiException('server', '응답 본문이 비어 있습니다.');
      }

      try {
        return _parser.parseWeeklyMenu(
          html: html,
          weekStart: weekStart,
          weekEnd: weekEnd,
          sourceUrl: uri.toString(),
        );
      } on MenuParserException catch (e) {
        throw MenuApiException('parsing', e.message);
      }
    } on SocketException catch (e) {
      throw MenuApiException('network', e.message);
    } on TimeoutException catch (e) {
      throw MenuApiException('network', e.message ?? '요청 시간이 초과되었습니다.');
    } finally {
      client.close(force: true);
    }
  }
}
