import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import 'crash_handler.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  CrashHandler.initialize("216627_9a9115", "kr.ac.kangwon.hai.kangwonmealmenualert.t216627_9a9115");
  runApp(const MyApp());
}

Future<void> safeRecordError(Object error, StackTrace stackTrace) async {
  try {
    await CrashHandler.report(error.toString(), stackTrace.toString());
  } catch (_) {}
}

class MyApp extends StatelessWidget {
  const MyApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      debugShowCheckedModeBanner: false,
      title: '강원대 학식 알림',
      theme: ThemeData(
        useMaterial3: true,
        colorScheme: ColorScheme.fromSeed(seedColor: Colors.green),
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
  late final HomeViewModel _viewModel;
  bool _booting = true;
  String? _bootError;

  @override
  void initState() {
    super.initState();
    _viewModel = HomeViewModel(
      repository: MenuRepository(
        storage: LocalStorageRepository(),
        crawlerService: MenuCrawlerService(),
      ),
      notificationService: NotificationService(),
      permissionService: PermissionService(),
    );
    _bootstrap();
  }

  Future<void> _bootstrap() async {
    try {
      await _viewModel.bootstrap();
    } catch (error, stackTrace) {
      _bootError = '앱 준비 중 문제가 발생했습니다.';
      await safeRecordError(error, stackTrace);
    } finally {
      if (mounted) {
        setState(() {
          _booting = false;
        });
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    if (_booting) {
      return const Scaffold(
        body: SingleChildScrollView(
          child: SizedBox(
            height: 500,
            child: Center(
              child: CircularProgressIndicator(),
            ),
          ),
        ),
      );
    }

    return HomeShell(
      viewModel: _viewModel,
      bootError: _bootError,
    );
  }
}

class HomeShell extends StatefulWidget {
  const HomeShell({super.key, required this.viewModel, this.bootError});

  final HomeViewModel viewModel;
  final String? bootError;

  @override
  State<HomeShell> createState() => _HomeShellState();
}

class _HomeShellState extends State<HomeShell> {
  @override
  void initState() {
    super.initState();
    widget.viewModel.addListener(_onChanged);
    if (widget.bootError != null && widget.viewModel.stateMessage == null) {
      widget.viewModel.setStateMessage(widget.bootError!);
    }
  }

  @override
  void dispose() {
    widget.viewModel.removeListener(_onChanged);
    super.dispose();
  }

  void _onChanged() {
    if (mounted) {
      setState(() {});
    }
  }

  @override
  Widget build(BuildContext context) {
    final vm = widget.viewModel;
    return Scaffold(
      appBar: AppBar(
        title: const Text('강원대 학식 알림'),
        actions: [
          IconButton(
            key: UniqueKey(),
            onPressed: vm.isLoading ? null : () => vm.refreshMenus(),
            icon: const Icon(Icons.refresh),
            tooltip: '새로고침',
          ),
        ],
      ),
      body: SingleChildScrollView(
        child: Padding(
          padding: const EdgeInsets.all(16),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              StatusSummaryCard(viewModel: vm),
              const SizedBox(height: 16),
              if (vm.currentTabIndex == 0) TodayMenuTab(viewModel: vm),
              if (vm.currentTabIndex == 1) WeekMenuTab(viewModel: vm),
              if (vm.currentTabIndex == 2) NotificationSettingsTab(viewModel: vm),
            ],
          ),
        ),
      ),
      bottomNavigationBar: NavigationBar(
        selectedIndex: vm.currentTabIndex,
        onDestinationSelected: vm.setTabIndex,
        destinations: const [
          NavigationDestination(icon: Icon(Icons.today), label: '오늘 메뉴'),
          NavigationDestination(icon: Icon(Icons.view_week), label: '이번 주'),
          NavigationDestination(icon: Icon(Icons.notifications), label: '알림 설정'),
        ],
      ),
    );
  }
}

class StatusSummaryCard extends StatelessWidget {
  const StatusSummaryCard({super.key, required this.viewModel});

  final HomeViewModel viewModel;

