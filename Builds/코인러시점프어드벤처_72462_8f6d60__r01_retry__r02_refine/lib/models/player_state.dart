class PlayerState {
  PlayerState({
    required this.x,
    required this.y,
    required this.width,
    required this.height,
    this.velocityY = 0,
    this.isJumping = false,
    this.isMovingLeft = false,
    this.isMovingRight = false,
    this.lives = 3,
  });

  double x;
  double y;
  double width;
  double height;
  double velocityY;
  bool isJumping;
  bool isMovingLeft;
  bool isMovingRight;
  int lives;
}
