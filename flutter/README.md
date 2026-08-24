# Legacy Flutter Archive

이 디렉터리는 Native Android 전환 전에 사용하던 Flutter 관련 코드, 런타임 데이터,
빌드 캐시와 배포 자료를 한곳에 보존한다.

## 구조

- `server/server.py`: 이전 Flutter APK 생성 서버 사본
- `runtime/tasks.db*`: 이전 Flutter Task 및 상호작용 DB
- `runtime/app_data.db*`: 이전 생성 앱 공유 데이터 DB
- `runtime/workspaces/`: 이전 Flutter 프로젝트와 Revision 산출물
- `runtime/.tooling/`: 이전 Pub 및 Gradle 빌드 캐시
- `runtime/profiles/`: 이전 서버 프로필 데이터
- `runtime/workspace_archive_index/`: 이전 NAS 보관 작업 인덱스
- `aws/`: 이전 Flutter 서비스의 AWS 배포 및 NAS 보관 자료

## 주의

현재 서비스는 루트의 `flutter_apk_server/`, `vibefactory/`, `BaseProject/`를 사용하며
Native 런타임 데이터는 `flutter_apk_server/native_*` 경로를 사용한다.

이 디렉터리는 복구와 과거 기록 조회를 위한 아카이브다. 이전 Flutter BaseProject는
현재 브랜치에 남아 있지 않으므로 `server/server.py`와 `aws/` 배포 파일만으로 이전
서비스를 바로 실행하거나 배포할 수는 없다.

`runtime/`의 DB와 workspace를 삭제하거나 덮어쓰지 않는다.