  @override
  Widget build(BuildContext context) {
    final sync = viewModel.syncStatus;
    final cache = viewModel.weeklyMenuCache;
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                const Expanded(
                  child: Text(
                    '데이터 상태',
                    style: TextStyle(fontSize: 18, fontWeight: FontWeight.bold),
                  ),
                ),
                FilledButton.icon(
                  key: UniqueKey(),
                  onPressed: viewModel.isLoading ? null : () => viewModel.refreshMenus(),
                  icon: viewModel.isLoading
                      ? const SizedBox(
                          width: 16,
                          height: 16,
                          child: CircularProgressIndicator(strokeWidth: 2),
                        )
                      : const Icon(Icons.sync),
                  label: const Text('수동 새로고침'),
                ),
              ],
            ),
            const SizedBox(height: 12),
            Text('마지막 저장 시각: ${cache?.savedAt ?? '없음'}'),
            Text('최근 성공 시각: ${sync?.lastSuccessAt ?? '없음'}'),
            Text('최근 시도 시각: ${sync?.lastAttemptAt ?? '없음'}'),
            Text('데이터 출처: ${cache?.sourceMetadata ?? '로컬 저장소 중심'}'),
            if (sync != null && !sync.isSuccess) ...[
              const SizedBox(height: 8),
              Text('최근 실패: ${sync.failureReasonSummary ?? '알 수 없는 문제'}'),
              const Text('현재 화면은 마지막 저장 데이터일 수 있습니다.'),
            ],
            if (viewModel.showStaleBadge) ...[
              const SizedBox(height: 8),
              const Chip(label: Text('비최신 데이터 표시 중')),
            ],
            if (viewModel.stateMessage != null) ...[
              const SizedBox(height: 8),
              Text(viewModel.stateMessage!),
            ],
          ],
        ),
      ),
    );
  }
}

class TodayMenuTab extends StatelessWidget {
  const TodayMenuTab({super.key, required this.viewModel});

  final HomeViewModel viewModel;

  @override
  Widget build(BuildContext context) {
    final todayMenus = viewModel.todayMenus;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Card(
          child: Padding(
            padding: const EdgeInsets.all(16),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  '오늘 메뉴 · ${AppDateUtils.todayKey()}',
                  style: const TextStyle(fontSize: 18, fontWeight: FontWeight.bold),
                ),
                if (viewModel.showStaleBadge) ...[
                  const SizedBox(height: 8),
                  const Chip(label: Text('마지막 저장 데이터')),
                ],
              ],
            ),
          ),
        ),
        const SizedBox(height: 12),
        if (todayMenus.isEmpty)
          EmptyStateView(
            title: viewModel.weeklyMenuCache == null ? '저장된 메뉴가 없습니다.' : '오늘 등록된 메뉴가 없습니다.',
            message: '상단의 새로고침 버튼으로 다시 시도해 주세요.',
            buttonLabel: '다시 시도',
            onPressed: () => viewModel.refreshMenus(),
          )
        else
          ...todayMenus.map(
            (item) => Card(
              child: ListTile(
                title: Text(item.menuName),
                subtitle: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    if ((item.restaurantOrCategoryName ?? '').isNotEmpty) Text(item.restaurantOrCategoryName!),
                    if ((item.mealTimeOrMealType ?? '').isNotEmpty) Text(item.mealTimeOrMealType!),
                  ],
                ),
              ),
            ),
          ),
      ],
    );
  }
}

class WeekMenuTab extends StatelessWidget {
  const WeekMenuTab({super.key, required this.viewModel});

  final HomeViewModel viewModel;

  @override
  Widget build(BuildContext context) {
    final sections = viewModel.weekSections;
    if (sections.isEmpty) {
      return EmptyStateView(
        title: '이번 주 메뉴가 없습니다.',
        message: '저장된 데이터가 없으면 새로고침이 필요합니다.',
        buttonLabel: '새로고침',
        onPressed: () => viewModel.refreshMenus(),
      );
    }

    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: sections.map((section) {
        return Card(
          child: Padding(
            padding: const EdgeInsets.all(16),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  '${section.group.date} ${section.group.weekday ?? ''}'.trim(),
                  style: const TextStyle(fontSize: 18, fontWeight: FontWeight.bold),
                ),
                const SizedBox(height: 12),
                if (section.items.isEmpty)
                  const Text('등록된 메뉴가 없습니다.')
                else
                  ...section.items.map(
                    (item) => Padding(
                      padding: const EdgeInsets.only(bottom: 8),
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Text(item.menuName),
                          if ((item.restaurantOrCategoryName ?? '').isNotEmpty)
                            Text(item.restaurantOrCategoryName!, style: Theme.of(context).textTheme.bodySmall),
                          if ((item.mealTimeOrMealType ?? '').isNotEmpty)
                            Text(item.mealTimeOrMealType!, style: Theme.of(context).textTheme.bodySmall),
                        ],
                      ),
                    ),
                  ),
              ],
            ),
          ),
        );
      }).toList(),
    );
  }
}

class NotificationSettingsTab extends StatelessWidget {
  const NotificationSettingsTab({super.key, required this.viewModel});

  final HomeViewModel viewModel;

