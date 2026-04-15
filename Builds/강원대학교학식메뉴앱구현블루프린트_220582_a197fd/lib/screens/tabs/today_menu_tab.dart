import 'package:flutter/material.dart';

import '../../state/app_controller.dart';
import '../../widgets/empty_state_view.dart';
import '../../widgets/menu_card.dart';

class TodayMenuTab extends StatelessWidget {
  final AppController controller;

  const TodayMenuTab({super.key, required this.controller});

  @override
  Widget build(BuildContext context) {
    if (controller.isInitialLoading && controller.dailyMenus.isEmpty) {
      return const Padding(
        padding: EdgeInsets.all(32),
        child: Center(child: CircularProgressIndicator()),
      );
    }

    if (controller.syncStatus.noData && controller.dailyMenus.isEmpty) {
      return EmptyStateView(
        title: '메뉴를 불러오지 못했습니다',
        description: '공식 웹페이지에서 메뉴를 가져오지 못했고 현재 저장된 메뉴도 없습니다.',
        buttonText: '다시 시도',
        onPressed: () => controller.refreshMenus(),
      );
    }

    final todayMenus = controller.todayMenus;
    if (todayMenus.isEmpty) {
      return Padding(
        padding: const EdgeInsets.all(16),
        child: Card(
          child: Padding(
            padding: const EdgeInsets.all(20),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text('오늘 메뉴가 없습니다', style: Theme.of(context).textTheme.titleMedium),
                const SizedBox(height: 8),
                const Text('오늘 날짜에 해당하는 메뉴가 아직 없거나 제공되지 않았습니다.'),
                const SizedBox(height: 12),
                ElevatedButton(
                  key: UniqueKey(),
                  onPressed: () => controller.selectTab(1),
                  child: const Text('이번 주 메뉴 보기'),
                ),
              ],
            ),
          ),
        ),
      );
    }

    return RefreshIndicator(
      onRefresh: () => controller.refreshMenus(),
      child: ListView.builder(
        shrinkWrap: true,
        physics: const AlwaysScrollableScrollPhysics(),
        itemCount: todayMenus.length,
        itemBuilder: (context, index) {
          return MenuCard(
            menu: todayMenus[index],
            restaurants: controller.restaurants,
          );
        },
      ),
    );
  }
}
