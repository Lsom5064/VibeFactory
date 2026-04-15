import 'package:flutter/material.dart';

import '../models/sync_status.dart';
import '../utils/date_helper.dart';

class SyncStatusCard extends StatelessWidget {
  final SyncStatus syncStatus;
  final VoidCallback onShowError;

  const SyncStatusCard({
    super.key,
    required this.syncStatus,
    required this.onShowError,
  });

  @override
  Widget build(BuildContext context) {
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text('동기화 상태', style: Theme.of(context).textTheme.titleLarge),
            const SizedBox(height: 8),
            Text('마지막 성공: ${DateHelper.formatDisplayDateTime(syncStatus.lastSuccessAt)}'),
            Text('마지막 시도: ${DateHelper.formatDisplayDateTime(syncStatus.lastAttemptAt)}'),
            Text('오류 요약: ${syncStatus.errorMessage ?? '없음'}'),
            Text('캐시 사용 가능: ${syncStatus.hasCache ? '예' : '아니오'}'),
            const SizedBox(height: 12),
            FilledButton(
              key: UniqueKey(),
              onPressed: onShowError,
              child: const Text('오류 안내 보기'),
            ),
          ],
        ),
      ),
    );
  }
}
