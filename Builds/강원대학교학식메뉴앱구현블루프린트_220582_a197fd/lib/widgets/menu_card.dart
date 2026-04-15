import 'package:flutter/material.dart';

import '../core/utils/date_utils.dart';
import '../models/daily_menu.dart';
import '../models/restaurant.dart';

class MenuCard extends StatelessWidget {
  final DailyMenu menu;
  final List<Restaurant> restaurants;

  const MenuCard({
    super.key,
    required this.menu,
    required this.restaurants,
  });

  @override
  Widget build(BuildContext context) {
    final restaurantName = restaurants
        .where((restaurant) => restaurant.restaurantId == menu.restaurantId)
        .map((restaurant) => restaurant.restaurantName)
        .cast<String?>()
        .firstWhere((value) => value != null, orElse: () => '알 수 없는 식당');

    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
      child: Card(
        child: Padding(
          padding: const EdgeInsets.all(16),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(restaurantName ?? '알 수 없는 식당', style: Theme.of(context).textTheme.titleMedium),
              const SizedBox(height: 4),
              Text('${AppDateUtils.formatKoreanDate(menu.date)} ${menu.weekday}'.trim()),
              if (menu.mealType.trim().isNotEmpty) ...[
                const SizedBox(height: 8),
                Text(menu.mealType, style: Theme.of(context).textTheme.labelLarge),
              ],
              const SizedBox(height: 12),
              ...menu.menuItems.map(
                (item) => Padding(
                  padding: const EdgeInsets.only(bottom: 6),
                  child: Text('• $item'),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
