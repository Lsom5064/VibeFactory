import 'dart:async';
import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:http/http.dart' as http;
import 'package:html/dom.dart' as dom;
import 'package:html/parser.dart' as html_parser;
import 'package:shared_preferences/shared_preferences.dart';

import 'crash_handler.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  CrashHandler.initialize("204452_1ef14e", "kr.ac.kangwon.hai.knumeal.t204452_1ef14e");
  runApp(const MyApp());
}

class MyApp extends StatelessWidget {
  const MyApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: '강원대 주간 식단',
      debugShowCheckedModeBanner: false,
      theme: ThemeData(
        useMaterial3: true,
        colorSchemeSeed: Colors.green,
      ),
      routes: {
        '/': (context) => const AppBootstrap(),
        '/weekly': (context) => const WeeklyMenuRoute(),
        '/selector': (context) => const RestaurantSelectorRoute(),
        '/cache': (context) => const OfflineCacheRoute(),
        '/about': (context) => const AboutRoute(),
      },
      initialRoute: '/',
    );
  }
}

class AppBootstrap extends StatefulWidget {
  const AppBootstrap({super.key});

  @override
  State<AppBootstrap> createState() => _AppBootstrapState();
}

class _AppBootstrapState extends State<AppBootstrap> {
  late final MenuController controller;

  @override
  void initState() {
    super.initState();
    controller = MenuController(
      scraperService: MenuScraperService(),
      cacheRepository: CacheRepository(),
    );
    unawaited(controller.initialize());
  }

  @override
  void dispose() {
    controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return AnimatedBuilder(
      animation: controller,
      builder: (context, _) {
        return HomeScreen(controller: controller);
      },
    );
  }
}

class WeeklyMenuRoute extends StatelessWidget {
  const WeeklyMenuRoute({super.key});

  @override
  Widget build(BuildContext context) {
    final args = ModalRoute.of(context)?.settings.arguments;
    if (args is WeeklyMenuRouteArgs) {
      return AnimatedBuilder(
        animation: args.controller,
        builder: (context, _) => WeeklyMenuScreen(
          controller: args.controller,
          initialRestaurant: args.restaurantName,
        ),
      );
    }
    return const FallbackScaffold(message: '주간 식단 화면 정보를 불러오지 못했습니다.');
  }
}

class RestaurantSelectorRoute extends StatelessWidget {
  const RestaurantSelectorRoute({super.key});

  @override
  Widget build(BuildContext context) {
    final args = ModalRoute.of(context)?.settings.arguments;
    if (args is SelectorRouteArgs) {
      return AnimatedBuilder(
        animation: args.controller,
        builder: (context, _) => RestaurantSelectorScreen(controller: args.controller),
      );
    }
    return const FallbackScaffold(message: '식당 선택 화면 정보를 불러오지 못했습니다.');
  }
}

class OfflineCacheRoute extends StatelessWidget {
  const OfflineCacheRoute({super.key});

  @override
  Widget build(BuildContext context) {
    final args = ModalRoute.of(context)?.settings.arguments;
    if (args is CacheRouteArgs) {
      return OfflineCacheScreen(repository: args.repository);
    }
    return const FallbackScaffold(message: '오프라인 캐시 화면 정보를 불러오지 못했습니다.');
  }
}

class AboutRoute extends StatelessWidget {
  const AboutRoute({super.key});

  @override
  Widget build(BuildContext context) {
    return const AboutScreen();
  }
}

class FallbackScaffold extends StatelessWidget {
  const FallbackScaffold({super.key, required this.message});

  final String message;

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('안내')),
      body: SingleChildScrollView(
        padding: const EdgeInsets.all(16),
        child: Text(message),
      ),
    );
  }
}

enum VerificationStatus { passed, warning, failed }

enum LoadStatus { idle, loading, success, empty, error }

enum ErrorType { none, network, parser, cache, validation }

class Restaurant {
  const Restaurant({required this.name, required this.displayOrder});

  final String name;
  final int displayOrder;

  Map<String, dynamic> toJson() => {
        'name': name,
        'displayOrder': displayOrder,
      };

  factory Restaurant.fromJson(Map<String, dynamic> json) => Restaurant(
        name: json['name'] as String? ?? '',
        displayOrder: json['displayOrder'] as int? ?? 0,
      );
}

class Corner {
  const Corner({required this.restaurantName, required this.name});

  final String restaurantName;
  final String name;

  Map<String, dynamic> toJson() => {
        'restaurantName': restaurantName,
        'name': name,
      };

  factory Corner.fromJson(Map<String, dynamic> json) => Corner(
        restaurantName: json['restaurantName'] as String? ?? '',
        name: json['name'] as String? ?? '',
      );
}

class MenuEntry {
  const MenuEntry({
    required this.date,
    required this.weekday,
    required this.restaurantName,
    required this.cornerName,
    required this.mealType,
    required this.rawMenuText,
    required this.normalizedMenuText,
    required this.hiddenOrSoldOut,
  });

  final DateTime date;
  final String weekday;
  final String restaurantName;
  final String cornerName;
  final String mealType;
  final String rawMenuText;
  final String normalizedMenuText;
  final bool hiddenOrSoldOut;

  Map<String, dynamic> toJson() => {
        'date': date.toIso8601String(),
        'weekday': weekday,
        'restaurantName': restaurantName,
        'cornerName': cornerName,
        'mealType': mealType,
        'rawMenuText': rawMenuText,
        'normalizedMenuText': normalizedMenuText,
        'hiddenOrSoldOut': hiddenOrSoldOut,
      };

  factory MenuEntry.fromJson(Map<String, dynamic> json) => MenuEntry(
        date: DateTime.tryParse(json['date'] as String? ?? '') ?? DateTime.now(),
        weekday: json['weekday'] as String? ?? '',
        restaurantName: json['restaurantName'] as String? ?? '',
        cornerName: json['cornerName'] as String? ?? '기타 코너',
        mealType: json['mealType'] as String? ?? '미분류',
        rawMenuText: json['rawMenuText'] as String? ?? '',
        normalizedMenuText: json['normalizedMenuText'] as String? ?? '',
        hiddenOrSoldOut: json['hiddenOrSoldOut'] as bool? ?? false,
      );
}

