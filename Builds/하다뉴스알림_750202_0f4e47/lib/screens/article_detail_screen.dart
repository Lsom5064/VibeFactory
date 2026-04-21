import 'package:flutter/material.dart';
import 'package:webview_flutter/webview_flutter.dart';

import '../models/article_item.dart';
import '../services/feed_repository.dart';
import '../services/notification_service.dart';

class ArticleDetailScreen extends StatefulWidget {
  const ArticleDetailScreen({
    super.key,
    required this.article,
    required this.notificationService,
    required this.repository,
  });

  final ArticleItem article;
  final NotificationService notificationService;
  final FeedRepository repository;

  @override
  State<ArticleDetailScreen> createState() => _ArticleDetailScreenState();
}

class _ArticleDetailScreenState extends State<ArticleDetailScreen> {
  late final WebViewController _controller;
  bool isPageLoading = true;
  bool pageLoadFailed = false;
  String errorMessage = '';

  @override
  void initState() {
    super.initState();
    widget.repository.markArticleRead(widget.article);
    _controller = WebViewController()
      ..setNavigationDelegate(
        NavigationDelegate(
          onPageStarted: (_) {
            if (!mounted) {
              return;
            }
            setState(() {
              isPageLoading = true;
              pageLoadFailed = false;
              errorMessage = '';
            });
          },
          onPageFinished: (_) {
            if (!mounted) {
              return;
            }
            setState(() {
              isPageLoading = false;
            });
          },
          onWebResourceError: (error) {
            if (!mounted) {
              return;
            }
            setState(() {
              isPageLoading = false;
              pageLoadFailed = true;
              errorMessage = error.description;
            });
          },
        ),
      )
      ..loadRequest(Uri.parse(widget.article.link));
  }

  Future<void> _retry() async {
    if (!mounted) {
      return;
    }
    setState(() {
      isPageLoading = true;
      pageLoadFailed = false;
      errorMessage = '';
    });
    await _controller.loadRequest(Uri.parse(widget.article.link));
  }

  @override
  Widget build(BuildContext context) {
    final uri = Uri.tryParse(widget.article.link);
    return Scaffold(
      appBar: AppBar(
        title: Text(
          widget.article.title.isNotEmpty ? widget.article.title : '원문 보기',
          maxLines: 1,
          overflow: TextOverflow.ellipsis,
        ),
      ),
      body: Column(
        children: [
          Padding(
            padding: const EdgeInsets.fromLTRB(16, 12, 16, 8),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  widget.article.title,
                  style: Theme.of(context).textTheme.titleMedium,
                ),
                const SizedBox(height: 4),
                Text(uri?.host ?? widget.article.link),
                const SizedBox(height: 12),
                FilledButton.tonalIcon(
                  onPressed: () async {
                    final ok = await widget.notificationService
                        .openAppSettingsOrBrowser(widget.article.link);
                    if (!ok && mounted) {
                      ScaffoldMessenger.of(context).showSnackBar(
                        const SnackBar(content: Text('브라우저를 열 수 없습니다.')),
                      );
                    }
                  },
                  icon: const Icon(Icons.open_in_browser),
                  label: const Text('외부 브라우저 열기'),
                ),
              ],
            ),
          ),
          Expanded(
            child: Stack(
              children: [
                if (!pageLoadFailed)
                  WebViewWidget(controller: _controller)
                else
                  ListView(
                    padding: const EdgeInsets.all(16),
                    children: [
                      Card(
                        child: Padding(
                          padding: const EdgeInsets.all(16),
                          child: Column(
                            crossAxisAlignment: CrossAxisAlignment.start,
                            children: [
                              const Text('콘텐츠를 불러오지 못했습니다.'),
                              const SizedBox(height: 8),
                              Text(errorMessage.isEmpty ? '다시 시도하거나 브라우저로 열어 주세요.' : errorMessage),
                              const SizedBox(height: 12),
                              Wrap(
                                spacing: 8,
                                runSpacing: 8,
                                children: [
                                  FilledButton(
                                    onPressed: _retry,
                                    child: const Text('다시 시도'),
                                  ),
                                  OutlinedButton(
                                    onPressed: () async {
                                      await widget.notificationService
                                          .openAppSettingsOrBrowser(widget.article.link);
                                    },
                                    child: const Text('브라우저 열기'),
                                  ),
                                ],
                              ),
                            ],
                          ),
                        ),
                      ),
                    ],
                  ),
                if (isPageLoading)
                  const Center(child: CircularProgressIndicator()),
              ],
            ),
          ),
        ],
      ),
    );
  }
}
