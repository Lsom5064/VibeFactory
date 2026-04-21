class SyncMetadata {
  const SyncMetadata({
    required this.selectedSourceUrl,
    required this.parserStrategy,
    required this.syncSuccess,
    this.lastSyncAt,
    this.errorMessage,
  });

  final String selectedSourceUrl;
  final String parserStrategy;
  final String? lastSyncAt;
  final bool syncSuccess;
  final String? errorMessage;

  SyncMetadata copyWith({
    String? selectedSourceUrl,
    String? parserStrategy,
    String? lastSyncAt,
    bool? syncSuccess,
    String? errorMessage,
  }) {
    return SyncMetadata(
      selectedSourceUrl: selectedSourceUrl ?? this.selectedSourceUrl,
      parserStrategy: parserStrategy ?? this.parserStrategy,
      lastSyncAt: lastSyncAt ?? this.lastSyncAt,
      syncSuccess: syncSuccess ?? this.syncSuccess,
      errorMessage: errorMessage,
    );
  }

  Map<String, dynamic> toJson() {
    return {
      'selectedSourceUrl': selectedSourceUrl,
      'parserStrategy': parserStrategy,
      'lastSyncAt': lastSyncAt,
      'syncSuccess': syncSuccess,
      'errorMessage': errorMessage,
    };
  }

  factory SyncMetadata.fromJson(Map<String, dynamic> json) {
    return SyncMetadata(
      selectedSourceUrl: json['selectedSourceUrl'] as String? ?? 'https://news.hada.io/new',
      parserStrategy: json['parserStrategy'] as String? ?? 'latest_posts_list_parser_with_link_extraction',
      lastSyncAt: json['lastSyncAt'] as String?,
      syncSuccess: json['syncSuccess'] as bool? ?? false,
      errorMessage: json['errorMessage'] as String?,
    );
  }
}
