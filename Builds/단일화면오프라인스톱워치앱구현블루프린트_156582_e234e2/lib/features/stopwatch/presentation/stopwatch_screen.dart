import 'dart:async';

import 'package:flutter/material.dart';

import '../../../crash_handler.dart';
import '../../../theme/app_theme.dart';

class StopwatchScreen extends StatefulWidget {
  const StopwatchScreen({super.key});

  @override
  State<StopwatchScreen> createState() => _StopwatchScreenState();
}

class _StopwatchScreenState extends State<StopwatchScreen> {
  final UniqueKey _toggleButtonKey = UniqueKey();

  bool isRunning = false;
  int elapsedMilliseconds = 0;
  int baseElapsedBeforeRun = 0;
  DateTime? startedAt;
  Timer? ticker;
  String displayText = '00:00.00';
  String statusText = '정지됨';
  String helperText = '버튼을 눌러 시작하거나 정지하세요';

  String get buttonLabel => isRunning ? '정지' : '시작';
  Color get buttonColor => isRunning ? AppTheme.error : AppTheme.primary;
  Color get statusColor => isRunning ? AppTheme.success : AppTheme.secondary;

  @override
  void dispose() {
    try {
      ticker?.cancel();
      ticker = null;
    } catch (error, stackTrace) {
      CrashHandler.recordError(error, stackTrace, 'dispose에서 타이머 취소 중 오류');
    }
    super.dispose();
  }

  void toggleStartStop() {
    try {
      if (isRunning) {
        _stopStopwatch();
      } else {
        _startStopwatch();
      }
    } catch (error, stackTrace) {
      CrashHandler.recordError(error, stackTrace, '토글 처리 중 오류');
      _recoverToStoppedState();
    }
  }

  void _startStopwatch() {
    try {
      ticker?.cancel();
      ticker = null;

      final DateTime now = DateTime.now();
      startedAt = now;
      baseElapsedBeforeRun = elapsedMilliseconds;

      if (!mounted) {
        return;
      }

      setState(() {
        isRunning = true;
        statusText = '실행 중';
      });

      ticker = Timer.periodic(const Duration(milliseconds: 20), (Timer timer) {
        try {
          if (!mounted) {
            return;
          }

          final DateTime? localStartedAt = startedAt;
          if (localStartedAt == null) {
            throw StateError('startedAt이 null입니다.');
          }

          final int diff = DateTime.now().difference(localStartedAt).inMilliseconds;
          final int nextElapsed = (baseElapsedBeforeRun + diff).clamp(0, 1 << 30);

          if (!mounted) {
            return;
          }

          setState(() {
            elapsedMilliseconds = nextElapsed;
            displayText = formatElapsed(elapsedMilliseconds);
          });
        } catch (error, stackTrace) {
          CrashHandler.recordError(error, stackTrace, '타이머 tick 처리 중 오류');
          _recoverToStoppedState();
        }
      });
    } catch (error, stackTrace) {
      CrashHandler.recordError(error, stackTrace, '시작 처리 중 오류');
      _recoverToStoppedState();
    }
  }

  void _stopStopwatch() {
    try {
      final DateTime now = DateTime.now();
      final DateTime? localStartedAt = startedAt;
      int finalElapsed = elapsedMilliseconds;

      if (localStartedAt != null) {
        final int diff = now.difference(localStartedAt).inMilliseconds;
        finalElapsed = baseElapsedBeforeRun + diff;
      }

      if (finalElapsed < 0) {
        finalElapsed = 0;
      }

      ticker?.cancel();
      ticker = null;

      if (!mounted) {
        startedAt = null;
        isRunning = false;
        baseElapsedBeforeRun = finalElapsed;
        elapsedMilliseconds = finalElapsed;
        statusText = '정지됨';
        displayText = formatElapsed(finalElapsed);
        return;
      }

      setState(() {
        elapsedMilliseconds = finalElapsed;
        baseElapsedBeforeRun = finalElapsed;
        isRunning = false;
        statusText = '정지됨';
        startedAt = null;
        displayText = formatElapsed(finalElapsed);
      });
    } catch (error, stackTrace) {
      CrashHandler.recordError(error, stackTrace, '정지 처리 중 오류');
      _recoverToStoppedState();
    }
  }

  void _recoverToStoppedState() {
    try {
      ticker?.cancel();
      ticker = null;
      startedAt = null;
      final int safeElapsed = elapsedMilliseconds < 0 ? 0 : elapsedMilliseconds;

      if (!mounted) {
        isRunning = false;
        elapsedMilliseconds = safeElapsed;
        baseElapsedBeforeRun = safeElapsed;
        statusText = '정지됨';
        displayText = formatElapsed(safeElapsed);
        return;
      }

      setState(() {
        isRunning = false;
        elapsedMilliseconds = safeElapsed;
        baseElapsedBeforeRun = safeElapsed;
        statusText = '정지됨';
        displayText = formatElapsed(safeElapsed);
      });
    } catch (error, stackTrace) {
      CrashHandler.recordError(error, stackTrace, '복구 처리 중 오류');
    }
  }

  String formatElapsed(int milliseconds) {
    final int safeMilliseconds = milliseconds < 0 ? 0 : milliseconds;
    final int totalSeconds = safeMilliseconds ~/ 1000;
    final int minutes = totalSeconds ~/ 60;
    final int seconds = totalSeconds % 60;
    final int hundredths = (safeMilliseconds % 1000) ~/ 10;

    final String minuteText = minutes.toString().padLeft(2, '0');
    final String secondText = seconds.toString().padLeft(2, '0');
    final String hundredthText = hundredths.toString().padLeft(2, '0');

    return '$minuteText:$secondText.$hundredthText';
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('스톱워치'),
      ),
      body: SafeArea(
        child: SingleChildScrollView(
          child: ConstrainedBox(
            constraints: BoxConstraints(
              minHeight: MediaQuery.of(context).size.height -
                  MediaQuery.of(context).padding.top -
                  kToolbarHeight,
            ),
            child: Padding(
              padding: const EdgeInsets.fromLTRB(24, 24, 24, 32),
              child: Column(
                children: <Widget>[
                  const SizedBox(height: 24),
                  Text(
                    statusText,
                    style: TextStyle(
                      fontSize: 16,
                      fontWeight: FontWeight.w500,
                      color: statusColor,
                    ),
                    textAlign: TextAlign.center,
                  ),
                  const SizedBox(height: 24),
                  Text(
                    displayText,
                    textAlign: TextAlign.center,
                    maxLines: 1,
                    overflow: TextOverflow.visible,
                    style: const TextStyle(
                      fontSize: 64,
                      fontWeight: FontWeight.w700,
                      color: AppTheme.onSurface,
                      height: 1.1,
                    ),
                  ),
                  const SizedBox(height: 12),
                  Text(
                    helperText,
                    textAlign: TextAlign.center,
                    style: const TextStyle(
                      fontSize: 14,
                      fontWeight: FontWeight.w400,
                      color: AppTheme.onSurface,
                    ),
                  ),
                  const SizedBox(height: 40),
                  FilledButton(
                    key: _toggleButtonKey,
                    onPressed: toggleStartStop,
                    style: FilledButton.styleFrom(
                      backgroundColor: buttonColor,
                      foregroundColor: Colors.white,
                      minimumSize: const Size(double.infinity, 72),
                      shape: RoundedRectangleBorder(
                        borderRadius: BorderRadius.circular(999),
                      ),
                    ),
                    child: Text(buttonLabel),
                  ),
                  const SizedBox(height: 24),
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }
}
