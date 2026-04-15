import 'package:flutter/material.dart';

enum HomeErrorType { none, network, server, parsing, configuration }

class StatusSection extends StatelessWidget {
  const StatusSection({
    super.key,
    required this.isLoading,
    required this.isRefreshing,
    required this.isEmpty,
    required this.errorType,
    required this.errorMessage,
    required this.onRetry,
    required this.hasData,
  });

  final bool isLoading;
  final bool isRefreshing;
  final bool isEmpty;
  final HomeErrorType errorType;
  final String? errorMessage;
  final VoidCallback onRetry;
  final bool hasData;

  @override
  Widget build(BuildContext context) {
    if (isLoading && !hasData) {
      return const Padding(
        padding: EdgeInsets.symmetric(vertical: 24),
        child: Center(child: CircularProgressIndicator()),
      );
    }

    if (errorType != HomeErrorType.none) {
      return Card(
        color: Theme.of(context).colorScheme.errorContainer,
        child: Padding(
          padding: const EdgeInsets.all(16),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(_title(), style: Theme.of(context).textTheme.titleMedium),
              const SizedBox(height: 8),
              Text(errorMessage ?? _defaultMessage()),
              const SizedBox(height: 12),
              FilledButton(
                key: UniqueKey(),
                onPressed: onRetry,
                child: const Text('다시 시도'),
              ),
            ],
          ),
        ),
      );
    }

    if (isEmpty) {
      return Card(
        child: Padding(
          padding: const EdgeInsets.all(16),
          child: Text(isRefreshing ? '캐시를 표시하는 중이며 최신 정보를 확인하고 있습니다.' : '선택한 주 또는 식당에 등록된 식단이 없습니다.'),
        ),
      );
    }

    if (isRefreshing && hasData) {
      return const Padding(
        padding: EdgeInsets.only(bottom: 8),
        child: Align(
          alignment: Alignment.centerLeft,
          child: Chip(label: Text('최신 정보로 갱신 중')),
        ),
      );
    }

    return const SizedBox.shrink();
  }

  String _title() {
    switch (errorType) {
      case HomeErrorType.network:
        return '네트워크 오류';
      case HomeErrorType.server:
        return '서버 응답 오류';
      case HomeErrorType.parsing:
        return '파싱 실패';
      case HomeErrorType.configuration:
        return '설정 오류';
      case HomeErrorType.none:
        return '';
    }
  }

  String _defaultMessage() {
    switch (errorType) {
      case HomeErrorType.network:
        return '인터넷 연결을 확인한 뒤 다시 시도해 주세요.';
      case HomeErrorType.server:
        return '서버 응답이 올바르지 않습니다. 잠시 후 다시 시도해 주세요.';
      case HomeErrorType.parsing:
        return '공식 사이트 구조가 변경되었을 수 있습니다.';
      case HomeErrorType.configuration:
        return '기본 식당 설정을 확인해 주세요.';
      case HomeErrorType.none:
        return '';
    }
  }
}
