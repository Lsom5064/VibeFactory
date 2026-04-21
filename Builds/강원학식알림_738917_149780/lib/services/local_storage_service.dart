import 'dart:convert';

import 'package:shared_preferences/shared_preferences.dart';

import '../models/cache_metadata.dart';
import '../models/favorite_item.dart';
import '../models/menu_item.dart';
import '../models/notification_setting.dart';
import '../models/recent_view_item.dart';

class LocalStorageService {
  static const String _favoritesKey = 'favorites';
  static const String _notificationsKey = 'notifications';
  static const String _recentViewsKey = 'recent_views';
  static const String _cacheMetadataKey = 'cache_metadata';
  static const String _menuCacheKey = 'menu_cache';

  SharedPreferences? _prefs;

  Future<void> initialize() async {
    _prefs ??= await SharedPreferences.getInstance();
  }

  Future<void> saveMenuCache(String key, List<MenuItem> items) async {
    await initialize();
    final map = _decodeMap(_prefs?.getString(_menuCacheKey));
    map[key] = items.map((item) => item.toJson()).toList();
    await _prefs?.setString(_menuCacheKey, jsonEncode(map));
  }

  Future<List<MenuItem>> loadMenuCache(String key) async {
    await initialize();
    final map = _decodeMap(_prefs?.getString(_menuCacheKey));
    final rawList = map[key] as List<dynamic>? ?? <dynamic>[];
    return rawList
        .map((item) => MenuItem.fromJson(Map<String, dynamic>.from(item as Map)))
        .toList();
  }

  Future<void> saveCacheMetadata(List<CacheMetadata> metadata) async {
    await initialize();
    await _prefs?.setString(
      _cacheMetadataKey,
      jsonEncode(metadata.map((item) => item.toJson()).toList()),
    );
  }

  Future<List<CacheMetadata>> loadCacheMetadata() async {
    await initialize();
    final raw = _prefs?.getString(_cacheMetadataKey);
    if (raw == null || raw.isEmpty) {
      return <CacheMetadata>[];
    }
    final list = jsonDecode(raw) as List<dynamic>;
    return list
        .map((item) => CacheMetadata.fromJson(Map<String, dynamic>.from(item as Map)))
        .toList();
  }

  Future<void> saveFavorites(List<FavoriteItem> favorites) async {
    await initialize();
    await _prefs?.setString(
      _favoritesKey,
      jsonEncode(favorites.map((item) => item.toJson()).toList()),
    );
  }

  Future<List<FavoriteItem>> loadFavorites() async {
    await initialize();
    final raw = _prefs?.getString(_favoritesKey);
    if (raw == null || raw.isEmpty) {
      return <FavoriteItem>[];
    }
    final list = jsonDecode(raw) as List<dynamic>;
    return list
        .map((item) => FavoriteItem.fromJson(Map<String, dynamic>.from(item as Map)))
        .toList();
  }

  Future<void> saveNotificationSettings(
    List<RestaurantNotificationSetting> settings,
  ) async {
    await initialize();
    await _prefs?.setString(
      _notificationsKey,
      jsonEncode(settings.map((item) => item.toJson()).toList()),
    );
  }

  Future<List<RestaurantNotificationSetting>> loadNotificationSettings() async {
    await initialize();
    final raw = _prefs?.getString(_notificationsKey);
    if (raw == null || raw.isEmpty) {
      return <RestaurantNotificationSetting>[];
    }
    final list = jsonDecode(raw) as List<dynamic>;
    return list
        .map(
          (item) => RestaurantNotificationSetting.fromJson(
            Map<String, dynamic>.from(item as Map),
          ),
        )
        .toList();
  }

  Future<void> saveRecentViews(List<RecentViewItem> items) async {
    await initialize();
    await _prefs?.setString(
      _recentViewsKey,
      jsonEncode(items.map((item) => item.toJson()).toList()),
    );
  }

  Future<List<RecentViewItem>> loadRecentViews() async {
    await initialize();
    final raw = _prefs?.getString(_recentViewsKey);
    if (raw == null || raw.isEmpty) {
      return <RecentViewItem>[];
    }
    final list = jsonDecode(raw) as List<dynamic>;
    return list
        .map((item) => RecentViewItem.fromJson(Map<String, dynamic>.from(item as Map)))
        .toList();
  }

  Map<String, dynamic> _decodeMap(String? raw) {
    if (raw == null || raw.isEmpty) {
      return <String, dynamic>{};
    }
    return Map<String, dynamic>.from(jsonDecode(raw) as Map);
  }
}
