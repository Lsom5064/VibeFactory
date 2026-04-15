class DailyMenu {
  final String date;
  final String restaurant;
  final String section;
  final List<String> items;

  const DailyMenu({
    required this.date,
    required this.restaurant,
    required this.section,
    required this.items,
  });
}

class WeeklyMenu {
  final List<DailyMenu> entries;

  const WeeklyMenu({required this.entries});

  List<String> get restaurants =>
      entries.map((entry) => entry.restaurant).toSet().toList();
}
