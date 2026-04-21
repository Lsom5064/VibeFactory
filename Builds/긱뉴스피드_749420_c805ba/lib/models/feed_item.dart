class FeedItem {
  const FeedItem({
    required this.title,
    required this.postLink,
    required this.fetchedAt,
    required this.sortOrder,
    required this.cacheStatus,
    this.timeOrScore,
  });

  final String title;
  final String postLink;
  final String? timeOrScore;
  final String fetchedAt;
  final int sortOrder;
  final String cacheStatus;

  Map<String, dynamic> toJson() {
    return {
      'title': title,
      'postLink': postLink,
      'timeOrScore': timeOrScore,
      'fetchedAt': fetchedAt,
      'sortOrder': sortOrder,
      'cacheStatus': cacheStatus,
    };
  }

  factory FeedItem.fromJson(Map<String, dynamic> json) {
    return FeedItem(
      title: json['title'] as String? ?? '',
      postLink: json['postLink'] as String? ?? '',
      timeOrScore: json['timeOrScore'] as String?,
      fetchedAt: json['fetchedAt'] as String? ?? '',
      sortOrder: json['sortOrder'] as int? ?? 0,
      cacheStatus: json['cacheStatus'] as String? ?? 'cached',
    );
  }
}
