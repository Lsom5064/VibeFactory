import 'package:flutter/material.dart';

import '../models/sync_status.dart';

class StatusBanner extends StatelessWidget {
  final SyncStatus syncStatus;

  const StatusBanner({super.key, required this.syncStatus});

  @override
  Widget build(BuildContext context) {
    if (!syncStatus.isShowingCache && !syncStatus.networkFailed) {
      return const SizedBox.shrink();
    }

    return Container(
      width: double.infinity,
      color: Colors.amber.shade100,
      padding: const EdgeInsets.all(12),
      child: Text(
        syncStatus.sourceDescription,
        style: Theme.of(context).textTheme.bodyMedium,
      ),
    );
  }
}
