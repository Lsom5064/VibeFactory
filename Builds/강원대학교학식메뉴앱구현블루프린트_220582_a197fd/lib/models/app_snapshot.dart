import 'daily_menu.dart';
import 'restaurant.dart';
import 'sync_status.dart';

class AppSnapshot {
  final List<Restaurant> restaurants;
  final List<DailyMenu> dailyMenus;
  final SyncStatus syncStatus;

  const AppSnapshot({
    required this.restaurants,
    required this.dailyMenus,
    required this.syncStatus,
  });

  Map<String, dynamic> toJson() => {
        'restaurants': restaurants.map((e) => e.toJson()).toList(),
        'daily_menus': dailyMenus.map((e) => e.toJson()).toList(),
        'sync_status': syncStatus.toJson(),
      };

  factory AppSnapshot.fromJson(Map<String, dynamic> json) {
    return AppSnapshot(
      restaurants: (json['restaurants'] as List<dynamic>? ?? const [])
          .whereType<Map<String, dynamic>>()
          .map(Restaurant.fromJson)
          .toList(),
      dailyMenus: (json['daily_menus'] as List<dynamic>? ?? const [])
          .whereType<Map<String, dynamic>>()
          .map(DailyMenu.fromJson)
          .toList(),
      syncStatus: SyncStatus.fromJson(
        (json['sync_status'] as Map<String, dynamic>? ?? const {}),
      ),
    );
  }
}
