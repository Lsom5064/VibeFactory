import 'package:flutter/material.dart';

import '../state/app_state_controller.dart';
import 'app_router.dart';

class MyApp extends StatefulWidget {
  const MyApp({super.key});

  @override
  State<MyApp> createState() => _MyAppState();
}

class _MyAppState extends State<MyApp> {
  late final AppStateController controller;

  @override
  void initState() {
    super.initState();
    controller = AppStateController();
    controller.initialize();
  }

  @override
  Widget build(BuildContext context) {
    return AnimatedBuilder(
      animation: controller,
      builder: (context, _) {
        return MaterialApp(
          debugShowCheckedModeBanner: false,
          title: '강원대 학식 메뉴',
          theme: ThemeData(
            useMaterial3: true,
            colorSchemeSeed: Colors.green,
          ),
          home: AppRouter(controller: controller),
        );
      },
    );
  }
}
