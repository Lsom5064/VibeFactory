import 'package:flutter/material.dart';
import 'package:url_launcher/url_launcher.dart';

import '../app.dart';
import '../utils/date_utils.dart';
import '../widgets/status_banner.dart';

class SettingsScreen extends StatefulWidget {
  const SettingsScreen({super.key});

  @override
  State<SettingsScreen> createState() => _SettingsScreenState();
}

class _SettingsScreenState extends State<SettingsScreen> {
  bool _refreshing = false;
  String? _summary;

  @override
  Widget build(BuildContext context) {
    final metadata = AppServices.menuFetchService.metadataNotifier.value;
    final lastSync = metadata.isNotEmpty ? metadata.first.lastSuccessfulFetchAt : null;

    return Scaffold(
      appBar: AppBar(title: const Text('설정')),
      body: SafeArea(
        child: SingleChildScrollView(
          padding: const EdgeInsets.all(16),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Card(
                child: ListTile(
                  title: const Text('전체 데이터 새로고침'),
                  subtitle: Text(_summary ?? '즐겨찾기와 알림 대상 식당을 순차 재조회합니다'),
                  trailing: _refreshing
                      ? const SizedBox(
                          width: 20,
                          height: 20,
                          child: CircularProgressIndicator(strokeWidth: 2),
                        )
                      : const Icon(Icons.refresh),
                  onTap: _refreshing
                      ? null
                      : () async {
                          setState(() {
                            _refreshing = true;
                            _summary = null;
                          });
                          final result = await AppServices.menuFetchService
                              .refreshTrackedRestaurants(
                            AppDateUtils.currentTargetDate(),
                          );
                          if (!mounted) {
                            return;
                          }
                          setState(() {
                            _refreshing = false;
                            _summary = '성공 ${result.successCount}건 · 실패 ${result.failureCount}건';
                          });
                        },
                ),
              ),
              const SizedBox(height: 12),
              Card(
                child: ListTile(
                  title: const Text('마지막 동기화 시각'),
                  subtitle: Text(lastSync ?? '기록 없음'),
                ),
              ),
              const SizedBox(height: 12),
              Card(
                child: ListTile(
                  title: const Text('캐시 상태'),
                  subtitle: Text('저장된 메타데이터 ${metadata.length}건'),
                ),
              ),
              const SizedBox(height: 12),
              FutureBuilder(
                future: AppServices.permissionService.getNotificationPermissionStatus(),
                builder: (context, snapshot) {
                  return Card(
                    child: ListTile(
                      title: const Text('알림 권한 상태'),
                      subtitle: Text(snapshot.data?.toString() ?? '확인 중'),
                      trailing: const Icon(Icons.notifications_active_outlined),
                      onTap: () async {
                        await AppServices.permissionService.openAppNotificationSettings();
                      },
                    ),
                  );
                },
              ),
              const SizedBox(height: 12),
              const Card(
                child: ListTile(
                  title: Text('재부팅 후 알림 유지 안내'),
                  subtitle: Text('저장된 활성 알림설정은 기기 재부팅 후 다시 등록됩니다.'),
                ),
              ),
              const SizedBox(height: 12),
              Card(
                child: ListTile(
                  title: const Text('공식 출처 링크'),
                  subtitle: const Text('https://kangwon.ac.kr/ko/extn/337/wkmenu-mngr/list.do'),
                  trailing: const Icon(Icons.open_in_new),
                  onTap: () async {
                    final uri = Uri.parse(
                      'https://kangwon.ac.kr/ko/extn/337/wkmenu-mngr/list.do',
                    );
                    await launchUrl(uri, mode: LaunchMode.externalApplication);
                  },
                ),
              ),
              const SizedBox(height: 12),
              const StatusBanner(
                message: '최신 식단을 불러오지 못하면 마지막 캐시 또는 빈 상태와 오류 안내를 표시합니다.',
              ),
            ],
          ),
        ),
      ),
    );
  }
}
