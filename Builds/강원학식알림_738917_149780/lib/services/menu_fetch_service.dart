import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:http/http.dart' as http;

import '../models/cache_metadata.dart';
import '../models/favorite_item.dart';
import '../models/menu_item.dart';
import '../models/notification_setting.dart';
import 'local_storage_service.dart';
import 'menu_parser.dart';
import 'validation_service.dart';

class MenuFetchResult {
  const MenuFetchResult({
    required this.items,
    required this.metadata,
    required this.usedCache,
    this.errorMessage,
  });

  final List<MenuItem> items;
  final CacheMetadata metadata;
  final bool usedCache;
  final String? errorMessage;
}

class RefreshSummary {
  const RefreshSummary({required this.successCount, required this.failureCount});

  final int successCount;
  final int failureCount;
}

class MenuFetchService {
  MenuFetchService({
    required LocalStorageService storage,
    required ValidationService validationService,
  })  : _storage = storage,
        _validationService = validationService;

  static const String primaryUrl =
      'https://kangwon.ac.kr/ko/extn/337/wkmenu-mngr/list.do';
  static const String updateUrl =
      'https://kangwon.ac.kr/ko/extn/337/wkmenu-mngr/updt.do?campusCd=';

  final LocalStorageService _storage;
  final ValidationService _validationService;
  final MenuParser _parser = MenuParser();

  final ValueNotifier<List<FavoriteItem>> favoritesNotifier =
      ValueNotifier<List<FavoriteItem>>(<FavoriteItem>[]);
  final ValueNotifier<List<RestaurantNotificationSetting>> notificationsNotifier =
      ValueNotifier<List<RestaurantNotificationSetting>>(<RestaurantNotificationSetting>[]);
  final ValueNotifier<List<CacheMetadata>> metadataNotifier =
      ValueNotifier<List<CacheMetadata>>(<CacheMetadata>[]);
  final ValueNotifier<Map<String, List<String>>> verifiedRestaurantsNotifier =
      ValueNotifier<Map<String, List<String>>>(<String, List<String>>{});

  Future<void> initialize() async {
    favoritesNotifier.value = await _storage.loadFavorites();
    notificationsNotifier.value = await _storage.loadNotificationSettings();
    metadataNotifier.value = await _storage.loadCacheMetadata();
    await probeSource();
  }

  Future<void> probeSource() async {
    try {
      final response = await http.get(Uri.parse(primaryUrl)).timeout(
            const Duration(seconds: 10),
          );
      if (response.statusCode == 200) {
        final parsed = _parser.parseTextPattern(response.body, DateTime.now().toIso8601String());
        if (parsed.isNotEmpty) {
          final map = <String, List<String>>{};
          for (final item in parsed) {
            map.putIfAbsent(item.campusName, () => <String>[]);
            if (!map[item.campusName]!.contains(item.restaurantName)) {
              map[item.campusName]!.add(item.restaurantName);
            }
          }
          verifiedRestaurantsNotifier.value = map;
        }
      }
    } catch (_) {}
  }

  Future<MenuFetchResult> fetchWeeklyMenu({
    required String campusName,
    required String restaurantName,
    required String targetDate,
  }) async {
    final cacheKey = '$campusName|$restaurantName|$targetDate';
    try {
      final response = await http
          .post(
            Uri.parse(primaryUrl),
            body: <String, String>{
              'campusCd': campusName,
              'caftrCd': restaurantName,
              'targetDate': targetDate,
            },
          )
          .timeout(const Duration(seconds: 12));

      if (response.statusCode != 200) {
        return _fallbackToCache(
          campusName: campusName,
          restaurantName: restaurantName,
          targetDate: targetDate,
          errorState: 'bad_response_${response.statusCode}',
        );
      }

      var items = _parser.parseHtmlTable(response.body, targetDate);
      if (items.isEmpty) {
        items = _parser.parseTextPattern(response.body, targetDate);
      }
      items = items.where(_validationService.validateMenuRecord).toList();

      if (items.isEmpty) {
        return _fallbackToCache(
          campusName: campusName,
          restaurantName: restaurantName,
          targetDate: targetDate,
          errorState: 'structure_changed_or_empty',
          structureNeedsVerification: campusName != '삼척',
          emptySuccess: true,
        );
      }

      await _storage.saveMenuCache(cacheKey, items);
      final metadata = CacheMetadata(
        campusName: campusName,
        restaurantName: restaurantName,
        targetDate: targetDate,
        lastSuccessfulFetchAt: DateTime.now().toIso8601String(),
        parseSucceeded: true,
        errorState: null,
        structureNeedsVerification: false,
      );
      await _upsertMetadata(metadata);
      _registerVerified(items);
      return MenuFetchResult(items: items, metadata: metadata, usedCache: false);
    } catch (_) {
      return _fallbackToCache(
        campusName: campusName,
        restaurantName: restaurantName,
        targetDate: targetDate,
        errorState: 'network_error',
      );
    }
  }

