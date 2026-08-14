# VibeFactory Native Android Migration Plan

- 작성일: 2026-08-14
- 문서 상태: 구현 진행 중
- 기준 저장소: `/Users/hai/Desktop/buildingAppswithCodex`
- 원본 기준: GitHub가 아니라 현재 로컬 작업 트리

## 1. Goal

현재 Flutter 기반 생성 앱 파이프라인을 Kotlin과 Android Views/XML 기반의 네이티브 Android 앱 생성 파이프라인으로 교체한다. 변경 전 현재 로컬 소스를 GitHub 복구 브랜치와 태그로 보존하고, 변경 후에도 FastAPI 서버와 Android 호스트 앱 사이의 API, APK 다운로드·설치, 리비전·분기, 런타임 LLM, 공유 데이터, 오류 보고 계약이 깨지지 않게 한다.

### Goals에 붙여넣을 문구

```text
작업을 시작하기 전에 저장소 루트의 NATIVE_ANDROID_MIGRATION_PLAN.md 전체를 읽고, 이 문서를 구현 순서·안전 규칙·검증 기준·롤백 절차의 기준 문서로 사용한다. 작업 중에는 각 Phase의 체크리스트와 작업 기록을 실제 진행 상태에 맞게 계속 갱신하며, 문서에 명시된 선행 단계와 검증을 건너뛰지 않는다. 현재 로컬 저장소를 최신 원본으로 취급하여 기존 서버·호스트 앱·생성 템플릿 소스를 GitHub 복구 브랜치와 태그로 먼저 보존한 뒤, Flutter 기반 앱 생성 파이프라인을 Kotlin + Android Views/XML 기반의 네이티브 Android 파이프라인으로 완전히 전환한다. BaseProject는 MainActivity.kt와 activity_main.xml을 포함하는 네이티브 Android 프로젝트로 교체하고, 서버의 템플릿 준비·식별자 적용·검증·빌드·APK 탐색·캐시 정리를 독립된 NativeAndroidProjectBuilder 계층으로 분리한다. 생성 앱의 런타임 LLM, 공유 데이터 API, 전체 로깅, 첨부 이미지, 오류 보고 기능을 Kotlin으로 이식한다. /generate, /status, /download, 취소, 리비전, 분기, 런타임 오류 API 및 task_id, package_name, APK, 타임라인 계약은 유지한다. 설치와 리비전 덮어쓰기를 위해 Git 밖의 고정 서명키로 release APK를 서명한다. 서버 테스트, Gradle 빌드, API 계약 테스트, ADB 실기기 테스트, APK 크기와 cold/warm 빌드 시간 측정을 모두 통과한 뒤에만 완료 처리한다. 기존 DB와 workspace는 삭제하지 않고 읽기 전용으로 보존하며 새 서비스는 별도 데이터 경로를 사용한다.
```

## 2. 확정된 결정

- 현재 로컬 파일이 가장 최신이며 작업의 유일한 소스 기준이다.
- 원격 브랜치 내용을 pull, merge, rebase하여 로컬을 덮어쓰지 않는다.
- 프레임워크 변경 전에 현재 로컬 소스를 GitHub 복구 브랜치와 태그로 올린다.
- 기존 Flutter `BaseProject`는 복구 커밋에서만 보존하고 현재 브랜치에서는 네이티브 Android 프로젝트로 교체한다.
- 새 생성 앱은 Android만 대상으로 한다.
- 생성 앱 구현 언어는 Kotlin으로 고정한다.
- UI는 Android Views와 XML을 사용하며 Jetpack Compose는 사용하지 않는다.
- `MainActivity.kt`와 `app/src/main/res/layout/activity_main.xml`은 항상 존재해야 한다.
- 여러 화면이 필요하면 Fragment, Activity 및 추가 XML layout을 사용할 수 있다.
- 기존 Flutter 프로젝트의 동시 빌드나 수정 호환 계층은 구현하지 않는다.
- 기존 FastAPI endpoint와 호스트 앱 응답 계약은 유지한다.
- 기존 Flutter 런타임 데이터는 삭제하지 않고 별도로 보존한다.
- 새 네이티브 서비스는 별도 DB와 workspace 경로를 사용한다.
- 일반 Android 기기 설치를 위해 APK는 고정된 전용 키로 서명한다.
- 초기 release 빌드는 R8와 리소스 축소를 끄고 안정성과 빌드 속도를 우선한다.
- R8는 실제 APK 크기와 빌드 시간 측정 후 별도 판단한다.

## 3. 안전 규칙

- 사용자 승인 전 프레임워크 코드를 변경하지 않는다.
- 기존 `tasks.db`, `app_data.db`, `workspaces/`, `profiles/`를 삭제하거나 이동하지 않는다.
- `git reset --hard`, `git checkout --`, 강제 push, history rewrite를 사용하지 않는다.
- 현재 로컬의 수정·삭제 상태를 원격 상태로 복원하지 않는다.
- `exports/`, `debug_workspaces/`, APK, DB, 빌드 캐시를 Git에 추가하지 않는다.
- 비밀키, keystore, 서명 비밀번호, API 키를 Git에 추가하지 않는다.
- 기존 서버를 중지하거나 배포 대상을 바꾸기 전에 별도로 사용자에게 알린다.
- 각 단계는 테스트가 통과한 뒤 다음 단계로 넘어간다.
- 실패한 단계의 체크박스를 완료 처리하지 않는다.

## 4. 현재 작업 트리 주의사항

문서 작성 시점에 현재 브랜치에는 커밋되지 않은 서버·테스트 변경, Gradle Wrapper 파일 삭제, 미추적 스크립트와 산출물 디렉터리가 존재한다.

백업 시 다음 원칙을 따른다.

- 추적 중인 로컬 수정과 삭제는 현재 로컬 상태의 일부로 취급한다.
- 미추적 파일은 소스, 테스트, 운영 스크립트인지 개별 확인한 뒤 필요한 것만 추가한다.
- `debug_workspaces/`와 `exports/`는 백업 커밋에서 제외한다.
- `git add -A`를 무조건 사용하지 않고 추가할 경로를 명시적으로 검토한다.
- 커밋 직전에 `git diff --cached --stat`와 `git diff --cached`를 검토한다.

## 5. 목표 구조

### 5.1 생성 앱 템플릿

```text
BaseProject/
├── settings.gradle.kts
├── build.gradle.kts
├── gradle.properties
├── gradlew
├── gradlew.bat
├── gradle/wrapper/
└── app/
    ├── build.gradle.kts
    ├── proguard-rules.pro
    └── src/main/
        ├── AndroidManifest.xml
        ├── kotlin/kr/ac/kangwon/hai/generated/
        │   ├── MainActivity.kt
        │   ├── GeneratedApplication.kt
        │   ├── VibeCrashReporter.kt
        │   ├── VibeDataClient.kt
        │   ├── VibeHttpClient.kt
        │   └── VibeLlmClient.kt
        └── res/
            ├── layout/activity_main.xml
            ├── values/strings.xml
            ├── values/colors.xml
            ├── values/themes.xml
            └── xml/network_security_config.xml
```

