import 'package:flutter/foundation.dart';

class CrashHandler {
  const CrashHandler._();

  static String? _projectId;
  static String? _packageName;

  static void initialize(String projectId, String packageName) {
    _projectId = projectId;
    _packageName = packageName;
    debugPrint('CrashHandler initialized: $_projectId / $_packageName');
  }

  static void recordError(
    Object error,
    StackTrace stackTrace, {
    String? reason,
  }) {
    final prefix = reason == null || reason.trim().isEmpty
        ? 'CrashHandler error'
        : 'CrashHandler error: $reason';
    debugPrint(prefix);
    debugPrint(error.toString());
    debugPrint(stackTrace.toString());
  }
}
