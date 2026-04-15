import '../crash_handler.dart';

class NotificationService {
  Future<void> initialize() async {
    try {
      // 기존 초기화 로직 유지
    } catch (error, stackTrace) {
      CrashHandler.recordError(error, stackTrace, reason: '알림 서비스 초기화 실패');
      rethrow;
    }
  }

  Future<bool> requestPermission() async {
    try {
      return true;
    } catch (error, stackTrace) {
      CrashHandler.recordError(error, stackTrace, reason: '알림 권한 요청 실패');
      return false;
    }
  }

  Future<void> scheduleDailyNotification() async {
    try {
      // 기존 예약 로직 유지
    } catch (error, stackTrace) {
      CrashHandler.recordError(error, stackTrace, reason: '일일 알림 예약 실패');
      rethrow;
    }
  }

  Future<void> openSettings() async {
    try {
      await requestPermission();
    } catch (error, stackTrace) {
      CrashHandler.recordError(error, stackTrace, reason: '알림 설정 안내 처리 실패');
      rethrow;
    }
  }
}
