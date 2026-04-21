import 'package:flutter/material.dart';

import 'screens/home_screen.dart';
import 'screens/manage_screen.dart';
import 'screens/notification_settings_screen.dart';
import 'screens/selector_screen.dart';
import 'screens/settings_screen.dart';
import 'screens/weekly_menu_screen.dart';
import 'services/crash_handler.dart';
import 'services/local_storage_service.dart';
import 'services/menu_fetch_service.dart';
import 'services/notification_service.dart';
import 'services/permission_service.dart';
import 'services/recent_view_service.dart';
import 'services/validation_service.dart';
import 'utils/app_routes.dart';

class AppServices {
  AppServices._();

  static final LocalStorageService storage = LocalStorageService();
  static final ValidationService validation = ValidationService();
  static final RecentViewService recentViews = RecentViewService(storage);
  static final PermissionService permissionService = PermissionService();
  static final NotificationService notificationService = NotificationService(
    storage: storage,
    permissionService: permissionService,
  );
  static final MenuFetchService menuFetchService = MenuFetchService(
    storage: storage,
    validationService: validation,
  );

  static Future<void> initialize() async {
    await storage.initialize();
    await recentViews.initialize();
    await notificationService.initialize();
    await menuFetchService.initialize();
  }
}

class MyApp extends StatefulWidget {
  const MyApp({super.key});

  @override
  State<MyApp> createState() => _MyAppState();
}

class _MyAppState extends State<MyApp> {
  late final Future<void> _bootstrapFuture;

  @override
  void initState() {
    super.initState();
    _bootstrapFuture = AppServices.initialize();
  }

  Route<dynamic> _onGenerateRoute(RouteSettings settings) {
    final args = (settings.arguments as Map<String, dynamic>?) ??
        <String, dynamic>{};
    switch (settings.name) {
      case AppRoutes.home:
        return MaterialPageRoute<void>(builder: (_) => const _RootScaffold());
      case AppRoutes.selector:
        return MaterialPageRoute<void>(builder: (_) => const SelectorScreen());
      case AppRoutes.weeklyMenu:
        return MaterialPageRoute<void>(
          builder: (_) => WeeklyMenuScreen(
            campusName: args[AppRoutes.argCampusName] as String? ?? '',
            restaurantName: args[AppRoutes.argRestaurantName] as String? ?? '',
            targetDate: args[AppRoutes.argTargetDate] as String?,
          ),
        );
      case AppRoutes.notificationSettings:
        return MaterialPageRoute<void>(
          builder: (_) => NotificationSettingsScreen(
            campusName: args[AppRoutes.argCampusName] as String? ?? '',
            restaurantName: args[AppRoutes.argRestaurantName] as String? ?? '',
          ),
        );
      case AppRoutes.manage:
        return MaterialPageRoute<void>(builder: (_) => const _RootScaffold(initialIndex: 1));
      case AppRoutes.settings:
        return MaterialPageRoute<void>(builder: (_) => const _RootScaffold(initialIndex: 2));
      default:
        return MaterialPageRoute<void>(builder: (_) => const _RootScaffold());
    }
  }

  @override
  Widget build(BuildContext context) {
    return FutureBuilder<void>(
      future: _bootstrapFuture,
      builder: (context, snapshot) {
        return MaterialApp(
          title: '강원학식알림',
          debugShowCheckedModeBanner: false,
          theme: ThemeData(
            useMaterial3: true,
            colorSchemeSeed: const Color(0xFF0B57D0),
          ),
          initialRoute: AppRoutes.home,
          onGenerateRoute: _onGenerateRoute,
          builder: (context, child) {
            final body = snapshot.connectionState == ConnectionState.done
                ? child ?? const SizedBox.shrink()
                : const Scaffold(
                    body: Center(child: CircularProgressIndicator()),
                  );
            return CrashBannerListener(child: body);
          },
        );
      },
    );
  }
}

class _RootScaffold extends StatefulWidget {
  const _RootScaffold({this.initialIndex = 0});

  final int initialIndex;

  @override
  State<_RootScaffold> createState() => _RootScaffoldState();
}

class _RootScaffoldState extends State<_RootScaffold> {
  late int _currentIndex;

  @override
  void initState() {
    super.initState();
    _currentIndex = widget.initialIndex;
  }

  @override
  Widget build(BuildContext context) {
    final screens = <Widget>[
      const HomeScreen(),
      const ManageScreen(),
      const SettingsScreen(),
    ];
    return Scaffold(
      body: screens[_currentIndex],
      bottomNavigationBar: NavigationBar(
        selectedIndex: _currentIndex,
        onDestinationSelected: (index) {
          setState(() {
            _currentIndex = index;
          });
        },
        destinations: const [
          NavigationDestination(icon: Icon(Icons.home_outlined), label: '홈'),
          NavigationDestination(icon: Icon(Icons.list_alt_outlined), label: '관리'),
          NavigationDestination(icon: Icon(Icons.settings_outlined), label: '설정'),
        ],
      ),
    );
  }
}