### 5.2 서버 빌드 계층

서버 route와 DB orchestration에서 네이티브 프로젝트 파일 조작을 분리한다. 과도한 프레임워크를 도입하지 않고 작은 인터페이스와 단일 구현체를 사용한다.

예상 책임은 다음과 같다.

```text
ProjectBuilder
├── prepare_project(...)
├── apply_identity(...)
├── apply_revision_version(...)
├── validate_identity(...)
├── validate_template_changed(...)
├── build_apk(...)
├── resolve_apk(...)
├── validate_apk(...)
└── clear_build_artifacts(...)

NativeAndroidProjectBuilder(ProjectBuilder)
```

라우트는 HTTP 요청·응답, 접근 제어 및 작업 큐만 담당하고, worker는 `ProjectBuilder`를 호출한다.

## 6. Android 프로젝트 정책

### 6.1 고정 도구 버전

- JDK는 서버와 로컬에서 같은 major version을 사용한다.
- Android Gradle Plugin, Kotlin, Gradle Wrapper 버전을 템플릿에 고정한다.
- `compileSdk`, `targetSdk`, `minSdk`를 명시한다.
- 로컬 `local.properties`는 Git에 올리지 않는다.
- Gradle dependency repository는 `google()`과 `mavenCentral()`로 제한한다.

정확한 버전은 구현 시작 시 현재 설치된 Android SDK와 호스트 앱 구성을 확인한 뒤 문서에 기록한다.

구현 고정값:

```text
JDK: Temurin 17.0.16 (AWS는 OpenJDK 17)
Gradle Wrapper: 8.13
Android Gradle Plugin: 8.13.2
Kotlin Android Plugin: 2.0.21
compileSdk: 36
targetSdk: 35
minSdk: 26
JVM target: 17
```

### 6.2 앱 식별자

- `applicationId`가 설치 앱의 실제 package identity다.
- `task_id`, `applicationId`, 앱 이름, version code는 서버만 변경한다.
- Codex가 이 값을 임의로 변경하지 못하게 workspace 지침에 명시한다.
- 분기 Task는 원본 Task의 `applicationId`와 서명키를 유지한다.
- 앱 이름 변경은 label만 바꾸고 `applicationId`는 바꾸지 않는다.
- 런타임 요청의 package name은 하드코딩 대신 `applicationContext.packageName`에서 얻는다.
- Task ID는 서버가 생성한 BuildConfig 또는 resource 값에서 읽는다.
- 서버 base URL은 빌드 환경의 `SERVER_BASE_URL`을 `BuildConfig.VIBE_SERVER_BASE_URL`로 주입해 사용한다.

### 6.3 버전 코드

- 리비전과 과거 버전 설치 정책을 호스트 앱의 현재 설치 정책과 맞춘다.
- 동일 package를 덮어쓸 수 있는지 각 리비전에서 검증한다.
- 과거 버전 설치가 필요한 경우 downgrade 오류가 발생하지 않는 기존 sideload 정책을 유지하거나 명시적인 제거·재설치 흐름을 제공한다.
- 선택한 정책과 실제 version code는 이 문서의 검증 기록에 남긴다.

### 6.4 레이아웃

- 최초 화면은 `activity_main.xml`에서 시작한다.
- 고정 높이와 고정 폭을 남용하지 않는다.
- 긴 화면은 `NestedScrollView`, `RecyclerView` 등 적합한 스크롤 컨테이너를 사용한다.
- 긴 텍스트, 작은 화면, 화면 회전, 키보드, system inset을 검증한다.
- `ConstraintLayout`의 양쪽 constraint와 `0dp` match constraint를 적절히 사용한다.
- `ScrollView` 안에 큰 목록을 중첩하지 않는다.
- top, bottom, left, right overflow가 발생하지 않게 Codex 지침과 검증 항목에 포함한다.

## 7. APK 서명 정책

Android는 설치 가능한 APK에 서명을 요구한다. 서명하지 않은 APK를 일반 기기나 정상적인 `adb install` 흐름으로 배포하지 않는다.

### 7.1 선택 정책

- 전용 장기 서명키 하나를 생성 앱에 공통 사용한다.
- release APK를 Gradle signing config로 서명한다.
- 키는 저장소 밖에 둔다.
- 로컬과 AWS는 동일한 키를 사용한다.
- 비밀번호는 환경변수 또는 권한이 제한된 secret 파일에서 읽는다.
- keystore와 복구 정보는 암호화하여 별도 백업한다.

권장 위치 예시는 다음과 같다.

```text
로컬: ~/.vibefactory/signing/generated-app.jks
AWS:   /etc/vibefactory/secrets/generated-app.jks
```

환경변수 이름은 다음처럼 통일한다.

```text
GENERATED_APP_KEYSTORE_PATH
GENERATED_APP_KEYSTORE_PASSWORD
GENERATED_APP_KEY_ALIAS
GENERATED_APP_KEY_PASSWORD
```

### 7.2 성능·크기 정책

- APK 서명 자체의 크기와 빌드 시간 영향은 미미하다.
- 초기에는 `minifyEnabled=false`, `shrinkResources=false`로 둔다.
- native release APK의 크기와 cold/warm build 시간을 측정한다.
- R8 적용 전후를 같은 앱으로 비교하고 기능 회귀가 없을 때만 활성화한다.

## 8. 고정해야 할 서버-호스트 계약

아래 endpoint의 URL, HTTP method, 핵심 요청 필드와 핵심 응답 필드를 유지한다.

| 기능 | 계약 |
|---|---|
| Task 목록 | `GET /tasks` |
| 생성·수정 요청 | `POST /generate` |
| 상태·타임라인 | `GET /status/{task_id}` |
| 중단 | `POST /tasks/{task_id}/cancel` |
| 사용량 | `GET /tasks/{task_id}/usage` |
| 리비전 목록 | `GET /tasks/{task_id}/revisions` |
| 리비전 분기 | `POST /tasks/{task_id}/revisions/{revision_label}/branch` |
| 이름 변경 | `PATCH /tasks/{task_id}` |
| 런타임 오류 | `POST /tasks/{task_id}/runtime-error` |
| APK 다운로드 | `GET /download/{task_id}` |
| 생성 앱 LLM | `POST /apps/{task_id}/llm/respond` |
| 생성 앱 데이터 | `/apps/{task_id}/data/{collection}` 계열 |

다음 필드는 의미와 타입을 유지한다.

- `task_id`
- `status`
- `status_display_text`
- `app_name`
- `package_name`
- `apk_url`
- `apk_path`
- `apk_size_bytes`
- `build_success`
- `conversation_state`
- `timeline_events`
- `timeline_cursor`
- `progress_mode`
- `cancel_allowed`
- 리비전의 `revision_label`, `request_summary`, `has_apk`, `can_branch`, `is_current`

APK 다운로드의 `Content-Length`, Range 요청 및 재시도 동작도 유지한다.

## 9. 생성 앱 런타임 이식

### 9.1 오류 보고

