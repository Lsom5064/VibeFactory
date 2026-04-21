import 'package:flutter/material.dart';

import 'screens/home_screen.dart';
import 'screens/notification_settings_screen.dart';
import 'screens/restaurant_settings_screen.dart';

class MyApp extends StatelessWidget {
  const MyApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: '강원 학식알림',
      debugShowCheckedModeBanner: false,
      theme: ThemeData(
        useMaterial3: true,
        colorScheme: ColorScheme.fromSeed(seedColor: const Color(0xFF2E7D32)),
      ),
      initialRoute: '/home',
      routes: <String, WidgetBuilder>{
        '/home': (BuildContext context) => const HomeScreen(),
        '/restaurant-settings': (BuildContext context) =>
            const RestaurantSettingsScreen(),
        '/notification-settings': (BuildContext context) =>
            const NotificationSettingsScreen(),
      },
    );
  }
}
