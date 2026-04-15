import 'package:flutter/material.dart';

import '../state/app_state_controller.dart';

class AppRouter {
  static Route<dynamic> onGenerateRoute(
    RouteSettings settings,
    AppStateController controller,
  ) {
    return MaterialPageRoute<void>(
      builder: (_) => HomeScreen(controller: controller),
      settings: settings,
    );
  }
}

class HomeScreen extends StatelessWidget {
  const HomeScreen({super.key, required this.controller});

  final AppStateController controller;

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('강원대 학식 메뉴'),
      ),
      body: SafeArea(
        child: SingleChildScrollView(
          child: Padding(
            padding: const EdgeInsets.all(16),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: <Widget>[
                Card(
                  child: Padding(
                    padding: const EdgeInsets.all(16),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: <Widget>[
                        const Text(
                          '오늘 메뉴',
                          style: TextStyle(
                            fontSize: 20,
                            fontWeight: FontWeight.bold,
                          ),
                        ),
                        const SizedBox(height: 8),
                        const Text('공식 웹페이지에서 오늘 식단을 실시간으로 불러옵니다.'),
                        if (controller.sourceLabel != null) ...<Widget>[
                          const SizedBox(height: 8),
                          SelectableText(
                            controller.sourceLabel!,
                            style: Theme.of(context).textTheme.bodySmall,
                          ),
                        ],
                      ],
                    ),
                  ),
                ),
                const SizedBox(height: 16),
                if (controller.restaurants.isNotEmpty)
                  DropdownButtonFormField<String>(
                    key: ValueKey<String?>(controller.selectedRestaurant),
                    initialValue: controller.selectedRestaurant,
                    decoration: const InputDecoration(
                      labelText: '식당 선택',
                      border: OutlineInputBorder(),
                    ),
                    items: controller.restaurants
                        .map(
                          (restaurant) => DropdownMenuItem<String>(
                            value: restaurant,
                            child: Text(restaurant),
                          ),
                        )
                        .toList(),
                    onChanged: controller.selectRestaurant,
                  ),
                const SizedBox(height: 16),
                FilledButton(
                  key: const ValueKey<String>('refresh_button'),
                  onPressed: controller.refresh,
                  child: const Text('새로고침'),
                ),
                const SizedBox(height: 24),
                if (controller.isLoading)
                  const Center(
                    child: Padding(
                      padding: EdgeInsets.all(24),
                      child: Column(
                        children: <Widget>[
                          CircularProgressIndicator(),
                          SizedBox(height: 12),
                          Text('메뉴 정보를 불러오는 중입니다.'),
                        ],
                      ),
                    ),
                  )
                else if (controller.visibleMeals.isNotEmpty) ...controller.visibleMeals
                    .map(
                      (meal) => Card(
                        margin: const EdgeInsets.only(bottom: 12),
                        child: Padding(
                          padding: const EdgeInsets.all(16),
                          child: Column(
                            crossAxisAlignment: CrossAxisAlignment.start,
                            children: <Widget>[
                              Text(
                                '${meal.sectionTitle} · ${meal.mealTime}',
                                style: const TextStyle(
                                  fontSize: 16,
                                  fontWeight: FontWeight.bold,
                                ),
                              ),
                              const SizedBox(height: 8),
                              Text(meal.dateLabel),
                              const SizedBox(height: 8),
                              Text(meal.menu),
                            ],
                          ),
                        ),
                      ),
                    )
                else if (controller.errorMessage != null)
                  Text(
                    controller.errorMessage!,
                    style: TextStyle(color: Theme.of(context).colorScheme.error),
                  )
                else
                  const Text('표시할 메뉴가 없습니다. 공식 페이지 파싱에 실패했거나 오늘 데이터가 비어 있습니다.'),
              ],
            ),
          ),
        ),
      ),
    );
  }
}
