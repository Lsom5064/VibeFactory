import 'dart:convert';

import 'package:shared_preferences/shared_preferences.dart';

import '../models/feed_item.dart';
import '../models/sync_metadata.dart';

class FeedSnapshot {
  const FeedSnapshot({
    required this.items,
    required this.metadata,
  });

  final List<FeedItem> items;
  final SyncMetadata metadata;
}

class CacheService {
  static const String _snapshotKey = 'geeknews_feed_snapshot';
  static const Duration ttl = Duration(minutes: 60);

  Future<FeedSnapshot?> loadFeedSnapshot() async {
    final prefs = await SharedPreferences.getInstance();
    final raw = prefs.getString(_snapshotKey);
    if (raw == null || raw.isEmpty) {
      return null;
    }

    final decoded = jsonDecode(raw) as Map<String, dynamic>;
    final itemsJson = (decoded['items'] as List<dynamic>? ?? <dynamic>[])
        .cast<Map<String, dynamic>>();
    final metadataJson = decoded['metadata'] as Map<String, dynamic>?;
    if (metadataJson == null) {
      return null;
    }

    return FeedSnapshot(
      items: itemsJson.map(FeedItem.fromJson).toList(),
      metadata: SyncMetadata.fromJson(metadataJson),
    );
  }

  Future<void> saveFeedSnapshot({
    required List<FeedItem> items,
    required SyncMetadata metadata,
  }) async {
    final prefs = await SharedPreferences.getInstance();
    final payload = jsonEncode({
      'items': items.map((item) => item.toJson()).toList(),
      'metadata': metadata.toJson(),
    });
    await prefs.setString(_snapshotKey, payload);
  }

  bool isStale(SyncMetadata metadata) {
    final lastSyncAt = metadata.lastSyncAt;
    if (lastSyncAt == null) {
      return true;
    }
    final parsed = DateTime.tryParse(lastSyncAt);
    if (parsed == null) {
      return true;
    }
    return DateTime.now().difference(parsed) > ttl;
  }
}
