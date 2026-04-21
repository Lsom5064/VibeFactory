import 'package:flutter/material.dart';

import '../models/feed_item.dart';
import '../models/permission_status_model.dart';
import '../models/sync_status.dart';
import '../models/user_settings.dart';
import '../services/crash_handler.dart';
import '../services/feed_service.dart';
import '../services/local_store.dart';
import '../services/notification_service.dart';
import '../services/permission_service.dart';
import '../widgets/feed_item_card.dart';
import '../widgets/initial_entry_card.dart';
import '../widgets/open_link_sheet.dart';
import '../widgets/permission_banner.dart';
import '../widgets/sync_status_card.dart';

class FeedScreen extends StatefulWidget {
  final LocalStore localStore;
  final FeedService feedService;
  final PermissionService permissionService;
  final NotificationService notificationService;

  const FeedScreen({
    super.key,
    required this.localStore,
    required this.feedService,
    required this.permissionService,
    required this.notificationService,
  });

  @override
  State<FeedScreen> createState() => _FeedScreenState();
}

class _FeedScreenState extends State<FeedScreen> {
  List<FeedItem> _items = <FeedItem>[];
  SyncStatus _syncStatus = SyncStatus.initial();
  UserSettings _settings = UserSettings.initial();
  PermissionStatusModel _permission = PermissionStatusModel.initial();
  bool _isLoading = true;
  bool _isRefreshing = false;
  bool _showingCache = false;
  String? _message;

  bool get _showInitialEntry =>
      !_permission.permissionRequestedBefore &&
      !_permission.notificationPermissionGranted;

  @override
  void initState() {
    super.initState();
    _initialize();
  }

  Future<void> _initialize() async {
    try {
      final cache = await widget.localStore.loadFeedCache();
      final syncStatus = await widget.localStore.loadSyncStatus();
      final settings = await widget.localStore.loadUserSettings();
      final storedPermission = await widget.localStore.loadPermissionStatus();
      final permission = await widget.permissionService.getNotificationPermissionStatus(
        requestedBefore: storedPermission.permissionRequestedBefore,
      );
      await widget.localStore.savePermissionStatus(permission);
      if (!mounted) {
        return;
      }
      setState(() {
        _items = cache;
        _syncStatus = syncStatus;
        _settings = settings;
        _permission = permission;
        _showingCache = cache.isNotEmpty;
        _isLoading = false;
      });
      await _sync(forceRefresh: false);
    } catch (error, stackTrace) {
      CrashHandler.recordError(
        error,
        stackTrace,
        context: 'FeedScreen._initialize',
      );
      if (!mounted) {
        return;
      }
      setState(() {
        _isLoading = false;
        _message = '초기 데이터를 불러오지 못했습니다.';
      });
    }
  }

  Future<void> _sync({required bool forceRefresh}) async {
    if (_isRefreshing) {
      return;
    }
    if (mounted) {
      setState(() {
        _isRefreshing = true;
        if (forceRefresh) {
          _message = null;
        }
      });
    }

    try {
      final result = await widget.feedService.syncFeed(
        existingCache: _items,
        previousStatus: _syncStatus,
        forceRefresh: forceRefresh,
      );

      if (result.success && !result.usedCache) {
        await widget.localStore.saveFeedCache(result.items);
      }
      await widget.localStore.saveSyncStatus(result.syncStatus);

      UserSettings updatedSettings = _settings;
      if (result.success &&
          result.hasNewItem &&
          _settings.notificationsEnabled &&
          _permission.notificationPermissionGranted &&
          result.latestItem != null) {
        final shown =
            await widget.notificationService.showNewFeedNotification(result.latestItem!);
        if (shown) {
          updatedSettings = _settings.copyWith(lastNotificationAt: DateTime.now());
          await widget.localStore.saveUserSettings(updatedSettings);
        }
      }

      if (!mounted) {
        return;
      }
      setState(() {
        _items = result.items;
        _syncStatus = result.syncStatus;
        _settings = updatedSettings;
        _showingCache = result.usedCache || (!result.success && result.items.isNotEmpty);
        _message = result.message;
        _isRefreshing = false;
      });
    } catch (error, stackTrace) {
      CrashHandler.recordError(
        error,
        stackTrace,
        context: 'FeedScreen._sync',
      );
      if (!mounted) {
        return;
      }
      setState(() {
        _isRefreshing = false;
        _message = _items.isNotEmpty
            ? '최신 데이터를 가져오지 못해 최근 캐시를 표시합니다.'
            : '데이터 소스 확인에 실패했습니다.';
        _showingCache = _items.isNotEmpty;
      });
    }
  }

  Future<void> _requestPermission() async {
    final granted = await showDialog<bool>(
          context: context,
          builder: (context) {
            return AlertDialog(
              title: const Text('알림 권한 안내'),
              content: const Text(
                '새 글이 올라오면 로컬 알림으로 알려드리기 위해 알림 권한이 필요합니다. 권한을 허용하지 않아도 목록 조회와 링크 열기는 계속 사용할 수 있습니다.',
              ),
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
            );
          },
        ) ??
        false;

    if (!granted) {
      final updated = _permission.copyWith(permissionRequestedBefore: true);
      await widget.localStore.savePermissionStatus(updated);
      if (!mounted) {
        return;
      }
      setState(() {
        _permission = updated;
      });
      return;
    }

    final permission =
        await widget.permissionService.requestNotificationPermissionWithRationale();
    await widget.localStore.savePermissionStatus(permission);
    if (!mounted) {
      return;
    }
    setState(() {
      _permission = permission;
      _message = permission.notificationPermissionGranted
          ? '알림 권한이 허용되었습니다.'
          : '알림 권한이 거부되었습니다. 목록 조회는 계속 가능합니다.';
    });
  }

