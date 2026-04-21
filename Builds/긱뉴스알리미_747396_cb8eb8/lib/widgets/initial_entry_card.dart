import 'package:flutter/material.dart';

class InitialEntryCard extends StatelessWidget {
  final VoidCallback onRequestPermission;
  final VoidCallback onLater;

  const InitialEntryCard({
    super.key,
    required this.onRequestPermission,
    required this.onLater,
  });

  @override
  Widget build(BuildContext context) {
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text('긱뉴스 알리미에 오신 것을 환영합니다', style: Theme.of(context).textTheme.titleMedium),
            const SizedBox(height: 8),
            const Text('최신 글을 빠르게 확인하고 새 글이 올라오면 알림으로 받을 수 있습니다.'),
            const SizedBox(height: 8),
            const Text('알림 권한을 허용하지 않아도 목록 조회는 계속 가능합니다.'),
            const SizedBox(height: 12),
            Wrap(
              spacing: 8,
              children: [
                FilledButton(onPressed: onRequestPermission, child: const Text('권한 요청')),
                TextButton(onPressed: onLater, child: const Text('나중에 하기')),
              ],
            ),
          ],
        ),
      ),
    );
  }
}
