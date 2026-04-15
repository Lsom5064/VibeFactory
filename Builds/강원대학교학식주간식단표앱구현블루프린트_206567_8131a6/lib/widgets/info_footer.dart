import 'package:flutter/material.dart';

import '../core/constants/app_colors.dart';
import '../core/utils/date_utils.dart';

class InfoFooter extends StatelessWidget {
  const InfoFooter({
    super.key,
    required this.notices,
    required this.sourceUrl,
    required this.lastUpdated,
  });

  final List<String> notices;
  final String sourceUrl;
  final DateTime? lastUpdated;

  @override
  Widget build(BuildContext context) {
    final items = <String>[
      ...notices,
      if (sourceUrl.isNotEmpty) '출처: $sourceUrl',
      if (lastUpdated != null) '마지막 갱신: ${AppDateUtils.formatDate(lastUpdated!)}',
    ];

    if (items.isEmpty) {
      return const SizedBox.shrink();
    }

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: items
          .map(
            (item) => Padding(
              padding: const EdgeInsets.only(bottom: 6),
              child: Text(
                item,
                style: Theme.of(context).textTheme.bodySmall?.copyWith(
                      color: AppColors.infoText,
                    ),
              ),
            ),
          )
          .toList(),
    );
  }
}
