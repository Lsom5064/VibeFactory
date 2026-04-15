import '../../core/utils/date_utils.dart';
import '../../models/daily_menu.dart';
import '../../models/restaurant.dart';

class ParseResult {
  final List<DailyMenu> menus;
  final bool success;
  final String message;

  const ParseResult({
    required this.menus,
    required this.success,
    required this.message,
  });
}

class MenuParser {
  ParseResult parse({
    required String document,
    required DateTime fetchedAt,
    required List<Restaurant> restaurants,
  }) {
    try {
      final normalized = document.trim();
      if (normalized.isEmpty) {
        return const ParseResult(menus: [], success: false, message: '본문이 비어 있습니다.');
      }

      // TODO: 실제 공식 웹페이지 구조가 확정되면 HTML 파싱 규칙을 추가하세요.
      // 현재는 선언된 URL과 선택자가 없으므로 실패 닫힘으로 처리합니다.
      return const ParseResult(
        menus: [],
        success: false,
        message: '현재 파서 규칙이 설정되지 않았습니다.',
      );
    } catch (_) {
      return const ParseResult(menus: [], success: false, message: '파싱 중 예외가 발생했습니다.');
    }
  }

  List<String> normalizeMenuItems(String rawText) {
    return rawText
        .split(RegExp(r'\n|•|·|/|,|\|'))
        .map((e) => e.replaceAll(RegExp(r'\s+'), ' ').trim())
        .where((e) => e.isNotEmpty)
        .toList();
  }

  String? normalizeRestaurantId(String restaurantName, List<Restaurant> restaurants) {
    for (final restaurant in restaurants) {
      if (restaurant.restaurantName == restaurantName.trim()) {
        return restaurant.restaurantId;
      }
    }
    return null;
  }

  bool isValidDate(String value) => AppDateUtils.tryParseIsoDate(value) != null;
}
