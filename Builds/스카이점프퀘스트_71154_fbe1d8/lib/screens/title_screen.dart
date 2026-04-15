import 'package:flutter/material.dart';

class TitleScreen extends StatelessWidget {
  const TitleScreen({super.key});

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: SafeArea(
        child: SingleChildScrollView(
          child: ConstrainedBox(
            constraints: BoxConstraints(
              minHeight: MediaQuery.of(context).size.height -
                  MediaQuery.of(context).padding.vertical,
            ),
            child: Padding(
              padding: const EdgeInsets.all(24),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  const SizedBox(height: 32),
                  Container(
                    padding: const EdgeInsets.all(24),
                    decoration: BoxDecoration(
                      gradient: const LinearGradient(
                        colors: [Color(0xFF81D4FA), Color(0xFFB3E5FC)],
                        begin: Alignment.topLeft,
                        end: Alignment.bottomRight,
                      ),
                      borderRadius: BorderRadius.circular(28),
                    ),
                    child: Column(
                      children: const [
                        Icon(Icons.landscape_rounded, size: 72, color: Color(0xFF1B5E20)),
                        SizedBox(height: 16),
                        Text(
                          '스카이 점프 퀘스트',
                          textAlign: TextAlign.center,
                          style: TextStyle(
                            fontSize: 30,
                            fontWeight: FontWeight.bold,
                            color: Color(0xFF0D47A1),
                          ),
                        ),
                        SizedBox(height: 12),
                        Text(
                          '젤리 탐험가와 함께 숲의 유적을 건너며 별빛 조각을 모아 보세요.',
                          textAlign: TextAlign.center,
                          style: TextStyle(fontSize: 16),
                        ),
                      ],
                    ),
                  ),
                  const SizedBox(height: 24),
                  Card(
                    child: Padding(
                      padding: const EdgeInsets.all(20),
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: const [
                          Text(
                            '조작 안내',
                            style: TextStyle(fontSize: 20, fontWeight: FontWeight.bold),
                          ),
                          SizedBox(height: 12),
                          Text('• 왼쪽 아래 버튼으로 좌우 이동'),
                          SizedBox(height: 8),
                          Text('• 오른쪽 아래 버튼으로 점프'),
                          SizedBox(height: 8),
                          Text('• 장애물을 피하고 별빛 조각을 모아 점수를 올리세요'),
                          SizedBox(height: 8),
                          Text('• 일시정지 버튼으로 빠르게 멈추고 다시 시작할 수 있습니다'),
                        ],
                      ),
                    ),
                  ),
                  const SizedBox(height: 24),
                  ElevatedButton.icon(
                    key: UniqueKey(),
                    onPressed: () {
                      try {
                        Navigator.of(context).pushReplacementNamed('/game');
                      } catch (e, st) {
                        FlutterError.reportError(
                          FlutterErrorDetails(exception: e, stack: st),
                        );
                        rethrow;
                      }
                    },
                    icon: const Icon(Icons.play_arrow_rounded),
                    label: const Padding(
                      padding: EdgeInsets.symmetric(vertical: 16),
                      child: Text('게임 시작'),
                    ),
                  ),
                  const SizedBox(height: 12),
                  OutlinedButton.icon(
                    key: UniqueKey(),
                    onPressed: () {
                      showAboutDialog(
                        context: context,
                        applicationName: '스카이 점프 퀘스트',
                        applicationVersion: '1.0.0',
                        children: const [
                          Text('짧고 경쾌한 터치 기반 점프 액션 게임입니다.'),
                        ],
                      );
                    },
                    icon: const Icon(Icons.info_outline_rounded),
                    label: const Padding(
                      padding: EdgeInsets.symmetric(vertical: 14),
                      child: Text('게임 정보'),
                    ),
                  ),
                  const SizedBox(height: 32),
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }
}
