import 'package:flutter/material.dart';

import '../state/app_controller.dart';
import '../widgets/status_banner.dart';
import 'tabs/about_tab.dart';
import 'tabs/today_menu_tab.dart';
import 'tabs/weekly_menu_tab.dart';

class HomeScreen extends StatefulWidget {
  final AppController controller;

  const HomeScreen({super.key, required this.controller});

  @override
  State<HomeScreen> createState() => _HomeScreenState();
}

class _HomeScreenState extends State<HomeScreen> {
  @override
  void initState() {
    super.initState();
    widget.controller.addListener(_onControllerChanged);
  }

  @override
  void dispose() {
    widget.controller.removeListener(_onControllerChanged);
    super.dispose();
  }

  void _onControllerChanged() {
    if (!mounted) {
      return;
    }
    setState(() {});
    final message = widget.controller.transientMessage;
    if (message != null && message.isNotEmpty) {
      ScaffoldMessenger.of(context)
        ..hideCurrentSnackBar()
        ..showSnackBar(SnackBar(content: Text(message)));
      widget.controller.clearTransientMessage();
    }
  }

  @override
  Widget build(BuildContext context) {
    final controller = widget.controller;
    final tabs = [
      TodayMenuTab(controller: controller),
      WeeklyMenuTab(controller: controller),
      AboutTab(controller: controller),
    ];

    return Scaffold(
      appBar: AppBar(
        title: const Text('강원대학교 학식 메뉴'),
        actions: controller.selectedTabIndex == 2
            ? null
            : [
                IconButton(
                  key: UniqueKey(),
                  onPressed: () => controller.refreshMenus(),
                  icon: controller.isRefreshing
                      ? const SizedBox(
                          width: 18,
                          height: 18,
                          child: CircularProgressIndicator(strokeWidth: 2),
                        )
                      : const Icon(Icons.refresh),
                  tooltip: '새로고침',
                ),
              ],
      ),
      body: SingleChildScrollView(
        child: Column(
          children: [
            StatusBanner(syncStatus: controller.syncStatus),
            tabs[controller.selectedTabIndex],
          ],
        ),
      ),
      bottomNavigationBar: NavigationBar(
        selectedIndex: controller.selectedTabIndex,
        onDestinationSelected: controller.selectTab,
        destinations: const [
          NavigationDestination(icon: Icon(Icons.today), label: '오늘 메뉴'),
          NavigationDestination(icon: Icon(Icons.calendar_view_week), label: '이번 주'),
          NavigationDestination(icon: Icon(Icons.info_outline), label: '앱 정보'),
        ],
      ),
    );
  }
}
