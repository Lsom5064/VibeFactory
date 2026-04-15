import 'package:flutter/material.dart';
import 'crash_handler.dart';
import 'screens/stopwatch_screen.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  CrashHandler.initialize("157293_862d01", "kr.ac.kangwon.hai.simplestopwatch.t157293_862d01");
  runApp(const MyApp());
}

class MyApp extends StatelessWidget {
  const MyApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      debugShowCheckedModeBanner: false,
      title: '초간단 스톱워치',
      theme: ThemeData(
        useMaterial3: true,
        colorSchemeSeed: Colors.blue,
      ),
      routes: {
        '/': (context) => const StopwatchScreen(),
      },
      initialRoute: '/',
    );
  }
}
