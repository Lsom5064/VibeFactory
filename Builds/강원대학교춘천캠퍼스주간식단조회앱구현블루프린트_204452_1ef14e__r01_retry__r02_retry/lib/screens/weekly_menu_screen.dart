import 'package:flutter/material.dart';
import '../controllers/menu_controller.dart';

class WeeklyMenuScreen extends StatefulWidget {
  const WeeklyMenuScreen({super.key});

  @override
  State<WeeklyMenuScreen> createState() => _WeeklyMenuScreenState();
}

class _WeeklyMenuScreenState extends State<WeeklyMenuScreen> {
  late final WeeklyMenuController _controller;

  @override
  void initState() {
    super.initState();
    _controller = WeeklyMenuController();
    _loadMenu();
  }

  Future<void> _loadMenu() async {
    try {
      await _controller.fetchCurrentWeek();
      if (mounted) {
        setState(() {});
      }
    } catch (_) {
      if (mounted) {
        setState(() {});
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);

    return Scaffold(
      appBar: AppBar(
        title: const Text('강원대학교 주간 식단'),
      ),
      body: SingleChildScrollView(
        padding: const EdgeInsets.all(16),
        child: _controller.isLoading
            ? const Center(child: Padding(
                padding: EdgeInsets.all(24),
                child: CircularProgressIndicator(),
              ))
            : _controller.errorMessage != null
                ? Card(
                    child: Padding(
                      padding: const EdgeInsets.all(16),
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Text(
                            '오류',
                            style: theme.textTheme.titleMedium,
                          ),
                          const SizedBox(height: 8),
                          Text(_controller.errorMessage!),
                          const SizedBox(height: 16),
                          ElevatedButton(
                            key: UniqueKey(),
                            onPressed: _loadMenu,
                            child: const Text('다시 시도'),
                          ),
                        ],
                      ),
                    ),
                  )
                : Column(
                    crossAxisAlignment: CrossAxisAlignment.stretch,
                    children: (_controller.weeklyMenu ?? <String, dynamic>{}).entries.map((entry) {
                      final meals = (entry.value as List<dynamic>).cast<String>();
                      return Card(
                        margin: const EdgeInsets.only(bottom: 12),
                        child: Padding(
                          padding: const EdgeInsets.all(16),
                          child: Column(
                            crossAxisAlignment: CrossAxisAlignment.start,
                            children: [
                              Text(
                                entry.key,
                                style: theme.textTheme.titleMedium,
                              ),
                              const SizedBox(height: 8),
                              ...meals.map(
                                (meal) => Padding(
                                  padding: const EdgeInsets.only(bottom: 4),
                                  child: Text('• $meal'),
                                ),
                              ),
                            ],
                          ),
                        ),
                      );
                    }).toList(),
                  ),
      ),
    );
  }
}
