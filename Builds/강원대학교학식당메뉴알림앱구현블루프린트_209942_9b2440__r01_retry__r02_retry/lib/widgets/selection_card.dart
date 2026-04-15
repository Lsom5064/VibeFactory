import 'package:flutter/material.dart';

import '../data/restaurant_catalog.dart';
import '../models/restaurant_info.dart';

class SelectionCard extends StatelessWidget {
  final String? selectedCampusId;
  final String? selectedRestaurantId;
  final ValueChanged<String?> onCampusChanged;
  final ValueChanged<String?> onRestaurantChanged;
  final String? helperMessage;

  const SelectionCard({
    super.key,
    required this.selectedCampusId,
    required this.selectedRestaurantId,
    required this.onCampusChanged,
    required this.onRestaurantChanged,
    required this.helperMessage,
  });

  @override
  Widget build(BuildContext context) {
    final restaurants = RestaurantCatalog.restaurantsForCampus(selectedCampusId);
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text('캠퍼스 및 식당 선택', style: Theme.of(context).textTheme.titleLarge),
            const SizedBox(height: 12),
            DropdownButtonFormField<String>(
              value: selectedCampusId,
              decoration: const InputDecoration(labelText: '캠퍼스'),
              items: RestaurantCatalog.campuses
                  .map(
                    (campus) => DropdownMenuItem<String>(
                      value: campus['id'],
                      child: Text(campus['name'] ?? ''),
                    ),
                  )
                  .toList(),
              onChanged: onCampusChanged,
            ),
            const SizedBox(height: 12),
            DropdownButtonFormField<String>(
              value: restaurants.any((e) => e.restaurantId == selectedRestaurantId) ? selectedRestaurantId : null,
              decoration: const InputDecoration(labelText: '식당'),
              items: restaurants
                  .map(
                    (RestaurantInfo restaurant) => DropdownMenuItem<String>(
                      value: restaurant.restaurantId,
                      child: Text(restaurant.name),
                    ),
                  )
                  .toList(),
              onChanged: selectedCampusId == null ? null : onRestaurantChanged,
            ),
            if (helperMessage != null) ...[
              const SizedBox(height: 12),
              Text(helperMessage!),
            ],
          ],
        ),
      ),
    );
  }
}
