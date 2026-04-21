import 'package:flutter/material.dart';

import 'screens/feed_screen.dart';
import 'screens/settings_screen.dart';
import 'services/background_sync_service.dart';
import 'services/crash_handler.dart';
import 'services/feed_service.dart';
import 'services/local_store.dart';
import 'services/notification_service.dart';
import 'services/permission_service.dart';

class MyApp extends StatefulWidget {
  const MyApp({super.key});

  @override
  State<MyApp> createState() => _MyAppState();

}

class _MyAppState extends State<MyApp> {
  late final LocalStore _localStore;
  late final FeedService _feedService;
  late final PermissionService _permissionService;
  late final NotificationService _notificationService;
  BackgroundSyncService? _backgroundSyncService;

  @override
  void initState() {
    super.initState();
    _localStore = LocalStore();
    _feedService = FeedService();
    _permissionService = PermissionService();
    _notificationService = NotificationService();
    _initializeServices();
  }

  Future<void> _initializeServices() async {
    try {
      await _notificationService.initialize();
      _backgroundSyncService = BackgroundSyncService(
        localStore: _localStore,
        feedService: _feedService,
        notificationService: _notificationService,
      );
      await _backgroundSyncService?.initialize();
      await _backgroundSyncService?.registerPeriodicSync();
    } catch (error, stackTrace) {
      CrashHandler.recordError(
        error,
        stackTrace,
        context: 'MyApp._initializeServices',
      );
    }
  }

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: '긱뉴스 알리미',
      debugShowCheckedModeBanner: false,
      theme: ThemeData(
        useMaterial3: true,
        colorScheme: ColorScheme.fromSeed(
          seedColor: const Color(0xFF0B57D0),
          brightness: Brightness.light,
        ),
        scaffoldBackgroundColor: const Color(0xFFF8FAFD),
      ),
      initialRoute: '/feed',
      routes: {
        '/feed': (context) => FeedScreen(
              localStore: _localStore,
              feedService: _feedService,
              permissionService: _permissionService,
              notificationService: _notificationService,
            ),
        '/settings': (context) => SettingsScreen(
              localStore: _localStore,
              permissionService: _permissionService,
            ),
      },
    );
  }
}
