class PermissionStatusModel {
  final bool notificationPermissionGranted;
  final bool permissionRequestedBefore;

  const PermissionStatusModel({
    required this.notificationPermissionGranted,
    required this.permissionRequestedBefore,
  });

  factory PermissionStatusModel.initial() {
    return const PermissionStatusModel(
      notificationPermissionGranted: false,
      permissionRequestedBefore: false,
    );
  }

  PermissionStatusModel copyWith({
    bool? notificationPermissionGranted,
    bool? permissionRequestedBefore,
  }) {
    return PermissionStatusModel(
      notificationPermissionGranted:
          notificationPermissionGranted ?? this.notificationPermissionGranted,
      permissionRequestedBefore:
          permissionRequestedBefore ?? this.permissionRequestedBefore,
    );
  }

  Map<String, dynamic> toJson() {
    return {
      'notificationPermissionGranted': notificationPermissionGranted,
      'permissionRequestedBefore': permissionRequestedBefore,
    };
  }

  factory PermissionStatusModel.fromJson(Map<String, dynamic> json) {
    return PermissionStatusModel(
      notificationPermissionGranted:
          json['notificationPermissionGranted'] as bool? ?? false,
      permissionRequestedBefore: json['permissionRequestedBefore'] as bool? ?? false,
    );
  }
}
