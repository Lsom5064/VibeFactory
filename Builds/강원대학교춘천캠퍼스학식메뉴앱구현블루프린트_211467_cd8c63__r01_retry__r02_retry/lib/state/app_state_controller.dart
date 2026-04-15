import 'package:flutter/foundation.dart';
import 'package:shared_preferences/shared_preferences.dart';

class AppStateController extends ChangeNotifier {
  static const String _cacheKeyMeals = 'cached_meals';
  static const String _cacheKeyRestaurants = 'available_restaurants';
  static const String _cacheKeySelectedRestaurant = 'selected_restaurant';
  static const String _cacheKeySelectedSection = 'selected_section';

  bool isLoading = false;
  String? errorMessage;

  List<String> _meals = <String>[];
  List<String> availableRestaurants = <String>[];
  String? selectedRestaurantName;
  String? selectedSectionTitle;

  List<String> get todayMeals => List.unmodifiable(_meals);

  Future<void> initialize() async {
    isLoading = true;
    notifyListeners();
    await _loadCache();
    isLoading = false;
    notifyListeners();
  }

  Future<void> refresh() async {
    await initialize();
  }

  Future<void> selectRestaurant(String? restaurant) async {
    selectedRestaurantName = restaurant;
    await _saveCache();
    notifyListeners();
  }

  Future<void> selectSection(String? section) async {
    selectedSectionTitle = section;
    await _saveCache();
    notifyListeners();
  }

  Future<void> _saveCache() async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setStringList(_cacheKeyMeals, _meals);
    await prefs.setStringList(_cacheKeyRestaurants, availableRestaurants);
    if (selectedRestaurantName != null) {
      await prefs.setString(_cacheKeySelectedRestaurant, selectedRestaurantName!);
    } else {
      await prefs.remove(_cacheKeySelectedRestaurant);
    }
    if (selectedSectionTitle != null) {
      await prefs.setString(_cacheKeySelectedSection, selectedSectionTitle!);
    } else {
      await prefs.remove(_cacheKeySelectedSection);
    }
  }

  Future<void> _loadCache() async {
    final prefs = await SharedPreferences.getInstance();
    _meals = prefs.getStringList(_cacheKeyMeals) ?? <String>[];
    availableRestaurants = prefs.getStringList(_cacheKeyRestaurants) ?? <String>[];
    selectedRestaurantName = prefs.getString(_cacheKeySelectedRestaurant);
    selectedSectionTitle = prefs.getString(_cacheKeySelectedSection);

    if (availableRestaurants.isNotEmpty &&
        (selectedRestaurantName == null ||
            !availableRestaurants.contains(selectedRestaurantName))) {
      selectedRestaurantName = availableRestaurants.first;
    }
  }
}
