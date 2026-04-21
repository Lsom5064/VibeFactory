class RestaurantNotificationSetting {
  const RestaurantNotificationSetting({
    required this.campusName,
    required this.restaurantName,
    required this.notificationTime,
    required this.isEnabled,
    required this.repeatDays,
    this.lastScheduledAt,
  });

  final String campusName;
  final String restaurantName;
  final String notificationTime;
  final bool isEnabled;
  final List<String> repeatDays;
  final String? lastScheduledAt;

  String get key => '$campusName|$restaurantName';

  RestaurantNotificationSetting copyWith({
    String? notificationTime,
    bool? isEnabled,
    List<String>? repeatDays,
    String? lastScheduledAt,
  }) {
    return RestaurantNotificationSetting(
      campusName: campusName,
      restaurantName: restaurantName,
      notificationTime: notificationTime ?? this.notificationTime,
      isEnabled: isEnabled ?? this.isEnabled,
      repeatDays: repeatDays ?? this.repeatDays,
      lastScheduledAt: lastScheduledAt ?? this.lastScheduledAt,
    );
  }

  Map<String, dynamic> toJson() => {
        'campusName': campusName,
        'restaurantName': restaurantName,
        'notificationTime': notificationTime,
        'isEnabled': isEnabled,
        'repeatDays': repeatDays,
        'lastScheduledAt': lastScheduledAt,
      };

  factory RestaurantNotificationSetting.fromJson(Map<String, dynamic> json) =>
      RestaurantNotificationSetting(
        campusName: json['campusName'] as String? ?? '',
        restaurantName: json['restaurantName'] as String? ?? '',
        notificationTime: json['notificationTime'] as String? ?? '',
        isEnabled: json['isEnabled'] as bool? ?? false,
        repeatDays: (json['repeatDays'] as List<dynamic>? ?? <dynamic>[])
            .map((e) => e.toString())
            .toList(),
        lastScheduledAt: json['lastScheduledAt'] as String?,
      );
}