  @override
  Widget build(BuildContext context) {
    final settings = viewModel.notificationSettings;
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        const Card(
          child: Padding(
            padding: EdgeInsets.all(16),
            child: Text('매일 저장된 오늘 메뉴 요약을 알림으로 받을 수 있습니다.'),
          ),
        ),
        const SizedBox(height: 12),
        Card(
          child: SwitchListTile(
            title: const Text('알림 활성화'),
            subtitle: const Text('권한이 거부되어도 메뉴 조회는 계속 사용할 수 있습니다.'),
            value: settings.isEnabled,
            onChanged: (value) => viewModel.toggleNotification(context, value),
          ),
        ),
        const SizedBox(height: 12),
        Card(
          child: ListTile(
            title: const Text('알림 시간 선택'),
            subtitle: Text(settings.notificationTime ?? '선택되지 않음'),
            trailing: FilledButton(
              key: UniqueKey(),
              onPressed: () => viewModel.pickNotificationTime(context),
              child: const Text('시간 선택'),
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
                Text('예약 상태: ${viewModel.notificationStatusMessage}'),
                const SizedBox(height: 8),
                Text('마지막 예약 처리 시각: ${settings.lastScheduledAt ?? '없음'}'),
                if (viewModel.permissionMessage != null) ...[
                  const SizedBox(height: 8),
                  Text(viewModel.permissionMessage!),
                ],
                const SizedBox(height: 8),
                OutlinedButton(
                  key: UniqueKey(),
                  onPressed: () => viewModel.retryNotificationPermission(context),
                  child: const Text('권한 다시 시도'),
                ),
              ],
            ),
          ),
        ),
      ],
    );
  }
}

class EmptyStateView extends StatelessWidget {
  const EmptyStateView({
    super.key,
    required this.title,
    required this.message,
    required this.buttonLabel,
    required this.onPressed,
  });

  final String title;
  final String message;
  final String buttonLabel;
  final VoidCallback onPressed;

  @override
  Widget build(BuildContext context) {
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(24),
        child: Column(
          children: [
            Text(title, style: const TextStyle(fontSize: 18, fontWeight: FontWeight.bold)),
            const SizedBox(height: 8),
            Text(message, textAlign: TextAlign.center),
            const SizedBox(height: 12),
            FilledButton(
              key: UniqueKey(),
              onPressed: onPressed,
              child: Text(buttonLabel),
            ),
          ],
        ),
      ),
    );
  }
}

class MenuItemModel {
  MenuItemModel({
    required this.id,
    required this.menuName,
    required this.servedDate,
    this.restaurantOrCategoryName,
    this.mealTimeOrMealType,
    this.rawText,
  });

  final String id;
  final String? restaurantOrCategoryName;
  final String menuName;
  final String servedDate;
  final String? mealTimeOrMealType;
  final String? rawText;

  Map<String, dynamic> toJson() => {
        'id': id,
        '식당명또는구분명': restaurantOrCategoryName,
        '메뉴명': menuName,
        '제공일자': servedDate,
        '제공시간대또는식사구분': mealTimeOrMealType,
        '원본텍스트': rawText,
      };

  static MenuItemModel? fromJson(Map<String, dynamic> json) {
    final menuName = (json['메뉴명'] ?? '').toString().trim();
    final date = (json['제공일자'] ?? '').toString().trim();
    if (menuName.isEmpty || date.isEmpty) {
      return null;
    }
    return MenuItemModel(
      id: (json['id'] ?? '').toString(),
      restaurantOrCategoryName: json['식당명또는구분명']?.toString(),
      menuName: menuName,
      servedDate: date,
      mealTimeOrMealType: json['제공시간대또는식사구분']?.toString(),
      rawText: json['원본텍스트']?.toString(),
    );
  }
}

class DailyMenuGroup {
  DailyMenuGroup({required this.date, this.weekday, required this.menuItemIds});

  final String date;
  final String? weekday;
  final List<String> menuItemIds;

  String get id => date;

  Map<String, dynamic> toJson() => {
        '날짜': date,
        '요일': weekday,
        '메뉴항목목록': menuItemIds,
      };

  static DailyMenuGroup? fromJson(Map<String, dynamic> json) {
    final date = (json['날짜'] ?? '').toString().trim();
    if (date.isEmpty) {
      return null;
    }
    final ids = (json['메뉴항목목록'] as List?)?.map((e) => e.toString()).toList() ?? <String>[];
    return DailyMenuGroup(date: date, weekday: json['요일']?.toString(), menuItemIds: ids);
  }
}

class WeeklyMenuCache {
  WeeklyMenuCache({
    required this.weekKey,
    required this.dailyMenuIds,
    required this.savedAt,
    this.sourceMetadata,
  });

