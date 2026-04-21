import 'dart:io';

import 'package:http/http.dart' as http;

import '../models/menu_cache_item.dart';
import '../models/sync_metadata.dart';
import 'crash_handler.dart';
import 'menu_parser.dart';
import 'settings_repository.dart';

class MenuFetchResult {
  const MenuFetchResult({
    required this.items,
    required this.usedCache,
    required this.success,
    required this.message,
    required this.parsingSucceeded,
    required this.hasMigrationNotice,
    required this.supportsMinimumSampleDays,
  });

  final List<MenuCacheItem> items;
  final bool usedCache;
  final bool success;
  final String message;
  final bool parsingSucceeded;
  final bool hasMigrationNotice;
  final bool supportsMinimumSampleDays;
}

class MenuRepository {
  MenuRepository({
    SettingsRepository? settingsRepository,
    MenuParser? parser,
    http.Client? client,
  })  : _settingsRepository = settingsRepository ?? SettingsRepository(),
        _parser = parser ?? MenuParser(),
        _client = client ?? http.Client();

  static const String sourceUrl =
      'https://www.kangwon.ac.kr/ko/extn/37/wkmenu-mngr/list.do?campusCd=1';

  final SettingsRepository _settingsRepository;
  final MenuParser _parser;
  final http.Client _client;

  Future<MenuFetchResult> fetchAndCacheTodayMenus() async {
    final List<MenuCacheItem> cached = await loadCachedTodayMenus();
    try {
      final http.Response response = await _client.get(Uri.parse(sourceUrl));
      if (response.statusCode != 200) {
        await _settingsRepository.saveSyncMetadata(
          (await _settingsRepository.loadSyncMetadata()).copyWith(usedCache: true),
        );
        return MenuFetchResult(
          items: cached,
          usedCache: true,
          success: false,
          message: '메뉴 페이지를 불러오지 못했습니다. 상태 코드: ${response.statusCode}',
          parsingSucceeded: false,
          hasMigrationNotice: false,
          supportsMinimumSampleDays: false,
        );
      }

      final String body = response.body;
      final List<MenuCacheItem> parsed = _parser.parseTodayMenus(body);
      final bool hasMigrationNotice = _parser.hasMigrationNotice(body);
      final bool supportsMinimumSampleDays =
          _parser.supportsMinimumSampleDays(body, minimumDays: 3);

      if (parsed.isEmpty) {
        await _settingsRepository.saveSyncMetadata(
          (await _settingsRepository.loadSyncMetadata()).copyWith(usedCache: true),
        );
        return MenuFetchResult(
          items: cached,
          usedCache: cached.isNotEmpty,
          success: true,
          message: '웹 조회는 성공했지만 오늘 날짜 기준 유효한 메뉴가 없습니다.',
          parsingSucceeded: true,
          hasMigrationNotice: hasMigrationNotice,
          supportsMinimumSampleDays: supportsMinimumSampleDays,
        );
      }

      await _settingsRepository.saveMenuCache(parsed);
      await _settingsRepository.saveSyncMetadata(
        SyncMetadata(
          lastSuccessfulSyncAt: DateTime.now().toIso8601String(),
          usedCache: false,
        ),
      );

      return MenuFetchResult(
        items: parsed,
        usedCache: false,
        success: true,
        message: '오늘 메뉴를 최신 정보로 동기화했습니다.',
        parsingSucceeded: true,
        hasMigrationNotice: hasMigrationNotice,
        supportsMinimumSampleDays: supportsMinimumSampleDays,
      );
    } on SocketException catch (error, stackTrace) {
      await CrashHandler.logError(error, stackTrace);
      await _settingsRepository.saveSyncMetadata(
        (await _settingsRepository.loadSyncMetadata()).copyWith(usedCache: true),
      );
      return MenuFetchResult(
        items: cached,
        usedCache: true,
        success: false,
        message: '네트워크 연결에 실패했습니다. 마지막 성공 캐시를 확인해 주세요.',
        parsingSucceeded: false,
        hasMigrationNotice: false,
        supportsMinimumSampleDays: false,
      );
    } catch (error, stackTrace) {
      await CrashHandler.logError(error, stackTrace);
      await _settingsRepository.saveSyncMetadata(
        (await _settingsRepository.loadSyncMetadata()).copyWith(usedCache: true),
      );
      return MenuFetchResult(
        items: cached,
        usedCache: true,
        success: false,
        message: '메뉴를 처리하는 중 오류가 발생했습니다. 소스 구조가 변경되었을 수 있습니다.',
        parsingSucceeded: false,
        hasMigrationNotice: false,
        supportsMinimumSampleDays: false,
      );
    }
  }

  Future<List<MenuCacheItem>> loadCachedTodayMenus() async {
    final List<MenuCacheItem> all = await _settingsRepository.loadMenuCache();
    final String today = _formatDate(DateTime.now());
    return all.where((MenuCacheItem item) => item.date == today).toList();
  }

  Future<List<String>> getAvailableRestaurants() async {
    final List<MenuCacheItem> items = await loadCachedTodayMenus();
    final Set<String> restaurants = items
        .map((MenuCacheItem item) => item.restaurantName.trim())
        .where((String name) => name.isNotEmpty)
        .toSet();
    final List<String> result = restaurants.toList()..sort();
    return result;
  }

  String _formatDate(DateTime dateTime) {
    final String year = dateTime.year.toString().padLeft(4, '0');
    final String month = dateTime.month.toString().padLeft(2, '0');
    final String day = dateTime.day.toString().padLeft(2, '0');
    return '$year-$month-$day';
  }
}
