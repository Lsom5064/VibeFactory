import 'dart:convert';

import 'package:shared_preferences/shared_preferences.dart';

import '../models/article_item.dart';
import '../models/last_seen_state.dart';
import '../models/notification_settings.dart';
import '../models/permission_state.dart';
import '../models/sync_status.dart';
import 'crash_handler.dart';

class LocalStore {
  static const _articlesKey = 'articles';
  static const _syncStatusKey = 'sync_status';
  static const _lastSeenKey = 'last_seen';
  static const _notificationSettingsKey = 'notification_settings';
  static const _permissionStateKey = 'permission_state';

  Future<void> saveArticles(List<ArticleItem> articles) async {
    try {
      final prefs = await SharedPreferences.getInstance();
      final encoded = jsonEncode(articles.map((e) => e.toJson()).toList());
      await prefs.setString(_articlesKey, encoded);
    } catch (error, stackTrace) {
      CrashHandler.record(error, stackTrace, reason: 'saveArticles failed');
      rethrow;
    }
  }

  Future<List<ArticleItem>> loadArticles() async {
    try {
      final prefs = await SharedPreferences.getInstance();
      final raw = prefs.getString(_articlesKey);
      if (raw == null || raw.isEmpty) {
        return [];
      }
      final decoded = jsonDecode(raw) as List<dynamic>;
      return decoded
          .map((e) => ArticleItem.fromJson(Map<String, dynamic>.from(e as Map)))
          .toList();
    } catch (error, stackTrace) {
      CrashHandler.record(error, stackTrace, reason: 'loadArticles failed');
      return [];
    }
  }

  Future<void> saveSyncStatus(SyncStatus status) async {
    try {
      final prefs = await SharedPreferences.getInstance();
      await prefs.setString(_syncStatusKey, jsonEncode(status.toJson()));
    } catch (error, stackTrace) {
      CrashHandler.record(error, stackTrace, reason: 'saveSyncStatus failed');
      rethrow;
    }
  }

  Future<SyncStatus> loadSyncStatus() async {
    try {
      final prefs = await SharedPreferences.getInstance();
      final raw = prefs.getString(_syncStatusKey);
      if (raw == null || raw.isEmpty) {
        return SyncStatus.initial();
      }
      return SyncStatus.fromJson(Map<String, dynamic>.from(jsonDecode(raw) as Map));
    } catch (error, stackTrace) {
      CrashHandler.record(error, stackTrace, reason: 'loadSyncStatus failed');
      return SyncStatus.initial();
    }
  }

  Future<void> saveLastSeenState(LastSeenState state) async {
    try {
      final prefs = await SharedPreferences.getInstance();
      await prefs.setString(_lastSeenKey, jsonEncode(state.toJson()));
    } catch (error, stackTrace) {
      CrashHandler.record(error, stackTrace, reason: 'saveLastSeenState failed');
      rethrow;
    }
  }

  Future<LastSeenState?> loadLastSeenState() async {
    try {
      final prefs = await SharedPreferences.getInstance();
      final raw = prefs.getString(_lastSeenKey);
      if (raw == null || raw.isEmpty) {
        return null;
      }
      return LastSeenState.fromJson(Map<String, dynamic>.from(jsonDecode(raw) as Map));
    } catch (error, stackTrace) {
      CrashHandler.record(error, stackTrace, reason: 'loadLastSeenState failed');
      return null;
    }
  }

  Future<void> saveNotificationSettings(AppNotificationSettings settings) async {
    try {
      final prefs = await SharedPreferences.getInstance();
      await prefs.setString(
        _notificationSettingsKey,
        jsonEncode(settings.toJson()),
      );
    } catch (error, stackTrace) {
      CrashHandler.record(error, stackTrace, reason: 'saveNotificationSettings failed');
      rethrow;
    }
  }

  Future<AppNotificationSettings> loadNotificationSettings() async {
    try {
      final prefs = await SharedPreferences.getInstance();
      final raw = prefs.getString(_notificationSettingsKey);
      if (raw == null || raw.isEmpty) {
        return AppNotificationSettings.initial();
      }
      return AppNotificationSettings.fromJson(
        Map<String, dynamic>.from(jsonDecode(raw) as Map),
      );
    } catch (error, stackTrace) {
      CrashHandler.record(error, stackTrace, reason: 'loadNotificationSettings failed');
      return AppNotificationSettings.initial();
    }
  }

  Future<void> savePermissionState(AppPermissionState state) async {
    try {
      final prefs = await SharedPreferences.getInstance();
      await prefs.setString(_permissionStateKey, jsonEncode(state.toJson()));
    } catch (error, stackTrace) {
      CrashHandler.record(error, stackTrace, reason: 'savePermissionState failed');
      rethrow;
    }
  }

  Future<AppPermissionState> loadPermissionState() async {
    try {
      final prefs = await SharedPreferences.getInstance();
      final raw = prefs.getString(_permissionStateKey);
      if (raw == null || raw.isEmpty) {
        return AppPermissionState.initial();
      }
      return AppPermissionState.fromJson(
        Map<String, dynamic>.from(jsonDecode(raw) as Map),
      );
    } catch (error, stackTrace) {
      CrashHandler.record(error, stackTrace, reason: 'loadPermissionState failed');
      return AppPermissionState.initial();
    }
  }
}
