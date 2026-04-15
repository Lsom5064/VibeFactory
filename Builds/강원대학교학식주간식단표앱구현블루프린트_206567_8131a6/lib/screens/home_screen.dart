import 'package:flutter/material.dart';

import '../core/constants/restaurant_constants.dart';
import '../core/utils/date_utils.dart';
import '../models/app_settings.dart';
import '../models/restaurant.dart';
import '../models/weekly_menu.dart';
import '../services/menu_api_service.dart';
import '../services/menu_repository.dart';
import '../widgets/action_bar.dart';
import '../widgets/header_section.dart';
import '../widgets/info_footer.dart';
import '../widgets/status_section.dart';
import '../widgets/weekly_menu_table.dart';

class HomeScreen extends StatefulWidget {
  const HomeScreen({super.key});

  @override
  State<HomeScreen> createState() => _HomeScreenState();
}

class _HomeScreenState extends State<HomeScreen> {
  final MenuRepository _repository = MenuRepository();

  Restaurant? _selectedRestaurant;
  String? _favoriteRestaurantId;
  DateTime _selectedDate = AppDateUtils.dateOnly(DateTime.now());
  DateTime _currentWeekStart = AppDateUtils.startOfWeek(DateTime.now());
  DateTime _currentWeekEnd = AppDateUtils.endOfWeek(DateTime.now());
  int _currentRequestId = 0;
  bool _isLoading = true;
  bool _isRefreshing = false;
  HomeErrorType _errorType = HomeErrorType.none;
  String? _errorMessage;
  bool _isEmpty = false;
  WeeklyMenu? _weeklyMenu;
  List<String> _notices = const [];
  String _sourceUrl = '';
  DateTime? _lastSuccessfulSync;

  @override
  void initState() {
    super.initState();
    _initialize();
  }

