class DailyMenu {
  final DateTime date;
  final String weekdayLabel;
  final List<String> breakfastMenus;
  final List<String> lunchMenus;
  final List<String> dinnerMenus;
  final String note;

  const DailyMenu({
    required this.date,
    required this.weekdayLabel,
    required this.breakfastMenus,
    required this.lunchMenus,
    required this.dinnerMenus,
    required this.note,
  });

  bool get isCompletelyEmpty =>
      breakfastMenus.isEmpty && lunchMenus.isEmpty && dinnerMenus.isEmpty && note.trim().isEmpty;

  Map<String, dynamic> toJson() => {
        'date': date.toIso8601String(),
        'weekdayLabel': weekdayLabel,
        'breakfastMenus': breakfastMenus,
        'lunchMenus': lunchMenus,
        'dinnerMenus': dinnerMenus,
        'note': note,
      };

  factory DailyMenu.fromJson(Map<String, dynamic> json) {
    return DailyMenu(
      date: DateTime.tryParse(json['date'] as String? ?? '') ?? DateTime.now(),
      weekdayLabel: json['weekdayLabel'] as String? ?? '',
      breakfastMenus: (json['breakfastMenus'] as List<dynamic>? ?? const [])
          .map((e) => e.toString())
          .toList(),
      lunchMenus: (json['lunchMenus'] as List<dynamic>? ?? const [])
          .map((e) => e.toString())
          .toList(),
      dinnerMenus: (json['dinnerMenus'] as List<dynamic>? ?? const [])
          .map((e) => e.toString())
          .toList(),
      note: json['note'] as String? ?? '',
    );
  }
}
