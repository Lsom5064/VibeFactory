import 'package:flutter/material.dart';
import 'crash_handler.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  CrashHandler.initialize("156287_066914", "kr.ac.kangwon.hai.simplestopwatch.t156287_066914");
  runApp(const MyApp());
}

class MyApp extends StatelessWidget {
  const MyApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Generated App',
      theme: ThemeData(
        primarySwatch: Colors.blue,
      ),
      home: const Scaffold(
        body: Center(
          child: Text("Generated App Running"),
        ),
      ),
    );
  }
}
