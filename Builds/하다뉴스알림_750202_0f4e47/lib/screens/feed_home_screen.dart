import 'package:flutter/material.dart';

import '../models/article_item.dart';
import '../models/permission_state.dart';
import '../models/sync_status.dart';
import '../services/background_check_service.dart';
import '../services/feed_repository.dart';
import '../widgets/article_card.dart';
import '../widgets/error_info_card.dart';
import '../widgets/sync_status_banner.dart';

class FeedHomeScreen extends StatefulWidget {
  const FeedHomeScreen({
    super.key,
    required this.repository,
    required this.backgroundCheckService,
    this.initialPayload,
  });

  final FeedRepository repository;
  final BackgroundCheckService backgroundCheckService;
  final String? initialPayload;

  @override
  State<FeedHomeScreen> createState() => _FeedHomeScreenState();
}

class _FeedHomeScreenState extends State<FeedHomeScreen> {
  bool isLoadingInitial = true;
  bool isRefreshing = false;
  String inlineError = '';

  @override
  void initState() {
    super.initState();
    _initialize();
  }

  Future<void> _initialize() async {
    await widget.repository.loadCachedState();
    if (!mounted) {
      return;
    }
    setState(() {
      isLoadingInitial = false;
    });
    await _sync();
    final article = widget.repository.findArticleByPayload(widget.initialPayload);
    if (article != null && mounted) {
      await _openArticle(article);
    }
  }

  Future<void> _sync() async {
    if (isRefreshing) {
      return;
    }
    if (mounted) {
      setState(() {
        isRefreshing = true;
        inlineError = '';
      });
    }
    try {
      await widget.repository.syncLatest();
    } catch (_) {
      if (!mounted) {
        return;
      }
      setState(() {
        inlineError = '동기화에 실패했습니다. 최근 캐시가 있으면 계속 표시합니다.';
      });
    }
    if (!mounted) {
      return;
    }
    setState(() {
      isRefreshing = false;
    });
  }

  Future<void> _openArticle(ArticleItem article) async {
    await widget.repository.markArticleRead(article);
    if (!mounted) {
      return;
    }
    await Navigator.of(context).pushNamed('/article', arguments: article);
    if (!mounted) {
      return;
    }
    setState(() {});
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('하다뉴스 알림'),
        actions: [
          IconButton(
            onPressed: () => Navigator.of(context).pushNamed('/notifications'),
            icon: const Icon(Icons.notifications_outlined),
          ),
          IconButton(
            onPressed: isRefreshing ? null : _sync,
            icon: const Icon(Icons.refresh),
          ),
        ],
      ),
      body: ValueListenableBuilder<List<ArticleItem>>(
        valueListenable: widget.repository.articlesNotifier,
        builder: (context, articles, _) {
          return ValueListenableBuilder<SyncStatus>(
            valueListenable: widget.repository.syncStatusNotifier,
            builder: (context, syncStatus, __) {
              return ValueListenableBuilder<AppPermissionState>(
                valueListenable: widget.repository.permissionStateNotifier,
                builder: (context, permissionState, ___) {
                  if (isLoadingInitial && articles.isEmpty) {
                    return const Center(child: CircularProgressIndicator());
                  }

                  final errorMessage = inlineError.isNotEmpty
                      ? inlineError
                      : syncStatus.errorState;

                  return RefreshIndicator(
                    onRefresh: _sync,
                    child: ListView(
                      padding: const EdgeInsets.all(16),
                      children: [
                        SyncStatusBanner(status: syncStatus),
                        const SizedBox(height: 12),
                        if (permissionState.notificationPermissionStatus == '거부') ...[
                          const ErrorInfoCard(
                            message:
                                '알림 권한이 거부되어도 피드 조회는 계속 가능합니다. 설정 화면에서 다시 허용할 수 있습니다.',
                          ),
                          const SizedBox(height: 12),
                        ],
                        if (errorMessage.isNotEmpty) ...[
                          ErrorInfoCard(message: errorMessage),
                          const SizedBox(height: 12),
                        ],
                        if (articles.isEmpty)
                          Card(
                            child: Padding(
                              padding: const EdgeInsets.all(20),
                              child: Column(
                                crossAxisAlignment: CrossAxisAlignment.start,
                                children: [
                                  const Text('표시할 최신 글이 없습니다.'),
                                  const SizedBox(height: 8),
                                  Text(
                                    syncStatus.lastSuccessSyncAt == null
                                        ? '새로고침으로 다시 시도해 주세요.'
                                        : '마지막 성공 동기화: ${syncStatus.lastSuccessSyncAt}',
                                  ),
                                ],
                              ),
                            ),
                          )
                        else
                          ...articles.map(
                            (article) => Padding(
                              padding: const EdgeInsets.only(bottom: 12),
                              child: ArticleCard(
                                article: article,
                                onTap: () => _openArticle(article),
                              ),
                            ),
                          ),
                        const SizedBox(height: 24),
                      ],
                    ),
                  );
                },
              );
            },
          );
        },
      ),
    );
  }
}
