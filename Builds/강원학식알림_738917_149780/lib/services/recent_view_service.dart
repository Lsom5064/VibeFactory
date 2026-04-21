import 'package:flutter/foundation.dart';

import '../models/recent_view_item.dart';
import 'local_storage_service.dart';

class RecentViewService {
  RecentViewService(this._storage);

  final LocalStorageService _storage;
  final ValueNotifier<List<RecentViewItem>> recentViews =
      ValueNotifier<List<RecentViewItem>>(<RecentViewItem>[]);

  Future<void> initialize() async {
    recentViews.value = await _storage.loadRecentViews();
  }

  Future<void> addRecentView(RecentViewItem item) async {
    final updated = recentViews.value.where((e) => e.key != item.key).toList();
    updated.insert(0, item);
    if (updated.length > 10) {
      updated.removeRange(10, updated.length);
    }
    recentViews.value = updated;
    await _storage.saveRecentViews(updated);
  }
}
