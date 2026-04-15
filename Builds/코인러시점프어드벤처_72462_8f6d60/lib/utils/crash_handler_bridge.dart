import 'package:flutter/foundation.dart';
import '../crash_handler.dart';

class CrashHandlerBridge {
  const CrashHandlerBridge._();

  static Future<void> report(
    Object error,
    StackTrace stackTrace, {
    String context = '알 수 없는 오류',
    bool rethrowError = false,
  }) async {
    try {
      debugPrint('크래시 보고: $context');
      debugPrint('$error');
      debugPrint('$stackTrace');
    } catch (reportingError, reportingStack) {
      debugPrint('크래시 보고 실패: $reportingError');
      debugPrint('$reportingStack');
    }

    if (rethrowError) {
      Error.throwWithStackTrace(error, stackTrace);
    }
  }
}