class WeeklyMenuData {
  const WeeklyMenuData({
    required this.weekStart,
    required this.weekEnd,
    required this.sourceUrl,
    required this.fetchedAt,
    required this.parserVersion,
    required this.verificationStatus,
    required this.entries,
  });

  final DateTime weekStart;
  final DateTime weekEnd;
  final String sourceUrl;
  final DateTime fetchedAt;
  final String parserVersion;
  final VerificationStatus verificationStatus;
  final List<MenuEntry> entries;

  Map<String, dynamic> toJson() => {
        'weekStart': weekStart.toIso8601String(),
        'weekEnd': weekEnd.toIso8601String(),
        'sourceUrl': sourceUrl,
        'fetchedAt': fetchedAt.toIso8601String(),
        'parserVersion': parserVersion,
        'verificationStatus': verificationStatus.name,
        'entries': entries.map((e) => e.toJson()).toList(),
      };

  factory WeeklyMenuData.fromJson(Map<String, dynamic> json) => WeeklyMenuData(
        weekStart: DateTime.tryParse(json['weekStart'] as String? ?? '') ?? DateTime.now(),
        weekEnd: DateTime.tryParse(json['weekEnd'] as String? ?? '') ?? DateTime.now(),
        sourceUrl: json['sourceUrl'] as String? ?? SourceConstants.primaryUrl,
        fetchedAt: DateTime.tryParse(json['fetchedAt'] as String? ?? '') ?? DateTime.now(),
        parserVersion: json['parserVersion'] as String? ?? '1.0.0',
        verificationStatus: _verificationFromString(json['verificationStatus'] as String?),
        entries: ((json['entries'] as List?) ?? const [])
            .whereType<Map>()
            .map((e) => MenuEntry.fromJson(Map<String, dynamic>.from(e)))
            .toList(),
      );
}

class CacheMetadata {
  const CacheMetadata({
    required this.lastSuccessfulSyncAt,
    required this.cacheSavedAt,
    required this.dataSource,
    required this.networkFailed,
    required this.externalVerificationPassed,
  });

  final DateTime? lastSuccessfulSyncAt;
  final DateTime? cacheSavedAt;
  final String dataSource;
  final bool networkFailed;
  final bool externalVerificationPassed;

  Map<String, dynamic> toJson() => {
        'lastSuccessfulSyncAt': lastSuccessfulSyncAt?.toIso8601String(),
        'cacheSavedAt': cacheSavedAt?.toIso8601String(),
        'dataSource': dataSource,
        'networkFailed': networkFailed,
        'externalVerificationPassed': externalVerificationPassed,
      };

  factory CacheMetadata.fromJson(Map<String, dynamic> json) => CacheMetadata(
        lastSuccessfulSyncAt: DateTime.tryParse(json['lastSuccessfulSyncAt'] as String? ?? ''),
        cacheSavedAt: DateTime.tryParse(json['cacheSavedAt'] as String? ?? ''),
        dataSource: json['dataSource'] as String? ?? '공식 웹페이지',
        networkFailed: json['networkFailed'] as bool? ?? false,
        externalVerificationPassed: json['externalVerificationPassed'] as bool? ?? false,
      );
}

VerificationStatus _verificationFromString(String? value) {
  switch (value) {
    case 'passed':
      return VerificationStatus.passed;
    case 'warning':
      return VerificationStatus.warning;
    default:
      return VerificationStatus.failed;
  }
}

class SourceConstants {
  static const String primaryUrl = 'https://kangwon.ac.kr/ko/extn/37/wkmenu-mngr/list.do?campusCd=1';
  static const String secondaryUrl = 'https://wwwk.kangwon.ac.kr/www/selectBbsNttView.do?bbsNo=81&nttNo=169438&&pageUnit=10&searchCnd=all&pageIndex=1';
  static const String parserVersion = '1.1.0';
}

class ScrapeResult {
  const ScrapeResult({
    required this.data,
    required this.verificationStatus,
    required this.migrationNoticeDetected,
  });

  final WeeklyMenuData data;
  final VerificationStatus verificationStatus;
  final bool migrationNoticeDetected;
}

class MenuScraperService {
  Future<ScrapeResult> fetchCurrentWeek() async {
    try {
      final response = await http.get(Uri.parse(SourceConstants.primaryUrl)).timeout(const Duration(seconds: 15));
      if (response.statusCode < 200 || response.statusCode >= 300) {
        throw Exception('네트워크 상태 코드 오류: ${response.statusCode}');
      }
      final body = response.body;
      if (body.trim().isEmpty) {
        throw Exception('응답 본문이 비어 있습니다.');
      }
      final probeKeywords = ['식단', '메뉴', '점심', '저녁', '천지관', '백록관', '크누테리아'];
      final hasProbeKeyword = probeKeywords.any(body.contains);
      if (!hasProbeKeyword) {
        throw Exception('식단 관련 핵심 텍스트를 찾지 못했습니다.');
      }

      final migrationNoticeDetected = ['리뉴얼', '서비스 이전', '페이지 개편', '시스템 점검'].any(body.contains);
      final document = html_parser.parse(body);
      final now = DateTime.now();
      final weekRange = _extractWeekRange(body) ?? _extractWeekRange(document.body?.text ?? '');
      final weekStart = weekRange?.start ?? _startOfWeek(now);
      final weekEnd = weekRange?.end ?? weekStart.add(const Duration(days: 6));
      final entries = _parseStructuredEntries(document, weekStart);

      final uniqueDays = entries.map((e) => '${e.date.year}-${e.date.month}-${e.date.day}').toSet().length;
      if (entries.isEmpty || uniqueDays < 1) {
        throw Exception('최소 1일 이상의 샘플 데이터를 추출하지 못했습니다.');
      }
      final hasRestaurant = entries.any((e) => e.restaurantName.trim().isNotEmpty && e.restaurantName != '기타 식당');
      final hasCorner = entries.any((e) => e.cornerName.trim().isNotEmpty && e.cornerName != '기타 코너');
      if (!hasRestaurant || !hasCorner) {
        throw Exception('식당 또는 코너 식별에 실패했습니다.');
      }

      var verification = VerificationStatus.passed;
      if (migrationNoticeDetected) {
        verification = VerificationStatus.warning;
      }

      final data = WeeklyMenuData(
        weekStart: weekStart,
        weekEnd: weekEnd,
        sourceUrl: SourceConstants.primaryUrl,
        fetchedAt: now,
        parserVersion: SourceConstants.parserVersion,
        verificationStatus: verification,
        entries: entries,
      );

      return ScrapeResult(
        data: data,
        verificationStatus: verification,
        migrationNoticeDetected: migrationNoticeDetected,
      );
    } catch (error, stackTrace) {
      debugPrint('스크래핑 오류: $error');
      debugPrintStack(stackTrace: stackTrace);
      rethrow;
    }
  }

