class CacheMetadata {
  const CacheMetadata({
    required this.campusName,
    required this.restaurantName,
    required this.targetDate,
    this.lastSuccessfulFetchAt,
    required this.parseSucceeded,
    this.errorState,
    this.structureNeedsVerification = false,
  });

  final String campusName;
  final String restaurantName;
  final String targetDate;
  final String? lastSuccessfulFetchAt;
  final bool parseSucceeded;
  final String? errorState;
  final bool structureNeedsVerification;

  String get key => '$campusName|$restaurantName|$targetDate';

  Map<String, dynamic> toJson() => {
        'campusName': campusName,
        'restaurantName': restaurantName,
        'targetDate': targetDate,
        'lastSuccessfulFetchAt': lastSuccessfulFetchAt,
        'parseSucceeded': parseSucceeded,
        'errorState': errorState,
        'structureNeedsVerification': structureNeedsVerification,
      };

  factory CacheMetadata.fromJson(Map<String, dynamic> json) => CacheMetadata(
        campusName: json['campusName'] as String? ?? '',
        restaurantName: json['restaurantName'] as String? ?? '',
        targetDate: json['targetDate'] as String? ?? '',
        lastSuccessfulFetchAt: json['lastSuccessfulFetchAt'] as String?,
        parseSucceeded: json['parseSucceeded'] as bool? ?? false,
        errorState: json['errorState'] as String?,
        structureNeedsVerification:
            json['structureNeedsVerification'] as bool? ?? false,
      );
}
