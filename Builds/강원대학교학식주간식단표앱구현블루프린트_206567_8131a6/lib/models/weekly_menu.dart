import 'daily_menu.dart';

class WeeklyMenu {
  final DateTime weekStart;
  final DateTime weekEnd;
  final DateTime fetchedAt;
  final String sourceUrl;
  final List<String> notices;
  final List<DailyMenu> days;

  const WeeklyMenu({
    required this.weekStart,
    required this.weekEnd,
    required this.fetchedAt,
    required this.sourceUrl,
    required this.notices,
    required this.days,
  });

  bool get isEmpty => days.isEmpty || days.every((day) => day.isCompletelyEmpty);

  Map<String, dynamic> toJson() => {
        'weekStart': weekStart.toIso8601String(),
        'weekEnd': weekEnd.toIso8601String(),
        'fetchedAt': fetchedAt.toIso8601String(),
        'sourceUrl': sourceUrl,
        'notices': notices,
        'days': days.map((e) => e.toJson()).toList(),
      };

  factory WeeklyMenu.fromJson(Map<String, dynamic> json) {
    return WeeklyMenu(
      weekStart: DateTime.tryParse(json['weekStart'] as String? ?? '') ?? DateTime.now(),
      weekEnd: DateTime.tryParse(json['weekEnd'] as String? ?? '') ?? DateTime.now(),
      fetchedAt: DateTime.tryParse(json['fetchedAt'] as String? ?? '') ?? DateTime.now(),
      sourceUrl: json['sourceUrl'] as String? ?? '',
      notices: (json['notices'] as List<dynamic>? ?? const []).map((e) => e.toString()).toList(),
      days: (json['days'] as List<dynamic>? ?? const [])
          .whereType<Map<String, dynamic>>()
          .map(DailyMenu.fromJson)
          .toList(),
    );
  }
}
