class CoinModel {
  CoinModel({
    required this.x,
    required this.y,
    required this.size,
    this.collected = false,
  });

  double x;
  double y;
  double size;
  bool collected;
}
