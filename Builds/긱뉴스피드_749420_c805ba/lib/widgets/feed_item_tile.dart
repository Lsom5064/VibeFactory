import 'package:flutter/material.dart';

import '../models/feed_item.dart';

class FeedItemTile extends StatelessWidget {
  const FeedItemTile({
    super.key,
    required this.item,
    required this.onTap,
  });

  final FeedItem item;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final theme = Theme.of(context);
    return Card(
      margin: const EdgeInsets.only(bottom: 12),
      child: InkWell(
        borderRadius: BorderRadius.circular(16),
        onTap: onTap,
        child: Padding(
          padding: const EdgeInsets.all(16),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(
                item.title,
                style: theme.textTheme.titleMedium?.copyWith(fontWeight: FontWeight.w600),
              ),
              const SizedBox(height: 8),
              Text(
                item.timeOrScore ?? '메타 정보 없음',
                style: theme.textTheme.bodySmall?.copyWith(color: const Color(0xFF334155)),
              ),
              const SizedBox(height: 8),
              Text(
                item.postLink,
                maxLines: 2,
                overflow: TextOverflow.ellipsis,
                style: theme.textTheme.bodySmall?.copyWith(color: theme.colorScheme.primary),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
