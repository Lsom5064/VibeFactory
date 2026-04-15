class SyncStatus {
  final String? lastSuccessAt;
  final String sourceDescription;
  final bool parseSuccess;
  final bool isShowingCache;
  final bool networkFailed;
  final bool noData;

  const SyncStatus({
    required this.lastSuccessAt,
    required this.sourceDescription,
    required this.parseSuccess,
    required this.isShowingCache,
    required this.networkFailed,
    required this.noData,
  });

  factory SyncStatus.initial() {
    return const SyncStatus(
      lastSuccessAt: null,
      sourceDescription: '저장된 메뉴가 없습니다.',
      parseSuccess: false,
      isShowingCache: false,
      networkFailed: false,
      noData: true,
    );
  }

  SyncStatus copyWith({
    String? lastSuccessAt,
    String? sourceDescription,
    bool? parseSuccess,
    bool? isShowingCache,
    bool? networkFailed,
    bool? noData,
  }) {
    return SyncStatus(
      lastSuccessAt: lastSuccessAt ?? this.lastSuccessAt,
      sourceDescription: sourceDescription ?? this.sourceDescription,
      parseSuccess: parseSuccess ?? this.parseSuccess,
      isShowingCache: isShowingCache ?? this.isShowingCache,
      networkFailed: networkFailed ?? this.networkFailed,
      noData: noData ?? this.noData,
    );
  }

  Map<String, dynamic> toJson() => {
        'last_success_at': lastSuccessAt,
        'source_description': sourceDescription,
        'parse_success': parseSuccess,
        'is_showing_cache': isShowingCache,
        'network_failed': networkFailed,
        'no_data': noData,
      };

  factory SyncStatus.fromJson(Map<String, dynamic> json) {
    return SyncStatus(
      lastSuccessAt: json['last_success_at'] as String?,
      sourceDescription: json['source_description'] as String? ?? '',
      parseSuccess: json['parse_success'] as bool? ?? false,
      isShowingCache: json['is_showing_cache'] as bool? ?? false,
      networkFailed: json['network_failed'] as bool? ?? false,
      noData: json['no_data'] as bool? ?? true,
    );
  }
}
