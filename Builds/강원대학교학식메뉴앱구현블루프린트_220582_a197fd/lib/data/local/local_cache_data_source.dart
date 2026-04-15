import 'dart:convert';
import 'dart:io';

import '../../models/app_snapshot.dart';
import '../../models/daily_menu.dart';
import '../../models/restaurant.dart';
import '../../models/sync_status.dart';
import '../../crash_handler.dart';

class LocalCacheDataSource {
  static const String _fileName = 'knu_meal_snapshot.json';

  Future<File> _resolveFile() async {
    final directory = Directory.current;
    if (!await directory.exists()) {
      await directory.create(recursive: true);
    }
    return File('${directory.path}${Platform.pathSeparator}$_fileName');
  }

  Future<AppSnapshot?> loadSnapshot() async {
    try {
      final file = await _resolveFile();
      if (!await file.exists()) {
        return null;
      }
      final content = await file.readAsString();
      if (content.trim().isEmpty) {
        return null;
      }
      final decoded = jsonDecode(content);
      if (decoded is! Map<String, dynamic>) {
        return null;
      }
      return AppSnapshot.fromJson(decoded);
    } catch (error, stackTrace) {
      CrashHandler.recordError(error, stackTrace, reason: '로컬 캐시 읽기 실패');
      return null;
    }
  }

  Future<void> saveSnapshot(AppSnapshot snapshot) async {
    try {
      final file = await _resolveFile();
      await file.writeAsString(jsonEncode(snapshot.toJson()), flush: true);
    } catch (error, stackTrace) {
      CrashHandler.recordError(error, stackTrace, reason: '로컬 캐시 저장 실패');
    }
  }

  AppSnapshot emptySnapshot() {
    return AppSnapshot(
      restaurants: Restaurant.defaults,
      dailyMenus: const <DailyMenu>[],
      syncStatus: SyncStatus.initial(),
    );
  }
}
