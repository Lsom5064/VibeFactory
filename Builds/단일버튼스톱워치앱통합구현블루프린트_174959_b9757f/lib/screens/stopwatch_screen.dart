import 'dart:async';

import 'package:flutter/material.dart';

import '../utils/time_formatter.dart';

class StopwatchScreen extends StatefulWidget {
  const StopwatchScreen({super.key});

  @override
  State<StopwatchScreen> createState() => _StopwatchScreenState();
}

class _StopwatchScreenState extends State<StopwatchScreen>
    with WidgetsBindingObserver {
  bool isRunning = false;
  Duration accumulatedDuration = Duration.zero;
  Duration currentElapsedDuration = Duration.zero;
  DateTime? lastStartTimestamp;
  Timer? tickerTimer;

  late final UniqueKey _toggleButtonKey;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _toggleButtonKey = UniqueKey();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    try {
      if (state == AppLifecycleState.paused ||
          state == AppLifecycleState.inactive ||
          state == AppLifecycleState.detached) {
        tickerTimer?.cancel();
        tickerTimer = null;
      } else if (state == AppLifecycleState.resumed) {
        _recalculateElapsedTime();
        if (isRunning && tickerTimer == null) {
          _startTicker();
        }
      }
    } catch (error, stackTrace) {
      FlutterError.reportError(
        FlutterErrorDetails(
          exception: error,
          stack: stackTrace,
          library: 'stopwatch_screen_lifecycle',
          context: ErrorDescription('앱 생명주기 처리 중 오류가 발생했습니다.'),
        ),
      );
      rethrow;
    }
  }

  void _onTogglePressed() {
    try {
      if (isRunning) {
        _stopStopwatch();
      } else {
        _startStopwatch();
      }
    } catch (error, stackTrace) {
      FlutterError.reportError(
        FlutterErrorDetails(
          exception: error,
          stack: stackTrace,
          library: 'stopwatch_screen_action',
          context: ErrorDescription('스톱워치 토글 처리 중 오류가 발생했습니다.'),
        ),
      );
      rethrow;
    }
  }

  void _startStopwatch() {
    try {
      if (isRunning) {
        return;
      }

      tickerTimer?.cancel();
      tickerTimer = null;

      final DateTime now = DateTime.now();
      if (!mounted) {
        return;
      }

      setState(() {
        isRunning = true;
        lastStartTimestamp = now;
        currentElapsedDuration = accumulatedDuration;
      });

      _startTicker();
    } catch (error, stackTrace) {
      FlutterError.reportError(
        FlutterErrorDetails(
          exception: error,
          stack: stackTrace,
          library: 'stopwatch_screen_start',
          context: ErrorDescription('스톱워치 시작 중 오류가 발생했습니다.'),
        ),
      );
      rethrow;
    }
  }

  void _stopStopwatch() {
    try {
      if (!isRunning) {
        return;
      }

      final DateTime now = DateTime.now();
      Duration nextAccumulated = accumulatedDuration;

      if (lastStartTimestamp != null) {
        nextAccumulated += now.difference(lastStartTimestamp!);
      }

      if (!mounted) {
        tickerTimer?.cancel();
        tickerTimer = null;
        return;
      }

      setState(() {
        accumulatedDuration = nextAccumulated.isNegative
            ? Duration.zero
            : nextAccumulated;
        currentElapsedDuration = accumulatedDuration;
        isRunning = false;
        lastStartTimestamp = null;
      });

      tickerTimer?.cancel();
      tickerTimer = null;
    } catch (error, stackTrace) {
      FlutterError.reportError(
        FlutterErrorDetails(
          exception: error,
          stack: stackTrace,
          library: 'stopwatch_screen_stop',
          context: ErrorDescription('스톱워치 정지 중 오류가 발생했습니다.'),
        ),
      );
      rethrow;
    }
  }

  void _startTicker() {
    try {
      tickerTimer?.cancel();
      tickerTimer = Timer.periodic(const Duration(milliseconds: 200), (_) {
        try {
          _recalculateElapsedTime();
        } catch (error, stackTrace) {
          FlutterError.reportError(
            FlutterErrorDetails(
              exception: error,
              stack: stackTrace,
              library: 'stopwatch_screen_ticker',
              context: ErrorDescription('타이머 틱 처리 중 오류가 발생했습니다.'),
            ),
          );
          rethrow;
        }
      });
    } catch (error, stackTrace) {
      FlutterError.reportError(
        FlutterErrorDetails(
          exception: error,
          stack: stackTrace,
          library: 'stopwatch_screen_ticker_start',
          context: ErrorDescription('타이머 시작 중 오류가 발생했습니다.'),
        ),
      );
      rethrow;
    }
  }

  void _recalculateElapsedTime() {
    try {
      if (!mounted) {
        return;
      }

      final DateTime? startTimestamp = lastStartTimestamp;
      final Duration nextDuration;

      if (startTimestamp == null) {
        nextDuration = accumulatedDuration.isNegative
            ? Duration.zero
            : accumulatedDuration;
      } else {
        final Duration runningDuration = DateTime.now().difference(startTimestamp);
        final Duration combined = accumulatedDuration + runningDuration;
        nextDuration = combined.isNegative ? Duration.zero : combined;
      }

      if (!mounted) {
        return;
      }

      setState(() {
        currentElapsedDuration = nextDuration;
      });
    } catch (error, stackTrace) {
      FlutterError.reportError(
        FlutterErrorDetails(
          exception: error,
          stack: stackTrace,
          library: 'stopwatch_screen_recalculate',
          context: ErrorDescription('경과 시간 계산 중 오류가 발생했습니다.'),
        ),
      );
      rethrow;
    }
  }

  String _formattedElapsedTime() {
    try {
      return TimeFormatter.formatHms(currentElapsedDuration);
    } catch (error, stackTrace) {
      FlutterError.reportError(
        FlutterErrorDetails(
          exception: error,
          stack: stackTrace,
          library: 'stopwatch_screen_format',
          context: ErrorDescription('시간 포맷팅 중 오류가 발생했습니다.'),
        ),
      );
      return TimeFormatter.formatHms(Duration.zero);
    }
  }

  @override
  void dispose() {
    tickerTimer?.cancel();
    tickerTimer = null;
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final ThemeData theme = Theme.of(context);

    return Scaffold(
      body: SafeArea(
        child: SingleChildScrollView(
          child: ConstrainedBox(
            constraints: BoxConstraints(
              minHeight: MediaQuery.of(context).size.height -
                  MediaQuery.of(context).padding.top -
                  MediaQuery.of(context).padding.bottom,
            ),
            child: Padding(
              padding: const EdgeInsets.symmetric(horizontal: 24, vertical: 32),
              child: Column(
                children: [
                  const SizedBox(height: 48),
                  Text(
                    '스톱워치',
                    style: theme.textTheme.headlineSmall,
                  ),
                  const SizedBox(height: 96),
                  Center(
                    child: FittedBox(
                      fit: BoxFit.scaleDown,
                      child: Text(
                        _formattedElapsedTime(),
                        style: theme.textTheme.displayLarge?.copyWith(
                          fontWeight: FontWeight.w700,
                        ),
                        textAlign: TextAlign.center,
                      ),
                    ),
                  ),
                  const SizedBox(height: 160),
                  FilledButton(
                    key: _toggleButtonKey,
                    onPressed: _onTogglePressed,
                    style: FilledButton.styleFrom(
                      minimumSize: const Size(double.infinity, 56),
                    ),
                    child: Text(isRunning ? '멈춤' : '시작'),
                  ),
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }
}