- Kotlin `Thread.UncaughtExceptionHandler`를 사용해 처리되지 않은 오류를 기록한다.
- Activity 시작 실패와 첫 화면 이전 오류도 가능한 범위에서 전달한다.
- 호스트 앱으로 보내는 explicit broadcast package를 유지한다.
- action은 `kr.ac.kangwon.hai.action.CRASH_REPORT`를 유지한다.
- `task_id`, `package_name`, `error_message`, `stack_trace`, `report_kind` extra를 유지한다.
- 동일 오류 중복 전송 방지 정책을 유지한다.
- ANR, 강제 종료, native process crash는 uncaught exception handler만으로 모두 포착할 수 없다는 제한을 기록한다.

### 9.2 런타임 LLM

- 서버가 제공한 endpoint와 Task ID를 사용한다.
- package name은 `applicationContext.packageName`을 사용한다.
- 시스템 프롬프트, 사용자 입력, 컨텍스트, 첨부 이미지 메타데이터, 원시 응답, 파싱 응답, 오류 응답을 전문 기록하는 서버 계약을 유지한다.
- 이미지가 있으면 실제 이미지 payload가 서버에 전달되는지 검증한다.
- 응답 JSON이 잘리지 않도록 서버 출력 제한 정책을 유지한다.
- timeout, network error, usage limit 오류를 사용자 친화적인 문구로 표시한다.

### 9.3 공유 데이터 API

- 기존 `app_data.db` endpoint 형식을 그대로 사용한다.
- list, create, get, patch, delete를 Kotlin client에서 지원한다.
- `task_id`, `package_name`, `collection`, `owner_id`를 유지한다.
- 네트워크 작업은 main thread에서 실행하지 않는다.
- lifecycle 종료 시 불필요한 요청을 취소한다.
- 로컬 저장과 서버 공유 저장의 사용 조건을 Codex 지침에 명확히 구분한다.

## 10. Codex workspace 지침 변경

새 workspace의 `AGENTS.md`와 `prompt.md`에서 다음을 강제한다.

- Flutter, Dart, `pubspec.yaml`, Compose를 사용하지 않는다.
- Kotlin과 Android Views/XML을 사용한다.
- `MainActivity.kt`와 `activity_main.xml`을 유지한다.
- 서버가 관리하는 Task ID, application ID, 앱 이름, 서명 설정을 바꾸지 않는다.
- Codex는 최종 APK를 직접 빌드하지 않고 정적 검증까지만 수행한다.
- 서버가 최종 Gradle build와 APK 검증을 수행한다.
- 사용자의 핵심 기능을 더미 데이터나 단순 대체 기능으로 완료 처리하지 않는다.
- 네트워크, 카메라, 알림, 파일, OCR 등 필요한 Android permission과 runtime permission을 함께 구현한다.
- 긴 화면, 회전, 작은 화면, 키보드 상태에서 레이아웃을 검증한다.
- `.codex_result/task_result.json` 계약을 유지한다.
- 성공 APK 경로는 native Android artifact 경로로 기록한다.

예상 APK 경로:

```text
project/app/build/outputs/apk/release/app-release.apk
```

## 11. 데이터 전환 정책

- 기존 `flutter_apk_server/tasks.db`, `app_data.db`, `workspaces/`를 삭제하지 않는다.
- 기존 데이터는 migration 시작 시점에 NAS 또는 별도 디렉터리로 백업한다.
- 새 서비스는 새로운 `DB_PATH`, `APP_DATA_DB_PATH`, `WORKSPACES_ROOT`를 사용한다.
- 예시 이름은 `native_tasks.db`, `native_app_data.db`, `native_workspaces/`다.
- 기존 Flutter Task의 로그와 APK는 보존하지만 새 서비스에서 수정·분기·재빌드하지 않는다.
- 기존 데이터를 새 호스트 앱에서 계속 보여줄 필요가 생기면 읽기 전용 history API를 별도 계획한다.
- 어떤 경우에도 프레임워크 전환을 이유로 기존 DB나 workspace를 삭제하지 않는다.

## 12. 단계별 실행 체크리스트

### Phase 0. 현재 로컬 소스 복구 지점 생성

- [x] `git status`, 현재 브랜치, remote, HEAD SHA를 기록한다.
- [x] 추적된 수정·삭제 파일을 검토한다.
- [x] 미추적 파일을 소스와 런타임 산출물로 분류한다.
- [x] 서버 테스트를 실행한다.
- [x] 호스트 앱 unit test와 compile을 실행한다.
- [x] 현재 로컬 소스만 포함한 복구 커밋을 만든다.
- [x] `backup/pre-native-android-20260814` 브랜치를 GitHub에 push한다.
- [x] `pre-native-android-20260814` annotated tag를 push한다.
- [x] 복구 커밋 SHA를 이 문서에 기록한다.
- [x] 기존 DB와 workspace 백업 위치를 기록한다.

기록:

```text
복구 브랜치: backup/pre-native-android-20260814
복구 태그: pre-native-android-20260814
복구 커밋 SHA: 8ab2bbdd8c8735d384584a1dc5ea6f1cce399a85
DB 백업 위치: /volume1/vibefactory-archive/pre-native-android-20260814/local/databases
workspace 백업 위치: /volume1/vibefactory-archive/pre-native-android-20260814/local/workspaces
검증 일시: 2026-08-14 14:42:52 KST
```

검증 결과:

- 서버 `unittest` 37개 통과
- 호스트 앱 `testDebugUnitTest`, `compileDebugKotlin` 통과 (`BUILD SUCCESSFUL`, 21초)
- NAS DB SHA-256이 로컬 SQLite online backup과 일치
- NAS에 실제 Task workspace 디렉터리 10개 확인
- 캐시 및 독립 ZIP 아카이브를 제외한 workspace `rsync --dry-run` 파일 차이 없음
- 기존 24.6GB 독립 ZIP 아카이브 4개는 workspace 디렉터리가 아니므로 NAS 동기화 대상에서 제외하고 로컬 원본을 그대로 보존

### Phase 1. 마이그레이션 브랜치와 계약 테스트

- [x] 복구 커밋에서 `feature/native-android-generation` 브랜치를 만든다.
- [x] 서버 API 응답 fixture 또는 contract test를 작성한다.
- [x] 호스트 앱 DTO와 서버 응답 필드를 대조한다.
- [x] 생성, 상태, 취소, 리비전, 분기, 다운로드 계약 테스트를 만든다.
- [x] 기존 테스트가 모두 통과하는 기준점을 기록한다.

### Phase 2. Native Android BaseProject

- [x] 기존 Flutter `BaseProject` 내용을 제거한다.
- [x] Gradle Wrapper를 정상 생성한다.
- [x] Kotlin Android application module을 구성한다.
- [x] `MainActivity.kt`를 추가한다.
- [x] `activity_main.xml`을 추가한다.
- [x] ViewBinding을 활성화한다.
- [x] INTERNET과 필요한 기본 manifest 설정을 추가한다.
- [x] 앱 label, application ID, Task ID, version code를 서버가 주입할 구조를 만든다.
- [x] 빈 템플릿을 signed release APK로 빌드한다.
- [x] APK를 ADB로 설치하고 실행한다.

### Phase 3. 서버 빌드 계층 분리

