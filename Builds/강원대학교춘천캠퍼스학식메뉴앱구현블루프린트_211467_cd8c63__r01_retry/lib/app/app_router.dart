import 'package:flutter/material.dart';

import '../state/app_state_controller.dart';

class AppRouter {
  static Route<dynamic> onGenerateRoute(
    RouteSettings settings,
    AppStateController controller,
  ) {
    return MaterialPageRoute<void>(
      settings: settings,
      builder: (_) => _AppShell(controller: controller),
    );
  }
}

class _AppShell extends StatelessWidget {
  const _AppShell({required this.controller});

  final AppStateController controller;

  @override
  Widget build(BuildContext context) {
    final mealData = controller.mealData;

    return Scaffold(
      appBar: AppBar(
        title: const Text('강원대 학식 메뉴'),
        actions: [
          IconButton(
            onPressed: controller.isLoading ? null : controller.refresh,
            icon: const Icon(Icons.refresh),
            tooltip: '새로고침',
          ),
        ],
      ),
      body: SafeArea(
        child: SingleChildScrollView(
          padding: const EdgeInsets.all(16),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                controller.isInitialized ? '앱이 초기화되었습니다.' : '앱을 초기화하는 중입니다.',
              ),
              const SizedBox(height: 12),
              Text(
                '공식 출처: ${AppStateController.sourceUrl}',
                style: Theme.of(context).textTheme.bodySmall,
              ),
              if (mealData != null) ...[
                const SizedBox(height: 8),
                Text('페이지 제목: ${mealData.pageTitle}'),
                if (mealData.restaurantName.isNotEmpty) Text('식당: ${mealData.restaurantName}'),
                if (mealData.weekRange.isNotEmpty) Text('주간 범위: ${mealData.weekRange}'),
                Text('마지막 갱신: ${mealData.fetchedAt.toLocal()}'),
              ],
              if (controller.isLoading) ...[
                const SizedBox(height: 16),
                const Center(child: CircularProgressIndicator()),
              ],
              if (controller.errorMessage != null) ...[
                const SizedBox(height: 16),
                Card(
                  color: Theme.of(context).colorScheme.errorContainer,
                  child: Padding(
                    padding: const EdgeInsets.all(12),
                    child: Text(controller.errorMessage!),
                  ),
                ),
              ],
              const SizedBox(height: 16),
              if (mealData == null || mealData.sections.isEmpty)
                const Card(
                  child: Padding(
                    padding: EdgeInsets.all(16),
                    child: Text('표시할 식단 데이터가 없습니다.'),
                  ),
                )
              else
                ...mealData.sections.map(
                  (section) => Card(
                    margin: const EdgeInsets.only(bottom: 12),
                    child: Padding(
                      padding: const EdgeInsets.all(12),
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Text(
                            section.title,
                            style: Theme.of(context).textTheme.titleMedium,
                          ),
                          const SizedBox(height: 4),
                          Text('구분: ${section.mealTime}'),
                          const SizedBox(height: 8),
                          ...section.days.map(
                            (day) => Padding(
                              padding: const EdgeInsets.only(bottom: 8),
                              child: Column(
                                crossAxisAlignment: CrossAxisAlignment.start,
                                children: [
                                  Text(
                                    day.label,
                                    style: Theme.of(context).textTheme.labelLarge,
                                  ),
                                  const SizedBox(height: 2),
                                  Text(day.menu),
                                ],
                              ),
                            ),
                          ),
                        ],
                      ),
                    ),
                  ),
                ),
            ],
          ),
        ),
      ),
    );
  }
}
