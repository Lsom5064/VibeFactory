import 'package:flutter/material.dart';

import '../services/score_storage_service.dart';
import '../utils/crash_handler_bridge.dart';

class ScoreHistoryScreen extends StatefulWidget {
  const ScoreHistoryScreen({super.key});

  @override
  State<ScoreHistoryScreen> createState() => _ScoreHistoryScreenState();
}

class _ScoreHistoryScreenState extends State<ScoreHistoryScreen> {
  final ScoreStorageService _storageService = ScoreStorageService();
  List<int> _scores = <int>[];
  int _bestScore = 0;
  int _lastStage = 1;
  bool _loading = true;

  @override
  void initState() {
    super.initState();
    _loadScores();
  }

  Future<void> _loadScores() async {
    try {
      final scores = await _storageService.loadRecentScores();
      final best = await _storageService.loadBestScore();
      final lastStage = await _storageService.loadLastStage();
      if (!mounted) {
        return;
      }
      setState(() {
        _scores = scores;
        _bestScore = best;
        _lastStage = lastStage;
        _loading = false;
      });
    } catch (error, stackTrace) {
      await CrashHandlerBridge.report(
        error,
        stackTrace,
        context: '점수 기록 로드 실패',
      );
      if (!mounted) {
        return;
      }
      setState(() {
        _loading = false;
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('점수 기록')),
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
                        Text('최고 점수 $_bestScore'),
                        Text('마지막 스테이지 $_lastStage'),
                      ],
                    ),
                  ),
                ),
                const SizedBox(height: 16),
                if (_loading)
                  const Center(child: Padding(
                    padding: EdgeInsets.all(24),
                    child: CircularProgressIndicator(),
                  ))
                else if (_scores.isEmpty)
                  const Card(
                    child: Padding(
                      padding: EdgeInsets.all(20),
                      child: Text('아직 저장된 플레이 기록이 없습니다'),
                    ),
                  )
                else
                  ..._scores.asMap().entries.map(
                    (entry) => Card(
                      child: ListTile(
                        title: Text('${entry.key + 1}번째 기록'),
                        subtitle: Text('점수 ${entry.value}'),
                        leading: const Icon(Icons.stars_rounded),
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
