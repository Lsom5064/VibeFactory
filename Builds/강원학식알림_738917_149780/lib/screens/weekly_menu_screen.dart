import 'package:flutter/material.dart';

import '../app.dart';
import '../models/menu_item.dart';
import '../models/recent_view_item.dart';
import '../utils/app_routes.dart';
import '../utils/date_utils.dart';
import '../widgets/empty_state_view.dart';
import '../widgets/menu_card.dart';
import '../widgets/status_banner.dart';

class WeeklyMenuScreen extends StatefulWidget {
  const WeeklyMenuScreen({
    super.key,
    required this.campusName,
    required this.restaurantName,
    this.targetDate,
  });

  final String campusName;
  final String restaurantName;
  final String? targetDate;

  @override
  State<WeeklyMenuScreen> createState() => _WeeklyMenuScreenState();
}

class _WeeklyMenuScreenState extends State<WeeklyMenuScreen> {
  bool _isLoading = true;
  bool _isShowingCache = false;
  String? _errorMessage;
  String? _selectedDay;
  late String _targetDate;
  List<MenuItem> _items = <MenuItem>[];
  String? _collectedAt;
  bool _structureNeedsVerification = false;

  @override
  void initState() {
    super.initState();
    _targetDate = widget.targetDate ?? AppDateUtils.currentTargetDate();
    _load();
  }

  Future<void> _load() async {
    setState(() {
      _isLoading = true;
      _errorMessage = null;
    });

    final result = await AppServices.menuFetchService.fetchWeeklyMenu(
      campusName: widget.campusName,
      restaurantName: widget.restaurantName,
      targetDate: _targetDate,
    );

    if (result.items.isNotEmpty) {
      await AppServices.recentViews.addRecentView(
        RecentViewItem(
          campusName: widget.campusName,
          restaurantName: widget.restaurantName,
          targetDate: _targetDate,
          viewedAt: DateTime.now().toIso8601String(),
          usedCache: result.usedCache,
        ),
      );
    }

    final days = result.items.map((item) => item.dayOfWeek).toSet().toList();
    if (!mounted) {
      return;
    }
    setState(() {
      _items = result.items;
      _isLoading = false;
      _isShowingCache = result.usedCache;
      _errorMessage = result.errorMessage;
      _collectedAt = result.metadata.lastSuccessfulFetchAt;
      _structureNeedsVerification = result.metadata.structureNeedsVerification;
      _selectedDay = days.isEmpty ? null : (_selectedDay != null && days.contains(_selectedDay) ? _selectedDay : days.first);
    });
  }

  @override
  Widget build(BuildContext context) {
    final filtered = _selectedDay == null
        ? _items
        : _items.where((item) => item.dayOfWeek == _selectedDay).toList();
    final days = _items.map((item) => item.dayOfWeek).toSet().toList();
    final isFavorite = AppServices.menuFetchService.favoritesNotifier.value.any(
      (item) => item.key == '${widget.campusName}|${widget.restaurantName}',
    );

    return Scaffold(
      appBar: AppBar(
        title: const Text('주간 메뉴'),
        actions: [
          IconButton(
            onPressed: () async {
              await AppServices.menuFetchService.toggleFavorite(
                widget.campusName,
                widget.restaurantName,
              );
              if (mounted) {
                setState(() {});
              }
            },
            icon: Icon(isFavorite ? Icons.star : Icons.star_border),
          ),
          IconButton(
            onPressed: () {
              Navigator.pushNamed(
                context,
                AppRoutes.notificationSettings,
                arguments: <String, dynamic>{
                  AppRoutes.argCampusName: widget.campusName,
                  AppRoutes.argRestaurantName: widget.restaurantName,
                },
              );
            },
            icon: const Icon(Icons.notifications_outlined),
          ),
        ],
      ),
      body: SafeArea(
        child: SingleChildScrollView(
          padding: const EdgeInsets.all(16),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Card(
                child: ListTile(
                  title: Text(widget.restaurantName),
                  subtitle: Text('${widget.campusName} · 기준일 $_targetDate'),
                  trailing: _isShowingCache
                      ? const Chip(label: Text('캐시'))
                      : const Chip(label: Text('실시간')),
                ),
              ),
              const SizedBox(height: 12),
              Row(
                children: [
                  OutlinedButton.icon(
                    onPressed: () {
                      _targetDate = AppDateUtils.moveWeek(_targetDate, -1);
                      _load();
                    },
                    icon: const Icon(Icons.chevron_left),
                    label: const Text('이전 주'),
                  ),
                  const SizedBox(width: 8),
                  OutlinedButton.icon(
                    onPressed: () {
                      _targetDate = AppDateUtils.moveWeek(_targetDate, 1);
                      _load();
                    },
                    icon: const Icon(Icons.chevron_right),
                    label: const Text('다음 주'),
                  ),
                  const Spacer(),
                  IconButton(
                    onPressed: _load,
                    icon: const Icon(Icons.refresh),
                  ),
                ],
              ),
              if (_errorMessage != null) ...[
                const SizedBox(height: 8),
                StatusBanner(
                  message: _errorMessage!,
                  actionLabel: '다시 시도',
                  onAction: _load,
                ),
              ],
              if (_structureNeedsVerification) ...[
                const SizedBox(height: 8),
                const StatusBanner(
                  message: '공식 페이지 구조가 변경되었거나 현재 조합은 런타임 검증이 필요합니다',
                ),
              ],
              if (_collectedAt != null) ...[
                const SizedBox(height: 8),
                Text('원본 출처: https://kangwon.ac.kr/ko/extn/337/wkmenu-mngr/list.do'),
                Text('수집 시각: $_collectedAt'),
              ],
              const SizedBox(height: 12),
              if (days.isNotEmpty)
                Wrap(
                  spacing: 8,
                  runSpacing: 8,
                  children: days
                      .map(
                        (day) => ChoiceChip(
                          label: Text(day),
                          selected: _selectedDay == day,
                          onSelected: (_) {
                            setState(() {
                              _selectedDay = day;
                            });
                          },
                        ),
                      )
                      .toList(),
                ),
              const SizedBox(height: 12),
              if (_isLoading)
                const Center(child: Padding(
                  padding: EdgeInsets.all(24),
                  child: CircularProgressIndicator(),
                ))
              else if (filtered.isEmpty)
                EmptyStateView(
                  title: '표시할 식단이 없습니다',
                  message: _errorMessage ?? '선택한 캠퍼스·식당·주차에 표시할 식단이 없습니다',
                  actionLabel: '새로고침',
                  onAction: _load,
                )
              else
                Column(
                  children: filtered.map((item) => MenuCard(item: item)).toList(),
                ),
            ],
          ),
        ),
      ),
    );
  }
}
