class FavoriteItem {
  const FavoriteItem({
    required this.campusName,
    required this.restaurantName,
    required this.sortOrder,
  });

  final String campusName;
  final String restaurantName;
  final int sortOrder;

  String get key => '$campusName|$restaurantName';

  Map<String, dynamic> toJson() => {
        'campusName': campusName,
        'restaurantName': restaurantName,
        'sortOrder': sortOrder,
      };

  factory FavoriteItem.fromJson(Map<String, dynamic> json) => FavoriteItem(
        campusName: json['campusName'] as String? ?? '',
        restaurantName: json['restaurantName'] as String? ?? '',
        sortOrder: json['sortOrder'] as int? ?? 0,
      );
}
