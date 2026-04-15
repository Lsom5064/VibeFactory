import 'package:flutter/material.dart';
import 'crash_handler.dart';
import 'app.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  CrashHandler.initialize("217783_9bb628", "kr.ac.kangwon.hai.geeknewsreader.t217783_9bb628");
  runApp(const MyApp());
}
