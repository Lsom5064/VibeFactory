import '../models/feed_item.dart';
import '../models/sync_metadata.dart';
import '../services/cache_service.dart';
import '../services/crash_handler.dart';
import '../services/geeknews_service.dart';

class FeedRepositoryResult {
  const FeedRepositoryResult({
    required this.items,
    required this.metadata,
    required this.isStale,
    required this.hasCache,
    this.errorMessage,
    this.isEmpty = false,
  });

  final List<FeedItem> items;
  final SyncMetadata metadata;
  final bool isStale;
  final bool hasCache;
  final String? errorMessage;
  final bool isEmpty;
}

class FeedRepository {
  FeedRepository({
    CacheService? cacheService,
    GeekNewsService? geekNewsService,
  })  : _cacheService = cacheService ?? CacheService(),
        _geekNewsService = geekNewsService ?? GeekNewsService();

  final CacheService _cacheService;
  final GeekNewsService _geekNewsService;

  Future<FeedRepositoryResult?> restoreCache() async {
    try {
      final snapshot = await _cacheService.loadFeedSnapshot();
      if (snapshot == null) {
        return null;
      }
      return FeedRepositoryResult(
        items: snapshot.items,
        metadata: snapshot.metadata,
        isStale: _cacheService.isStale(snapshot.metadata),
        hasCache: snapshot.items.isNotEmpty,
      );
    } catch (error, stack) {
      await CrashHandler.recordError(error, stack, fatal: false);
      return null;
    }
  }

  Future<FeedRepositoryResult> syncFeed() async {
    final cached = await restoreCache();
    try {
      final result = await _geekNewsService.fetchLatestFeed();
      if (result.items.isEmpty) {
        return FeedRepositoryResult(
          items: cached?.items ?? <FeedItem>[],
          metadata: result.metadata.copyWith(
            syncSuccess: false,
            errorMessage: '최신 글이 없거나 읽을 수 없습니다.',
          ),
          isStale: cached != null,
          hasCache: cached != null,
          errorMessage: '최신 글이 없거나 읽을 수 없습니다.',
          isEmpty: true,
        );
      }

      await _cacheService.saveFeedSnapshot(items: result.items, metadata: result.metadata);
      return FeedRepositoryResult(
        items: result.items,
        metadata: result.metadata,
        isStale: false,
        hasCache: true,
      );
    } catch (error, stack) {
      await CrashHandler.recordError(error, stack, fatal: false);
      final metadata = (cached?.metadata ??
              const SyncMetadata(
                selectedSourceUrl: GeekNewsService.primaryUrl,
                parserStrategy: GeekNewsService.parserStrategy,
                syncSuccess: false,
              ))
          .copyWith(
        syncSuccess: false,
        errorMessage: '불러오기에 실패했습니다. 네트워크 또는 소스 구조를 확인해 주세요.',
      );
      return FeedRepositoryResult(
        items: cached?.items ?? <FeedItem>[],
        metadata: metadata,
        isStale: cached != null,
        hasCache: cached != null,
        errorMessage: metadata.errorMessage,
        isEmpty: false,
      );
    }
  }
}
