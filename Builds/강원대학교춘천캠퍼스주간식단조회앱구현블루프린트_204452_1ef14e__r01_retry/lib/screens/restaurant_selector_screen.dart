import 'package:flutter/material.dart';
import '../widgets/restaurant_chip_list.dart';

class RestaurantSelectorScreen extends StatefulWidget {
  const RestaurantSelectorScreen({super.key});

  @override
  State<RestaurantSelectorScreen> createState() => _RestaurantSelectorScreenState();
}

class _RestaurantSelectorScreenState extends State<RestaurantSelectorScreen> {
  final List<String> _restaurants = const ['학생식당', '교직원식당', '기숙사식당'];
  String? _selectedRestaurant;

  @override
  void initState() {
    super.initState();
    _selectedRestaurant = _restaurants.first;
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('식당 선택')),
      body: SingleChildScrollView(
        padding: const EdgeInsets.all(16),
        child: RestaurantChipList(
          restaurants: _restaurants,
          selectedRestaurant: _selectedRestaurant,
          onSelected: (value) {
            setState(() {
              _selectedRestaurant = value;
            });
          },
        ),
      ),
    );
  }
}
