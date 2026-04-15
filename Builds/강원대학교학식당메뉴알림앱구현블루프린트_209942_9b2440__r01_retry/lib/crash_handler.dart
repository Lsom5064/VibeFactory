import 'package:flutter/foundation.dart';

class CrashHandler {
  static String? _projectId;
  static String? _packageName;

  static void initialize(String projectId, String packageName) {
    _projectId = projectId;
    _packageName = packageName;
  }

  static void recordError(Object error, StackTrace stackTrace, {String? reason}) {
    FlutterError.reportError(
      FlutterErrorDetails(
        exception: error,
        stack: stackTrace,
        library: 'crash_handler',
        context: ErrorDescription(reason ?? '알 수 없는 오류'),
        informationCollector: () sync* {
          if (_projectId != null) {
            yield DiagnosticsProperty<String>('projectId', _projectId);
          }
          if (_packageName != null) {
            yield DiagnosticsProperty<String>('packageName', _packageName);
          }
        },
      ),
    );
  }
}
