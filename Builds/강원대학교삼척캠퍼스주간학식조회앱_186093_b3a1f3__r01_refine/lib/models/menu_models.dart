class WeeklyMenu {
  final DateTime startDate;
  final DateTime endDate;
  final DateTime updatedAt;
  final List<DailyMenu> days;

  const WeeklyMenu({
    required this.startDate,
    required this.endDate,
    required this.updatedAt,
    required this.days,
  });

  Map<String, dynamic> toJson() => {
        'startDate': startDate.toIso8601String(),
        'endDate': endDate.toIso8601String(),
        'updatedAt': updatedAt.toIso8601String(),
        'days': days.map((e) => e.toJson()).toList(),
      };

  factory WeeklyMenu.fromJson(Map<String, dynamic> json) {
    return WeeklyMenu(
      startDate: DateTime.parse(json['startDate'] as String),
      endDate: DateTime.parse(json['endDate'] as String),
      updatedAt: DateTime.parse(json['updatedAt'] as String),
      days: (json['days'] as List<dynamic>)
          .map((e) => DailyMenu.fromJson(Map<String, dynamic>.from(e as Map)))
          .toList(),
    );
  }
}

class DailyMenu {
  final DateTime date;
  final String weekday;
  final int order;
  final List<CafeteriaMenu> cafeterias;

  const DailyMenu({
    required this.date,
    required this.weekday,
    required this.order,
    required this.cafeterias,
  });

  Map<String, dynamic> toJson() => {
        'date': date.toIso8601String(),
        'weekday': weekday,
        'order': order,
        'cafeterias': cafeterias.map((e) => e.toJson()).toList(),
      };

  factory DailyMenu.fromJson(Map<String, dynamic> json) {
    return DailyMenu(
      date: DateTime.parse(json['date'] as String),
      weekday: json['weekday'] as String? ?? '',
      order: json['order'] as int? ?? 0,
      cafeterias: (json['cafeterias'] as List<dynamic>)
          .map((e) => CafeteriaMenu.fromJson(Map<String, dynamic>.from(e as Map)))
          .toList(),
    );
  }
}

class CafeteriaMenu {
  final String name;
  final String? category;
  final List<MealSection> sections;

  const CafeteriaMenu({
    required this.name,
    this.category,
    required this.sections,
  });

  Map<String, dynamic> toJson() => {
        'name': name,
        'category': category,
        'sections': sections.map((e) => e.toJson()).toList(),
      };

  factory CafeteriaMenu.fromJson(Map<String, dynamic> json) {
    return CafeteriaMenu(
      name: json['name'] as String? ?? '',
      category: json['category'] as String?,
      sections: (json['sections'] as List<dynamic>)
          .map((e) => MealSection.fromJson(Map<String, dynamic>.from(e as Map)))
          .toList(),
    );
  }
}

class MealSection {
  final String title;
  final List<String> items;

  const MealSection({
    required this.title,
    required this.items,
  });

  Map<String, dynamic> toJson() => {
        'title': title,
        'items': items,
      };

  factory MealSection.fromJson(Map<String, dynamic> json) {
    return MealSection(
      title: json['title'] as String? ?? '',
      items: (json['items'] as List<dynamic>).map((e) => e.toString()).toList(),
    );
  }
}

class CachedWeeklyMenu {
  final WeeklyMenu menu;
  final DateTime savedAt;

  const CachedWeeklyMenu({required this.menu, required this.savedAt});
}

class MenuFetchResult {
  final WeeklyMenu? menu;
  final bool usedCache;
  final String? warningMessage;

  const MenuFetchResult({
    required this.menu,
    required this.usedCache,
    this.warningMessage,
  });
}
