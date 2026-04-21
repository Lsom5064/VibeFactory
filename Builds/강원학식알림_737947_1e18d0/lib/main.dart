import 'dart:async';
import 'dart:ui';

import 'package:flutter/material.dart';

import 'app.dart';
import 'services/crash_handler.dart';
import 'services/notification_service.dart';

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  await CrashHandler.initialize("737947_1e18d0", "kr.ac.kangwon.hai.knumealalert.t737947_1e18d0");
  final NotificationService notificationService = NotificationService();
  await notificationService.initialize();

  FlutterError.onError = (FlutterErrorDetails details) {
    FlutterError.presentError(details);
    unawaited(CrashHandler.logError(details.exception, details.stack ?? StackTrace.current));
  };

  PlatformDispatcher.instance.onError = (Object error, StackTrace stackTrace) {
    unawaited(CrashHandler.logError(error, stackTrace));
    return true;
  };

  runZonedGuarded(
    () => runApp(const MyApp()),
    (Object error, StackTrace stackTrace) {
      unawaited(CrashHandler.logError(error, stackTrace));
    },
  );
}
