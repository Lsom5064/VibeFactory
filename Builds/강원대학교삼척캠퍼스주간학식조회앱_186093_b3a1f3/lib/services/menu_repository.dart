import '../crash_handler.dart';
import '../models/menu_models.dart';
import '../utils/date_utils.dart';
import 'cache_service.dart';
import 'menu_parser.dart';
import 'official_menu_service.dart';

class MenuRepository {
  MenuRepository({
    OfficialMenuService? officialMenuService,
    MenuParser? menuParser,
    CacheService? cacheService,
  })  : _officialMenuService = officialMenuService ?? OfficialMenuService(),
        _menuParser = menuParser ?? MenuParser(),
        _cacheService = cacheService ?? CacheService();

  final OfficialMenuService _officialMenuService;
  final MenuParser _menuParser;
  final CacheService _cacheService;

  Future<MenuFetchResult> fetchWeeklyMenu() async {
    try {
      final html = await _officialMenuService.fetchHtml();
      final parsed = _menuParser.parse(html);
      final filtered = _filterThisWeek(parsed);
      if (filtered.days.isEmpty) {
        return const MenuFetchResult(menu: null, usedCache: false);
      }
      await _cacheService.saveWeeklyMenu(filtered);
      return MenuFetchResult(menu: filtered, usedCache: false);
    } catch (error, stackTrace) {
      CrashHandler.recordError(error, stackTrace, reason: '주간 식단 조회 실패');
      final cached = await _cacheService.loadWeeklyMenu();
      if (cached != null) {
        return MenuFetchResult(
          menu: cached.menu,
          usedCache: true,
          warningMessage: '최신 조회에 실패하여 최근 저장 데이터를 표시합니다.',
        );
      }
      rethrow;
    }
  }

  WeeklyMenu _filterThisWeek(WeeklyMenu menu) {
    final now = DateTime.now();
    final start = menu.startDate;
    final end = menu.endDate;
    final fallbackStart = startOfWeek(now);
    final fallbackEnd = endOfWeek(now);
    final effectiveStart = start.isAfter(end) ? fallbackStart : start;
    final effectiveEnd = start.isAfter(end) ? fallbackEnd : end;

    final filteredDays = menu.days
        .where((day) => isWithinInclusive(day.date, effectiveStart, effectiveEnd))
        .toList()
      ..sort((a, b) => a.date.compareTo(b.date));

    return WeeklyMenu(
      startDate: effectiveStart,
      endDate: effectiveEnd,
      updatedAt: menu.updatedAt,
      days: filteredDays,
    );
  }
}
