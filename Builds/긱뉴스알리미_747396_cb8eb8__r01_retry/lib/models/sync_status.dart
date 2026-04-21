class SyncStatus {
  final DateTime? lastSuccessfulSyncAt;
  final String? lastCheckedLatestItemId;
  final String currentSourceKind;
  final String sourceUrl;
  final String parserStrategy;
  final String? errorState;

  const SyncStatus({
    required this.lastSuccessfulSyncAt,
    required this.lastCheckedLatestItemId,
    required this.currentSourceKind,
    required this.sourceUrl,
    required this.parserStrategy,
    required this.errorState,
  });

  factory SyncStatus.initial() {
    return const SyncStatus(
      lastSuccessfulSyncAt: null,
      lastCheckedLatestItemId: null,
      currentSourceKind: 'api_or_rss_or_web',
      sourceUrl: 'https://news.hada.io/',
      parserStrategy: 'none',
      errorState: null,
    );
  }

  SyncStatus copyWith({
    DateTime? lastSuccessfulSyncAt,
    String? lastCheckedLatestItemId,
    String? currentSourceKind,
    String? sourceUrl,
    String? parserStrategy,
    String? errorState,
    bool clearError = false,
  }) {
    return SyncStatus(
      lastSuccessfulSyncAt: lastSuccessfulSyncAt ?? this.lastSuccessfulSyncAt,
      lastCheckedLatestItemId: lastCheckedLatestItemId ?? this.lastCheckedLatestItemId,
      currentSourceKind: currentSourceKind ?? this.currentSourceKind,
      sourceUrl: sourceUrl ?? this.sourceUrl,
      parserStrategy: parserStrategy ?? this.parserStrategy,
      errorState: clearError ? null : (errorState ?? this.errorState),
    );
  }

  Map<String, dynamic> toJson() {
    return {
      'lastSuccessfulSyncAt': lastSuccessfulSyncAt?.toIso8601String(),
      'lastCheckedLatestItemId': lastCheckedLatestItemId,
      'currentSourceKind': currentSourceKind,
      'sourceUrl': sourceUrl,
      'parserStrategy': parserStrategy,
      'errorState': errorState,
    };
  }

  factory SyncStatus.fromJson(Map<String, dynamic> json) {
    return SyncStatus(
      lastSuccessfulSyncAt: DateTime.tryParse(json['lastSuccessfulSyncAt'] as String? ?? ''),
      lastCheckedLatestItemId: json['lastCheckedLatestItemId'] as String?,
      currentSourceKind: json['currentSourceKind'] as String? ?? 'api_or_rss_or_web',
      sourceUrl: json['sourceUrl'] as String? ?? 'https://news.hada.io/',
      parserStrategy: json['parserStrategy'] as String? ?? 'none',
      errorState: json['errorState'] as String?,
    );
  }
}
