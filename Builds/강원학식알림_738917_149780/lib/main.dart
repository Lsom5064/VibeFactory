import 'dart:async';

import 'package:flutter/material.dart';

import 'app.dart';
import 'services/crash_handler.dart';

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  await CrashHandler.initialize("738917_149780", "kr.ac.kangwon.hai.gangwonmealalert.t738917_149780");
  runZonedGuarded(
    () {
      runApp(const MyApp());
    },
    (error, stackTrace) {
      CrashHandler.recordError(error, stackTrace, fatal: true);
    },
  );
}
