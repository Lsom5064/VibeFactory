import 'package:flutter/material.dart';

import '../models/menu_item.dart';

class MenuCard extends StatelessWidget {
  const MenuCard({super.key, required this.item});

  final MenuItem item;

  @override
  Widget build(BuildContext context) {
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text('${item.menuCategoryName} (${item.mealType})',
                style: Theme.of(context).textTheme.titleMedium),
            const SizedBox(height: 8),
            Text('${item.dateLabel} · ${item.dayOfWeek}'),
            const SizedBox(height: 8),
            Text(item.menuBody),
          ],
        ),
      ),
    );
  }
}