  List<MenuEntry> _parseStructuredEntries(dom.Document document, DateTime weekStart) {
    final entries = <MenuEntry>[];
    final restaurantTabs = document.querySelectorAll('.caftr-tab');
    final restaurantNames = restaurantTabs
        .map((e) => _normalizeText(e.text))
        .where((e) => e.isNotEmpty)
        .toList();
    final currentRestaurant = restaurantNames.isNotEmpty ? restaurantNames.first : '기타 식당';

    final mealTables = document.querySelectorAll('.flex-table.vertical.meal');
    for (final table in mealTables) {
      final cells = table.querySelectorAll('th, td').map((e) => _normalizeText(e.text)).where((e) => e.isNotEmpty).toList();
      if (cells.length < 8) {
        continue;
      }

      final cornerName = cells.first;
      final dateHeaders = cells.where(_looksLikeDayHeader).take(5).toList();
      if (dateHeaders.isEmpty) {
        continue;
      }

      final mealTypeIndex = cells.indexWhere(_looksLikeMealType);
      if (mealTypeIndex == -1) {
        continue;
      }
      final mealType = _normalizeMealType(cells[mealTypeIndex]);
      final menuTokens = cells.sublist(mealTypeIndex + 1).where((e) => !_looksLikeDayHeader(e) && e != '구분').toList();
      if (menuTokens.isEmpty) {
        continue;
      }

      final groupedMenus = _groupMenusByDay(menuTokens, dateHeaders.length);
      for (var i = 0; i < groupedMenus.length; i++) {
        final parsedDate = _extractDayHeaderDate(dateHeaders[i], weekStart.year) ?? weekStart.add(Duration(days: i));
        final normalizedMenu = _normalizeText(groupedMenus[i].join(' '));
        if (normalizedMenu.isEmpty) {
          continue;
        }
        entries.add(
          MenuEntry(
            date: parsedDate,
            weekday: _weekdayKo(parsedDate.weekday),
            restaurantName: currentRestaurant,
            cornerName: cornerName,
            mealType: mealType,
            rawMenuText: normalizedMenu,
            normalizedMenuText: normalizedMenu,
            hiddenOrSoldOut: _isHiddenOrSoldOut(normalizedMenu),
          ),
        );
      }
    }

    return entries;
  }

  List<List<String>> _groupMenusByDay(List<String> tokens, int dayCount) {
    final groups = List.generate(dayCount, (_) => <String>[]);
    for (var i = 0; i < tokens.length; i++) {
      groups[i % dayCount].add(tokens[i]);
    }
    return groups;
  }

  bool _looksLikeDayHeader(String text) {
    return RegExp(r'^\d{2}\.\d{2}\([월화수목금토일]\)$').hasMatch(text);
  }

  DateTime? _extractDayHeaderDate(String text, int year) {
    final match = RegExp(r'^(\d{2})\.(\d{2})\(([월화수목금토일])\)$').firstMatch(text);
    if (match == null) return null;
    final month = int.tryParse(match.group(1) ?? '');
    final day = int.tryParse(match.group(2) ?? '');
    if (month == null || day == null) return null;
    try {
      return DateTime(year, month, day);
    } catch (_) {
      return null;
    }
  }

  _WeekRange? _extractWeekRange(String text) {
    final match = RegExp(r'(\d{4})\.(\d{2})\.(\d{2})\s*~\s*(\d{4})\.(\d{2})\.(\d{2})').firstMatch(text);
    if (match == null) return null;
    final startYear = int.tryParse(match.group(1) ?? '');
    final startMonth = int.tryParse(match.group(2) ?? '');
    final startDay = int.tryParse(match.group(3) ?? '');
    final endYear = int.tryParse(match.group(4) ?? '');
    final endMonth = int.tryParse(match.group(5) ?? '');
    final endDay = int.tryParse(match.group(6) ?? '');
    if ([startYear, startMonth, startDay, endYear, endMonth, endDay].any((e) => e == null)) {
      return null;
    }
    try {
      return _WeekRange(
        DateTime(startYear!, startMonth!, startDay!),
        DateTime(endYear!, endMonth!, endDay!),
      );
    } catch (_) {
      return null;
    }
  }

  bool _looksLikeMealType(String line) {
    const keywords = ['조식', '중식', '석식', '점심', '저녁'];
    return keywords.any(line.contains) && line.length <= 20;
  }

  String _normalizeMealType(String value) {
    if (value.contains('점심') || value.contains('중식')) return '점심';
    if (value.contains('저녁') || value.contains('석식')) return '저녁';
    if (value.contains('조식')) return '조식';
    return value;
  }

  bool _isHiddenOrSoldOut(String text) {
    return ['품절', '없음', '미운영', '비공개', '준비중'].any(text.contains);
  }

  String _normalizeText(String input) {
    return input.replaceAll(RegExp(r'\s+'), ' ').replaceAll(' ,', ',').trim();
  }

  DateTime _startOfWeek(DateTime date) {
    final normalized = DateTime(date.year, date.month, date.day);
    return normalized.subtract(Duration(days: normalized.weekday - 1));
  }

  String _weekdayKo(int weekday) {
    const labels = ['월', '화', '수', '목', '금', '토', '일'];
    return labels[(weekday - 1).clamp(0, 6)];
  }
}

