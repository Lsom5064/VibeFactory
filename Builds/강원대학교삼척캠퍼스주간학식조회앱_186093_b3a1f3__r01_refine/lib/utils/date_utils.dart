String formatDate(DateTime date) {
  final y = date.year.toString().padLeft(4, '0');
  final m = date.month.toString().padLeft(2, '0');
  final d = date.day.toString().padLeft(2, '0');
  return '$y-$m-$d';
}

String formatDateTime(DateTime dateTime) {
  final date = formatDate(dateTime);
  final hh = dateTime.hour.toString().padLeft(2, '0');
  final mm = dateTime.minute.toString().padLeft(2, '0');
  return '$date $hh:$mm';
}

String weekdayKorean(DateTime date) {
  const names = ['월', '화', '수', '목', '금', '토', '일'];
  return names[date.weekday - 1];
}

DateTime startOfWeek(DateTime date) {
  final normalized = DateTime(date.year, date.month, date.day);
  return normalized.subtract(Duration(days: normalized.weekday - 1));
}

DateTime endOfWeek(DateTime date) {
  return startOfWeek(date).add(const Duration(days: 6));
}

bool isSameDate(DateTime a, DateTime b) {
  return a.year == b.year && a.month == b.month && a.day == b.day;
}

bool isWithinInclusive(DateTime target, DateTime start, DateTime end) {
  final t = DateTime(target.year, target.month, target.day);
  final s = DateTime(start.year, start.month, start.day);
  final e = DateTime(end.year, end.month, end.day);
  return !t.isBefore(s) && !t.isAfter(e);
}
