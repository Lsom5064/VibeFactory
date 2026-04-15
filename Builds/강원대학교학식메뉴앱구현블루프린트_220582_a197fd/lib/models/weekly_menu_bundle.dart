import 'daily_menu.dart';

class WeeklyMenuBundle {
  final String weekStart;
  final String weekEnd;
  final List<DailyMenu> dailyMenus;

  const WeeklyMenuBundle({
    required this.weekStart,
    required this.weekEnd,
    required this.dailyMenus,
  });

  bool get hasData => dailyMenus.isNotEmpty;
}