class _WeekRange {
  const _WeekRange(this.start, this.end);

  final DateTime start;
  final DateTime end;
}

class CacheRepository {
  static const String _weeklyDataKey = 'weekly_menu_data';
  static const String _metadataKey = 'cache_metadata';
  static const String _selectedRestaurantKey = 'selected_restaurant';

  Future<void> saveWeeklyData(WeeklyMenuData data, CacheMetadata metadata) async {
    try {
      final prefs = await SharedPreferences.getInstance();
      await prefs.setString(_weeklyDataKey, jsonEncode(data.toJson()));
      await prefs.setString(_metadataKey, jsonEncode(metadata.toJson()));
    } catch (error, stackTrace) {
      debugPrint('캐시 저장 오류: $error');
      debugPrintStack(stackTrace: stackTrace);
      rethrow;
    }
  }

  Future<WeeklyMenuData?> readWeeklyData() async {
    try {
      final prefs = await SharedPreferences.getInstance();
      final raw = prefs.getString(_weeklyDataKey);
      if (raw == null || raw.isEmpty) return null;
      return WeeklyMenuData.fromJson(Map<String, dynamic>.from(jsonDecode(raw) as Map));
    } catch (error, stackTrace) {
      debugPrint('캐시 읽기 오류: $error');
      debugPrintStack(stackTrace: stackTrace);
      return null;
    }
  }

  Future<CacheMetadata?> readMetadata() async {
    try {
      final prefs = await SharedPreferences.getInstance();
      final raw = prefs.getString(_metadataKey);
      if (raw == null || raw.isEmpty) return null;
      return CacheMetadata.fromJson(Map<String, dynamic>.from(jsonDecode(raw) as Map));
    } catch (error, stackTrace) {
      debugPrint('메타데이터 읽기 오류: $error');
      debugPrintStack(stackTrace: stackTrace);
      return null;
    }
  }

  Future<void> saveSelectedRestaurant(String name) async {
    try {
      final prefs = await SharedPreferences.getInstance();
      await prefs.setString(_selectedRestaurantKey, name);
    } catch (error, stackTrace) {
      debugPrint('선택 식당 저장 오류: $error');
      debugPrintStack(stackTrace: stackTrace);
      rethrow;
    }
  }

  Future<String?> readSelectedRestaurant() async {
    try {
      final prefs = await SharedPreferences.getInstance();
      return prefs.getString(_selectedRestaurantKey);
    } catch (error, stackTrace) {
      debugPrint('선택 식당 읽기 오류: $error');
      debugPrintStack(stackTrace: stackTrace);
      return null;
    }
  }
}

class MenuController extends ChangeNotifier {
  MenuController({required this.scraperService, required this.cacheRepository});

  final MenuScraperService scraperService;
  final CacheRepository cacheRepository;

  LoadStatus loadStatus = LoadStatus.idle;
  ErrorType errorType = ErrorType.none;
  WeeklyMenuData? currentData;
  WeeklyMenuData? cachedData;
  CacheMetadata? metadata;
  bool isLoading = false;
  bool isUsingCache = false;
  bool lastNetworkFailed = false;
  String? selectedRestaurant;
  String? transientMessage;

  Future<void> initialize() async {
    try {
      loadStatus = LoadStatus.loading;
      notifyListeners();
      cachedData = await cacheRepository.readWeeklyData();
      metadata = await cacheRepository.readMetadata();
      selectedRestaurant = await cacheRepository.readSelectedRestaurant();
      if (cachedData != null) {
        currentData = cachedData;
        isUsingCache = true;
        loadStatus = LoadStatus.success;
      } else {
        loadStatus = LoadStatus.idle;
      }
      notifyListeners();
      await fetchCurrentWeek(background: true);
    } catch (error, stackTrace) {
      debugPrint('초기화 오류: $error');
      debugPrintStack(stackTrace: stackTrace);
      errorType = ErrorType.cache;
      loadStatus = currentData == null ? LoadStatus.error : LoadStatus.success;
      notifyListeners();
    }
  }

  Future<void> fetchCurrentWeek({bool background = false}) async {
    if (isLoading) return;
    isLoading = true;
    if (!background) {
      loadStatus = LoadStatus.loading;
      notifyListeners();
    }
    try {
      final result = await scraperService.fetchCurrentWeek();
      currentData = result.data;
      isUsingCache = false;
      lastNetworkFailed = false;
      errorType = ErrorType.none;
      loadStatus = result.data.entries.isEmpty ? LoadStatus.empty : LoadStatus.success;
      final newMetadata = CacheMetadata(
        lastSuccessfulSyncAt: DateTime.now(),
        cacheSavedAt: DateTime.now(),
        dataSource: '공식 웹페이지',
        networkFailed: false,
        externalVerificationPassed: result.verificationStatus != VerificationStatus.failed,
      );
      metadata = newMetadata;
      try {
        await cacheRepository.saveWeeklyData(result.data, newMetadata);
        cachedData = result.data;
      } catch (error, stackTrace) {
        debugPrint('캐시 저장 실패: $error');
        debugPrintStack(stackTrace: stackTrace);
        transientMessage = '현재 데이터는 표시되지만 오프라인 저장에 실패했습니다.';
      }
    } catch (error, stackTrace) {
      debugPrint('현재 주 조회 실패: $error');
      debugPrintStack(stackTrace: stackTrace);
      lastNetworkFailed = true;
      errorType = error.toString().contains('샘플') || error.toString().contains('식당')
          ? ErrorType.validation
          : error.toString().contains('파싱')
              ? ErrorType.parser
              : ErrorType.network;
      if (cachedData != null) {
        currentData = cachedData;
        isUsingCache = true;
        loadStatus = LoadStatus.success;
        metadata = CacheMetadata(
          lastSuccessfulSyncAt: metadata?.lastSuccessfulSyncAt,
          cacheSavedAt: metadata?.cacheSavedAt,
          dataSource: '공식 웹페이지',
          networkFailed: true,
          externalVerificationPassed: metadata?.externalVerificationPassed ?? false,
        );
      } else {
        loadStatus = LoadStatus.error;
      }
    } finally {
      isLoading = false;
      notifyListeners();
    }
  }

