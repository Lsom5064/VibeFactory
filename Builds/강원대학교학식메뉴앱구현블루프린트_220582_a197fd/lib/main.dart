import 'package:flutter/material.dart';
import 'crash_handler.dart';
import 'app/app.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  CrashHandler.initialize("220582_a197fd", "kr.ac.kangwon.hai.knumealmenu.t220582_a197fd");
  runApp(const MyApp());
}
