import 'package:flutter/material.dart';
import 'crash_handler.dart';
import 'features/stopwatch/presentation/stopwatch_page.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  CrashHandler.initialize("155414_b9e641", "kr.ac.kangwon.hai.simplestopwatch.t155414_b9e641");
  runApp(const MyApp());
}

class MyApp extends StatelessWidget {
  const MyApp({super.key});

  @override
  Widget build(BuildContext context) {
    const backgroundColor = Color(0xFFFFF8F5);
    const primaryColor = Color(0xFF6750A4);
    const dangerColor = Color(0xFFB3261E);
    const textPrimaryColor = Color(0xFF1D1B20);

    final colorScheme = ColorScheme.fromSeed(
      seedColor: primaryColor,
      brightness: Brightness.light,
      primary: primaryColor,
      error: dangerColor,
      surface: Colors.white,
    ).copyWith(
      primary: primaryColor,
      error: dangerColor,
      surface: Colors.white,
      onPrimary: Colors.white,
      onSurface: textPrimaryColor,
      onError: Colors.white,
    );

    return MaterialApp(
      debugShowCheckedModeBanner: false,
      title: '단일 버튼 스톱워치',
      theme: ThemeData(
        useMaterial3: true,
        colorScheme: colorScheme,
        scaffoldBackgroundColor: backgroundColor,
        textTheme: ThemeData.light().textTheme.apply(
              bodyColor: textPrimaryColor,
              displayColor: textPrimaryColor,
            ),
        filledButtonTheme: FilledButtonThemeData(
          style: FilledButton.styleFrom(
            elevation: 0,
            minimumSize: const Size.fromHeight(56),
            shape: const StadiumBorder(),
            textStyle: const TextStyle(
              fontSize: 18,
              fontWeight: FontWeight.w600,
            ),
            padding: const EdgeInsets.symmetric(horizontal: 32),
          ),
        ),
      ),
      home: const StopwatchPage(),
    );
  }
}
