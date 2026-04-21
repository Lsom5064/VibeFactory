import 'package:flutter/services.dart';

import 'crash_handler.dart';
import 'notification_service.dart';
import 'settings_repository.dart';

class BootRescheduleService {
  BootRescheduleService({
    SettingsRepository? settingsRepository,
    NotificationService? notificationService,
  })  : _settingsRepository = settingsRepository ?? SettingsRepository(),
        _notificationService = notificationService ?? NotificationService();

  final SettingsRepository _settingsRepository;
  final NotificationService _notificationService;

  Future<void> handleBootCompleted() async {
    try {
      await _notificationService.initialize();
      final settings = await _settingsRepository.loadUserSettings();
      if (settings.notificationEnabled && settings.selectedRestaurantName.isNotEmpty) {
        await _notificationService.scheduleDaily8amNotification();
      }
    } on PlatformException catch (error, stackTrace) {
      await CrashHandler.logError(error, stackTrace);
    } catch (error, stackTrace) {
      await CrashHandler.logError(error, stackTrace);
    }
  }
}
