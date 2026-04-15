import 'dart:math' as math;
import 'dart:ui';

import '../models/game_object.dart';

class GameWorld {
  GameWorld({
    required this.player,
    required this.platforms,
    required this.hazards,
    required this.collectibles,
    required this.worldWidth,
    required this.worldHeight,
    this.score = 0,
    this.lives = 3,
    this.cameraOffset = 0,
    this.distanceScore = 0,
  });

  final PlayerState player;
  final List<PlatformBlock> platforms;
  final List<HazardBlock> hazards;
  final List<CollectibleItem> collectibles;
  final double worldWidth;
  final double worldHeight;
  int score;
  int lives;
  double cameraOffset;
  int distanceScore;
}

class GameTickResult {
  const GameTickResult({
    required this.hitHazard,
    required this.fellOut,
    required this.collectedScore,
    required this.cleared,
  });

  final bool hitHazard;
  final bool fellOut;
  final int collectedScore;
  final bool cleared;
}

class GameEngine {
  static const double gravity = 1800;
  static const double moveSpeed = 260;
  static const double jumpSpeed = -720;

  static GameWorld createInitialWorld() {
    final player = PlayerState(
      id: 'player',
      x: 80,
      y: 420,
      width: 42,
      height: 42,
    );

    final platforms = <PlatformBlock>[
      PlatformBlock(id: 'ground_0', x: 0, y: 500, width: 420, height: 80),
      PlatformBlock(id: 'p_1', x: 470, y: 450, width: 180, height: 28),
      PlatformBlock(id: 'p_2', x: 730, y: 390, width: 160, height: 28),
      PlatformBlock(id: 'p_3', x: 980, y: 340, width: 180, height: 28),
      PlatformBlock(id: 'p_4', x: 1240, y: 420, width: 220, height: 28),
      PlatformBlock(id: 'p_5', x: 1540, y: 360, width: 180, height: 28),
      PlatformBlock(id: 'goal', x: 1820, y: 300, width: 220, height: 28),
    ];

    final hazards = <HazardBlock>[
      HazardBlock(id: 'h_1', x: 560, y: 422, width: 36, height: 28),
      HazardBlock(id: 'h_2', x: 1080, y: 312, width: 36, height: 28),
      HazardBlock(id: 'h_3', x: 1660, y: 332, width: 36, height: 28),
    ];

    final collectibles = <CollectibleItem>[
      CollectibleItem(id: 'c_1', x: 520, y: 400, width: 22, height: 22, value: 15),
      CollectibleItem(id: 'c_2', x: 790, y: 340, width: 22, height: 22, value: 15),
      CollectibleItem(id: 'c_3', x: 1320, y: 370, width: 22, height: 22, value: 20),
      CollectibleItem(id: 'c_4', x: 1880, y: 250, width: 22, height: 22, value: 30),
    ];

    return GameWorld(
      player: player,
      platforms: platforms,
      hazards: hazards,
      collectibles: collectibles,
      worldWidth: 2200,
      worldHeight: 580,
    );
  }

  static void setMoveDirection(PlayerState player, int direction) {
    player.velocityX = direction * moveSpeed;
    if (direction != 0) {
      player.facing = direction;
    }
  }

  static void jump(PlayerState player) {
    if (!player.isJumping) {
      player.velocityY = jumpSpeed;
      player.isJumping = true;
    }
  }

  static GameTickResult update({
    required GameWorld world,
    required double dt,
    required double viewportWidth,
  }) {
    final safeDt = dt.isFinite ? dt.clamp(0.0, 0.033) : 0.016;
    final player = world.player;
    int collectedScore = 0;
    bool hitHazard = false;
    bool fellOut = false;
    bool cleared = false;

    final previousRect = player.rect;

    player.velocityY += gravity * safeDt;
    player.x += player.velocityX * safeDt;
    player.y += player.velocityY * safeDt;

    player.x = player.x.clamp(0.0, world.worldWidth - player.width);

    for (final platform in world.platforms) {
      if (_landedOnTop(previousRect, player.rect, platform.rect)) {
        player.y = platform.y - player.height;
        player.velocityY = 0;
        player.isJumping = false;
      }
    }

    for (final collectible in world.collectibles) {
      if (collectible.active && player.rect.overlaps(collectible.rect)) {
        collectible.active = false;
        collectedScore += collectible.value;
      }
    }

    for (final hazard in world.hazards) {
      if (player.rect.overlaps(hazard.rect)) {
        hitHazard = true;
        break;
      }
    }

    if (player.y > world.worldHeight + 120) {
      fellOut = true;
    }

    world.score += collectedScore;
    world.distanceScore = math.max(world.distanceScore, (player.x / 10).floor());
    world.cameraOffset = (player.x - viewportWidth * 0.35)
        .clamp(0.0, math.max(0.0, world.worldWidth - viewportWidth));

    if (player.x > world.worldWidth - 180) {
      cleared = true;
      world.score += 100;
    }

    return GameTickResult(
      hitHazard: hitHazard,
      fellOut: fellOut,
      collectedScore: collectedScore,
      cleared: cleared,
    );
  }

  static bool _landedOnTop(Rect previous, Rect current, Rect platform) {
    final wasAbove = previous.bottom <= platform.top + 6;
    final isNowBelowTop = current.bottom >= platform.top;
    final horizontalOverlap = current.right > platform.left + 8 && current.left < platform.right - 8;
    return wasAbove && isNowBelowTop && horizontalOverlap;
  }

  static void resetPlayer(GameWorld world) {
    world.player.x = 80;
    world.player.y = 420;
    world.player.velocityX = 0;
    world.player.velocityY = 0;
    world.player.isJumping = false;
    world.cameraOffset = 0;
  }
}
