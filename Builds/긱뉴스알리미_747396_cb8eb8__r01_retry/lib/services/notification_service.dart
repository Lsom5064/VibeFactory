import 'package:flutter_local_notifications/flutter_local_notifications.dart';

import '../models/feed_item.dart';
import 'crash_handler.dart';

class NotificationService {
  final FlutterLocalNotificationsPlugin _plugin =
      FlutterLocalNotificationsPlugin();

  Future<void> initialize() async {
    try {
      const android = AndroidInitializationSettings('@mipmap/ic_launcher');
      const settings = InitializationSettings(android: android);
      await _plugin.initialize(settings);
    } catch (error, stackTrace) {
      CrashHandler.recordError(
        error,
        stackTrace,
        context: 'NotificationService.initialize',
      );
    }
  }

  Future<bool> showNewFeedNotification(FeedItem item) async {
    try {
      const details = NotificationDetails(
        android: AndroidNotificationDetails(
          'geeknews_new_feed',
          '긱뉴스 새 글',
          channelDescription: '긱뉴스 최신 글 알림',
          importance: Importance.high,
          priority: Priority.high,
        ),
      );
      await _plugin.show(
        item.cacheKey.hashCode,
        '긱뉴스 새 글',
        item.title,
        details,
      );
      return true;
    } catch (error, stackTrace) {
      CrashHandler.recordError(
        error,
        stackTrace,
        context: 'NotificationService.showNewFeedNotification',
      );
      return false;
    }
  }
}
