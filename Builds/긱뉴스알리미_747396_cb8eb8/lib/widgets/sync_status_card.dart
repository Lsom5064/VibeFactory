import 'package:flutter/material.dart';

import '../models/sync_status.dart';

class SyncStatusCard extends StatelessWidget {
  final SyncStatus status;

  const SyncStatusCard({super.key, required this.status});

  @override
  Widget build(BuildContext context) {
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text('동기화 상태', style: Theme.of(context).textTheme.titleMedium),
            const SizedBox(height: 8),
            Text('마지막 성공: ${status.lastSuccessfulSyncAt?.toLocal().toString() ?? '없음'}'),
            Text('현재 소스: ${status.currentSourceKind}'),
            Text('파서 전략: ${status.parserStrategy}'),
            Text('소스 URL: ${status.sourceUrl}'),
            Text('마지막 오류: ${status.errorState ?? '없음'}'),
          ],
        ),
      ),
    );
  }
}
