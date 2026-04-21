import 'package:flutter/material.dart';

import '../app.dart';
import '../models/notification_setting.dart';
import '../utils/date_utils.dart';
import '../widgets/status_banner.dart';

class NotificationSettingsScreen extends StatefulWidget {
  const NotificationSettingsScreen({
    super.key,
    required this.campusName,
    required this.restaurantName,
  });

  final String campusName;
  final String restaurantName;

  @override
  State<NotificationSettingsScreen> createState() => _NotificationSettingsScreenState();
}

class _NotificationSettingsScreenState extends State<NotificationSettingsScreen> {
  bool _enabled = false;
  TimeOfDay? _time;
  List<String> _repeatDays = <String>[];
  String? _message;
  bool _saving = false;

  @override
  void initState() {
    super.initState();
    final existing = AppServices.menuFetchService.notificationsNotifier.value.where(
      (item) =>
          item.campusName == widget.campusName &&
          item.restaurantName == widget.restaurantName,
    );
    if (existing.isNotEmpty) {
      final item = existing.first;
      _enabled = item.isEnabled;
      _repeatDays = List<String>.from(item.repeatDays);
      final parts = item.notificationTime.split(':');
      _time = TimeOfDay(
        hour: int.tryParse(parts.first) ?? 8,
        minute: int.tryParse(parts.length > 1 ? parts[1] : '0') ?? 0,
      );
    }
  }

  @override
  Widget build(BuildContext context) {
    const labels = <String>['월', '화', '수', '목', '금', '토', '일'];
    return Scaffold(
      appBar: AppBar(title: const Text('알림 설정')),
      body: SafeArea(
        child: SingleChildScrollView(
          padding: const EdgeInsets.all(16),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Card(
                child: ListTile(
                  title: Text(widget.restaurantName),
                  subtitle: Text(widget.campusName),
                ),
              ),
              const SizedBox(height: 12),
              Card(
                child: SwitchListTile(
                  value: _enabled,
                  title: const Text('알림 활성화'),
                  subtitle: const Text('반복 요일과 시간이 있을 때만 유효합니다'),
                  onChanged: (value) async {
                    if (value) {
                      final granted = await AppServices.notificationService
                          .requestPermissionWithRationale(context);
                      if (!granted) {
                        if (!mounted) {
                          return;
                        }
                        setState(() {
                          _enabled = false;
                          _message = '메뉴 조회는 계속 사용할 수 있지만 알림은 제한됩니다';
                        });
                        return;
                      }
                    }
                    setState(() {
                      _enabled = value;
                    });
                  },
                ),
              ),
              const SizedBox(height: 12),
              Card(
                child: ListTile(
                  title: const Text('알림 시간'),
                  subtitle: Text(
                    _time == null
                        ? '시간을 선택해 주세요'
                        : '${_time!.hour.toString().padLeft(2, '0')}:${_time!.minute.toString().padLeft(2, '0')}',
                  ),
                  trailing: const Icon(Icons.schedule),
                  onTap: () async {
                    final picked = await showTimePicker(
                      context: context,
                      initialTime: _time ?? const TimeOfDay(hour: 8, minute: 0),
                    );
                    if (picked != null) {
                      setState(() {
                        _time = picked;
                      });
                    }
                  },
                ),
              ),
              const SizedBox(height: 12),
              Text('반복 요일', style: Theme.of(context).textTheme.titleMedium),
              const SizedBox(height: 8),
              Wrap(
                spacing: 8,
                runSpacing: 8,
                children: labels
                    .map(
                      (label) => FilterChip(
                        label: Text(label),
                        selected: _repeatDays.contains(label),
                        onSelected: (_) {
                          setState(() {
                            if (_repeatDays.contains(label)) {
                              _repeatDays.remove(label);
                            } else {
                              _repeatDays.add(label);
                            }
                          });
                        },
                      ),
                    )
                    .toList(),
              ),
              const SizedBox(height: 12),
              SwitchListTile(
                value: _repeatDays.length == 7,
                title: const Text('매일 반복'),
                onChanged: (value) {
                  setState(() {
                    _repeatDays = value ? List<String>.from(labels) : <String>[];
                  });
                },
              ),
              if (_message != null) ...[
                const SizedBox(height: 12),
                StatusBanner(
                  message: _message!,
                  actionLabel: '설정 열기',
                  onAction: () async {
                    await AppServices.permissionService.openAppNotificationSettings();
                  },
                ),
              ],
              const SizedBox(height: 16),
              SizedBox(
                width: double.infinity,
                child: FilledButton(
                  onPressed: _saving
                      ? null
                      : () async {
                          if (_time == null) {
                            setState(() {
                              _message = '알림 시간을 선택해 주세요';
                            });
                            return;
                          }
                          if (_repeatDays.isEmpty) {
                            setState(() {
                              _message = '반복 요일을 선택해 주세요';
                            });
                            return;
                          }

                          setState(() {
                            _saving = true;
                            _message = null;
                          });

                          final setting = RestaurantNotificationSetting(
                            campusName: widget.campusName,
                            restaurantName: widget.restaurantName,
                            notificationTime:
                                '${_time!.hour.toString().padLeft(2, '0')}:${_time!.minute.toString().padLeft(2, '0')}',
                            isEnabled: _enabled,
                            repeatDays: List<String>.from(_repeatDays),
                            lastScheduledAt: DateTime.now().toIso8601String(),
                          );

                          await AppServices.menuFetchService.saveNotificationSetting(setting);
                          try {
                            if (_enabled) {
                              await AppServices.notificationService
                                  .scheduleRestaurantNotification(
                                setting,
                                AppDateUtils.currentTargetDate(),
                              );
                            } else {
                              await AppServices.notificationService
                                  .cancelRestaurantNotification(
                                widget.campusName,
                                widget.restaurantName,
                              );
                            }
                            if (!mounted) {
                              return;
                            }
                            setState(() {
                              _message = '저장되었습니다';
                            });
                          } catch (_) {
                            if (!mounted) {
                              return;
                            }
                            setState(() {
                              _message = '설정은 저장되었지만 실제 알림 등록에 실패했습니다';
                            });
                          } finally {
                            if (mounted) {
                              setState(() {
                                _saving = false;
                              });
                            }
                          }
                        },
                  child: Text(_saving ? '저장 중...' : '저장'),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
