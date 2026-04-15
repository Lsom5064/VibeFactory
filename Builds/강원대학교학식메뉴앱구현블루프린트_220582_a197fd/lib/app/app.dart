import 'package:flutter/material.dart';

import '../data/local/local_cache_data_source.dart';
import '../data/parser/menu_parser.dart';
import '../data/remote/remote_menu_data_source.dart';
import '../data/repository/menu_repository.dart';
import '../screens/home_screen.dart';
import '../state/app_controller.dart';

class MyApp extends StatefulWidget {
  const MyApp({super.key});

  @override
  State<MyApp> createState() => _MyAppState();
}

class _MyAppState extends State<MyApp> {
  late final AppController _controller;

  @override
  void initState() {
    super.initState();
    final repository = MenuRepository(
      localDataSource: LocalCacheDataSource(),
      remoteDataSource: RemoteMenuDataSource(),
      parser: MenuParser(),
    );
    _controller = AppController(repository: repository);
    _controller.initialize();
  }

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      debugShowCheckedModeBanner: false,
      title: '강원대학교 학식 메뉴',
      theme: ThemeData(
        useMaterial3: true,
        colorSchemeSeed: Colors.green,
      ),
      home: HomeScreen(controller: _controller),
    );
  }
}
