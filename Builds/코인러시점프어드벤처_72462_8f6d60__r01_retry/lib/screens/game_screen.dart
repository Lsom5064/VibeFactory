import 'package:flutter/material.dart';

import '../controllers/game_controller.dart';
import '../services/score_storage_service.dart';
import '../utils/crash_handler_bridge.dart';
import '../widgets/game_canvas.dart';
import '../widgets/game_hud.dart';
import '../widgets/touch_controls.dart';
import 'result_screen.dart';

class GameScreen extends StatefulWidget {
  const GameScreen({super.key});

  @override
  State<GameScreen> createState() => _GameScreenState();
}

class _GameScreenState extends State<GameScreen> {
  final GameController _controller = GameController();
  final ScoreStorageService _storageService = ScoreStorageService();
  bool _initialized = false;
  bool _navigatedToResult = false;

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  Future<void> _handleGameOver() async {
    if (_navigatedToResult) {
      return;
    }
    _navigatedToResult = true;

    try {
      final bestScore = await _storageService.saveScore(_controller.score);
      await _storageService.saveLastStage(_controller.stage);
      if (!mounted) {
        return;
      }
      await Navigator.of(context).pushReplacement(
        MaterialPageRoute<void>(
          builder: (_) => ResultScreen(
            score: _controller.score,
            coins: _controller.coinCount,
            bestScore: bestScore,
            stage: _controller.stage,
          ),
        ),
      );
    } catch (error, stackTrace) {
      await CrashHandlerBridge.report(
        error,
        stackTrace,
        context: '게임 결과 저장 또는 결과 화면 이동 실패',
      );
      rethrow;
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('플레이 중')),
      body: SafeArea(
        child: SingleChildScrollView(
          physics: const NeverScrollableScrollPhysics(),
          child: Padding(
            padding: const EdgeInsets.all(12),
            child: LayoutBuilder(
              builder: (BuildContext context, BoxConstraints _) {
                final size = MediaQuery.of(context).size;
                if (!_initialized) {
                  _controller.initialize(
                    width: size.width - 24,
                    height: size.height * 0.62,
                  );
                  _controller.start();
                  _initialized = true;
                }

                return AnimatedBuilder(
                  animation: _controller,
                  builder: (BuildContext context, Widget? child) {
                    if (_controller.isGameOver) {
                      WidgetsBinding.instance.addPostFrameCallback((_) {
                        _handleGameOver();
                      });
                    }

                    return Column(
                      children: <Widget>[
                        GameHud(
                          score: _controller.score,
                          coins: _controller.coinCount,
                          lives: _controller.player.lives,
                          stage: _controller.stage,
                        ),
                        const SizedBox(height: 8),
                        GameCanvas(controller: _controller),
                        const SizedBox(height: 12),
                        TouchControls(
                          onLeftDown: () => _controller.moveLeft(true),
                          onLeftUp: () => _controller.moveLeft(false),
                          onRightDown: () => _controller.moveRight(true),
                          onRightUp: () => _controller.moveRight(false),
                          onJump: _controller.jump,
                        ),
                      ],
                    );
                  },
                );
              },
            ),
          ),
        ),
      ),
    );
  }
}
