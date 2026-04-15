import 'package:flutter/material.dart';

import 'screens/timer_home_screen.dart';

class MyApp extends StatelessWidget {
  const MyApp({super.key});

  @override
  Widget build(BuildContext context) {
    const background = Color(0xFFFFFBFE);
    const onSurface = Color(0xFF1C1B1F);
    const onSurfaceVariant = Color(0xFF49454F);
    const primary = Color(0xFF6750A4);
    const onPrimary = Color(0xFFFFFFFF);
    const error = Color(0xFFB3261E);

    final colorScheme = ColorScheme.fromSeed(
      seedColor: primary,
      brightness: Brightness.light,
      primary: primary,
      onPrimary: onPrimary,
      error: error,
      surface: background,
      onSurface: onSurface,
    ).copyWith(
      surface: background,
      onSurface: onSurface,
      onSurfaceVariant: onSurfaceVariant,
      error: error,
    );

    return MaterialApp(
      debugShowCheckedModeBanner: false,
      title: '단순 타이머',
      theme: ThemeData(
        useMaterial3: true,
        colorScheme: colorScheme,
        scaffoldBackgroundColor: background,
        appBarTheme: const AppBarTheme(
          centerTitle: true,
          backgroundColor: background,
          foregroundColor: onSurface,
          elevation: 0,
          scrolledUnderElevation: 0,
        ),
        filledButtonTheme: FilledButtonThemeData(
          style: FilledButton.styleFrom(
            minimumSize: const Size.fromHeight(64),
            elevation: 1,
            shape: RoundedRectangleBorder(
              borderRadius: BorderRadius.circular(24),
            ),
            textStyle: const TextStyle(
              fontSize: 20,
              fontWeight: FontWeight.w600,
            ),
          ),
        ),
      ),
      initialRoute: '/',
      routes: {
        '/': (context) => const TimerHomeScreen(),
      },
    );
  }
}
