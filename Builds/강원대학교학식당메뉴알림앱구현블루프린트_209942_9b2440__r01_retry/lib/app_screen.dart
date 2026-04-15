import 'package:flutter/material.dart';

import 'crash_handler.dart';
import 'data/restaurant_catalog.dart';
import 'models/menu_data.dart';
import 'models/sync_status.dart';
import 'models/user_settings.dart';
import 'services/menu_service.dart';
import 'services/notification_service.dart';
import 'services/storage_service.dart';
import 'utils/date_helper.dart';
import 'widgets/error_status_card.dart';
import 'widgets/menu_summary_card.dart';
import 'widgets/notification_settings_card.dart';
import 'widgets/selection_card.dart';
import 'widgets/sync_status_card.dart';

enum MainSection { onboarding, home, error, settings }

class MealAppScreen extends StatefulWidget {
  const MealAppScreen({super.key});

  @override
  State<MealAppScreen> createState() => _MealAppScreenState();
}

class _MealAppScreenState extends State<MealAppScreen> with WidgetsBindingObserver {
  final StorageService _storageService = StorageService();
  final MenuService _menuService = MenuService();
  final NotificationService _notificationService = NotificationService();

  bool _isLoading = true;
  bool _isRefreshing = false;
  bool _isPermissionRequestInProgress = false;
  bool _initialSetupCompleted = false;
  UserSettings _settings = UserSettings.initial();
  MenuData? _currentMenu;
  MenuData? _lastSuccessMenu;
  SyncStatus _syncStatus = SyncStatus.initial();
  String? _lastErrorSummary;
  String? _statusMessage;
  String? _permissionMessage;
  MainSection _currentSection = MainSection.onboarding;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _bootstrap();
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) {
      _recalculateDateState();
    }
  }

  Future<void> _bootstrap() async {
    try {
      await _notificationService.initialize();
      UserSettings loadedSettings;
      MenuData? loadedMenu;
      SyncStatus loadedSync;
      try {
        loadedSettings = await _storageService.loadSettings();
        loadedMenu = await _storageService.loadMenuCache();
        loadedSync = await _storageService.loadSyncStatus();
      } catch (error, stackTrace) {
        CrashHandler.recordError(error, stackTrace, reason: '초기 저장소 로드 실패');
        loadedSettings = UserSettings.initial();
        loadedMenu = null;
        loadedSync = SyncStatus.initial();
        _statusMessage = '저장된 설정을 읽지 못했습니다. 설정을 다시 확인해 주세요.';
      }
      final validatedSettings = _validateSettings(loadedSettings);
      if (!mounted) {
        return;
      }
      setState(() {
        _settings = validatedSettings;
        _currentMenu = loadedMenu;
        _lastSuccessMenu = loadedMenu;
        _syncStatus = loadedSync.copyWith(hasCache: loadedMenu != null);
        _initialSetupCompleted = validatedSettings.campusId != null && validatedSettings.restaurantId != null;
        _currentSection = _initialSetupCompleted ? MainSection.home : MainSection.onboarding;
        _isLoading = false;
      });
    } catch (error, stackTrace) {
      CrashHandler.recordError(error, stackTrace, reason: '앱 초기화 실패');
      if (!mounted) {
        return;
      }
      setState(() {
        _isLoading = false;
        _statusMessage = '앱 초기화 중 문제가 발생했습니다.';
      });
    }
  }

  UserSettings _validateSettings(UserSettings settings) {
    final restaurant = RestaurantCatalog.findRestaurant(settings.campusId, settings.restaurantId);
    if (settings.campusId != null && restaurant == null) {
      _lastErrorSummary = '선택한 식당 정보가 유효하지 않아 다시 선택이 필요합니다.';
      return settings.copyWith(restaurantId: null, notificationsEnabled: false);
    }
    return settings;
  }

  Future<void> _persistSettings(UserSettings settings) async {
    try {
      await _storageService.saveSettings(settings);
    } catch (error, stackTrace) {
      CrashHandler.recordError(error, stackTrace, reason: '설정 영속화 실패');
      if (!mounted) {
        return;
      }
      setState(() {
        _statusMessage = '설정 저장에 실패했습니다. 이번 실행 중에는 유지되지만 다시 열면 반영되지 않을 수 있습니다.';
      });
    }
  }

  Future<void> _persistSyncState() async {
    try {
      if (_lastSuccessMenu != null) {
        await _storageService.saveMenuCache(_lastSuccessMenu!);
      }
      await _storageService.saveSyncStatus(_syncStatus);
    } catch (error, stackTrace) {
      CrashHandler.recordError(error, stackTrace, reason: '동기화 상태 영속화 실패');
      if (!mounted) {
        return;
      }
      setState(() {
        _statusMessage = '일부 데이터를 저장하지 못했습니다. 현재 화면은 계속 사용할 수 있습니다.';
      });
    }
  }

  Future<void> _refreshMenu() async {
    if (_isRefreshing) {
      return;
    }
    if (_settings.campusId == null || _settings.restaurantId == null) {
      if (!mounted) {
        return;
      }
      setState(() {
        _statusMessage = '먼저 캠퍼스와 식당을 선택해 주세요.';
      });
      return;
    }
    if (!mounted) {
      return;
    }
    setState(() {
      _isRefreshing = true;
      _statusMessage = '메뉴를 새로 불러오는 중입니다.';
    });
    final attemptAt = DateHelper.nowIso();
    try {
      final result = await _menuService.fetchTodayMenu(
        campusId: _settings.campusId!,
        restaurantId: _settings.restaurantId!,
      );
      if (result.isSuccess) {
        final newStatus = _syncStatus.copyWith(
          lastAttemptAt: attemptAt,
          lastSuccessAt: DateHelper.nowIso(),
          isSuccess: true,
          errorCode: null,
          errorMessage: null,
          hasCache: true,
        );
        _syncStatus = newStatus;
        _currentMenu = result.menuData;
        _lastSuccessMenu = result.menuData;
        _lastErrorSummary = null;
        await _persistSyncState();
        if (_settings.notificationsEnabled) {
          await _rescheduleNotification();
        }
        if (!mounted) {
          return;
        }
        setState(() {
          _statusMessage = '메뉴를 업데이트했습니다.';
          _currentSection = MainSection.home;
        });
      } else {
        final newStatus = _syncStatus.copyWith(
          lastAttemptAt: attemptAt,
          isSuccess: false,
          errorCode: result.errorCode,
          errorMessage: result.errorMessage,
          hasCache: _lastSuccessMenu != null,
        );
        _syncStatus = newStatus;
        _lastErrorSummary = result.errorMessage;
        _currentMenu = _lastSuccessMenu;
        await _persistSyncState();
        if (!mounted) {
          return;
        }
        setState(() {
          _statusMessage = _lastSuccessMenu != null
              ? '새 메뉴를 가져오지 못해 저장된 메뉴를 표시합니다.'
              : '메뉴를 불러오지 못했습니다. 다시 시도해 주세요.';
          _currentSection = _lastSuccessMenu == null ? MainSection.error : MainSection.home;
        });
      }
    } catch (error, stackTrace) {
      CrashHandler.recordError(error, stackTrace, reason: '새로고침 처리 실패');
      _syncStatus = _syncStatus.copyWith(
        lastAttemptAt: attemptAt,
        isSuccess: false,
        errorCode: 'network',
        errorMessage: '메뉴를 불러오는 중 문제가 발생했습니다.',
        hasCache: _lastSuccessMenu != null,
      );
      await _persistSyncState();
      if (!mounted) {
        return;
      }
      setState(() {
        _statusMessage = '메뉴를 불러오는 중 문제가 발생했습니다.';
        _currentMenu = _lastSuccessMenu;
      });
    } finally {
      if (!mounted) {
        return;
      }
      setState(() {
        _isRefreshing = false;
      });
    }
  }

  Future<void> _onCampusChanged(String? campusId) async {
    try {
      final nextSettings = _settings.copyWith(
        campusId: campusId,
        restaurantId: null,
        notificationsEnabled: false,
      );
      await _notificationService.cancelAll();
      await _persistSettings(nextSettings);
      if (!mounted) {
        return;
      }
      setState(() {
        _settings = nextSettings;
        _initialSetupCompleted = false;
        _statusMessage = '캠퍼스가 변경되었습니다. 식당을 다시 선택해 주세요.';
        _permissionMessage = null;
      });
    } catch (error, stackTrace) {
      CrashHandler.recordError(error, stackTrace, reason: '캠퍼스 변경 실패');
      rethrow;
    }
  }

  Future<void> _onRestaurantChanged(String? restaurantId) async {
    try {
      final restaurant = RestaurantCatalog.findRestaurant(_settings.campusId, restaurantId);
      if (restaurant == null) {
        if (!mounted) {
          return;
        }
        setState(() {
          _statusMessage = '유효한 식당을 다시 선택해 주세요.';
        });
        return;
      }
      final nextSettings = _settings.copyWith(restaurantId: restaurantId);
      await _persistSettings(nextSettings);
      if (!mounted) {
        return;
      }
      setState(() {
        _settings = nextSettings;
        _initialSetupCompleted = nextSettings.campusId != null && nextSettings.restaurantId != null;
        _currentSection = MainSection.home;
        _statusMessage = '식당 선택이 저장되었습니다.';
      });
      if (nextSettings.notificationsEnabled) {
        await _rescheduleNotification();
      }
    } catch (error, stackTrace) {
      CrashHandler.recordError(error, stackTrace, reason: '식당 변경 실패');
      rethrow;
    }
  }

  Future<void> _pickNotificationTime() async {
    try {
      final picked = await showTimePicker(
        context: context,
        initialTime: TimeOfDay(hour: _settings.notificationHour, minute: _settings.notificationMinute),
      );
      if (picked == null) {
        return;
      }
      final nextSettings = _settings.copyWith(
        notificationHour: picked.hour,
        notificationMinute: picked.minute,
      );
      await _persistSettings(nextSettings);
      if (!mounted) {
        return;
      }
      setState(() {
        _settings = nextSettings;
        _statusMessage = '알림 시간이 저장되었습니다.';
      });
      if (nextSettings.notificationsEnabled) {
        await _rescheduleNotification();
      }
    } catch (error, stackTrace) {
      CrashHandler.recordError(error, stackTrace, reason: '알림 시간 선택 실패');
      rethrow;
    }
  }

  Future<void> _toggleNotifications(bool enabled) async {
    if (_isPermissionRequestInProgress) {
      return;
    }
    if (_settings.campusId == null || _settings.restaurantId == null) {
      if (!mounted) {
        return;
      }
      setState(() {
        _statusMessage = '알림을 켜기 전에 캠퍼스와 식당을 먼저 선택해 주세요.';
      });
      return;
    }
    if (!enabled) {
      final nextSettings = _settings.copyWith(notificationsEnabled: false);
      await _notificationService.cancelAll();
      await _persistSettings(nextSettings);
      if (!mounted) {
        return;
      }
      setState(() {
        _settings = nextSettings;
        _permissionMessage = null;
        _statusMessage = '알림이 꺼졌습니다.';
      });
      return;
    }
    try {
      if (!mounted) {
        return;
      }
      setState(() {
        _isPermissionRequestInProgress = true;
      });
      final granted = await _notificationService.isPermissionGranted();
      bool permissionGranted = granted;
      bool permanentlyDenied = false;
      if (!granted) {
        final result = await _notificationService.requestPermission(context);
        permissionGranted = result.granted;
        permanentlyDenied = result.permanentlyDenied;
      }
      if (!permissionGranted) {
        final nextSettings = _settings.copyWith(notificationsEnabled: false);
        await _persistSettings(nextSettings);
        if (!mounted) {
          return;
        }
        setState(() {
          _settings = nextSettings;
          _permissionMessage = permanentlyDenied
              ? '알림 권한이 영구적으로 거부되었습니다. 시스템 설정에서 다시 허용해 주세요.'
              : '알림 권한이 허용되지 않아 알림이 꺼진 상태로 유지됩니다.';
          _statusMessage = '알림 권한이 없어 알림을 켤 수 없습니다.';
        });
        return;
      }
      final nextSettings = _settings.copyWith(notificationsEnabled: true);
      await _persistSettings(nextSettings);
      _settings = nextSettings;
      await _rescheduleNotification();
      if (!mounted) {
        return;
      }
      setState(() {
        _settings = nextSettings;
        _permissionMessage = null;
        _statusMessage = '알림이 설정되었습니다.';
      });
    } catch (error, stackTrace) {
      CrashHandler.recordError(error, stackTrace, reason: '알림 토글 실패');
      final nextSettings = _settings.copyWith(notificationsEnabled: false);
      await _persistSettings(nextSettings);
      if (!mounted) {
        return;
      }
      setState(() {
        _settings = nextSettings;
        _statusMessage = '알림 설정에 실패했습니다.';
      });
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('알림 예약에 실패했습니다. 다시 시도해 주세요.')),
      );
    } finally {
      if (!mounted) {
        return;
      }
      setState(() {
        _isPermissionRequestInProgress = false;
      });
    }
  }

  Future<void> _rescheduleNotification() async {
    try {
      final restaurant = RestaurantCatalog.findRestaurant(_settings.campusId, _settings.restaurantId);
      final body = _buildNotificationBody();
      await _notificationService.scheduleDailyNotification(
        hour: _settings.notificationHour,
        minute: _settings.notificationMinute,
        title: '${restaurant?.name ?? '선택한 식당'} 메뉴 알림',
        body: body,
      );
    } catch (error, stackTrace) {
      CrashHandler.recordError(error, stackTrace, reason: '알림 재예약 실패');
      final nextSettings = _settings.copyWith(notificationsEnabled: false);
      await _persistSettings(nextSettings);
      if (!mounted) {
        return;
      }
      setState(() {
        _settings = nextSettings;
        _statusMessage = '알림 예약에 실패해 알림이 꺼졌습니다.';
      });
      rethrow;
    }
  }

  String _buildNotificationBody() {
    final menu = _currentMenu ?? _lastSuccessMenu;
    if (menu == null) {
      return '오늘 메뉴가 없거나 최신 메뉴 확인이 필요합니다.';
    }
    if (DateHelper.isToday(menu.baseDate)) {
      return menu.items.take(4).join(', ');
    }
    return '최근 저장 메뉴가 있습니다. 앱에서 최신 메뉴를 확인해 주세요.';
  }

  void _recalculateDateState() {
    if (!mounted) {
      return;
    }
    setState(() {
      if (_currentMenu == null) {
        return;
      }
      _statusMessage = DateHelper.isToday(_currentMenu!.baseDate)
          ? '오늘 기준 메뉴를 표시 중입니다.'
          : '최근 저장 메뉴를 표시 중입니다. 최신 확인이 필요할 수 있습니다.';
    });
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('강원대학교 학식당 메뉴 알림'),
        actions: [
          IconButton(
            key: UniqueKey(),
            onPressed: _isRefreshing ? null : _refreshMenu,
            icon: _isRefreshing
                ? const SizedBox(
                    width: 18,
                    height: 18,
                    child: CircularProgressIndicator(strokeWidth: 2),
                  )
                : const Icon(Icons.refresh),
          ),
        ],
      ),
      body: SingleChildScrollView(
        padding: const EdgeInsets.all(16),
        child: _isLoading
            ? const Center(child: Padding(
                padding: EdgeInsets.all(24),
                child: CircularProgressIndicator(),
              ))
            : Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  if (_statusMessage != null)
                    Padding(
                      padding: const EdgeInsets.only(bottom: 12),
                      child: Text(_statusMessage!),
                    ),
                  MenuSummaryCard(
                    campusId: _settings.campusId,
                    restaurantId: _settings.restaurantId,
                    menuData: _currentMenu ?? _lastSuccessMenu,
                    isInitialSetup: !_initialSetupCompleted,
                  ),
                  const SizedBox(height: 12),
                  SyncStatusCard(
                    syncStatus: _syncStatus,
                    onShowError: () {
                      setState(() {
                        _currentSection = MainSection.error;
                      });
                    },
                  ),
                  const SizedBox(height: 12),
                  SelectionCard(
                    selectedCampusId: _settings.campusId,
                    selectedRestaurantId: _settings.restaurantId,
                    onCampusChanged: (value) {
                      _onCampusChanged(value);
                    },
                    onRestaurantChanged: (value) {
                      _onRestaurantChanged(value);
                    },
                    helperMessage: _lastErrorSummary,
                  ),
                  const SizedBox(height: 12),
                  NotificationSettingsCard(
                    notificationsEnabled: _settings.notificationsEnabled,
                    hour: _settings.notificationHour,
                    minute: _settings.notificationMinute,
                    permissionMessage: _permissionMessage,
                    onToggle: (value) {
                      _toggleNotifications(value);
                    },
                    onPickTime: _pickNotificationTime,
                    onOpenSettings: () {
                      _notificationService.openSettings();
                    },
                  ),
                  const SizedBox(height: 12),
                  Card(
                    child: Padding(
                      padding: const EdgeInsets.all(16),
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Text('설정 및 초기 설정', style: Theme.of(context).textTheme.titleLarge),
                          const SizedBox(height: 8),
                          Text(_initialSetupCompleted
                              ? '현재 설정을 바로 변경할 수 있습니다.'
                              : '앱을 사용하려면 캠퍼스와 식당을 먼저 선택해 주세요.'),
                          const SizedBox(height: 12),
                          FilledButton(
                            key: UniqueKey(),
                            onPressed: () {
                              setState(() {
                                _currentSection = _initialSetupCompleted ? MainSection.settings : MainSection.onboarding;
                              });
                            },
                            child: Text(_initialSetupCompleted ? '설정 보기' : '초기 설정 안내 보기'),
                          ),
                        ],
                      ),
                    ),
                  ),
                  const SizedBox(height: 12),
                  if (_currentSection == MainSection.error || _syncStatus.errorMessage != null)
                    ErrorStatusCard(
                      syncStatus: _syncStatus,
                      onRetry: _refreshMenu,
                    ),
                ],
              ),
      ),
    );
  }
}
