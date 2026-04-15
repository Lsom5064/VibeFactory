import 'package:flutter/material.dart';

import '../core/utils/date_utils.dart';
import '../models/daily_menu.dart';
import '../models/restaurant.dart';
import 'menu_card.dart';

class DateSection extends StatelessWidget {
  final String date;
  final List<DailyMenu> menus;
  final List<Restaurant> restaurants;

  const DateSection({
    super.key,
    required this.date,
    required this.menus,
    required this.restaurants,
  });

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 8),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Padding(
            padding: const EdgeInsets.symmetric(horizontal: 16),
            child: Text(
              AppDateUtils.formatKoreanDate(date),
              style: Theme.of(context).textTheme.titleLarge,
            ),
          ),
          const SizedBox(height: 4),
          ...menus.map((menu) => MenuCard(menu: menu, restaurants: restaurants)),
        ],
      ),
    );
  }
}
