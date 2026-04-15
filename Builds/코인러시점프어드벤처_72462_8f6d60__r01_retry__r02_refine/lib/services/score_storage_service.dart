import 'dart:convert';

import 'package:shared_preferences/shared_preferences.dart';

class ScoreStorageService {
  static const String _bestScoreKey = 'best_score';
  static const String _recentScoresKey = 'recent_scores';
  static const String _lastStageKey = 'last_stage';

  Future<int> loadBestScore() async {
    final prefs = await SharedPreferences.getInstance();
    return prefs.getInt(_bestScoreKey) ?? 0;
  }

  Future<List<int>> loadRecentScores() async {
    final prefs = await SharedPreferences.getInstance();
    final raw = prefs.getString(_recentScoresKey);
    if (raw == null || raw.isEmpty) {
      return <int>[];
    }

    final decoded = jsonDecode(raw);
    if (decoded is List) {
      return decoded.whereType<num>().map((e) => e.toInt()).toList();
    }
    return <int>[];
  }

  Future<int> saveScore(int score) async {
    final prefs = await SharedPreferences.getInstance();
    final best = await loadBestScore();
    final updatedBest = score > best ? score : best;
    await prefs.setInt(_bestScoreKey, updatedBest);

    final recent = await loadRecentScores();
    final updatedRecent = <int>[score, ...recent].take(10).toList();
    await prefs.setString(_recentScoresKey, jsonEncode(updatedRecent));
    return updatedBest;
  }

  Future<void> saveLastStage(int stage) async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setInt(_lastStageKey, stage);
  }

  Future<int> loadLastStage() async {
    final prefs = await SharedPreferences.getInstance();
    return prefs.getInt(_lastStageKey) ?? 1;
  }
}
