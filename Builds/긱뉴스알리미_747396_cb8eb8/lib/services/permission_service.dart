import 'dart:io';

import 'package:permission_handler/permission_handler.dart' as handler;

import '../models/permission_status_model.dart';
import 'crash_handler.dart';

class PermissionService {
  Future<PermissionStatusModel> getNotificationPermissionStatus({
    bool requestedBefore = false,
  }) async {
    try {
      if (!Platform.isAndroid) {
        return PermissionStatusModel(
          notificationPermissionGranted: true,
          permissionRequestedBefore: requestedBefore,
        );
      }
      final status = await handler.Permission.notification.status;
      return PermissionStatusModel(
        notificationPermissionGranted: status.isGranted,
        permissionRequestedBefore: requestedBefore,
      );
    } catch (error, stackTrace) {
      CrashHandler.recordError(
        error,
        stackTrace,
        context: 'PermissionService.getNotificationPermissionStatus',
      );
      return PermissionStatusModel(
        notificationPermissionGranted: false,
        permissionRequestedBefore: requestedBefore,
      );
    }
  }

  Future<PermissionStatusModel> requestNotificationPermissionWithRationale() async {
    try {
      if (!Platform.isAndroid) {
        return const PermissionStatusModel(
          notificationPermissionGranted: true,
          permissionRequestedBefore: true,
        );
      }
      final result = await handler.Permission.notification.request();
      return PermissionStatusModel(
        notificationPermissionGranted: result.isGranted,
        permissionRequestedBefore: true,
      );
    } catch (error, stackTrace) {
      CrashHandler.recordError(
        error,
        stackTrace,
        context: 'PermissionService.requestNotificationPermissionWithRationale',
      );
      return const PermissionStatusModel(
        notificationPermissionGranted: false,
        permissionRequestedBefore: true,
      );
    }
  }

  Future<bool> openNotificationSettings() async {
    try {
      return await handler.openAppSettings();
    } catch (error, stackTrace) {
      CrashHandler.recordError(
        error,
        stackTrace,
        context: 'PermissionService.openNotificationSettings',
      );
      return false;
    }
  }
}
