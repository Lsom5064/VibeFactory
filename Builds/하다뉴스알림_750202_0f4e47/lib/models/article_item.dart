class ArticleItem {
  final String id;
  final String title;
  final String link;
  final String sortKey;
  final String summary;
  final String sourceUrl;
  final String fetchedAt;
  final bool isRead;

  const ArticleItem({
    required this.id,
    required this.title,
    required this.link,
    required this.sortKey,
    required this.summary,
    required this.sourceUrl,
    required this.fetchedAt,
    this.isRead = false,
  });

  ArticleItem copyWith({
    String? id,
    String? title,
    String? link,
    String? sortKey,
    String? summary,
    String? sourceUrl,
    String? fetchedAt,
    bool? isRead,
  }) {
    return ArticleItem(
      id: id ?? this.id,
      title: title ?? this.title,
      link: link ?? this.link,
      sortKey: sortKey ?? this.sortKey,
      summary: summary ?? this.summary,
      sourceUrl: sourceUrl ?? this.sourceUrl,
      fetchedAt: fetchedAt ?? this.fetchedAt,
      isRead: isRead ?? this.isRead,
    );
  }

  Map<String, dynamic> toJson() => {
        'id': id,
        'title': title,
        'link': link,
        'sort_key': sortKey,
        'summary': summary,
        'source_url': sourceUrl,
        'fetched_at': fetchedAt,
        'is_read': isRead,
      };

  factory ArticleItem.fromJson(Map<String, dynamic> json) {
    return ArticleItem(
      id: (json['id'] ?? '') as String,
      title: (json['title'] ?? '') as String,
      link: (json['link'] ?? '') as String,
      sortKey: (json['sort_key'] ?? '') as String,
      summary: (json['summary'] ?? '') as String,
      sourceUrl: (json['source_url'] ?? '') as String,
      fetchedAt: (json['fetched_at'] ?? '') as String,
      isRead: (json['is_read'] ?? false) as bool,
    );
  }
}
