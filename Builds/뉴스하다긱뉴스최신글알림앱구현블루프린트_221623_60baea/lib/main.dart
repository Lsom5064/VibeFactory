import 'package:flutter/material.dart';
import 'crash_handler.dart';
import 'app.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  CrashHandler.initialize("221623_60baea", "kr.ac.kangwon.hai.geeknewsnotifier.t221623_60baea");
  runApp(const MyApp());
}
