import 'package:flutter/material.dart';

import '../utils/date_helper.dart';

class NotificationSettingsCard extends StatelessWidget {
  final bool notificationsEnabled;
  final int hour;
  final int minute;
  final String? permissionMessage;
  final ValueChanged<bool> onToggle;
  final VoidCallback onPickTime;
  final VoidCallback onOpenSettings;

  const NotificationSettingsCard({
    super.key,
    required this.notificationsEnabled,
    required this.hour,
    required this.minute,
    required this.permissionMessage,
    required this.onToggle,
    required this.onPickTime,
    required this.onOpenSettings,
  });

  @override
  Widget build(BuildContext context) {
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text('알림 설정', style: Theme.of(context).textTheme.titleLarge),
            const SizedBox(height: 8),
            SwitchListTile(
              key: UniqueKey(),
              contentPadding: EdgeInsets.zero,
              title: const Text('매일 메뉴 알림 받기'),
              value: notificationsEnabled,
              onChanged: onToggle,
            ),
            const SizedBox(height: 8),
            FilledButton(
              key: UniqueKey(),
              onPressed: onPickTime,
              child: Text('알림 시간 선택: ${DateHelper.formatTime(hour, minute)}'),
            ),
            if (permissionMessage != null) ...[
              const SizedBox(height: 12),
              Text(permissionMessage!),
              const SizedBox(height: 8),
              OutlinedButton(
                key: UniqueKey(),
                onPressed: onOpenSettings,
                child: const Text('시스템 설정 열기'),
              ),
            ],
          ],
        ),
      ),
    );
  }
}
