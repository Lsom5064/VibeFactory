import 'package:flutter/material.dart';
import 'crash_handler.dart';
import 'app.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  CrashHandler.initialize("204452_1ef14e", "kr.ac.kangwon.hai.knumeal.t204452_1ef14e");
  runApp(const MyApp());
}
