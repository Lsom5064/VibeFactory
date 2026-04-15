class SyncStatus {
  final DateTime? lastSyncedAt;
  final bool isSyncing;
  final String lastResult;

  const SyncStatus({
    required this.lastSyncedAt,
    required this.isSyncing,
    required this.lastResult,
  });

  factory SyncStatus.initial() {
    return const SyncStatus(
      lastSyncedAt: null,
      isSyncing: false,
      lastResult: '',
    );
  }

  SyncStatus copyWith({
    DateTime? lastSyncedAt,
    bool? isSyncing,
    String? lastResult,
  }) {
    return SyncStatus(
      lastSyncedAt: lastSyncedAt ?? this.lastSyncedAt,
      isSyncing: isSyncing ?? this.isSyncing,
      lastResult: lastResult ?? this.lastResult,
    );
  }

  Map<String, dynamic> toJson() {
    return {
      'lastSyncedAt': lastSyncedAt?.toIso8601String(),
      'isSyncing': isSyncing,
      'lastResult': lastResult,
    };
  }

  factory SyncStatus.fromJson(Map<String, dynamic> json) {
    return SyncStatus(
      lastSyncedAt: json['lastSyncedAt'] == null
          ? null
          : DateTime.tryParse(json['lastSyncedAt'] as String),
      isSyncing: json['isSyncing'] as bool? ?? false,
      lastResult: json['lastResult'] as String? ?? '',
    );
  }
}
