import 'dart:ui';

class GameObject {
  GameObject({
    required this.id,
    required this.x,
    required this.y,
    required this.width,
    required this.height,
    this.active = true,
  });

  final String id;
  double x;
  double y;
  double width;
  double height;
  bool active;

  Rect get rect => Rect.fromLTWH(x, y, width, height);
}

class PlatformBlock extends GameObject {
  PlatformBlock({
    required super.id,
    required super.x,
    required super.y,
    required super.width,
    required super.height,
  });
}

class HazardBlock extends GameObject {
  HazardBlock({
    required super.id,
    required super.x,
    required super.y,
    required super.width,
    required super.height,
  });
}

class CollectibleItem extends GameObject {
  CollectibleItem({
    required super.id,
    required super.x,
    required super.y,
    required super.width,
    required super.height,
    this.value = 10,
  });

  final int value;
}

class PlayerState extends GameObject {
  PlayerState({
    required super.id,
    required super.x,
    required super.y,
    required super.width,
    required super.height,
    this.velocityX = 0,
    this.velocityY = 0,
    this.isJumping = false,
    this.facing = 1,
  });

  double velocityX;
  double velocityY;
  bool isJumping;
  int facing;
}
