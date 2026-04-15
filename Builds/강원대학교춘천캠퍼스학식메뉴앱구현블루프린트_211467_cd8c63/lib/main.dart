import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:http/http.dart' as http;

import 'crash_handler.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  CrashHandler.initialize("211467_cd8c63", "kr.ac.kangwon.hai.kangwonmealmenu.t211467_cd8c63");
  runApp(const MyApp());
}

class MyApp extends StatelessWidget {
  const MyApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: '강원대 학식 메뉴',
      debugShowCheckedModeBanner: false,
      theme: ThemeData(
        useMaterial3: true,
        colorSchemeSeed: Colors.green,
      ),
      home: const AppBootstrap(),
    );
  }
}

class AppBootstrap extends StatefulWidget {
  const AppBootstrap({super.key});

  @override
  State<AppBootstrap> createState() => _AppBootstrapState();
}

class _AppBootstrapState extends State<AppBootstrap> {
  late final AppController controller;
  bool initialized = false;
  String? initError;

  @override
  void initState() {
    super.initState();
    controller = AppController();
    _initialize();
  }

  Future<void> _initialize() async {
    try {
      await controller.initialize();
      if (!mounted) {
        return;
      }
      setState(() {
        initialized = true;
      });
    } catch (error, stackTrace) {
      await CrashHandler.capture(error, stackTrace);
      if (!mounted) {
        return;
      }
      setState(() {
        initError = '앱 초기화 중 오류가 발생했습니다. 다시 시도해 주세요.';
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    if (initError != null) {
      return Scaffold(
        appBar: AppBar(title: const Text('초기화 오류')),
        body: SingleChildScrollView(
          padding: const EdgeInsets.all(16),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Card(
                child: Padding(
                  padding: const EdgeInsets.all(16),
                  child: Text(initError!),
                ),
              ),
              const SizedBox(height: 12),
              ElevatedButton(
                key: UniqueKey(),
                onPressed: _initialize,
                child: const Text('다시 시도'),
              ),
            ],
          ),
        ),
      );
    }

    if (!initialized) {
      return const Scaffold(
        body: SingleChildScrollView(
          child: SizedBox(
            height: 400,
            child: Center(child: CircularProgressIndicator()),
          ),
        ),
      );
    }

    return AnimatedBuilder(
      animation: controller,
      builder: (context, _) {
        if (!controller.state.userSettings.onboardingCompleted ||
            controller.state.userSettings.selectedRestaurantId == null ||
            controller.state.userSettings.selectedRestaurantName == null) {
          return OnboardingScreen(controller: controller);
        }
        return HomeShellScreen(controller: controller);
      },
    );
  }
}

class SourceConstants {
  static const String primaryUrl = 'https://kangwon.ac.kr/ko/extn/37/wkmenu-mngr/list.do';
  static const List<String> candidateUrls = [
    'https://kangwon.ac.kr/ko/extn/37/wkmenu-mngr/list.do',
    'https://kangwon.ac.kr/ko/extn/37/wkmenu-mngr/list.do#content',
    'https://kangwon.ac.kr/assets/neibis/js/neibis.ajaxWrapper.js',
    'https://t1.daumcdn.net/mapjsapi/bundle/postcode/prod/postcode.v2.js',
    'https://dapi.kakao.com/v2/maps/sdk.js?appkey=b2172a175743beeac393facc09b5e62f&libraries=services',
    'https://kangwon.ac.kr/ko/extn/37/wkmenu-mngr/updt.do?campusCd=',
    'https://kangwon.ac.kr/neibis-api/v1/stats/usr/dgstfn?action=post',
  ];
}

class UserSettings {
  final String? selectedRestaurantId;
  final String? selectedRestaurantName;
  final String? notificationTime;
  final bool notificationsEnabled;
  final bool onboardingCompleted;

  const UserSettings({
    this.selectedRestaurantId,
    this.selectedRestaurantName,
    this.notificationTime,
    required this.notificationsEnabled,
    required this.onboardingCompleted,
  });

  factory UserSettings.initial() => const UserSettings(
        notificationsEnabled: false,
        onboardingCompleted: false,
      );

  UserSettings copyWith({
    String? selectedRestaurantId,
    String? selectedRestaurantName,
    String? notificationTime,
    bool? notificationsEnabled,
    bool? onboardingCompleted,
    bool clearRestaurant = false,
  }) {
    return UserSettings(
      selectedRestaurantId: clearRestaurant ? null : (selectedRestaurantId ?? this.selectedRestaurantId),
      selectedRestaurantName: clearRestaurant ? null : (selectedRestaurantName ?? this.selectedRestaurantName),
      notificationTime: notificationTime ?? this.notificationTime,
      notificationsEnabled: notificationsEnabled ?? this.notificationsEnabled,
      onboardingCompleted: onboardingCompleted ?? this.onboardingCompleted,
    );
  }

  Map<String, dynamic> toJson() => {
        'selected_restaurant_id': selectedRestaurantId,
        'selected_restaurant_name': selectedRestaurantName,
        'notification_time': notificationTime,
        'notifications_enabled': notificationsEnabled,
        'onboarding_completed': onboardingCompleted,
      };

  factory UserSettings.fromJson(Map<String, dynamic> json) => UserSettings(
        selectedRestaurantId: json['selected_restaurant_id'] as String?,
        selectedRestaurantName: json['selected_restaurant_name'] as String?,
        notificationTime: json['notification_time'] as String?,
        notificationsEnabled: json['notifications_enabled'] as bool? ?? false,
        onboardingCompleted: json['onboarding_completed'] as bool? ?? false,
      );
}

class RestaurantItem {
  final String restaurantId;
  final String restaurantName;
  final bool isVisible;
  final String sourceLocation;

  const RestaurantItem({
    required this.restaurantId,
    required this.restaurantName,
    required this.isVisible,
    required this.sourceLocation,
  });

  Map<String, dynamic> toJson() => {
        'restaurant_id': restaurantId,
        'restaurant_name': restaurantName,
        'is_visible': isVisible,
        'source_location': sourceLocation,
      };

  factory RestaurantItem.fromJson(Map<String, dynamic> json) => RestaurantItem(
        restaurantId: json['restaurant_id'] as String,
        restaurantName: json['restaurant_name'] as String? ?? '',
        isVisible: json['is_visible'] as bool? ?? false,
        sourceLocation: json['source_location'] as String,
      );
}

class DailyMenuItem {
  final String restaurantId;
  final String? mealType;
  final String? dateLabel;
  final String menuBody;
  final bool isToday;

  const DailyMenuItem({
    required this.restaurantId,
    required this.mealType,
    required this.dateLabel,
    required this.menuBody,
    required this.isToday,
  });

  Map<String, dynamic> toJson() => {
        'restaurant_id': restaurantId,
        'meal_type': mealType,
        'date_label': dateLabel,
        'menu_body': menuBody,
        'is_today': isToday,
      };

  factory DailyMenuItem.fromJson(Map<String, dynamic> json) => DailyMenuItem(
        restaurantId: json['restaurant_id'] as String,
        mealType: json['meal_type'] as String?,
        dateLabel: json['date_label'] as String?,
        menuBody: json['menu_body'] as String? ?? '',
        isToday: json['is_today'] as bool? ?? false,
      );
}

class WeeklyMenu {
  final String restaurantId;
  final DateTime fetchedAt;
  final String sourceUrl;
  final String validationStatus;

  const WeeklyMenu({
    required this.restaurantId,
    required this.fetchedAt,
    required this.sourceUrl,
    required this.validationStatus,
  });

  Map<String, dynamic> toJson() => {
        'restaurant_id': restaurantId,
        'fetched_at': fetchedAt.toIso8601String(),
        'source_url': sourceUrl,
        'validation_status': validationStatus,
      };

