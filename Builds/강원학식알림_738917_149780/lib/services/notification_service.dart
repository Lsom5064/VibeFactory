import 'package:flutter/material.dart';
import 'package:flutter_local_notifications/flutter_local_notifications.dart';
import 'package:permission_handler/permission_handler.dart';
import 'package:timezone/data/latest.dart' as tz;
import 'package:timezone/timezone.dart' as tz;

import '../models/notification_setting.dart';
import '../utils/app_routes.dart';
import 'local_storage_service.dart';
import 'permission_service.dart';

class NotificationService {
  NotificationService({
    required LocalStorageService storage,
    required PermissionService permissionService,
  })  : _storage = storage,
        _permissionService = permissionService;

  final LocalStorageService _storage;
  final PermissionService _permissionService;
  final FlutterLocalNotificationsPlugin _plugin =
      FlutterLocalNotificationsPlugin();

  final ValueNotifier<Map<String, String>?> launchPayloadNotifier =
      ValueNotifier<Map<String, String>?>(null);

  Future<void> initialize() async {
    tz.initializeTimeZones();
    const android = AndroidInitializationSettings('@mipmap/ic_launcher');
    await _plugin.initialize(
      const InitializationSettings(android: android),
      onDidReceiveNotificationResponse: (NotificationResponse response) {
        final payload = response.payload;
        if (payload == null || payload.isEmpty) {
          return;
        }
        final parts = payload.split('|');
        if (parts.length < 3) {
          return;
        }
        launchPayloadNotifier.value = <String, String>{
          AppRoutes.argCampusName: parts[0],
          AppRoutes.argRestaurantName: parts[1],
          AppRoutes.argTargetDate: parts[2],
        };
      },
    );
  }

  Future<bool> requestPermissionWithRationale(BuildContext context) async {
    final approved = await showDialog<bool>(
          context: context,
          builder: (context) => AlertDialog(
            title: const Text('알림 권한 안내'),
            content: const Text(
              '선택한 식당의 메뉴를 원하는 시간에 알려드리기 위해 알림 권한이 필요합니다',
            ),
            actions: [
              TextButton(
                onPressed: () => Navigator.pop(context, false),
                child: const Text('취소'),
              ),
              FilledButton(
                onPressed: () => Navigator.pop(context, true),
                child: const Text('권한 요청'),
              ),
            ],
          ),
        ) ??
        false;
    if (!approved) {
      return false;
    }
    final status = await Permission.notification.request();
    return status.isGranted;
  }

  Future<void> scheduleRestaurantNotification(
    RestaurantNotificationSetting setting,
    String targetDate,
  ) async {
    if (!setting.isEnabled ||
        setting.notificationTime.isEmpty ||
        setting.repeatDays.isEmpty) {
      return;
    }

    final permission = await _permissionService.getNotificationPermissionStatus();
    if (!permission.isGranted) {
      throw Exception('알림 권한이 없어 실제 알림을 등록하지 못했습니다');
    }

    final parts = setting.notificationTime.split(':');
    final hour = int.tryParse(parts.first) ?? 8;
    final minute = int.tryParse(parts.length > 1 ? parts[1] : '0') ?? 0;
    final now = tz.TZDateTime.now(tz.local);
    var scheduled = tz.TZDateTime(tz.local, now.year, now.month, now.day, hour, minute);
    if (scheduled.isBefore(now)) {
      scheduled = scheduled.add(const Duration(days: 1));
    }

    await _plugin.zonedSchedule(
      _stableId(setting.campusName, setting.restaurantName),
      '${setting.restaurantName} 메뉴 알림',
      '${setting.campusName} ${setting.restaurantName} 식단을 확인해 보세요.',
      scheduled,
      NotificationDetails(
        android: AndroidNotificationDetails(
          'gangwon_meal_alert',
          '강원학식알림',
          channelDescription: '식당 메뉴 알림',
          importance: Importance.high,
          priority: Priority.high,
        ),
      ),
      androidScheduleMode: AndroidScheduleMode.exactAllowWhileIdle,
      uiLocalNotificationDateInterpretation:
          UILocalNotificationDateInterpretation.absoluteTime,
      matchDateTimeComponents: DateTimeComponents.time,
      payload: '${setting.campusName}|${setting.restaurantName}|$targetDate',
    );
  }

  Future<void> cancelRestaurantNotification(
    String campusName,
    String restaurantName,
  ) async {
    await _plugin.cancel(_stableId(campusName, restaurantName));
  }

  Future<void> rescheduleAllAfterBoot(String targetDate) async {
    final settings = await _storage.loadNotificationSettings();
    for (final setting in settings.where((item) => item.isEnabled)) {
      try {
        await scheduleRestaurantNotification(setting, targetDate);
      } catch (_) {}
    }
  }

  int _stableId(String campusName, String restaurantName) {
    return Object.hash(campusName, restaurantName) & 0x7fffffff;
  }
}
