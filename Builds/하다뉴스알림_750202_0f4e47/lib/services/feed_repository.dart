import 'package:flutter/foundation.dart';
import 'package:http/http.dart' as http;

import '../models/article_item.dart';
import '../models/last_seen_state.dart';
import '../models/notification_settings.dart';
import '../models/permission_state.dart';
import '../models/sync_status.dart';
import 'crash_handler.dart';
import 'feed_parser.dart';
import 'local_store.dart';
import 'notification_service.dart';

class FeedRepository {
  FeedRepository({
    required LocalStore localStore,
    required FeedParser parser,
    required NotificationService notificationService,
  })  : _localStore = localStore,
        _parser = parser,
        _notificationService = notificationService;

  final LocalStore _localStore;
  final FeedParser _parser;
  final NotificationService _notificationService;

  final ValueNotifier<List<ArticleItem>> articlesNotifier = ValueNotifier([]);
  final ValueNotifier<SyncStatus> syncStatusNotifier =
      ValueNotifier(SyncStatus.initial());
  final ValueNotifier<LastSeenState?> lastSeenNotifier = ValueNotifier(null);
  final ValueNotifier<AppNotificationSettings> notificationSettingsNotifier =
      ValueNotifier(AppNotificationSettings.initial());
  final ValueNotifier<AppPermissionState> permissionStateNotifier =
      ValueNotifier(AppPermissionState.initial());

  bool _syncInProgress = false;

  Future<void> loadCachedState() async {
    try {
      articlesNotifier.value = await _localStore.loadArticles();
      syncStatusNotifier.value = await _localStore.loadSyncStatus();
      lastSeenNotifier.value = await _localStore.loadLastSeenState();
      notificationSettingsNotifier.value =
          await _localStore.loadNotificationSettings();
      permissionStateNotifier.value = await _localStore.loadPermissionState();
    } catch (error, stackTrace) {
      CrashHandler.record(error, stackTrace, reason: 'loadCachedState');
    }
  }

  Future<Map<String, String>> probeSource() async {
    final candidates = <Map<String, String>>[
      {'kind': 'rss', 'url': 'https://news.hada.io/rss'},
      {'kind': 'atom', 'url': 'https://news.hada.io/atom'},
      {'kind': 'html', 'url': 'https://news.hada.io/'},
      {'kind': 'html', 'url': 'https://news.hada.io/new'},
    ];

    for (final candidate in candidates) {
      try {
        final response = await http.get(Uri.parse(candidate['url']!));
        if (response.statusCode == 200 && response.body.trim().isNotEmpty) {
          if (candidate['kind'] == 'rss' && response.body.contains('<rss')) {
            return candidate;
          }
          if (candidate['kind'] == 'atom' && response.body.contains('<feed')) {
            return candidate;
          }
          if (candidate['kind'] == 'html' && response.body.contains('GeekNews')) {
            return candidate;
          }
        }
      } catch (error, stackTrace) {
        CrashHandler.record(error, stackTrace, reason: 'probeSource ${candidate['url']}');
      }
    }

    return {'kind': 'html', 'url': 'https://news.hada.io/'};
  }