  factory WeeklyMenu.fromJson(Map<String, dynamic> json) => WeeklyMenu(
        restaurantId: json['restaurant_id'] as String,
        fetchedAt: DateTime.tryParse(json['fetched_at'] as String? ?? '') ?? DateTime.now(),
        sourceUrl: json['source_url'] as String? ?? SourceConstants.primaryUrl,
        validationStatus: json['validation_status'] as String? ?? '실패',
      );
}

class DataStatus {
  final DateTime? lastSuccessAt;
  final DateTime? lastAttemptAt;
  final String? lastErrorCode;
  final String? errorMessage;
  final bool hasCache;
  final String? sourceValidationResult;

  const DataStatus({
    required this.lastSuccessAt,
    required this.lastAttemptAt,
    required this.lastErrorCode,
    required this.errorMessage,
    required this.hasCache,
    required this.sourceValidationResult,
  });

  factory DataStatus.initial() => const DataStatus(
        lastSuccessAt: null,
        lastAttemptAt: null,
        lastErrorCode: null,
        errorMessage: null,
        hasCache: false,
        sourceValidationResult: null,
      );

  DataStatus copyWith({
    DateTime? lastSuccessAt,
    DateTime? lastAttemptAt,
    String? lastErrorCode,
    String? errorMessage,
    bool? hasCache,
    String? sourceValidationResult,
    bool clearError = false,
  }) {
    return DataStatus(
      lastSuccessAt: lastSuccessAt ?? this.lastSuccessAt,
      lastAttemptAt: lastAttemptAt ?? this.lastAttemptAt,
      lastErrorCode: clearError ? null : (lastErrorCode ?? this.lastErrorCode),
      errorMessage: clearError ? null : (errorMessage ?? this.errorMessage),
      hasCache: hasCache ?? this.hasCache,
      sourceValidationResult: sourceValidationResult ?? this.sourceValidationResult,
    );
  }

  Map<String, dynamic> toJson() => {
        'last_success_at': lastSuccessAt?.toIso8601String(),
        'last_attempt_at': lastAttemptAt?.toIso8601String(),
        'last_error_code': lastErrorCode,
        'error_message': errorMessage,
        'has_cache': hasCache,
        'source_validation_result': sourceValidationResult,
      };

  factory DataStatus.fromJson(Map<String, dynamic> json) => DataStatus(
        lastSuccessAt: DateTime.tryParse(json['last_success_at'] as String? ?? ''),
        lastAttemptAt: DateTime.tryParse(json['last_attempt_at'] as String? ?? ''),
        lastErrorCode: json['last_error_code'] as String?,
        errorMessage: json['error_message'] as String?,
        hasCache: json['has_cache'] as bool? ?? false,
        sourceValidationResult: json['source_validation_result'] as String?,
      );
}

class NotificationScheduleInfo {
  final String? scheduledTime;
  final DateTime? nextNotificationAt;
  final bool requiresBootRestore;

  const NotificationScheduleInfo({
    required this.scheduledTime,
    required this.nextNotificationAt,
    required this.requiresBootRestore,
  });

  factory NotificationScheduleInfo.initial() => const NotificationScheduleInfo(
        scheduledTime: null,
        nextNotificationAt: null,
        requiresBootRestore: true,
      );

  NotificationScheduleInfo copyWith({
    String? scheduledTime,
    DateTime? nextNotificationAt,
    bool? requiresBootRestore,
  }) {
    return NotificationScheduleInfo(
      scheduledTime: scheduledTime ?? this.scheduledTime,
      nextNotificationAt: nextNotificationAt ?? this.nextNotificationAt,
      requiresBootRestore: requiresBootRestore ?? this.requiresBootRestore,
    );
  }

  Map<String, dynamic> toJson() => {
        'scheduled_time': scheduledTime,
        'next_notification_at': nextNotificationAt?.toIso8601String(),
        'requires_boot_restore': requiresBootRestore,
      };

  factory NotificationScheduleInfo.fromJson(Map<String, dynamic> json) => NotificationScheduleInfo(
        scheduledTime: json['scheduled_time'] as String?,
        nextNotificationAt: DateTime.tryParse(json['next_notification_at'] as String? ?? ''),
        requiresBootRestore: json['requires_boot_restore'] as bool? ?? true,
      );
}

class AppStateModel {
  final UserSettings userSettings;
  final List<RestaurantItem> restaurants;
  final WeeklyMenu? weeklyMenu;
  final List<DailyMenuItem> menuItems;
  final DataStatus dataStatus;
  final NotificationScheduleInfo notificationInfo;
  final bool notificationPermissionGranted;
  final bool refreshInFlight;
  final String? transientMessage;

  const AppStateModel({
    required this.userSettings,
    required this.restaurants,
    required this.weeklyMenu,
    required this.menuItems,
    required this.dataStatus,
    required this.notificationInfo,
    required this.notificationPermissionGranted,
    required this.refreshInFlight,
    required this.transientMessage,
  });

  factory AppStateModel.initial() => AppStateModel(
        userSettings: UserSettings.initial(),
        restaurants: const [],
        weeklyMenu: null,
        menuItems: const [],
        dataStatus: DataStatus.initial(),
        notificationInfo: NotificationScheduleInfo.initial(),
        notificationPermissionGranted: false,
        refreshInFlight: false,
        transientMessage: null,
      );

  AppStateModel copyWith({
    UserSettings? userSettings,
    List<RestaurantItem>? restaurants,
    WeeklyMenu? weeklyMenu,
    List<DailyMenuItem>? menuItems,
    DataStatus? dataStatus,
    NotificationScheduleInfo? notificationInfo,
    bool? notificationPermissionGranted,
    bool? refreshInFlight,
    String? transientMessage,
    bool clearTransientMessage = false,
  }) {
    return AppStateModel(
      userSettings: userSettings ?? this.userSettings,
      restaurants: restaurants ?? this.restaurants,
      weeklyMenu: weeklyMenu ?? this.weeklyMenu,
      menuItems: menuItems ?? this.menuItems,
      dataStatus: dataStatus ?? this.dataStatus,
      notificationInfo: notificationInfo ?? this.notificationInfo,
      notificationPermissionGranted: notificationPermissionGranted ?? this.notificationPermissionGranted,
      refreshInFlight: refreshInFlight ?? this.refreshInFlight,
      transientMessage: clearTransientMessage ? null : (transientMessage ?? this.transientMessage),
    );
  }
}

class LocalStorageService {
  static const MethodChannel _channel = MethodChannel('kangwon_meal_menu/storage');

  Future<String?> read(String key) async {
    try {
      return await _channel.invokeMethod<String>('read', {'key': key});
    } catch (error, stackTrace) {
      await CrashHandler.capture(error, stackTrace);
      return null;
    }
  }

  Future<void> write(String key, String value) async {
    try {
      await _channel.invokeMethod('write', {'key': key, 'value': value});
    } catch (error, stackTrace) {
      await CrashHandler.capture(error, stackTrace);
      rethrow;
    }
  }

  Future<void> remove(String key) async {
    try {
      await _channel.invokeMethod('remove', {'key': key});
    } catch (error, stackTrace) {
      await CrashHandler.capture(error, stackTrace);
    }
  }
}

class SettingsRepository {
  SettingsRepository(this.storage);
  final LocalStorageService storage;

  Future<UserSettings> loadSettings() async {
    final raw = await storage.read('user_settings');
    if (raw == null || raw.isEmpty) {
      return UserSettings.initial();
    }
    try {
      return UserSettings.fromJson(jsonDecode(raw) as Map<String, dynamic>);
    } catch (error, stackTrace) {
      await CrashHandler.capture(error, stackTrace);
      await storage.remove('user_settings');
      return UserSettings.initial();
    }
  }

  Future<void> saveSettings(UserSettings settings) async {
    await storage.write('user_settings', jsonEncode(settings.toJson()));
  }
}

class CacheRepository {
  CacheRepository(this.storage);
  final LocalStorageService storage;

