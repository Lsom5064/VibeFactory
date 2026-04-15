import 'dart:convert';

import 'package:shared_preferences/shared_preferences.dart';

import '../models/app_settings.dart';
import '../models/weekly_menu.dart';

class LocalStorageService {
  static const String _lastSelectedRestaurantKey = 'last_selected_restaurant_id';
  static const String _favoriteRestaurantKey = 'favorite_restaurant_id';
  static const String _lastSelectedDateKey = 'last_selected_date';
  static const String _lastSuccessfulSyncKey = 'last_successful_sync';
  static const String _weeklyMenuPrefix = 'weekly_menu_cache_';

  Future<AppSettings> loadSettings() async {
    try {
      final prefs = await SharedPreferences.getInstance();
      return AppSettings(
        lastSelectedRestaurantId: prefs.getString(_lastSelectedRestaurantKey),
        favoriteRestaurantId: prefs.getString(_favoriteRestaurantKey),
        lastSelectedDate: _tryParseDate(prefs.getString(_lastSelectedDateKey)),
        lastSuccessfulSync: _tryParseDate(prefs.getString(_lastSuccessfulSyncKey)),
      );
    } catch (e, st) {
      Error.throwWithStackTrace(Exception('설정 복원 실패: $e'), st);
    }
  }

  Future<void> saveLastSelectedRestaurant(String restaurantId) async {
    try {
      final prefs = await SharedPreferences.getInstance();
      await prefs.setString(_lastSelectedRestaurantKey, restaurantId);
    } catch (e, st) {
      Error.throwWithStackTrace(Exception('마지막 선택 식당 저장 실패: $e'), st);
    }
  }

  Future<void> saveFavoriteRestaurant(String? restaurantId) async {
    try {
      final prefs = await SharedPreferences.getInstance();
      if (restaurantId == null || restaurantId.isEmpty) {
        await prefs.remove(_favoriteRestaurantKey);
      } else {
        await prefs.setString(_favoriteRestaurantKey, restaurantId);
      }
    } catch (e, st) {
      Error.throwWithStackTrace(Exception('즐겨찾기 식당 저장 실패: $e'), st);
    }
  }

  Future<void> saveLastSelectedDate(DateTime date) async {
    try {
      final prefs = await SharedPreferences.getInstance();
      await prefs.setString(_lastSelectedDateKey, date.toIso8601String());
    } catch (e, st) {
      Error.throwWithStackTrace(Exception('마지막 선택 날짜 저장 실패: $e'), st);
    }
  }

  Future<void> saveLastSuccessfulSync(DateTime date) async {
    try {
      final prefs = await SharedPreferences.getInstance();
      await prefs.setString(_lastSuccessfulSyncKey, date.toIso8601String());
    } catch (e, st) {
      Error.throwWithStackTrace(Exception('마지막 동기화 시각 저장 실패: $e'), st);
    }
  }

  String buildWeeklyMenuCacheKey({
    required String restaurantId,
    required DateTime weekStart,
  }) {
    return '$_weeklyMenuPrefix${restaurantId}_${weekStart.toIso8601String()}';
  }

  Future<void> saveWeeklyMenuCache({
    required String restaurantId,
    required DateTime weekStart,
    required WeeklyMenu menu,
  }) async {
    try {
      final prefs = await SharedPreferences.getInstance();
      final key = buildWeeklyMenuCacheKey(restaurantId: restaurantId, weekStart: weekStart);
      await prefs.setString(key, jsonEncode(menu.toJson()));
    } catch (e, st) {
      Error.throwWithStackTrace(Exception('주간 식단 캐시 저장 실패: $e'), st);
    }
  }

  Future<WeeklyMenu?> loadWeeklyMenuCache({
    required String restaurantId,
    required DateTime weekStart,
  }) async {
    try {
      final prefs = await SharedPreferences.getInstance();
      final key = buildWeeklyMenuCacheKey(restaurantId: restaurantId, weekStart: weekStart);
      final raw = prefs.getString(key);
      if (raw == null || raw.isEmpty) {
        return null;
      }
      final decoded = jsonDecode(raw);
      if (decoded is! Map<String, dynamic>) {
        await prefs.remove(key);
        return null;
      }
      return WeeklyMenu.fromJson(decoded);
    } catch (e, st) {
      Error.throwWithStackTrace(Exception('주간 식단 캐시 복원 실패: $e'), st);
    }
  }

  DateTime? _tryParseDate(String? value) {
    if (value == null || value.isEmpty) {
      return null;
    }
    return DateTime.tryParse(value);
  }
}
