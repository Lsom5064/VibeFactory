import 'package:flutter/material.dart';
import 'package:intl/intl.dart';

import '../models/permission_status_model.dart';
import '../models/sync_status.dart';
import '../models/user_settings.dart';
import '../services/crash_handler.dart';
import '../services/local_store.dart';
import '../services/permission_service.dart';

class SettingsScreen extends StatefulWidget {
  final LocalStore localStore;
  final PermissionService permissionService;

  const SettingsScreen({
    super.key,
    required this.localStore,
    required this.permissionService,
  });

  @override
  State<SettingsScreen> createState() => _SettingsScreenState();
}

class _SettingsScreenState extends State<SettingsScreen> {
  UserSettings _settings = UserSettings.initial();
  PermissionStatusModel _permission = PermissionStatusModel.initial();
  SyncStatus _syncStatus = SyncStatus.initial();
  bool _loading = true;

  String _formatDateTime(DateTime? value) {
    if (value == null) {
      return '없음';
    }
    return DateFormat('yyyy-MM-dd HH:mm').format(value.toLocal());
  }

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    try {
      final settings = await widget.localStore.loadUserSettings();
      final storedPermission = await widget.localStore.loadPermissionStatus();
      final permission = await widget.permissionService.getNotificationPermissionStatus(
        requestedBefore: storedPermission.permissionRequestedBefore,
      );
      final syncStatus = await widget.localStore.loadSyncStatus();
      await widget.localStore.savePermissionStatus(permission);
      if (!mounted) {
        return;
      }
      setState(() {
        _settings = settings;
        _permission = permission;
        _syncStatus = syncStatus;
        _loading = false;
      });
    } catch (error, stackTrace) {
      CrashHandler.recordError(
        error,
        stackTrace,
        context: 'SettingsScreen._load',
      );
      if (!mounted) {
        return;
      }
      setState(() {
        _loading = false;
      });
    }
  }

  Future<void> _toggleNotifications(bool value) async {
    try {
      if (!value) {
        final updated = _settings.copyWith(notificationsEnabled: false);
        await widget.localStore.saveUserSettings(updated);
        if (!mounted) {
          return;
        }
        setState(() {
          _settings = updated;
        });
        return;
      }

      if (!_permission.notificationPermissionGranted) {
        final proceed = await showDialog<bool>(
              context: context,
              builder: (context) {
                return AlertDialog(
                  title: const Text('알림 권한 필요'),
                  content: const Text(
                    '새 글 알림을 사용하려면 알림 권한이 필요합니다. 권한을 허용하지 않아도 목록 조회는 계속 가능합니다.',
                  ),
                  actions: [
                    TextButton(
                      onPressed: () => Navigator.of(context).pop(false),
                      child: const Text('취소'),
                    ),
                    FilledButton(
                      onPressed: () => Navigator.of(context).pop(true),
                      child: const Text('권한 요청'),
                    ),
                  ],
                );
              },
            ) ??
            false;

        if (!proceed) {
          return;
        }

        final permission =
            await widget.permissionService.requestNotificationPermissionWithRationale();
        await widget.localStore.savePermissionStatus(permission);
        if (!permission.notificationPermissionGranted) {
          if (!mounted) {
            return;
          }
          setState(() {
            _permission = permission;
          });
          ScaffoldMessenger.of(context).showSnackBar(
            const SnackBar(
              content: Text('권한이 거부되었습니다. 시스템 설정에서 다시 허용할 수 있습니다.'),
            ),
          );
          return;
        }
        _permission = permission;
      }

      final updated = _settings.copyWith(notificationsEnabled: true);
      await widget.localStore.saveUserSettings(updated);
      if (!mounted) {
        return;
      }
      setState(() {
        _settings = updated;
      });
    } catch (error, stackTrace) {
      CrashHandler.recordError(
        error,
        stackTrace,
        context: 'SettingsScreen._toggleNotifications',
      );
      if (!mounted) {
        return;
      }
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('설정을 저장하지 못했습니다. 다시 시도해 주세요.')),
      );
    }
  }

  Future<void> _openSystemSettings() async {
    final opened = await widget.permissionService.openNotificationSettings();
    if (!mounted) {
      return;
    }
    if (!opened) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(
          content: Text('시스템 설정을 열 수 없습니다. 앱 설정에서 직접 변경해 주세요.'),
        ),
      );
      return;
    }
    await _load();
  }

  Widget _buildCard({required Widget child}) {
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: child,
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('설정'),
      ),
      body: SafeArea(
        child: _loading
            ? const Center(child: CircularProgressIndicator())
            : ListView(
                padding: const EdgeInsets.all(16),
                children: [
                  _buildCard(
                    child: Row(
                      children: [
                        const Expanded(
                          child: Column(
                            crossAxisAlignment: CrossAxisAlignment.start,
                            children: [
                              Text(
                                '알림 사용',
                                style: TextStyle(fontWeight: FontWeight.bold),
                              ),
                              SizedBox(height: 8),
                              Text('새 글이 감지되면 로컬 알림으로 알려줍니다.'),
                            ],
                          ),
                        ),
                        Switch(
                          value: _settings.notificationsEnabled,
                          onChanged: _toggleNotifications,
                        ),
                      ],
                    ),
                  ),
                  _buildCard(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        const Text(
                          '알림 권한 상태',
                          style: TextStyle(fontWeight: FontWeight.bold),
                        ),
                        const SizedBox(height: 8),
                        Text(_permission.notificationPermissionGranted
                            ? '알림 권한이 허용되어 있습니다.'
                            : '알림 권한이 거부되었거나 아직 허용되지 않았습니다.'),
                        const SizedBox(height: 8),
                        const Text('권한이 없어도 목록 조회와 링크 열기는 계속 사용할 수 있습니다.'),
                      ],
                    ),
                  ),
                  _buildCard(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        const Text(
                          '백그라운드 확인 안내',
                          style: TextStyle(fontWeight: FontWeight.bold),
                        ),
                        const SizedBox(height: 8),
                        Text(_settings.backgroundSyncAllowed
                            ? '주기적 백그라운드 확인이 허용된 상태입니다. 안드로이드 정책에 따라 정확한 실시간 보장은 어렵습니다.'
                            : '백그라운드 확인이 제한된 상태입니다.'),
                      ],
                    ),
                  ),
                  _buildCard(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        const Text(
                          '데이터 소스 정보',
                          style: TextStyle(fontWeight: FontWeight.bold),
                        ),
                        const SizedBox(height: 8),
                        Text('현재 소스: ${_syncStatus.currentSourceKind}'),
                        Text('소스 URL: ${_syncStatus.sourceUrl}'),
                        Text('파서 전략: ${_syncStatus.parserStrategy}'),
                        Text('마지막 오류: ${_syncStatus.errorState ?? '없음'}'),
                      ],
                    ),
                  ),
                  _buildCard(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        const Text(
                          '마지막 동기화 및 알림 시각',
                          style: TextStyle(fontWeight: FontWeight.bold),
                        ),
                        const SizedBox(height: 8),
                        Text('마지막 성공 동기화: ${_formatDateTime(_syncStatus.lastSuccessfulSyncAt)}'),
                        Text('마지막 알림 시각: ${_formatDateTime(_settings.lastNotificationAt)}'),
                      ],
                    ),
                  ),
                  _buildCard(
                    child: const Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(
                          '문제 해결 안내',
                          style: TextStyle(fontWeight: FontWeight.bold),
                        ),
                        SizedBox(height: 8),
                        Text('• 알림이 오지 않으면 알림 권한과 앱 알림 설정을 확인하세요.'),
                        Text('• 네트워크 오류 시 최근 캐시가 표시될 수 있습니다.'),
                        Text('• 백그라운드 작업은 기기 절전 정책에 따라 지연될 수 있습니다.'),
                      ],
                    ),
                  ),
                  FilledButton(
                    onPressed: _openSystemSettings,
                    child: const Text('시스템 설정 열기'),
                  ),
                  const SizedBox(height: 24),
                ],
              ),
      ),
    );
  }
}