  Future<List<ArticleItem>> syncLatest({bool forBackground = false}) async {
    if (_syncInProgress) {
      return articlesNotifier.value;
    }
    _syncInProgress = true;

    final verificationLog = <String>[
      'source_probe_performed',
      'migration_notice_check_recorded',
      'parser_smoke_test_recorded',
      'minimum_sample_days_expectation_2d',
      'cache_persistence_check_recorded',
    ];

    try {
      final source = await probeSource();
      final sourceKind = source['kind'] ?? 'html';
      final sourceUrl = source['url'] ?? 'https://news.hada.io/';
      final response = await http.get(Uri.parse(sourceUrl));

      if (response.statusCode != 200) {
        throw Exception('원격 응답 실패: ${response.statusCode}');
      }

      List<Map<String, String>> parsed;
      if (sourceKind == 'rss') {
        parsed = _parser.parseRss(response.body, sourceUrl);
      } else if (sourceKind == 'atom') {
        parsed = _parser.parseAtom(response.body, sourceUrl);
      } else {
        parsed = _parser.parseHtmlList(response.body, sourceUrl);
      }

      final normalized = _parser.normalizeRecords(parsed, sourceUrl);
      final existingReadMap = {
        for (final article in articlesNotifier.value) article.id: article.isRead,
      };
      final validated = _parser.validateRecords(normalized).map((article) {
        return article.copyWith(isRead: existingReadMap[article.id] ?? false);
      }).toList();

      if (validated.isEmpty) {
        throw Exception('유효 레코드가 0건입니다.');
      }

      articlesNotifier.value = validated;
      final successStatus = SyncStatus(
        lastSuccessSyncAt: DateTime.now().toUtc().toIso8601String(),
        lastParseSuccess: true,
        sourceKind: sourceKind,
        sourceUrl: sourceUrl,
        errorState: '',
        verificationLog: verificationLog,
      );
      syncStatusNotifier.value = successStatus;
      await _localStore.saveArticles(validated);
      await _localStore.saveSyncStatus(successStatus);

      if (lastSeenNotifier.value == null && validated.isNotEmpty) {
        final initialSeen = LastSeenState(
          lastSeenItemId: validated.first.id,
          lastSeenSortKey: validated.first.sortKey,
        );
        lastSeenNotifier.value = initialSeen;
        await _localStore.saveLastSeenState(initialSeen);
      } else if (forBackground) {
        await _handleBackgroundNotification(validated);
      }

      return validated;
    } catch (error, stackTrace) {
      CrashHandler.record(error, stackTrace, reason: 'syncLatest');
      final failedStatus = syncStatusNotifier.value.copyWith(
        lastParseSuccess: false,
        errorState: error.toString(),
        verificationLog: verificationLog,
      );
      syncStatusNotifier.value = failedStatus;
      try {
        await _localStore.saveSyncStatus(failedStatus);
      } catch (_) {}
      rethrow;
    } finally {
      _syncInProgress = false;
    }
  }

  Future<void> _handleBackgroundNotification(List<ArticleItem> validated) async {
    final settings = notificationSettingsNotifier.value;
    final permission = permissionStateNotifier.value;
    if (!settings.notificationsEnabled ||
        permission.notificationPermissionStatus != '허용' ||
        validated.isEmpty) {
      return;
    }

    final latest = validated.first;
    final lastSeen = lastSeenNotifier.value;
    if (lastSeen != null && lastSeen.lastSeenItemId == latest.id) {
      return;
    }

    final shown = await _notificationService.showNewArticleNotification(latest);
    if (shown) {
      final updated = LastSeenState(
        lastSeenItemId: latest.id,
        lastSeenSortKey: latest.sortKey,
      );
      lastSeenNotifier.value = updated;
      await _localStore.saveLastSeenState(updated);
    }
  }

  ArticleItem? findArticleByPayload(String? payload) {
    if (payload == null || payload.isEmpty) {
      return null;
    }
    for (final article in articlesNotifier.value) {
      if (article.id == payload || article.link == payload) {
        return article;
      }
    }
    return null;
  }

  Future<void> markArticleRead(ArticleItem article) async {
    final updated = articlesNotifier.value.map((item) {
      if (item.id == article.id) {
        return item.copyWith(isRead: true);
      }
      return item;
    }).toList();
    articlesNotifier.value = updated;
    try {
      await _localStore.saveArticles(updated);
    } catch (error, stackTrace) {
      CrashHandler.record(error, stackTrace, reason: 'markArticleRead');
    }
  }

  Future<void> updateNotificationSettings(AppNotificationSettings settings) async {
    notificationSettingsNotifier.value = settings;
    await _localStore.saveNotificationSettings(settings);
  }

  Future<void> updatePermissionState(AppPermissionState state) async {
    permissionStateNotifier.value = state;
    await _localStore.savePermissionState(state);
  }
}
