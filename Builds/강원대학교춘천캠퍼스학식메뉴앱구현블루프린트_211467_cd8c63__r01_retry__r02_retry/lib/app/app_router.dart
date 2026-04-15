import 'package:flutter/material.dart';

import '../state/app_state_controller.dart';

class AppRouter {
  static Route<dynamic> onGenerateRoute(
    RouteSettings settings,
    AppStateController controller,
  ) {
    return MaterialPageRoute<void>(
      builder: (_) => _HomePage(controller: controller),
      settings: settings,
    );
  }
}

class _HomePage extends StatelessWidget {
  const _HomePage({required this.controller});

  final AppStateController controller;

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('강원대 학식 메뉴'),
      ),
      body: SafeArea(
        child: Padding(
          padding: const EdgeInsets.all(16),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Card(
                child: Padding(
                  padding: const EdgeInsets.all(16),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      const Text(
                        '오늘 메뉴',
                        style: TextStyle(
                          fontSize: 18,
                          fontWeight: FontWeight.bold,
                        ),
                      ),
                      const SizedBox(height: 12),
                      if (controller.isLoading)
                        const Center(child: CircularProgressIndicator())
                      else if (controller.errorMessage != null)
                        Text(controller.errorMessage!)
                      else if (controller.todayMeals.isEmpty)
                        const Text('표시할 메뉴가 없습니다.')
                      else
                        ...controller.todayMeals.map(
                          (meal) => Padding(
                            padding: const EdgeInsets.only(bottom: 8),
                            child: Text(meal),
                          ),
                        ),
                    ],
                  ),
                ),
              ),
              if (controller.availableRestaurants.length > 1) ...[
                const SizedBox(height: 16),
                DropdownButtonFormField<String>(
                  key: UniqueKey(),
                  initialValue: controller.selectedRestaurantName,
                  decoration: const InputDecoration(
                    labelText: '식당 선택',
                    border: OutlineInputBorder(),
                  ),
                  items: controller.availableRestaurants
                      .map(
                        (restaurant) => DropdownMenuItem<String>(
                          value: restaurant,
                          child: Text(restaurant),
                        ),
                      )
                      .toList(),
                  onChanged: (value) {
                    controller.selectRestaurant(value);
                  },
                ),
              ],
              const SizedBox(height: 16),
              FilledButton.icon(
                key: UniqueKey(),
                onPressed: controller.refresh,
                icon: const Icon(Icons.refresh),
                label: const Text('새로고침'),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
