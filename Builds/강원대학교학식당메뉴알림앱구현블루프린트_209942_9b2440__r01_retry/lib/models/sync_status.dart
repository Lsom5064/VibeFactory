class SyncStatus {
  final String? lastAttemptAt;
  final String? lastSuccessAt;
  final bool isSuccess;
  final String? errorCode;
  final String? errorMessage;
  final bool hasCache;

  const SyncStatus({
    required this.lastAttemptAt,
    required this.lastSuccessAt,
    required this.isSuccess,
    required this.errorCode,
    required this.errorMessage,
    required this.hasCache,
  });

  factory SyncStatus.initial() {
    return const SyncStatus(
      lastAttemptAt: null,
      lastSuccessAt: null,
      isSuccess: false,
      errorCode: null,
      errorMessage: null,
      hasCache: false,
    );
  }

  SyncStatus copyWith({
    Object? lastAttemptAt = _syncSentinel,
    Object? lastSuccessAt = _syncSentinel,
    bool? isSuccess,
    Object? errorCode = _syncSentinel,
    Object? errorMessage = _syncSentinel,
    bool? hasCache,
  }) {
    return SyncStatus(
      lastAttemptAt: identical(lastAttemptAt, _syncSentinel) ? this.lastAttemptAt : lastAttemptAt as String?,
      lastSuccessAt: identical(lastSuccessAt, _syncSentinel) ? this.lastSuccessAt : lastSuccessAt as String?,
      isSuccess: isSuccess ?? this.isSuccess,
      errorCode: identical(errorCode, _syncSentinel) ? this.errorCode : errorCode as String?,
      errorMessage: identical(errorMessage, _syncSentinel) ? this.errorMessage : errorMessage as String?,
      hasCache: hasCache ?? this.hasCache,
    );
  }

  Map<String, dynamic> toJson() {
    return {
      'lastAttemptAt': lastAttemptAt,
      'lastSuccessAt': lastSuccessAt,
      'isSuccess': isSuccess,
      'errorCode': errorCode,
      'errorMessage': errorMessage,
      'hasCache': hasCache,
    };
  }

  factory SyncStatus.fromJson(Map<String, dynamic> json) {
    return SyncStatus(
      lastAttemptAt: json['lastAttemptAt'] as String?,
      lastSuccessAt: json['lastSuccessAt'] as String?,
      isSuccess: json['isSuccess'] as bool? ?? false,
      errorCode: json['errorCode'] as String?,
      errorMessage: json['errorMessage'] as String?,
      hasCache: json['hasCache'] as bool? ?? false,
    );
  }
}

const Object _syncSentinel = Object();
