class RecentViewItem {
  const RecentViewItem({
    required this.campusName,
    required this.restaurantName,
    required this.targetDate,
    required this.viewedAt,
    required this.usedCache,
  });

  final String campusName;
  final String restaurantName;
  final String targetDate;
  final String viewedAt;
  final bool usedCache;

  String get key => '$campusName|$restaurantName';

  Map<String, dynamic> toJson() => {
        'campusName': campusName,
        'restaurantName': restaurantName,
        'targetDate': targetDate,
        'viewedAt': viewedAt,
        'usedCache': usedCache,
      };

  factory RecentViewItem.fromJson(Map<String, dynamic> json) => RecentViewItem(
        campusName: json['campusName'] as String? ?? '',
        restaurantName: json['restaurantName'] as String? ?? '',
        targetDate: json['targetDate'] as String? ?? '',
        viewedAt: json['viewedAt'] as String? ?? '',
        usedCache: json['usedCache'] as bool? ?? false,
      );
}
