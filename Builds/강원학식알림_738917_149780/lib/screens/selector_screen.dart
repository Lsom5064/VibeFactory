import 'package:flutter/material.dart';

import '../app.dart';
import '../utils/app_routes.dart';
import '../utils/date_utils.dart';
import '../widgets/empty_state_view.dart';
import '../widgets/restaurant_card.dart';
import '../widgets/status_banner.dart';

class SelectorScreen extends StatefulWidget {
  const SelectorScreen({super.key});

  @override
  State<SelectorScreen> createState() => _SelectorScreenState();
}

class _SelectorScreenState extends State<SelectorScreen> {
  String _selectedCampus = '삼척';
  String _query = '';

  @override
  Widget build(BuildContext context) {
    final verified = AppServices.menuFetchService.verifiedRestaurantsNotifier.value;
    final campuses = verified.keys.isEmpty
        ? <String>['삼척', '춘천', '도계']
        : verified.keys.toList()..sort();
    if (!campuses.contains(_selectedCampus)) {
      _selectedCampus = campuses.first;
    }
    final restaurants = List<String>.from(verified[_selectedCampus] ?? <String>[])
      ..sort();
    final filtered = restaurants
        .where((item) => item.toLowerCase().contains(_query.toLowerCase()))
        .toList();
    final favorites = AppServices.menuFetchService.favoritesNotifier.value;

    return Scaffold(
      appBar: AppBar(title: const Text('캠퍼스·식당 선택')),
      body: SafeArea(
        child: SingleChildScrollView(
          padding: const EdgeInsets.all(16),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              if (restaurants.isEmpty)
                const StatusBanner(
                  message: '공식 페이지 구조 변경 또는 런타임 검증 필요 상태입니다',
                ),
              TextField(
                decoration: const InputDecoration(
                  prefixIcon: Icon(Icons.search),
                  hintText: '식당 검색',
                ),
                onChanged: (value) {
                  setState(() {
                    _query = value;
                  });
                },
              ),
              const SizedBox(height: 16),
              Wrap(
                spacing: 8,
                runSpacing: 8,
                children: campuses
                    .map(
                      (campus) => ChoiceChip(
                        label: Text(campus),
                        selected: _selectedCampus == campus,
                        onSelected: (_) {
                          setState(() {
                            _selectedCampus = campus;
                          });
                        },
                      ),
                    )
                    .toList(),
              ),
              const SizedBox(height: 16),
              Text('선택된 캠퍼스: $_selectedCampus'),
              const SizedBox(height: 12),
              if (filtered.isEmpty)
                const EmptyStateView(
                  title: '표시할 식당이 없습니다',
                  message: '최근 검증 결과가 없거나 검색 결과가 없습니다.',
                )
              else
                Column(
                  children: filtered
                      .map(
                        (restaurant) => RestaurantCard(
                          campusName: _selectedCampus,
                          restaurantName: restaurant,
                          isFavorite: favorites.any(
                            (item) => item.key == '$_selectedCampus|$restaurant',
                          ),
                          subtitle: '주간 메뉴 보기',
                          onTap: () {
                            Navigator.pushNamed(
                              context,
                              AppRoutes.weeklyMenu,
                              arguments: <String, dynamic>{
                                AppRoutes.argCampusName: _selectedCampus,
                                AppRoutes.argRestaurantName: restaurant,
                                AppRoutes.argTargetDate:
                                    AppDateUtils.currentTargetDate(),
                              },
                            );
                          },
                          onFavoriteToggle: () async {
                            await AppServices.menuFetchService.toggleFavorite(
                              _selectedCampus,
                              restaurant,
                            );
                            if (mounted) {
                              setState(() {});
                            }
                          },
                        ),
                      )
                      .toList(),
                ),
            ],
          ),
        ),
      ),
    );
  }
}
