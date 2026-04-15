import 'package:flutter/material.dart';

import 'crash_handler.dart';
import 'data/restaurant_catalog.dart';
import 'models/menu_data.dart';
import 'models/sync_status.dart';
import 'models/user_settings.dart';
import 'services/menu_service.dart';
import 'services/notification_service.dart';
import 'services/storage_service.dart';

class AppScreen extends StatefulWidget {
  const AppScreen({super.key});

  @override
  State<AppScreen> createState() => _AppScreenState();
}

class _AppScreenState extends State<AppScreen> {
  final StorageService _storageService = StorageService();
  final MenuService _menuService = MenuService();
  final NotificationService _notificationService = NotificationService();

  UserSettings _settings = UserSettings.initial();
  SyncStatus _syncStatus = SyncStatus.initial();
  MenuData? _menuData;
  bool _isLoading = true;
  String? _message;

  @override
  void initState() {
    super.initState();
    _initialize();
  }

  Future<void> _initialize() async {
    try {
      final settings = await _storageService.loadSettings();
      final syncStatus = await _storageService.loadSyncStatus();
      final menuCache = await _storageService.loadMenuCache();
      if (!mounted) {
        return;
      }
      setState(() {
        _settings = settings;
        _syncStatus = syncStatus;
        _menuData = menuCache;
        _isLoading = false;
      });
    } catch (error, stackTrace) {
      CrashHandler.recordError(error, stackTrace, reason: '초기 데이터 로드 실패');
      if (!mounted) {
        return;
      }
      setState(() {
        _isLoading = false;
        _message = '초기 데이터를 불러오지 못했습니다.';
      });
    }
  }

  Future<void> _fetchMenu() async {
    try {
      setState(() {
        _isLoading = true;
        _message = null;
      });
      final result = await _menuService.fetchTodayMenu(
        campusId: _settings.campusId,
        restaurantId: _settings.restaurantId,
      );
      if (!mounted) {
        return;
      }
      if (result.isSuccess && result.menuData != null) {
        final nextSync = SyncStatus(
          lastSyncedAt: DateTime.now(),
          isSyncing: false,
          lastResult: 'success',
        );
        await _storageService.saveMenuCache(result.menuData!);
        await _storageService.saveSyncStatus(nextSync);
        setState(() {
          _menuData = result.menuData;
          _syncStatus = nextSync;
          _isLoading = false;
        });
      } else {
        setState(() {
          _isLoading = false;
          _message = result.errorMessage ?? '메뉴를 불러오지 못했습니다.';
        });
      }
    } catch (error, stackTrace) {
      CrashHandler.recordError(error, stackTrace, reason: '메뉴 동기화 실패');
      if (!mounted) {
        return;
      }
      setState(() {
        _isLoading = false;
        _message = '메뉴 동기화 중 오류가 발생했습니다.';
      });
    }
  }

  Future<void> _updateCampus(String? campusId) async {
    if (campusId == null) {
      return;
    }
    try {
      final restaurants = RestaurantCatalog.restaurantsForCampus(campusId);
      final restaurantId = restaurants.isNotEmpty ? restaurants.first.id : '';
      final next = _settings.copyWith(campusId: campusId, restaurantId: restaurantId);
      await _storageService.saveSettings(next);
      if (!mounted) {
        return;
      }
      setState(() {
        _settings = next;
      });
    } catch (error, stackTrace) {
      CrashHandler.recordError(error, stackTrace, reason: '캠퍼스 변경 실패');
    }
  }

  Future<void> _updateRestaurant(String? restaurantId) async {
    if (restaurantId == null) {
      return;
    }
    try {
      final next = _settings.copyWith(restaurantId: restaurantId);
      await _storageService.saveSettings(next);
      if (!mounted) {
        return;
      }
      setState(() {
        _settings = next;
      });
    } catch (error, stackTrace) {
      CrashHandler.recordError(error, stackTrace, reason: '식당 변경 실패');
    }
  }

