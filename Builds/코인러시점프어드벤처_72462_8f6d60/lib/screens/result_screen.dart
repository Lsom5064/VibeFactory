import 'package:flutter/material.dart';

import '../utils/crash_handler_bridge.dart';
import 'game_screen.dart';
import 'home_screen.dart';

class ResultScreen extends StatelessWidget {
  const ResultScreen({
    super.key,
    required this.score,
    required this.coins,
    required this.bestScore,
    required this.stage,
  });

  final int score;
  final int coins;
  final int bestScore;
  final int stage;

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('게임 결과')),
      body: SafeArea(
        child: SingleChildScrollView(
          child: Padding(
            padding: const EdgeInsets.all(20),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: <Widget>[
                Card(
                  child: Padding(
                    padding: const EdgeInsets.all(20),
                    child: Column(
                      children: <Widget>[
                        const Icon(Icons.emoji_events_rounded, size: 64),
                        const SizedBox(height: 12),
                        Text('이번 점수 $score', style: Theme.of(context).textTheme.headlineSmall),
                        const SizedBox(height: 8),
                        Text('획득 코인 $coins'),
                        Text('최고 점수 $bestScore'),
                        Text('도달 스테이지 $stage'),
                        Text(score >= bestScore ? '새 기록을 달성했습니다' : '다시 도전해 더 높은 점수를 노려보세요'),
                      ],
                    ),
                  ),
                ),
                const SizedBox(height: 16),
                ElevatedButton.icon(
                  key: UniqueKey(),
                  onPressed: () async {
                    try {
                      await Navigator.of(context).pushAndRemoveUntil(
                        MaterialPageRoute<void>(builder: (_) => const GameScreen()),
                        (route) => false,
                      );
                    } catch (error, stackTrace) {
                      await CrashHandlerBridge.report(
                        error,
                        stackTrace,
                        context: '다시 시작 이동 실패',
                      );
                    }
                  },
                  icon: const Icon(Icons.replay_rounded),
                  label: const Text('다시 시작'),
                ),
                const SizedBox(height: 12),
                ElevatedButton.icon(
                  key: UniqueKey(),
                  onPressed: () async {
                    try {
                      await Navigator.of(context).pushAndRemoveUntil(
                        MaterialPageRoute<void>(builder: (_) => const HomeScreen()),
                        (route) => false,
                      );
                    } catch (error, stackTrace) {
                      await CrashHandlerBridge.report(
                        error,
                        stackTrace,
                        context: '홈 화면 이동 실패',
                      );
                    }
                  },
                  icon: const Icon(Icons.home_rounded),
                  label: const Text('홈으로'),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}
