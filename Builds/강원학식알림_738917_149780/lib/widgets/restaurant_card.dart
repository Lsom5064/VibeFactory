import 'package:flutter/material.dart';

class RestaurantCard extends StatelessWidget {
  const RestaurantCard({
    super.key,
    required this.campusName,
    required this.restaurantName,
    required this.isFavorite,
    this.subtitle,
    this.onTap,
    this.onFavoriteToggle,
  });

  final String campusName;
  final String restaurantName;
  final bool isFavorite;
  final String? subtitle;
  final VoidCallback? onTap;
  final VoidCallback? onFavoriteToggle;

  @override
  Widget build(BuildContext context) {
    return Card(
      child: ListTile(
        onTap: onTap,
        title: Text(restaurantName),
        subtitle: Text(subtitle ?? campusName),
        trailing: IconButton(
          onPressed: onFavoriteToggle,
          icon: Icon(isFavorite ? Icons.star : Icons.star_border),
        ),
      ),
    );
  }
}
