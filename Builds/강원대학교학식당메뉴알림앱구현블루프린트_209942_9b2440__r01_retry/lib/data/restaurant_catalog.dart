import '../models/restaurant_info.dart';

class RestaurantCatalog {
  static const List<Map<String, String>> campuses = [
    {'id': 'chuncheon', 'name': '춘천캠퍼스'},
    {'id': 'samcheok', 'name': '삼척캠퍼스'},
  ];

  static const List<RestaurantInfo> restaurants = [
    RestaurantInfo(campusId: 'chuncheon', restaurantId: 'student', name: '학생식당', type: '학생'),
    RestaurantInfo(campusId: 'chuncheon', restaurantId: 'staff', name: '교직원식당', type: '교직원'),
    RestaurantInfo(campusId: 'samcheok', restaurantId: 'student', name: '학생식당', type: '학생'),
    RestaurantInfo(campusId: 'samcheok', restaurantId: 'staff', name: '교직원식당', type: '교직원'),
  ];

  static List<RestaurantInfo> restaurantsForCampus(String? campusId) {
    if (campusId == null) {
      return const [];
    }
    return restaurants.where((e) => e.campusId == campusId).toList();
  }

  static RestaurantInfo? findRestaurant(String? campusId, String? restaurantId) {
    if (campusId == null || restaurantId == null) {
      return null;
    }
    try {
      return restaurants.firstWhere((e) => e.campusId == campusId && e.restaurantId == restaurantId);
    } catch (_) {
      return null;
    }
  }

  static String campusName(String? campusId) {
    final found = campuses.where((e) => e['id'] == campusId).toList();
    if (found.isEmpty) {
      return '미선택';
    }
    return found.first['name'] ?? '미선택';
  }
}
