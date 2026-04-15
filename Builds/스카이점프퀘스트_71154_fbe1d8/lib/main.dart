import 'package:flutter/material.dart';
import 'crash_handler.dart';
import 'screens/title_screen.dart';
import 'screens/game_screen.dart';
import 'screens/result_screen.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  CrashHandler.initialize('71154_fbe1d8', 'kr.ac.kangwon.hai.skyjumpquest');
  runApp(const MyApp());
}

class MyApp extends StatelessWidget {
  const MyApp({super.key});

  @override
  Widget build(BuildContext context) {
    final colorScheme = ColorScheme.fromSeed(
      seedColor: const Color(0xFF4FC3F7),
      brightness: Brightness.light,
    );

    return MaterialApp(
      debugShowCheckedModeBanner: false,
      title: '스카이 점프 퀘스트',
      theme: ThemeData(
        useMaterial3: true,
        colorScheme: colorScheme,
        scaffoldBackgroundColor: const Color(0xFFEAF7FF),
      ),
      initialRoute: '/',
      routes: {
        '/': (context) => const TitleScreen(),
        '/game': (context) => const GameScreen(),
      },
      onGenerateRoute: (settings) {
        if (settings.name == '/result') {
          final args = settings.arguments;
          if (args is ResultScreenArgs) {
            return MaterialPageRoute<void>(
              builder: (_) => ResultScreen(args: args),
            );
          }
          return MaterialPageRoute<void>(
            builder: (_) => ResultScreen(
              args: const ResultScreenArgs(
                title: '결과',
                message: '결과 정보를 불러오지 못했습니다.',
                score: 0,
                cleared: false,
              ),
            ),
          );
        }
        return null;
      },
    );
  }
}