  Future<DataStatus> loadDataStatus() async {
    final raw = await storage.read('data_status');
    if (raw == null || raw.isEmpty) {
      return DataStatus.initial();
    }
    try {
      return DataStatus.fromJson(jsonDecode(raw) as Map<String, dynamic>);
    } catch (error, stackTrace) {
      await CrashHandler.capture(error, stackTrace);
      await storage.remove('data_status');
      return DataStatus.initial();
    }
  }

  Future<void> saveDataStatus(DataStatus status) async {
    await storage.write('data_status', jsonEncode(status.toJson()));
  }

  Future<NotificationScheduleInfo> loadNotificationInfo() async {
    final raw = await storage.read('notification_info');
    if (raw == null || raw.isEmpty) {
      return NotificationScheduleInfo.initial();
    }
    try {
      return NotificationScheduleInfo.fromJson(jsonDecode(raw) as Map<String, dynamic>);
    } catch (error, stackTrace) {
      await CrashHandler.capture(error, stackTrace);
      await storage.remove('notification_info');
      return NotificationScheduleInfo.initial();
    }
  }

  Future<void> saveNotificationInfo(NotificationScheduleInfo info) async {
    await storage.write('notification_info', jsonEncode(info.toJson()));
  }

  Future<void> saveRestaurants(List<RestaurantItem> items) async {
    await storage.write('restaurants', jsonEncode(items.map((e) => e.toJson()).toList()));
  }

  Future<List<RestaurantItem>> loadRestaurants() async {
    final raw = await storage.read('restaurants');
    if (raw == null || raw.isEmpty) {
      return const [];
    }
    try {
      final list = jsonDecode(raw) as List<dynamic>;
      return list.map((e) => RestaurantItem.fromJson(e as Map<String, dynamic>)).toList();
    } catch (error, stackTrace) {
      await CrashHandler.capture(error, stackTrace);
      await storage.remove('restaurants');
      return const [];
    }
  }

  Future<void> saveMenuCache(String restaurantId, WeeklyMenu weeklyMenu, List<DailyMenuItem> items) async {
    await storage.write('weekly_menu_$restaurantId', jsonEncode(weeklyMenu.toJson()));
    await storage.write('daily_menu_$restaurantId', jsonEncode(items.map((e) => e.toJson()).toList()));
  }

  Future<(WeeklyMenu?, List<DailyMenuItem>)> loadMenuCache(String restaurantId) async {
    try {
      final weeklyRaw = await storage.read('weekly_menu_$restaurantId');
      final dailyRaw = await storage.read('daily_menu_$restaurantId');
      WeeklyMenu? weekly;
      List<DailyMenuItem> items = const [];
      if (weeklyRaw != null && weeklyRaw.isNotEmpty) {
        weekly = WeeklyMenu.fromJson(jsonDecode(weeklyRaw) as Map<String, dynamic>);
      }
      if (dailyRaw != null && dailyRaw.isNotEmpty) {
        final list = jsonDecode(dailyRaw) as List<dynamic>;
        items = list.map((e) => DailyMenuItem.fromJson(e as Map<String, dynamic>)).toList();
      }
      return (weekly, items);
    } catch (error, stackTrace) {
      await CrashHandler.capture(error, stackTrace);
      await storage.remove('weekly_menu_$restaurantId');
      await storage.remove('daily_menu_$restaurantId');
      return (null, <DailyMenuItem>[]);
    }
  }
}

class PermissionService {
  static const MethodChannel _channel = MethodChannel('kangwon_meal_menu/permissions');

  Future<bool> isNotificationGranted() async {
    try {
      return await _channel.invokeMethod<bool>('isNotificationGranted') ?? false;
    } catch (error, stackTrace) {
      await CrashHandler.capture(error, stackTrace);
      return false;
    }
  }

  Future<bool> requestNotificationPermission() async {
    try {
      return await _channel.invokeMethod<bool>('requestNotificationPermission') ?? false;
    } catch (error, stackTrace) {
      await CrashHandler.capture(error, stackTrace);
      rethrow;
    }
  }

  Future<void> openAppSettings() async {
    try {
      await _channel.invokeMethod('openAppSettings');
    } catch (error, stackTrace) {
      await CrashHandler.capture(error, stackTrace);
    }
  }
}

class NotificationService {
  static const MethodChannel _channel = MethodChannel('kangwon_meal_menu/notifications');

  Future<void> initializeChannel() async {
    try {
      await _channel.invokeMethod('initializeChannel', {
        'channelId': 'meal_notifications',
        'channelName': '학식 메뉴 알림',
        'channelDescription': '선택한 식당의 오늘 메뉴를 알려줍니다.',
      });
    } catch (error, stackTrace) {
      await CrashHandler.capture(error, stackTrace);
    }
  }

  Future<void> scheduleDailyNotification({
    required String time,
    required String title,
    required String body,
  }) async {
    try {
      await _channel.invokeMethod('scheduleDailyNotification', {
        'time': time,
        'title': title,
        'body': body,
      });
    } catch (error, stackTrace) {
      await CrashHandler.capture(error, stackTrace);
      rethrow;
    }
  }

  Future<void> cancelAll() async {
    try {
      await _channel.invokeMethod('cancelAll');
    } catch (error, stackTrace) {
      await CrashHandler.capture(error, stackTrace);
    }
  }
}

class BackgroundTaskService {
  static const MethodChannel _channel = MethodChannel('kangwon_meal_menu/background');

  Future<void> registerMorningRefresh() async {
    try {
      await _channel.invokeMethod('registerMorningRefresh');
    } catch (error, stackTrace) {
      await CrashHandler.capture(error, stackTrace);
    }
  }
}

class SourceVerificationResult {
  final String status;
  final String message;

  const SourceVerificationResult({required this.status, required this.message});
}

class SourceVerificationService {
  Future<SourceVerificationResult> verify(String html) async {
    try {
      final hasTable = html.contains('<table');
      final hasMigrationNotice = html.contains('점검') || html.contains('변경') || html.contains('안내');
      if (!hasTable) {
        return const SourceVerificationResult(status: '실패', message: '공식 페이지에서 표를 찾지 못했습니다. 구조 변경 가능성이 있습니다.');
      }
      if (hasMigrationNotice) {
        return const SourceVerificationResult(status: '부분유효', message: '공지 또는 구조 변경 가능성 문구가 감지되었습니다.');
      }
      return const SourceVerificationResult(status: '유효', message: '공식 페이지 표 구조를 확인했습니다.');
    } catch (error, stackTrace) {
      await CrashHandler.capture(error, stackTrace);
      return const SourceVerificationResult(status: '실패', message: '소스 검증 중 오류가 발생했습니다.');
    }
  }
}

class HtmlMenuParser {
  List<RestaurantItem> parseRestaurants(String html) {
    final tables = _extractTables(html);
    final items = <RestaurantItem>[];
    for (var i = 0; i < tables.length; i++) {
      final source = 'table[$i]';
      final text = _stripHtml(tables[i]);
      final visible = text.trim().isNotEmpty;
      final name = _guessRestaurantName(text, i);
      items.add(RestaurantItem(
        restaurantId: source,
        restaurantName: name,
        isVisible: visible,
        sourceLocation: source,
      ));
    }
    return items.where((e) => e.isVisible).toList();
  }

