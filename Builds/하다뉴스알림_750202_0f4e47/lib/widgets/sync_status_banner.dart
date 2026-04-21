import 'package:flutter/material.dart';

import '../models/sync_status.dart';

class SyncStatusBanner extends StatelessWidget {
  const SyncStatusBanner({super.key, required this.status});

  final SyncStatus status;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Card(
      color: status.lastParseSuccess
          ? const Color(0xFFDCFCE7)
          : const Color(0xFFFFF7ED),
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              status.lastParseSuccess ? '동기화 정상' : '동기화 주의',
              style: theme.textTheme.titleMedium,
            ),
            const SizedBox(height: 8),
            Text('마지막 성공 동기화: ${status.lastSuccessSyncAt ?? '없음'}'),
            Text('소스 종류: ${status.sourceKind}'),
            Text('소스 URL: ${status.sourceUrl}'),
            if (status.errorState.isNotEmpty) Text('오류: ${status.errorState}'),
          ],
        ),
      ),
    );
  }
}
