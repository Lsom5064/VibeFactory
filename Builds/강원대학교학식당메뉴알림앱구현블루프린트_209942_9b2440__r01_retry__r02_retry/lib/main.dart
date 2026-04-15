import 'package:flutter/material.dart';
import 'crash_handler.dart';
import 'app_screen.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  CrashHandler.initialize("[task_id]", "[package_name]");
  runApp(const MyApp());
}

class MyApp extends StatelessWidget {
  const MyApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      debugShowCheckedModeBanner: false,
      title: '오늘의 메뉴',
      theme: ThemeData(
        useMaterial3: true,
        colorSchemeSeed: Colors.blue,
      ),
      home: const AppScreen(),
    );
  }
}
