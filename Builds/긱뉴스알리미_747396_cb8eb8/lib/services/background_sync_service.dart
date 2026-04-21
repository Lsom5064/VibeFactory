import 'package:workmanager/workmanager.dart';

import 'crash_handler.dart';
import 'feed_service.dart';
import 'local_store.dart';
import 'notification_service.dart';

class BackgroundSyncService {
  static const String taskName = 'geeknews_periodic_sync';

  final LocalStore localStore;
  final FeedService feedService;
  final NotificationService notificationService;

  BackgroundSyncService({
    required this.localStore,
    required this.feedService,
    required this.notificationService,
  });

  Future<void> initialize() async {
    try {
      await Workmanager().initialize(_callbackDispatcher);
    } catch (error, stackTrace) {
      CrashHandler.recordError(
        error,
        stackTrace,
        context: 'BackgroundSyncService.initialize',
      );
    }
  }

  Future<void> registerPeriodicSync() async {
    try {
      await Workmanager().registerPeriodicTask(
        taskName,
        taskName,
        frequency: const Duration(hours: 1),
        existingWorkPolicy: ExistingPeriodicWorkPolicy.update,
      );
    } catch (error, stackTrace) {
      CrashHandler.recordError(
        error,
        stackTrace,
        context: 'BackgroundSyncService.registerPeriodicSync',
      );
    }
  }

  Future<void> runSyncTask() async {
    try {
      final cache = await localStore.loadFeedCache();
      final status = await localStore.loadSyncStatus();
      final settings = await localStore.loadUserSettings();
      final permission = await localStore.loadPermissionStatus();

      final result = await feedService.syncFeed(
        existingCache: cache,
        previousStatus: status,
        forceRefresh: true,
      );

      if (result.success) {
        await localStore.saveFeedCache(result.items);
      }
      await localStore.saveSyncStatus(result.syncStatus);

      if (result.hasNewItem &&
          settings.notificationsEnabled &&
          settings.backgroundSyncAllowed &&
          permission.notificationPermissionGranted &&
          result.latestItem != null) {
        final shown =
            await notificationService.showNewFeedNotification(result.latestItem!);
        if (shown) {
          final updatedSettings = settings.copyWith(lastNotificationAt: DateTime.now());
          await localStore.saveUserSettings(updatedSettings);
        }
      }
    } catch (error, stackTrace) {
      CrashHandler.recordError(
        error,
        stackTrace,
        context: 'BackgroundSyncService.runSyncTask',
      );
    }
  }
}

@pragma('vm:entry-point')
void _callbackDispatcher() {
  Workmanager().executeTask((task, inputData) async {
    final localStore = LocalStore();
    final feedService = FeedService();
    final notificationService = NotificationService();
    final backgroundSyncService = BackgroundSyncService(
      localStore: localStore,
      feedService: feedService,
      notificationService: notificationService,
    );

    try {
      await notificationService.initialize();
      if (task == BackgroundSyncService.taskName) {
        await backgroundSyncService.runSyncTask();
      }
      return true;
    } catch (error, stackTrace) {
      CrashHandler.recordError(
        error,
        stackTrace,
        context: 'BackgroundSyncService.callbackDispatcher',
      );
      return true;
    }
  });
}
