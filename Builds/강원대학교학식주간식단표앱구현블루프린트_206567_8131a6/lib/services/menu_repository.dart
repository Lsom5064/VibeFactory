import '../models/weekly_menu.dart';
import 'local_storage_service.dart';
import 'menu_api_service.dart';

class MenuRepository {
  MenuRepository({
    MenuApiService? apiService,
    LocalStorageService? localStorageService,
  })  : _apiService = apiService ?? MenuApiService(),
        _localStorageService = localStorageService ?? LocalStorageService();

  final MenuApiService _apiService;
  final LocalStorageService _localStorageService;
  final Map<String, WeeklyMenu> memoryCache = <String, WeeklyMenu>{};

  String buildCacheKey({
    required String restaurantId,
    required DateTime weekStart,
  }) {
    return _localStorageService.buildWeeklyMenuCacheKey(
      restaurantId: restaurantId,
      weekStart: weekStart,
    );
  }

  Future<WeeklyMenu?> loadLocalCache({
    required String restaurantId,
    required DateTime weekStart,
  }) {
    return _localStorageService.loadWeeklyMenuCache(
      restaurantId: restaurantId,
      weekStart: weekStart,
    );
  }

  Future<WeeklyMenu> fetchAndCache({
    required String restaurantId,
    required DateTime weekStart,
    required DateTime weekEnd,
  }) async {
    final menu = await _apiService.fetchWeeklyMenu(
      restaurantId: restaurantId,
      weekStart: weekStart,
      weekEnd: weekEnd,
    );
    final key = buildCacheKey(restaurantId: restaurantId, weekStart: weekStart);
    memoryCache[key] = menu;
    await _localStorageService.saveWeeklyMenuCache(
      restaurantId: restaurantId,
      weekStart: weekStart,
      menu: menu,
    );
    await _localStorageService.saveLastSuccessfulSync(menu.fetchedAt);
    return menu;
  }

  LocalStorageService get storage => _localStorageService;
}
