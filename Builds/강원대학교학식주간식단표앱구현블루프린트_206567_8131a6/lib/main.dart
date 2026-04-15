import 'package:flutter/material.dart';
import 'crash_handler.dart';
import 'app/app.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  CrashHandler.initialize("206567_8131a6", "kr.ac.kangwon.hai.kangwonmealweekly.t206567_8131a6");
  runApp(const MyApp());
}
