import '../models/menu_models.dart';

class MenuParser {
  WeeklyMenu? parse(String html) {
    if (html.trim().isEmpty) {
      return null;
    }

    return WeeklyMenu(
      startDate: DateTime.now(),
      endDate: DateTime.now(),
      updatedAt: DateTime.now(),
      days: const <DailyMenu>[],
    );
  }
}
