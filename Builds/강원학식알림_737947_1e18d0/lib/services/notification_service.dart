import 'package:flutter/material.dart';
import 'package:flutter_local_notifications/flutter_local_notifications.dart';
import 'package:permission_handler/permission_handler.dart';
import 'package:timezone/data/latest.dart' as tz;
import 'package:timezone/timezone.dart' as tz;

import '../models/menu_cache_item.dart';
import '../models/user_settings.dart';
import 'crash_handler.dart';
import 'menu_repository.dart';
import 'settings_repository.dart';

class NotificationService {
  NotificationService({
    FlutterLocalNotificationsPlugin? plugin,
    SettingsRepository? settingsRepository,
    MenuRepository? menuRepository,
  })  : _plugin = plugin ?? FlutterLocalNotificationsPlugin(),
        _settingsRepository = settingsRepository ?? SettingsRepository(),
        _menuRepository = menuRepository ?? MenuRepository();

  final FlutterLocalNotificationsPlugin _plugin;
  final SettingsRepository _settingsRepository;
  final MenuRepository _menuRepository;

  Future<void> initialize() async {
    try {
      tz.initializeTimeZones();
      const AndroidInitializationSettings androidSettings =
          AndroidInitializationSettings('@mipmap/ic_launcher');
      const InitializationSettings settings =
          InitializationSettings(android: androidSettings);
      await _plugin.initialize(settings);

      const AndroidNotificationChannel channel = AndroidNotificationChannel(
        'knu_meal_daily',
        '강원 학식 알림',
        description: '매일 오전 8시에 선택한 식당 메뉴를 알려줍니다.',
        importance: Importance.high,
      );
      await _plugin
          .resolvePlatformSpecificImplementation<
              AndroidFlutterLocalNotificationsPlugin>()
          ?.createNotificationChannel(channel);
    } catch (error, stackTrace) {
      await CrashHandler.logError(error, stackTrace);
    }
  }

  Future<bool> checkNotificationPermission() async {
    try {
      return await Permission.notification.isGranted;
    } catch (error, stackTrace) {
      await CrashHandler.logError(error, stackTrace);
      return false;
    }
  }

  Future<bool> requestNotificationPermissionWithPrePrompt(
    BuildContext context,
  ) async {
    try {
      final bool? proceed = await showDialog<bool>(
        context: context,
        builder: (BuildContext dialogContext) {
          return AlertDialog(
            title: const Text('알림 권한 안내'),
            content: const Text(
              '매일 오전 8시에 선택한 식당 메뉴를 알려드리기 위해 알림 권한이 필요합니다.',
            ),
            actions: <Widget>[
              TextButton(
                onPressed: () => Navigator.of(dialogContext).pop(false),
                child: const Text('나중에'),
              ),
              FilledButton(
                onPressed: () => Navigator.of(dialogContext).pop(true),
                child: const Text('권한 요청'),
              ),
            ],
          );
        },
      );
      if (proceed != true) {
        return false;
      }
      final PermissionStatus status = await Permission.notification.request();
      return status.isGranted;
    } catch (error, stackTrace) {
      await CrashHandler.logError(error, stackTrace);
      return false;
    }
  }

  Future<void> scheduleDaily8amNotification() async {
    try {
      final UserSettings settings = await _settingsRepository.loadUserSettings();
      if (!settings.notificationEnabled || settings.selectedRestaurantName.isEmpty) {
        return;
      }

      final bool permissionGranted = await checkNotificationPermission();
      if (!permissionGranted) {
        return;
      }

      final MenuFetchResult result = await _menuRepository.fetchAndCacheTodayMenus();
      final List<MenuCacheItem> items = result.items;
      final MenuCacheItem? selectedMenu = _findSelectedMenu(
        items,
        settings.selectedRestaurantName,
      );
      if (selectedMenu == null) {
        return;
      }

      final String body = result.usedCache
          ? '${selectedMenu.menuText}\n\n최신이 아닐 수 있는 캐시 기준 메뉴입니다.'
          : selectedMenu.menuText;

      await _plugin.zonedSchedule(
        8001,
        '${settings.selectedRestaurantName} 오늘 메뉴',
        body,
        _next8am(),
        const NotificationDetails(
          android: AndroidNotificationDetails(
            'knu_meal_daily',
            '강원 학식 알림',
            channelDescription: '매일 오전 8시에 선택한 식당 메뉴를 알려줍니다.',
            importance: Importance.high,
            priority: Priority.high,
          ),
        ),
        androidScheduleMode: AndroidScheduleMode.exactAllowWhileIdle,
        uiLocalNotificationDateInterpretation:
            UILocalNotificationDateInterpretation.absoluteTime,
        matchDateTimeComponents: DateTimeComponents.time,
      );
    } catch (error, stackTrace) {
      await CrashHandler.logError(error, stackTrace);
      rethrow;
    }
  }

  Future<void> cancelDailyNotification() async {
    try {
      await _plugin.cancel(8001);
    } catch (error, stackTrace) {
      await CrashHandler.logError(error, stackTrace);
      rethrow;
    }
  }

  MenuCacheItem? _findSelectedMenu(List<MenuCacheItem> items, String restaurantName) {
    for (final MenuCacheItem item in items) {
      if (item.restaurantName.trim() == restaurantName.trim()) {
        return item;
      }
    }
    return null;
  }

  tz.TZDateTime _next8am() {
    final tz.TZDateTime now = tz.TZDateTime.now(tz.local);
    tz.TZDateTime scheduled = tz.TZDateTime(tz.local, now.year, now.month, now.day, 8);
    if (scheduled.isBefore(now)) {
      scheduled = scheduled.add(const Duration(days: 1));
    }
    return scheduled;
  }
}
