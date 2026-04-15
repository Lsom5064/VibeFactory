import 'package:flutter/material.dart';

import '../state/app_state_controller.dart';

class AppRouter extends StatelessWidget {
  const AppRouter({super.key, required this.controller});

  final AppStateController controller;

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('오늘의 학식'),
      ),
      body: SingleChildScrollView(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: <Widget>[
            DropdownButtonFormField<String>(
              key: UniqueKey(),
              initialValue: controller.restaurants.contains(controller.selectedRestaurant)
                  ? controller.selectedRestaurant
                  : null,
              decoration: const InputDecoration(
                labelText: '식당 선택',
                border: OutlineInputBorder(),
              ),
              items: controller.restaurants
                  .map(
                    (String restaurant) => DropdownMenuItem<String>(
                      value: restaurant,
                      child: Text(restaurant),
                    ),
                  )
                  .toList(),
              onChanged: controller.restaurants.isEmpty ? null : controller.selectRestaurant,
            ),
            const SizedBox(height: 12),
            FilledButton.icon(
              key: UniqueKey(),
              onPressed: controller.isLoading
                  ? null
                  : () async {
                      try {
                        await controller.refresh();
                      } catch (_) {
                        if (!context.mounted) {
                          return;
                        }
                        ScaffoldMessenger.of(context).showSnackBar(
                          SnackBar(
                            content: Text(controller.errorMessage ?? '메뉴를 새로고침하지 못했습니다.'),
                          ),
                        );
                      }
                    },
              icon: const Icon(Icons.refresh),
              label: Text(controller.isLoading ? '불러오는 중...' : '새로고침'),
            ),
            const SizedBox(height: 16),
            if (controller.errorMessage != null)
              Card(
                child: Padding(
                  padding: const EdgeInsets.all(16),
                  child: Text(controller.errorMessage!),
                ),
              ),
            if (controller.visibleMeals.isEmpty && !controller.isLoading)
              const Card(
                child: Padding(
                  padding: EdgeInsets.all(16),
                  child: Text('표시할 메뉴가 없습니다.'),
                ),
              ),
            ...controller.visibleMeals.map(
              (MealItem meal) {
                final titleParts = <String>[
                  meal.effectiveRestaurantName,
                  if ((meal.cornerTitle ?? '').trim().isNotEmpty) meal.cornerTitle!.trim(),
                  meal.mealTime,
                ];
                return Card(
                  margin: const EdgeInsets.only(bottom: 12),
                  child: Padding(
                    padding: const EdgeInsets.all(16),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: <Widget>[
                        Text(
                          titleParts.join(' · '),
                          style: Theme.of(context).textTheme.titleMedium,
                        ),
                        const SizedBox(height: 8),
                        ...meal.menuItems.map(
                          (String item) => Padding(
                            padding: const EdgeInsets.only(bottom: 4),
                            child: Text(item),
                          ),
                        ),
                      ],
                    ),
                  ),
                );
              },
            ),
          ],
        ),
      ),
    );
  }
}
