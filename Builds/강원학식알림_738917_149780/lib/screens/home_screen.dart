import 'package:flutter/material.dart';
import 'package:permission_handler/permission_handler.dart';

import '../app.dart';
import '../models/cache_metadata.dart';
import '../models/favorite_item.dart';
import '../models/recent_view_item.dart';
import '../utils/app_routes.dart';
import '../utils/date_utils.dart';
import '../widgets/empty_state_view.dart';
import '../widgets/restaurant_card.dart';
import '../widgets/status_banner.dart';

class HomeScreen extends StatefulWidget {
  const HomeScreen({super.key});

  @override
  State<HomeScreen> createState() => _HomeScreenState();
}

class _HomeScreenState extends State<HomeScreen> {
  List<RecentViewItem> _recentViews = <RecentViewItem>[];
  List<FavoriteItem> _favorites = <FavoriteItem>[];
  List<CacheMetadata> _metadata = <CacheMetadata>[];
  PermissionStatus? _permissionStatus;
  String? _loadError;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    try {
      final permission =
          await AppServices.permissionService.getNotificationPermissionStatus();
      if (!mounted) {
        return;
      }
      setState(() {
        _recentViews = AppServices.recentViews.recentViews.value;
        _favorites = AppServices.menuFetchService.favoritesNotifier.value;
        _metadata = AppServices.menuFetchService.metadataNotifier.value;
        _permissionStatus = permission;
      });
    } catch (_) {
      if (!mounted) {
        return;
      }
      setState(() {
        _loadError = '저장된 정보를 불러오지 못했습니다';
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    final lastSync = _metadata.isNotEmpty ? _metadata.first.lastSuccessfulFetchAt : null;
    return Scaffold(
      appBar: AppBar(title: const Text('강원학식알림')),
      body: SafeArea(
        child: SingleChildScrollView(
          padding: const EdgeInsets.all(16),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              if (_loadError != null) StatusBanner(message: _loadError!),
              if (_permissionStatus != null && !_permissionStatus!.isGranted)
                StatusBanner(
                  message: '메뉴 조회는 계속 사용할 수 있지만 알림은 제한됩니다',
                  actionLabel: '권한 설정',
                  onAction: () async {
                    final granted = await AppServices.notificationService
                        .requestPermissionWithRationale(context);
                    if (!granted) {
                      await AppServices.permissionService.openAppNotificationSettings();
                    }
                    await _load();
                  },
                ),
              Card(
                child: ListTile(
                  title: const Text('빠른 메뉴 조회'),
                  subtitle: const Text('캠퍼스와 식당을 선택해 주간 메뉴를 확인하세요'),
                  trailing: const Icon(Icons.chevron_right),
                  onTap: () => Navigator.pushNamed(context, AppRoutes.selector),
                ),
              ),
              const SizedBox(height: 16),
              Text('최근 조회', style: Theme.of(context).textTheme.titleLarge),
              const SizedBox(height: 8),
              if (_recentViews.isEmpty)
                const EmptyStateView(
                  title: '최근 조회가 없습니다',
                  message: '식당 메뉴를 열면 최근 조회가 여기에 표시됩니다.',
                )
              else
                Column(
                  children: _recentViews
                      .map(
                        (item) => RestaurantCard(
                          campusName: item.campusName,
                          restaurantName: item.restaurantName,
                          isFavorite: _favorites.any(
                            (favorite) => favorite.key == '${item.campusName}|${item.restaurantName}',
                          ),
                          subtitle: '${item.campusName} · ${item.targetDate}',
                          onTap: () => Navigator.pushNamed(
                            context,
                            AppRoutes.weeklyMenu,
                            arguments: <String, dynamic>{
                              AppRoutes.argCampusName: item.campusName,
                              AppRoutes.argRestaurantName: item.restaurantName,
                              AppRoutes.argTargetDate: item.targetDate,
                            },
                          ),
                          onFavoriteToggle: () async {
                            await AppServices.menuFetchService.toggleFavorite(
                              item.campusName,
                              item.restaurantName,
                            );
                            await _load();
                          },
                        ),
                      )
                      .toList(),
                ),
              const SizedBox(height: 16),
              Text('즐겨찾기', style: Theme.of(context).textTheme.titleLarge),
              const SizedBox(height: 8),
              if (_favorites.isEmpty)
                const EmptyStateView(
                  title: '즐겨찾기가 없습니다',
                  message: '자주 보는 식당을 즐겨찾기에 추가해 빠르게 확인하세요.',
                )
              else
                Column(
                  children: _favorites
                      .map(
                        (item) => RestaurantCard(
                          campusName: item.campusName,
                          restaurantName: item.restaurantName,
                          isFavorite: true,
                          subtitle: item.campusName,
                          onTap: () => Navigator.pushNamed(
                            context,
                            AppRoutes.weeklyMenu,
                            arguments: <String, dynamic>{
                              AppRoutes.argCampusName: item.campusName,
                              AppRoutes.argRestaurantName: item.restaurantName,
                              AppRoutes.argTargetDate: AppDateUtils.currentTargetDate(),
                            },
                          ),
                          onFavoriteToggle: () async {
                            await AppServices.menuFetchService.toggleFavorite(
                              item.campusName,
                              item.restaurantName,
                            );
                            await _load();
                          },
                        ),
                      )
                      .toList(),
                ),
              const SizedBox(height: 16),
              Card(
                child: ListTile(
                  title: const Text('마지막 동기화 및 캐시 상태'),
                  subtitle: Text(lastSync ?? '아직 동기화 기록이 없습니다'),
                  trailing: const Icon(Icons.sync),
                  onTap: () => Navigator.pushNamed(context, AppRoutes.settings),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
