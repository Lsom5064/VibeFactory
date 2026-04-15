import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter_local_notifications/flutter_local_notifications.dart';
import 'package:timezone/data/latest.dart' as tz;
import 'package:timezone/timezone.dart' as tz;

class NotificationPermissionResult {
  final bool granted;
  final bool permanentlyDenied;

  const NotificationPermissionResult({
    required this.granted,
    required this.permanentlyDenied,
  });
}

class NotificationService {
  NotificationService();

  static final FlutterLocalNotificationsPlugin _plugin =
      FlutterLocalNotificationsPlugin();
  static bool _initialized = false;

  Future<void> initialize() async {
    if (_initialized) {
      return;
    }

    try {
      tz.initializeTimeZones();
      tz.setLocalLocation(tz.getLocation('Asia/Seoul'));

      const androidSettings = AndroidInitializationSettings('@mipmap/ic_launcher');
      const initializationSettings = InitializationSettings(
        android: androidSettings,
      );

      await _plugin.initialize(initializationSettings);
      _initialized = true;
    } catch (error, stackTrace) {
      FlutterError.reportError(
        FlutterErrorDetails(
          exception: error,
          stack: stackTrace,
          library: 'notification_service',
          context: ErrorDescription('알림 서비스 초기화 중 오류'),
        ),
      );
      rethrow;
    }
  }

  Future<void> cancelAll() async {
    try {
      await _plugin.cancelAll();
    } catch (error, stackTrace) {
      FlutterError.reportError(
        FlutterErrorDetails(
          exception: error,
          stack: stackTrace,
          library: 'notification_service',
          context: ErrorDescription('알림 전체 취소 중 오류'),
        ),
      );
      rethrow;
    }
  }

  Future<bool> isPermissionGranted() async {
    try {
      final androidImplementation =
          _plugin.resolvePlatformSpecificImplementation<
              AndroidFlutterLocalNotificationsPlugin>();
      final granted = await androidImplementation?.areNotificationsEnabled();
      return granted ?? true;
    } catch (error, stackTrace) {
      FlutterError.reportError(
        FlutterErrorDetails(
          exception: error,
          stack: stackTrace,
          library: 'notification_service',
          context: ErrorDescription('알림 권한 확인 중 오류'),
        ),
      );
      return true;
    }
  }

  Future<NotificationPermissionResult> requestPermission(BuildContext context) async {
    try {
      final androidImplementation =
          _plugin.resolvePlatformSpecificImplementation<
              AndroidFlutterLocalNotificationsPlugin>();
      final granted = await androidImplementation?.requestNotificationsPermission();
      await androidImplementation?.requestExactAlarmsPermission();
      return NotificationPermissionResult(
        granted: granted ?? true,
        permanentlyDenied: false,
      );
    } catch (error, stackTrace) {
      FlutterError.reportError(
        FlutterErrorDetails(
          exception: error,
          stack: stackTrace,
          library: 'notification_service',
          context: ErrorDescription('알림 권한 요청 중 오류'),
        ),
      );
      return const NotificationPermissionResult(
        granted: false,
        permanentlyDenied: false,
      );
    }
  }

  Future<void> scheduleDailyNotification({
    required int hour,
    required int minute,
    required String title,
    required String body,
  }) async {
    try {
      if (!_initialized) {
        await initialize();
      }

      final now = tz.TZDateTime.now(tz.local);
      var scheduled = tz.TZDateTime(
        tz.local,
        now.year,
        now.month,
        now.day,
        hour,
        minute,
      );

      if (!scheduled.isAfter(now)) {
        scheduled = scheduled.add(const Duration(days: 1));
      }

      const androidDetails = AndroidNotificationDetails(
        'meal_alert_channel',
        '학식 알림',
        channelDescription: '강원대 학식 알림 채널',
        importance: Importance.max,
        priority: Priority.high,
      );

      const details = NotificationDetails(android: androidDetails);

      await _plugin.zonedSchedule(
        1001,
        title,
        body,
        scheduled,
        details,
        androidScheduleMode: AndroidScheduleMode.exactAllowWhileIdle,
        matchDateTimeComponents: DateTimeComponents.time,
        uiLocalNotificationDateInterpretation:
            UILocalNotificationDateInterpretation.absoluteTime,
      );
    } catch (error, stackTrace) {
      FlutterError.reportError(
        FlutterErrorDetails(
          exception: error,
          stack: stackTrace,
          library: 'notification_service',
          context: ErrorDescription('일일 알림 예약 중 오류'),
        ),
      );
      rethrow;
    }
  }

  Future<void> openSettings() async {
    try {
      final androidImplementation =
          _plugin.resolvePlatformSpecificImplementation<
              AndroidFlutterLocalNotificationsPlugin>();
      await androidImplementation?.requestNotificationsPermission();
    } catch (error, stackTrace) {
      FlutterError.reportError(
        FlutterErrorDetails(
          exception: error,
          stack: stackTrace,
          library: 'notification_service',
          context: ErrorDescription('알림 설정 열기 중 오류'),
        ),
      );
    }
  }
}
