import 'package:flutter/material.dart';
import 'crash_handler.dart';
import 'theme/app_theme.dart';
import 'features/stopwatch/presentation/stopwatch_screen.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  CrashHandler.initialize("156582_e234e2", "kr.ac.kangwon.hai.simplestopwatch.t156582_e234e2");
  runApp(const MyApp());
}

class MyApp extends StatelessWidget {
  const MyApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      debugShowCheckedModeBanner: false,
      title: '스톱워치',
      theme: AppTheme.lightTheme,
      home: const StopwatchScreen(),
    );
  }
}
