import '../crash_handler.dart';
import '../models/menu_data.dart';

class MenuFetchResult {
  final bool isSuccess;
  final MenuData? menuData;
  final String? errorCode;
  final String? errorMessage;

  const MenuFetchResult({
    required this.isSuccess,
    this.menuData,
    this.errorCode,
    this.errorMessage,
  });
}

class MenuService {
  Future<MenuFetchResult> fetchTodayMenu({
    required String campusId,
    required String restaurantId,
  }) async {
    try {
      final now = DateTime.now();
      final menuData = MenuData(
        campusId: campusId,
        restaurantId: restaurantId,
        date: DateTime(now.year, now.month, now.day),
        meals: const <String>['등록된 메뉴가 없습니다.'],
        source: 'fallback',
      );

      return MenuFetchResult(
        isSuccess: true,
        menuData: menuData,
      );
    } catch (error, stackTrace) {
      CrashHandler.recordError(error, stackTrace, reason: '오늘 메뉴 조회 실패');
      return const MenuFetchResult(
        isSuccess: false,
        errorCode: 'menu_fetch_failed',
        errorMessage: '메뉴를 불러오지 못했습니다.',
      );
    }
  }
}
