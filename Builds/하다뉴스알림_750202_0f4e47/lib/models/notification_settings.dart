class AppNotificationSettings {
  final bool notificationsEnabled;
  final String backgroundCheckPolicy;
  final bool permissionPromptCompleted;

  const AppNotificationSettings({
    required this.notificationsEnabled,
    required this.backgroundCheckPolicy,
    required this.permissionPromptCompleted,
  });

  factory AppNotificationSettings.initial() {
    return const AppNotificationSettings(
      notificationsEnabled: false,
      backgroundCheckPolicy: '보수적 배터리 절약 정책: 하루 수회 이하 확인',
      permissionPromptCompleted: false,
    );
  }

  AppNotificationSettings copyWith({
    bool? notificationsEnabled,
    String? backgroundCheckPolicy,
    bool? permissionPromptCompleted,
  }) {
    return AppNotificationSettings(
      notificationsEnabled: notificationsEnabled ?? this.notificationsEnabled,
      backgroundCheckPolicy:
          backgroundCheckPolicy ?? this.backgroundCheckPolicy,
      permissionPromptCompleted:
          permissionPromptCompleted ?? this.permissionPromptCompleted,
    );
  }

  Map<String, dynamic> toJson() => {
        'notifications_enabled': notificationsEnabled,
        'background_check_policy': backgroundCheckPolicy,
        'permission_prompt_completed': permissionPromptCompleted,
      };

  factory AppNotificationSettings.fromJson(Map<String, dynamic> json) {
    return AppNotificationSettings(
      notificationsEnabled: (json['notifications_enabled'] ?? false) as bool,
      backgroundCheckPolicy:
          (json['background_check_policy'] ?? '보수적 배터리 절약 정책: 하루 수회 이하 확인')
              as String,
      permissionPromptCompleted:
          (json['permission_prompt_completed'] ?? false) as bool,
    );
  }
}
