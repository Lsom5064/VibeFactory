import 'dart:developer' as developer;

import 'package:flutter/foundation.dart';

class CrashHandler {
  static String? _taskId;
  static String? _packageName;

  static void initialize(String taskId, String packageName) {
    _taskId = taskId;
    _packageName = packageName;
    developer.log(
      'CrashHandler initialized for $taskId / $packageName',
      name: 'CrashHandler',
    );
  }

  static void recordError(
    Object error,
    StackTrace stackTrace, {
    String context = 'unknown',
    bool fatal = false,
  }) {
    developer.log(
      '[$context] $error',
      name: 'CrashHandler',
      error: error,
      stackTrace: stackTrace,
      level: fatal ? 1000 : 900,
    );
    if (kDebugMode) {
      debugPrint('CrashHandler($_taskId,$_packageName) [$context] $error');
      debugPrintStack(stackTrace: stackTrace);
    }
  }
}
