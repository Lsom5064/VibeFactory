class DateHelper {
  static String todayKey() {
    final now = DateTime.now();
    return _dateKey(now);
  }

  static String nowIso() {
    return DateTime.now().toIso8601String();
  }

  static bool isToday(String? date) {
    if (date == null || date.isEmpty) {
      return false;
    }
    return date == todayKey();
  }

  static String formatDisplayDate(String? date) {
    if (date == null || date.isEmpty) {
      return '날짜 정보 없음';
    }
    if (date.length == 8) {
      return '${date.substring(0, 4)}-${date.substring(4, 6)}-${date.substring(6, 8)}';
    }
    return date;
  }

  static String formatDisplayDateTime(String? iso) {
    if (iso == null || iso.isEmpty) {
      return '기록 없음';
    }
    try {
      final dt = DateTime.parse(iso).toLocal();
      final y = dt.year.toString().padLeft(4, '0');
      final m = dt.month.toString().padLeft(2, '0');
      final d = dt.day.toString().padLeft(2, '0');
      final h = dt.hour.toString().padLeft(2, '0');
      final min = dt.minute.toString().padLeft(2, '0');
      return '$y-$m-$d $h:$min';
    } catch (_) {
      return iso;
    }
  }

  static String formatTime(int hour, int minute) {
    final h = hour.toString().padLeft(2, '0');
    final m = minute.toString().padLeft(2, '0');
    return '$h:$m';
  }

  static String _dateKey(DateTime dt) {
    final y = dt.year.toString().padLeft(4, '0');
    final m = dt.month.toString().padLeft(2, '0');
    final d = dt.day.toString().padLeft(2, '0');
    return '$y$m$d';
  }
}
