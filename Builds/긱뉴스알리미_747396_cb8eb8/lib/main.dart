import 'dart:async';
import 'dart:ui';

import 'package:flutter/material.dart';

import 'app.dart';
import 'services/crash_handler.dart';

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  CrashHandler.initialize("747396_cb8eb8", "kr.ac.kangwon.hai.geeknewsalert.t747396_cb8eb8");

  FlutterError.onError = (details) {
    CrashHandler.recordError(
      details.exception,
      details.stack ?? StackTrace.current,
      context: 'FlutterError.onError',
      fatal: true,
    );
  };

  PlatformDispatcher.instance.onError = (error, stackTrace) {
    CrashHandler.recordError(
      error,
      stackTrace,
      context: 'PlatformDispatcher.onError',
      fatal: true,
    );
    return false;
  };

  await runZonedGuarded(
    () async {
      runApp(const MyApp());
    },
    (error, stackTrace) {
      CrashHandler.recordError(
        error,
        stackTrace,
        context: 'runZonedGuarded',
        fatal: true,
      );
    },
  );
}
