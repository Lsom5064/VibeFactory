import 'package:flutter/material.dart';

import '../app.dart';
import '../models/favorite_item.dart';
import '../models/notification_setting.dart';
import '../utils/app_routes.dart';
import '../utils/date_utils.dart';
import '../widgets/empty_state_view.dart';
import '../widgets/restaurant_card.dart';

class ManageScreen extends StatefulWidget {
  const ManageScreen({super.key});

  @override
  State<ManageScreen> createState() => _ManageScreenState();
}

class _ManageScreenState extends State<ManageScreen> {
  int _tabIndex = 0;

  @override
  Widget build(BuildContext context) {
    final favorites = AppServices.menuFetchService.favoritesNotifier.value;
    final notifications = AppServices.menuFetchService.notificationsNotifier.value;

    return Scaffold(
      appBar: AppBar(title: const Text('관리')),
      body: SafeArea(
        child: SingleChildScrollView(
          padding: const EdgeInsets.all(16),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              SegmentedButton<int>(
                segments: const [
                  ButtonSegment<int>(value: 0, label: Text('즐겨찾기')),
                  ButtonSegment<int>(value: 1, label: Text('알림 대상')),
                ],
                selected: <int>{_tabIndex},
                onSelectionChanged: (value) {
                  setState(() {
                    _tabIndex = value.first;
                  });
                },
              ),
              const SizedBox(height: 16),
              if (_tabIndex == 0)
                _buildFavorites(context, favorites)
              else
                _buildNotifications(context, notifications),
              const SizedBox(height: 16),
              FilledButton.icon(
                onPressed: () => Navigator.pushNamed(context, AppRoutes.selector),
                icon: const Icon(Icons.add),
                label: const Text('식당 추가'),
              ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _buildFavorites(BuildContext context, List<FavoriteItem> favorites) {
    if (favorites.isEmpty) {
      return const EmptyStateView(
        title: '즐겨찾기가 없습니다',
        message: '식당을 추가해 빠르게 접근해 보세요.',
      );
    }

    return ReorderableListView(
      shrinkWrap: true,
      physics: const NeverScrollableScrollPhysics(),
      onReorder: (oldIndex, newIndex) async {
        await AppServices.menuFetchService.reorderFavorites(oldIndex, newIndex);
        if (mounted) {
          setState(() {});
        }
      },
      children: [
        for (final item in favorites)
          Card(
            key: ValueKey(item.key),
            child: ListTile(
              title: Text(item.restaurantName),
              subtitle: Text(item.campusName),
              leading: const Icon(Icons.drag_handle),
              trailing: IconButton(
                onPressed: () async {
                  await AppServices.menuFetchService.toggleFavorite(
                    item.campusName,
                    item.restaurantName,
                  );
                  if (mounted) {
                    setState(() {});
                  }
                },
                icon: const Icon(Icons.delete_outline),
              ),
              onTap: () {
                Navigator.pushNamed(
                  context,
                  AppRoutes.weeklyMenu,
                  arguments: <String, dynamic>{
                    AppRoutes.argCampusName: item.campusName,
                    AppRoutes.argRestaurantName: item.restaurantName,
                    AppRoutes.argTargetDate: AppDateUtils.currentTargetDate(),
                  },
                );
              },
            ),
          ),
      ],
    );
  }

  Widget _buildNotifications(
    BuildContext context,
    List<RestaurantNotificationSetting> notifications,
  ) {
    if (notifications.isEmpty) {
      return const EmptyStateView(
        title: '알림 대상이 없습니다',
        message: '식당별로 원하는 시간의 알림을 설정해 보세요.',
      );
    }

    return Column(
      children: notifications
          .map(
            (item) => RestaurantCard(
              campusName: item.campusName,
              restaurantName: item.restaurantName,
              isFavorite: AppServices.menuFetchService.favoritesNotifier.value
                  .any((favorite) => favorite.key == item.key),
              subtitle:
                  '${item.notificationTime} · ${item.repeatDays.join(', ')} · ${item.isEnabled ? '활성' : '비활성'}',
              onTap: () {
                Navigator.pushNamed(
                  context,
                  AppRoutes.notificationSettings,
                  arguments: <String, dynamic>{
                    AppRoutes.argCampusName: item.campusName,
                    AppRoutes.argRestaurantName: item.restaurantName,
                  },
                );
              },
              onFavoriteToggle: () async {
                final updated = item.copyWith(isEnabled: !item.isEnabled);
                await AppServices.menuFetchService.saveNotificationSetting(updated);
                try {
                  if (updated.isEnabled) {
                    await AppServices.notificationService.scheduleRestaurantNotification(
                      updated,
                      AppDateUtils.currentTargetDate(),
                    );
                  } else {
                    await AppServices.notificationService.cancelRestaurantNotification(
                      updated.campusName,
                      updated.restaurantName,
                    );
                  }
                } catch (_) {}
                if (mounted) {
                  setState(() {});
                }
              },
            ),
          )
          .toList(),
    );
  }
}
