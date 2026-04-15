import '../crash_handler.dart';
import '../models/menu_data.dart';
import '../utils/date_helper.dart';

class MenuFetchResult {
  final MenuData? menuData;
  final String? errorCode;
  final String? errorMessage;

  const MenuFetchResult({
    required this.menuData,
    required this.errorCode,
    required this.errorMessage,
  });

  bool get isSuccess => menuData != null;
}

class MenuService {
  Future<MenuFetchResult> fetchTodayMenu({
    required String campusId,
    required String restaurantId,
  }) async {
    try {
      final raw = await _loadStaticSource(campusId: campusId, restaurantId: restaurantId);
      final parsed = _parseMenu(raw, campusId: campusId, restaurantId: restaurantId);
      return MenuFetchResult(menuData: parsed, errorCode: null, errorMessage: null);
    } catch (error, stackTrace) {
      CrashHandler.recordError(error, stackTrace, reason: '메뉴 조회 실패');
      return MenuFetchResult(
        menuData: null,
        errorCode: 'parse',
        errorMessage: '메뉴를 해석하지 못했습니다. 잠시 후 다시 시도해 주세요.',
      );
    }
  }

  Future<String> _loadStaticSource({
    required String campusId,
    required String restaurantId,
  }) async {
    try {
      final today = DateHelper.todayKey();
      return 'DATE=$today\nCAMPUS=$campusId\nRESTAURANT=$restaurantId\nITEM=쌀밥\nITEM=된장국\nITEM=제육볶음\nITEM=김치';
    } catch (error, stackTrace) {
      CrashHandler.recordError(error, stackTrace, reason: '정적 메뉴 소스 생성 실패');
      rethrow;
    }
  }

  MenuData _parseMenu(
    String raw, {
    required String campusId,
    required String restaurantId,
  }) {
    try {
      final lines = raw.split('\n');
      String baseDate = DateHelper.todayKey();
      final items = <String>[];
      for (final line in lines) {
        if (line.startsWith('DATE=')) {
          baseDate = line.replaceFirst('DATE=', '').trim();
        } else if (line.startsWith('ITEM=')) {
          final item = line.replaceFirst('ITEM=', '').trim();
          if (item.isNotEmpty) {
            items.add(item);
          }
        }
      }
      if (items.isEmpty) {
        throw StateError('빈 메뉴');
      }
      return MenuData(
        menuId: '${campusId}_$restaurantId\_$baseDate',
        campusId: campusId,
        restaurantId: restaurantId,
        baseDate: baseDate,
        items: items,
        rawText: raw,
        parsedAt: DateHelper.nowIso(),
        sourceLabel: '정적 자리표시자 소스',
        isFromCache: false,
      );
    } catch (error, stackTrace) {
      CrashHandler.recordError(error, stackTrace, reason: '메뉴 파싱 실패');
      rethrow;
    }
  }
}
