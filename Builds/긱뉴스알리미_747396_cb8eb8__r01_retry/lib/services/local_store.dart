import 'dart:convert';

import 'package:shared_preferences/shared_preferences.dart';

import '../models/feed_item.dart';
import '../models/permission_status_model.dart';
import '../models/sync_status.dart';
import '../models/user_settings.dart';

class LocalStore {
  static const _feedCacheKey = 'feed_cache';
  static const _syncStatusKey = 'sync_status';
  static const _userSettingsKey = 'user_settings';
  static const _permissionStatusKey = 'permission_status';

  Future<List<FeedItem>> loadFeedCache() async {
    final prefs = await SharedPreferences.getInstance();
    final raw = prefs.getString(_feedCacheKey);
    if (raw == null || raw.isEmpty) {
      return <FeedItem>[];
    }
    final decoded = jsonDecode(raw) as List<dynamic>;
    return decoded
        .map((item) => FeedItem.fromJson(item as Map<String, dynamic>))
        .toList();
  }

  Future<void> saveFeedCache(List<FeedItem> items) async {
    final prefs = await SharedPreferences.getInstance();
    final raw = jsonEncode(items.map((item) => item.toJson()).toList());
    await prefs.setString(_feedCacheKey, raw);
  }

  Future<SyncStatus> loadSyncStatus() async {
    final prefs = await SharedPreferences.getInstance();
    final raw = prefs.getString(_syncStatusKey);
    if (raw == null || raw.isEmpty) {
      return SyncStatus.initial();
    }
    return SyncStatus.fromJson(jsonDecode(raw) as Map<String, dynamic>);
  }

  Future<void> saveSyncStatus(SyncStatus status) async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setString(_syncStatusKey, jsonEncode(status.toJson()));
  }

  Future<UserSettings> loadUserSettings() async {
    final prefs = await SharedPreferences.getInstance();
    final raw = prefs.getString(_userSettingsKey);
    if (raw == null || raw.isEmpty) {
      return UserSettings.initial();
    }
    return UserSettings.fromJson(jsonDecode(raw) as Map<String, dynamic>);
  }

  Future<void> saveUserSettings(UserSettings settings) async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setString(_userSettingsKey, jsonEncode(settings.toJson()));
  }

  Future<PermissionStatusModel> loadPermissionStatus() async {
    final prefs = await SharedPreferences.getInstance();
    final raw = prefs.getString(_permissionStatusKey);
    if (raw == null || raw.isEmpty) {
      return PermissionStatusModel.initial();
    }
    return PermissionStatusModel.fromJson(jsonDecode(raw) as Map<String, dynamic>);
  }

  Future<void> savePermissionStatus(PermissionStatusModel status) async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setString(_permissionStatusKey, jsonEncode(status.toJson()));
  }
}