  Future<RefreshSummary> refreshTrackedRestaurants(String targetDate) async {
    final tracked = <String, Map<String, String>>{};
    for (final favorite in favoritesNotifier.value) {
      tracked[favorite.key] = <String, String>{
        'campusName': favorite.campusName,
        'restaurantName': favorite.restaurantName,
      };
    }
    for (final notification in notificationsNotifier.value) {
      tracked[notification.key] = <String, String>{
        'campusName': notification.campusName,
        'restaurantName': notification.restaurantName,
      };
    }

    var successCount = 0;
    var failureCount = 0;
    for (final item in tracked.values) {
      final result = await fetchWeeklyMenu(
        campusName: item['campusName'] ?? '',
        restaurantName: item['restaurantName'] ?? '',
        targetDate: targetDate,
      );
      if (result.items.isNotEmpty) {
        successCount += 1;
      } else {
        failureCount += 1;
      }
    }
    return RefreshSummary(successCount: successCount, failureCount: failureCount);
  }

  Future<void> toggleFavorite(String campusName, String restaurantName) async {
    final current = List<FavoriteItem>.from(favoritesNotifier.value);
    final key = '$campusName|$restaurantName';
    final existingIndex = current.indexWhere((item) => item.key == key);
    if (existingIndex >= 0) {
      current.removeAt(existingIndex);
    } else {
      current.add(
        FavoriteItem(
          campusName: campusName,
          restaurantName: restaurantName,
          sortOrder: current.length,
        ),
      );
    }
    favoritesNotifier.value = current;
    await _storage.saveFavorites(current);
  }

  Future<void> reorderFavorites(int oldIndex, int newIndex) async {
    final current = List<FavoriteItem>.from(favoritesNotifier.value);
    if (oldIndex < newIndex) {
      newIndex -= 1;
    }
    final item = current.removeAt(oldIndex);
    current.insert(newIndex, item);
    final updated = <FavoriteItem>[];
    for (var i = 0; i < current.length; i++) {
      updated.add(
        FavoriteItem(
          campusName: current[i].campusName,
          restaurantName: current[i].restaurantName,
          sortOrder: i,
        ),
      );
    }
    favoritesNotifier.value = updated;
    await _storage.saveFavorites(updated);
  }

  Future<void> saveNotificationSetting(
    RestaurantNotificationSetting setting,
  ) async {
    final current = List<RestaurantNotificationSetting>.from(notificationsNotifier.value)
      ..removeWhere((item) => item.key == setting.key)
      ..add(setting);
    notificationsNotifier.value = current;
    await _storage.saveNotificationSettings(current);
  }

  Future<void> deleteNotificationSetting(String campusName, String restaurantName) async {
    final current = List<RestaurantNotificationSetting>.from(notificationsNotifier.value)
      ..removeWhere((item) => item.key == '$campusName|$restaurantName');
    notificationsNotifier.value = current;
    await _storage.saveNotificationSettings(current);
  }

  Future<MenuFetchResult> _fallbackToCache({
    required String campusName,
    required String restaurantName,
    required String targetDate,
    required String errorState,
    bool structureNeedsVerification = false,
    bool emptySuccess = false,
  }) async {
    final cacheKey = '$campusName|$restaurantName|$targetDate';
    final cached = await _storage.loadMenuCache(cacheKey);
    final existing = metadataNotifier.value.where((item) => item.key == cacheKey).toList();
    final metadata = CacheMetadata(
      campusName: campusName,
      restaurantName: restaurantName,
      targetDate: targetDate,
      lastSuccessfulFetchAt:
          existing.isNotEmpty ? existing.first.lastSuccessfulFetchAt : null,
      parseSucceeded: false,
      errorState: errorState,
      structureNeedsVerification: structureNeedsVerification,
    );
    await _upsertMetadata(metadata);

    if (cached.isNotEmpty) {
      return MenuFetchResult(
        items: cached,
        metadata: metadata,
        usedCache: true,
        errorMessage: emptySuccess
            ? '최신 데이터 확인에 실패해 마지막 캐시를 표시합니다'
            : '최신 식단을 불러오지 못했습니다',
      );
    }

    return MenuFetchResult(
      items: <MenuItem>[],
      metadata: metadata,
      usedCache: false,
      errorMessage: emptySuccess
          ? '선택한 캠퍼스·식당·주차에 표시할 식단이 없습니다'
          : '최신 식단을 불러오지 못했습니다',
    );
  }

  Future<void> _upsertMetadata(CacheMetadata metadata) async {
    final current = List<CacheMetadata>.from(metadataNotifier.value)
      ..removeWhere((item) => item.key == metadata.key)
      ..insert(0, metadata);
    metadataNotifier.value = current;
    await _storage.saveCacheMetadata(current);
  }

  void _registerVerified(List<MenuItem> items) {
    final map = Map<String, List<String>>.from(verifiedRestaurantsNotifier.value);
    for (final item in items) {
      final restaurants = List<String>.from(map[item.campusName] ?? <String>[]);
      if (!restaurants.contains(item.restaurantName)) {
        restaurants.add(item.restaurantName);
      }
      map[item.campusName] = restaurants;
    }
    verifiedRestaurantsNotifier.value = map;
  }
}
