class MenuData {
  final String campusId;
  final String restaurantId;
  final DateTime date;
  final List<String> meals;
  final String source;

  const MenuData({
    required this.campusId,
    required this.restaurantId,
    required this.date,
    required this.meals,
    required this.source,
  });

  Map<String, dynamic> toJson() {
    return {
      'campusId': campusId,
      'restaurantId': restaurantId,
      'date': date.toIso8601String(),
      'meals': meals,
      'source': source,
    };
  }

  factory MenuData.fromJson(Map<String, dynamic> json) {
    return MenuData(
      campusId: json['campusId'] as String? ?? '',
      restaurantId: json['restaurantId'] as String? ?? '',
      date: DateTime.tryParse(json['date'] as String? ?? '') ?? DateTime.now(),
      meals: (json['meals'] as List<dynamic>? ?? const <dynamic>[])
          .map((item) => item.toString())
          .toList(),
      source: json['source'] as String? ?? '',
    );
  }
}