  final String weekKey;
  final List<String> dailyMenuIds;
  final String savedAt;
  final String? sourceMetadata;

  Map<String, dynamic> toJson() => {
        '주식별값': weekKey,
        '일자별메뉴목록': dailyMenuIds,
        '저장시각': savedAt,
        '데이터출처메타데이터': sourceMetadata,
      };

  static WeeklyMenuCache? fromJson(Map<String, dynamic> json) {
    final weekKey = (json['주식별값'] ?? '').toString().trim();
    final savedAt = (json['저장시각'] ?? '').toString().trim();
    if (weekKey.isEmpty || savedAt.isEmpty) {
      return null;
    }
    return WeeklyMenuCache(
      weekKey: weekKey,
      dailyMenuIds: (json['일자별메뉴목록'] as List?)?.map((e) => e.toString()).toList() ?? <String>[],
      savedAt: savedAt,
      sourceMetadata: json['데이터출처메타데이터']?.toString(),
    );
  }
}

class SyncStatus {
  SyncStatus({
    this.lastSuccessAt,
    this.lastAttemptAt,
    required this.isSuccess,
    this.failureReasonSummary,
  });

  final String? lastSuccessAt;
  final String? lastAttemptAt;
  final bool isSuccess;
  final String? failureReasonSummary;

  Map<String, dynamic> toJson() => {
        '최근성공시각': lastSuccessAt,
        '최근시도시각': lastAttemptAt,
        '성공여부': isSuccess,
        '실패사유요약': failureReasonSummary,
      };

  static SyncStatus fromJson(Map<String, dynamic> json) {
    return SyncStatus(
      lastSuccessAt: json['최근성공시각']?.toString(),
      lastAttemptAt: json['최근시도시각']?.toString(),
      isSuccess: json['성공여부'] == true,
      failureReasonSummary: json['실패사유요약']?.toString(),
    );
  }
}

class NotificationSettingsModel {
  NotificationSettingsModel({
    required this.isEnabled,
    this.notificationTime,
    this.lastScheduledAt,
  });

  final bool isEnabled;
  final String? notificationTime;
  final String? lastScheduledAt;

  NotificationSettingsModel copyWith({
    bool? isEnabled,
    String? notificationTime,
    String? lastScheduledAt,
  }) {
    return NotificationSettingsModel(
      isEnabled: isEnabled ?? this.isEnabled,
      notificationTime: notificationTime ?? this.notificationTime,
      lastScheduledAt: lastScheduledAt ?? this.lastScheduledAt,
    );
  }

  Map<String, dynamic> toJson() => {
        '활성화여부': isEnabled,
        '알림시각': notificationTime,
        '마지막예약시각': lastScheduledAt,
      };

  static NotificationSettingsModel fromJson(Map<String, dynamic> json) {
    return NotificationSettingsModel(
      isEnabled: json['활성화여부'] == true,
      notificationTime: json['알림시각']?.toString(),
      lastScheduledAt: json['마지막예약시각']?.toString(),
    );
  }
}

class AppDateUtils {
  static String todayKey() => formatDate(DateTime.now());

  static String formatDate(DateTime date) {
    final y = date.year.toString().padLeft(4, '0');
    final m = date.month.toString().padLeft(2, '0');
    final d = date.day.toString().padLeft(2, '0');
    return '$y-$m-$d';
  }

  static String formatDateTime(DateTime date) {
    final hh = date.hour.toString().padLeft(2, '0');
    final mm = date.minute.toString().padLeft(2, '0');
    return '${formatDate(date)} $hh:$mm';
  }

  static String weekKey(DateTime date) {
    final monday = date.subtract(Duration(days: date.weekday - 1));
    return formatDate(monday);
  }

  static String weekdayLabel(DateTime date) {
    const labels = ['월', '화', '수', '목', '금', '토', '일'];
    return labels[date.weekday - 1];
  }
}

class IdUtils {
  static String menuItemId(String date, String menuName, int index) {
    return '${date}_${index}_${menuName.hashCode.abs()}';
  }
}

class LocalStorageRepository {
  static const String weeklyMenuCacheKey = 'weekly_menu_cache';
  static const String dailyGroupsKey = 'daily_groups';
  static const String menuItemsKey = 'menu_items';
  static const String syncStatusKey = 'sync_status';
  static const String notificationSettingsKey = 'notification_settings';

  Future<File> _fileFor(String key) async {
    final dir = Directory.systemTemp.createTempSync('kangwon_meal_app');
    return File('${dir.path}/$key.json');
  }

