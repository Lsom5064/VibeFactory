import 'package:flutter/material.dart';

import '../data/restaurant_catalog.dart';
import '../models/menu_data.dart';
import '../utils/date_helper.dart';

class MenuSummaryCard extends StatelessWidget {
  final String? campusId;
  final String? restaurantId;
  final MenuData? menuData;
  final bool isInitialSetup;

  const MenuSummaryCard({
    super.key,
    required this.campusId,
    required this.restaurantId,
    required this.menuData,
    required this.isInitialSetup,
  });

  @override
  Widget build(BuildContext context) {
    final restaurant = RestaurantCatalog.findRestaurant(campusId, restaurantId);
    final isToday = DateHelper.isToday(menuData?.baseDate);
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text('오늘의 메뉴', style: Theme.of(context).textTheme.titleLarge),
            const SizedBox(height: 8),
            Text('캠퍼스: ${RestaurantCatalog.campusName(campusId)}'),
            Text('식당: ${restaurant?.name ?? '미선택'}'),
            Text('기준 날짜: ${DateHelper.formatDisplayDate(menuData?.baseDate)}'),
            Text('상태: ${isToday ? '오늘 메뉴' : '최근 저장 메뉴 또는 최신 확인 필요'}'),
            const SizedBox(height: 12),
            if (isInitialSetup)
              const Text('먼저 캠퍼스와 식당을 선택해 주세요.')
            else if (menuData == null)
              const Text('표시할 메뉴가 없습니다. 새로고침으로 다시 시도해 주세요.')
            else
              ...menuData!.items.map(
                (item) => Padding(
                  padding: const EdgeInsets.only(bottom: 6),
                  child: Row(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      const Text('• '),
                      Expanded(child: Text(item)),
                    ],
                  ),
                ),
              ),
          ],
        ),
      ),
    );
  }
}
