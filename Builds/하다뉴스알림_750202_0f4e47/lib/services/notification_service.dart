import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_local_notifications/flutter_local_notifications.dart';
import 'package:permission_handler/permission_handler.dart';
import 'package:url_launcher/url_launcher.dart';

import '../models/article_item.dart';
import '../models/permission_state.dart';
import 'crash_handler.dart';
import 'local_store.dart';

class NotificationService {
  NotificationService(this._localStore);

  final LocalStore _localStore;
  final FlutterLocalNotificationsPlugin _plugin =
      FlutterLocalNotificationsPlugin();

  Future<void> ensureInitialized() async {
    try {
      const android = AndroidInitializationSettings('@mipmap/ic_launcher');
      const settings = InitializationSettings(android: android);
      await _plugin.initialize(settings);
      await _plugin
          .resolvePlatformSpecificImplementation<
              AndroidFlutterLocalNotificationsPlugin>()
          ?.createNotificationChannel(
        const AndroidNotificationChannel(
          'new_articles',
          '새 글 알림',
          description: '긱뉴스 새 글 알림',
          importance: Importance.defaultImportance,
        ),
      );
    } catch (error, stackTrace) {
      CrashHandler.record(error, stackTrace, reason: 'ensureInitialized');
    }
  }

  Future<AppPermissionState> getCurrentPermissionState() async {
    try {
      final status = await Permission.notification.status;
      final mapped = status.isGranted
          ? '허용'
          : (status.isDenied || status.isPermanentlyDenied)
              ? '거부'
              : '미요청';
      final state = AppPermissionState(notificationPermissionStatus: mapped);
      await _localStore.savePermissionState(state);
      return state;
    } catch (error, stackTrace) {
      CrashHandler.record(error, stackTrace, reason: 'getCurrentPermissionState');
      return AppPermissionState.initial();
    }
  }

  Future<AppPermissionState> requestPermissionWithPrePrompt(
    BuildContext context,
  ) async {
    final proceed = await showDialog<bool>(
          context: context,
          builder: (context) => AlertDialog(
            title: const Text('알림 권한 안내'),
            content: const Text(
              '새 글이 올라오면 알림으로 알려드리기 위해 알림 권한이 필요합니다. 거부해도 피드 열람은 계속 가능하며, 나중에 설정에서 변경할 수 있습니다.',
            ),
            actions: [
              TextButton(
                onPressed: () => Navigator.of(context).pop(false),
                child: const Text('계속 피드 보기'),
              ),
              FilledButton(
                onPressed: () => Navigator.of(context).pop(true),
                child: const Text('권한 요청'),
              ),
            ],
          ),
        ) ??
        false;

    if (!proceed) {
      final state = await getCurrentPermissionState();
      return state;
    }

    try {
      final result = await Permission.notification.request();
      final mapped = result.isGranted ? '허용' : '거부';
      final state = AppPermissionState(notificationPermissionStatus: mapped);
      await _localStore.savePermissionState(state);
      return state;
    } catch (error, stackTrace) {
      CrashHandler.record(error, stackTrace, reason: 'requestPermissionWithPrePrompt');
      return AppPermissionState(notificationPermissionStatus: '거부');
    }
  }

  Future<bool> showNewArticleNotification(ArticleItem article) async {
    try {
      final payload = jsonEncode({'id': article.id, 'link': article.link});
      await _plugin.show(
        article.id.hashCode,
        '새 글이 등록되었습니다',
        article.title,
        const NotificationDetails(
          android: AndroidNotificationDetails(
            'new_articles',
            '새 글 알림',
            channelDescription: '긱뉴스 새 글 알림',
            importance: Importance.defaultImportance,
            priority: Priority.defaultPriority,
          ),
        ),
        payload: payload,
      );
      return true;
    } catch (error, stackTrace) {
      CrashHandler.record(error, stackTrace, reason: 'showNewArticleNotification');
      return false;
    }
  }

  Future<String?> handleLaunchPayload() async {
    try {
      final details = await _plugin.getNotificationAppLaunchDetails();
      final payload = details?.notificationResponse?.payload;
      if (payload == null || payload.isEmpty) {
        return null;
      }
      final decoded = jsonDecode(payload) as Map<String, dynamic>;
      return (decoded['id'] ?? decoded['link']) as String?;
    } catch (error, stackTrace) {
      CrashHandler.record(error, stackTrace, reason: 'handleLaunchPayload');
      return null;
    }
  }

  Future<bool> openSystemSettings() async {
    try {
      return openAppSettings();
    } catch (error, stackTrace) {
      CrashHandler.record(error, stackTrace, reason: 'openSystemSettings');
      return false;
    }
  }

  Future<bool> openAppSettingsOrBrowser(String url) async {
    try {
      final uri = Uri.parse(url);
      if (await canLaunchUrl(uri)) {
        return launchUrl(uri, mode: LaunchMode.externalApplication);
      }
      return false;
    } catch (error, stackTrace) {
      CrashHandler.record(error, stackTrace, reason: 'openAppSettingsOrBrowser');
      return false;
    }
  }
}