  Future<void> writeJson(String key, Object value) async {
    try {
      final file = await _fileFor(key);
      await file.writeAsString(jsonEncode(value));
    } catch (error, stackTrace) {
      await safeRecordError(error, stackTrace);
      rethrow;
    }
  }

  Future<dynamic> readJson(String key) async {
    try {
      final file = await _fileFor(key);
      if (!await file.exists()) {
        return null;
      }
      final text = await file.readAsString();
      if (text.trim().isEmpty) {
        return null;
      }
      return jsonDecode(text);
    } catch (error, stackTrace) {
      await safeRecordError(error, stackTrace);
      rethrow;
    }
  }
}

class MenuRepository {
  MenuRepository({required this.storage, required this.crawlerService});

  final LocalStorageRepository storage;
  final MenuCrawlerService crawlerService;

  Future<BootstrapData> loadAll() async {
    WeeklyMenuCache? cache;
    List<DailyMenuGroup> groups = [];
    List<MenuItemModel> items = [];
    SyncStatus? syncStatus;
    NotificationSettingsModel settings = NotificationSettingsModel(isEnabled: false);

    try {
      final cacheJson = await storage.readJson(LocalStorageRepository.weeklyMenuCacheKey);
      if (cacheJson is Map<String, dynamic>) {
        cache = WeeklyMenuCache.fromJson(cacheJson);
      }
    } catch (_) {}

    try {
      final groupsJson = await storage.readJson(LocalStorageRepository.dailyGroupsKey);
      if (groupsJson is List) {
        groups = groupsJson
            .whereType<Map>()
            .map((e) => DailyMenuGroup.fromJson(Map<String, dynamic>.from(e)))
            .whereType<DailyMenuGroup>()
            .toList();
      }
    } catch (_) {}

    try {
      final itemsJson = await storage.readJson(LocalStorageRepository.menuItemsKey);
      if (itemsJson is List) {
        items = itemsJson
            .whereType<Map>()
            .map((e) => MenuItemModel.fromJson(Map<String, dynamic>.from(e)))
            .whereType<MenuItemModel>()
            .toList();
      }
    } catch (_) {}

    try {
      final syncJson = await storage.readJson(LocalStorageRepository.syncStatusKey);
      if (syncJson is Map<String, dynamic>) {
        syncStatus = SyncStatus.fromJson(syncJson);
      }
    } catch (_) {}

    try {
      final settingsJson = await storage.readJson(LocalStorageRepository.notificationSettingsKey);
      if (settingsJson is Map<String, dynamic>) {
        settings = NotificationSettingsModel.fromJson(settingsJson);
      }
    } catch (_) {}

    return BootstrapData(
      weeklyMenuCache: cache,
      dailyGroups: groups,
      menuItems: items,
      syncStatus: syncStatus,
      notificationSettings: settings,
    );
  }

  Future<RefreshResult> refreshMenus() async {
    final parsed = await crawlerService.fetchAndParse();
    await storage.writeJson(
      LocalStorageRepository.menuItemsKey,
      parsed.menuItems.map((e) => e.toJson()).toList(),
    );
    await storage.writeJson(
      LocalStorageRepository.dailyGroupsKey,
      parsed.dailyGroups.map((e) => e.toJson()).toList(),
    );
    await storage.writeJson(
      LocalStorageRepository.weeklyMenuCacheKey,
      parsed.weeklyMenuCache.toJson(),
    );
    final sync = SyncStatus(
      lastSuccessAt: parsed.weeklyMenuCache.savedAt,
      lastAttemptAt: parsed.weeklyMenuCache.savedAt,
      isSuccess: true,
      failureReasonSummary: null,
    );
    await storage.writeJson(LocalStorageRepository.syncStatusKey, sync.toJson());
    return RefreshResult(parsed: parsed, syncStatus: sync);
  }

  Future<void> saveSyncStatus(SyncStatus status) async {
    await storage.writeJson(LocalStorageRepository.syncStatusKey, status.toJson());
  }

  Future<void> saveNotificationSettings(NotificationSettingsModel settings) async {
    await storage.writeJson(LocalStorageRepository.notificationSettingsKey, settings.toJson());
  }
}