  ({WeeklyMenu? weeklyMenu, List<DailyMenuItem> items, String validationStatus}) parseWeeklyMenu({
    required String html,
    required String restaurantId,
  }) {
    final tables = _extractTables(html);
    final index = _tableIndexFromSource(restaurantId);
    if (index == null || index < 0 || index >= tables.length) {
      return (weeklyMenu: null, items: const [], validationStatus: '실패');
    }

    final tableHtml = tables[index];
    final rows = _extractRows(tableHtml);
    if (rows.isEmpty) {
      return (weeklyMenu: null, items: const [], validationStatus: '실패');
    }

    final headerCells = _extractCells(rows.first).map(_stripHtml).toList();
    final items = <DailyMenuItem>[];
    for (final row in rows.skip(1)) {
      final cells = _extractCells(row).map((e) => _normalizeMenu(_stripHtml(e))).toList();
      if (cells.isEmpty) {
        continue;
      }
      final mealType = cells.isNotEmpty ? cells.first : null;
      if (headerCells.length > 1 && cells.length > 1) {
        for (var i = 1; i < cells.length; i++) {
          final menuBody = cells[i].trim();
          if (menuBody.isEmpty) {
            continue;
          }
          final dateLabel = i < headerCells.length ? headerCells[i] : null;
          items.add(DailyMenuItem(
            restaurantId: restaurantId,
            mealType: mealType,
            dateLabel: dateLabel,
            menuBody: menuBody,
            isToday: _isToday(dateLabel),
          ));
        }
      } else if (cells.length > 1) {
        for (var i = 1; i < cells.length; i++) {
          final menuBody = cells[i].trim();
          if (menuBody.isEmpty) {
            continue;
          }
          items.add(DailyMenuItem(
            restaurantId: restaurantId,
            mealType: mealType,
            dateLabel: null,
            menuBody: menuBody,
            isToday: false,
          ));
        }
      }
    }

    final nonEmptyMenus = items.where((e) => e.menuBody.trim().isNotEmpty).length;
    final distinctDates = items.map((e) => e.dateLabel).whereType<String>().toSet().length;
    final validationStatus = nonEmptyMenus == 0
        ? '실패'
        : (distinctDates >= 1 ? '유효' : '부분유효');

    return (
      weeklyMenu: WeeklyMenu(
        restaurantId: restaurantId,
        fetchedAt: DateTime.now(),
        sourceUrl: SourceConstants.primaryUrl,
        validationStatus: validationStatus,
      ),
      items: items,
      validationStatus: validationStatus,
    );
  }

  List<String> _extractTables(String html) {
    final regex = RegExp(r'<table[\\s\\S]*?</table>', caseSensitive: false);
    return regex.allMatches(html).map((e) => e.group(0) ?? '').where((e) => e.isNotEmpty).toList();
  }

  List<String> _extractRows(String tableHtml) {
    final regex = RegExp(r'<tr[\\s\\S]*?</tr>', caseSensitive: false);
    return regex.allMatches(tableHtml).map((e) => e.group(0) ?? '').where((e) => e.isNotEmpty).toList();
  }

  List<String> _extractCells(String rowHtml) {
    final regex = RegExp(r'<t[hd][\\s\\S]*?</t[hd]>', caseSensitive: false);
    return regex.allMatches(rowHtml).map((e) => e.group(0) ?? '').where((e) => e.isNotEmpty).toList();
  }

  String _stripHtml(String input) {
    return input
        .replaceAll(RegExp(r'<br\\s*/?>', caseSensitive: false), '\n')
        .replaceAll(RegExp(r'<[^>]*>'), ' ')
        .replaceAll('&nbsp;', ' ')
        .replaceAll(RegExp(r'\\s+'), ' ')
        .trim();
  }

  String _normalizeMenu(String input) {
    return input.replaceAll(RegExp(r'\\s+'), ' ').trim();
  }

  String _guessRestaurantName(String text, int index) {
    final lines = text.split(RegExp(r'(?<=[가-힣])\s{2,}|\n')).where((e) => e.trim().isNotEmpty).toList();
    final first = lines.isNotEmpty ? lines.first.trim() : '';
    if (first.isNotEmpty && first.length <= 20) {
      return first;
    }
    return '식당 ${index + 1}';
  }

  int? _tableIndexFromSource(String source) {
    final match = RegExp(r'table\\[(\\d+)\\]').firstMatch(source);
    return match == null ? null : int.tryParse(match.group(1) ?? '');
  }

  bool _isToday(String? dateLabel) {
    if (dateLabel == null) {
      return false;
    }
    final match = RegExp(r'(\\d{1,2})\\.(\\d{1,2})').firstMatch(dateLabel);
    if (match == null) {
      return false;
    }
    final month = int.tryParse(match.group(1) ?? '');
    final day = int.tryParse(match.group(2) ?? '');
    if (month == null || day == null) {
      return false;
    }
    final now = DateTime.now();
    return now.month == month && now.day == day;
  }
}

class MenuFetchService {
  Future<String> fetchHtml() async {
    try {
      final response = await http.get(Uri.parse(SourceConstants.primaryUrl)).timeout(const Duration(seconds: 15));
      if (response.statusCode < 200 || response.statusCode >= 300) {
        throw HttpException('비정상 응답: ${response.statusCode}');
      }
      return utf8.decode(response.bodyBytes);
    } catch (error, stackTrace) {
      await CrashHandler.capture(error, stackTrace);
      rethrow;
    }
  }
}

class AppController extends ChangeNotifier {
  AppController()
      : storage = LocalStorageService(),
        settingsRepository = SettingsRepository(LocalStorageService()),
        cacheRepository = CacheRepository(LocalStorageService()),
        permissionService = PermissionService(),
        notificationService = NotificationService(),
        backgroundTaskService = BackgroundTaskService(),
        menuFetchService = MenuFetchService(),
        parser = HtmlMenuParser(),
        verificationService = SourceVerificationService();

  final LocalStorageService storage;
  final SettingsRepository settingsRepository;
  final CacheRepository cacheRepository;
  final PermissionService permissionService;
  final NotificationService notificationService;
  final BackgroundTaskService backgroundTaskService;
  final MenuFetchService menuFetchService;
  final HtmlMenuParser parser;
  final SourceVerificationService verificationService;

  AppStateModel state = AppStateModel.initial();

  Future<void> initialize() async {
    try {
      await notificationService.initializeChannel();
      final settings = await settingsRepository.loadSettings();
      final dataStatus = await cacheRepository.loadDataStatus();
      final notificationInfo = await cacheRepository.loadNotificationInfo();
      final restaurants = await cacheRepository.loadRestaurants();
      final permissionGranted = await permissionService.isNotificationGranted();

      WeeklyMenu? weeklyMenu;
      List<DailyMenuItem> menuItems = const [];
      if (settings.selectedRestaurantId != null) {
        final cache = await cacheRepository.loadMenuCache(settings.selectedRestaurantId!);
        weeklyMenu = cache.$1;
        menuItems = cache.$2;
      }

      state = state.copyWith(
        userSettings: settings,
        dataStatus: dataStatus,
        notificationInfo: notificationInfo,
        restaurants: restaurants,
        weeklyMenu: weeklyMenu,
        menuItems: menuItems,
        notificationPermissionGranted: permissionGranted,
      );
      notifyListeners();

      if (restaurants.isEmpty) {
        await loadRestaurants();
      }
      if (settings.selectedRestaurantId != null) {
        unawaited(refreshSelectedRestaurant(showSuccessMessage: false, background: true));
      }
    } catch (error, stackTrace) {
      await CrashHandler.capture(error, stackTrace);
      rethrow;
    }
  }

  Future<void> loadRestaurants() async {
    try {
      final html = await menuFetchService.fetchHtml();
      final restaurants = parser.parseRestaurants(html);
      await cacheRepository.saveRestaurants(restaurants);
      state = state.copyWith(restaurants: restaurants);
      notifyListeners();
    } catch (error, stackTrace) {
      await CrashHandler.capture(error, stackTrace);
      state = state.copyWith(transientMessage: '식당 목록을 불러오지 못했습니다. 공식 페이지를 확인해 주세요.');
      notifyListeners();
    }
  }

  Future<void> selectRestaurantTemporarily(RestaurantItem item) async {
    final updated = state.userSettings.copyWith(
      selectedRestaurantId: item.restaurantId,
      selectedRestaurantName: item.restaurantName,
    );
    state = state.copyWith(userSettings: updated);
    notifyListeners();
  }