- [x] 프로젝트 준비·식별자·빌드·산출물 로직의 호출 지점을 목록화한다.
- [x] 작은 `ProjectBuilder` 계약을 만든다.
- [x] `NativeAndroidProjectBuilder`를 구현한다.
- [x] route와 worker에서 파일 조작을 builder 호출로 교체한다.
- [x] Flutter command, PUB_CACHE, Dart identity, pubspec version 처리를 제거한다.
- [x] native Gradle cache와 temporary directory 정책을 적용한다.
- [x] build, `.gradle`, `.tooling` 캐시 정리 정책을 유지한다.
- [x] revision과 branch가 native 프로젝트를 복사하도록 변경한다.
- [x] native APK 산출물 탐색과 검증을 구현한다.
- [x] 기본 템플릿 미변경 감지를 Kotlin/XML 기준으로 바꾼다.

### Phase 4. 런타임 기능 이식

- [x] `VibeCrashReporter.kt`를 구현한다.
- [x] 호스트 앱 broadcast 계약을 검증한다.
- [x] `VibeLlmClient.kt`를 구현한다.
- [x] 텍스트·이미지 LLM 요청을 검증한다.
- [x] 전체 prompt, context, response, raw response, error logging을 검증한다.
- [x] `VibeDataClient.kt`를 구현한다.
- [x] app data CRUD를 실기기에서 검증한다.
- [x] cleartext HTTP와 HTTPS 환경을 모두 점검한다.
- [x] timeout과 cancellation 처리를 검증한다.

### Phase 5. Codex 지침과 결과 계약 전환

- [x] workspace `AGENTS.md`를 Native Android 기준으로 변경한다.
- [x] `prompt.md`의 Flutter 설명과 경로를 제거한다.
- [x] task result의 예상 APK 경로를 변경한다.
- [x] Codex가 application ID와 Task ID를 바꾸지 못하게 검증한다.
- [x] Codex가 Compose 또는 Flutter를 생성하면 실패 처리한다.
- [x] XML layout과 Kotlin source의 기본 품질 검증을 추가한다.
- [x] Gradle lint·compile·assemble 실패가 사용자 버블로 전달되는지 검증한다.

### Phase 6. 서명과 설치 정책

- [x] 전용 keystore를 생성한다.
- [x] keystore를 Git 외부에 저장한다.
- [x] 암호화된 별도 백업을 만든다.
- [x] Gradle signing config를 환경변수 기반으로 연결한다.
- [x] 서명 설정이 없으면 명확한 서버 오류를 반환한다.
- [x] 동일 Task 리비전 APK가 기존 앱을 덮어쓰는지 확인한다.
- [x] 분기 Task APK가 의도한 package를 유지하는지 확인한다.
- [x] 설치 후 호스트 앱이 생성 앱을 자동 실행하는지 확인한다.

### Phase 7. 자동 검증

- [x] Python unit test 전체 통과
- [x] DB integrity 및 foreign key test 통과
- [x] 서버 API contract test 통과
- [x] 호스트 앱 unit test 전체 통과
- [x] 호스트 앱 Kotlin compile 통과
- [x] BaseProject Gradle lint 통과
- [x] BaseProject release assemble 통과
- [x] APK application ID 검증 통과
- [x] APK version code 검증 통과
- [x] APK signature 검증 통과
- [x] APK launcher Activity 검증 통과

### Phase 8. ADB 실기기 검증

- [x] 새 채팅방 생성
- [x] 최초 생성 요청 및 prompt 확인·전송
- [x] 텍스트만 사용한 앱 생성
- [x] 이미지 여러 장을 첨부한 앱 생성
- [x] PDF 또는 일반 파일 첨부 생성
- [x] 생성 중 백그라운드 전환과 복귀
- [x] 생성 중 중단 및 중단 버블 확인
- [x] APK 다운로드 퍼센트 확인
- [x] APK 설치와 자동 실행 확인
- [x] 수정 요청 5회 이상 수행
- [x] 모든 수정 APK가 같은 앱을 덮어쓰는지 확인
- [x] 리비전 목록과 과거 APK 설치 확인
- [x] 특정 리비전에서 새 Task 분기
- [x] 분기 후 package와 서명 동작 확인
- [ ] 생성 앱에서 런타임 LLM 텍스트 요청
- [ ] 생성 앱에서 런타임 LLM 이미지 요청
- [x] 생성 앱 데이터 생성·조회·수정·삭제
- [x] 생성 앱 오류를 발생시켜 호스트 앱 보고 확인
- [x] 자동 복구 요청과 후속 APK 설치 확인
- [x] 화면 회전, 작은 화면, 긴 텍스트, 키보드 상태 확인
- [x] 호스트 앱 채팅 스크롤과 첨부 초안 유지 확인

### Phase 9. 성능 측정

각 측정은 같은 기기와 같은 네트워크에서 최소 3회 수행하고 median을 기록한다.

- [x] BaseProject cold Gradle build 시간
- [x] BaseProject warm Gradle build 시간
- [x] 실제 생성 앱 cold build 시간
- [x] 실제 수정 앱 warm build 시간
- [x] Codex 수행 시간과 Gradle build 시간을 분리 기록
- [x] signed release APK 크기
- [x] 서버에서 호스트까지 다운로드 시간
- [x] 설치 UI 진입 시간
- [ ] 설치 후 자동 실행 시간
- [x] 호스트 앱 Task 목록과 로그 화면 전환 지연
- [x] 생성 중 상태 polling이 UI 입력을 방해하지 않는지 확인

측정 기록:

| 항목 | 1회 | 2회 | 3회 | Median | 비고 |
|---|---:|---:|---:|---:|---|
| BaseProject cold build | 23.38초 | 22.86초 | 23.16초 | 23.16초 | no-daemon, clean release 기준 |
| BaseProject warm build | 1.85초 | 2.29초 | 1.83초 | 1.85초 | Gradle daemon, up-to-date release 기준 |
| 실제 생성 앱 clean-output build | 5.43초 | 4.83초 | 4.43초 | 4.83초 | 실제 Codex 생성 앱 소스, 새 project copy, 공유 dependency/build cache 및 daemon, lint + signed release |
| 실제 수정 앱 incremental build | 20.64초 | 11.94초 | 11.85초 | 11.94초 | 실제 rev_0002 소스의 string resource를 매회 변경, lint + signed release |
| BaseProject APK 크기 | 5,094,598B | 5,094,598B | 5,094,598B | 5,094,598B | 최종 BuildConfig 계약 반영본, R8/resource shrink 비활성화 |
| 실제 생성 앱 APK 크기 | 5,135,235B | 5,138,951B | 5,138,955B | 5,138,951B | V1, V2, 성능 측정용 V2 변경본 |
| APK 다운로드 | 714ms | 669ms | 663ms | 669ms | ADB reverse 로컬 서버, 5,115,158B v4, 매회 전체 재다운로드 후 설치 UI 진입 |
| 설치 UI 진입 | 714ms | 669ms | 663ms | 669ms | 다운로드 탭부터 PackageInstaller Activity 표시까지, 로컬 ADB reverse 기준 |
| APK 다운로드 진행률 | 15% | 48% | 84% | - | 임시 저속 프록시에서 2/5/8초 캡처, 같은 버블 위치 유지 후 설치 UI 진입 |
| 호스트 Task 목록 전환 | <=100ms | <=100ms | <=100ms | <=100ms | 세 차례 모두 탭 100ms 후 Task 목록과 상태 카드가 완전히 표시됨 |
| 호스트 로그 화면 전환 | 86ms | 65ms | 73ms | 73ms | 로그 보기 왼쪽·가운데·오른쪽 터치, ActivityTaskManager Displayed 기준 |
| 호스트 서버 API | Task 목록 2ms | 상태 5ms | 리비전 4ms | - | 각 API 3회 median 반올림. Codex usage 조회는 약 0.80초 |
| Codex 비수정 질문 응답 | 33초 | 34초 | 25초 | 33초 | 같은 Native Task에서 answer-only 요청, 사용자·답변 버블 각각 1건 |
| 설치 후 실행 | | | | | |

