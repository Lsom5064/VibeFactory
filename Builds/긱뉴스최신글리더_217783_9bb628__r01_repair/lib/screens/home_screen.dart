import 'package:flutter/material.dart';

import '../crash_handler.dart';
import '../models/article_item.dart';
import '../services/article_service.dart';
import '../utils/url_opener.dart';
import '../widgets/article_card.dart';
import '../widgets/state_views.dart';

class HomeScreen extends StatefulWidget {
  const HomeScreen({super.key});

  @override
  State<HomeScreen> createState() => _HomeScreenState();
}

class _HomeScreenState extends State<HomeScreen> {
  final ArticleService _articleService = ArticleService();

  List<ArticleItem> items = <ArticleItem>[];
  bool isInitialLoading = false;
  bool isRefreshing = false;
  String? errorMessage;
  bool hasLoadedOnce = false;

  @override
  void initState() {
    super.initState();
    loadLatestArticles(initial: true);
  }

  Future<void> loadLatestArticles({required bool initial}) async {
    if (isInitialLoading || isRefreshing) {
      return;
    }

    if (initial) {
      setState(() {
        isInitialLoading = true;
        errorMessage = null;
      });
    } else {
      setState(() {
        isRefreshing = true;
      });
    }

    try {
      final List<ArticleItem> latestItems = await _articleService.fetchLatestArticles();
      if (!mounted) {
        return;
      }
      setState(() {
        items = latestItems;
        hasLoadedOnce = true;
        errorMessage = null;
      });
    } catch (error, stackTrace) {
      if (!mounted) {
        return;
      }

      if (items.isNotEmpty && !initial) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('새로고침에 실패했습니다. 잠시 후 다시 시도해 주세요.')),
        );
      } else {
        setState(() {
          errorMessage = '최신 글을 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.';
        });
      }
    } finally {
      if (!mounted) {
        return;
      }
      setState(() {
        isInitialLoading = false;
        isRefreshing = false;
      });
    }
  }

  Future<void> _openArticle(ArticleItem item) async {
    try {
      final bool opened = await UrlOpener.openExternal(item.url);
      if (!mounted) {
        return;
      }
      if (!opened) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('브라우저에서 링크를 열 수 없습니다.')),
        );
      }
    } catch (error, stackTrace) {
      if (!mounted) {
        return;
      }
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('브라우저에서 링크를 열 수 없습니다.')),
      );
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('긱 뉴스 최신 글 리더'),
        actions: <Widget>[
          IconButton(
            key: UniqueKey(),
            onPressed: (isInitialLoading || isRefreshing)
                ? null
                : () {
                    loadLatestArticles(initial: false);
                  },
            icon: const Icon(Icons.refresh),
            tooltip: '새로고침',
          ),
        ],
      ),
      body: SingleChildScrollView(
        physics: const AlwaysScrollableScrollPhysics(),
        child: ConstrainedBox(
          constraints: BoxConstraints(
            minHeight: MediaQuery.of(context).size.height -
                kToolbarHeight -
                MediaQuery.of(context).padding.top,
          ),
          child: RefreshIndicator(
            onRefresh: () => loadLatestArticles(initial: false),
            child: SingleChildScrollView(
              physics: const AlwaysScrollableScrollPhysics(),
              child: Padding(
                padding: const EdgeInsets.all(16),
                child: _buildBody(),
              ),
            ),
          ),
        ),
      ),
    );
  }

  Widget _buildBody() {
    if (isInitialLoading && !hasLoadedOnce) {
      return const LoadingStateView();
    }

    if (errorMessage != null && items.isEmpty) {
      return ErrorStateView(
        message: errorMessage!,
        onRetry: () {
          loadLatestArticles(initial: true);
        },
      );
    }

    if (items.isEmpty) {
      return EmptyStateView(
        onRetry: () {
          loadLatestArticles(initial: false);
        },
      );
    }

    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: <Widget>[
        if (isRefreshing)
          const Padding(
            padding: EdgeInsets.only(bottom: 12),
            child: LinearProgressIndicator(),
          ),
        ListView.separated(
          shrinkWrap: true,
          physics: const NeverScrollableScrollPhysics(),
          itemCount: items.length,
          separatorBuilder: (_, __) => const SizedBox(height: 8),
          itemBuilder: (BuildContext context, int index) {
            final ArticleItem item = items[index];
            return ArticleCard(
              item: item,
              onTap: () {
                _openArticle(item);
              },
            );
          },
        ),
      ],
    );
  }
}