  Future<void> completeOnboarding({
    required String notificationTime,
    required bool notificationsEnabled,
  }) async {
    try {
      final updatedSettings = state.userSettings.copyWith(
        notificationTime: notificationTime,
        notificationsEnabled: notificationsEnabled,
        onboardingCompleted: true,
      );
      await settingsRepository.saveSettings(updatedSettings);
      state = state.copyWith(userSettings: updatedSettings);
      notifyListeners();
      await _recalculateSchedules();
      await refreshSelectedRestaurant(showSuccessMessage: false, background: false);
    } catch (error, stackTrace) {
      await CrashHandler.capture(error, stackTrace);
      rethrow;
    }
  }

  Future<void> updateNotificationSettings({
    required String notificationTime,
    required bool notificationsEnabled,
  }) async {
    try {
      final updated = state.userSettings.copyWith(
        notificationTime: notificationTime,
        notificationsEnabled: notificationsEnabled,
      );
      await settingsRepository.saveSettings(updated);
      state = state.copyWith(userSettings: updated);
      notifyListeners();
      await _recalculateSchedules();
    } catch (error, stackTrace) {
      await CrashHandler.capture(error, stackTrace);
      state = state.copyWith(transientMessage: '알림 설정 저장에 실패했습니다.');
      notifyListeners();
    }
  }

  Future<void> updateRestaurant(RestaurantItem item) async {
    try {
      final updated = state.userSettings.copyWith(
        selectedRestaurantId: item.restaurantId,
        selectedRestaurantName: item.restaurantName,
      );
      await settingsRepository.saveSettings(updated);
      state = state.copyWith(
        userSettings: updated,
        weeklyMenu: null,
        menuItems: const [],
        transientMessage: '식당을 변경했습니다. 새 식당 기준으로 다시 조회합니다.',
      );
      notifyListeners();
      await refreshSelectedRestaurant(showSuccessMessage: false, background: false);
      await _recalculateSchedules();
    } catch (error, stackTrace) {
      await CrashHandler.capture(error, stackTrace);
    }
  }

  Future<bool> requestNotificationPermissionWithPrompt() async {
    try {
      final granted = await permissionService.requestNotificationPermission();
      state = state.copyWith(notificationPermissionGranted: granted);
      notifyListeners();
      if (!granted) {
        state = state.copyWith(transientMessage: '알림 권한이 없어도 메뉴 조회는 계속 사용할 수 있습니다.');
        notifyListeners();
      }
      return granted;
    } catch (error, stackTrace) {
      await CrashHandler.capture(error, stackTrace);
      state = state.copyWith(transientMessage: '권한 요청 중 오류가 발생했습니다. 메뉴 조회는 계속 사용할 수 있습니다.');
      notifyListeners();
      return false;
    }
  }

  Future<void> refreshPermissionStatus() async {
    final granted = await permissionService.isNotificationGranted();
    state = state.copyWith(notificationPermissionGranted: granted);
    notifyListeners();
  }

  Future<void> refreshSelectedRestaurant({required bool showSuccessMessage, required bool background}) async {
    if (state.refreshInFlight) {
      if (!background) {
        state = state.copyWith(transientMessage: '이미 새로고침이 진행 중입니다.');
        notifyListeners();
      }
      return;
    }
    final restaurantId = state.userSettings.selectedRestaurantId;
    if (restaurantId == null) {
      return;
    }

    state = state.copyWith(refreshInFlight: true);
    notifyListeners();

    try {
      final attemptAt = DateTime.now();
      final html = await menuFetchService.fetchHtml();
      final verification = await verificationService.verify(html);
      final parsed = parser.parseWeeklyMenu(html: html, restaurantId: restaurantId);

      if (parsed.items.isEmpty || parsed.weeklyMenu == null || parsed.validationStatus == '실패') {
        final updatedStatus = state.dataStatus.copyWith(
          lastAttemptAt: attemptAt,
          lastErrorCode: parsed.items.isEmpty ? '데이터없음' : '검증실패',
          errorMessage: parsed.items.isEmpty
              ? '선택한 식당의 메뉴가 아직 게시되지 않았거나 찾을 수 없습니다.'
              : '공식 페이지 구조 변경 가능성으로 메뉴를 검증하지 못했습니다.',
          hasCache: state.menuItems.isNotEmpty,
          sourceValidationResult: verification.status,
        );
        await cacheRepository.saveDataStatus(updatedStatus);
        state = state.copyWith(
          dataStatus: updatedStatus,
          refreshInFlight: false,
          transientMessage: '새 데이터를 불러오지 못해 마지막 성공 캐시를 유지합니다.',
        );
        notifyListeners();
        return;
      }

      await cacheRepository.saveMenuCache(restaurantId, parsed.weeklyMenu!, parsed.items);
      final updatedStatus = state.dataStatus.copyWith(
        lastSuccessAt: DateTime.now(),
        lastAttemptAt: attemptAt,
        lastErrorCode: null,
        errorMessage: verification.message,
        hasCache: true,
        sourceValidationResult: parsed.validationStatus == '유효' ? verification.status : '부분유효',
        clearError: true,
      );
      await cacheRepository.saveDataStatus(updatedStatus);
      state = state.copyWith(
        weeklyMenu: parsed.weeklyMenu,
        menuItems: parsed.items,
        dataStatus: updatedStatus,
        refreshInFlight: false,
        transientMessage: showSuccessMessage ? '메뉴를 새로고침했습니다.' : null,
      );
      notifyListeners();
      await _recalculateSchedules();
    } on TimeoutException catch (error, stackTrace) {
      await CrashHandler.capture(error, stackTrace);
      await _handleRefreshFailure('네트워크오류', '네트워크 상태를 확인한 뒤 다시 시도해 주세요.');
    } on SocketException catch (error, stackTrace) {
      await CrashHandler.capture(error, stackTrace);
      await _handleRefreshFailure('네트워크오류', '인터넷 연결이 원활하지 않습니다.');
    } catch (error, stackTrace) {
      await CrashHandler.capture(error, stackTrace);
      await _handleRefreshFailure('파싱실패', '공식 페이지 구조 변경 가능성으로 메뉴를 불러오지 못했습니다.');
    }
  }

  Future<void> _handleRefreshFailure(String code, String message) async {
    final updatedStatus = state.dataStatus.copyWith(
      lastAttemptAt: DateTime.now(),
      lastErrorCode: code,
      errorMessage: message,
      hasCache: state.menuItems.isNotEmpty,
      sourceValidationResult: state.dataStatus.sourceValidationResult,
    );
    await cacheRepository.saveDataStatus(updatedStatus);
    state = state.copyWith(
      dataStatus: updatedStatus,
      refreshInFlight: false,
      transientMessage: state.menuItems.isNotEmpty
          ? '새로고침에 실패하여 마지막 성공 캐시를 표시합니다.'
          : message,
    );
    notifyListeners();
  }

  Future<void> _recalculateSchedules() async {
    try {
      await backgroundTaskService.registerMorningRefresh();
      final settings = state.userSettings;
      if (!settings.notificationsEnabled ||
          settings.notificationTime == null ||
          settings.selectedRestaurantName == null ||
          !state.notificationPermissionGranted) {
        await notificationService.cancelAll();
        final info = state.notificationInfo.copyWith(
          scheduledTime: settings.notificationTime,
          nextNotificationAt: null,
          requiresBootRestore: true,
        );
        await cacheRepository.saveNotificationInfo(info);
        state = state.copyWith(notificationInfo: info);
        notifyListeners();
        return;
      }

      final todayMenus = state.menuItems.where((e) => e.isToday).toList();
      final body = todayMenus.isEmpty
          ? '오늘 메뉴 정보가 없거나 최신 갱신이 필요합니다.'
          : todayMenus.map((e) => '${e.mealType ?? '메뉴'} ${e.menuBody}').join(' / ');
      await notificationService.scheduleDailyNotification(
        time: settings.notificationTime!,
        title: '${settings.selectedRestaurantName!} 오늘의 학식',
        body: body,
      );
      final nextAt = _nextDateTimeFromTime(settings.notificationTime!);
      final info = state.notificationInfo.copyWith(
        scheduledTime: settings.notificationTime,
        nextNotificationAt: nextAt,
        requiresBootRestore: false,
      );
      await cacheRepository.saveNotificationInfo(info);
      state = state.copyWith(notificationInfo: info);
      notifyListeners();
    } catch (error, stackTrace) {
      await CrashHandler.capture(error, stackTrace);
      final reverted = state.userSettings.copyWith(notificationsEnabled: false);
      await settingsRepository.saveSettings(reverted);
      final info = state.notificationInfo.copyWith(requiresBootRestore: true, nextNotificationAt: null);
      await cacheRepository.saveNotificationInfo(info);
      state = state.copyWith(
        userSettings: reverted,
        notificationInfo: info,
        transientMessage: '알림 예약에 실패했습니다. 메뉴 조회는 계속 사용할 수 있습니다.',
      );
      notifyListeners();
    }
  }

