import 'package:flutter/material.dart';
import 'crash_handler.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  CrashHandler.initialize("task-unknown", "kr.ac.kangwon.hai.baseproject");
  runApp(const GeneratedAppShell());
}

class GeneratedAppShell extends StatelessWidget {
  const GeneratedAppShell({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Generated App',
      debugShowCheckedModeBanner: false,
      theme: ThemeData(useMaterial3: true),
      home: const Scaffold(
        body: SafeArea(
          child: Center(
            child: Text("Generated App"),
          ),
        ),
      ),
    );
  }
}
