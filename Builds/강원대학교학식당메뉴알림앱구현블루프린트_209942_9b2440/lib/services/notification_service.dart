import 'package:flutter/material.dart';
import 'package:flutter_local_notifications/flutter_local_notifications.dart';
import 'package:permission_handler/permission_handler.dart';

import '../crash_handler.dart';

class NotificationPermissionResult {
  final bool granted;
  final bool permanentlyDenied;

  const NotificationPermissionResult({
    required this.granted,
    required this.permanentlyDenied,
  });
}

class NotificationService {
  final FlutterLocalNotificationsPlugin _plugin = FlutterLocalNotificationsPlugin();

  Future<void> initialize() async {
    try {
      const android = AndroidInitializationSettings('@mipmap/ic_launcher');
      const settings = InitializationSettings(android: android);
      await _plugin.initialize(settings);
      CrashHandler.recordMessage('알림 초기화 완료');
    } catch (error, stackTrace) {
      CrashHandler.recordError(error, stackTrace, reason: '알림 초기화 실패');
      rethrow;
    }
  }

  Future<bool> isPermissionGranted() async {
    try {
      final status = await Permission.notification.status;
      return status.isGranted;
    } catch (error, stackTrace) {
      CrashHandler.recordError(error, stackTrace, reason: '알림 권한 확인 실패');
      rethrow;
    }
  }

  Future<NotificationPermissionResult> requestPermission(BuildContext context) async {
    try {
      final agreed = await showDialog<bool>(
            context: context,
            builder: (dialogContext) {
              return AlertDialog(
                title: const Text('알림 권한 안내'),
                content: const Text('매일 선택한 식당의 메뉴를 제시간에 알려드리기 위해 알림 권한이 필요합니다.'),
                actions: [
                  TextButton(
                    onPressed: () => Navigator.of(dialogContext).pop(false),
                    child: const Text('취소'),
                  ),
                  FilledButton(
                    onPressed: () => Navigator.of(dialogContext).pop(true),
                    child: const Text('계속'),
                  ),
                ],
              );
            },
          ) ??
          false;
      if (!agreed) {
        return const NotificationPermissionResult(granted: false, permanentlyDenied: false);
      }
      final status = await Permission.notification.request();
      return NotificationPermissionResult(
        granted: status.isGranted,
        permanentlyDenied: status.isPermanentlyDenied,
      );
    } catch (error, stackTrace) {
      CrashHandler.recordError(error, stackTrace, reason: '알림 권한 요청 실패');
      rethrow;
    }
  }

  Future<void> openSettings() async {
    try {
      await openAppSettings();
    } catch (error, stackTrace) {
      CrashHandler.recordError(error, stackTrace, reason: '시스템 설정 열기 실패');
      rethrow;
    }
  }

  Future<void> cancelAll() async {
    try {
      await _plugin.cancelAll();
    } catch (error, stackTrace) {
      CrashHandler.recordError(error, stackTrace, reason: '알림 취소 실패');
      rethrow;
    }
  }

  Future<void> scheduleDailyNotification({
    required int hour,
    required int minute,
    required String title,
    required String body,
  }) async {
    try {
      await cancelAll();
      final now = DateTime.now();
      var scheduled = DateTime(now.year, now.month, now.day, hour, minute);
      if (!scheduled.isAfter(now)) {
        scheduled = scheduled.add(const Duration(days: 1));
      }
      final androidDetails = AndroidNotificationDetails(
        'meal_alert_channel',
        '학식당 메뉴 알림',
        channelDescription: '매일 학식당 메뉴를 알려주는 알림 채널',
        importance: Importance.max,
        priority: Priority.high,
      );
      final details = NotificationDetails(android: androidDetails);
      await _plugin.zonedSchedule(
        1001,
        title,
        body,
        scheduled,
        details,
        androidScheduleMode: AndroidScheduleMode.exactAllowWhileIdle,
        matchDateTimeComponents: DateTimeComponents.time,
        uiLocalNotificationDateInterpretation: UILocalNotificationDateInterpretation.absoluteTime,
      );
      CrashHandler.recordMessage('알림 예약 완료');
    } catch (error, stackTrace) {
      CrashHandler.recordError(error, stackTrace, reason: '알림 예약 실패');
      rethrow;
    }
  }
}
