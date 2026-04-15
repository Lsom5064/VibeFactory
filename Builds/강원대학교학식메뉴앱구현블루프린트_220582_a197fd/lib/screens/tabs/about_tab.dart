import 'package:flutter/material.dart';

import '../../core/constants/source_config.dart';
import '../../core/utils/date_utils.dart';
import '../../state/app_controller.dart';

class AboutTab extends StatelessWidget {
  final AppController controller;

  const AboutTab({super.key, required this.controller});

  @override
  Widget build(BuildContext context) {
    final lastSuccessAt = controller.syncStatus.lastSuccessAt;
    return Padding(
      padding: const EdgeInsets.all(16),
      child: Card(
        child: Padding(
          padding: const EdgeInsets.all(20),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text('앱 정보', style: Theme.of(context).textTheme.titleLarge),
              const SizedBox(height: 12),
              const Text('이 앱은 강원대학교 춘천캠퍼스 학생식당 공식 웹페이지 기반 메뉴 정보를 표시하도록 설계되었습니다.'),
              const SizedBox(height: 12),
              Text('데이터 상태: ${controller.syncStatus.sourceDescription}'),
              const SizedBox(height: 8),
              Text('캐시 표시 여부: ${controller.syncStatus.isShowingCache ? '예' : '아니오'}'),
              const SizedBox(height: 8),
              Text('파싱 성공 여부: ${controller.syncStatus.parseSuccess ? '성공' : '실패'}'),
              const SizedBox(height: 8),
              Text(
                lastSuccessAt == null
                    ? '마지막 성공 갱신 시각: 없음'
                    : '마지막 성공 갱신 시각: ${AppDateUtils.formatDateTime(context, DateTime.parse(lastSuccessAt))}',
              ),
              const SizedBox(height: 8),
              Text(
                SourceConfig.hasOfficialUrl
                    ? '공식 출처 주소: ${SourceConfig.officialMenuUrl}'
                    : '공식 출처 주소: 아직 설정되지 않았습니다.',
              ),
            ],
          ),
        ),
      ),
    );
  }
}
