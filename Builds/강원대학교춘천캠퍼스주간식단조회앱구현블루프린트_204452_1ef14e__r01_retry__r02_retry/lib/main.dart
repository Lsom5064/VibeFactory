import 'package:flutter/material.dart';
import 'crash_handler.dart';
import 'app.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  CrashHandler.initialize("[task_id]", "[package_name]");
  runApp(const MyApp());
}