  DateTime _nextDateTimeFromTime(String time) {
    final parts = time.split(':');
    final hour = int.tryParse(parts.first) ?? 8;
    final minute = parts.length > 1 ? int.tryParse(parts[1]) ?? 0 : 0;
    final now = DateTime.now();
    var next = DateTime(now.year, now.month, now.day, hour, minute);
    if (!next.isAfter(now)) {
      next = next.add(const Duration(days: 1));
    }
    return next;
  }

  void clearTransientMessage() {
    state = state.copyWith(clearTransientMessage: true);
    notifyListeners();
  }
}

class OnboardingScreen extends StatefulWidget {
  const OnboardingScreen({super.key, required this.controller});
  final AppController controller;

  @override
  State<OnboardingScreen> createState() => _OnboardingScreenState();
}

class _OnboardingScreenState extends State<OnboardingScreen> {
  RestaurantItem? selected;

  @override
  void initState() {
    super.initState();
    if (widget.controller.state.restaurants.isEmpty) {
      unawaited(widget.controller.loadRestaurants());
    }
  }

  @override
  Widget build(BuildContext context) {
    return AnimatedBuilder(
      animation: widget.controller,
      builder: (context, _) {
        final restaurants = widget.controller.state.restaurants.where((e) => e.isVisible).toList();
        return Scaffold(
          appBar: AppBar(title: const Text('식당 선택')),
          body: SingleChildScrollView(
            padding: const EdgeInsets.all(16),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                const Card(
                  child: Padding(
                    padding: EdgeInsets.all(16),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text('강원대학교 춘천캠퍼스 학식 메뉴 앱', style: TextStyle(fontSize: 18, fontWeight: FontWeight.bold)),
                        SizedBox(height: 8),
                        Text('공식 웹페이지 기반으로 식단을 조회합니다. 페이지 구조 변경이나 네트워크 상태에 따라 자동 새로고침이 지연될 수 있습니다.'),
                      ],
                    ),
                  ),
                ),
                const SizedBox(height: 12),
                if (restaurants.isEmpty)
                  Card(
                    child: Padding(
                      padding: const EdgeInsets.all(16),
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.stretch,
                        children: [
                          const Text('선택 가능한 식당 목록이 아직 없습니다.'),
                          const SizedBox(height: 8),
                          ElevatedButton(
                            key: UniqueKey(),
                            onPressed: () => widget.controller.loadRestaurants(),
                            child: const Text('식당 목록 다시 불러오기'),
                          ),
                        ],
                      ),
                    ),
                  )
                else
                  ...restaurants.map(
                    (item) => RadioListTile<RestaurantItem>(
                      value: item,
                      groupValue: selected,
                      title: Text(item.restaurantName.isEmpty ? item.restaurantId : item.restaurantName),
                      subtitle: Text('원본 위치: ${item.sourceLocation}'),
                      onChanged: (value) {
                        setState(() {
                          selected = value;
                        });
                      },
                    ),
                  ),
                const SizedBox(height: 16),
                ElevatedButton(
                  key: UniqueKey(),
                  onPressed: selected == null
                      ? null
                      : () async {
                          await widget.controller.selectRestaurantTemporarily(selected!);
                          if (!mounted) {
                            return;
                          }
                          Navigator.of(context).push(
                            MaterialPageRoute(
                              builder: (_) => NotificationTimeScreen(controller: widget.controller),
                            ),
                          );
                        },
                  child: const Text('다음'),
                ),
              ],
            ),
          ),
        );
      },
    );
  }
}

class NotificationTimeScreen extends StatefulWidget {
  const NotificationTimeScreen({super.key, required this.controller});
  final AppController controller;

  @override
  State<NotificationTimeScreen> createState() => _NotificationTimeScreenState();
}

class _NotificationTimeScreenState extends State<NotificationTimeScreen> {
  TimeOfDay selectedTime = const TimeOfDay(hour: 8, minute: 0);
  bool notificationsEnabled = true;

  Future<void> _pickTime() async {
    final picked = await showTimePicker(context: context, initialTime: selectedTime);
    if (picked != null) {
      setState(() {
        selectedTime = picked;
      });
    }
  }

  Future<void> _start() async {
    var allowNotifications = notificationsEnabled;
    if (notificationsEnabled) {
      final confirmed = await showDialog<bool>(
            context: context,
            builder: (context) => AlertDialog(
              title: const Text('알림 권한 안내'),
              content: const Text('선택한 식당의 오늘 메뉴를 지정 시간에 알려드리기 위해 알림 권한이 필요합니다. 거부해도 메뉴 조회는 계속 사용할 수 있습니다.'),
              actions: [
                TextButton(
                  onPressed: () => Navigator.of(context).pop(false),
                  child: const Text('나중에'),
                ),
                FilledButton(
                  onPressed: () => Navigator.of(context).pop(true),
                  child: const Text('권한 요청'),
                ),
              ],
            ),
          ) ??
          false;
      if (confirmed) {
        final granted = await widget.controller.requestNotificationPermissionWithPrompt();
        allowNotifications = granted;
      } else {
        allowNotifications = false;
      }
    }

    final time = '${selectedTime.hour.toString().padLeft(2, '0')}:${selectedTime.minute.toString().padLeft(2, '0')}';
    await widget.controller.completeOnboarding(
      notificationTime: time,
      notificationsEnabled: allowNotifications,
    );
    if (!mounted) {
      return;
    }
    Navigator.of(context).popUntil((route) => route.isFirst);
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('알림 시간 설정')),
      body: SingleChildScrollView(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            const Card(
              child: Padding(
                padding: EdgeInsets.all(16),
                child: Text('알림은 선택한 식당의 오늘 메뉴를 기준으로 발송됩니다. 시스템 상황에 따라 실제 발송 시점이나 자동 새로고침 시점이 다소 지연될 수 있습니다.'),
              ),
            ),
            const SizedBox(height: 12),
            ListTile(
              title: const Text('알림 시간'),
              subtitle: Text(selectedTime.format(context)),
              trailing: ElevatedButton(
                key: UniqueKey(),
                onPressed: _pickTime,
                child: const Text('시간 선택'),
              ),
            ),
            SwitchListTile(
              value: notificationsEnabled,
              onChanged: (value) {
                setState(() {
                  notificationsEnabled = value;
                });
              },
              title: const Text('알림 활성화'),
              subtitle: const Text('거부해도 메뉴 조회 기능은 계속 사용할 수 있습니다.'),
            ),
            const SizedBox(height: 16),
            ElevatedButton(
              key: UniqueKey(),
              onPressed: _start,
              child: const Text('시작하기'),
            ),
          ],
        ),
      ),
    );
  }
}

class HomeShellScreen extends StatefulWidget {
  const HomeShellScreen({super.key, required this.controller});
  final AppController controller;

  @override
  State<HomeShellScreen> createState() => _HomeShellScreenState();
}

class _HomeShellScreenState extends State<HomeShellScreen> {
  int index = 0;

