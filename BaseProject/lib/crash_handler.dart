import 'dart:async';
import 'package:flutter/services.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter/widgets.dart';

class CrashHandler {
  static const MethodChannel _channel = MethodChannel('kr.ac.kangwon.hai/crash');
  static String taskId = "unknown";
  static String packageName = "unknown_app";
  static bool _firstFrameRendered = false;
  static bool _crashReportSent = false;
  static bool _initialized = false;

  static void _log(String message) {
    debugPrint("[CrashHandler] $message");
  }

  static void _markFirstFrameRendered() {
    if (_firstFrameRendered) {
      return;
    }
    _firstFrameRendered = true;
    _log("first frame rendered");
  }

  static Future<void> report(
    String error,
    String stack, {
    bool allowDuplicate = false,
    String reportKind = "manual_report",
    String? userMessage,
  }) async {
    if (!allowDuplicate && _crashReportSent) {
      _log("duplicate crash report suppressed");
      return;
    }
    if (!allowDuplicate) {
      _crashReportSent = true;
    }

    try {
      await _channel.invokeMethod("reportCrash", {
        "task_id": taskId,
        "package_name": packageName,
        "error_message": error,
        "stack_trace": stack,
        "report_kind": reportKind,
        "user_message": userMessage,
      });
    } catch (e) {
      _log("warning: crash report delivery failed");
    }
  }

  static void _reportFrameworkError(FlutterErrorDetails details) {
    final stack = details.stack?.toString() ?? StackTrace.current.toString();
    final exception = details.exceptionAsString();
    unawaited(
      report(
        exception,
        stack,
        reportKind: "framework_error",
      ),
    );
  }

  static void initialize(String task, String pkg) {
    if (_initialized) {
      return;
    }
    _initialized = true;
    taskId = task;
    packageName = pkg;
    _firstFrameRendered = false;
    _crashReportSent = false;

    WidgetsBinding.instance.addPostFrameCallback((_) {
      _markFirstFrameRendered();
    });

    FlutterError.onError = (FlutterErrorDetails details) {
      FlutterError.presentError(details);
      _log("framework error reported: ${details.exceptionAsString()}");
      _reportFrameworkError(details);
    };

    PlatformDispatcher.instance.onError = (error, stack) {
      if (!_firstFrameRendered) {
        _log("uncaught fatal error before first frame; sending crash report");
      } else {
        _log("uncaught error after first frame reported: $error");
      }
      unawaited(
        report(
          error.toString(),
          stack.toString(),
          reportKind: _firstFrameRendered ? "uncaught_error" : "fatal_uncaught_error",
        ),
      );
      return true;
    };
  }
}
