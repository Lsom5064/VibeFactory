import 'package:flutter/material.dart';
import 'crash_handler.dart';
import 'screens/home_screen.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  CrashHandler.initialize("186093_b3a1f3", "kr.ac.kangwon.hai.kangwonmealweekly.t186093_b3a1f3");
  runApp(const MyApp());
}

class MyApp extends StatelessWidget {
  const MyApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      debugShowCheckedModeBanner: false,
      title: '강원대학교 삼척캠퍼스 주간 학식 조회',
      theme: ThemeData(
        useMaterial3: true,
        colorScheme: ColorScheme.fromSeed(seedColor: Colors.blue),
      ),
      routes: {
        '/': (context) => const HomeScreen(),
      },
      initialRoute: '/',
    );
  }
}
