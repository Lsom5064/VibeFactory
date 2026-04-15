import 'package:flutter/material.dart';

class ActionBar extends StatelessWidget {
  const ActionBar({
    super.key,
    required this.onPickDate,
    required this.onPickRestaurant,
    required this.onToggleFavorite,
    required this.onRefresh,
    required this.isFavorite,
    required this.isRefreshing,
  });

  final VoidCallback onPickDate;
  final VoidCallback onPickRestaurant;
  final VoidCallback onToggleFavorite;
  final VoidCallback onRefresh;
  final bool isFavorite;
  final bool isRefreshing;

  @override
  Widget build(BuildContext context) {
    return Wrap(
      spacing: 8,
      runSpacing: 8,
      children: [
        FilledButton.icon(
          key: UniqueKey(),
          onPressed: onPickDate,
          icon: const Icon(Icons.calendar_today),
          label: const Text('날짜 선택'),
        ),
        OutlinedButton.icon(
          key: UniqueKey(),
          onPressed: onPickRestaurant,
          icon: const Icon(Icons.storefront),
          label: const Text('식당 선택'),
        ),
        OutlinedButton.icon(
          key: UniqueKey(),
          onPressed: onToggleFavorite,
          icon: Icon(isFavorite ? Icons.star : Icons.star_border),
          label: Text(isFavorite ? '즐겨찾기 해제' : '즐겨찾기 지정'),
        ),
        FilledButton.tonalIcon(
          key: UniqueKey(),
          onPressed: isRefreshing ? null : onRefresh,
          icon: isRefreshing
              ? const SizedBox(
                  width: 16,
                  height: 16,
                  child: CircularProgressIndicator(strokeWidth: 2),
                )
              : const Icon(Icons.refresh),
          label: const Text('새로고침'),
        ),
      ],
    );
  }
}
