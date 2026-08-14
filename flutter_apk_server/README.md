# Native Android APK Builder Server

Android 호스트 앱의 생성·수정 요청을 받아 Task별 workspace를 만들고, Codex CLI가 Kotlin과 Android Views/XML 소스를 구현한 뒤 서버가 Gradle lint, signed release APK 빌드, 식별자·서명 검증을 수행하는 FastAPI 서버다.

전체 Flutter에서 Native Android로의 전환 절차와 완료 기준은 저장소 루트의 `NATIVE_ANDROID_MIGRATION_PLAN.md`를 따른다.

## 구조

- `server.py`: HTTP API, Task/로그/사용량/런타임 데이터 orchestration, worker queue
- `project_builder.py`: Native Android 프로젝트 복사, 식별자 적용, 검증, Gradle build 단계, APK 탐색·검증, 캐시 정리
- `tests/`: DB integrity, API contract, 전체 로깅·이미지, Native workspace 테스트
- `../BaseProject/`: Kotlin + Android Views/XML 생성 템플릿

생성 프로젝트의 필수 파일은 다음과 같다.

```text
app/src/main/kotlin/kr/ac/kangwon/hai/generated/MainActivity.kt
app/src/main/res/layout/activity_main.xml
```

## 로컬 실행

저장소 루트에서 실행한다.

```bash
./run-local-server.sh
```

기본값은 기존 Flutter 데이터와 분리된다.

```text
DB_PATH=flutter_apk_server/native_tasks.db
APP_DATA_DB_PATH=flutter_apk_server/native_app_data.db
WORKSPACES_ROOT=flutter_apk_server/native_workspaces
BUILD_CACHE_ROOT=flutter_apk_server/.native_tooling
```

기존 `tasks.db`, `app_data.db`, `workspaces/`는 마이그레이션 이전 데이터이므로 새 서비스에서 쓰지 않으며 삭제하지 않는다.

## 필수 환경

실제 생성에는 다음 도구가 필요하다.

- Python 3.11 이상
- JDK 17
- Android SDK Platform 36
- Android Build Tools 및 `apksigner`
- Codex CLI 로그인
- release APK 서명키

주요 환경변수:

```text
BASE_PROJECT_PATH
WORKSPACES_ROOT
DB_PATH
APP_DATA_DB_PATH
BUILD_CACHE_ROOT
SERVER_BASE_URL

CODEX_COMMAND
CODEX_MODEL
CODEX_REASONING_EFFORT
CODEX_SERVICE_TIER
CODEX_FAST_MODE
CODEX_TIMEOUT_SECONDS
MAX_CONCURRENT_CODEX_RUNS

GENERATED_APP_KEYSTORE_PATH
GENERATED_APP_KEYSTORE_PASSWORD
GENERATED_APP_KEY_ALIAS
GENERATED_APP_KEY_PASSWORD
```

로컬 실행 스크립트는 기본적으로 다음 Git 외부 파일을 읽는다.

```text
~/.vibefactory/signing/generated-app-signing.env
```

서명키와 비밀번호는 Git에 추가하지 않는다. 실제 빌드에서 서명 설정이 없거나 keystore 파일을 찾을 수 없으면 서버는 Task를 명확한 build 실패로 종료한다.

## Mock 실행

```bash
MOCK_CODEX=1 INTENT_AGENT_ENABLED=0 ./run-local-server.sh
```

Mock 모드는 Codex, Android SDK, 서명키 없이 API와 Task 상태 흐름을 검증한다. Native workspace와 release APK 경로 계약은 그대로 사용하지만 APK bytes는 설치 가능한 실제 산출물이 아니다.

## 빌드 계약

Codex는 Kotlin/XML 구현과 정적 확인만 담당한다. 서버는 성공 결과를 받은 뒤 항상 다음 단계를 직접 수행한다.

```bash
./gradlew :app:lintDebug
./gradlew :app:assembleRelease
```

