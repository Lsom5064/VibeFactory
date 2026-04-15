import 'dart:async';

import 'package:flutter/material.dart';

import '../crash_handler.dart';

class TimerHomeScreen extends StatefulWidget {
  const TimerHomeScreen({super.key});

  @override
  State<TimerHomeScreen> createState() => _TimerHomeScreenState();
}

class _TimerHomeScreenState extends State<TimerHomeScreen>
    with WidgetsBindingObserver {
  Duration elapsedDuration = Duration.zero;
  bool isRunning = false;
  DateTime? lastStartTime;
  Timer? ticker;
  String displayText = '00:00:00';
  String statusText = '버튼을 눌러 시작하거나 멈추세요';

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _syncDisplay();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    try {
      if (state == AppLifecycleState.resumed && isRunning) {
        _syncDisplay();
      }
    } catch (error, stackTrace) {
      CrashHandler.recordError(error, stackTrace, '생명주기 처리');
      _safeStopRecovery();
    }
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    ticker?.cancel();
    ticker = null;
    super.dispose();
  }

  void _toggleTimer() {
    try {
      if (isRunning) {
        _stopTimer();
      } else {
        _startTimer();
      }
    } catch (error, stackTrace) {
      CrashHandler.recordError(error, stackTrace, '토글 처리');
      _safeStopRecovery();
    }
  }

  void _startTimer() {
    try {
      ticker?.cancel();
      ticker = null;

      lastStartTime = DateTime.now();
      isRunning = true;
      statusText = '타이머가 실행 중입니다';
      displayText = _formatDuration(_calculateCurrentDuration());

      if (mounted) {
        setState(() {});
      }

      ticker = Timer.periodic(const Duration(seconds: 1), (_) {
        _handleTick();
      });
    } catch (error, stackTrace) {
      CrashHandler.recordError(error, stackTrace, '시작 로직');
      _safeStopRecovery();
      rethrow;
    }
  }

  void _stopTimer() {
    try {
      final now = DateTime.now();
      if (lastStartTime != null) {
        final delta = now.difference(lastStartTime!);
        elapsedDuration += delta.isNegative ? Duration.zero : delta;
      }

      if (elapsedDuration.isNegative) {
        elapsedDuration = Duration.zero;
      }

      isRunning = false;
      lastStartTime = null;
      ticker?.cancel();
      ticker = null;
      displayText = _formatDuration(elapsedDuration);
      statusText = elapsedDuration > Duration.zero
          ? '다시 눌러 이어서 사용할 수 있습니다'
          : '버튼을 눌러 시작하거나 멈추세요';

      if (mounted) {
        setState(() {});
      }
    } catch (error, stackTrace) {
      CrashHandler.recordError(error, stackTrace, '멈춤 로직');
      _safeStopRecovery();
      rethrow;
    }
  }

  void _handleTick() {
    try {
      if (!isRunning) {
        return;
      }

      if (lastStartTime == null) {
        _safeStopRecovery();
        return;
      }

      if (!mounted) {
        ticker?.cancel();
        ticker = null;
        return;
      }

      setState(() {
        displayText = _formatDuration(_calculateCurrentDuration());
      });
    } catch (error, stackTrace) {
      CrashHandler.recordError(error, stackTrace, '틱 로직');
      _safeStopRecovery();
    }
  }

  void _syncDisplay() {
    displayText = _formatDuration(_calculateCurrentDuration());
    statusText = isRunning
        ? '타이머가 실행 중입니다'
        : (elapsedDuration > Duration.zero
            ? '다시 눌러 이어서 사용할 수 있습니다'
            : '버튼을 눌러 시작하거나 멈추세요');
    if (mounted) {
      setState(() {});
    }
  }

  void _safeStopRecovery() {
    ticker?.cancel();
    ticker = null;
    isRunning = false;
    lastStartTime = null;
    if (elapsedDuration.isNegative) {
      elapsedDuration = Duration.zero;
    }
    displayText = _formatDuration(elapsedDuration);
    statusText = elapsedDuration > Duration.zero
        ? '다시 눌러 이어서 사용할 수 있습니다'
        : '버튼을 눌러 시작하거나 멈추세요';
    if (mounted) {
      setState(() {});
    }
  }

  Duration _calculateCurrentDuration() {
    final base = elapsedDuration;
    if (isRunning && lastStartTime != null) {
      final delta = DateTime.now().difference(lastStartTime!);
      return base + (delta.isNegative ? Duration.zero : delta);
    }
    return base;
  }

  String _formatDuration(Duration duration) {
    final totalSeconds = duration.inSeconds;
    final hours = totalSeconds ~/ 3600;
    final minutes = (totalSeconds % 3600) ~/ 60;
    final seconds = totalSeconds % 60;
    return '${hours.toString().padLeft(2, '0')}:${minutes.toString().padLeft(2, '0')}:${seconds.toString().padLeft(2, '0')}';
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    final isActive = isRunning;

    return Scaffold(
      appBar: AppBar(
        title: const Text('타이머'),
        automaticallyImplyLeading: false,
      ),
      body: SafeArea(
        child: SingleChildScrollView(
          padding: const EdgeInsets.fromLTRB(24, 24, 24, 32),
          child: LayoutBuilder(
            builder: (context, constraints) {
              final minHeight = MediaQuery.of(context).size.height -
                  kToolbarHeight -
                  MediaQuery.of(context).padding.top -
                  MediaQuery.of(context).padding.bottom -
                  56;
              return ConstrainedBox(
                constraints: BoxConstraints(minHeight: minHeight),
                child: IntrinsicHeight(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.stretch,
                    children: [
                      Expanded(
                        child: Column(
                          mainAxisAlignment: MainAxisAlignment.center,
                          children: [
                            Text(
                              '경과 시간',
                              textAlign: TextAlign.center,
                              style: theme.textTheme.bodyMedium?.copyWith(
                                fontSize: 14,
                                fontWeight: FontWeight.w400,
                                color: const Color(0xFF49454F),
                              ),
                            ),
                            const SizedBox(height: 12),
                            Semantics(
                              label: '경과 시간 표시',
                              child: LayoutBuilder(
                                builder: (context, timeConstraints) {
                                  final fontSize = timeConstraints.maxWidth < 360 ? 56.0 : 64.0;
                                  return FittedBox(
                                    fit: BoxFit.scaleDown,
                                    child: Text(
                                      displayText,
                                      textAlign: TextAlign.center,
                                      style: theme.textTheme.displayMedium?.copyWith(
                                        fontSize: fontSize,
                                        fontWeight: FontWeight.w700,
                                        letterSpacing: -1.0,
                                        height: 1.1,
                                        color: const Color(0xFF1C1B1F),
                                      ),
                                    ),
                                  );
                                },
                              ),
                            ),
                            const SizedBox(height: 16),
                            AnimatedSwitcher(
                              duration: const Duration(milliseconds: 200),
                              child: Text(
                                statusText,
                                key: ValueKey(statusText),
                                textAlign: TextAlign.center,
                                style: theme.textTheme.bodyMedium?.copyWith(
                                  fontSize: 14,
                                  fontWeight: FontWeight.w400,
                                  color: const Color(0xFF49454F),
                                ),
                              ),
                            ),
                          ],
                        ),
                      ),
                      const SizedBox(height: 32),
                      Semantics(
                        label: isActive ? '타이머 멈춤' : '타이머 시작',
                        button: true,
                        child: AnimatedSwitcher(
                          duration: const Duration(milliseconds: 200),
                          child: FilledButton.icon(
                            key: UniqueKey(),
                            onPressed: _toggleTimer,
                            icon: Icon(isActive ? Icons.stop : Icons.play_arrow),
                            label: Text(isActive ? '멈춤' : '시작'),
                            style: FilledButton.styleFrom(
                              backgroundColor: isActive
                                  ? const Color(0xFFB3261E)
                                  : const Color(0xFF6750A4),
                              foregroundColor: const Color(0xFFFFFFFF),
                              minimumSize: const Size.fromHeight(64),
                              elevation: 1,
                              shape: RoundedRectangleBorder(
                                borderRadius: BorderRadius.circular(24),
                              ),
                            ),
                          ),
                        ),
                      ),
                    ],
                  ),
                ),
              );
            },
          ),
        ),
      ),
    );
  }
}
