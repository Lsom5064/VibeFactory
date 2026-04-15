import '../models/menu_models.dart';

class CacheRepository {
  WeeklyMenu? _cached;

  Future<void> saveWeeklyMenu(WeeklyMenu menu) async {
    _cached = menu;
  }

  Future<WeeklyMenu?> loadWeeklyMenu() async {
    return _cached;
  }
}
