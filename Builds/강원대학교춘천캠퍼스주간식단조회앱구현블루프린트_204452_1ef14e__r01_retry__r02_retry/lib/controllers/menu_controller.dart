import 'package:flutter/foundation.dart';

class WeeklyMenuController extends ChangeNotifier {
  WeeklyMenuController();

  bool isLoading = false;
  String? errorMessage;
  Map<String, dynamic>? weeklyMenu;

  Future<void> fetchCurrentWeek() async {
    try {
      isLoading = true;
      errorMessage = null;
      notifyListeners();

      await Future<void>.delayed(const Duration(milliseconds: 300));

      weeklyMenu = <String, dynamic>{
        '월요일': <String>['조식 없음', '중식 예시', '석식 예시'],
        '화요일': <String>['조식 없음', '중식 예시', '석식 예시'],
        '수요일': <String>['조식 없음', '중식 예시', '석식 예시'],
        '목요일': <String>['조식 없음', '중식 예시', '석식 예시'],
        '금요일': <String>['조식 없음', '중식 예시', '석식 예시'],
      };
    } catch (e) {
      errorMessage = '식단 정보를 불러오는 중 오류가 발생했습니다.';
      rethrow;
    } finally {
      isLoading = false;
      notifyListeners();
    }
  }
}
