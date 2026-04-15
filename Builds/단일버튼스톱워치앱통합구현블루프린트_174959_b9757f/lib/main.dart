import 'package:flutter/material.dart';

import 'crash_handler.dart';
import 'screens/stopwatch_screen.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  CrashHandler.initialize("174959_b9757f", "kr.ac.kangwon.hai.simplestopwatch.t174959_b9757f");
  runApp(const MyApp());
}

class MyApp extends StatelessWidget {
  const MyApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      debugShowCheckedModeBanner: false,
      title: '간단한 스톱워치',
      theme: ThemeData(
        useMaterial3: true,
        colorScheme: ColorScheme.fromSeed(seedColor: Colors.blue),
      ),
      initialRoute: '/',
      routes: {
        '/': (context) => const StopwatchScreen(),
      },
    );
  }
}