실제 서버 단계 분리 기록:

- 최초 생성: Codex 코드 생성 907초, 서버 lint 2초, 서버 signed release 66초. 최초 격리 cache에서 Codex가 lint 오류 2건을 수정하며 재실행한 시간 포함.
- 최초 수정: Codex follow-up 판단 35초, Codex 코드 수정 130초, 서버 lint 2초, 서버 signed release 27초.
- V1 분기 재빌드: workspace 생성부터 signed release 성공까지 56초. package name, versionCode, signer 유지 확인.

### Phase 10. 새 서비스 배포

- [ ] 기존 서비스와 다른 배포 경로를 사용한다.
- [ ] 새 DB, app data DB, workspace 경로를 설정한다.
- [ ] JDK와 Android SDK 버전을 확인한다.
- [ ] 동일 signing keystore를 안전하게 배치한다.
- [ ] Gradle shared cache 권한을 확인한다.
- [ ] health endpoint를 확인한다.
- [ ] canary 포트 또는 내부 사용자로 먼저 검증한다.
- [ ] 호스트 앱 base URL 전환 전에 API contract test를 실행한다.
- [ ] 전환 후 생성·수정·다운로드·설치 smoke test를 실행한다.
- [ ] 기존 Flutter 데이터가 삭제되지 않았는지 확인한다.

## 13. 상세 검증 시나리오

### 시나리오 A. 최초 생성

1. 새 Task를 만든다.
2. 사용자가 작성한 요청으로 생성 prompt를 준비한다.
3. 확인 후 전송한다.
4. native workspace에 `MainActivity.kt`와 `activity_main.xml`이 존재하는지 확인한다.
5. 서버 상태가 Queued, Running, Success 순서로 일관되게 변하는지 확인한다.
6. APK 다운로드 버블이 하나만 생성되는지 확인한다.
7. APK를 설치하고 자동 실행한다.

### 시나리오 B. 리비전

1. 텍스트 수정 요청을 3회 수행한다.
2. 이미지 첨부 수정 요청을 2회 수행한다.
3. 모든 revision의 application ID와 signer가 같은지 확인한다.
4. 설치 시 별도 앱이 생기지 않고 기존 앱이 갱신되는지 확인한다.
5. 각 수정 요청, 첨부 파일 및 결과가 DB timeline에 한 번씩만 기록되는지 확인한다.

### 시나리오 C. 분기

1. 특정 revision에서 새 Task로 분기한다.
2. 분기 채팅방이 즉시 생성되는지 확인한다.
3. 원본 revision의 파일이 새 workspace에 정확히 복사되는지 확인한다.
4. Task ID는 새 값으로 바뀌고 application ID 정책은 의도대로 유지되는지 확인한다.
5. 원본과 분기 앱의 설치·덮어쓰기 동작이 제품 정책과 일치하는지 확인한다.

### 시나리오 D. 런타임 기능

1. 생성 앱에서 LLM 텍스트 요청을 보낸다.
2. 생성 앱에서 이미지 포함 LLM 요청을 보낸다.
3. DB에 입력, 시스템 prompt, context, 원시 응답, 파싱 응답이 잘리지 않고 기록되는지 확인한다.
4. app data를 저장하고 다른 화면 또는 기기에서 조회한다.
5. 의도적인 예외를 발생시켜 호스트 앱 오류 보고와 자동 복구 흐름을 확인한다.

## 14. 완료 조건

다음을 모두 만족해야 migration을 완료로 본다.

- [x] 현재 로컬 원본이 GitHub 복구 브랜치와 태그에 존재한다.
- [x] Flutter와 Dart 빌드 의존성이 새 서비스 코드에서 제거됐다.
- [x] BaseProject가 정상적인 Native Android 프로젝트다.
- [x] 생성 앱에 `MainActivity.kt`와 `activity_main.xml`이 존재한다.
- [x] 서버 빌드 로직이 route에서 분리됐다.
- [x] 기존 호스트 앱 API DTO를 깨는 변경이 없다.
- [x] APK가 고정 키로 정상 서명된다.
- [x] 최초 생성, 수정, revision, branch, cancel이 동작한다.
- [x] 다운로드, 설치, 자동 실행이 동작한다.
- [ ] 런타임 LLM, 첨부 이미지, 전체 로깅이 동작한다.
- [x] app data CRUD가 동작한다.
- [x] 런타임 오류 보고가 동작한다.
- [ ] 자동 테스트와 ADB 실기기 테스트가 통과한다.
- [x] APK 크기와 build delay 측정 결과가 기록됐다.
- [x] 기존 DB와 workspace가 삭제되지 않았다.
- [ ] 새 서비스 배포와 smoke test가 통과했다.

## 15. 롤백

### 코드 롤백

- pre-migration 태그를 새 디렉터리에 clone하여 복구한다.
- 현재 작업 디렉터리에 destructive reset을 수행하지 않는다.
- 복구 커밋 SHA와 배포 환경변수를 확인한 뒤 별도 서비스로 실행한다.

### 데이터 롤백

- 새 native DB와 기존 Flutter DB를 섞지 않는다.
- 기존 DB와 workspace 백업을 원래 위치에 덮어쓰기 전에 서비스를 중지하고 추가 백업을 만든다.
- DB integrity check와 foreign key check를 통과한 뒤 서비스를 연다.

### 서명키 사고

- signing key를 잃으면 기존 설치 앱에 새 APK를 덮어쓸 수 없다.
- 암호화된 keystore 백업과 alias·비밀번호 복구 절차를 별도로 검증한다.
- 키가 손상된 경우 package name을 바꾸거나 기존 앱을 제거해야 하므로 정상 migration으로 보지 않는다.

## 16. 작업 기록

