class MenuCacheItem {
  const MenuCacheItem({
    required this.date,
    required this.restaurantName,
    required this.menuText,
  });

  final String date;
  final String restaurantName;
  final String menuText;

  MenuCacheItem copyWith({
    String? date,
    String? restaurantName,
    String? menuText,
  }) {
    return MenuCacheItem(
      date: date ?? this.date,
      restaurantName: restaurantName ?? this.restaurantName,
      menuText: menuText ?? this.menuText,
    );
  }

  Map<String, dynamic> toJson() {
    return <String, dynamic>{
      '날짜': date,
      '식당명': restaurantName,
      '메뉴_텍스트': menuText,
    };
  }

  factory MenuCacheItem.fromJson(Map<String, dynamic> json) {
    return MenuCacheItem(
      date: (json['날짜'] ?? '').toString(),
      restaurantName: (json['식당명'] ?? '').toString(),
      menuText: (json['메뉴_텍스트'] ?? '').toString(),
    );
  }
}
