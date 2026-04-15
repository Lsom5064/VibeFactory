import '../../core/constants/source_config.dart';
import '../../core/utils/date_utils.dart';
import '../../models/app_snapshot.dart';
import '../../models/daily_menu.dart';
import '../../models/restaurant.dart';
import '../../models/sync_status.dart';
import '../../models/weekly_menu_bundle.dart';
import '../../crash_handler.dart';
import '../local/local_cache_data_source.dart';
import '../parser/menu_parser.dart';
import '../remote/remote_menu_data_source.dart';

class FetchLatestResult {
  final bool success;
  final List<DailyMenu> dailyMenus;
  final SyncStatus syncStatus;
  final String message;

  const FetchLatestResult({
    required this.success,
    required this.dailyMenus,
    required this.syncStatus,
    required this.message,
  });
}

class MenuRepository {
  final LocalCacheDataSource localDataSource;
  final RemoteMenuDataSource remoteDataSource;
  final MenuParser parser;

  MenuRepository({
    required this.localDataSource,
    required this.remoteDataSource,
    required this.parser,
  });

  Future<AppSnapshot?> loadCachedSnapshot() async {
    return localDataSource.loadSnapshot();
  }

  WeeklyMenuBundle buildWeeklyBundle(List<DailyMenu> menus) {
    if (menus.isEmpty) {
      return const WeeklyMenuBundle(weekStart: '', weekEnd: '', dailyMenus: []);
    }
    final sorted = [...menus]..sort((a, b) => a.date.compareTo(b.date));
    return WeeklyMenuBundle(
      weekStart: sorted.first.date,
      weekEnd: sorted.last.date,
      dailyMenus: sorted,
    );
  }

  Future<FetchLatestResult> fetchLatestMenus({
    required List<Restaurant> restaurants,
    required bool hasCache,
  }) async {
    try {
      if (!SourceConfig.hasOfficialUrl) {
        return FetchLatestResult(
          success: false,
          dailyMenus: const [],
          syncStatus: SyncStatus(
            lastSuccessAt: null,
            sourceDescription: '공식 메뉴 주소가 설정되지 않아 저장된 데이터만 표시할 수 있습니다.',
            parseSuccess: false,
            isShowingCache: hasCache,
            networkFailed: false,
            noData: !hasCache,
          ),
          message: '공식 메뉴 주소가 설정되지 않았습니다.',
        );
      }

      final remote = await remoteDataSource.fetchMenuDocument();
      if (!remote.success) {
        return FetchLatestResult(
          success: false,
          dailyMenus: const [],
          syncStatus: SyncStatus(
            lastSuccessAt: null,
            sourceDescription: hasCache
                ? '최신 메뉴를 불러오지 못해 마지막 저장 데이터를 표시합니다.'
                : '공식 웹페이지에서 메뉴를 불러오지 못했습니다.',
            parseSuccess: false,
            isShowingCache: hasCache,
            networkFailed: remote.networkFailed,
            noData: !hasCache,
          ),
          message: remote.message,
        );
      }

      final fetchedAt = DateTime.now().toIso8601String();
      final parsed = parser.parse(
        document: remote.body,
        fetchedAt: DateTime.now(),
        restaurants: restaurants,
      );
      final validMenus = parsed.menus.where((menu) => menu.isValid).toList()
        ..sort((a, b) => a.date.compareTo(b.date));

      if (!parsed.success || validMenus.isEmpty) {
        return FetchLatestResult(
          success: false,
          dailyMenus: const [],
          syncStatus: SyncStatus(
            lastSuccessAt: null,
            sourceDescription: hasCache
                ? '파싱에 실패하여 마지막 저장 데이터를 표시합니다.'
                : '공식 웹페이지 메뉴를 해석하지 못했습니다.',
            parseSuccess: false,
            isShowingCache: hasCache,
            networkFailed: false,
            noData: !hasCache,
          ),
          message: parsed.message,
        );
      }

      final syncStatus = SyncStatus(
        lastSuccessAt: fetchedAt,
        sourceDescription: '공식 웹페이지 기준 최신 메뉴입니다.',
        parseSuccess: true,
        isShowingCache: false,
        networkFailed: false,
        noData: false,
      );

      final snapshot = AppSnapshot(
        restaurants: restaurants,
        dailyMenus: validMenus,
        syncStatus: syncStatus,
      );
      await localDataSource.saveSnapshot(snapshot);

      return FetchLatestResult(
        success: true,
        dailyMenus: validMenus,
        syncStatus: syncStatus,
        message: '최신 메뉴를 불러왔습니다.',
      );
    } catch (error, stackTrace) {
      CrashHandler.recordError(error, stackTrace, reason: '최신 메뉴 조회 실패');
      return FetchLatestResult(
        success: false,
        dailyMenus: const [],
        syncStatus: SyncStatus(
          lastSuccessAt: null,
          sourceDescription: hasCache
              ? '오류가 발생하여 마지막 저장 데이터를 표시합니다.'
              : '메뉴를 불러오는 중 오류가 발생했습니다.',
          parseSuccess: false,
          isShowingCache: hasCache,
          networkFailed: true,
          noData: !hasCache,
        ),
        message: '예상하지 못한 오류가 발생했습니다.',
      );
    }
  }
}
