class RestaurantInfo {
  final String id;
  final String name;

  const RestaurantInfo({required this.id, required this.name});
}

class CampusInfo {
  final String id;
  final String name;
  final List<RestaurantInfo> restaurants;

  const CampusInfo({
    required this.id,
    required this.name,
    required this.restaurants,
  });
}

class RestaurantCatalog {
  static const List<CampusInfo> campuses = <CampusInfo>[
    CampusInfo(
      id: 'main',
      name: '메인 캠퍼스',
      restaurants: <RestaurantInfo>[
        RestaurantInfo(id: 'student', name: '학생식당'),
        RestaurantInfo(id: 'staff', name: '교직원식당'),
      ],
    ),
  ];

  static List<RestaurantInfo> restaurantsForCampus(String campusId) {
    for (final campus in campuses) {
      if (campus.id == campusId) {
        return campus.restaurants;
      }
    }
    return const <RestaurantInfo>[];
  }

  static RestaurantInfo? findRestaurant(String campusId, String restaurantId) {
    final restaurants = restaurantsForCampus(campusId);
    for (final restaurant in restaurants) {
      if (restaurant.id == restaurantId) {
        return restaurant;
      }
    }
    return null;
  }
}
