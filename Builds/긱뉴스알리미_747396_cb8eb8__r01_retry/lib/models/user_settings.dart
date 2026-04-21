class UserSettings {
  final bool notificationsEnabled;
  final bool backgroundSyncAllowed;
  final DateTime? lastNotificationAt;

  const UserSettings({
    required this.notificationsEnabled,
    required this.backgroundSyncAllowed,
    required this.lastNotificationAt,
  });

  factory UserSettings.initial() {
    return const UserSettings(
      notificationsEnabled: false,
      backgroundSyncAllowed: true,
      lastNotificationAt: null,
    );
  }

  UserSettings copyWith({
    bool? notificationsEnabled,
    bool? backgroundSyncAllowed,
    DateTime? lastNotificationAt,
  }) {
    return UserSettings(
      notificationsEnabled: notificationsEnabled ?? this.notificationsEnabled,
      backgroundSyncAllowed: backgroundSyncAllowed ?? this.backgroundSyncAllowed,
      lastNotificationAt: lastNotificationAt ?? this.lastNotificationAt,
    );
  }

  Map<String, dynamic> toJson() {
    return {
      'notificationsEnabled': notificationsEnabled,
      'backgroundSyncAllowed': backgroundSyncAllowed,
      'lastNotificationAt': lastNotificationAt?.toIso8601String(),
    };
  }

  factory UserSettings.fromJson(Map<String, dynamic> json) {
    return UserSettings(
      notificationsEnabled: json['notificationsEnabled'] as bool? ?? false,
      backgroundSyncAllowed: json['backgroundSyncAllowed'] as bool? ?? true,
      lastNotificationAt: DateTime.tryParse(json['lastNotificationAt'] as String? ?? ''),
    );
  }
}