  List<Restaurant> get restaurants {
    final data = currentData;
    if (data == null) return const [];
    final names = <String>[];
    for (final entry in data.entries) {
      if (!names.contains(entry.restaurantName)) {
        names.add(entry.restaurantName);
      }
    }
    return List.generate(
      names.length,
      (index) => Restaurant(name: names[index], displayOrder: index),
    );
  }

  List<Corner> cornersForRestaurant(String restaurantName) {
    final data = currentData;
    if (data == null) return const [];
    final names = <String>{};
    for (final entry in data.entries.where((e) => e.restaurantName == restaurantName)) {
      names.add(entry.cornerName.isEmpty ? '기타 코너' : entry.cornerName);
    }
    return names.map((e) => Corner(restaurantName: restaurantName, name: e)).toList();
  }

  List<MenuEntry> entriesForRestaurant(String restaurantName) {
    final data = currentData;
    if (data == null) return const [];
    final filtered = data.entries.where((e) => e.restaurantName == restaurantName).toList();
    filtered.sort((a, b) {
      final dateCompare = a.date.compareTo(b.date);
      if (dateCompare != 0) return dateCompare;
      return a.mealType.compareTo(b.mealType);
    });
    return filtered;
  }

  Future<void> selectRestaurant(String restaurantName) async {
    selectedRestaurant = restaurantName;
    notifyListeners();
    try {
      await cacheRepository.saveSelectedRestaurant(restaurantName);
    } catch (error, stackTrace) {
      debugPrint('식당 선택 저장 실패: $error');
      debugPrintStack(stackTrace: stackTrace);
    }
  }

  String get effectiveRestaurant {
    if (selectedRestaurant != null && selectedRestaurant!.trim().isNotEmpty) {
      return selectedRestaurant!;
    }
    final list = restaurants;
    return list.isNotEmpty ? list.first.name : '기타 식당';
  }
}

class HomeScreen extends StatelessWidget {
  const HomeScreen({super.key, required this.controller});

  final MenuController controller;

  @override
  Widget build(BuildContext context) {
    final data = controller.currentData;
    final effectiveRestaurant = controller.effectiveRestaurant;
    return Scaffold(
      appBar: AppBar(
        title: const Text('강원대 주간 식단'),
        actions: [
          IconButton(
            key: UniqueKey(),
            onPressed: controller.isLoading
                ? null
                : () async {
                    await controller.fetchCurrentWeek();
                    if (context.mounted && controller.transientMessage != null) {
                      ScaffoldMessenger.of(context).showSnackBar(
                        SnackBar(content: Text(controller.transientMessage!)),
                      );
                      controller.transientMessage = null;
                    }
                  },
            icon: const Icon(Icons.refresh),
            tooltip: '새로고침',
          ),
        ],
      ),
      body: RefreshIndicator(
        onRefresh: () => controller.fetchCurrentWeek(),
        child: SingleChildScrollView(
          physics: const AlwaysScrollableScrollPhysics(),
          padding: const EdgeInsets.all(16),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              StatusCard(controller: controller),
              const SizedBox(height: 12),
              Card(
                child: Padding(
                  padding: const EdgeInsets.all(16),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      const Text('현재 주 요약', style: TextStyle(fontSize: 18, fontWeight: FontWeight.bold)),
                      const SizedBox(height: 8),
                      Text(data == null
                          ? '현재 표시할 식단 데이터가 없습니다.'
                          : '${_formatDate(data.weekStart)} ~ ${_formatDate(data.weekEnd)}'),
                      const SizedBox(height: 8),
                      Text('마지막 갱신 상태: ${_lastUpdateText(controller)}'),
                      const SizedBox(height: 8),
                      Text('빠른 식당: $effectiveRestaurant'),
                    ],
                  ),
                ),
              ),
              const SizedBox(height: 12),
              if (controller.loadStatus == LoadStatus.error && controller.cachedData == null)
                EmptyStateView(
                  title: '식단을 불러오지 못했습니다',
                  message: '인터넷 연결이 필요합니다. 다시 시도하거나 앱 정보에서 출처와 안내를 확인해 주세요.',
                  primaryLabel: '다시 시도',
                  secondaryLabel: '앱 정보 보기',
                  onPrimary: () => controller.fetchCurrentWeek(),
                  onSecondary: () => Navigator.pushNamed(context, '/about'),
                )
              else ...[
                ElevatedButton.icon(
                  key: UniqueKey(),
                  onPressed: controller.restaurants.isEmpty
                      ? null
                      : () async {
                          await controller.selectRestaurant(effectiveRestaurant);
                          if (!context.mounted) return;
                          Navigator.pushNamed(
                            context,
                            '/weekly',
                            arguments: WeeklyMenuRouteArgs(
                              controller: controller,
                              restaurantName: effectiveRestaurant,
                            ),
                          );
                        },
                  icon: const Icon(Icons.restaurant_menu),
                  label: const Text('빠른 식당 식단 보기'),
                ),
                const SizedBox(height: 8),
                ElevatedButton.icon(
                  key: UniqueKey(),
                  onPressed: () {
                    Navigator.pushNamed(
                      context,
                      '/selector',
                      arguments: SelectorRouteArgs(controller: controller),
                    );
                  },
                  icon: const Icon(Icons.tune),
                  label: const Text('식당/코너 선택'),
                ),
                const SizedBox(height: 8),
                ElevatedButton.icon(
                  key: UniqueKey(),
                  onPressed: () {
                    Navigator.pushNamed(
                      context,
                      '/cache',
                      arguments: CacheRouteArgs(repository: controller.cacheRepository),
                    );
                  },
                  icon: const Icon(Icons.offline_pin),
                  label: const Text('오프라인 캐시 보기'),
                ),
                const SizedBox(height: 8),
                OutlinedButton.icon(
                  key: UniqueKey(),
                  onPressed: () => Navigator.pushNamed(context, '/about'),
                  icon: const Icon(Icons.info_outline),
                  label: const Text('앱 정보 및 출처'),
                ),
              ],
            ],
          ),
        ),
      ),
    );
  }

  String _lastUpdateText(MenuController controller) {
    final metadata = controller.metadata;
    if (metadata?.lastSuccessfulSyncAt != null) {
      return '${_formatDateTime(metadata!.lastSuccessfulSyncAt!)}${controller.isUsingCache ? ' · 캐시 표시 중' : ''}';
    }
    return '아직 동기화 기록이 없습니다.';
  }
}