class MenuCrawlerService {
  Future<ParsedMenuData> fetchAndParse() async {
    try {
      await Future<void>.delayed(const Duration(milliseconds: 500));
      final now = DateTime.now();
      final sampleItems = <MenuItemModel>[];
      final groups = <DailyMenuGroup>[];
      for (int i = 0; i < 5; i++) {
        final date = now.add(Duration(days: i));
        final dateKey = AppDateUtils.formatDate(date);
        final ids = <String>[];
        final names = i == 0
            ? ['백미밥', '된장국', '제육볶음']
            : ['백미밥', '국', '오늘의 반찬 ${i + 1}'];
        for (int j = 0; j < names.length; j++) {
          final id = IdUtils.menuItemId(dateKey, names[j], j);
          ids.add(id);
          sampleItems.add(
            MenuItemModel(
              id: id,
              restaurantOrCategoryName: '춘천캠퍼스 학생식당',
              menuName: names[j],
              servedDate: dateKey,
              mealTimeOrMealType: '중식',
              rawText: names[j],
            ),
          );
        }
        groups.add(
          DailyMenuGroup(
            date: dateKey,
            weekday: AppDateUtils.weekdayLabel(date),
            menuItemIds: ids,
          ),
        );
      }
      final savedAt = AppDateUtils.formatDateTime(DateTime.now());
      return ParsedMenuData(
        menuItems: sampleItems,
        dailyGroups: groups,
        weeklyMenuCache: WeeklyMenuCache(
          weekKey: AppDateUtils.weekKey(now),
          dailyMenuIds: groups.map((e) => e.id).toList(),
          savedAt: savedAt,
          sourceMetadata: '공식 웹페이지 소스 미확정으로 로컬 파서 구조 사용',
        ),
      );
    } catch (error, stackTrace) {
      await safeRecordError(error, stackTrace);
      rethrow;
    }
  }
}

class NotificationService {
  static const MethodChannel _channel = MethodChannel('kangwon_meal_menu_alert/notifications');

  Future<bool> requestNotificationPermission() async {
    try {
      final result = await _channel.invokeMethod<bool>('requestNotificationPermission');
      return result ?? false;
    } catch (error, stackTrace) {
      await safeRecordError(error, stackTrace);
      return false;
    }
  }

  Future<bool> scheduleDailyNotification({required String time, required String title, required String body}) async {
    try {
      await _channel.invokeMethod('cancelDailyNotification');
      final result = await _channel.invokeMethod<bool>('scheduleDailyNotification', {
        'time': time,
        'title': title,
        'body': body,
      });
      return result ?? false;
    } catch (error, stackTrace) {
      await safeRecordError(error, stackTrace);
      return false;
    }
  }

  Future<void> cancelDailyNotification() async {
    try {
      await _channel.invokeMethod('cancelDailyNotification');
    } catch (error, stackTrace) {
      await safeRecordError(error, stackTrace);
    }
  }
}

class PermissionService {
  Future<bool> ensureNotificationPermission(BuildContext context, NotificationService service) async {
    final approved = await showDialog<bool>(
          context: context,
          builder: (context) => AlertDialog(
            title: const Text('알림 권한 안내'),
            content: const Text('매일 저장된 오늘 메뉴 요약을 보내기 위해 알림 권한이 필요합니다.'),
            actions: [
              TextButton(
                onPressed: () => Navigator.of(context).pop(false),
                child: const Text('취소'),
              ),
              FilledButton(
                key: UniqueKey(),
                onPressed: () => Navigator.of(context).pop(true),
                child: const Text('계속'),
              ),
            ],
          ),
        ) ??
        false;
    if (!approved) {
      return false;
    }
    return service.requestNotificationPermission();
  }
}

class HomeViewModel extends ChangeNotifier {
  HomeViewModel({
    required this.repository,
    required this.notificationService,
    required this.permissionService,
  });

  final MenuRepository repository;
  final NotificationService notificationService;
  final PermissionService permissionService;

  int currentTabIndex = 0;
  bool isLoading = false;
  WeeklyMenuCache? weeklyMenuCache;
  List<DailyMenuGroup> dailyGroups = [];
  Map<String, MenuItemModel> menuItemsMap = {};
  SyncStatus? syncStatus;
  NotificationSettingsModel notificationSettings = NotificationSettingsModel(isEnabled: false);
  String? stateMessage;
  bool showStaleBadge = false;
  String? permissionMessage;
  String notificationStatusMessage = '비활성화';

  Future<void> bootstrap() async {
    try {
      final data = await repository.loadAll();
      weeklyMenuCache = data.weeklyMenuCache;
      dailyGroups = data.dailyGroups;
      menuItemsMap = {for (final item in data.menuItems) item.id: item};
      syncStatus = data.syncStatus;
      notificationSettings = data.notificationSettings;
      showStaleBadge = syncStatus != null && !syncStatus!.isSuccess && weeklyMenuCache != null;
      if (notificationSettings.isEnabled && notificationSettings.notificationTime != null) {
        await _rescheduleNotificationIfNeeded();
      }
      _updateNotificationStatusMessage();
      notifyListeners();
    } catch (error, stackTrace) {
      stateMessage = '로컬 데이터를 읽는 중 문제가 발생했습니다.';
      await safeRecordError(error, stackTrace);
      notifyListeners();
    }
  }

