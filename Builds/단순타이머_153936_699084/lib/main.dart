import 'package:flutter/material.dart';
import 'crash_handler.dart';
import 'app.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  CrashHandler.initialize("153936_699084", "kr.ac.kangwon.hai.simpletimer.t153936_699084");
  runApp(const MyApp());
}
