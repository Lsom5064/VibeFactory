class AppPermissionState {
  final String notificationPermissionStatus;

  const AppPermissionState({required this.notificationPermissionStatus});

  factory AppPermissionState.initial() {
    return const AppPermissionState(notificationPermissionStatus: '미요청');
  }

  Map<String, dynamic> toJson() => {
        'notification_permission_status': notificationPermissionStatus,
      };

  factory AppPermissionState.fromJson(Map<String, dynamic> json) {
    return AppPermissionState(
      notificationPermissionStatus:
          (json['notification_permission_status'] ?? '미요청') as String,
    );
  }
}
