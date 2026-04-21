import 'package:flutter/material.dart';

import '../models/menu_cache_item.dart';
import '../models/sync_metadata.dart';
import '../models/user_settings.dart';
import '../services/crash_handler.dart';
import '../services/menu_repository.dart';
import '../services/notification_service.dart';
import '../services/settings_repository.dart';
import '../widgets/menu_card.dart';
import '../widgets/permission_info_card.dart';
import '../widgets/status_card.dart';

class HomeScreen extends StatefulWidget {
  const HomeScreen({super.key});

  @override
  State<HomeScreen> createState() => _HomeScreenState();
}

class _HomeScreenState extends State<HomeScreen> {
  final SettingsRepository _settingsRepository = SettingsRepository();
  final MenuRepository _menuRepository = MenuRepository();
  final NotificationService _notificationService = NotificationService();

  UserSettings _userSettings = UserSettings.defaults();
  SyncMetadata _syncMetadata = SyncMetadata.defaults();
  List<MenuCacheItem> _cachedMenus = <MenuCacheItem>[];
  bool _loading = true;
  bool _refreshing = false;
  bool _permissionGranted = false;
  bool _parsingSucceeded = true;
  String _statusMessage = '';

  @override
  void initState() {
    super.initState();
    _initialize();
  }

  Future<void> _initialize() async {
    try {
      final UserSettings settings = await _settingsRepository.loadUserSettings();
      final SyncMetadata metadata = await _settingsRepository.loadSyncMetadata();
      final List<MenuCacheItem> cached = await _menuRepository.loadCachedTodayMenus();
      final bool permissionGranted =
          await _notificationService.checkNotificationPermission();
      if (!mounted) {
        return;
      }
      setState(() {
        _userSettings = settings;
        _syncMetadata = metadata;
        _cachedMenus = cached;
        _permissionGranted = permissionGranted;
        _loading = false;
      });
      await _syncMenus(background: true);
    } catch (error, stackTrace) {
      await CrashHandler.logError(error, stackTrace);
      if (!mounted) {
        return;
      }
      setState(() {
        _loading = false;
        _statusMessage = '초기 데이터를 불러오지 못했습니다.';
      });
    }
  }

  Future<void> _syncMenus({bool background = false}) async {
    if (_refreshing) {
      return;
    }
    setState(() {
      _refreshing = true;
      if (!background) {
        _statusMessage = '메뉴를 새로고침하는 중입니다.';
      }
    });

    try {
      final MenuFetchResult result = await _menuRepository.fetchAndCacheTodayMenus();
      final SyncMetadata metadata = await _settingsRepository.loadSyncMetadata();
      if (!mounted) {
        return;
      }
      setState(() {
        _cachedMenus = result.items;
        _syncMetadata = metadata;
        _parsingSucceeded = result.parsingSucceeded;
        _statusMessage = result.message;
        _refreshing = false;
      });
    } catch (error, stackTrace) {
      await CrashHandler.logError(error, stackTrace);
      if (!mounted) {
        return;
      }
      setState(() {
        _refreshing = false;
        _statusMessage = '메뉴를 새로고침하지 못했습니다.';
      });
    }
  }

  Future<void> _openRestaurantSettings() async {
    final Object? result = await Navigator.of(context).pushNamed('/restaurant-settings');
    if (result == true) {
      final UserSettings settings = await _settingsRepository.loadUserSettings();
      if (!mounted) {
        return;
      }
      setState(() {
        _userSettings = settings;
      });
      if (_userSettings.notificationEnabled) {
        try {
          await _notificationService.scheduleDaily8amNotification();
        } catch (error, stackTrace) {
          await CrashHandler.logError(error, stackTrace);
        }
      }
    }
  }

  Future<void> _openNotificationSettings() async {
    await Navigator.of(context).pushNamed('/notification-settings');
    final UserSettings settings = await _settingsRepository.loadUserSettings();
    final bool permissionGranted =
        await _notificationService.checkNotificationPermission();
    if (!mounted) {
      return;
    }
    setState(() {
      _userSettings = settings;
      _permissionGranted = permissionGranted;
    });
  }

  MenuCacheItem? get _selectedMenu {
    if (_userSettings.selectedRestaurantName.isEmpty) {
      return null;
    }
    for (final MenuCacheItem item in _cachedMenus) {
      if (item.restaurantName == _userSettings.selectedRestaurantName) {
        return item;
      }
    }
    return null;
  }

