import 'package:flutter/material.dart';

import '../controllers/menu_controller.dart';
import '../widgets/empty_state_view.dart';
import '../widgets/menu_section_card.dart';
import '../widgets/status_card.dart';

class WeeklyMenuScreen extends StatefulWidget {
  const WeeklyMenuScreen({super.key});

  @override
  State<WeeklyMenuScreen> createState() => _WeeklyMenuScreenState();
}

class _WeeklyMenuScreenState extends State<WeeklyMenuScreen> {
  final MenuController _controller = MenuController();

  @override
  void initState() {
    super.initState();
    _controller.addListener(_onChanged);
    _controller.fetchCurrentWeek();
  }

  @override
  void dispose() {
    _controller.removeListener(_onChanged);
    _controller.dispose();
    super.dispose();
  }

  void _onChanged() {
    if (mounted) {
      setState(() {});
    }
  }

  @override
  Widget build(BuildContext context) {
    final weeklyMenu = _controller.weeklyMenu;

    return Scaffold(
      appBar: AppBar(
        title: const Text('주간 식단'),
        actions: [
          IconButton(
            onPressed: _controller.fetchCurrentWeek,
            icon: const Icon(Icons.refresh),
          ),
        ],
      ),
      body: SingleChildScrollView(
        padding: const EdgeInsets.all(16),
        child: _controller.isLoading && weeklyMenu == null
            ? const Center(child: CircularProgressIndicator())
            : weeklyMenu == null || weeklyMenu.entries.isEmpty
                ? EmptyStateView(
                    title: '식단 정보 없음',
                    message: _controller.errorMessage ?? '표시할 주간 식단이 없습니다.',
                    icon: Icons.restaurant_outlined,
                  )
                : Column(
                    crossAxisAlignment: CrossAxisAlignment.stretch,
                    children: [
                      if (_controller.transientMessage != null)
                        Padding(
                          padding: const EdgeInsets.only(bottom: 12),
                          child: StatusCard(
                            title: '안내',
                            message: _controller.transientMessage!,
                          ),
                        ),
                      if (_controller.errorMessage != null)
                        Padding(
                          padding: const EdgeInsets.only(bottom: 12),
                          child: StatusCard(
                            title: '오류',
                            message: _controller.errorMessage!,
                            icon: Icons.error_outline,
                          ),
                        ),
                      ...weeklyMenu.entries.map(
                        (entry) => Padding(
                          padding: const EdgeInsets.only(bottom: 12),
                          child: MenuSectionCard(
                            title: '${entry.restaurant} · ${entry.date} · ${entry.section}',
                            items: entry.items,
                          ),
                        ),
                      ),
                    ],
                  ),
      ),
    );
  }
}
