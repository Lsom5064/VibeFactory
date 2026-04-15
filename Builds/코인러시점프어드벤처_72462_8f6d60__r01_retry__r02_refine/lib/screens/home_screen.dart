import 'package:flutter/material.dart';

import '../services/score_storage_service.dart';
import '../utils/crash_handler_bridge.dart';
import 'game_screen.dart';
import 'score_history_screen.dart';

class HomeScreen extends StatefulWidget {
  const HomeScreen({super.key});

  @override
  State<HomeScreen> createState() => _HomeScreenState();
}

class _HomeScreenState extends State<HomeScreen> {
  final ScoreStorageService _storageService = ScoreStorageService();
  int _bestScore = 0;
  bool _isLoading = true;

  @override
  void initState() {
    super.initState();
    _loadData();
  }

  Future<void> _loadData() async {
    try {
      final best = await _storageService.loadBestScore();
      if (!mounted) {
        return;
      }
      setState(() {
        _bestScore = best;
        _isLoading = false;
      });
    } catch (error, stackTrace) {
      await CrashHandlerBridge.report(
        error,
        stackTrace,
        context: '홈 화면 점수 로드 실패',
      );
      if (!mounted) {
        return;
      }
      setState(() {
        _isLoading = false;
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Scaffold(
      appBar: AppBar(title: const Text('코인러시 점프 어드벤처')),
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
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: <Widget>[
                        Text('즉시 달리고 점프하며 코인을 모아보세요', style: theme.textTheme.headlineSmall),
                        const SizedBox(height: 12),
                        Text(
                          _isLoading ? '최고 점수를 불러오는 중입니다' : '최고 점수 $_bestScore',
                          style: theme.textTheme.titleLarge,
                        ),
                      ],
                    ),
                  ),
                ),
                const SizedBox(height: 16),
                ElevatedButton.icon(
                  key: UniqueKey(),
                  onPressed: () async {
                    try {
                      await Navigator.of(context).push(
                        MaterialPageRoute<void>(builder: (_) => const GameScreen()),
                      );
                      await _loadData();
                    } catch (error, stackTrace) {
                      await CrashHandlerBridge.report(
                        error,
                        stackTrace,
                        context: '게임 화면 이동 실패',
                      );
                    }
                  },
                  icon: const Icon(Icons.play_arrow_rounded),
                  label: const Text('게임 시작'),
                ),
                const SizedBox(height: 12),
                ElevatedButton.icon(
                  key: UniqueKey(),
                  onPressed: () async {
                    try {
                      await Navigator.of(context).push(
                        MaterialPageRoute<void>(builder: (_) => const ScoreHistoryScreen()),
                      );
                    } catch (error, stackTrace) {
                      await CrashHandlerBridge.report(
                        error,
                        stackTrace,
                        context: '점수 기록 화면 이동 실패',
                      );
                    }
                  },
                  icon: const Icon(Icons.leaderboard_rounded),
                  label: const Text('점수 기록'),
                ),
                const SizedBox(height: 16),
                Card(
                  child: Padding(
                    padding: const EdgeInsets.all(20),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: const <Widget>[
                        Text('조작 안내'),
                        SizedBox(height: 8),
                        Text('왼쪽과 오른쪽 버튼으로 이동합니다'),
                        Text('점프 버튼으로 장애물을 피하고 코인을 획득합니다'),
                        Text('적과 충돌하면 생명이 줄어들고 모두 소진되면 게임이 종료됩니다'),
                      ],
                    ),
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}
