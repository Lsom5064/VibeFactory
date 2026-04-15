import 'dart:developer' as developer;

class CrashHandler {
  static String? _appId;
  static String? _packageName;

  static void initialize(String appId, String packageName) {
    _appId = appId;
    _packageName = packageName;
    developer.log(
      '크래시 핸들러 초기화 완료',
      name: 'CrashHandler',
      error: {
        'appId': _appId,
        'packageName': _packageName,
      },
    );
  }

  static void recordError(
    Object error,
    StackTrace stackTrace, {
    String? reason,
  }) {
    developer.log(
      reason ?? '처리되지 않은 오류가 기록되었습니다.',
      name: 'CrashHandler',
      error: error,
      stackTrace: stackTrace,
    );
  }
}