  @override
  Widget build(BuildContext context) {
    return AnimatedBuilder(
      animation: widget.controller,
      builder: (context, _) {
        WidgetsBinding.instance.addPostFrameCallback((_) {
          final message = widget.controller.state.transientMessage;
          if (message != null && mounted) {
            ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(message)));
            widget.controller.clearTransientMessage();
          }
        });

        final pages = [
          TodayMenuScreen(controller: widget.controller),
          WeeklyMenuScreen(controller: widget.controller),
        ];
        return Scaffold(
          appBar: AppBar(
            title: const Text('강원대 학식 메뉴'),
            actions: [
              IconButton(
                key: UniqueKey(),
                onPressed: () {
                  Navigator.of(context).push(
                    MaterialPageRoute(builder: (_) => DataStatusScreen(controller: widget.controller)),
                  );
                },
                icon: const Icon(Icons.analytics_outlined),
                tooltip: '데이터 상태',
              ),
              IconButton(
                key: UniqueKey(),
                onPressed: () {
                  Navigator.of(context).push(
                    MaterialPageRoute(builder: (_) => SettingsScreen(controller: widget.controller)),
                  );
                },
                icon: const Icon(Icons.settings_outlined),
                tooltip: '설정',
              ),
            ],
          ),
          body: pages[index],
          bottomNavigationBar: NavigationBar(
            selectedIndex: index,
            onDestinationSelected: (value) {
              setState(() {
                index = value;
              });
            },
            destinations: const [
              NavigationDestination(icon: Icon(Icons.home_outlined), label: '홈'),
              NavigationDestination(icon: Icon(Icons.calendar_view_week_outlined), label: '주간 메뉴'),
            ],
          ),
        );
      },
    );
  }
}

class TodayMenuScreen extends StatelessWidget {
  const TodayMenuScreen({super.key, required this.controller});
  final AppController controller;

  @override
  Widget build(BuildContext context) {
    final state = controller.state;
    final todayMenus = state.menuItems.where((e) => e.isToday).toList();
    return Scaffold(
      body: SingleChildScrollView(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Card(
              child: Padding(
                padding: const EdgeInsets.all(16),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(state.userSettings.selectedRestaurantName ?? '선택된 식당 없음', style: const TextStyle(fontSize: 20, fontWeight: FontWeight.bold)),
                    const SizedBox(height: 8),
                    Text('마지막 갱신: ${_formatDateTime(state.dataStatus.lastSuccessAt)}'),
                    const SizedBox(height: 4),
                    Text('데이터 상태: ${state.dataStatus.sourceValidationResult ?? '확인 전'}'),
                  ],
                ),
              ),
            ),
            const SizedBox(height: 12),
            if (!state.notificationPermissionGranted && state.userSettings.notificationsEnabled)
              const Card(
                child: Padding(
                  padding: EdgeInsets.all(16),
                  child: Text('알림 권한이 없어도 메뉴 조회는 계속 사용할 수 있습니다. 설정 화면에서 다시 허용할 수 있습니다.'),
                ),
              ),
            const SizedBox(height: 12),
            ElevatedButton(
              key: UniqueKey(),
              onPressed: state.refreshInFlight ? null : () => controller.refreshSelectedRestaurant(showSuccessMessage: true, background: false),
              child: Text(state.refreshInFlight ? '새로고침 진행 중' : '수동 새로고침'),
            ),
            const SizedBox(height: 12),
            const Card(
              child: Padding(
                padding: EdgeInsets.all(16),
                child: Text('공식 웹페이지 기반 데이터입니다. 자동 새로고침은 시스템 제약으로 실제 실행 시점이 지연될 수 있습니다.'),
              ),
            ),
            const SizedBox(height: 12),
            if (todayMenus.isEmpty)
              Card(
                child: Padding(
                  padding: const EdgeInsets.all(16),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.stretch,
                    children: [
                      const Text('오늘 메뉴가 없습니다.'),
                      const SizedBox(height: 8),
                      const Text('추정 메뉴는 생성하지 않습니다. 아직 게시되지 않았을 수 있습니다.'),
                      const SizedBox(height: 8),
                      OutlinedButton(
                        key: UniqueKey(),
                        onPressed: () {},
                        child: const Text('주간 메뉴는 아래 탭에서 확인해 주세요'),
                      ),
                    ],
                  ),
                ),
              )
            else
              ...todayMenus.map(
                (item) => Card(
                  child: Padding(
                    padding: const EdgeInsets.all(16),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(item.mealType ?? '메뉴', style: const TextStyle(fontWeight: FontWeight.bold)),
                        const SizedBox(height: 8),
                        Text(item.dateLabel ?? '날짜 정보 없음'),
                        const SizedBox(height: 8),
                        Text(item.menuBody),
                      ],
                    ),
                  ),
                ),
              ),
          ],
        ),
      ),
    );
  }
}

class WeeklyMenuScreen extends StatelessWidget {
  const WeeklyMenuScreen({super.key, required this.controller});
  final AppController controller;

  @override
  Widget build(BuildContext context) {
    final grouped = <String, List<DailyMenuItem>>{};
    final undated = <DailyMenuItem>[];
    for (final item in controller.state.menuItems) {
      if (item.dateLabel == null || item.dateLabel!.isEmpty) {
        undated.add(item);
      } else {
        grouped.putIfAbsent(item.dateLabel!, () => []).add(item);
      }
    }

    return Scaffold(
      body: RefreshIndicator(
        onRefresh: () => controller.refreshSelectedRestaurant(showSuccessMessage: false, background: false),
        child: SingleChildScrollView(
          physics: const AlwaysScrollableScrollPhysics(),
          padding: const EdgeInsets.all(16),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              ElevatedButton(
                key: UniqueKey(),
                onPressed: controller.state.refreshInFlight ? null : () => controller.refreshSelectedRestaurant(showSuccessMessage: true, background: false),
                child: const Text('주간 메뉴 새로고침'),
              ),
              const SizedBox(height: 12),
              if (grouped.isEmpty && undated.isEmpty)
                const Card(
                  child: Padding(
                    padding: EdgeInsets.all(16),
                    child: Text('표시할 주간 메뉴가 없습니다. 공식 페이지 구조 변경 또는 데이터 미게시 상태일 수 있습니다.'),
                  ),
                ),
              ...grouped.entries.map(
                (entry) => Card(
                  color: entry.value.any((e) => e.isToday) ? Theme.of(context).colorScheme.primaryContainer : null,
                  child: Padding(
                    padding: const EdgeInsets.all(16),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(entry.key, style: const TextStyle(fontSize: 18, fontWeight: FontWeight.bold)),
                        const SizedBox(height: 8),
                        ...entry.value.map((item) => Padding(
                              padding: const EdgeInsets.only(bottom: 8),
                              child: Text('${item.mealType ?? '메뉴'}: ${item.menuBody}'),
                            )),
                      ],
                    ),
                  ),
                ),
              ),
              if (undated.isNotEmpty)
                Card(
                  child: Padding(
                    padding: const EdgeInsets.all(16),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        const Text('날짜 정보 누락 항목', style: TextStyle(fontSize: 18, fontWeight: FontWeight.bold)),
                        const SizedBox(height: 8),
                        ...undated.map((item) => Padding(
                              padding: const EdgeInsets.only(bottom: 8),
                              child: Text('${item.mealType ?? '메뉴'}: ${item.menuBody}'),
                            )),
                      ],
                    ),
                  ),
                ),
            ],
          ),
        ),
      ),
    );
  }
}

class DataStatusScreen extends StatelessWidget {
  const DataStatusScreen({super.key, required this.controller});
  final AppController controller;