class WeeklyMenuScreen extends StatelessWidget {
  const WeeklyMenuScreen({super.key, required this.controller, required this.initialRestaurant});

  final MenuController controller;
  final String initialRestaurant;

  @override
  Widget build(BuildContext context) {
    final data = controller.currentData;
    final restaurant = initialRestaurant.isNotEmpty ? initialRestaurant : controller.effectiveRestaurant;
    final entries = controller.entriesForRestaurant(restaurant);
    final corners = controller.cornersForRestaurant(restaurant);
    return Scaffold(
      appBar: AppBar(
        title: Text('$restaurant 식단표'),
      ),
      body: SingleChildScrollView(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            StatusCard(controller: controller),
            const SizedBox(height: 12),
            Card(
              child: Padding(
                padding: const EdgeInsets.all(16),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text('주간 범위: ${data == null ? '-' : '${_formatDate(data.weekStart)} ~ ${_formatDate(data.weekEnd)}'}'),
                    const SizedBox(height: 6),
                    Text('데이터 출처 URL: ${data?.sourceUrl ?? SourceConstants.primaryUrl}'),
                    const SizedBox(height: 6),
                    Text('수집 시각: ${data == null ? '-' : _formatDateTime(data.fetchedAt)}'),
                    const SizedBox(height: 6),
                    Text('캐시 여부: ${controller.isUsingCache ? '예' : '아니오'}'),
                    const SizedBox(height: 6),
                    Text('검증 상태: ${_verificationLabel(data?.verificationStatus ?? VerificationStatus.failed)}'),
                  ],
                ),
              ),
            ),
            const SizedBox(height: 12),
            Row(
              children: [
                Expanded(
                  child: OutlinedButton(
                    key: UniqueKey(),
                    onPressed: () {
                      ScaffoldMessenger.of(context).showSnackBar(
                        const SnackBar(content: Text('현재 버전에서는 현재 주 중심 조회만 지원합니다.')),
                      );
                    },
                    child: const Text('이전 주'),
                  ),
                ),
                const SizedBox(width: 8),
                Expanded(
                  child: FilledButton(
                    key: UniqueKey(),
                    onPressed: null,
                    child: const Text('현재 주'),
                  ),
                ),
                const SizedBox(width: 8),
                Expanded(
                  child: OutlinedButton(
                    key: UniqueKey(),
                    onPressed: () {
                      ScaffoldMessenger.of(context).showSnackBar(
                        const SnackBar(content: Text('현재 버전에서는 현재 주 중심 조회만 지원합니다.')),
                      );
                    },
                    child: const Text('다음 주'),
                  ),
                ),
              ],
            ),
            const SizedBox(height: 12),
            RestaurantChipList(
              restaurants: controller.restaurants.map((e) => e.name).toList(),
              selected: restaurant,
              onSelected: (value) async {
                await controller.selectRestaurant(value);
                if (!context.mounted) return;
                Navigator.pushReplacementNamed(
                  context,
                  '/weekly',
                  arguments: WeeklyMenuRouteArgs(controller: controller, restaurantName: value),
                );
              },
            ),
            const SizedBox(height: 12),
            if (entries.isEmpty)
              const EmptyStateView(
                title: '표시할 식단이 없습니다',
                message: '선택한 식당의 현재 주 식단이 비어 있습니다.',
              )
            else
              ...corners.map((corner) {
                final cornerEntries = entries.where((e) => e.cornerName == corner.name).toList();
                return Padding(
                  padding: const EdgeInsets.only(bottom: 12),
                  child: MenuSectionCard(cornerName: corner.name, entries: cornerEntries),
                );
              }),
          ],
        ),
      ),
    );
  }
}

class RestaurantSelectorScreen extends StatelessWidget {
  const RestaurantSelectorScreen({super.key, required this.controller});

  final MenuController controller;

  @override
  Widget build(BuildContext context) {
    final restaurants = controller.restaurants;
    return Scaffold(
      appBar: AppBar(title: const Text('식당/코너 선택')),
      body: SingleChildScrollView(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Text('식당 그룹', style: TextStyle(fontSize: 18, fontWeight: FontWeight.bold)),
            const SizedBox(height: 8),
            if (restaurants.isEmpty)
              const EmptyStateView(
                title: '식당 목록이 없습니다',
                message: '먼저 홈 화면에서 데이터를 새로고침해 주세요.',
              )
            else
              ...restaurants.map((restaurant) {
                final corners = controller.cornersForRestaurant(restaurant.name);
                return Card(
                  child: Padding(
                    padding: const EdgeInsets.all(16),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(restaurant.name, style: const TextStyle(fontSize: 17, fontWeight: FontWeight.bold)),
                        const SizedBox(height: 8),
                        Wrap(
                          spacing: 8,
                          runSpacing: 8,
                          children: corners
                              .map((corner) => Chip(label: Text(corner.name.isEmpty ? '기타 코너' : corner.name)))
                              .toList(),
                        ),
                        const SizedBox(height: 12),
                        ElevatedButton(
                          key: UniqueKey(),
                          onPressed: () async {
                            await controller.selectRestaurant(restaurant.name);
                            if (!context.mounted) return;
                            Navigator.pushReplacementNamed(
                              context,
                              '/weekly',
                              arguments: WeeklyMenuRouteArgs(
                                controller: controller,
                                restaurantName: restaurant.name,
                              ),
                            );
                          },
                          child: const Text('이 식당 선택'),
                        ),
                      ],
                    ),
                  ),
                );
              }),
          ],
        ),
      ),
    );
  }
}

class OfflineCacheScreen extends StatefulWidget {
  const OfflineCacheScreen({super.key, required this.repository});

  final CacheRepository repository;

  @override
  State<OfflineCacheScreen> createState() => _OfflineCacheScreenState();
}

