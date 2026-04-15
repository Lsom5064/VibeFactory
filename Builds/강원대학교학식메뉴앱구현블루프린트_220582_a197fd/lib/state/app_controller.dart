import 'package:flutter/foundation.dart';

import '../crash_handler.dart';
import '../core/utils/date_utils.dart';
import '../data/repository/menu_repository.dart';
import '../models/daily_menu.dart';
import '../models/restaurant.dart';
import '../models/sync_status.dart';
import '../models/weekly_menu_bundle.dart';

class AppController extends ChangeNotifier {
  final MenuRepository repository;

  AppController({required this.repository});

  List<Restaurant> restaurants = Restaurant.defaults;
  List<DailyMenu> dailyMenus = <DailyMenu>[];
  WeeklyMenuBundle weeklyBundle = const WeeklyMenuBundle(
    weekStart: '',
    weekEnd: '',
    dailyMenus: [],
  );
  SyncStatus syncStatus = SyncStatus.initial();
  int selectedTabIndex = 0;
  bool isInitialLoading = true;
  bool isRefreshing = false;
  String? transientMessage;
  bool _disposed = false;

  Future<void> initialize() async {
    try {
      isInitialLoading = true;
      notifyListeners();

      final cached = await repository.loadCachedSnapshot();
      if (cached != null) {
        restaurants = cached.restaurants.isEmpty ? Restaurant.defaults : cached.restaurants;
        dailyMenus = cached.dailyMenus;
        weeklyBundle = repository.buildWeeklyBundle(dailyMenus);
        syncStatus = cached.syncStatus.copyWith(
          isShowingCache: cached.dailyMenus.isNotEmpty,
          noData: cached.dailyMenus.isEmpty,
        );
      }

      isInitialLoading = false;
      _safeNotify();
      await refreshMenus(background: true);
    } catch (error, stackTrace) {
      CrashHandler.recordError(error, stackTrace, reason: '앱 초기화 실패');
      isInitialLoading = false;
      syncStatus = syncStatus.copyWith(
        sourceDescription: dailyMenus.isNotEmpty
            ? '오류가 발생하여 저장된 데이터를 표시합니다.'
            : '메뉴를 준비하지 못했습니다.',
        isShowingCache: dailyMenus.isNotEmpty,
        networkFailed: true,
        noData: dailyMenus.isEmpty,
      );
      _safeNotify();
    }
  }

  Future<void> refreshMenus({bool background = false}) async {
    if (isRefreshing) {
      transientMessage = '이미 새로고침 중입니다.';
      _safeNotify();
      return;
    }

    try {
      isRefreshing = true;
      transientMessage = null;
      _safeNotify();

      final result = await repository.fetchLatestMenus(
        restaurants: restaurants,
        hasCache: dailyMenus.isNotEmpty,
      );

      if (result.success) {
        dailyMenus = result.dailyMenus;
        weeklyBundle = repository.buildWeeklyBundle(dailyMenus);
        syncStatus = result.syncStatus;
      } else {
        syncStatus = result.syncStatus.copyWith(
          lastSuccessAt: syncStatus.lastSuccessAt,
        );
        transientMessage = result.message;
      }
    } catch (error, stackTrace) {
      CrashHandler.recordError(error, stackTrace, reason: '메뉴 새로고침 실패');
      syncStatus = syncStatus.copyWith(
        sourceDescription: dailyMenus.isNotEmpty
            ? '오류가 발생하여 저장된 데이터를 유지합니다.'
            : '메뉴를 불러오지 못했습니다.',
        isShowingCache: dailyMenus.isNotEmpty,
        networkFailed: true,
        noData: dailyMenus.isEmpty,
      );
      transientMessage = '새로고침 중 오류가 발생했습니다.';
    } finally {
      isInitialLoading = false;
      isRefreshing = false;
      _safeNotify();
    }
  }

  void selectTab(int index) {
    selectedTabIndex = index;
    _safeNotify();
  }

  List<DailyMenu> get todayMenus {
    final today = AppDateUtils.toIsoDate(DateTime.now());
    final filtered = dailyMenus.where((menu) => menu.date == today).toList();
    filtered.sort((a, b) => _restaurantOrder(a.restaurantId).compareTo(_restaurantOrder(b.restaurantId)));
    return filtered;
  }

  Map<String, List<DailyMenu>> get weeklyMenusByDate {
    final map = <String, List<DailyMenu>>{};
    final sorted = [...dailyMenus]..sort((a, b) {
      final dateCompare = a.date.compareTo(b.date);
      if (dateCompare != 0) {
        return dateCompare;
      }
      return _restaurantOrder(a.restaurantId).compareTo(_restaurantOrder(b.restaurantId));
    });
    for (final menu in sorted) {
      map.putIfAbsent(menu.date, () => <DailyMenu>[]).add(menu);
    }
    return map;
  }

  int _restaurantOrder(String restaurantId) {
    return restaurants
            .where((restaurant) => restaurant.restaurantId == restaurantId)
            .map((restaurant) => restaurant.displayOrder)
            .cast<int?>()
            .firstWhere((value) => value != null, orElse: () => 999) ??
        999;
  }

  void clearTransientMessage() {
    transientMessage = null;
  }

  void _safeNotify() {
    if (!_disposed) {
      notifyListeners();
    }
  }

  @override
  void dispose() {
    _disposed = true;
    super.dispose();
  }
}
