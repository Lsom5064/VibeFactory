import 'package:flutter/material.dart';

import '../crash_handler.dart';
import '../models/menu_models.dart';
import '../services/menu_repository.dart';
import '../widgets/menu_cards.dart';

enum HomeViewState { initializing, loading, success, empty, error }

class HomeScreen extends StatefulWidget {
  const HomeScreen({super.key});

  @override
  State<HomeScreen> createState() => _HomeScreenState();
}

class _HomeScreenState extends State<HomeScreen> {
  final MenuRepository _repository = MenuRepository();

  HomeViewState _viewState = HomeViewState.initializing;
  bool _isRequestInProgress = false;
  WeeklyMenu? _weeklyMenu;
  DateTime? _lastUpdatedAt;
  String? _recentErrorMessage;
  bool _usedCache = false;
  String? _refreshFailureNotice;

  @override
  void initState() {
    super.initState();
    loadMenus();
  }

  Future<void> loadMenus() async {
    if (_isRequestInProgress) {
      return;
    }

    final hadExistingData = _weeklyMenu != null;
    if (mounted) {
      setState(() {
        _isRequestInProgress = true;
        _refreshFailureNotice = null;
        _recentErrorMessage = null;
        if (!hadExistingData) {
          _viewState = HomeViewState.loading;
        }
      });
    }

    try {
      final result = await _repository.fetchWeeklyMenu();
      if (!mounted) {
        return;
      }
      setState(() {
        _weeklyMenu = result.menu;
        _usedCache = result.usedCache;
        _refreshFailureNotice = result.warningMessage;
        _lastUpdatedAt = result.menu?.updatedAt;
        _viewState = result.menu == null ? HomeViewState.empty : HomeViewState.success;
      });
      if (result.warningMessage != null && result.warningMessage!.isNotEmpty) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text(result.warningMessage!)),
        );
      }
    } catch (error, stackTrace) {
      CrashHandler.recordError(error, stackTrace, reason: '홈 화면 식단 조회 실패');
      if (!mounted) {
        return;
      }
      final message = '식단 정보를 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.';
      setState(() {
        _recentErrorMessage = message;
        if (_weeklyMenu != null) {
          _refreshFailureNotice = '새로고침에 실패하여 기존 데이터를 유지합니다.';
          _viewState = HomeViewState.success;
        } else {
          _viewState = HomeViewState.error;
        }
      });
      if (_weeklyMenu != null) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('새로고침에 실패했습니다. 기존 데이터를 표시합니다.')),
        );
      }
    } finally {
      if (!mounted) {
        return;
      }
      setState(() {
        _isRequestInProgress = false;
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('강원대 삼척캠퍼스 주간 학식'),
        actions: [
          IconButton(
            key: UniqueKey(),
            onPressed: _isRequestInProgress ? null : loadMenus,
            icon: const Icon(Icons.refresh),
            tooltip: '새로고침',
          ),
        ],
      ),
      body: SafeArea(
        child: SingleChildScrollView(
          padding: const EdgeInsets.all(16),
          child: _buildBody(),
        ),
      ),
    );
  }

  Widget _buildBody() {
    switch (_viewState) {
      case HomeViewState.initializing:
      case HomeViewState.loading:
        return SizedBox(
          height: 500,
          child: Center(
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: const [
                CircularProgressIndicator(),
                SizedBox(height: 16),
                Text('식단 정보를 불러오는 중입니다.'),
              ],
            ),
          ),
        );
      case HomeViewState.error:
        return SizedBox(
          height: 500,
          child: Center(
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                const Icon(Icons.error_outline, size: 56),
                const SizedBox(height: 16),
                Text(_recentErrorMessage ?? '오류가 발생했습니다.'),
                const SizedBox(height: 16),
                ElevatedButton(
                  key: UniqueKey(),
                  onPressed: _isRequestInProgress ? null : loadMenus,
                  child: const Text('다시 시도'),
                ),
              ],
            ),
          ),
        );
      case HomeViewState.empty:
        return SizedBox(
          height: 500,
          child: Center(
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                const Icon(Icons.event_busy, size: 56),
                const SizedBox(height: 16),
                const Text('이번 주 식단 정보가 없습니다.'),
                const SizedBox(height: 16),
                ElevatedButton(
                  key: UniqueKey(),
                  onPressed: _isRequestInProgress ? null : loadMenus,
                  child: const Text('새로고침'),
                ),
              ],
            ),
          ),
        );
      case HomeViewState.success:
        final menu = _weeklyMenu;
        if (menu == null) {
          return const SizedBox.shrink();
        }
        return Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            WeeklySummaryCard(
              menu: menu,
              usedCache: _usedCache,
              warningMessage: _refreshFailureNotice,
            ),
            if (_lastUpdatedAt != null) const SizedBox(height: 8),
            const SizedBox(height: 8),
            ...menu.days.map((day) => Padding(
                  padding: const EdgeInsets.only(bottom: 12),
                  child: DailyMenuCard(dailyMenu: day),
                )),
          ],
        );
    }
  }
}
