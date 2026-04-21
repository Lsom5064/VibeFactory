class UserSettings {
  const UserSettings({
    required this.selectedRestaurantName,
    required this.notificationEnabled,
    required this.notificationTime,
  });

  final String selectedRestaurantName;
  final bool notificationEnabled;
  final String notificationTime;

  UserSettings copyWith({
    String? selectedRestaurantName,
    bool? notificationEnabled,
    String? notificationTime,
  }) {
    return UserSettings(
      selectedRestaurantName:
          selectedRestaurantName ?? this.selectedRestaurantName,
      notificationEnabled: notificationEnabled ?? this.notificationEnabled,
      notificationTime: notificationTime ?? this.notificationTime,
    );
  }

  Map<String, dynamic> toJson() {
    return <String, dynamic>{
      '선택_식당명': selectedRestaurantName,
      '알림_활성화': notificationEnabled,
      '알림_시각': notificationTime,
    };
  }

  factory UserSettings.fromJson(Map<String, dynamic> json) {
    return UserSettings(
      selectedRestaurantName: (json['선택_식당명'] ?? '').toString(),
      notificationEnabled: json['알림_활성화'] == true,
      notificationTime: (json['알림_시각'] ?? '매일 오전 8시').toString(),
    );
  }

  factory UserSettings.defaults() {
    return const UserSettings(
      selectedRestaurantName: '',
      notificationEnabled: false,
      notificationTime: '매일 오전 8시',
    );
  }
}