  Future<void> _deferPermission() async {
    final updated = _permission.copyWith(permissionRequestedBefore: true);
    await widget.localStore.savePermissionStatus(updated);
    if (!mounted) {
      return;
    }
    setState(() {
      _permission = updated;
    });
  }

  Future<void> _openSettings() async {
    final opened = await widget.permissionService.openNotificationSettings();
    if (!mounted) {
      return;
    }
    if (!opened) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('시스템 설정을 열 수 없습니다. 앱 설정에서 직접 변경해 주세요.')),
      );
      return;
    }
    final refreshed = await widget.permissionService.getNotificationPermissionStatus(
      requestedBefore: true,
    );
    await widget.localStore.savePermissionStatus(refreshed);
    if (!mounted) {
      return;
    }
    setState(() {
      _permission = refreshed;
    });
  }

  void _openLinkSheet(FeedItem item) {
    showModalBottomSheet<void>(
      context: context,
      isScrollControlled: true,
      builder: (context) => OpenLinkSheet(
        title: item.title,
        url: item.linkUrl,
      ),
    );
  }

  Widget _buildEmptyState() {
    if (_items.isEmpty && _syncStatus.errorState != null) {
      return Card(
        child: Padding(
          padding: const EdgeInsets.all(20),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              const Text(
                '데이터를 불러오지 못했습니다.',
                style: TextStyle(fontWeight: FontWeight.bold),
              ),
              const SizedBox(height: 8),
              Text(_syncStatus.errorState ?? '마지막 확인에 실패했습니다.'),
              const SizedBox(height: 12),
              FilledButton(
                onPressed: () => _sync(forceRefresh: true),
                child: const Text('재시도'),
              ),
              if (!_permission.notificationPermissionGranted) ...[
                const SizedBox(height: 8),
                const Text('알림 권한이 없어도 목록 조회는 계속 가능합니다.'),
              ],
            ],
          ),
        ),
      );
    }

    return Card(
      child: Padding(
        padding: const EdgeInsets.all(20),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Text(
              '최신 글이 없습니다.',
              style: TextStyle(fontWeight: FontWeight.bold),
            ),
            const SizedBox(height: 8),
            Text(_showingCache
                ? '최근 캐시 목록만 표시 중이며 최신이 아닐 수 있습니다.'
                : '가져오기는 성공했지만 유효한 최신 글이 없습니다.'),
            if (!_permission.notificationPermissionGranted) ...[
              const SizedBox(height: 8),
              const Text('알림 없이도 목록 조회는 계속 사용할 수 있습니다.'),
            ],
          ],
        ),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('긱뉴스 알리미'),
        actions: [
          IconButton(
            onPressed: _isRefreshing ? null : () => _sync(forceRefresh: true),
            icon: _isRefreshing
                ? const SizedBox(
                    width: 18,
                    height: 18,
                    child: CircularProgressIndicator(strokeWidth: 2),
                  )
                : const Icon(Icons.refresh),
            tooltip: '새로고침',
          ),
          IconButton(
            onPressed: () => Navigator.of(context).pushNamed('/settings'),
            icon: const Icon(Icons.settings),
            tooltip: '설정',
          ),
        ],
      ),
      body: SafeArea(
        child: _isLoading
            ? const Center(child: CircularProgressIndicator())
            : RefreshIndicator(
                onRefresh: () => _sync(forceRefresh: true),
                child: ListView(
                  padding: const EdgeInsets.all(16),
                  children: [
                    if (_showInitialEntry) ...[
                      InitialEntryCard(
                        onRequestPermission: _requestPermission,
                        onLater: _deferPermission,
                      ),
                      const SizedBox(height: 12),
                    ],
                    PermissionBanner(
                      granted: _permission.notificationPermissionGranted,
                      onRequest: _requestPermission,
                      onOpenSettings: _openSettings,
                    ),
                    const SizedBox(height: 12),
                    SyncStatusCard(status: _syncStatus),
                    const SizedBox(height: 12),
                    Wrap(
                      spacing: 8,
                      runSpacing: 8,
                      children: [
                        Chip(label: Text('소스: ${_syncStatus.currentSourceKind}')),
                        Chip(label: Text('전략: ${_syncStatus.parserStrategy}')),
                        Chip(
                          label: Text(_showingCache ? '캐시 표시 중' : '원격 최신 상태'),
                        ),
                      ],
                    ),
                    if (_message != null) ...[
                      const SizedBox(height: 12),
                      Card(
                        color: _showingCache
                            ? Theme.of(context).colorScheme.surfaceContainerHighest
                            : Theme.of(context).colorScheme.surface,
                        child: Padding(
                          padding: const EdgeInsets.all(16),
                          child: Text(_message!),
                        ),
                      ),
                    ],
                    const SizedBox(height: 12),
                    if (_items.isEmpty)
                      _buildEmptyState()
                    else ...[
                      if (_showingCache) ...[
                        Card(
                          child: Padding(
                            padding: const EdgeInsets.all(16),
                            child: Text(
                              '오프라인 또는 원격 오류로 최근 캐시를 표시하고 있습니다. 최신이 아닐 수 있습니다.',
                              style: Theme.of(context).textTheme.bodyMedium,
                            ),
                          ),
                        ),
                        const SizedBox(height: 12),
                      ],
                      ..._items.map(
                        (item) => FeedItemCard(
                          item: item,
                          onTap: () => _openLinkSheet(item),
                        ),
                      ),
                    ],
                    const SizedBox(height: 24),
                  ],
                ),
              ),
      ),
    );
  }
}
