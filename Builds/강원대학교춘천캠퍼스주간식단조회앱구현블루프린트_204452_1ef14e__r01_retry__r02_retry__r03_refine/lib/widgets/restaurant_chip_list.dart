import 'package:flutter/material.dart';

class RestaurantChipList extends StatelessWidget {
  final List<String> restaurants;
  final String? selectedRestaurant;
  final ValueChanged<String> onSelected;

  const RestaurantChipList({
    super.key,
    required this.restaurants,
    required this.selectedRestaurant,
    required this.onSelected,
  });

  @override
  Widget build(BuildContext context) {
    return Wrap(
      spacing: 8,
      runSpacing: 8,
      children: restaurants
          .map(
            (restaurant) => ChoiceChip(
              label: Text(restaurant),
              selected: selectedRestaurant == restaurant,
              onSelected: (_) => onSelected(restaurant),
            ),
          )
          .toList(),
    );
  }
}
