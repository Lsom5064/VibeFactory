import 'package:flutter/foundation.dart';

class CrashHandlerBridge {
  const CrashHandlerBridge._();

  static Future<void> report(
    Object error,
    StackTrace stackTrace, {
    String? context,
    bool rethrowError = false,
  }) async {
    try {
      if (context != null && context.isNotEmpty) {
        debugPrint('오류 맥락: $context');
      }
      debugPrint('오류 보고: $error');
      debugPrint('$stackTrace');
    } catch (_) {
      // 보고 중 예외는 무시합니다.
    }

    if (rethrowError) {
      Error.throwWithStackTrace(error, stackTrace);
    }
  }
}
