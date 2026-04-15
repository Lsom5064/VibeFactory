import '../models/menu_models.dart';

class MenuScraperService {
  Future<WeeklyMenu> fetchCurrentWeek() async {
    return const WeeklyMenu(entries: [
      DailyMenu(
        date: '월',
        restaurant: '학생식당',
        section: '중식',
        items: ['등록된 메뉴가 없습니다.'],
      ),
    ]);
  }
}
