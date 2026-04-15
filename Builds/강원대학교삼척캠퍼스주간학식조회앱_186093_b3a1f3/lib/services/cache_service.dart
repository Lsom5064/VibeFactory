import 'dart:convert';

import '../crash_handler.dart';
import '../models/menu_models.dart';

class CacheService {
  static String? _cachedJson;
  static String? _savedAtIso;

  Future<void> saveWeeklyMenu(WeeklyMenu menu) async {
    try {
      _cachedJson = jsonEncode(menu.toJson());
      _savedAtIso = DateTime.now().toIso8601String();
    } catch (error, stackTrace) {
      CrashHandler.recordError(error, stackTrace, reason: '식단 캐시 저장 실패');
      rethrow;
    }
  }

  Future<CachedWeeklyMenu?> loadWeeklyMenu() async {
    try {
      if (_cachedJson == null || _savedAtIso == null) {
        return null;
      }
      final decoded = jsonDecode(_cachedJson!) as Map<String, dynamic>;
      return CachedWeeklyMenu(
        menu: WeeklyMenu.fromJson(decoded),
        savedAt: DateTime.parse(_savedAtIso!),
      );
    } catch (error, stackTrace) {
      CrashHandler.recordError(error, stackTrace, reason: '식단 캐시 복원 실패');
      return null;
    }
  }
}
