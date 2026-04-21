class AppDateUtils {
  static String currentTargetDate() {
    final now = DateTime.now();
    return _format(now);
  }

  static String moveWeek(String targetDate, int weekOffset) {
    final parsed = DateTime.tryParse(targetDate) ?? DateTime.now();
    return _format(parsed.add(Duration(days: 7 * weekOffset)));
  }

  static String _format(DateTime date) {
    final month = date.month.toString().padLeft(2, '0');
    final day = date.day.toString().padLeft(2, '0');
    return '${date.year}-$month-$day';
  }
}
