class LastSeenState {
  final String lastSeenItemId;
  final String lastSeenSortKey;

  const LastSeenState({
    required this.lastSeenItemId,
    required this.lastSeenSortKey,
  });

  Map<String, dynamic> toJson() => {
        'last_seen_item_id': lastSeenItemId,
        'last_seen_sort_key': lastSeenSortKey,
      };

  factory LastSeenState.fromJson(Map<String, dynamic> json) {
    return LastSeenState(
      lastSeenItemId: (json['last_seen_item_id'] ?? '') as String,
      lastSeenSortKey: (json['last_seen_sort_key'] ?? '') as String,
    );
  }
}