  Future<void> _toggleNotifications(bool enabled) async {
    try {
      bool finalEnabled = enabled;
      if (enabled) {
        final granted = await _notificationService.requestPermission();
        if (!granted) {
          finalEnabled = false;
          if (mounted) {
            ScaffoldMessenger.of(context).showSnackBar(
              const SnackBar(content: Text('알림 권한이 거부되어 기능이 비활성화됩니다.')),
            );
          }
        }
      }
      final next = _settings.copyWith(notificationsEnabled: finalEnabled);
      await _storageService.saveSettings(next);
      if (finalEnabled) {
        await _notificationService.scheduleDailyNotification();
      }
      if (!mounted) {
        return;
      }
      setState(() {
        _settings = next;
      });
    } catch (error, stackTrace) {
      CrashHandler.recordError(error, stackTrace, reason: '알림 설정 변경 실패');
    }
  }

  @override
  Widget build(BuildContext context) {
    final campuses = RestaurantCatalog.campuses;
    final restaurants = RestaurantCatalog.restaurantsForCampus(_settings.campusId);

    return Scaffold(
      appBar: AppBar(
        title: const Text('오늘의 메뉴'),
      ),
      body: SingleChildScrollView(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: <Widget>[
            DropdownButtonFormField<String>(
              key: UniqueKey(),
              value: _settings.campusId.isEmpty ? null : _settings.campusId,
              decoration: const InputDecoration(
                labelText: '캠퍼스 선택',
                border: OutlineInputBorder(),
              ),
              items: campuses
                  .map(
                    (campus) => DropdownMenuItem<String>(
                      value: campus.id,
                      child: Text(campus.name),
                    ),
                  )
                  .toList(),
              onChanged: (value) async {
                await _updateCampus(value);
              },
            ),
            const SizedBox(height: 16),
            DropdownButtonFormField<String>(
              key: UniqueKey(),
              value: _settings.restaurantId.isEmpty ? null : _settings.restaurantId,
              decoration: const InputDecoration(
                labelText: '식당 선택',
                border: OutlineInputBorder(),
              ),
              items: restaurants
                  .map(
                    (restaurant) => DropdownMenuItem<String>(
                      value: restaurant.id,
                      child: Text(restaurant.name),
                    ),
                  )
                  .toList(),
              onChanged: (value) async {
                await _updateRestaurant(value);
              },
            ),
            const SizedBox(height: 16),
            SwitchListTile(
              value: _settings.notificationsEnabled,
              title: const Text('알림 사용'),
              subtitle: const Text('권한이 거부되면 앱은 계속 사용할 수 있습니다.'),
              onChanged: (value) async {
                await _toggleNotifications(value);
              },
            ),
            const SizedBox(height: 16),
            ElevatedButton(
              key: UniqueKey(),
              onPressed: _isLoading ? null : () async => _fetchMenu(),
              child: const Text('오늘 메뉴 새로고침'),
            ),
            const SizedBox(height: 16),
            if (_isLoading)
              const Center(child: CircularProgressIndicator())
            else
              Card(
                child: Padding(
                  padding: const EdgeInsets.all(16),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: <Widget>[
                      Text('동기화 상태: ${_syncStatus.lastResult.isEmpty ? '없음' : _syncStatus.lastResult}'),
                      const SizedBox(height: 8),
                      Text(
                        _syncStatus.lastSyncedAt == null
                            ? '마지막 동기화: 없음'
                            : '마지막 동기화: ${_syncStatus.lastSyncedAt}',
                      ),
                      const SizedBox(height: 16),
                      Text(
                        _menuData == null
                            ? '표시할 메뉴가 없습니다.'
                            : _menuData!.meals.join('\n'),
                      ),
                      if (_message != null) ...<Widget>[
                        const SizedBox(height: 16),
                        Text(_message!, style: const TextStyle(color: Colors.red)),
                      ],
                    ],
                  ),
                ),
              ),
          ],
        ),
      ),
    );
  }
}