```text
일시: 2026-08-14 KST
Phase: 1-7 자동 구현·검증
변경 파일: BaseProject 전체, flutter_apk_server/server.py, project_builder.py, tests, run-local-server.sh, aws/native/*
실행 명령: Python unittest, py_compile, Gradle lintDebug/compileDebugKotlin/assembleRelease, apksigner verify, aapt dump badging
테스트 결과: 서버 44개 통과, 호스트 unit/Kotlin compile 통과, Native lint/compile/signed release 통과
ADB 기기: adb devices 결과 연결 기기 없음. 실기기 항목은 미완료로 유지
빌드 시간: Native lint+compile+release 통합 warm build 1분 21초
APK 크기: 5,094,722 bytes
발견된 문제: Codex 성공 JSON 존재 시 서버 final build를 건너뛸 수 있었고, OkHttp coroutine 취소가 실제 Call을 취소하지 않았음. --no-daemon 사용 시 up-to-date build도 median 18.42초 소요
해결 내용: 성공 JSON이면 서버가 항상 lint+signed release를 수행하고 기존 결과 필드를 보존. suspendCancellableCoroutine으로 OkHttp Call 취소 연결. 공유 Gradle daemon을 재사용해 warm release median 1.85초, clean lint+release fixture median 6.69초로 단축
서명 fingerprint: SHA-256 6661f6b196932fbeabf06e955bc8820fe2ca438dcc8f9c270e9d32528560f277
커밋 SHA: f76cfda55
다음 단계: ADB 실기기, 실제 Codex 생성·수정·리비전·분기, 성능 3회 측정, canary 배포
```

```text
일시: 2026-08-14 KST
Phase: 6-9 실제 Native 생성·수정·분기 및 성능 검증
변경 파일: flutter_apk_server/server.py, project_builder.py, tests/test_host_api_contract.py, tests/test_project_identity.py, NATIVE_ANDROID_MIGRATION_PLAN.md
실행 명령: 실제 gpt-5.4 /generate, follow-up /generate, revision branch, /status, /download Range, app data CRUD, unittest, Gradle lintDebug/assembleRelease, apksigner, aapt
테스트 결과: 실제 V1·V2 생성 성공, 두 APK 모두 보존, package/versionCode/signer 동일, V2 기능 코드 반영, 분기 Task 즉시 생성 및 signed APK 성공, 서버 49개 테스트 통과
ADB 기기: adb devices 결과 연결 기기 없음. 설치·덮어쓰기·자동 실행 항목은 미완료로 유지
빌드 시간: 실제 생성 앱 clean-output median 4.83초, 실제 수정 앱 incremental median 11.94초. 최초 Codex 907초, 첫 수정 Codex 130초
APK 크기: V1 5,135,235 bytes, V2 5,138,951 bytes
발견된 문제: 분기 worker에서 정의되지 않은 source_root를 참조해 Error 발생. 분기 시 이전 project 내부 logs/.codex_result가 함께 복사됨. Codex subprocess에 signing/runtime/admin 비밀 환경변수가 전달될 수 있었고 AWS secrets 디렉터리는 서비스 사용자가 keystore를 읽을 수 없는 권한이었음. 최종 prompt의 명시적 앱 이름보다 최초 임시 이름이 우선됨
해결 내용: source_project_path 검증으로 수정하고 worker 회귀 테스트 추가. project root runner 산출물은 revision/branch 복사 및 성공 후 cache prune에서 제외·제거. Codex 환경에서 비밀값을 제거하고 signing 값은 Gradle release 단계에만 주입. AWS secrets를 root:ubuntu 750, keystore root:ubuntu 640 정책으로 수정. 최종 prompt의 명시적 앱 이름을 최초 빌드 Task label에 반영하고 package는 유지
서명 fingerprint: SHA-256 6661f6b196932fbeabf06e955bc8820fe2ca438dcc8f9c270e9d32528560f277
커밋 SHA: f76cfda55
다음 단계: ADB 실기기 검증, 별도 Native AWS canary 배포, 최종 감사와 feature branch push
```

```text
일시: 2026-08-14 KST
Phase: 7 최종 자동 회귀 검증
변경 파일: flutter_apk_server/tests/test_native_build_pipeline.py, NATIVE_ANDROID_MIGRATION_PLAN.md
실행 명령: Python unittest, py_compile, bash -n, host Gradle unit/compile, Native Gradle lintDebug/assembleRelease, apksigner, aapt, git diff --check
테스트 결과: 서버 50개 통과, 호스트 unit/Kotlin compile 통과, Native lint/signed release 통과, shell/Python 정적 검증 통과
ADB 기기: 연결 기기 없음. 설치·덮어쓰기·자동 실행과 실기기 runtime 검증은 미완료
빌드 시간: 최종 Native lint+signed release 27초
APK 크기: 5,094,598 bytes
발견된 문제: 기본 데이터 경로 분리를 직접 고정하는 회귀 테스트가 없었음
해결 내용: 환경변수 미설정 시 native_tasks.db, native_app_data.db, native_workspaces, .native_tooling만 선택하는 테스트를 추가
커밋 SHA: f76cfda55
다음 단계: 소스 감사와 feature branch push 후 ADB 실기기 및 별도 Native AWS canary 검증
```

```text
일시: 2026-08-14 KST
Phase: 완료 조건 증거 감사
변경 파일: NATIVE_ANDROID_MIGRATION_PLAN.md
실행 명령: git ls-remote/show-ref/rev-parse, SQLite PRAGMA quick_check, 소스 의존성 검색, ADB devices
테스트 결과: GitHub 복구 branch/tag와 Native feature branch 확인, 기존 tasks.db quick_check=ok, 기존 workspace 보존 확인, Native APK 설치 경로가 artifact URL과 APK package identity를 사용함을 확인
ADB 기기: 연결 기기 없음. 설치·자동 실행·runtime 기능 관련 완료 조건은 체크하지 않음
빌드 시간: 기존 Phase 9 측정값 유지
APK 크기: 5,094,598 bytes
발견된 문제: 없음. 호스트 앱 설치 흐름은 Native/Flutter 파일 구조를 직접 참조하지 않지만 실제 installer 결과와 자동 실행은 정적 검사만으로 증명할 수 없음
해결 내용: 직접 증명된 완료 조건만 체크하고 실기기·배포 조건은 미완료로 유지
커밋 SHA: 92997e417 상태 기준
다음 단계: ADB 기기 연결 후 Phase 8 전체 시나리오, 이후 별도 Native AWS canary 배포
```

