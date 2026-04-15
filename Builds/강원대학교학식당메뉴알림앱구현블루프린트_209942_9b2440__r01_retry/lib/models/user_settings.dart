class UserSettings {
  final String? campusId;
  final String? restaurantId;
  final bool notificationsEnabled;
  final int notificationHour;
  final int notificationMinute;

  const UserSettings({
    required this.campusId,
    required this.restaurantId,
    required this.notificationsEnabled,
    required this.notificationHour,
    required this.notificationMinute,
  });

  factory UserSettings.initial() {
    return const UserSettings(
      campusId: null,
      restaurantId: null,
      notificationsEnabled: false,
      notificationHour: 8,
      notificationMinute: 0,
    );
  }

  UserSettings copyWith({
    Object? campusId = _sentinel,
    Object? restaurantId = _sentinel,
    bool? notificationsEnabled,
    int? notificationHour,
    int? notificationMinute,
  }) {
    return UserSettings(
      campusId: identical(campusId, _sentinel) ? this.campusId : campusId as String?,
      restaurantId: identical(restaurantId, _sentinel) ? this.restaurantId : restaurantId as String?,
      notificationsEnabled: notificationsEnabled ?? this.notificationsEnabled,
      notificationHour: notificationHour ?? this.notificationHour,
      notificationMinute: notificationMinute ?? this.notificationMinute,
    );
  }

  Map<String, dynamic> toJson() {
    return {
      'campusId': campusId,
      'restaurantId': restaurantId,
      'notificationsEnabled': notificationsEnabled,
      'notificationHour': notificationHour,
      'notificationMinute': notificationMinute,
    };
  }

  factory UserSettings.fromJson(Map<String, dynamic> json) {
    return UserSettings(
      campusId: json['campusId'] as String?,
      restaurantId: json['restaurantId'] as String?,
      notificationsEnabled: json['notificationsEnabled'] as bool? ?? false,
      notificationHour: json['notificationHour'] as int? ?? 8,
      notificationMinute: json['notificationMinute'] as int? ?? 0,
    );
  }
}

const Object _sentinel = Object();