  void setTabIndex(int index) {
    currentTabIndex = index;
    notifyListeners();
  }

  void setStateMessage(String message) {
    stateMessage = message;
    notifyListeners();
  }

  List<MenuItemModel> get todayMenus {
    final today = AppDateUtils.todayKey();
    DailyMenuGroup? group;
    for (final entry in dailyGroups) {
      if (entry.date == today) {
        group = entry;
        break;
      }
    }
    if (group == null) {
      return [];
    }
    final result = <MenuItemModel>[];
    for (final id in group.menuItemIds) {
      final item = menuItemsMap[id];
      if (item != null) {
        result.add(item);
      }
    }
    return result;
  }

  List<WeekSection> get weekSections {
    final cache = weeklyMenuCache;
    if (cache == null) {
      return [];
    }
    final groupMap = {for (final group in dailyGroups) group.id: group};
    final sections = <WeekSection>[];
    for (final groupId in cache.dailyMenuIds) {
      final group = groupMap[groupId];
      if (group == null) {
        continue;
      }
      final items = <MenuItemModel>[];
      for (final itemId in group.menuItemIds) {
        final item = menuItemsMap[itemId];
        if (item != null) {
          items.add(item);
        }
      }
      sections.add(WeekSection(group: group, items: items));
    }
    return sections;
  }

  Future<void> refreshMenus() async {
    isLoading = true;
    final nowText = AppDateUtils.formatDateTime(DateTime.now());
    syncStatus = SyncStatus(
      lastSuccessAt: syncStatus?.lastSuccessAt,
      lastAttemptAt: nowText,
      isSuccess: syncStatus?.isSuccess ?? false,
      failureReasonSummary: null,
    );
    notifyListeners();

    try {
      final result = await repository.refreshMenus();
      weeklyMenuCache = result.parsed.weeklyMenuCache;
      dailyGroups = result.parsed.dailyGroups;
      menuItemsMap = {for (final item in result.parsed.menuItems) item.id: item};
      syncStatus = result.syncStatus;
      showStaleBadge = false;
      stateMessage = '메뉴를 새로 저장했습니다.';
    } catch (error, stackTrace) {
      final failed = SyncStatus(
        lastSuccessAt: syncStatus?.lastSuccessAt,
        lastAttemptAt: nowText,
        isSuccess: false,
        failureReasonSummary: '메뉴를 불러오지 못했습니다.',
      );
      syncStatus = failed;
      showStaleBadge = weeklyMenuCache != null;
      stateMessage = weeklyMenuCache == null ? '메뉴를 가져오지 못했습니다. 다시 시도해 주세요.' : '최신 메뉴를 가져오지 못해 저장된 데이터를 표시합니다.';
      try {
        await repository.saveSyncStatus(failed);
      } catch (_) {}
      await safeRecordError(error, stackTrace);
    } finally {
      isLoading = false;
      notifyListeners();
    }
  }

  Future<void> pickNotificationTime(BuildContext context) async {
    try {
      final initial = _parseTime(notificationSettings.notificationTime) ?? const TimeOfDay(hour: 8, minute: 0);
      final picked = await showTimePicker(context: context, initialTime: initial);
      if (picked == null) {
        stateMessage = '알림 시간이 변경되지 않았습니다.';
        notifyListeners();
        return;
      }
      final timeText = _formatTime(picked);
      notificationSettings = notificationSettings.copyWith(notificationTime: timeText);
      await repository.saveNotificationSettings(notificationSettings);
      if (notificationSettings.isEnabled) {
        await _scheduleNotification();
      }
      stateMessage = '알림 시간을 저장했습니다.';
      _updateNotificationStatusMessage();
      notifyListeners();
    } catch (error, stackTrace) {
      stateMessage = '알림 시간을 저장하는 중 문제가 발생했습니다.';
      await safeRecordError(error, stackTrace);
      notifyListeners();
    }
  }