```text
일시: 2026-08-14 17:20-18:40 KST
Phase: 2, 4, 6, 8 ADB 실기기 검증 및 첨부 회전 복원 보강
변경 파일: flutter_apk_server/server.py, tests/test_native_build_pipeline.py, tests/test_full_logging_and_image_storage.py, vibefactory/app/build.gradle.kts, HostAppConfig.kt, MainActivity.kt, MainUiAdapters.kt, HostUiModels.kt, ComposerDraftAttachmentStore.kt, PromptReviewMessagePolicy.kt 및 관련 테스트, NATIVE_ANDROID_MIGRATION_PLAN.md
실행 명령: codex login status, 실제 /generate 및 follow-up, ADB install/launch/rotate/process recreation, SQLite task/event/attachment 확인, Python unittest/py_compile, host Gradle unit/compile/assemble
테스트 결과: Codex ChatGPT 인증 정상, 실제 gpt-5.4로 Native V1 및 V2-V6 생성 성공, 서버 전체 55개 테스트 통과, 호스트 unit/Kotlin compile/assemble 통과
ADB 기기: R5CT60A8H4R (SM-S908N, API 36). 원본 Task 8b69aac6cb7140f098f3101db2a1ea29, 분기 Task 8c8129b1cc8648ada186971a8137e2c6
빌드 시간: V6 cold launch 192ms. 실제 최초·수정 Codex/Gradle 시간은 앞선 Phase 9 기록 유지
APK 크기: 실제 생성 APK 약 5.1MB, V1과 V6 모두 동일 applicationId 및 고정 signer 확인
발견된 문제: 처리 완료된 최초 prompt 확인 버튼이 cold relaunch 후 다시 노출됨. PDF 첨부는 회전 후 View 칩만 남고 모델 상태가 유실될 수 있었음. 서버가 image/* 외 PDF·텍스트 첨부를 normalize 단계에서 조용히 제거하여 attachment_count=0으로 기록함. 런타임 LLM 키가 없어 성공 호출은 검증하지 못함
해결 내용: prompt 확인 action을 message ID 단위로 재바인딩. 첨부 payload를 앱 전용 임시 파일에 보존하고 회전·프로세스 재생성 시 작은 metadata로 복원하며 전송·삭제 시 정리. 서버 첨부 정규화·파일 저장·DB kind·Codex prompt/context를 image/pdf/text 공통으로 일반화하고 PDF/텍스트 크기·형식 검증 및 회귀 테스트 추가
커밋 SHA: 미커밋
다음 단계: 사용자가 8000번 서버를 새 코드로 재시작한 뒤 PDF 실기기 attachment_count=1 및 workspace 저장 재검증. APP_RUNTIME_OPENAI_API_KEY 설정 후 런타임 LLM 텍스트·이미지 성공 검증. 다운로드 퍼센트·설치 전환 성능 3회 측정 후 Native AWS canary 배포
```

```text
일시: 2026-08-14 19:06-19:42 KST
Phase: 7-9 서버 재시작 후 첨부·사용량·다운로드·UI 실기기 후속 검증
변경 파일: flutter_apk_server/server.py, tests/test_native_build_pipeline.py, TokenUsageMockRepository.kt, TokenUsageActivity.kt, SettingsActivity.kt, activity_token_usage.xml, values/strings.xml, values/colors.xml, values-night/colors.xml, TokenUsagePresentationTest.kt, NATIVE_ANDROID_MIGRATION_PLAN.md
실행 명령: ADB install/launch/rotate/photo picker/package installer, SQLite task_events/task_attachments 조회, server unittest discover, host Gradle unit/compile/assemble, gfxinfo/logcat
테스트 결과: 서버 58개 통과, 호스트 unit/Kotlin compile/assemble 통과. PDF는 v4 수정 build까지 성공했고 DB/workspace/Codex referenced_files를 확인. 일반 text 파일은 kind=text, MIME, SHA-256, workspace 저장 및 Codex 마커 판독을 확인. 이미지 2장과 텍스트 초안은 회전 후 모두 유지
ADB 기기: R5CT60A8H4R (SM-S908N, API 36), 라이트 모드·자동 회전 복구, tcp:8000/tcp:8012 reverse 유지
빌드 시간: 호스트 최종 Gradle unit/compile/assemble 7초. APK 다운로드 714/669/663ms, median 669ms
APK 크기: v4 5,115,158 bytes
발견된 문제: secondary usage window null이 주간 남은 용량 0%로 표시되고 API 실패 시 예시 토큰이 실제 값처럼 표시됨. 다크모드 overview 제목 대비 부족. Codex의 정상 마커 응답을 사용자용 보안 필터가 코드 변수로 오인해 '앱 내부 구현'으로 변경함. 완성 Task의 질문 응답 후 status가 Pending Decision으로 퇴행함. 시스템 설치 승인 후 Samsung/Play Protect/생체 인증이 개입해 이번 회차의 자동 실행 시간은 미측정
해결 내용: 없는 usage window를 null/정보 없음으로 유지하고 가짜 fallback 수치 제거, 설정 요약 동적 구성, dark overview 전용 고대비 색상 추가, 오류 문구에서 내부 endpoint 제거. 후속 응답 필터는 경로·코드 식별자를 계속 가리되 사용자 마커는 보존하고, APK가 있는 Task의 질문은 Success 상태를 유지하도록 수정. 일반 UI 부하 재측정은 603 frame, jank 1.99%, p95 10ms, missed vsync 0
커밋 SHA: 미커밋
다음 단계: 현재 server.py는 --reload 없이 실행 중이므로 다음 명시적 재시작 후 text marker 응답·Success status 유지 E2E 재검증. APP_RUNTIME_OPENAI_API_KEY 설정 후 런타임 LLM 텍스트·이미지 검증. 다운로드 중간 퍼센트는 더 큰 APK 또는 제한 네트워크에서 시각 확인. 별도 Native AWS canary 배포
```

```text
일시: 2026-08-14 19:48-20:00 KST
Phase: 8-9 런타임 API, 다운로드 진행률, Task 목록 전환 실기기 후속 검증
변경 파일: NATIVE_ANDROID_MIGRATION_PLAN.md
실행 명령: 생성 앱 ADB launch/input/tap/scroll, runtime LLM text 요청, runtime image API 요청, SQLite task_events/task_attachments/app_llm_usage 대조, 임시 저속 HTTP proxy, ADB reverse 원복, Task drawer 100ms 반복 캡처
테스트 결과: 생성 앱의 text 요청은 실제 서버에 도달해 요청·컨텍스트 전문과 configuration_error 사용량이 각각 기록됨. image 요청은 9.9ms에 수신되어 image_attached/MIME/event 연결과 최적화 JPEG 파일·DB 크기·SHA-256 일치를 확인. API 키 미설정으로 실제 모델 성공 응답은 미검증. 저속 다운로드는 같은 APK 버블에서 15%/48%/84%로 갱신되고 완료 후 PackageInstaller 업데이트 화면 진입. Task 목록은 세 차례 모두 100ms 캡처에서 완전히 표시
ADB 기기: R5CT60A8H4R (SM-S908N, API 36), 검증 후 tcp:8000->tcp:8000 및 tcp:8012->tcp:8012 복구
빌드 시간: 이번 회차 신규 빌드 없음. runtime image 저장·configuration error 응답 9.9ms
APK 크기: v4 5,115,158 bytes
발견된 문제: 기존 Task의 app_llm_configs에 API 키가 없어 실제 LLM text/image 성공 호출 불가. 생성 앱은 이 상태를 일반 400 서버 실패로만 안내함. 현재 설치된 생성 앱의 AI Advice 버튼 영문 표기와 다크 테마 색 대비가 낮음. 현재 실행 서버는 --reload가 아니어서 앞서 수정한 text marker 보존·Success 상태 유지 코드는 아직 반영되지 않음
해결 내용: 검증용 저속 프록시는 종료하고 ADB reverse를 원복. 다운로드 진행률과 Task 목록·로그 전환 검증 항목을 완료 처리. API 키가 필요한 실제 모델 성공 검증과 생성 앱 표현 개선은 완료 처리하지 않음
커밋 SHA: 미커밋
다음 단계: APP_RUNTIME_OPENAI_API_KEY를 안전하게 설정하고 기존 Task 설정에 적용한 뒤 생성 앱 text/image 실제 성공 응답과 전문 raw_response 로깅 검증. 다음 명시적 서버 재시작 후 text marker/Success 상태 E2E 재검증. 설치 UI 진입 및 자동 실행 시간을 사용자 승인 흐름이 가능한 환경에서 측정하고 별도 Native AWS canary 배포
```