class _OfflineCacheScreenState extends State<OfflineCacheScreen> {
  WeeklyMenuData? data;
  CacheMetadata? metadata;
  bool loading = true;

  @override
  void initState() {
    super.initState();
    unawaited(_load());
  }

  Future<void> _load() async {
    try {
      final loadedData = await widget.repository.readWeeklyData();
      final loadedMetadata = await widget.repository.readMetadata();
      if (!mounted) return;
      setState(() {
        data = loadedData;
        metadata = loadedMetadata;
        loading = false;
      });
    } catch (error, stackTrace) {
      debugPrint('오프라인 캐시 로드 오류: $error');
      debugPrintStack(stackTrace: stackTrace);
      if (!mounted) return;
      setState(() {
        loading = false;
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('오프라인 캐시 보기')),
      body: SingleChildScrollView(
        padding: const EdgeInsets.all(16),
        child: loading
            ? const Center(child: Padding(padding: EdgeInsets.all(24), child: CircularProgressIndicator()))
            : data == null
                ? EmptyStateView(
                    title: '저장된 캐시가 없습니다',
                    message: '최근 성공 캐시가 없어서 오프라인으로 표시할 수 없습니다.',
                    primaryLabel: '홈으로 돌아가기',
                    onPrimary: () => Navigator.pop(context),
                  )
                : Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Card(
                        child: Padding(
                          padding: const EdgeInsets.all(16),
                          child: Column(
                            crossAxisAlignment: CrossAxisAlignment.start,
                            children: [
                              Text('캐시 저장 시각: ${metadata?.cacheSavedAt == null ? '-' : _formatDateTime(metadata!.cacheSavedAt!)}'),
                              const SizedBox(height: 6),
                              Text('마지막 성공 동기화: ${metadata?.lastSuccessfulSyncAt == null ? '-' : _formatDateTime(metadata!.lastSuccessfulSyncAt!)}'),
                              const SizedBox(height: 6),
                              Text('네트워크 실패 대체 여부: ${metadata?.networkFailed == true ? '예' : '아니오'}'),
                            ],
                          ),
                        ),
                      ),
                      const SizedBox(height: 12),
                      ..._groupEntriesByRestaurant(data!).entries.map((entry) {
                        return Card(
                          child: Padding(
                            padding: const EdgeInsets.all(16),
                            child: Column(
                              crossAxisAlignment: CrossAxisAlignment.start,
                              children: [
                                Text(entry.key, style: const TextStyle(fontSize: 17, fontWeight: FontWeight.bold)),
                                const SizedBox(height: 8),
                                ...entry.value.map(
                                  (item) => Padding(
                                    padding: const EdgeInsets.only(bottom: 8),
                                    child: Text('${_formatDate(item.date)}(${item.weekday}) · ${item.mealType} · ${item.cornerName}\n${item.normalizedMenuText}'),
                                  ),
                                ),
                              ],
                            ),
                          ),
                        );
                      }),
                    ],
                  ),
      ),
    );
  }

  Map<String, List<MenuEntry>> _groupEntriesByRestaurant(WeeklyMenuData data) {
    final map = <String, List<MenuEntry>>{};
    for (final entry in data.entries) {
      map.putIfAbsent(entry.restaurantName, () => []).add(entry);
    }
    return map;
  }
}

class AboutScreen extends StatelessWidget {
  const AboutScreen({super.key});

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('앱 정보 및 출처')),
      body: SingleChildScrollView(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: const [
            InfoCard(
              title: '공식 출처 URL',
              lines: [
                '1차 출처: https://kangwon.ac.kr/ko/extn/37/wkmenu-mngr/list.do?campusCd=1',
                '보조 안내: https://wwwk.kangwon.ac.kr/www/selectBbsNttView.do?bbsNo=81&nttNo=169438&&pageUnit=10&searchCnd=all&pageIndex=1',
              ],
            ),
            SizedBox(height: 12),
            InfoCard(
              title: '수집 방식',
              lines: [
                '이 앱은 공개 API가 아닌 공식 웹페이지를 스크래핑하여 식단을 표시합니다.',
                '최신 식단 조회를 위해 인터넷 연결이 필요합니다.',
              ],
            ),
            SizedBox(height: 12),
            InfoCard(
              title: '파서 한계',
              lines: [
                '웹페이지 구조 변경에 민감할 수 있습니다.',
                '현재 버전은 표 구조와 주간 범위를 DOM 기준으로 읽어 줄 단위 추정보다 안정성을 높였습니다.',
                '구조 변경 또는 명확한 마이그레이션 문구 감지 시 검증 상태를 경고로 낮춰 표시합니다.',
              ],
            ),
            SizedBox(height: 12),
            InfoCard(
              title: '검증 및 캐시 기준',
              lines: [
                '응답 본문 비어 있음 여부와 식단 관련 핵심 텍스트를 먼저 검사합니다.',
                '표 구조에서 최소 1일 이상의 샘플 데이터가 추출될 때만 온라인 데이터를 확정합니다.',
                '검증 실패 시 기존 성공 캐시는 덮어쓰지 않습니다.',
              ],
            ),
          ],
        ),
      ),
    );
  }
}

class InfoCard extends StatelessWidget {
  const InfoCard({super.key, required this.title, required this.lines});

  final String title;
  final List<String> lines;

  @override
  Widget build(BuildContext context) {
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(title, style: const TextStyle(fontSize: 18, fontWeight: FontWeight.bold)),
            const SizedBox(height: 8),
            ...lines.map((line) => Padding(
                  padding: const EdgeInsets.only(bottom: 6),
                  child: Text(line),
                )),
          ],
        ),
      ),
    );
  }
}

class StatusCard extends StatelessWidget {
  const StatusCard({super.key, required this.controller});

  final MenuController controller;