APK 경로:

```text
project/app/build/outputs/apk/release/app-release.apk
```

서버는 다음을 검증한다.

- `applicationId`가 Task의 `package_name`과 일치
- version code가 sideload 고정값 `2100000000`과 일치
- APK 파일이 비어 있지 않음
- `apksigner verify` 통과
- Kotlin/XML 필수 파일과 서버 관리 Gradle 계약 유지
- Flutter/Dart와 Jetpack Compose가 생성되지 않음
- 기본 템플릿 화면이 실제 구현으로 변경됨

성공 후 프로젝트 build cache는 release APK와 `output-metadata.json`만 남기고 정리한다. 공유 Gradle cache는 warm build를 위해 `BUILD_CACHE_ROOT`에 유지한다. 리비전과 분기는 build, `.gradle`, `.tooling`, `.kotlin`, `__pycache__`와 프로젝트 루트의 중복 `logs`, `.codex_result`를 복사하지 않는다.

## 생성 앱 런타임

Native 템플릿은 다음 Kotlin client를 제공한다.

- `VibeLlmClient`: `/apps/{task_id}/llm/respond`, 텍스트·이미지 요청
- `VibeDataClient`: `/apps/{task_id}/data/{collection}` CRUD
- `VibeCrashReporter`: `kr.ac.kangwon.hai.action.CRASH_REPORT` explicit broadcast
- `VibeHttpClient`: coroutine cancellation과 연결된 비동기 OkHttp 요청

package name은 `applicationContext.packageName`, Task ID는 `BuildConfig.VIBE_TASK_ID`, 빌드 대상 서버 주소는 `BuildConfig.VIBE_SERVER_BASE_URL`을 사용한다. 서버는 LLM 입력, system prompt, context, 이미지 메타데이터, raw response, parsed response, 오류 응답을 축약하지 않고 기록한다.

Codex와 사용량 조회 subprocess에는 keystore 비밀번호, 런타임 API 키, 관리자 토큰을 전달하지 않는다. release Gradle subprocess에만 서명 환경변수 4개를 제한적으로 전달한다.

## API 계약

호스트 앱과 유지하는 핵심 endpoint:

```text
GET    /tasks
POST   /generate
GET    /status/{task_id}
POST   /tasks/{task_id}/cancel
PATCH  /tasks/{task_id}
GET    /tasks/{task_id}/usage
GET    /tasks/{task_id}/revisions
POST   /tasks/{task_id}/revisions/{revision_label}/branch
POST   /tasks/{task_id}/runtime-error
GET    /download/{task_id}
POST   /apps/{task_id}/llm/respond
GET|POST|PATCH|DELETE /apps/{task_id}/data/{collection}...
```

`/download`는 APK media type, `Content-Length`, `Content-Disposition`, byte Range 응답을 유지한다.

## 테스트

```bash
flutter_apk_server/.venv/bin/python -m unittest discover \
  -s flutter_apk_server/tests -p 'test_*.py' -v

cd BaseProject
source ~/.vibefactory/signing/generated-app-signing.env
./gradlew :app:lintDebug :app:compileDebugKotlin :app:assembleRelease
```

호스트 앱 검증:

```bash
cd vibefactory
./gradlew testDebugUnitTest :app:compileDebugKotlin
```

실기기 설치 전에는 `apksigner verify --verbose --print-certs`와 `aapt dump badging`으로 signer, package, version, launcher를 확인한다.

## 운영 주의

- 새 Native 서비스는 기존 Flutter 서비스와 다른 배포 경로, DB, workspace, systemd unit, canary port를 사용한다.
- 기존 DB/workspace를 새 DB로 복사하거나 덮어쓰지 않는다.
- 공개 네트워크 배포 시 TLS, 인증, 다운로드 권한 검증이 필요하다.
- keystore를 잃으면 동일 package 앱을 업데이트할 수 없으므로 암호화 백업을 유지한다.
