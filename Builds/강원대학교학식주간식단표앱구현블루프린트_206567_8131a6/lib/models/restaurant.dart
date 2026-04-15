class Restaurant {
  final String id;
  final String name;
  final int sortOrder;

  const Restaurant({
    required this.id,
    required this.name,
    required this.sortOrder,
  });

  Map<String, dynamic> toJson() => {
        'id': id,
        'name': name,
        'sortOrder': sortOrder,
      };

  factory Restaurant.fromJson(Map<String, dynamic> json) {
    return Restaurant(
      id: json['id'] as String? ?? '',
      name: json['name'] as String? ?? '',
      sortOrder: json['sortOrder'] as int? ?? 0,
    );
  }
}