  @override
  Widget build(BuildContext context) {
    final String today = _formatToday();
    final MenuCacheItem? selectedMenu = _selectedMenu;

    return Scaffold(
      appBar: AppBar(title: const Text('강원 학식알림')),
      body: _loading
          ? const Center(child: CircularProgressIndicator())
          : SingleChildScrollView(
              padding: const EdgeInsets.all(16),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: <Widget>[
                  Card(
                    child: Padding(
                      padding: const EdgeInsets.all(16),
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: <Widget>[
                          Text(today, style: Theme.of(context).textTheme.titleLarge),
                          const SizedBox(height: 8),
                          Text(
                            _userSettings.selectedRestaurantName.isEmpty
                                ? '선택한 식당이 없습니다.'
                                : '선택 식당: ${_userSettings.selectedRestaurantName}',
                          ),
                        ],
                      ),
                    ),
                  ),
                  const SizedBox(height: 12),
                  if (_userSettings.selectedRestaurantName.isEmpty)
                    const StatusCard(
                      title: '식당 선택 필요',
                      message: '홈 화면과 알림 기준 식당을 먼저 선택해 주세요.',
                      icon: Icons.storefront_outlined,
                    )
                  else if (selectedMenu != null)
                    MenuCard(
                      title: '${selectedMenu.restaurantName} 오늘 메뉴',
                      subtitle: _syncMetadata.usedCache ? '캐시 데이터 표시 중' : '최신 조회 결과',
                      menuText: selectedMenu.menuText,
                    )
                  else if (_cachedMenus.isEmpty)
                    const StatusCard(
                      title: '오늘 메뉴 없음',
                      message: '오늘 날짜 기준 유효한 메뉴가 없거나 아직 불러오지 못했습니다.',
                      icon: Icons.no_meals_outlined,
                    )
                  else
                    const StatusCard(
                      title: '선택 식당 메뉴 없음',
                      message: '선택한 식당의 오늘 메뉴가 없습니다. 다른 식당을 선택해 보세요.',
                      icon: Icons.info_outline,
                    ),
                  const SizedBox(height: 12),
                  if (_statusMessage.isNotEmpty)
                    StatusCard(
                      title: _syncMetadata.usedCache ? '데이터 상태' : '동기화 상태',
                      message: _statusMessage,
                      icon: _syncMetadata.usedCache ? Icons.warning_amber : Icons.cloud_done,
                      color: _syncMetadata.usedCache
                          ? Colors.orange
                          : Theme.of(context).colorScheme.primary,
                    ),
                  if (!_parsingSucceeded)
                    const StatusCard(
                      title: '파싱 경고',
                      message: '소스 구조가 변경되었을 수 있습니다. 마지막 성공 캐시를 우선 확인해 주세요.',
                      icon: Icons.error_outline,
                      color: Colors.red,
                    ),
                  const SizedBox(height: 8),
                  Text(
                    _syncMetadata.lastSuccessfulSyncAt.isEmpty
                        ? '마지막 성공 동기화 시각이 없습니다.'
                        : '마지막 성공 동기화: ${_syncMetadata.lastSuccessfulSyncAt}',
                  ),
                  const SizedBox(height: 12),
                  FilledButton.icon(
                    onPressed: _refreshing ? null : () => _syncMenus(),
                    icon: _refreshing
                        ? const SizedBox(
                            width: 18,
                            height: 18,
                            child: CircularProgressIndicator(strokeWidth: 2),
                          )
                        : const Icon(Icons.refresh),
                    label: const Text('수동 새로고침'),
                  ),
                  const SizedBox(height: 12),
                  OutlinedButton.icon(
                    onPressed: _openRestaurantSettings,
                    icon: const Icon(Icons.restaurant_menu),
                    label: const Text('식당 선택 설정'),
                  ),
                  const SizedBox(height: 12),
                  OutlinedButton.icon(
                    onPressed: _openNotificationSettings,
                    icon: const Icon(Icons.notifications_active_outlined),
                    label: const Text('알림 설정'),
                  ),
                  const SizedBox(height: 12),
                  if (_userSettings.notificationEnabled && !_permissionGranted)
                    PermissionInfoCard(
                      title: '알림 권한이 필요합니다',
                      body: '권한이 허용되지 않아 오전 8시 알림을 보낼 수 없습니다. 홈 화면에서 수동 확인하거나 설정 화면에서 다시 요청해 주세요.',
                      actionLabel: '알림 설정으로 이동',
                      onAction: _openNotificationSettings,
                    ),
                  if (_cachedMenus.isEmpty && _statusMessage.isNotEmpty)
                    const StatusCard(
                      title: '오류 안내',
                      message: '캐시도 없어서 표시할 메뉴가 없습니다. 잠시 후 수동 새로고침을 다시 시도해 주세요.',
                      icon: Icons.wifi_off,
                      color: Colors.red,
                    ),
                ],
              ),
            ),
    );
  }

  String _formatToday() {
    final DateTime now = DateTime.now();
    return '${now.year}년 ${now.month}월 ${now.day}일';
  }
}
