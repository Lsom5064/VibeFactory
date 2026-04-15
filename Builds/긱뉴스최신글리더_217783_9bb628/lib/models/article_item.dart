class ArticleItem {
  final String id;
  final String title;
  final String url;
  final String? publishedTime;
  final String? sourceOrAuthor;

  const ArticleItem({
    required this.id,
    required this.title,
    required this.url,
    this.publishedTime,
    this.sourceOrAuthor,
  });

  static ArticleItem? fromLocalMap(Map<String, dynamic> map, int index) {
    final String title = (map['title'] as String? ?? '').trim();
    final String url = (map['url'] as String? ?? '').trim();

    if (title.isEmpty || url.isEmpty) {
      return null;
    }

    final String? publishedTime = _normalizeOptional(map['published_time']);
    final String? sourceOrAuthor = _normalizeOptional(map['source_or_author']);

    return ArticleItem(
      id: 'local_$index',
      title: title,
      url: url,
      publishedTime: publishedTime,
      sourceOrAuthor: sourceOrAuthor,
    );
  }

  static String? _normalizeOptional(dynamic value) {
    final String normalized = (value as String? ?? '').trim();
    return normalized.isEmpty ? null : normalized;
  }
}
