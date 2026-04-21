import 'dart:async';
import 'dart:ui';

import 'package:flutter/material.dart';

import 'app.dart';
import 'services/crash_handler.dart';

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  CrashHandler.initialize("750202_0f4e47", "kr.ac.kangwon.hai.hadanewsalert.t750202_0f4e47");

  FlutterError.onError = (details) {
    CrashHandler.record(details.exception, details.stack, reason: 'FlutterError');
  };

  PlatformDispatcher.instance.onError = (error, stackTrace) {
    CrashHandler.record(error, stackTrace, reason: 'PlatformDispatcher');
    return true;
  };

  await AppServices.notificationService.ensureInitialized();
  final initialPayload =
      await AppServices.notificationService.handleLaunchPayload();

  runZonedGuarded(
    () {
      runApp(
        MyApp(
          initialPayload: initialPayload,
          repository: AppServices.repository,
          notificationService: AppServices.notificationService,
          backgroundCheckService: AppServices.backgroundCheckService,
        ),
      );
    },
    (error, stackTrace) {
      CrashHandler.record(error, stackTrace, reason: 'runZonedGuarded');
    },
  );
}
