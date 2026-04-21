class SyncMetadata {
  const SyncMetadata({
    required this.lastSuccessfulSyncAt,
    required this.usedCache,
  });

  final String lastSuccessfulSyncAt;
  final bool usedCache;

  SyncMetadata copyWith({
    String? lastSuccessfulSyncAt,
    bool? usedCache,
  }) {
    return SyncMetadata(
      lastSuccessfulSyncAt: lastSuccessfulSyncAt ?? this.lastSuccessfulSyncAt,
      usedCache: usedCache ?? this.usedCache,
    );
  }

  Map<String, dynamic> toJson() {
    return <String, dynamic>{
      '마지막_성공_동기화_시각': lastSuccessfulSyncAt,
      '캐시_사용_여부': usedCache,
    };
  }

  factory SyncMetadata.fromJson(Map<String, dynamic> json) {
    return SyncMetadata(
      lastSuccessfulSyncAt: (json['마지막_성공_동기화_시각'] ?? '').toString(),
      usedCache: json['캐시_사용_여부'] == true,
    );
  }

  factory SyncMetadata.defaults() {
    return const SyncMetadata(
      lastSuccessfulSyncAt: '',
      usedCache: false,
    );
  }
}