  @override
  Widget build(BuildContext context) {
    final verification = controller.currentData?.verificationStatus ?? VerificationStatus.failed;
    final hasWarning = verification == VerificationStatus.warning;
    final hasError = controller.loadStatus == LoadStatus.error;
    return Card(
      color: hasError
          ? Theme.of(context).colorScheme.errorContainer
          : hasWarning
              ? Theme.of(context).colorScheme.tertiaryContainer
              : Theme.of(context).colorScheme.surfaceContainerHighest,
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              hasError ? '오류 상태' : hasWarning ? '검증 경고' : '조회 상태',
              style: const TextStyle(fontSize: 18, fontWeight: FontWeight.bold),
            ),
            const SizedBox(height: 8),
            Text(_statusMessage(controller)),
            const SizedBox(height: 8),
            Text('인터넷 연결은 최신 식단 조회에 필요하며, 실패 시 캐시 보기로 이어질 수 있습니다.'),
            if (hasWarning) ...[
              const SizedBox(height: 8),
              const Text('페이지 구조 변경 또는 명확한 이전 안내 문구가 감지되어 검증 경고를 표시합니다.'),
            ],
            if (hasError || controller.lastNetworkFailed) ...[
              const SizedBox(height: 12),
              Wrap(
                spacing: 8,
                runSpacing: 8,
                children: [
                  ElevatedButton(
                    key: UniqueKey(),
                    onPressed: () {
                      Navigator.pushNamed(
                        context,
                        '/cache',
                        arguments: CacheRouteArgs(repository: controller.cacheRepository),
                      );
                    },
                    child: const Text('캐시 보기'),
                  ),
                  OutlinedButton(
                    key: UniqueKey(),
                    onPressed: controller.isLoading ? null : () => controller.fetchCurrentWeek(),
                    child: const Text('재시도'),
                  ),
                ],
              ),
            ],
          ],
        ),
      ),
    );
  }

  String _statusMessage(MenuController controller) {
    switch (controller.loadStatus) {
      case LoadStatus.loading:
        return '현재 주 식단을 불러오는 중입니다.';
      case LoadStatus.success:
        if (controller.isUsingCache) {
          return '최신 데이터를 불러오지 못해 최근 성공 캐시를 표시하고 있습니다.';
        }
        return '현재 주 식단을 정상적으로 표시하고 있습니다.';
      case LoadStatus.empty:
        return '식단 데이터가 비어 있습니다. 다시 시도해 주세요.';
      case LoadStatus.error:
        return '최신 데이터를 불러오지 못했습니다. 인터넷 연결을 확인하거나 캐시를 확인해 주세요.';
      case LoadStatus.idle:
        return '식단 조회를 준비 중입니다.';
    }
  }
}

class MenuSectionCard extends StatelessWidget {
  const MenuSectionCard({super.key, required this.cornerName, required this.entries});

  final String cornerName;
  final List<MenuEntry> entries;

  @override
  Widget build(BuildContext context) {
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(cornerName, style: const TextStyle(fontSize: 18, fontWeight: FontWeight.bold)),
            const SizedBox(height: 8),
            ...entries.map(
              (entry) => Padding(
                padding: const EdgeInsets.only(bottom: 10),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text('${_formatDate(entry.date)}(${entry.weekday}) · ${entry.mealType}'),
                    const SizedBox(height: 4),
                    Text(entry.normalizedMenuText),
                    if (entry.hiddenOrSoldOut)
                      const Padding(
                        padding: EdgeInsets.only(top: 4),
                        child: Text('품절 또는 비표시 가능 항목이 포함되어 있습니다.'),
                      ),
                  ],
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class RestaurantChipList extends StatelessWidget {
  const RestaurantChipList({
    super.key,
    required this.restaurants,
    required this.selected,
    required this.onSelected,
  });

  final List<String> restaurants;
  final String selected;
  final ValueChanged<String> onSelected;

  @override
  Widget build(BuildContext context) {
    return Wrap(
      spacing: 8,
      runSpacing: 8,
      children: restaurants
          .map(
            (restaurant) => ChoiceChip(
              label: Text(restaurant),
              selected: restaurant == selected,
              onSelected: (_) => onSelected(restaurant),
            ),
          )
          .toList(),
    );
  }
}

class EmptyStateView extends StatelessWidget {
  const EmptyStateView({
    super.key,
    required this.title,
    required this.message,
    this.primaryLabel,
    this.secondaryLabel,
    this.onPrimary,
    this.onSecondary,
  });

  final String title;
  final String message;
  final String? primaryLabel;
  final String? secondaryLabel;
  final VoidCallback? onPrimary;
  final VoidCallback? onSecondary;

  @override
  Widget build(BuildContext context) {
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(title, style: const TextStyle(fontSize: 18, fontWeight: FontWeight.bold)),
            const SizedBox(height: 8),
            Text(message),
            if (primaryLabel != null || secondaryLabel != null) ...[
              const SizedBox(height: 12),
              Wrap(
                spacing: 8,
                runSpacing: 8,
                children: [
                  if (primaryLabel != null)
                    ElevatedButton(
                      key: UniqueKey(),
                      onPressed: onPrimary,
                      child: Text(primaryLabel!),
                    ),
                  if (secondaryLabel != null)
                    OutlinedButton(
                      key: UniqueKey(),
                      onPressed: onSecondary,
                      child: Text(secondaryLabel!),
                    ),
                ],
              ),
            ],
          ],
        ),
      ),
    );
  }
}

class WeeklyMenuRouteArgs {
  const WeeklyMenuRouteArgs({required this.controller, required this.restaurantName});

  final MenuController controller;
  final String restaurantName;
}

class SelectorRouteArgs {
  const SelectorRouteArgs({required this.controller});

  final MenuController controller;
}

class CacheRouteArgs {
  const CacheRouteArgs({required this.repository});

  final CacheRepository repository;
}

String _formatDate(DateTime date) {
  return '${date.year}.${date.month.toString().padLeft(2, '0')}.${date.day.toString().padLeft(2, '0')}';
}

String _formatDateTime(DateTime dateTime) {
  return '${_formatDate(dateTime)} ${dateTime.hour.toString().padLeft(2, '0')}:${dateTime.minute.toString().padLeft(2, '0')}';
}

String _verificationLabel(VerificationStatus status) {
  switch (status) {
    case VerificationStatus.passed:
      return '통과';
    case VerificationStatus.warning:
      return '경고';
    case VerificationStatus.failed:
      return '실패';
  }
}
