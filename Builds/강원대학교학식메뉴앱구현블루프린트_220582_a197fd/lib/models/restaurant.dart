class Restaurant {
  final String restaurantId;
  final String restaurantName;
  final String campus;
  final int displayOrder;

  const Restaurant({
    required this.restaurantId,
    required this.restaurantName,
    required this.campus,
    required this.displayOrder,
  });

  Map<String, dynamic> toJson() => {
        'restaurant_id': restaurantId,
        'restaurant_name': restaurantName,
        'campus': campus,
        'display_order': displayOrder,
      };

  factory Restaurant.fromJson(Map<String, dynamic> json) {
    return Restaurant(
      restaurantId: json['restaurant_id'] as String? ?? '',
      restaurantName: json['restaurant_name'] as String? ?? '',
      campus: json['campus'] as String? ?? '춘천캠퍼스',
      displayOrder: json['display_order'] as int? ?? 999,
    );
  }

  static const String chuncheonCampus = '춘천캠퍼스';

  static const List<Restaurant> defaults = [
    Restaurant(
      restaurantId: 'student_center',
      restaurantName: '학생식당',
      campus: chuncheonCampus,
      displayOrder: 1,
    ),
    Restaurant(
      restaurantId: 'dormitory',
      restaurantName: '생활관식당',
      campus: chuncheonCampus,
      displayOrder: 2,
    ),
    Restaurant(
      restaurantId: 'faculty',
      restaurantName: '교직원식당',
      campus: chuncheonCampus,
      displayOrder: 3,
    ),
  ];
}
