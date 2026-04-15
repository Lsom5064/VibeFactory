import 'package:flutter/material.dart';
import 'crash_handler.dart';
import 'app_screen.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  CrashHandler.initialize("t209942_9b2440", "kr.ac.kangwon.hai.kangwonmealalert.t209942_9b2440");
  runApp(const MyApp());
}

class MyApp extends StatelessWidget {
  const MyApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      debugShowCheckedModeBanner: false,
      title: '강원대 학식 알리미',
      theme: ThemeData(
        useMaterial3: true,
        colorSchemeSeed: Colors.green,
      ),
      home: const MealAppScreen(),
    );
  }
}
