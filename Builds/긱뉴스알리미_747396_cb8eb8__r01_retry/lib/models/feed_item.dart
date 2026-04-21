class FeedItem {
  final String title;
  final String linkUrl;
  final String publishedAtOrId;
  final DateTime fetchedAt;

  const FeedItem({
    required this.title,
    required this.linkUrl,
    required this.publishedAtOrId,
    required this.fetchedAt,
  });

  String get cacheKey => '$linkUrl|$publishedAtOrId';

  FeedItem copyWith({
    String? title,
    String? linkUrl,
    String? publishedAtOrId,
    DateTime? fetchedAt,
  }) {
    return FeedItem(
      title: title ?? this.title,
      linkUrl: linkUrl ?? this.linkUrl,
      publishedAtOrId: publishedAtOrId ?? this.publishedAtOrId,
      fetchedAt: fetchedAt ?? this.fetchedAt,
    );
  }

  Map<String, dynamic> toJson() {
    return {
      'title': title,
      'linkUrl': linkUrl,
      'publishedAtOrId': publishedAtOrId,
      'fetchedAt': fetchedAt.toIso8601String(),
    };
  }

  factory FeedItem.fromJson(Map<String, dynamic> json) {
    return FeedItem(
      title: (json['title'] as String? ?? '').trim(),
      linkUrl: json['linkUrl'] as String? ?? '',
      publishedAtOrId: json['publishedAtOrId'] as String? ?? '',
      fetchedAt: DateTime.tryParse(json['fetchedAt'] as String? ?? '') ?? DateTime.now(),
    );
  }
}
