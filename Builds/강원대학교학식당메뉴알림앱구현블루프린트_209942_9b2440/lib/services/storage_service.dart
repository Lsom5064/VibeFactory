import 'dart:convert';

import 'package:shared_preferences/shared_preferences.dart';

import '../models/menu_data.dart';
import '../models/sync_status.dart';
import '../models/user_settings.dart';
import '../crash_handler.dart';

class StorageService {
  static const _settingsKey = 'user_settings';
  static const _menuCacheKey = 'menu_cache';
  static const _syncStatusKey = 'sync_status';

  Future<UserSettings> loadSettings() async {
    try {
      final prefs = await SharedPreferences.getInstance();
      final raw = prefs.getString(_settingsKey);
      if (raw == null || raw.isEmpty) {
        return UserSettings.initial();
      }
      return UserSettings.fromJson(jsonDecode(raw) as Map<String, dynamic>);
    } catch (error, stackTrace) {
      CrashHandler.recordError(error, stackTrace, reason: '설정 불러오기 실패');
      rethrow;
    }
  }

  Future<void> saveSettings(UserSettings settings) async {
    try {
      final prefs = await SharedPreferences.getInstance();
      await prefs.setString(_settingsKey, jsonEncode(settings.toJson()));
    } catch (error, stackTrace) {
      CrashHandler.recordError(error, stackTrace, reason: '설정 저장 실패');
      rethrow;
    }
  }

  Future<MenuData?> loadMenuCache() async {
    try {
      final prefs = await SharedPreferences.getInstance();
      final raw = prefs.getString(_menuCacheKey);
      if (raw == null || raw.isEmpty) {
        return null;
      }
      return MenuData.fromJson(jsonDecode(raw) as Map<String, dynamic>).copyWith(isFromCache: true);
    } catch (error, stackTrace) {
      CrashHandler.recordError(error, stackTrace, reason: '메뉴 캐시 불러오기 실패');
      rethrow;
    }
  }

  Future<void> saveMenuCache(MenuData menuData) async {
    try {
      final prefs = await SharedPreferences.getInstance();
      await prefs.setString(_menuCacheKey, jsonEncode(menuData.toJson()));
    } catch (error, stackTrace) {
      CrashHandler.recordError(error, stackTrace, reason: '메뉴 캐시 저장 실패');
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
      return SyncStatus.fromJson(jsonDecode(raw) as Map<String, dynamic>);
    } catch (error, stackTrace) {
      CrashHandler.recordError(error, stackTrace, reason: '동기화 상태 불러오기 실패');
      rethrow;
    }
  }

  Future<void> saveSyncStatus(SyncStatus status) async {
    try {
      final prefs = await SharedPreferences.getInstance();
      await prefs.setString(_syncStatusKey, jsonEncode(status.toJson()));
    } catch (error, stackTrace) {
      CrashHandler.recordError(error, stackTrace, reason: '동기화 상태 저장 실패');
      rethrow;
    }
  }
}
