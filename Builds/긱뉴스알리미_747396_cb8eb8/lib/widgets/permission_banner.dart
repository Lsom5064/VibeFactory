import 'package:flutter/material.dart';

class PermissionBanner extends StatelessWidget {
  final bool granted;
  final VoidCallback onRequest;
  final VoidCallback onOpenSettings;

  const PermissionBanner({
    super.key,
    required this.granted,
    required this.onRequest,
    required this.onOpenSettings,
  });

  @override
  Widget build(BuildContext context) {
    if (granted) {
      return Card(
        color: Colors.green.shade50,
        child: const ListTile(
          leading: Icon(Icons.notifications_active_outlined),
          title: Text('알림 권한이 허용되어 있습니다.'),
          subtitle: Text('새 글이 감지되면 로컬 알림을 받을 수 있습니다.'),
        ),
      );
    }
    return Card(
      color: Colors.blue.shade50,
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Text('알림 권한이 필요합니다', style: TextStyle(fontWeight: FontWeight.bold)),
            const SizedBox(height: 8),
            const Text('권한이 없어도 목록 조회와 링크 열기는 계속 사용할 수 있습니다.'),
            const SizedBox(height: 12),
            Wrap(
              spacing: 8,
              children: [
                FilledButton(
                  onPressed: onRequest,
                  child: const Text('권한 요청'),
                ),
                OutlinedButton(
                  onPressed: onOpenSettings,
                  child: const Text('시스템 설정 열기'),
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }
}