```text
일시: 2026-08-14 20:10-20:39 KST
Phase: 7-9 서버·호스트 회귀 및 미검증 UI 실기기 검증
변경 파일: BaseProject VibeLlmClient.kt 및 unit test, flutter_apk_server/project_builder.py, server.py, server tests, ChatResponseScrollPolicy.kt 및 test, MainActivity.kt, NATIVE_ANDROID_MIGRATION_PLAN.md
실행 명령: server unittest discover/py_compile, BaseProject unit/compile/lint/signed release, host unit/compile/assemble, ADB install/launch/input/tap/swipe/screencap/logcat, SQLite event 대조, curl API 3회 측정, 임시 8012 source server와 ADB reverse 원복
테스트 결과: 서버 65개 통과, 호스트 unit/Kotlin compile/assemble 통과, BaseProject unit/Kotlin compile/lint/signed release 통과. 로그 보기와 사용량 카드 왼쪽 가장자리, 첨부 + 왼쪽 가장자리에서 전체 클릭 영역이 동작. 다크 모드 사용량 화면의 배경·카드·텍스트 대비 정상. Task 목록/상태/리비전 API median 약 2/5/4ms, usage 약 0.80초. 세 차례 answer-only 요청은 사용자·답변 event가 각각 1건이며 최종 요청은 FINAL_SCROLL_OK 원문이 DB와 화면에 1회 표시되고 입력창 위로 자동 정렬됨. crash buffer 비어 있음
ADB 기기: R5CT60A8H4R (SM-S908N, API 36), 호스트 versionCode 6, 검증 후 tcp:8000->tcp:8000 및 tcp:8012->tcp:8012 복구, 라이트 모드 복구
빌드 시간: host unit/compile/assemble 12초, BaseProject warm signed release 검증 통과. answer-only 응답 33/34/25초, median 33초
APK 크기: BaseProject signed release 5,095,498 bytes, SHA-256 f280a495b80ee1c14b551aa6bffcd9380e1ce3e705b515639bec278236c315fd
발견된 문제: Codex가 반환한 7일 창 10080분을 서버가 무조건 5시간 한도로 표기함. 사용자가 전송한 뒤 진행 버블이 최종 답변 자동 스크롤 예약을 취소함. 사용자 요청의 SCROLL_OK 같은 리터럴을 내부 식별자 필터가 앱 내부 구현으로 바꿔 동일 과거 답변과 중복 처리함. 생성 앱 runtime LLM 키는 계속 미설정. AWS 13.124.86.162:22 SSH는 timeout
해결 내용: 한도 window duration으로 단기/주간 창을 분류하여 7일 창을 주간 카드에 표시. 진행 중에는 사용자 위치를 고정하되 명시적 전송의 최종 스크롤 예약을 유지. 요청에 포함된 리터럴과 전체 마커 응답은 보존하고 영어 답변도 비어 있지 않으면 유지. BaseProject runtime helper를 최초·수정·분기·최종 build 직전에 템플릿 원본으로 복원해 오래된 workspace에도 최신 런타임 계약을 적용. 생성 앱 LLM HTTP/configuration 오류는 endpoint/API key를 노출하지 않는 사용자 문구로 변환
커밋 SHA: 미커밋
다음 단계: APP_RUNTIME_OPENAI_API_KEY를 안전하게 설정하고 기존 Task app_llm_config에 적용하여 text/image 실제 모델 성공과 raw_response 전문 로깅 검증. 사용자 승인 가능한 설치 흐름에서 설치 후 자동 실행 시간 측정. AWS SSH 접근 복구 후 별도 Native canary 배포
```

```text
일시: 2026-08-14 20:40-20:52 KST
Phase: 7-9 서버 재시작 상태 확인 및 미검증 UI·오류·지연 실기기 후속 검증
변경 파일: NATIVE_ANDROID_MIGRATION_PLAN.md
실행 명령: lsof/ps/curl, server unittest discover/py_compile, host Gradle unit/compile/assemble, BaseProject unit/compile/lint/signed release, ADB tap/swipe/reverse/uiautomator/screencap/logcat/gfxinfo, SQLite task_events/app_llm_configs 대조, 임시 8012 latest-source server
테스트 결과: 서버 65개 통과, 호스트 unit/Kotlin compile/assemble 통과, BaseProject unit/Kotlin compile/lint/고정 서명 release 통과. 로그 보기·설정·사용량·리비전 선택의 왼쪽 배경 터치가 모두 동작. 첨부 +를 3회 빠르게 눌러도 선택창 1개만 표시되고 뒤로가기 1회로 완전히 닫힘. 사용량 로딩 중 상태와 연결 실패 fallback이 빈 화면 없이 표시됨. latest-source에서 10080분 창은 주간 한도 61%로 표시됨
ADB 기기: R5CT60A8H4R (SM-S908N, API 36), 호스트 versionCode 6, 검증 후 tcp:8000->tcp:8000만 유지하고 임시 tcp:8012 reverse·서버 종료
빌드 시간: 호스트 warm unit/compile/assemble 3초, BaseProject warm signed release 3초. 사용량 API 약 0.90-0.92초, 호스트 warm launch 191ms
APK 크기: BaseProject signed release 5,095,498 bytes, SHA-256 f280a495b80ee1c14b551aa6bffcd9380e1ce3e705b515639bec278236c315fd
발견된 문제: 실행 중인 메인 서버는 19:00:45에 시작되었고 server.py 최신 수정은 20:35:09이므로 최신 한도 분류·응답 보존 코드가 메인 프로세스에 미반영. 두 Task의 app_llm_configs.api_key가 비어 실제 text/image 모델 성공 호출은 불가. 473 frame 중 jank 7.40%, p50 5ms, p90 8ms, p95 13ms, p99 105ms이며 ANR·crash·Skipped frames는 없음
해결 내용: 메인 서버를 종료하지 않고 8012 보조 서버로 최신 소스를 실기기 검증한 뒤 종료하고 ADB reverse를 원복. 실행 중 메인 서버는 PID 77178로 계속 유지
커밋 SHA: 미커밋
다음 단계: 사용자가 현재 작업을 멈춘 후 메인 서버를 최신 소스로 명시적 재시작. APP_RUNTIME_OPENAI_API_KEY 설정 후 새 Task에서 runtime LLM text/image 성공·raw_response 전문 로깅 검증. 설치 후 자동 실행 시간 측정과 AWS Native canary 배포
```

각 작업 단계가 끝날 때 아래 형식으로 기록한다.

```text
일시:
Phase:
변경 파일:
실행 명령:
테스트 결과:
ADB 기기:
빌드 시간:
APK 크기:
발견된 문제:
해결 내용:
커밋 SHA:
다음 단계:
```
