class DailyMenu {
  final String date;
  final String weekday;
  final String restaurantId;
  final String mealType;
  final List<String> menuItems;
  final String rawText;
  final String fetchedAt;

  const DailyMenu({
    required this.date,
    required this.weekday,
    required this.restaurantId,
    required this.mealType,
    required this.menuItems,
    required this.rawText,
    required this.fetchedAt,
  });

  bool get isValid {
    return date.trim().isNotEmpty &&
        restaurantId.trim().isNotEmpty &&
        fetchedAt.trim().isNotEmpty &&
        menuItems.any((item) => item.trim().isNotEmpty);
  }

  Map<String, dynamic> toJson() => {
        'date': date,
        'weekday': weekday,
        'restaurant_id': restaurantId,
        'meal_type': mealType,
        'menu_items': menuItems,
        'raw_text': rawText,
        'fetched_at': fetchedAt,
      };

  factory DailyMenu.fromJson(Map<String, dynamic> json) {
    return DailyMenu(
      date: json['date'] as String? ?? '',
      weekday: json['weekday'] as String? ?? '',
      restaurantId: json['restaurant_id'] as String? ?? '',
      mealType: json['meal_type'] as String? ?? '',
      menuItems: (json['menu_items'] as List<dynamic>? ?? const [])
          .map((e) => e.toString())
          .toList(),
      rawText: json['raw_text'] as String? ?? '',
      fetchedAt: json['fetched_at'] as String? ?? '',
    );
  }
}
