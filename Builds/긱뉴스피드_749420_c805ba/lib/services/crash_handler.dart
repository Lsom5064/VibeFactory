import 'dart:async';
import 'dart:convert';
import 'dart:developer';
import 'dart:io';

class CrashHandler {
  static late String _taskId;
  static late String _packageName;
  static File? _logFile;

  static Future<void> initialize(String taskId, String packageName) async {
    _taskId = taskId;
    _packageName = packageName;
    try {
      final directory = Directory.systemTemp;
      _logFile = File('${directory.path}/$_packageName-crash.log');
      if (!await _logFile!.exists()) {
        await _logFile!.create(recursive: true);
      }
    } catch (error, stack) {
      log('CrashHandler initialize failed: $error', stackTrace: stack);
    }
  }

  static Future<void> recordError(Object error, StackTrace? stack, {bool fatal = false}) async {
    final payload = <String, dynamic>{
      'taskId': _taskId,
      'packageName': _packageName,
      'fatal': fatal,
      'error': error.toString(),
      'stack': stack?.toString(),
      'timestamp': DateTime.now().toIso8601String(),
    };

    log('CrashHandler: ${jsonEncode(payload)}');

    try {
      if (_logFile != null) {
        await _logFile!.writeAsString('${jsonEncode(payload)}\n', mode: FileMode.append);
      }
    } catch (writeError, writeStack) {
      log('CrashHandler write failed: $writeError', stackTrace: writeStack);
    }
  }
}
