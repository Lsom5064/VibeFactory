import 'package:flutter/material.dart';

class ResultScreenArgs {
  const ResultScreenArgs({
    required this.title,
    required this.message,
    required this.score,
    required this.cleared,
  });

  final String title;
  final String message;
  final int score;
  final bool cleared;
}

class ResultScreen extends StatelessWidget {
  const ResultScreen({super.key, required this.args});

  final ResultScreenArgs args;

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
                mainAxisAlignment: MainAxisAlignment.center,
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  const SizedBox(height: 48),
                  Icon(
                    args.cleared ? Icons.emoji_events_rounded : Icons.refresh_rounded,
                    size: 88,
                    color: args.cleared ? const Color(0xFFFFB300) : const Color(0xFF546E7A),
                  ),
                  const SizedBox(height: 20),
                  Text(
                    args.title,
                    textAlign: TextAlign.center,
                    style: const TextStyle(fontSize: 30, fontWeight: FontWeight.bold),
                  ),
                  const SizedBox(height: 12),
                  Text(
                    args.message,
                    textAlign: TextAlign.center,
                    style: const TextStyle(fontSize: 16),
                  ),
                  const SizedBox(height: 24),
                  Card(
                    child: Padding(
                      padding: const EdgeInsets.all(24),
                      child: Column(
                        children: [
                          const Text('최종 점수', style: TextStyle(fontSize: 18)),
                          const SizedBox(height: 8),
                          Text(
                            '${args.score}',
                            style: const TextStyle(fontSize: 40, fontWeight: FontWeight.bold),
                          ),
                        ],
                      ),
                    ),
                  ),
                  const SizedBox(height: 24),
                  ElevatedButton(
                    key: UniqueKey(),
                    onPressed: () {
                      try {
                        Navigator.of(context).pushNamedAndRemoveUntil('/game', (route) => false);
                      } catch (e, st) {
                        FlutterError.reportError(
                          FlutterErrorDetails(exception: e, stack: st),
                        );
                        rethrow;
                      }
                    },
                    child: const Padding(
                      padding: EdgeInsets.symmetric(vertical: 16),
                      child: Text('다시 도전'),
                    ),
                  ),
                  const SizedBox(height: 12),
                  OutlinedButton(
                    key: UniqueKey(),
                    onPressed: () {
                      try {
                        Navigator.of(context).pushNamedAndRemoveUntil('/', (route) => false);
                      } catch (e, st) {
                        FlutterError.reportError(
                          FlutterErrorDetails(exception: e, stack: st),
                        );
                        rethrow;
                      }
                    },
                    child: const Padding(
                      padding: EdgeInsets.symmetric(vertical: 16),
                      child: Text('타이틀로'),
                    ),
                  ),
                  const SizedBox(height: 48),
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }
}
