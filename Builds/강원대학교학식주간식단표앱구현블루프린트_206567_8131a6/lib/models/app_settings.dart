class AppSettings {
  final String? lastSelectedRestaurantId;
  final String? favoriteRestaurantId;
  final DateTime? lastSelectedDate;
  final DateTime? lastSuccessfulSync;

  const AppSettings({
    required this.lastSelectedRestaurantId,
    required this.favoriteRestaurantId,
    required this.lastSelectedDate,
    required this.lastSuccessfulSync,
  });
}
