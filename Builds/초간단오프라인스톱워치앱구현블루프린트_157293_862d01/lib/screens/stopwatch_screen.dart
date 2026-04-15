import 'package:flutter/material.dart';

import '../controllers/stopwatch_controller.dart';

class StopwatchScreen extends StatefulWidget {
  const StopwatchScreen({super.key});

  @override
  State<StopwatchScreen> createState() => _StopwatchScreenState();
}

class _StopwatchScreenState extends State<StopwatchScreen>
    with WidgetsBindingObserver {
  late final StopwatchController _controller;

  @override
  void initState() {
    super.initState();
    _controller = StopwatchController();
    WidgetsBinding.instance.addObserver(this);
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    try {
      _controller.handleLifecycleState(state);
    } catch (error, stackTrace) {
      FlutterError.reportError(
        FlutterErrorDetails(
          exception: error,
          stack: stackTrace,
          library: 'stopwatch_screen',
          context: ErrorDescription('앱 생명주기 상태 처리 중 오류'),
        ),
      );
    }
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    try {
      _controller.dispose();
    } catch (error, stackTrace) {
      FlutterError.reportError(
        FlutterErrorDetails(
          exception: error,
          stack: stackTrace,
          library: 'stopwatch_screen',
          context: ErrorDescription('컨트롤러 해제 중 오류'),
        ),
      );
    }
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);

    return Scaffold(
      appBar: AppBar(
        title: const Text('스톱워치'),
      ),
      body: SafeArea(
        child: SingleChildScrollView(
          child: ConstrainedBox(
            constraints: BoxConstraints(
              minHeight: MediaQuery.of(context).size.height -
                  kToolbarHeight -
                  MediaQuery.of(context).padding.top,
            ),
            child: Padding(
              padding: const EdgeInsets.symmetric(horizontal: 24, vertical: 32),
              child: AnimatedBuilder(
                animation: _controller,
                builder: (context, _) {
                  return Column(
                    mainAxisAlignment: MainAxisAlignment.center,
                    crossAxisAlignment: CrossAxisAlignment.stretch,
                    children: [
                      const SizedBox(height: 48),
                      Text(
                        _controller.displayText,
                        textAlign: TextAlign.center,
                        style: theme.textTheme.displayLarge?.copyWith(
                          fontWeight: FontWeight.w800,
                          fontSize: 56,
                        ),
                      ),
                      const SizedBox(height: 48),
                      Center(
                        child: FilledButton(
                          key: UniqueKey(),
                          onPressed: () {
                            try {
                              _controller.toggle();
                            } catch (error, stackTrace) {
                              FlutterError.reportError(
                                FlutterErrorDetails(
                                  exception: error,
                                  stack: stackTrace,
                                  library: 'stopwatch_screen',
                                  context: ErrorDescription('토글 버튼 처리 중 오류'),
                                ),
                              );
                            }
                          },
                          style: FilledButton.styleFrom(
                            backgroundColor: _controller.isRunning
                                ? theme.colorScheme.error
                                : theme.colorScheme.primary,
                            padding: const EdgeInsets.symmetric(
                              horizontal: 32,
                              vertical: 18,
                            ),
                          ),
                          child: Text(
                            _controller.isRunning ? '멈춤' : '시작',
                          ),
                        ),
                      ),
                      const SizedBox(height: 48),
                    ],
                  );
                },
              ),
            ),
          ),
        ),
      ),
    );
  }
}
