import '../models/menu_item.dart';

class ValidationService {
  bool validateMenuRecord(MenuItem item) {
    if (item.campusName.trim().isEmpty || item.restaurantName.trim().isEmpty) {
      return false;
    }
    if (item.menuCategoryName.trim().isEmpty || item.mealType.trim().isEmpty) {
      return false;
    }
    if (item.dateLabel.trim().isEmpty || item.dayOfWeek.trim().isEmpty) {
      return false;
    }
    if (item.menuBody.trim().isEmpty) {
      return false;
    }
    return true;
  }
}
