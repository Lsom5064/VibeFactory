class MenuItem {
  const MenuItem({
    required this.campusName,
    required this.restaurantName,
    required this.menuCategoryName,
    required this.mealType,
    required this.dateLabel,
    required this.dayOfWeek,
    required this.menuBody,
    required this.targetDate,
    required this.sourceUrl,
  });

  final String campusName;
  final String restaurantName;
  final String menuCategoryName;
  final String mealType;
  final String dateLabel;
  final String dayOfWeek;
  final String menuBody;
  final String targetDate;
  final String sourceUrl;

  String get cacheKey => '$campusName|$restaurantName|$targetDate';

  Map<String, dynamic> toJson() => {
        'campusName': campusName,
        'restaurantName': restaurantName,
        'menuCategoryName': menuCategoryName,
        'mealType': mealType,
        'dateLabel': dateLabel,
        'dayOfWeek': dayOfWeek,
        'menuBody': menuBody,
        'targetDate': targetDate,
        'sourceUrl': sourceUrl,
      };

  factory MenuItem.fromJson(Map<String, dynamic> json) => MenuItem(
        campusName: json['campusName'] as String? ?? '',
        restaurantName: json['restaurantName'] as String? ?? '',
        menuCategoryName: json['menuCategoryName'] as String? ?? '',
        mealType: json['mealType'] as String? ?? '',
        dateLabel: json['dateLabel'] as String? ?? '',
        dayOfWeek: json['dayOfWeek'] as String? ?? '',
        menuBody: json['menuBody'] as String? ?? '',
        targetDate: json['targetDate'] as String? ?? '',
        sourceUrl: json['sourceUrl'] as String? ?? '',
      );
}
