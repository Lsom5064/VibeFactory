import 'package:flutter/material.dart';

import 'crash_handler.dart';

class MyApp extends StatelessWidget {
  const MyApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: '뉴스하다 긱뉴스 알림',
      theme: ThemeData(
        useMaterial3: true,
        colorSchemeSeed: Colors.indigo,
      ),
      home: const _HomeScreen(),
      builder: (context, child) {
        ErrorWidget.builder = (details) {
          CrashHandler.report(
            details.exception.toString(),
            details.stack?.toString() ?? StackTrace.current.toString(),
          );
          return Scaffold(
            body: SingleChildScrollView(
              child: Padding(
                padding: const EdgeInsets.all(24),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: const [
                    SizedBox(height: 48),
                    Text('화면을 표시하는 중 문제가 발생했습니다.'),
                    SizedBox(height: 8),
                    Text('앱을 다시 열거나 잠시 후 다시 시도해 주세요.'),
                  ],
                ),
              ),
            ),
          );
        };
        return child ?? const SizedBox.shrink();
      },
    );
  }
}

class _HomeScreen extends StatelessWidget {
  const _HomeScreen();

  @override
  Widget build(BuildContext context) {
    return const Scaffold(
      body: Center(
        child: Padding(
          padding: EdgeInsets.all(24),
          child: Text(
            '긱뉴스 알림 앱을 준비 중입니다.',
            textAlign: TextAlign.center,
          ),
        ),
      ),
    );
  }
}
