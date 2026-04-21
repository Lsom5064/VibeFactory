import 'package:flutter/material.dart';

import 'models/article_item.dart';
import 'screens/article_detail_screen.dart';
import 'screens/feed_home_screen.dart';
import 'screens/notification_settings_screen.dart';
import 'services/background_check_service.dart';
import 'services/feed_parser.dart';
import 'services/feed_repository.dart';
import 'services/local_store.dart';
import 'services/notification_service.dart';

class AppServices {
  static final LocalStore localStore = LocalStore();
  static final NotificationService notificationService =
      NotificationService(localStore);
  static final FeedParser parser = FeedParser();
  static final FeedRepository repository = FeedRepository(
    localStore: localStore,
    parser: parser,
    notificationService: notificationService,
  );
  static final BackgroundCheckService backgroundCheckService =
      BackgroundCheckService(repository);
}

class MyApp extends StatelessWidget {
  const MyApp({
    super.key,
    required this.initialPayload,
    required this.repository,
    required this.notificationService,
    required this.backgroundCheckService,
  });

  final String? initialPayload;
  final FeedRepository repository;
  final NotificationService notificationService;
  final BackgroundCheckService backgroundCheckService;

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: '하다뉴스 알림',
      debugShowCheckedModeBanner: false,
      theme: ThemeData(
        useMaterial3: true,
        colorSchemeSeed: const Color(0xFF2563EB),
        scaffoldBackgroundColor: const Color(0xFFF8FAFC),
      ),
      initialRoute: '/home',
      onGenerateRoute: (settings) => AppRouter.onGenerateRoute(
        settings,
        repository: repository,
        notificationService: notificationService,
        backgroundCheckService: backgroundCheckService,
        initialPayload: initialPayload,
      ),
    );
  }
}

class AppRouter {
  static Route<dynamic> onGenerateRoute(
    RouteSettings settings, {
    required FeedRepository repository,
    required NotificationService notificationService,
    required BackgroundCheckService backgroundCheckService,
    String? initialPayload,
  }) {
    switch (settings.name) {
      case '/article':
        final article = settings.arguments is ArticleItem
            ? settings.arguments as ArticleItem
            : repository.findArticleByPayload(initialPayload);
        if (article == null) {
          return MaterialPageRoute(
            builder: (_) => FeedHomeScreen(
              repository: repository,
              backgroundCheckService: backgroundCheckService,
              initialPayload: initialPayload,
            ),
            settings: const RouteSettings(name: '/home'),
          );
        }
        return MaterialPageRoute(
          builder: (_) => ArticleDetailScreen(
            article: article,
            notificationService: notificationService,
            repository: repository,
          ),
          settings: const RouteSettings(name: '/article'),
        );
      case '/notifications':
        return MaterialPageRoute(
          builder: (_) => NotificationSettingsScreen(
            repository: repository,
            notificationService: notificationService,
            backgroundCheckService: backgroundCheckService,
          ),
          settings: const RouteSettings(name: '/notifications'),
        );
      case '/home':
      default:
        return MaterialPageRoute(
          builder: (_) => FeedHomeScreen(
            repository: repository,
            backgroundCheckService: backgroundCheckService,
            initialPayload: initialPayload,
          ),
          settings: const RouteSettings(name: '/home'),
        );
    }
  }
}
