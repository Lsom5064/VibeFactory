import 'package:flutter/material.dart';

class GameHud extends StatelessWidget {
  const GameHud({
    super.key,
    required this.score,
    required this.coins,
    required this.lives,
    required this.stage,
  });

  final int score;
  final int coins;
  final int lives;
  final int stage;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Padding(
      padding: const EdgeInsets.all(12),
      child: Row(
        children: <Widget>[
          Expanded(
            child: Card(
              child: Padding(
                padding: const EdgeInsets.all(12),
                child: Wrap(
                  spacing: 12,
                  runSpacing: 8,
                  children: <Widget>[
                    Text('점수 $score', style: theme.textTheme.titleMedium),
                    Text('코인 $coins', style: theme.textTheme.titleMedium),
                    Text('생명 $lives', style: theme.textTheme.titleMedium),
                    Text('스테이지 $stage', style: theme.textTheme.titleMedium),
                  ],
                ),
              ),
            ),
          ),
        ],
      ),
    );
  }
}
