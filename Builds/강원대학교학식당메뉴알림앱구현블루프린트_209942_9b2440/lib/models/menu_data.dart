class MenuData {
  final String menuId;
  final String campusId;
  final String restaurantId;
  final String baseDate;
  final List<String> items;
  final String rawText;
  final String parsedAt;
  final String sourceLabel;
  final bool isFromCache;

  const MenuData({
    required this.menuId,
    required this.campusId,
    required this.restaurantId,
    required this.baseDate,
    required this.items,
    required this.rawText,
    required this.parsedAt,
    required this.sourceLabel,
    required this.isFromCache,
  });

  MenuData copyWith({bool? isFromCache}) {
    return MenuData(
      menuId: menuId,
      campusId: campusId,
      restaurantId: restaurantId,
      baseDate: baseDate,
      items: items,
      rawText: rawText,
      parsedAt: parsedAt,
      sourceLabel: sourceLabel,
      isFromCache: isFromCache ?? this.isFromCache,
    );
  }

  Map<String, dynamic> toJson() {
    return {
      'menuId': menuId,
      'campusId': campusId,
      'restaurantId': restaurantId,
      'baseDate': baseDate,
      'items': items,
      'rawText': rawText,
      'parsedAt': parsedAt,
      'sourceLabel': sourceLabel,
      'isFromCache': isFromCache,
    };
  }

  factory MenuData.fromJson(Map<String, dynamic> json) {
    return MenuData(
      menuId: json['menuId'] as String? ?? '',
      campusId: json['campusId'] as String? ?? '',
      restaurantId: json['restaurantId'] as String? ?? '',
      baseDate: json['baseDate'] as String? ?? '',
      items: (json['items'] as List<dynamic>? ?? const []).map((e) => e.toString()).toList(),
      rawText: json['rawText'] as String? ?? '',
      parsedAt: json['parsedAt'] as String? ?? '',
      sourceLabel: json['sourceLabel'] as String? ?? '',
      isFromCache: json['isFromCache'] as bool? ?? false,
    );
  }
}
