import 'dart:async';

import 'package:flutter/material.dart';

import '../../../crash_handler.dart';
import '../domain/stopwatch_formatter.dart';

class StopwatchPage extends StatefulWidget {
  const StopwatchPage({super.key});

  @override
  State<StopwatchPage> createState() => _StopwatchPageState();
}

class _StopwatchPageState extends State<StopwatchPage>
    with WidgetsBindingObserver {
  static const Color _backgroundColor = Color(0xFFFFF8F5);
  static const Color _primaryColor = Color(0xFF6750A4);
  static const Color _dangerColor = Color(0xFFB3261E);
  static const Color _textPrimaryColor = Color(0xFF1D1B20);
  static const Color _textSecondaryColor = Color(0xFF49454F);

  bool isRunning = false;
  Duration accumulatedElapsed = Duration.zero;
  DateTime? lastStartTime;
  Duration displayElapsed = Duration.zero;
  String? runtimeError;
  Timer? ticker;
  bool isToggleLocked = false;

  String get buttonLabel => isRunning ? '정지' : '시작';
  Color get buttonBackgroundColor => isRunning ? _dangerColor : _primaryColor;
  String get formattedElapsedText => formatStopwatchElapsed(displayElapsed);

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _cancelTicker();
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    try {
      switch (state) {
        case AppLifecycleState.resumed:
          if (isRunning) {
            final recalculated = _computeCurrentElapsed(now: DateTime.now());
            if (mounted) {
              setState(() {
                displayElapsed = recalculated;
              });
            }
            if (ticker == null) {
              _startTicker();
            }
          }
          break;
        case AppLifecycleState.inactive:
        case AppLifecycleState.paused:
        case AppLifecycleState.detached:
        case AppLifecycleState.hidden:
          if (isRunning) {
            _cancelTicker();
          }
          break;
      }
    } catch (error, stackTrace) {
      runtimeError = '생명주기 처리 중 오류가 발생했습니다.';
      CrashHandler.recordError(error, stackTrace);
    }
  }

  Duration _computeCurrentElapsed({DateTime? now}) {
    try {
      if (!isRunning) {
        return accumulatedElapsed.isNegative ? Duration.zero : accumulatedElapsed;
      }

      if (lastStartTime == null) {
        runtimeError = '실행 상태에서 시작 시각이 없습니다.';
        CrashHandler.recordError(
          StateError('isRunning 이 true 인데 lastStartTime 이 null 입니다.'),
          StackTrace.current,
        );
        isRunning = false;
        _cancelTicker();
        displayElapsed = accumulatedElapsed;
        return accumulatedElapsed;
      }

      final currentTime = now ?? DateTime.now();
      final runningElapsed = accumulatedElapsed + currentTime.difference(lastStartTime!);

      if (runningElapsed.isNegative) {
        runtimeError = '음수 경과 시간이 감지되었습니다.';
        CrashHandler.recordError(
          StateError('계산된 경과 시간이 음수입니다.'),
          StackTrace.current,
        );
        return Duration.zero;
      }

      return runningElapsed;
    } catch (error, stackTrace) {
      runtimeError = '경과 시간 계산 중 오류가 발생했습니다.';
      CrashHandler.recordError(error, stackTrace);
      return displayElapsed.isNegative ? Duration.zero : displayElapsed;
    }
  }

  void _cancelTicker() {
    try {
      ticker?.cancel();
      ticker = null;
    } catch (error, stackTrace) {
      runtimeError = '타이머 정리 중 오류가 발생했습니다.';
      CrashHandler.recordError(error, stackTrace);
      ticker = null;
    }
  }

  void _startTicker() {
    try {
      _cancelTicker();
      ticker = Timer.periodic(const Duration(milliseconds: 10), (_) {
        try {
          if (!mounted || !isRunning) {
            return;
          }
          final nextElapsed = _computeCurrentElapsed(now: DateTime.now());
          if (!mounted) {
            return;
          }
          setState(() {
            displayElapsed = nextElapsed;
          });
        } catch (error, stackTrace) {
          runtimeError = '타이머 갱신 중 오류가 발생했습니다.';
          CrashHandler.recordError(error, stackTrace);
        }
      });
    } catch (error, stackTrace) {
      runtimeError = '타이머 시작 중 오류가 발생했습니다.';
      CrashHandler.recordError(error, stackTrace);
      _cancelTicker();
    }
  }

  Future<void> _handleToggle() async {
    if (isToggleLocked) {
      return;
    }

    isToggleLocked = true;
    try {
      if (isRunning) {
        _stopStopwatch();
      } else {
        _startStopwatch();
      }
    } catch (error, stackTrace) {
      runtimeError = '토글 처리 중 오류가 발생했습니다.';
      CrashHandler.recordError(error, stackTrace);
      _cancelTicker();
      if (mounted) {
        setState(() {
          isRunning = false;
          lastStartTime = null;
          displayElapsed = accumulatedElapsed.isNegative
              ? Duration.zero
              : accumulatedElapsed;
        });
      } else {
        isRunning = false;
        lastStartTime = null;
        displayElapsed = accumulatedElapsed.isNegative
            ? Duration.zero
            : accumulatedElapsed;
      }
    } finally {
      await Future<void>.delayed(Duration.zero);
      isToggleLocked = false;
    }
  }

  void _startStopwatch() {
    try {
      final now = DateTime.now();
      if (mounted) {
        setState(() {
          runtimeError = null;
          lastStartTime = now;
          isRunning = true;
        });
      } else {
        runtimeError = null;
        lastStartTime = now;
        isRunning = true;
      }
      _startTicker();
    } catch (error, stackTrace) {
      runtimeError = '시작 처리 중 오류가 발생했습니다.';
      CrashHandler.recordError(error, stackTrace);
      rethrow;
    }
  }

  void _stopStopwatch() {
    try {
      final latestElapsed = _computeCurrentElapsed(now: DateTime.now());
      _cancelTicker();
      if (mounted) {
        setState(() {
          accumulatedElapsed = latestElapsed;
          displayElapsed = latestElapsed;
          isRunning = false;
          lastStartTime = null;
        });
      } else {
        accumulatedElapsed = latestElapsed;
        displayElapsed = latestElapsed;
        isRunning = false;
        lastStartTime = null;
      }
    } catch (error, stackTrace) {
      runtimeError = '정지 처리 중 오류가 발생했습니다.';
      CrashHandler.recordError(error, stackTrace);
      rethrow;
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: _backgroundColor,
      body: SafeArea(
        child: LayoutBuilder(
          builder: (context, constraints) {
            return SingleChildScrollView(
              child: Padding(
                padding: const EdgeInsets.symmetric(horizontal: 24, vertical: 32),
                child: ConstrainedBox(
                  constraints: BoxConstraints(minHeight: constraints.maxHeight),
                  child: Column(
                    mainAxisAlignment: MainAxisAlignment.spaceBetween,
                    crossAxisAlignment: CrossAxisAlignment.center,
                    children: [
                      const SizedBox(height: 24),
                      Column(
                        children: [
                          const Text(
                            '경과 시간',
                            style: TextStyle(
                              fontSize: 14,
                              fontWeight: FontWeight.w400,
                              color: _textSecondaryColor,
                            ),
                          ),
                          const SizedBox(height: 12),
                          Semantics(
                            label: '경과 시간',
                            value: formattedElapsedText,
                            child: SizedBox(
                              width: double.infinity,
                              child: FittedBox(
                                fit: BoxFit.scaleDown,
                                child: Text(
                                  formattedElapsedText,
                                  textAlign: TextAlign.center,
                                  maxLines: 1,
                                  style: const TextStyle(
                                    fontSize: 56,
                                    fontWeight: FontWeight.w700,
                                    letterSpacing: -1.0,
                                    height: 1.1,
                                    color: _textPrimaryColor,
                                  ),
                                ),
                              ),
                            ),
                          ),
                        ],
                      ),
                      const SizedBox(height: 32),
                      SizedBox(
                        width: double.infinity,
                        child: Semantics(
                          button: true,
                          label: buttonLabel,
                          child: FilledButton(
                            key: UniqueKey(),
                            onPressed: _handleToggle,
                            style: FilledButton.styleFrom(
                              backgroundColor: buttonBackgroundColor,
                              foregroundColor: Colors.white,
                              minimumSize: const Size.fromHeight(56),
                              elevation: 0,
                              shape: const StadiumBorder(),
                              textStyle: const TextStyle(
                                fontSize: 18,
                                fontWeight: FontWeight.w600,
                              ),
                            ),
                            child: Text(buttonLabel),
                          ),
                        ),
                      ),
                    ],
                  ),
                ),
              ),
            );
          },
        ),
      ),
    );
  }
}