  Future<void> _initialize() async {
    try {
      final restaurants = RestaurantConstants.restaurants;
      if (restaurants.isEmpty) {
        setState(() {
          _isLoading = false;
          _errorType = HomeErrorType.configuration;
          _errorMessage = '식당 목록이 비어 있습니다.';
        });
        return;
      }

      final AppSettings settings = await _repository.storage.loadSettings();
      _favoriteRestaurantId = settings.favoriteRestaurantId;
      _lastSuccessfulSync = settings.lastSuccessfulSync;
      _selectedDate = AppDateUtils.dateOnly(settings.lastSelectedDate ?? DateTime.now());
      _currentWeekStart = AppDateUtils.startOfWeek(_selectedDate);
      _currentWeekEnd = AppDateUtils.endOfWeek(_selectedDate);

      final preferredId = settings.favoriteRestaurantId ?? settings.lastSelectedRestaurantId;
      _selectedRestaurant = restaurants.firstWhere(
        (restaurant) => restaurant.id == preferredId,
        orElse: () => restaurants.first,
      );

      await _showCachedThenRefresh(forceNetwork: false);
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _isLoading = false;
        _errorType = HomeErrorType.configuration;
        _errorMessage = '초기화 중 문제가 발생했습니다: $e';
      });
      rethrow;
    }
  }

  Future<void> _showCachedThenRefresh({required bool forceNetwork}) async {
    final restaurant = _selectedRestaurant;
    if (restaurant == null) {
      setState(() {
        _isLoading = false;
        _errorType = HomeErrorType.configuration;
        _errorMessage = '선택된 식당이 없습니다.';
      });
      return;
    }

    final cacheKey = _repository.buildCacheKey(
      restaurantId: restaurant.id,
      weekStart: _currentWeekStart,
    );

    try {
      final memory = _repository.memoryCache[cacheKey];
      if (memory != null) {
        setState(() {
          _applyWeeklyMenu(memory);
          _isLoading = false;
          _isRefreshing = true;
          _errorType = HomeErrorType.none;
          _errorMessage = null;
        });
      } else {
        final local = await _repository.loadLocalCache(
          restaurantId: restaurant.id,
          weekStart: _currentWeekStart,
        );
        if (local != null && mounted) {
          setState(() {
            _repository.memoryCache[cacheKey] = local;
            _applyWeeklyMenu(local);
            _isLoading = false;
            _isRefreshing = true;
            _errorType = HomeErrorType.none;
            _errorMessage = null;
          });
        }
      }
    } catch (e) {
      debugPrint('캐시 복원 실패: $e');
      rethrow;
    }

    await _fetchWeeklyMenu(forceNetwork: forceNetwork);
  }

  Future<void> _fetchWeeklyMenu({required bool forceNetwork}) async {
    if (_isRefreshing && forceNetwork) {
      return;
    }

    final restaurant = _selectedRestaurant;
    if (restaurant == null) {
      return;
    }

    final requestId = ++_currentRequestId;
    if (mounted) {
      setState(() {
        _isLoading = _weeklyMenu == null;
        _isRefreshing = _weeklyMenu != null;
        _errorType = HomeErrorType.none;
        _errorMessage = null;
      });
    }

    try {
      final menu = await _repository.fetchAndCache(
        restaurantId: restaurant.id,
        weekStart: _currentWeekStart,
        weekEnd: _currentWeekEnd,
      );
      if (!mounted || requestId != _currentRequestId) {
        return;
      }
      setState(() {
        _applyWeeklyMenu(menu);
        _lastSuccessfulSync = menu.fetchedAt;
        _isLoading = false;
        _isRefreshing = false;
        _errorType = HomeErrorType.none;
        _errorMessage = null;
      });
    } on MenuApiException catch (e) {
      if (!mounted || requestId != _currentRequestId) {
        return;
      }
      setState(() {
        _isLoading = false;
        _isRefreshing = false;
        _errorType = _mapErrorType(e.type);
        _errorMessage = _mapErrorMessage(e);
      });
    } catch (e) {
      if (!mounted || requestId != _currentRequestId) {
        return;
      }
      setState(() {
        _isLoading = false;
        _isRefreshing = false;
        _errorType = HomeErrorType.server;
        _errorMessage = '알 수 없는 오류가 발생했습니다: $e';
      });
      rethrow;
    }
  }

  void _applyWeeklyMenu(WeeklyMenu menu) {
    _weeklyMenu = menu;
    _notices = menu.notices;
    _sourceUrl = menu.sourceUrl;
    _isEmpty = menu.isEmpty;
  }

  HomeErrorType _mapErrorType(String type) {
    switch (type) {
      case 'network':
        return HomeErrorType.network;
      case 'server':
        return HomeErrorType.server;
      case 'parsing':
        return HomeErrorType.parsing;
      default:
        return HomeErrorType.server;
    }
  }

  String _mapErrorMessage(MenuApiException exception) {
    switch (exception.type) {
      case 'network':
        return '네트워크 연결에 실패했습니다. 캐시가 있으면 마지막 데이터를 유지합니다.';
      case 'server':
        return '서버 응답이 올바르지 않습니다. 잠시 후 다시 시도해 주세요.';
      case 'parsing':
        return '공식 사이트 구조가 변경되었을 수 있습니다. ${exception.message}';
      default:
        return exception.message;
    }
  }

  Future<void> _pickDate() async {
    final picked = await showDatePicker(
      context: context,
      initialDate: _selectedDate,
      firstDate: DateTime(2020),
      lastDate: DateTime(2100),
    );
    if (picked == null) {
      return;
    }

    try {
      final normalized = AppDateUtils.dateOnly(picked);
      await _repository.storage.saveLastSelectedDate(normalized);
      setState(() {
        _selectedDate = normalized;
        _currentWeekStart = AppDateUtils.startOfWeek(normalized);
        _currentWeekEnd = AppDateUtils.endOfWeek(normalized);
      });
      await _showCachedThenRefresh(forceNetwork: false);
    } catch (e) {
      debugPrint('날짜 선택 처리 실패: $e');
      rethrow;
    }
  }

  Future<void> _pickRestaurant() async {
    final selected = await showModalBottomSheet<Restaurant>(
      context: context,
      showDragHandle: true,
      builder: (context) {
        return SafeArea(
          child: ListView(
            shrinkWrap: true,
            children: RestaurantConstants.restaurants.map((restaurant) {
              final isFavorite = restaurant.id == _favoriteRestaurantId;
              return ListTile(
                title: Text(restaurant.name),
                leading: Icon(isFavorite ? Icons.star : Icons.restaurant),
                trailing: _selectedRestaurant?.id == restaurant.id ? const Icon(Icons.check) : null,
                onTap: () => Navigator.of(context).pop(restaurant),
              );
            }).toList(),
          ),
        );
      },
    );

    if (selected == null) {
      return;
    }

    try {
      await _repository.storage.saveLastSelectedRestaurant(selected.id);
      setState(() {
        _selectedRestaurant = selected;
      });
      await _showCachedThenRefresh(forceNetwork: false);
    } catch (e) {
      debugPrint('식당 선택 처리 실패: $e');
      rethrow;
    }
  }

  Future<void> _toggleFavorite() async {
    final restaurant = _selectedRestaurant;
    if (restaurant == null) {
      return;
    }

    try {
      final nextFavorite = _favoriteRestaurantId == restaurant.id ? null : restaurant.id;
      await _repository.storage.saveFavoriteRestaurant(nextFavorite);
      setState(() {
        _favoriteRestaurantId = nextFavorite;
      });
    } catch (e) {
      debugPrint('즐겨찾기 저장 실패: $e');
      rethrow;
    }
  }

  @override
  Widget build(BuildContext context) {
    final restaurantName = _selectedRestaurant?.name ?? '식당 미선택';
    final weekRange = AppDateUtils.formatWeekRange(_currentWeekStart, _currentWeekEnd);
    final hasData = _weeklyMenu != null;

    return Scaffold(
      appBar: AppBar(title: const Text('강원대학교 학식')),
      body: SafeArea(
        child: SingleChildScrollView(
          padding: const EdgeInsets.all(16),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              HeaderSection(
                restaurantName: restaurantName,
                weekRangeText: weekRange,
              ),
              const SizedBox(height: 16),
              ActionBar(
                onPickDate: _pickDate,
                onPickRestaurant: _pickRestaurant,
                onToggleFavorite: _toggleFavorite,
                onRefresh: () => _fetchWeeklyMenu(forceNetwork: true),
                isFavorite: _favoriteRestaurantId == _selectedRestaurant?.id,
                isRefreshing: _isRefreshing,
              ),
              const SizedBox(height: 16),
              StatusSection(
                isLoading: _isLoading,
                isRefreshing: _isRefreshing,
                isEmpty: _isEmpty,
                errorType: _errorType,
                errorMessage: _errorMessage,
                onRetry: () => _fetchWeeklyMenu(forceNetwork: true),
                hasData: hasData,
              ),
              if (hasData) ...[
                const SizedBox(height: 12),
                WeeklyMenuTable(days: _weeklyMenu!.days),
                const SizedBox(height: 16),
                InfoFooter(
                  notices: _notices,
                  sourceUrl: _sourceUrl,
                  lastUpdated: _lastSuccessfulSync,
                ),
              ],
            ],
          ),
        ),
      ),
    );
  }
}
