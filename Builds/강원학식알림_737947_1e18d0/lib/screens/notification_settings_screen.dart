import 'package:flutter/material.dart';

import '../models/user_settings.dart';
import '../services/crash_handler.dart';
import '../services/notification_service.dart';
import '../services/settings_repository.dart';
import '../widgets/permission_info_card.dart';
import '../widgets/status_card.dart';

class NotificationSettingsScreen extends StatefulWidget {
  const NotificationSettingsScreen({super.key});

  @override
  State<NotificationSettingsScreen> createState() => _NotificationSettingsScreenState();
}

class _NotificationSettingsScreenState extends State<NotificationSettingsScreen> {
  final SettingsRepository _settingsRepository = SettingsRepository();
  final NotificationService _notificationService = NotificationService();

  UserSettings _settings = UserSettings.defaults();
  bool _permissionGranted = false;
  String _message = '';
  bool _loading = true;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    try {
      final settings = await _settingsRepository.loadUserSettings();
      final permissionGranted =
          await _notificationService.checkNotificationPermission();
      if (!mounted) {
        return;
      }
      setState(() {
        _settings = settings;
        _permissionGranted = permissionGranted;
        _loading = false;
      });
    } catch (error, stackTrace) {
      await CrashHandler.logError(error, stackTrace);
      if (!mounted) {
        return;
      }
      setState(() {
        _loading = false;
        _message = '알림 설정을 불러오지 못했습니다.';
      });
    }
  }

  Future<void> _toggle(bool value) async {
    if (value && _settings.selectedRestaurantName.isEmpty) {
      setState(() {
        _message = '알림을 켜려면 먼저 식당을 선택해 주세요.';
      });
      return;
    }

    if (value && !_permissionGranted) {
      final bool granted =
          await _notificationService.requestNotificationPermissionWithPrePrompt(context);
      if (!mounted) {
        return;
      }
      if (!granted) {
        await _settingsRepository.saveNotificationEnabled(false);
        setState(() {
          _permissionGranted = false;
          _settings = _settings.copyWith(notificationEnabled: false);
          _message = '권한이 거부되어 알림은 비활성 상태로 유지됩니다.';
        });
        return;
      }
      _permissionGranted = true;
    }

    try {
      await _settingsRepository.saveNotificationEnabled(value);
      if (value) {
        await _notificationService.scheduleDaily8amNotification();
      } else {
        await _notificationService.cancelDailyNotification();
      }
      final UserSettings updated = await _settingsRepository.loadUserSettings();
      if (!mounted) {
        return;
      }
      setState(() {
        _settings = updated;
        _message = value ? '매일 오전 8시 알림이 설정되었습니다.' : '알림이 해제되었습니다.';
      });
    } catch (error, stackTrace) {
      await CrashHandler.logError(error, stackTrace);
      if (!mounted) {
        return;
      }
      setState(() {
        _message = '알림 설정 변경에 실패했습니다. 다시 시도해 주세요.';
      });
    }
  }

  Future<void> _goToRestaurantSettings() async {
    await Navigator.of(context).pushNamed('/restaurant-settings');
    await _load();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('알림 설정')),
      body: _loading
          ? const Center(child: CircularProgressIndicator())
          : SingleChildScrollView(
              padding: const EdgeInsets.all(16),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: <Widget>[
                  Card(
                    child: SwitchListTile(
                      title: const Text('알림 사용'),
                      subtitle: const Text('선택한 식당의 메뉴를 매일 오전 8시에 알려드립니다.'),
                      value: _settings.notificationEnabled,
                      onChanged: _toggle,
                    ),
                  ),
                  const SizedBox(height: 12),
                  const StatusCard(
                    title: '고정 알림 시각',
                    message: '알림 시각은 변경할 수 없으며 항상 매일 오전 8시로 유지됩니다.',
                    icon: Icons.schedule,
                  ),
                  const SizedBox(height: 12),
                  PermissionInfoCard(
                    title: _permissionGranted ? '권한 허용 상태' : '권한 요청 이유 안내',
                    body: _permissionGranted
                        ? '알림 권한이 허용되어 있습니다. 선택 식당이 있으면 오전 8시 알림을 등록할 수 있습니다.'
                        : '매일 오전 8시에 선택한 식당 메뉴를 알려주기 위한 권한입니다. 시스템 팝업은 알림을 켜려는 시점에만 요청됩니다.',
                    actionLabel: !_permissionGranted ? '식당 선택 화면으로 이동' : null,
                    onAction: !_permissionGranted ? _goToRestaurantSettings : null,
                  ),
                  const SizedBox(height: 12),
                  if (!_permissionGranted)
                    const StatusCard(
                      title: '권한 거부 시 대체 동작',
                      message: '권한이 없으면 홈 화면에서 수동으로 메뉴를 확인하고, 이 화면에서 다시 권한 요청을 시도할 수 있습니다.',
                      icon: Icons.info_outline,
                      color: Colors.orange,
                    ),
                  const SizedBox(height: 12),
                  StatusCard(
                    title: '재부팅 후 자동 재등록 안내',
                    message: _settings.notificationEnabled
                        ? '기기 재부팅 후에도 저장된 설정이 유효하면 오전 8시 알림을 다시 등록합니다.'
                        : '알림을 켜면 기기 재부팅 후에도 오전 8시 알림을 다시 등록합니다.',
                    icon: Icons.restart_alt,
                  ),
                  const SizedBox(height: 12),
                  Card(
                    child: Padding(
                      padding: const EdgeInsets.all(16),
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: <Widget>[
                          Text('현재 선택 식당', style: Theme.of(context).textTheme.titleMedium),
                          const SizedBox(height: 8),
                          Text(
                            _settings.selectedRestaurantName.isEmpty
                                ? '선택된 식당이 없습니다.'
                                : _settings.selectedRestaurantName,
                          ),
                          const SizedBox(height: 12),
                          OutlinedButton(
                            onPressed: _goToRestaurantSettings,
                            child: const Text('식당 선택으로 이동'),
                          ),
                        ],
                      ),
                    ),
                  ),
                  if (_message.isNotEmpty) ...<Widget>[
                    const SizedBox(height: 12),
                    StatusCard(
                      title: '안내',
                      message: _message,
                      icon: Icons.campaign_outlined,
                    ),
                  ],
                ],
              ),
            ),
    );
  }
}
