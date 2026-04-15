class UserSettings {
  final String campusId;
  final String restaurantId;
  final bool notificationsEnabled;

  const UserSettings({
    required this.campusId,
    required this.restaurantId,
    required this.notificationsEnabled,
  });

  factory UserSettings.initial() {
    return const UserSettings(
      campusId: '',
      restaurantId: '',
      notificationsEnabled: false,
    );
  }

  UserSettings copyWith({
    String? campusId,
    String? restaurantId,
    bool? notificationsEnabled,
  }) {
    return UserSettings(
      campusId: campusId ?? this.campusId,
      restaurantId: restaurantId ?? this.restaurantId,
      notificationsEnabled: notificationsEnabled ?? this.notificationsEnabled,
    );
  }

  Map<String, dynamic> toJson() {
    return {
      'campusId': campusId,
      'restaurantId': restaurantId,
      'notificationsEnabled': notificationsEnabled,
    };
  }

  factory UserSettings.fromJson(Map<String, dynamic> json) {
    return UserSettings(
      campusId: json['campusId'] as String? ?? '',
      restaurantId: json['restaurantId'] as String? ?? '',
      notificationsEnabled: json['notificationsEnabled'] as bool? ?? false,
    );
  }
}
