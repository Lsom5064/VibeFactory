# Native Android APK Builder Server

Android 호스트 앱의 생성·수정 요청을 받아 Task별 workspace를 만들고, Codex CLI가 Kotlin과 Android Views/XML 소스를 구현한 뒤 서버가 Gradle lint, signed release APK 빌드, 식별자·서명 검증을 수행하는 FastAPI 서버다.

현재 구조와 작업 지침은 저장소 루트의 [AGENTS.md](../AGENTS.md)를 따른다.
Flutter에서 Native Android로의 전환 계획·복구 정보는
[과거 전환 기록](../docs/history/NATIVE_ANDROID_MIGRATION_PLAN.md)에 보존한다.
해당 문서의 과거 Goal을 현재 서비스에 다시 수행하지 않는다.

서버 소스의 현재 디렉터리는 저장소 루트의 `server/`다. 저장소 루트에서는
`server.server`로 import하고, `server/` 안에서는 `uvicorn server:app`으로 실행한다.
DB와 Task workspace의 경로도 `server/`를 기준으로 사용한다.

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
DB_PATH=server/native_tasks.db
APP_DATA_DB_PATH=server/native_app_data.db
WORKSPACES_ROOT=server/native_workspaces
BUILD_CACHE_ROOT=server/.native_tooling
```

기존 `tasks.db`, `app_data.db`, `workspaces/`는 `../flutter/runtime/`에 보존된
마이그레이션 이전 데이터이므로 새 서비스에서 쓰지 않으며 삭제하지 않는다.

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
CODEX_SANDBOX_MODE
CODEX_DANGEROUS_BYPASS
CODEX_TIMEOUT_SECONDS
LINT_RECOVERY_MAX_ATTEMPTS
LINT_RECOVERY_TIMEOUT_SECONDS
MAX_CONCURRENT_CODEX_RUNS

GENERATED_APP_KEYSTORE_PATH
GENERATED_APP_KEYSTORE_PASSWORD
GENERATED_APP_KEY_ALIAS
GENERATED_APP_KEY_PASSWORD
```

로컬 실행 스크립트는 기본적으로 다음 Git 외부 파일을 읽는다.

```text
~/.vibefactory/signing/generated-app-signing.env
~/.vibefactory/integrations.env
```

서명키와 비밀번호는 Git에 추가하지 않는다. 실제 빌드에서 서명 설정이 없거나 keystore 파일을 찾을 수 없으면 서버는 Task를 명확한 build 실패로 종료한다.
관리자 소유 외부 API 키와 OAuth 비밀 값은 Git, 채팅, Task DB에 넣지 않고 `integrations.env`에만 등록한다. 참가자 발급형으로 분류된 연구용 API 키는 채팅 원문과 Task 연동 설정에 기록하되 Agent·Codex 입력에서는 등록 완료 표기로 치환한다. 지원 제공자, 역할 구분과 발급 절차는 `EXTERNAL_INTEGRATIONS.md`를 따른다.

Codex는 기본적으로 `workspace-write` sandbox에서 Task workspace와 공유 Gradle
cache만 수정한다. 외부 격리 환경이 별도로 검증된 경우가 아니면
`CODEX_DANGEROUS_BYPASS=1`을 사용하지 않는다.

Codex의 기본 모델은 `gpt-5.6-sol`이다. 로컬 실행 시 모델 선택 화면이나
`CODEX_MODEL` 환경변수로 명시적으로 다른 모델을 선택할 수 있다.

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

최종 lint가 Kotlin/Java/XML/리소스 오류로 실패하면 같은 Task·리비전에서 Codex에
진단과 원래 요청을 전달해 소스를 수정하고 다시 검증한다. 기본 자동 수정은 최대 2회
(`LINT_RECOVERY_MAX_ATTEMPTS`, 0으로 비활성화, 상한 2)이며 최초 검증을 포함해
서버 lint는 최대 3회 실행한다. 복구와 재검증을 합친 추가 시간은 최대 600초
(`LINT_RECOVERY_TIMEOUT_SECONDS`, 상한 600)다. 최초 Codex 실행과 최초 lint의
시간 제한은 기존 `CODEX_TIMEOUT_SECONDS`를 따른다.

같은 오류가 반복되거나 복구 시간 초과, 취소, 복구 엔진 실패가 발생하면 중단한다.
SDK·디스크·메모리·네트워크·권한 문제와 분류할 수 없는 오류에는 소스 자동 수정을
시도하지 않는다. 수정 범위는 `app/src/`이며 빌드 설정·lint baseline 변경과 새
오류 무시 지시를 검사한다. 서버 공통 런타임·식별자·사용설명서 계약을 다시 확인한 뒤
lint와 release 빌드 및 APK 서명 검증을 모두 통과해야 성공한다.

진행 메시지는 `앱 검증 중 발견한 오류를 자동으로 수정하고 있어요. (1/2)`로 표시한다.
전체 복구 입력·출력은 `logs/lint_recovery/<실행 ID>/`와 `lint_repair_output` 이벤트에
보존하며, 시도 횟수·종료 이유·소요 시간은 결과의 `lint_recovery`와
`lint_recovery_finished` 이벤트에 기록한다. 복구 Codex 토큰도 해당 요청 사용량에 합산한다.

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

Codex와 사용량 조회 subprocess에는 keystore 비밀번호, 런타임 API 키, 외부 API 자격증명, 관리자 토큰을 전달하지 않는다. release Gradle subprocess에는 서명 환경변수와 해당 Task에서 확인된 Android 클라이언트 키만 제한적으로 전달한다.
Uvicorn access log는 endpoint와 상태 코드는 유지하되 전화번호·device ID가 들어 있는
query string을 출력하지 않는다.

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
server/.venv/bin/python -m unittest discover \
  -s server/tests -p 'test_*.py' -v

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
- 호스트와 생성 앱은 Android cloud backup 및 device transfer에서 로컬 파일, DB,
  SharedPreferences를 제외한다. 서버 DB와 workspace 백업은 별도 운영 절차로 관리한다.
- keystore를 잃으면 동일 package 앱을 업데이트할 수 없으므로 암호화 백업을 유지한다.
