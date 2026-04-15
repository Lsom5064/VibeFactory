import 'package:flutter/material.dart';

import '../models/sync_status.dart';
import '../utils/date_helper.dart';

class ErrorStatusCard extends StatelessWidget {
  final SyncStatus syncStatus;
  final VoidCallback onRetry;

  const ErrorStatusCard({
    super.key,
    required this.syncStatus,
    required this.onRetry,
  });

  @override
  Widget build(BuildContext context) {
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text('오류 안내', style: Theme.of(context).textTheme.titleLarge),
            const SizedBox(height: 8),
            Text('마지막 실패 사유: ${syncStatus.errorMessage ?? '오류 정보 없음'}'),
            Text('마지막 성공 시각: ${DateHelper.formatDisplayDateTime(syncStatus.lastSuccessAt)}'),
            Text('캐시 존재 여부: ${syncStatus.hasCache ? '있음' : '없음'}'),
            const SizedBox(height: 12),
            FilledButton(
              key: UniqueKey(),
              onPressed: onRetry,
              child: const Text('다시 시도'),
            ),
          ],
        ),
      ),
    );
  }
}
