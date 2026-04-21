import 'dart:developer' as developer;

class CrashHandler {
  static late String _taskId;
  static late String _packageName;
  static bool _initialized = false;

  static void initialize(String taskId, String packageName) {
    _taskId = taskId;
    _packageName = packageName;
    _initialized = true;
    developer.log(
      'CrashHandler initialized for $taskId / $packageName',
      name: 'CrashHandler',
    );
  }

  static void record(
    Object error,
    StackTrace? stackTrace, {
    String reason = '',
  }) {
    final prefix = _initialized ? '[$_taskId][$_packageName]' : '[uninitialized]';
    developer.log(
      '$prefix $reason ${error.toString()}',
      name: 'CrashHandler',
      error: error,
      stackTrace: stackTrace,
    );
  }
}
