class SourceConfig {
  const SourceConfig._();

  // TODO: 실제 강원대학교 춘천캠퍼스 학생식당 공식 메뉴 웹페이지 주소를 설정하세요.
  static const String officialMenuUrl = '';

  static bool get hasOfficialUrl => officialMenuUrl.trim().isNotEmpty;
}
