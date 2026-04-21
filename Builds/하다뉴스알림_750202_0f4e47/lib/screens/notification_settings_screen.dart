import 'package:flutter/material.dart';

import '../services/background_check_service.dart';
import '../services/feed_repository.dart';
import '../services/notification_service.dart';

class NotificationSettingsScreen extends StatefulWidget {
  const NotificationSettingsScreen({
    super.key,
    required this.repository,
    required this.notificationService,
    required this.backgroundCheckService,
  });

  final FeedRepository repository;
  final NotificationService notificationService;
  final BackgroundCheckService backgroundCheckService;

  @override
  State<NotificationSettingsScreen> createState() =>
      _NotificationSettingsScreenState();
}

class _NotificationSettingsScreenState extends State<NotificationSettingsScreen> {
  bool settingsActionInProgress = false;

  @override
  void initState() {
    super.initState();
    _refreshPermission();
  }

  Future<void> _refreshPermission() async {
    final state = await widget.notificationService.getCurrentPermissionState();
    await widget.repository.updatePermissionState(state);
    if (!mounted) {
      return;
    }
    setState(() {});
  }

  Future<void> _toggleNotifications(bool value) async {
    if (settingsActionInProgress) {
      return;
    }
    setState(() {
      settingsActionInProgress = true;
    });
    try {
      final current = widget.repository.notificationSettingsNotifier.value;
      if (value) {
        final permission = await widget.notificationService
            .requestPermissionWithPrePrompt(context);
        await widget.repository.updatePermissionState(permission);
        final enabled = permission.notificationPermissionStatus == '허용';
        final updated = current.copyWith(
          notificationsEnabled: enabled,
          permissionPromptCompleted: true,
        );
        await widget.repository.updateNotificationSettings(updated);
        if (enabled) {
          await widget.backgroundCheckService.scheduleConservativeChecks();
        }
      } else {
        final updated = current.copyWith(notificationsEnabled: false);
        await widget.repository.updateNotificationSettings(updated);
      }
    } catch (_) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('알림 설정을 변경하지 못했습니다.')),
        );
      }
    }
    if (!mounted) {
      return;
    }
    setState(() {
      settingsActionInProgress = false;
    });
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('알림 설정')),
      body: ValueListenableBuilder(
        valueListenable: widget.repository.notificationSettingsNotifier,
        builder: (context, settings, _) {
          return ValueListenableBuilder(
            valueListenable: widget.repository.permissionStateNotifier,
            builder: (context, permission, __) {
              final enabled = settings.notificationsEnabled &&
                  permission.notificationPermissionStatus == '허용';
              return ListView(
                padding: const EdgeInsets.all(16),
                children: [
                  const Card(
                    child: Padding(
                      padding: EdgeInsets.all(16),
                      child: Text(
                        '새 글이 감지되면 안드로이드 알림으로 알려드립니다. 권한을 거부해도 피드 목록과 상세 열람은 계속 사용할 수 있습니다.',
                      ),
                    ),
                  ),
                  const SizedBox(height: 12),
                  const Card(
                    child: Padding(
                      padding: EdgeInsets.all(16),
                      child: Text(
                        '권한 요청 전 안내: 새 글 알림 제공 목적, 거부해도 피드 열람 가능, 나중에 설정에서 변경 가능함을 먼저 확인해 주세요.',
                      ),
                    ),
                  ),
                  const SizedBox(height: 12),
                  Card(
                    child: ListTile(
                      title: const Text('현재 권한 상태'),
                      subtitle: Text(permission.notificationPermissionStatus),
                      trailing: const Icon(Icons.shield_outlined),
                    ),
                  ),
                  const SizedBox(height: 12),
                  Card(
                    child: SwitchListTile(
                      value: enabled,
                      onChanged: settingsActionInProgress ? null : _toggleNotifications,
                      title: const Text('새 글 알림 활성화'),
                      subtitle: Text(settings.backgroundCheckPolicy),
                    ),
                  ),
                  const SizedBox(height: 12),
                  const Card(
                    child: Padding(
                      padding: EdgeInsets.all(16),
                      child: Text(
                        '백그라운드 확인은 배터리 사용을 고려한 보수적 정책으로 동작합니다. 중복 알림을 막기 위해 마지막 확인 항목을 저장합니다.',
                      ),
                    ),
                  ),
                  const SizedBox(height: 12),
                  OutlinedButton.icon(
                    onPressed: () async {
                      final ok = await widget.notificationService.openSystemSettings();
                      if (!ok && mounted) {
                        ScaffoldMessenger.of(context).showSnackBar(
                          const SnackBar(
                            content: Text('시스템 설정을 열지 못했습니다. 직접 알림 설정을 확인해 주세요.'),
                          ),
                        );
                      }
                    },
                    icon: const Icon(Icons.settings),
                    label: const Text('설정으로 이동'),
                  ),
                ],
              );
            },
          );
        },
      ),
    );
  }
}
