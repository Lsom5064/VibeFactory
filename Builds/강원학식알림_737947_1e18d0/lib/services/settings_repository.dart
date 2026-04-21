import 'dart:convert';

import 'package:shared_preferences/shared_preferences.dart';

import '../models/menu_cache_item.dart';
import '../models/sync_metadata.dart';
import '../models/user_settings.dart';
import 'crash_handler.dart';

class SettingsRepository {
  static const String _userSettingsKey = 'user_settings';
  static const String _syncMetadataKey = 'sync_metadata';
  static const String _menuCacheKey = 'menu_cache';

  Future<UserSettings> loadUserSettings() async {
    try {
      final SharedPreferences prefs = await SharedPreferences.getInstance();
      final String? raw = prefs.getString(_userSettingsKey);
      if (raw == null || raw.isEmpty) {
        return UserSettings.defaults();
      }
      return UserSettings.fromJson(jsonDecode(raw) as Map<String, dynamic>);
    } catch (error, stackTrace) {
      await CrashHandler.logError(error, stackTrace);
      return UserSettings.defaults();
    }
  }

  Future<void> saveSelectedRestaurant(String restaurantName) async {
    try {
      final SharedPreferences prefs = await SharedPreferences.getInstance();
      final UserSettings current = await loadUserSettings();
      final UserSettings updated = current.copyWith(
        selectedRestaurantName: restaurantName.trim(),
      );
      await prefs.setString(_userSettingsKey, jsonEncode(updated.toJson()));
    } catch (error, stackTrace) {
      await CrashHandler.logError(error, stackTrace);
      rethrow;
    }
  }

  Future<void> saveNotificationEnabled(bool enabled) async {
    try {
      final SharedPreferences prefs = await SharedPreferences.getInstance();
      final UserSettings current = await loadUserSettings();
      final UserSettings updated = current.copyWith(
        notificationEnabled: enabled,
        notificationTime: '매일 오전 8시',
      );
      await prefs.setString(_userSettingsKey, jsonEncode(updated.toJson()));
    } catch (error, stackTrace) {
      await CrashHandler.logError(error, stackTrace);
      rethrow;
    }
  }

  Future<SyncMetadata> loadSyncMetadata() async {
    try {
      final SharedPreferences prefs = await SharedPreferences.getInstance();
      final String? raw = prefs.getString(_syncMetadataKey);
      if (raw == null || raw.isEmpty) {
        return SyncMetadata.defaults();
      }
      return SyncMetadata.fromJson(jsonDecode(raw) as Map<String, dynamic>);
    } catch (error, stackTrace) {
      await CrashHandler.logError(error, stackTrace);
      return SyncMetadata.defaults();
    }
  }

  Future<void> saveSyncMetadata(SyncMetadata metadata) async {
    try {
      final SharedPreferences prefs = await SharedPreferences.getInstance();
      await prefs.setString(_syncMetadataKey, jsonEncode(metadata.toJson()));
    } catch (error, stackTrace) {
      await CrashHandler.logError(error, stackTrace);
      rethrow;
    }
  }

  Future<List<MenuCacheItem>> loadMenuCache() async {
    try {
      final SharedPreferences prefs = await SharedPreferences.getInstance();
      final String? raw = prefs.getString(_menuCacheKey);
      if (raw == null || raw.isEmpty) {
        return <MenuCacheItem>[];
      }
      final List<dynamic> decoded = jsonDecode(raw) as List<dynamic>;
      return decoded
          .map((dynamic item) => MenuCacheItem.fromJson(item as Map<String, dynamic>))
          .toList();
    } catch (error, stackTrace) {
      await CrashHandler.logError(error, stackTrace);
      return <MenuCacheItem>[];
    }
  }

  Future<void> saveMenuCache(List<MenuCacheItem> items) async {
    try {
      final SharedPreferences prefs = await SharedPreferences.getInstance();
      final List<Map<String, dynamic>> encoded =
          items.map((MenuCacheItem item) => item.toJson()).toList();
      await prefs.setString(_menuCacheKey, jsonEncode(encoded));
    } catch (error, stackTrace) {
      await CrashHandler.logError(error, stackTrace);
      rethrow;
    }
  }
}
