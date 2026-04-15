import 'package:flutter/material.dart';

import '../models/menu_models.dart';
import '../utils/date_utils.dart';

class WeeklySummaryCard extends StatelessWidget {
  const WeeklySummaryCard({
    super.key,
    required this.menu,
    required this.usedCache,
    this.warningMessage,
  });

  final WeeklyMenu menu;
  final bool usedCache;
  final String? warningMessage;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text('이번 주 범위', style: theme.textTheme.titleMedium),
            const SizedBox(height: 8),
            Text('${formatDate(menu.startDate)} ~ ${formatDate(menu.endDate)}'),
            const SizedBox(height: 8),
            Text('갱신 시각: ${formatDateTime(menu.updatedAt)}'),
            const SizedBox(height: 8),
            const Text('강원대학교 삼척캠퍼스 공식 페이지 기반 정보입니다.'),
            if (usedCache) ...[
              const SizedBox(height: 8),
              Text('최근 저장된 데이터를 표시 중입니다.', style: theme.textTheme.bodyMedium?.copyWith(color: theme.colorScheme.primary)),
            ],
            if (warningMessage != null && warningMessage!.isNotEmpty) ...[
              const SizedBox(height: 8),
              Text(warningMessage!, style: theme.textTheme.bodyMedium?.copyWith(color: theme.colorScheme.error)),
            ],
          ],
        ),
      ),
    );
  }
}

class DailyMenuCard extends StatelessWidget {
  const DailyMenuCard({super.key, required this.dailyMenu});

  final DailyMenu dailyMenu;

  @override
  Widget build(BuildContext context) {
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text('${formatDate(dailyMenu.date)} (${dailyMenu.weekday})', style: Theme.of(context).textTheme.titleMedium),
            const SizedBox(height: 12),
            if (dailyMenu.cafeterias.isEmpty)
              const Text('제공 정보가 없습니다.')
            else
              ...dailyMenu.cafeterias.map((cafeteria) => Padding(
                    padding: const EdgeInsets.only(bottom: 12),
                    child: CafeteriaSection(cafeteria: cafeteria),
                  )),
          ],
        ),
      ),
    );
  }
}

class CafeteriaSection extends StatelessWidget {
  const CafeteriaSection({super.key, required this.cafeteria});

  final CafeteriaMenu cafeteria;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(
        color: theme.colorScheme.surfaceContainerHighest.withOpacity(0.35),
        borderRadius: BorderRadius.circular(12),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(cafeteria.name, style: theme.textTheme.titleSmall),
          if (cafeteria.category != null && cafeteria.category!.isNotEmpty) ...[
            const SizedBox(height: 4),
            Text(cafeteria.category!),
          ],
          const SizedBox(height: 8),
          if (cafeteria.sections.isEmpty)
            const Text('제공 정보가 없습니다.')
          else
            ...cafeteria.sections.map((section) => Padding(
                  padding: const EdgeInsets.only(bottom: 8),
                  child: MealSectionWidget(section: section),
                )),
        ],
      ),
    );
  }
}

class MealSectionWidget extends StatelessWidget {
  const MealSectionWidget({super.key, required this.section});

  final MealSection section;

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(section.title, style: Theme.of(context).textTheme.labelLarge),
        const SizedBox(height: 4),
        if (section.items.isEmpty)
          const Text('제공 정보가 없습니다.')
        else
          ...section.items.map((item) => Padding(
                padding: const EdgeInsets.only(bottom: 2),
                child: Text('• $item'),
              )),
      ],
    );
  }
}
