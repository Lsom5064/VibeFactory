import 'package:permission_handler/permission_handler.dart';

class PermissionService {
  Future<PermissionStatus> getNotificationPermissionStatus() async {
    return Permission.notification.status;
  }

  Future<bool> openAppNotificationSettings() async {
    return openAppSettings();
  }
}
