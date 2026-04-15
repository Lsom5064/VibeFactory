class AppDateUtils {
  static DateTime dateOnly(DateTime value) {
    return DateTime(value.year, value.month, value.day);
  }

  static DateTime startOfWeek(DateTime value) {
    final normalized = dateOnly(value);
    final diff = normalized.weekday - DateTime.monday;
    return normalized.subtract(Duration(days: diff));
  }

  static DateTime endOfWeek(DateTime value) {
    return startOfWeek(value).add(const Duration(days: 6));
  }

  static String formatDate(DateTime value) {
    final normalized = dateOnly(value);
    return '${normalized.year}.${normalized.month.toString().padLeft(2, '0')}.${normalized.day.toString().padLeft(2, '0')}';
  }

  static String formatMonthDay(DateTime value) {
    final normalized = dateOnly(value);
    return '${normalized.month.toString().padLeft(2, '0')}/${normalized.day.toString().padLeft(2, '0')}';
  }

  static String formatWeekRange(DateTime start, DateTime end) {
    return '${formatDate(start)} ~ ${formatDate(end)}';
  }

  static String toIsoDate(DateTime value) {
    return dateOnly(value).toIso8601String();
  }

  const AppDateUtils._();
}
