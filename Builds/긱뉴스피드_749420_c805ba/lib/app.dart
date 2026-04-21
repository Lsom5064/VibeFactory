import 'package:flutter/material.dart';

import 'models/feed_item.dart';
import 'screens/feed_list_screen.dart';
import 'screens/in_app_webview_screen.dart';
import 'screens/post_links_screen.dart';

class MyApp extends StatelessWidget {
  const MyApp({super.key});

  @override
  Widget build(BuildContext context) {
    final colorScheme = ColorScheme.fromSeed(
      seedColor: const Color(0xFF2563EB),
      brightness: Brightness.light,
      surface: Colors.white,
    );

    return MaterialApp(
      title: '긱뉴스 피드',
      debugShowCheckedModeBanner: false,
      theme: ThemeData(
        useMaterial3: true,
        colorScheme: colorScheme,
        scaffoldBackgroundColor: const Color(0xFFF8FAFC),
        appBarTheme: const AppBarTheme(centerTitle: false),
        cardTheme: CardThemeData(
          color: Colors.white,
          elevation: 0,
          shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.circular(16),
            side: const BorderSide(color: Color(0xFFE2E8F0)),
          ),
        ),
      ),
      initialRoute: '/feed',
      onGenerateRoute: (settings) {
        switch (settings.name) {
          case '/feed':
            return MaterialPageRoute<void>(
              builder: (_) => const FeedListScreen(),
              settings: settings,
            );
          case '/post-links':
            final item = settings.arguments as FeedItem;
            return MaterialPageRoute<void>(
              builder: (_) => PostLinksScreen(item: item),
              settings: settings,
            );
          case '/webview':
            final args = settings.arguments as Map<String, dynamic>;
            return MaterialPageRoute<void>(
              builder: (_) => InAppWebViewScreen(
                title: args['title'] as String? ?? '웹 보기',
                url: args['url'] as String,
              ),
              settings: settings,
            );
          default:
            return MaterialPageRoute<void>(
              builder: (_) => const FeedListScreen(),
              settings: settings,
            );
        }
      },
    );
  }
}
