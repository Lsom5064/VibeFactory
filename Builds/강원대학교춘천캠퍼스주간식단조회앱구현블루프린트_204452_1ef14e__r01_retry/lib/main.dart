import 'package:flutter/material.dart';
import 'crash_handler.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  CrashHandler.initialize("[task_id]", "[package_name]");
  runApp(const MyApp());
}

// 참고 구현 방향:
// 1) SourceConstants.secondaryUrl은 검증 출처가 아닌 안내용 상수로만 유지합니다.
// 2) MenuController.fetchCurrentWeek 시작 시 transientMessage = null; 로 초기화합니다.
// 3) MenuScraperService.fetchCurrentWeek 에서 try-catch 유지, 실패 시 예외를 다시 던지거나 의미 있는 메시지로 래핑합니다.
// 4) _parseStructuredEntries 는 특정 클래스 고정 대신 table, .flex-table.vertical.meal, role 기반 표 등 다중 후보를 순회합니다.
// 5) 각 표는 tr 단위로 읽고, 헤더 행에서 날짜를 추출한 뒤 이후 행에서 코너명/식사구분/날짜별 메뉴를 매핑합니다.
// 6) 식당명은 표 주변 제목, 직전 heading, 탭 텍스트, 문서 전체 키워드 순으로 탐색해 보정합니다.
// 7) modulo 분배 방식은 제거하고 행 단위 매핑으로 교체합니다.
// 8) 메뉴 정규화는 줄바꿈, bullet, 중복 공백, 빈 구분자 제거를 강화합니다.
// 9) 검증은 날짜와 유의미한 메뉴가 충분하면 fallback 식당/코너가 일부 있어도 허용하고, 완전 무의미한 데이터만 실패 처리합니다.
// 10) 모든 화면은 기존 Scaffold + SingleChildScrollView 구조와 Material 3 테마를 유지합니다.

class MyApp extends StatelessWidget {
  const MyApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      debugShowCheckedModeBanner: false,
      theme: ThemeData(
        useMaterial3: true,
        colorSchemeSeed: Colors.blue,
      ),
      home: const Placeholder(),
    );
  }
}
