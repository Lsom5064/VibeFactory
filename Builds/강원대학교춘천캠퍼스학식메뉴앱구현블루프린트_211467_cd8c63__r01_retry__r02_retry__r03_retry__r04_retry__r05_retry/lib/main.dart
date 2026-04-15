import 'package:flutter/material.dart';

import 'app/app.dart';
import 'crash_handler.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  CrashHandler.initialize("211467_cd8c63", "kr.ac.kangwon.hai.kangwonmealmenu.t211467_cd8c63");
  runApp(const MyApp());
}
