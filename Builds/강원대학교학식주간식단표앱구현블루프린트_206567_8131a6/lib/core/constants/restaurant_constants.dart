import '../../models/restaurant.dart';

class RestaurantConstants {
  static const String officialBaseUrl = 'https://www.kangwon.ac.kr/www/selecttnSchafsList.do';

  static const List<Restaurant> restaurants = [
    Restaurant(id: 'student', name: '학생식당', sortOrder: 0),
    Restaurant(id: 'dormitory', name: '기숙사식당', sortOrder: 1),
    Restaurant(id: 'staff', name: '교직원식당', sortOrder: 2),
  ];

  static Uri buildWeeklyMenuUri({
    required String restaurantId,
    required DateTime weekStart,
  }) {
    final normalized = DateTime(weekStart.year, weekStart.month, weekStart.day);
    final weekValue =
        '${normalized.year.toString().padLeft(4, '0')}-${normalized.month.toString().padLeft(2, '0')}-${normalized.day.toString().padLeft(2, '0')}';

    return Uri.parse(officialBaseUrl).replace(
      queryParameters: <String, String>{
        'key': '561',
        'sc1': restaurantId,
        'schM': 'week',
        'schYmd': weekValue,
      },
    );
  }

  const RestaurantConstants._();
}
