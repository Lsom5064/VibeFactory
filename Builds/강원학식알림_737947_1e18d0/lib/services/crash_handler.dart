import 'dart:async';

import 'package:flutter/services.dart';

class CrashHandler {
  static const MethodChannel _channel = MethodChannel('kr.ac.kangwon.hai/crash');

  static String _taskId = '';
  static String _packageName = '';
  static bool _initialized = false;

  static Future<void> initialize(String taskId, String packageName) async {
    _taskId = taskId;
    _packageName = packageName;
    _initialized = true;
  }

  static Future<void> logError(Object error, StackTrace stackTrace) async {
    if (!_initialized) {
      return;
    }
    try {
      await _channel.invokeMethod<void>('reportCrash', <String, String>{
        'task_id': _taskId,
        'package_name': _packageName,
        'stack_trace': '$error\n$stackTrace',
      });
    } on PlatformException {
      unawaited(Future<void>.value());
    }
  }
}
