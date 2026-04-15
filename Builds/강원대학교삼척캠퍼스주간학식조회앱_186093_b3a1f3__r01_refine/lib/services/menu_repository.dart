import '../crash_handler.dart';
import '../models/menu_models.dart';
import 'cache_service.dart';
import 'menu_parser.dart';
import 'official_menu_service.dart';

class MenuFetchResult {
  const MenuFetchResult({
    required this.menu,
    required this.usedCache,
    this.warningMessage,
  });

  final WeeklyMenu? menu;
  final bool usedCache;
  final String? warningMessage;
}

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
      final menu = _menuParser.parse(html);

      if (menu != null) {
        await _cacheService.saveWeeklyMenu(menu);
        return MenuFetchResult(
          menu: menu,
          usedCache: false,
        );
      }

      CrashHandler.recordError(
        StateError('파싱 결과가 비어 있습니다.'),
        StackTrace.current,
        reason: '빈 식단 결과 반환 직전',
      );

      final cached = await _cacheService.loadWeeklyMenu();
      if (cached != null) {
        return MenuFetchResult(
          menu: cached.menu,
          usedCache: true,
          warningMessage: '최신 식단을 확인하지 못해 최근 저장된 데이터를 표시합니다.',
        );
      }

      return const MenuFetchResult(
        menu: null,
        usedCache: false,
      );
    } catch (error, stackTrace) {
      CrashHandler.recordError(error, stackTrace, reason: '주간 식단 조회 실패');

      final cached = await _cacheService.loadWeeklyMenu();
      if (cached != null) {
        return MenuFetchResult(
          menu: cached.menu,
          usedCache: true,
          warningMessage: '네트워크 또는 파싱 문제로 최근 저장된 데이터를 표시합니다.',
        );
      }

      rethrow;
    }
  }
}
