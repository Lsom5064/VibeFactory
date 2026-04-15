import 'package:flutter/material.dart';
import 'package:flutter/scheduler.dart';

import '../models/game_object.dart';
import '../services/game_engine.dart';
import '../widgets/control_pad.dart';
import '../widgets/game_hud.dart';
import 'result_screen.dart';

class GameScreen extends StatefulWidget {
  const GameScreen({super.key});

  @override
  State<GameScreen> createState() => _GameScreenState();
}

class _GameScreenState extends State<GameScreen>
    with SingleTickerProviderStateMixin {
  late final Ticker _ticker;
  Duration? _lastTick;
  late GameWorld _world;
  bool _isPaused = false;
  bool _hasError = false;
  String _errorMessage = '';
  bool _navigating = false;

  @override
  void initState() {
    super.initState();
    _world = GameEngine.createInitialWorld();
    _ticker = createTicker(_onTick);
    _ticker.start();
  }

  @override
  void dispose() {
    _ticker.dispose();
    super.dispose();
  }

  void _onTick(Duration elapsed) {
    if (!mounted || _isPaused || _hasError || _navigating) {
      _lastTick = elapsed;
      return;
    }

    try {
      final previous = _lastTick;
      _lastTick = elapsed;
      final dt = previous == null
          ? 0.016
          : (elapsed - previous).inMicroseconds / 1000000.0;

      final viewportWidth = MediaQuery.sizeOf(context).width - 32;
      final result = GameEngine.update(
        world: _world,
        dt: dt,
        viewportWidth: viewportWidth.clamp(320.0, 900.0),
      );

      if (result.hitHazard || result.fellOut) {
        _world.lives -= 1;
        if (_world.lives <= 0) {
          _goToResult(
            title: '게임 오버',
            message: '모든 목숨을 잃었습니다. 다시 도전해 보세요.',
            cleared: false,
          );
          return;
        }
        GameEngine.resetPlayer(_world);
      }

      if (result.cleared) {
        _goToResult(
          title: '스테이지 클리어',
          message: '숲의 유적 끝에 도착했습니다. 멋진 점프였습니다.',
          cleared: true,
        );
        return;
      }

      if (mounted) {
        setState(() {});
      }
    } catch (e, st) {
      FlutterError.reportError(
        FlutterErrorDetails(exception: e, stack: st),
      );
      if (mounted) {
        setState(() {
          _hasError = true;
          _errorMessage = '게임 진행 중 오류가 발생했습니다.';
        });
      }
    }
  }

  void _goToResult({
    required String title,
    required String message,
    required bool cleared,
  }) {
    if (_navigating || !mounted) {
      return;
    }
    _navigating = true;
    _ticker.stop();
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted) {
        return;
      }
      try {
        Navigator.of(context).pushReplacementNamed(
          '/result',
          arguments: ResultScreenArgs(
            title: title,
            message: message,
            score: _world.score + _world.distanceScore,
            cleared: cleared,
          ),
        );
      } catch (e, st) {
        FlutterError.reportError(
          FlutterErrorDetails(exception: e, stack: st),
        );
        rethrow;
      }
    });
  }

  void _togglePause() {
    setState(() {
      _isPaused = !_isPaused;
    });
  }

  void _restartGame() {
    try {
      setState(() {
        _world = GameEngine.createInitialWorld();
        _isPaused = false;
        _hasError = false;
        _errorMessage = '';
        _navigating = false;
        _lastTick = null;
      });
      if (!_ticker.isActive) {
        _ticker.start();
      }
    } catch (e, st) {
      FlutterError.reportError(
        FlutterErrorDetails(exception: e, stack: st),
      );
      rethrow;
    }
  }

  void _moveLeftStart() {
    try {
      setState(() {
        GameEngine.setMoveDirection(_world.player, -1);
      });
    } catch (e, st) {
      FlutterError.reportError(FlutterErrorDetails(exception: e, stack: st));
      rethrow;
    }
  }

  void _moveLeftEnd() {
    try {
      setState(() {
        if (_world.player.velocityX < 0) {
          GameEngine.setMoveDirection(_world.player, 0);
        }
      });
    } catch (e, st) {
      FlutterError.reportError(FlutterErrorDetails(exception: e, stack: st));
      rethrow;
    }
  }

  void _moveRightStart() {
    try {
      setState(() {
        GameEngine.setMoveDirection(_world.player, 1);
      });
    } catch (e, st) {
      FlutterError.reportError(FlutterErrorDetails(exception: e, stack: st));
      rethrow;
    }
  }

  void _moveRightEnd() {
    try {
      setState(() {
        if (_world.player.velocityX > 0) {
          GameEngine.setMoveDirection(_world.player, 0);
        }
      });
    } catch (e, st) {
      FlutterError.reportError(FlutterErrorDetails(exception: e, stack: st));
      rethrow;
    }
  }

  void _jump() {
    try {
      setState(() {
        GameEngine.jump(_world.player);
      });
    } catch (e, st) {
      FlutterError.reportError(FlutterErrorDetails(exception: e, stack: st));
      rethrow;
    }
  }

  @override
  Widget build(BuildContext context) {
    final screenSize = MediaQuery.of(context).size;
    final gameWidth = screenSize.width - 32;
    final gameHeight = (screenSize.height * 0.48).clamp(320.0, 460.0);

    return Scaffold(
      body: SafeArea(
        child: SingleChildScrollView(
          child: Padding(
            padding: const EdgeInsets.all(16),
            child: Column(
              children: [
                GameHud(
                  score: _world.score + _world.distanceScore,
                  lives: _world.lives,
                  distance: _world.distanceScore,
                  onPause: _togglePause,
                ),
                const SizedBox(height: 16),
                SizedBox(
                  width: gameWidth,
                  height: gameHeight,
                  child: ClipRRect(
                    borderRadius: BorderRadius.circular(24),
                    child: Stack(
                      children: [
                        Container(
                          decoration: const BoxDecoration(
                            gradient: LinearGradient(
                              colors: [Color(0xFFB3E5FC), Color(0xFFE1F5FE)],
                              begin: Alignment.topCenter,
                              end: Alignment.bottomCenter,
                            ),
                          ),
                        ),
                        Positioned.fill(
                          child: CustomPaint(
                            painter: _BackgroundPainter(cameraOffset: _world.cameraOffset),
                          ),
                        ),
                        ..._buildPlatforms(),
                        ..._buildHazards(),
                        ..._buildCollectibles(),
                        _buildPlayer(),
                        if (_isPaused) _buildPauseOverlay(context),
                        if (_hasError) _buildErrorOverlay(context),
                      ],
                    ),
                  ),
                ),
                const SizedBox(height: 16),
                Container(
                  padding: const EdgeInsets.all(16),
                  decoration: BoxDecoration(
                    color: Colors.white,
                    borderRadius: BorderRadius.circular(24),
                  ),
                  child: ControlPad(
                    onMoveLeftStart: _moveLeftStart,
                    onMoveLeftEnd: _moveLeftEnd,
                    onMoveRightStart: _moveRightStart,
                    onMoveRightEnd: _moveRightEnd,
                    onJump: _jump,
                  ),
                ),
                const SizedBox(height: 16),
                Card(
                  child: Padding(
                    padding: const EdgeInsets.all(16),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: const [
                        Text(
                          '플레이 팁',
                          style: TextStyle(fontSize: 18, fontWeight: FontWeight.bold),
                        ),
                        SizedBox(height: 8),
                        Text('별빛 조각을 모으면 점수가 오르고, 끝 지점에 도달하면 보너스 점수를 얻습니다.'),
                        SizedBox(height: 6),
                        Text('가시 장애물에 닿거나 아래로 떨어지면 목숨이 줄어듭니다.'),
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

  List<Widget> _buildPlatforms() {
    return _world.platforms.map((platform) {
      return Positioned(
        left: platform.x - _world.cameraOffset,
        top: platform.y,
        child: Container(
          width: platform.width,
          height: platform.height,
          decoration: BoxDecoration(
            color: const Color(0xFF6D8F47),
            borderRadius: BorderRadius.circular(10),
            border: Border.all(color: const Color(0xFF4E6B31), width: 2),
          ),
        ),
      );
    }).toList();
  }

  List<Widget> _buildHazards() {
    return _world.hazards.map((hazard) {
      return Positioned(
        left: hazard.x - _world.cameraOffset,
        top: hazard.y,
        child: Container(
          width: hazard.width,
          height: hazard.height,
          decoration: const BoxDecoration(
            color: Color(0xFFD84315),
            borderRadius: BorderRadius.only(
              topLeft: Radius.circular(8),
              topRight: Radius.circular(8),
            ),
          ),
          child: const Icon(Icons.change_history_rounded, color: Colors.white, size: 18),
        ),
      );
    }).toList();
  }

  List<Widget> _buildCollectibles() {
    return _world.collectibles.where((item) => item.active).map((item) {
      return Positioned(
        left: item.x - _world.cameraOffset,
        top: item.y,
        child: Container(
          width: item.width,
          height: item.height,
          decoration: const BoxDecoration(
            color: Color(0xFFFFEE58),
            shape: BoxShape.circle,
          ),
          child: const Icon(Icons.auto_awesome_rounded, size: 14, color: Color(0xFF6A1B9A)),
        ),
      );
    }).toList();
  }

  Widget _buildPlayer() {
    final PlayerState player = _world.player;
    return Positioned(
      left: player.x - _world.cameraOffset,
      top: player.y,
      child: Container(
        width: player.width,
        height: player.height,
        decoration: BoxDecoration(
          color: const Color(0xFFAB47BC),
          borderRadius: BorderRadius.circular(14),
          boxShadow: const [
            BoxShadow(color: Colors.black26, blurRadius: 6, offset: Offset(0, 3)),
          ],
        ),
        child: Icon(
          player.facing >= 0 ? Icons.chevron_right_rounded : Icons.chevron_left_rounded,
          color: Colors.white,
        ),
      ),
    );
  }

  Widget _buildPauseOverlay(BuildContext context) {
    return Positioned.fill(
      child: Container(
        color: Colors.black45,
        child: Center(
          child: Container(
            margin: const EdgeInsets.all(24),
            padding: const EdgeInsets.all(24),
            decoration: BoxDecoration(
              color: Colors.white,
              borderRadius: BorderRadius.circular(24),
            ),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                const Text(
                  '일시정지',
                  style: TextStyle(fontSize: 24, fontWeight: FontWeight.bold),
                ),
                const SizedBox(height: 16),
                ElevatedButton(
                  key: UniqueKey(),
                  onPressed: _togglePause,
                  child: const Text('계속하기'),
                ),
                const SizedBox(height: 8),
                OutlinedButton(
                  key: UniqueKey(),
                  onPressed: _restartGame,
                  child: const Text('재시작'),
                ),
                const SizedBox(height: 8),
                TextButton(
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
                  child: const Text('타이틀로'),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }

  Widget _buildErrorOverlay(BuildContext context) {
    return Positioned.fill(
      child: Container(
        color: Colors.black54,
        child: Center(
          child: Container(
            margin: const EdgeInsets.all(24),
            padding: const EdgeInsets.all(24),
            decoration: BoxDecoration(
              color: Colors.white,
              borderRadius: BorderRadius.circular(24),
            ),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                const Icon(Icons.error_outline_rounded, size: 56, color: Colors.redAccent),
                const SizedBox(height: 12),
                const Text(
                  '오류 발생',
                  style: TextStyle(fontSize: 22, fontWeight: FontWeight.bold),
                ),
                const SizedBox(height: 8),
                Text(
                  _errorMessage,
                  textAlign: TextAlign.center,
                ),
                const SizedBox(height: 16),
                ElevatedButton(
                  key: UniqueKey(),
                  onPressed: _restartGame,
                  child: const Text('게임 다시 시작'),
                ),
                const SizedBox(height: 8),
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
                  child: const Text('타이틀로 이동'),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

class _BackgroundPainter extends CustomPainter {
  const _BackgroundPainter({required this.cameraOffset});

  final double cameraOffset;

  @override
  void paint(Canvas canvas, Size size) {
    final hillPaint = Paint()..color = const Color(0xFFB2DF8A);
    final hillPaint2 = Paint()..color = const Color(0xFF81C784);
    final cloudPaint = Paint()..color = Colors.white.withValues(alpha: 0.85);

    final hillPath = Path()
      ..moveTo(-cameraOffset * 0.2, size.height * 0.78)
      ..quadraticBezierTo(size.width * 0.2, size.height * 0.55, size.width * 0.45, size.height * 0.78)
      ..quadraticBezierTo(size.width * 0.7, size.height * 0.58, size.width * 1.05, size.height * 0.8)
      ..lineTo(size.width * 1.05, size.height)
      ..lineTo(-cameraOffset * 0.2, size.height)
      ..close();

    final hillPath2 = Path()
      ..moveTo(-cameraOffset * 0.1, size.height * 0.84)
      ..quadraticBezierTo(size.width * 0.25, size.height * 0.66, size.width * 0.55, size.height * 0.84)
      ..quadraticBezierTo(size.width * 0.82, size.height * 0.68, size.width * 1.1, size.height * 0.86)
      ..lineTo(size.width * 1.1, size.height)
      ..lineTo(-cameraOffset * 0.1, size.height)
      ..close();

    canvas.drawPath(hillPath, hillPaint);
    canvas.drawPath(hillPath2, hillPaint2);

    for (int i = 0; i < 4; i++) {
      final dx = (80 + i * 120) - (cameraOffset * 0.15 % 120);
      canvas.drawOval(Rect.fromLTWH(dx, 50 + (i % 2) * 18, 70, 24), cloudPaint);
      canvas.drawOval(Rect.fromLTWH(dx + 18, 38 + (i % 2) * 18, 54, 28), cloudPaint);
    }
  }

  @override
  bool shouldRepaint(covariant _BackgroundPainter oldDelegate) {
    return oldDelegate.cameraOffset != cameraOffset;
  }
}