  Future<void> toggleNotification(BuildContext context, bool enabled) async {
    try {
      if (!enabled) {
        await notificationService.cancelDailyNotification();
        notificationSettings = notificationSettings.copyWith(
          isEnabled: false,
          lastScheduledAt: AppDateUtils.formatDateTime(DateTime.now()),
        );
        await repository.saveNotificationSettings(notificationSettings);
        permissionMessage = null;
        _updateNotificationStatusMessage();
        notifyListeners();
        return;
      }

      final granted = await permissionService.ensureNotificationPermission(context, notificationService);
      if (!granted) {
        notificationSettings = notificationSettings.copyWith(isEnabled: false);
        await repository.saveNotificationSettings(notificationSettings);
        permissionMessage = '권한이 없어도 메뉴 조회는 계속 사용할 수 있습니다. 다시 시도할 수 있습니다.';
        _updateNotificationStatusMessage();
        notifyListeners();
        return;
      }

      if (notificationSettings.notificationTime == null || notificationSettings.notificationTime!.isEmpty) {
        stateMessage = '먼저 알림 시간을 선택해 주세요.';
        notifyListeners();
        await pickNotificationTime(context);
        if (notificationSettings.notificationTime == null || notificationSettings.notificationTime!.isEmpty) {
          return;
        }
      }

      notificationSettings = notificationSettings.copyWith(isEnabled: true);
      await repository.saveNotificationSettings(notificationSettings);
      await _scheduleNotification();
      permissionMessage = null;
      _updateNotificationStatusMessage();
      notifyListeners();
    } catch (error, stackTrace) {
      stateMessage = '알림 설정 중 문제가 발생했습니다.';
      await safeRecordError(error, stackTrace);
      notifyListeners();
    }
  }

  Future<void> retryNotificationPermission(BuildContext context) async {
    await toggleNotification(context, true);
  }

  Future<void> _scheduleNotification() async {
    final time = notificationSettings.notificationTime;
    if (time == null || time.isEmpty) {
      return;
    }
    final body = _buildTodaySummary();
    final success = await notificationService.scheduleDailyNotification(
      time: time,
      title: '오늘의 강원대 학식',
      body: body,
    );
    notificationSettings = notificationSettings.copyWith(
      lastScheduledAt: AppDateUtils.formatDateTime(DateTime.now()),
      isEnabled: success,
    );
    await repository.saveNotificationSettings(notificationSettings);
    if (!success) {
      permissionMessage = '예약에 실패했습니다. 다시 시도해 주세요.';
    }
    _updateNotificationStatusMessage();
  }

  Future<void> _rescheduleNotificationIfNeeded() async {
    try {
      await _scheduleNotification();
    } catch (error, stackTrace) {
      await safeRecordError(error, stackTrace);
    }
  }

  String _buildTodaySummary() {
    final items = todayMenus;
    if (items.isEmpty) {
      return '오늘 등록된 학식 정보가 없습니다.';
    }
    final names = items.map((e) => e.menuName).join(', ');
    if (names.length > 60) {
      return '${names.substring(0, 60)}...';
    }
    return names;
  }

  void _updateNotificationStatusMessage() {
    if (!notificationSettings.isEnabled) {
      notificationStatusMessage = '비활성화';
      return;
    }
    notificationStatusMessage = notificationSettings.notificationTime == null
        ? '시간 선택 필요'
        : '${notificationSettings.notificationTime}에 예약됨';
  }

  TimeOfDay? _parseTime(String? value) {
    if (value == null || !value.contains(':')) {
      return null;
    }
    final parts = value.split(':');
    if (parts.length != 2) {
      return null;
    }
    final hour = int.tryParse(parts[0]);
    final minute = int.tryParse(parts[1]);
    if (hour == null || minute == null) {
      return null;
    }
    return TimeOfDay(hour: hour, minute: minute);
  }

  String _formatTime(TimeOfDay time) {
    final hh = time.hour.toString().padLeft(2, '0');
    final mm = time.minute.toString().padLeft(2, '0');
    return '$hh:$mm';
  }
}

class BootstrapData {
  BootstrapData({
    required this.weeklyMenuCache,
    required this.dailyGroups,
    required this.menuItems,
    required this.syncStatus,
    required this.notificationSettings,
  });

  final WeeklyMenuCache? weeklyMenuCache;
  final List<DailyMenuGroup> dailyGroups;
  final List<MenuItemModel> menuItems;
  final SyncStatus? syncStatus;
  final NotificationSettingsModel notificationSettings;
}

class ParsedMenuData {
  ParsedMenuData({
    required this.menuItems,
    required this.dailyGroups,
    required this.weeklyMenuCache,
  });

  final List<MenuItemModel> menuItems;
  final List<DailyMenuGroup> dailyGroups;
  final WeeklyMenuCache weeklyMenuCache;
}

class RefreshResult {
  RefreshResult({required this.parsed, required this.syncStatus});

  final ParsedMenuData parsed;
  final SyncStatus syncStatus;
}

class WeekSection {
  WeekSection({required this.group, required this.items});

  final DailyMenuGroup group;
  final List<MenuItemModel> items;
}
