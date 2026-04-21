import 'dart:async';
import 'dart:ui';

import 'package:flutter/material.dart';

import 'app.dart';
import 'services/crash_handler.dart';

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  await CrashHandler.initialize(
    '749420_c805ba',
    'kr.ac.kangwon.hai.geeknewsfeed.t749420_c805ba',
  );

  FlutterError.onError = (FlutterErrorDetails details) {
    CrashHandler.recordError(details.exception, details.stack, fatal: false);
    FlutterError.presentError(details);
  };

  PlatformDispatcher.instance.onError = (error, stack) {
    CrashHandler.recordError(error, stack, fatal: true);
    return true;
  };

  runZonedGuarded(
    () => runApp(const MyApp()),
    (error, stack) => CrashHandler.recordError(error, stack, fatal: true),
  );
}
