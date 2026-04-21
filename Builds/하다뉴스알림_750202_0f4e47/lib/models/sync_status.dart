class SyncStatus {
  final String? lastSuccessSyncAt;
  final bool lastParseSuccess;
  final String sourceKind;
  final String sourceUrl;
  final String errorState;
  final List<String> verificationLog;

  const SyncStatus({
    this.lastSuccessSyncAt,
    required this.lastParseSuccess,
    required this.sourceKind,
    required this.sourceUrl,
    required this.errorState,
    this.verificationLog = const [],
  });

  factory SyncStatus.initial() {
    return const SyncStatus(
      lastParseSuccess: false,
      sourceKind: 'html',
      sourceUrl: 'https://news.hada.io/',
      errorState: '',
      verificationLog: [],
    );
  }

  SyncStatus copyWith({
    String? lastSuccessSyncAt,
    bool? lastParseSuccess,
    String? sourceKind,
    String? sourceUrl,
    String? errorState,
    List<String>? verificationLog,
  }) {
    return SyncStatus(
      lastSuccessSyncAt: lastSuccessSyncAt ?? this.lastSuccessSyncAt,
      lastParseSuccess: lastParseSuccess ?? this.lastParseSuccess,
      sourceKind: sourceKind ?? this.sourceKind,
      sourceUrl: sourceUrl ?? this.sourceUrl,
      errorState: errorState ?? this.errorState,
      verificationLog: verificationLog ?? this.verificationLog,
    );
  }

  Map<String, dynamic> toJson() => {
        'last_success_sync_at': lastSuccessSyncAt,
        'last_parse_success': lastParseSuccess,
        'source_kind': sourceKind,
        'source_url': sourceUrl,
        'error_state': errorState,
        'verification_log': verificationLog,
      };

  factory SyncStatus.fromJson(Map<String, dynamic> json) {
    return SyncStatus(
      lastSuccessSyncAt: json['last_success_sync_at'] as String?,
      lastParseSuccess: (json['last_parse_success'] ?? false) as bool,
      sourceKind: (json['source_kind'] ?? 'html') as String,
      sourceUrl: (json['source_url'] ?? 'https://news.hada.io/') as String,
      errorState: (json['error_state'] ?? '') as String,
      verificationLog: ((json['verification_log'] ?? const []) as List)
          .map((e) => e.toString())
          .toList(),
    );
  }
}
