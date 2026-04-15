import 'package:flutter/material.dart';

import 'app/app.dart';
import 'crash_handler.dart';

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  CrashHandler.initialize('[task_id]', 'kangwon_meal_menu');
  runApp(const MyApp());
}
