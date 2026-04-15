import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter/widgets.dart';

import '../utils/time_formatter.dart';

class StopwatchController extends ChangeNotifier {
  StopwatchController();

  bool isRunning = false;
  Duration accumulated = Duration.zero;
  DateTime? startedAt;
  String displayText = '00:00.00';

  Timer? _timer;
  bool _isDisposed = false;
  String _lastValidDisplay = '00:00.00';

  void toggle() {
    try {
      if (isRunning) {
        stop();
      } else {
        start();
      }
    } catch (error, stackTrace) {
      FlutterError.reportError(
        FlutterErrorDetails(
          exception: error,
          stack: stackTrace,
          library: 'stopwatch_controller',
          context: ErrorDescription('토글 처리 중 오류'),
        ),
      );
      _recoverToSafeState();
    }
  }

  void start() {
    try {
      if (isRunning) {
        return;
      }
      startedAt = DateTime.now();
      isRunning = true;
      _startTicker();
      _refreshDisplay();
    } catch (error, stackTrace) {
      FlutterError.reportError(
        FlutterErrorDetails(
          exception: error,
          stack: stackTrace,
          library: 'stopwatch_controller',
          context: ErrorDescription('시작 처리 중 오류'),
        ),
      );
      _recoverToSafeState();
    }
  }

  void stop() {
    try {
      if (!isRunning) {
        return;
      }
      final DateTime? localStartedAt = startedAt;
      if (localStartedAt == null) {
        isRunning = false;
        _stopTicker();
        _notifySafely();
        return;
      }

      final Duration delta = DateTime.now().difference(localStartedAt);
      accumulated += delta.isNegative ? Duration.zero : delta;
      startedAt = null;
      isRunning = false;
      _stopTicker();
      _refreshDisplay();
    } catch (error, stackTrace) {
      FlutterError.reportError(
        FlutterErrorDetails(
          exception: error,
          stack: stackTrace,
          library: 'stopwatch_controller',
          context: ErrorDescription('멈춤 처리 중 오류'),
        ),
      );
      _recoverToSafeState();
    }
  }

  void handleLifecycleState(AppLifecycleState state) {
    try {
      if (state == AppLifecycleState.resumed ||
          state == AppLifecycleState.paused ||
          state == AppLifecycleState.inactive) {
        _refreshDisplay();
      }
    } catch (error, stackTrace) {
      FlutterError.reportError(
        FlutterErrorDetails(
          exception: error,
          stack: stackTrace,
          library: 'stopwatch_controller',
          context: ErrorDescription('생명주기 처리 중 오류'),
        ),
      );
      _recoverToSafeState();
    }
  }

  Duration _currentElapsed() {
    try {
      Duration total = accumulated;
      if (isRunning && startedAt != null) {
        final Duration delta = DateTime.now().difference(startedAt!);
        total += delta.isNegative ? Duration.zero : delta;
      }
      if (total.isNegative) {
        return Duration.zero;
      }
      return total;
    } catch (_) {
      return Duration.zero;
    }
  }

  void _startTicker() {
    try {
      _stopTicker();
      _timer = Timer.periodic(const Duration(milliseconds: 16), (_) {
        try {
          _refreshDisplay();
        } catch (error, stackTrace) {
          FlutterError.reportError(
            FlutterErrorDetails(
              exception: error,
              stack: stackTrace,
              library: 'stopwatch_controller',
              context: ErrorDescription('주기 갱신 중 오류'),
            ),
          );
          _recoverToSafeState();
        }
      });
    } catch (error, stackTrace) {
      FlutterError.reportError(
        FlutterErrorDetails(
          exception: error,
          stack: stackTrace,
          library: 'stopwatch_controller',
          context: ErrorDescription('타이머 시작 중 오류'),
        ),
      );
      _recoverToSafeState();
    }
  }

  void _stopTicker() {
    try {
      _timer?.cancel();
      _timer = null;
    } catch (error, stackTrace) {
      FlutterError.reportError(
        FlutterErrorDetails(
          exception: error,
          stack: stackTrace,
          library: 'stopwatch_controller',
          context: ErrorDescription('타이머 중지 중 오류'),
        ),
      );
    }
  }

  void _refreshDisplay() {
    try {
      final Duration elapsed = _currentElapsed();
      final String formatted = formatStopwatchDuration(elapsed);
      displayText = formatted;
      _lastValidDisplay = formatted;
      _notifySafely();
    } catch (error, stackTrace) {
      FlutterError.reportError(
        FlutterErrorDetails(
          exception: error,
          stack: stackTrace,
          library: 'stopwatch_controller',
          context: ErrorDescription('표시값 계산 중 오류'),
        ),
      );
      displayText = _lastValidDisplay;
      _notifySafely();
    }
  }

  void _recoverToSafeState() {
    _stopTicker();
    isRunning = false;
    startedAt = null;
    if (accumulated.isNegative) {
      accumulated = Duration.zero;
    }
    displayText = _lastValidDisplay;
    _notifySafely();
  }

  void _notifySafely() {
    if (_isDisposed) {
      return;
    }
    notifyListeners();
  }

  @override
  void dispose() {
    _isDisposed = true;
    _stopTicker();
    super.dispose();
  }
}
