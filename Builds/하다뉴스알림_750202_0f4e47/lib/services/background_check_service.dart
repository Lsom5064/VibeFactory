import 'package:workmanager/workmanager.dart';

import 'crash_handler.dart';
import 'feed_repository.dart';

class BackgroundCheckService {
  BackgroundCheckService(this._repository);

  final FeedRepository _repository;

  Future<void> scheduleConservativeChecks() async {
    try {
      await Workmanager().initialize((task, inputData) async {
        return true;
      });
      await Workmanager().registerPeriodicTask(
        'hada-news-check',
        'hada-news-check',
        frequency: const Duration(hours: 6),
        existingWorkPolicy: ExistingPeriodicWorkPolicy.keep,
      );
    } catch (error, stackTrace) {
      CrashHandler.record(error, stackTrace, reason: 'scheduleConservativeChecks failed');
    }
  }

  Future<void> runCheckNow() async {
    try {
      await _repository.syncLatest(forBackground: true);
    } catch (error, stackTrace) {
      CrashHandler.record(error, stackTrace, reason: 'runCheckNow failed');
    }
  }
}
