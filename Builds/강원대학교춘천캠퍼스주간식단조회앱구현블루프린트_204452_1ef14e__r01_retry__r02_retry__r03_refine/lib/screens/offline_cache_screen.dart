import 'package:flutter/material.dart';
import '../widgets/empty_state_view.dart';

class OfflineCacheScreen extends StatelessWidget {
  const OfflineCacheScreen({super.key});

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('오프라인 캐시')),
      body: const SingleChildScrollView(
        padding: EdgeInsets.all(16),
        child: EmptyStateView(
          title: '캐시 데이터 없음',
          message: '저장된 오프라인 식단 데이터가 없습니다.',
          icon: Icons.cloud_off_outlined,
        ),
      ),
    );
  }
}
