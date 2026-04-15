import 'package:flutter/material.dart';
import 'crash_handler.dart';
import 'screens/home_screen.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  CrashHandler.initialize('72462_8f6d60', 'kr.ac.kangwon.hai.coinrushjump');
  runApp(const MyApp());
}

class MyApp extends StatelessWidget {
  const MyApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      debugShowCheckedModeBanner: false,
      title: '코인러시 점프 어드벤처',
      theme: ThemeData(
        useMaterial3: true,
        colorScheme: ColorScheme.fromSeed(
          seedColor: const Color(0xFFFFB300),
          brightness: Brightness.light,
        ),
      ),
      home: const HomeScreen(),
    );
  }
}
