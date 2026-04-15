import 'package:flutter/material.dart';

import '../../state/app_controller.dart';
import '../../widgets/date_section.dart';
import '../../widgets/empty_state_view.dart';

class WeeklyMenuTab extends StatelessWidget {
  final AppController controller;

  const WeeklyMenuTab({super.key, required this.controller});

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
        title: '저장된 메뉴가 없습니다',
        description: '공식 웹페이지에서 메뉴를 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.',
        buttonText: '다시 시도',
        onPressed: () => controller.refreshMenus(),
      );
    }

    final grouped = controller.weeklyMenusByDate;
    return RefreshIndicator(
      onRefresh: () => controller.refreshMenus(),
      child: ListView(
        shrinkWrap: true,
        physics: const AlwaysScrollableScrollPhysics(),
        children: grouped.entries
            .map(
              (entry) => DateSection(
                date: entry.key,
                menus: entry.value,
                restaurants: controller.restaurants,
              ),
            )
            .toList(),
      ),
    );
  }
}
