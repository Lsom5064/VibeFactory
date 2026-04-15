import 'dart:async';
import 'dart:math';

import 'package:flutter/foundation.dart';

import '../models/coin_model.dart';
import '../models/enemy_model.dart';
import '../models/player_state.dart';
import '../utils/crash_handler_bridge.dart';

class GameController extends ChangeNotifier {
  GameController();

  final Random _random = Random();
  Timer? _timer;

  late PlayerState player;
  final List<EnemyModel> enemies = <EnemyModel>[];
  final List<CoinModel> coins = <CoinModel>[];

  double worldWidth = 360;
  double worldHeight = 640;
  double groundHeight = 110;
  double gravity = 0.9;
  double jumpForce = -14;
  double moveSpeed = 5;
  double scrollSpeed = 4;

  int score = 0;
  int coinCount = 0;
  int stage = 1;
  bool isRunning = false;
  bool isGameOver = false;

  void initialize({required double width, required double height}) {
    worldWidth = width;
    worldHeight = height;
    player = PlayerState(
      x: width * 0.18,
      y: height - groundHeight - 56,
      width: 42,
      height: 42,
    );
    enemies
      ..clear()
      ..addAll(_createInitialEnemies());
    coins
      ..clear()
      ..addAll(_createInitialCoins());
    score = 0;
    coinCount = 0;
    stage = 1;
    isRunning = false;
    isGameOver = false;
    notifyListeners();
  }

  void start() {
    if (isRunning || isGameOver) {
      return;
    }
    isRunning = true;
    _timer = Timer.periodic(const Duration(milliseconds: 16), (_) {
      try {
        _tick();
      } catch (error, stackTrace) {
        unawaited(
          CrashHandlerBridge.report(
            error,
            stackTrace,
            context: '게임 루프 업데이트 실패',
            rethrowError: false,
          ),
        );
        isGameOver = true;
        isRunning = false;
        _timer?.cancel();
        notifyListeners();
      }
    });
  }

  void stop() {
    isRunning = false;
    _timer?.cancel();
  }

  void moveLeft(bool active) {
    player.isMovingLeft = active;
  }

  void moveRight(bool active) {
    player.isMovingRight = active;
  }

  void jump() {
    if (!player.isJumping && !isGameOver) {
      player.isJumping = true;
      player.velocityY = jumpForce;
      notifyListeners();
    }
  }

  void _tick() {
    if (!isRunning || isGameOver) {
      return;
    }

    _updatePlayer();
    _updateEnemies();
    _updateCoins();
    _checkCollisions();
    score += 1;
    if (score % 500 == 0) {
      stage += 1;
      scrollSpeed += 0.3;
    }
    notifyListeners();
  }

  void _updatePlayer() {
    if (player.isMovingLeft) {
      player.x = max(0, player.x - moveSpeed);
    }
    if (player.isMovingRight) {
      player.x = min(worldWidth - player.width, player.x + moveSpeed);
    }

    player.velocityY += gravity;
    player.y += player.velocityY;

    final groundY = worldHeight - groundHeight - player.height;
    if (player.y >= groundY) {
      player.y = groundY;
      player.velocityY = 0;
      player.isJumping = false;
    }
  }

  void _updateEnemies() {
    for (final enemy in enemies) {
      enemy.x -= enemy.speed + scrollSpeed;
      if (enemy.x + enemy.width < 0) {
        enemy.x = worldWidth + _random.nextInt(180);
      }
    }
  }

  void _updateCoins() {
    for (final coin in coins) {
      coin.x -= scrollSpeed;
      if (coin.x + coin.size < 0) {
        coin.x = worldWidth + _random.nextInt(220);
        coin.y = 120 + _random.nextInt(max(80, (worldHeight - groundHeight - 220).toInt())).toDouble();
        coin.collected = false;
      }
    }
  }

  void _checkCollisions() {
    for (final enemy in enemies) {
      if (_overlaps(
        player.x,
        player.y,
        player.width,
        player.height,
        enemy.x,
        enemy.y,
        enemy.width,
        enemy.height,
      )) {
        player.lives -= 1;
        enemy.x = worldWidth + _random.nextInt(200);
        if (player.lives <= 0) {
          isGameOver = true;
          stop();
        }
        break;
      }
    }

    for (final coin in coins) {
      if (!coin.collected &&
          _overlaps(
            player.x,
            player.y,
            player.width,
            player.height,
            coin.x,
            coin.y,
            coin.size,
            coin.size,
          )) {
        coin.collected = true;
        coinCount += 1;
        score += 100;
      }
    }
  }

  bool _overlaps(
    double ax,
    double ay,
    double aw,
    double ah,
    double bx,
    double by,
    double bw,
    double bh,
  ) {
    return ax < bx + bw && ax + aw > bx && ay < by + bh && ay + ah > by;
  }

  List<EnemyModel> _createInitialEnemies() {
    final baseY = worldHeight - groundHeight - 34;
    return <EnemyModel>[
      EnemyModel(x: worldWidth + 80, y: baseY, width: 34, height: 34, speed: 2.5),
      EnemyModel(x: worldWidth + 260, y: baseY, width: 38, height: 38, speed: 3.0),
    ];
  }

  List<CoinModel> _createInitialCoins() {
    return <CoinModel>[
      CoinModel(x: worldWidth + 120, y: worldHeight - groundHeight - 120, size: 22),
      CoinModel(x: worldWidth + 260, y: worldHeight - groundHeight - 180, size: 22),
      CoinModel(x: worldWidth + 420, y: worldHeight - groundHeight - 140, size: 22),
    ];
  }

  @override
  void dispose() {
    _timer?.cancel();
    super.dispose();
  }
}