  @override
  Widget build(BuildContext context) {
    final status = controller.state.dataStatus;
    return Scaffold(
      appBar: AppBar(title: const Text('데이터 상태')),
      body: SingleChildScrollView(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Card(
              child: Padding(
                padding: const EdgeInsets.all(16),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text('마지막 성공 갱신: ${_formatDateTime(status.lastSuccessAt)}'),
                    Text('마지막 시도: ${_formatDateTime(status.lastAttemptAt)}'),
                    Text('마지막 오류 코드: ${status.lastErrorCode ?? '없음'}'),
                    Text('오류 메시지: ${status.errorMessage ?? '없음'}'),
                    Text('캐시 존재 여부: ${status.hasCache ? '있음' : '없음'}'),
                    Text('소스 검증 결과: ${status.sourceValidationResult ?? '확인 전'}'),
                  ],
                ),
              ),
            ),
            const SizedBox(height: 12),
            if (status.sourceValidationResult == '실패')
              const Card(
                child: Padding(
                  padding: EdgeInsets.all(16),
                  child: Text('공식 페이지 구조 변경 가능성으로 일시적으로 메뉴를 불러오지 못할 수 있습니다. 캐시가 있으면 마지막 성공 데이터를 계속 사용합니다.'),
                ),
              ),
            const SizedBox(height: 12),
            ElevatedButton(
              key: UniqueKey(),
              onPressed: controller.state.refreshInFlight ? null : () => controller.refreshSelectedRestaurant(showSuccessMessage: true, background: false),
              child: const Text('검증 및 새로고침 다시 실행'),
            ),
          ],
        ),
      ),
    );
  }
}

class SettingsScreen extends StatefulWidget {
  const SettingsScreen({super.key, required this.controller});
  final AppController controller;

  @override
  State<SettingsScreen> createState() => _SettingsScreenState();
}

class _SettingsScreenState extends State<SettingsScreen> {
  late bool notificationsEnabled;
  late TimeOfDay selectedTime;
  RestaurantItem? selectedRestaurant;

  @override
  void initState() {
    super.initState();
    final settings = widget.controller.state.userSettings;
    notificationsEnabled = settings.notificationsEnabled;
    final parts = (settings.notificationTime ?? '08:00').split(':');
    selectedTime = TimeOfDay(
      hour: int.tryParse(parts.first) ?? 8,
      minute: parts.length > 1 ? int.tryParse(parts[1]) ?? 0 : 0,
    );
    final currentId = settings.selectedRestaurantId;
    selectedRestaurant = widget.controller.state.restaurants.where((e) => e.restaurantId == currentId).cast<RestaurantItem?>().firstWhere((e) => e != null, orElse: () => null);
    unawaited(widget.controller.refreshPermissionStatus());
  }

  Future<void> _pickTime() async {
    final picked = await showTimePicker(context: context, initialTime: selectedTime);
    if (picked != null) {
      setState(() {
        selectedTime = picked;
      });
    }
  }

  Future<void> _save() async {
    if (selectedRestaurant != null && selectedRestaurant!.restaurantId != widget.controller.state.userSettings.selectedRestaurantId) {
      await widget.controller.updateRestaurant(selectedRestaurant!);
    }

    var enable = notificationsEnabled;
    if (enable && !widget.controller.state.notificationPermissionGranted) {
      final confirmed = await showDialog<bool>(
            context: context,
            builder: (context) => AlertDialog(
              title: const Text('알림 권한 안내'),
              content: const Text('학식 메뉴 알림을 보내기 위해 알림 권한이 필요합니다. 거부해도 메뉴 조회는 계속 사용할 수 있습니다.'),
              actions: [
                TextButton(onPressed: () => Navigator.of(context).pop(false), child: const Text('취소')),
                FilledButton(onPressed: () => Navigator.of(context).pop(true), child: const Text('권한 요청')),
              ],
            ),
          ) ??
          false;
      if (confirmed) {
        enable = await widget.controller.requestNotificationPermissionWithPrompt();
      } else {
        enable = false;
      }
    }

    final time = '${selectedTime.hour.toString().padLeft(2, '0')}:${selectedTime.minute.toString().padLeft(2, '0')}';
    await widget.controller.updateNotificationSettings(notificationTime: time, notificationsEnabled: enable);
    if (!mounted) {
      return;
    }
    Navigator.of(context).pop();
  }

  @override
  Widget build(BuildContext context) {
    final state = widget.controller.state;
    return Scaffold(
      appBar: AppBar(title: const Text('설정')),
      body: SingleChildScrollView(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Card(
              child: Padding(
                padding: const EdgeInsets.all(16),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    const Text('선택 식당 변경', style: TextStyle(fontWeight: FontWeight.bold)),
                    const SizedBox(height: 8),
                    DropdownButtonFormField<RestaurantItem>(
                      value: selectedRestaurant,
                      items: state.restaurants
                          .where((e) => e.isVisible)
                          .map((e) => DropdownMenuItem(value: e, child: Text(e.restaurantName.isEmpty ? e.restaurantId : e.restaurantName)))
                          .toList(),
                      onChanged: (value) {
                        setState(() {
                          selectedRestaurant = value;
                        });
                      },
                    ),
                  ],
                ),
              ),
            ),
            const SizedBox(height: 12),
            Card(
              child: Padding(
                padding: const EdgeInsets.all(16),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    const Text('알림 시간 변경', style: TextStyle(fontWeight: FontWeight.bold)),
                    const SizedBox(height: 8),
                    ListTile(
                      contentPadding: EdgeInsets.zero,
                      title: Text(selectedTime.format(context)),
                      trailing: ElevatedButton(
                        key: UniqueKey(),
                        onPressed: _pickTime,
                        child: const Text('시간 선택'),
                      ),
                    ),
                    SwitchListTile(
                      contentPadding: EdgeInsets.zero,
                      value: notificationsEnabled,
                      onChanged: (value) {
                        setState(() {
                          notificationsEnabled = value;
                        });
                      },
                      title: const Text('알림 활성화 여부'),
                    ),
                  ],
                ),
              ),
            ),
            const SizedBox(height: 12),
            Card(
              child: Padding(
                padding: const EdgeInsets.all(16),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text('알림 권한 상태: ${state.notificationPermissionGranted ? '허용됨' : '허용되지 않음'}'),
                    const SizedBox(height: 8),
                    if (!state.notificationPermissionGranted)
                      const Text('알림 없이도 메뉴 조회 기능은 계속 사용할 수 있습니다. 필요하면 다시 권한을 요청하거나 시스템 설정으로 이동해 주세요.'),
                    const SizedBox(height: 8),
                    Wrap(
                      spacing: 8,
                      children: [
                        ElevatedButton(
                          key: UniqueKey(),
                          onPressed: () async {
                            await widget.controller.requestNotificationPermissionWithPrompt();
                            if (mounted) {
                              setState(() {});
                            }
                          },
                          child: const Text('권한 다시 요청'),
                        ),
                        OutlinedButton(
                          key: UniqueKey(),
                          onPressed: () => widget.controller.permissionService.openAppSettings(),
                          child: const Text('시스템 설정 열기'),
                        ),
                      ],
                    ),
                  ],
                ),
              ),
            ),
            const SizedBox(height: 12),
            Card(
              child: Padding(
                padding: const EdgeInsets.all(16),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text('다음 알림 예정 시각: ${_formatDateTime(state.notificationInfo.nextNotificationAt)}'),
                    const SizedBox(height: 8),
                    Text('재부팅 후 재등록 필요 여부: ${state.notificationInfo.requiresBootRestore ? '필요' : '완료'}'),
                    const SizedBox(height: 8),
                    const Text('기기 재부팅 후에는 저장된 설정을 바탕으로 알림 예약과 아침 새로고침 작업을 다시 등록해야 합니다.'),
                  ],
                ),
              ),
            ),
            const SizedBox(height: 16),
            ElevatedButton(
              key: UniqueKey(),
              onPressed: _save,
              child: const Text('설정 저장'),
            ),
          ],
        ),
      ),
    );
  }
}

String _formatDateTime(DateTime? value) {
  if (value == null) {
    return '없음';
  }
  return '${value.year.toString().padLeft(4, '0')}-${value.month.toString().padLeft(2, '0')}-${value.day.toString().padLeft(2, '0')} ${value.hour.toString().padLeft(2, '0')}:${value.minute.toString().padLeft(2, '0')}';
}
