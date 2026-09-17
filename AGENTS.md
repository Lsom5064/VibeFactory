# AGENTS.md

## 프로젝트와 작업 범위

VibeFactory는 비개발자가 Android 호스트 앱에서 대화·이미지·시각적 UI 표시로 앱을 요청하면,
서버가 Codex CLI로 소스를 구현하고 APK를 빌드하는 서비스다.
현재 생성 대상은 **Kotlin + Android Views/XML**이다. 활성 서버 코드는 `server/`에 있으며,
Flutter/Dart 또는 Compose로 생성하지 않는다.

- 이 문서는 서비스 유지보수를 시작하는 Codex를 위한 현재 구조 안내다. 사용자와는 한국어로 소통한다.
- 현재 로컬 파일을 기준으로 작업한다. 시작할 때 `git status --short`를 확인하고 기존 변경을 보존한다.
- `server/`, `vibefactory/`, `BaseProject/`를 연결된 하나의 서비스로 검토한다.
- 하위 디렉터리의 `AGENTS.md`도 읽는다. Task 안의 생성용 `AGENTS.md`는 서버가 별도로 작성한다.
  생성 에이전트의 소스 작성 범위는 해당 Task 지침을 따르며, 이 문서가 서비스 전체 수정 권한을 뜻하지 않는다.
- `docs/history/`는 과거 계획·검증 기록이다. 당시 Goal, 임시 포트, 복사·배포 명령을 현재 작업 지침으로 실행하지 않는다.

## 반드시 보존할 것

- 명시적인 요청 없이 파일, DB, workspace, 참가자 기록, 첨부파일을 삭제하지 않는다.
  `exports/`와 Git에서 무시되는 파일도 사용자 데이터이며 정리 가능한 캐시로 간주하지 않는다.
- 테스트를 위해 호스트 앱을 삭제하거나 `adb shell pm clear`로 데이터를 초기화하지 않는다.
  설치가 필요하면 기존 패키지·서명을 확인하고 `adb install -r`로 업데이트한다.
  서명이 달라 설치가 실패해도 앱 삭제로 우회하지 않는다.
- 사용자가 운영 중인 서버를 임의로 중지·재시작하지 않는다. 포트 충돌 때 PID와 실행 위치를 확인한다.
  별도 테스트 서버가 필요하면 포트뿐 아니라 두 DB, workspace 경로도 격리하고 시작한 프로세스를 기록한다.
- 서비스 코드 수정과 생성 앱 소스 수정을 구분한다. 운영 Task의 workspace를 직접 수정하는 것은
  해당 요청이 있을 때만 수행한다. 복사본 수정 요청은 `exports/` 등 지정된 위치에서 처리한다.
- `git reset --hard`, `git checkout --`, 강제 push, history rewrite를 임의로 사용하지 않는다.
  Git과 파일 상태가 다르면 파일 보존을 우선한다. 커밋·push·배포는 요청된 범위에서만 한다.
- API 키, 인증 토큰, PEM, keystore, 비밀번호를 문서·Git·도구 출력에 노출하지 않는다.
  참가자 키의 채팅 보존과 Agent 입력 치환은 [외부 연동 정책](server/EXTERNAL_INTEGRATIONS.md)을 따른다.

## 디렉터리 지도

| 경로 | 역할 |
| --- | --- |
| `run-local-server.sh` | 로컬 실행 진입점. 환경변수, 서명·연동 설정, Codex 실행 옵션, Python venv, Uvicorn 실행 |
| `server/` | 현재 FastAPI 서버, 생성·수정·분기·로그·사용량·첨부·UI 편집 API |
| `vibefactory/` | Kotlin 기반 Android 호스트 앱. 채팅, 첨부, UI 시각 수정, 다운로드·설치, 상태 알림 |
| `BaseProject/` | 생성 앱의 Native Android 템플릿과 공통 런타임 |
| `aws/native/` | Native 배포 스크립트, systemd·Nginx·환경변수 예시. 실제 배포 상태는 별도 확인 |
| `exports/` | 연구 분석 시트, 이미지, 발표자료, 사용자가 요청한 생성 앱 복사본. Git 제외 |
| `flutter/` | 이전 Flutter 코드·DB·workspace·배포 자료 보관소. 현재 서비스 실행에 사용하지 않음 |
| `docs/history/` | 전환 계획, 초기 XML 편집 Goal, 특정 시점의 E2E 보고서 |

## 서버에서 먼저 볼 파일

서버 디렉터리의 [AGENTS.md](server/AGENTS.md)와 [README.md](server/README.md)를 함께 읽는다.

| 파일 (`server/` 기준) | 주요 책임 |
| --- | --- |
| `server.py` | `create_app`, `Database`, `AppDataDatabase`, `CodexTaskRunner`, 요청·상태·리비전 처리, 프롬프트와 생성용 지침 |
| `server_settings.py` | 환경변수 해석, Codex 명령 구성, subprocess 환경, access log query 필터 |
| `api_models.py` | Pydantic 요청·응답 계약 |
| `project_builder.py` | `NativeAndroidProjectBuilder`: 템플릿 복사, 패키지·버전 적용, 공통 런타임, lint·APK 빌드·서명 검증, 캐시 |
| `reference_attachments.py` | 이미지·파일 검증, 최적화, 영구 저장과 메타데이터 |
| `prebuild_requirements.py` | 외부 API·계정·권한 등 생성 전 필수 조건과 키 등록 정책 |
| `ui_editor_server.py` | XML 검증, 시각 표시 검증, diff와 Codex 전달 문구 구성. HTTP route는 `server.py`에 있음 |
| `ui_catalog.py` | 화면 이름·사용설명서 카탈로그 검증 및 복구 |
| `codex_rate_limits.py` | Codex 사용량·한도 조회 |
| `admin_dashboard.py`, `admin_dashboard/` | `/admin/dashboard` 웹과 DB 조회 API. 데이터 API는 관리자 토큰 확인 |

생성 에이전트의 지침을 바꾸려면 `server.py`의 `render_task_agents_md`, `render_prompt_md`,
후속 요청 처리와 builder의 계약 문구를 확인한다. 이 루트 문서만 바꾸어도 생성 앱 프롬프트가
모두 바뀐다고 가정하지 않는다. 모델·reasoning·fast 설정은 `run-local-server.sh`와
`server_settings.py`를 확인하고 실제 프로세스 설정과 코드 기본값을 구분한다.

## 호스트 앱과 생성 템플릿

호스트 Kotlin 소스는 `vibefactory/app/src/main/java/kr/ac/kangwon/hai/vibefactory/`에 있다.

- `MainActivity.kt`: 채팅·Task 선택·요청·화면 상태 연결. 기능별 policy/helper를 먼저 확인하고 기능을 한 파일에 계속 추가하지 않는다.
- `ApiService.kt`, `ApiModels.kt`, `HostAppConfig.kt`: Retrofit API·DTO·서버 주소·환경설정 계약.
- `HostPreferencesStore.kt`, `TaskChatFileCodec.kt`, `ComposerDraftViewModel.kt`, `ComposerDraftAttachmentStore.kt`: 채팅 캐시·입력 초안·첨부 복구.
- `MainUiAdapters.kt`, `TaskTimelineRenderCache.kt`, `UiRenderFingerprint.kt`, `ChatResponseScrollPolicy.kt`,
  `AsyncTaskUiOwnershipPolicy.kt`: 부분 렌더링, 스크롤 유지, 비동기 응답의 채팅방 소유권.
- `PromptReviewActivity.kt`, `PromptReviewMessagePolicy.kt`: 최초 생성 프롬프트 편집과 최종 전송 문구 표시.
- `BuildMonitorService.kt`, `BuildNotificationController.kt`: 백그라운드 상태 감시와 완료·실패 알림.
- `ApkArtifactActionHandler.kt`, `GeneratedAppInstallPolicy.kt`: 다운로드, 임시 APK, 설치·열기 동작.
- `ui_editor/UiAnnotationEditorActivity.kt`, `UiAnnotationModels.kt`, `UiAnnotationOverlayView.kt`,
  `UiAnnotationDraftStore.kt`, `UiPreviewRenderer.kt`: 현재 사용자용 시각 수정 흐름.
  같은 디렉터리의 `UiEditorActivity.kt` 등 기존 XML 편집 구현을 현재 진입점과 혼동하지 않는다.
- 레이아웃·색상·문구는 `app/src/main/res/`에 있다. 밝은/어두운 테마를 함께 확인한다.

생성 앱 공통 코드는 `BaseProject/app/src/main/kotlin/kr/ac/kangwon/hai/generated/`에 있다.
`VibeLlmClient`, `VibeDataClient`, `VibeHttpClient`, `VibeCrashReporter`는 서버 연동 계약이다.
`GeneratedApplication`의 공통 초기화와 `GeneratedAppInitializer`의 앱별 초기화를 구분한다.
`UiGuideController`, `UiGuideHelpTab`은 생성 앱의 사용설명서와 도움말 탭을 담당한다.
템플릿 수정만으로 이미 설치된 APK가 갱신되지는 않는다. 기존 생성 프로젝트에 대한 적용 경로와 재빌드가 필요하다.

## 유지해야 하는 사용자 흐름과 계약

1. 최초 요청: 입력·첨부 → 명세 및 외부 연동 조건 확인 → 앱 생성 프롬프트 제시 → 사용자가 편집·전송 → 생성.
   최종 편집 문구가 실제 빌드 요청과 전체보기 버블에 동일하게 반영되어야 한다.
2. 생성 이후: 기존 Task의 질문·수정·오류 해결은 앱 소스를 참조할 수 있는 Codex 후속 처리로 연결한다.
   단순 답변과 빌드 요청을 구분하며, 최초 명세 버블이나 완료 APK 버블을 다시 추가하지 않는다.
3. 리비전: 같은 Task에서 새 `rev_XXXX`를 만든다. 이름 변경만으로 `task_id`나 `package_name`이 바뀌면 안 된다.
4. 분기: 선택 리비전에서 새 Task·workspace·Codex 세션을 만들고 원본 이력을 보존한다.
   현재 정책상 원본 패키지와 서명을 유지해 분기 앱끼리 덮어 설치할 수 있어야 하며 런타임 Task ID는 분기 Task로 바뀐다.
   분기 응답은 `202`이며 새 채팅방을 작업 완료까지 숨기지 않는다.
5. 시각 수정: 원본 XML 미리보기 위에 삭제(빨강 X), 이동(파랑 화살표), 모양·기능 변경(보라 톱니), 추가(초록 ＋)를 표시한다.
   원본 XML을 직접 바꾸지 않고 별도 `annotation_xml`과 설명·이미지를 저장한다.
   이동 목적지는 사용자가 지정한 화살표 끝 좌표를 유지한다.
   모양·기능 변경과 추가는 설명이 필수이며 스케치와 참고 이미지를 함께 저장한다. 새 주석은 v3, 기존 v1/v2 읽기는 유지한다.
   캔버스 이미지의 영역 내부 좌표와 순서를 보존하고, 원본 이미지·벡터 선·합성 스케치를 구분한다.
   그리기 캔버스의 확대·이동은 보기 변환이며 원본 기준 좌표를 바꾸지 않는다. 스타일만 요청하면 기존 동작을 보존한다.
   추가는 영역의 위치·크기와 스케치·설명을 보존한다. 배경색 덮기는 그리기 보조이며,
   명시적으로 선택된 교체 대상 외의 기존 UI와 기능을 유지한다. 적용된 표시를 누르면 삭제 확인창을 제공한다.
   저장은 빌드 시작이 아니다. 채팅의 체크박스와 `use_ui_editor_draft`가 선택됐을 때 저장한 표시를 후속 요청에 포함한다.
6. 사용설명서: `res/xml/vf_ui_catalog.xml`의 화면 이름과 요소 설명을 사용하며 `guideVersion`은 리비전에 대응한다.
   최초 표시 이후 다시 보기는 공통 도움말 탭에서 제공한다. 탭은 누르면 안내, 길게 누른 뒤 드래그하면 이동한다.
   생성 앱에 별도 상단 사용법 버튼을 중복 생성하지 않는다. 상세 계약은 [UI_CATALOG.md](server/UI_CATALOG.md)를 따른다.

상태 응답이 다른 채팅방으로 이동시키거나 입력 텍스트·첨부·스크롤을 초기화하면 안 된다.
목록 시간과 날짜 구분은 한국 시간으로 일관되게 표시한다. 이미지 단독 요청에 사용자 작성인 것처럼 자동 문구를 덧붙이지 않는다.
통신·이미지 디코딩·파일 I/O·무거운 JSON 작업을 메인 스레드에 넣지 않는다. `suspend`만으로 백그라운드 실행이 보장되지는 않는다.

API 수정 시 [호스트 연동 계약](server/HOST_APP_INTEGRATION_NOTES.md), 서버 DTO와 호스트 DTO를 함께 확인한다.
서버의 Task ID·리비전·산출물·타임라인 이벤트를 기준으로 캐시를 합친다.
실패·취소 상태는 안내 버블에 전달하고, 실패한 새 리비전 때문에 이전 성공 APK 이력을 잃지 않게 한다.

## DB, workspace, 로그

아래는 로컬 실행 기본값이다. 조사 시 환경변수 override와 실제 서버 실행 위치부터 확인한다.

| 환경변수 | 기본 경로 | 데이터 |
| --- | --- | --- |
| `DB_PATH` | `server/native_tasks.db` | Task, 이벤트, 첨부 메타데이터, 사용량, 리비전, 연동 설정, UI 초안·이미지 |
| `APP_DATA_DB_PATH` | `server/native_app_data.db` | 생성 앱 공유 데이터 (`app_data_records`) |
| `WORKSPACES_ROOT` | `server/native_workspaces/` | Task별 원본 첨부·소스·Codex 상태·로그·리비전·APK |
| `BUILD_CACHE_ROOT` | `server/.native_tooling/` | 공유 빌드 캐시 |

- DB 스키마는 `server.py`의 `Database`와 `AppDataDatabase`가 관리한다. PK 충돌 재시도, FK, 트랜잭션·migration 정책을 유지한다.
- 첨부는 `task_attachments` 등 DB 메타데이터와 workspace 파일로 연결된다. DB만 복사했다고 이미지까지 백업된 것이 아니다.
- Task workspace 아래 `revisions/rev_XXXX/project/`에 버전별 소스가 있다.
  상위 `project`는 서버가 관리하는 현재 리비전 경로이므로 실제 경로를 resolve한 뒤 다룬다.
- 생성 결과 계약은 `.codex_result/task_result.json`, release APK는 `project/app/build/outputs/apk/release/app-release.apk`다.
  성공 여부는 결과 JSON뿐 아니라 서버의 lint·빌드·패키지·서명 검증을 기준으로 한다.
- 사용자 입력, 프롬프트, LLM 입력·컨텍스트·응답·원시 응답·오류의 영구 기록을 길이 제한으로 자르지 않는다.
  화면 미리보기와 진행 로그의 축약은 영구 저장과 구분한다. 키 취급은 외부 연동 정책을 유지한다.
- SQLite 조사에는 읽기 전용 연결을 쓴다. 실행 중인 DB의 일관된 사본은 SQLite backup API 등으로 만든다.
  DB 파일 하나를 단순 복사해 WAL 변경까지 백업됐다고 가정하지 않는다.
- 운영 DB 대신 테스트용 임시 DB와 workspace를 쓴다. `flutter/runtime/`의 이전 DB와 혼합하지 않는다.

## 실행과 검증

실제 생성 환경은 Python 3.11+, JDK 17, Android SDK Platform 36와 build tools,
Codex CLI 인증 및 release 서명 설정이 필요하다. 정확한 의존성은 requirements와 Gradle 파일을 따른다.

```bash
# 저장소 루트에서, 기존 서버가 없는지 먼저 확인
lsof -nP -iTCP:8000 -sTCP:LISTEN
./run-local-server.sh
```

스크립트는 `~/.vibefactory/signing/generated-app-signing.env`와 `~/.vibefactory/integrations.env`를 읽는다.
대화형 터미널에서는 모델·reasoning·fast 선택을 제공하며 `LOCAL_SERVER_PROMPTS=0`이면 선택창을 생략한다.
`MOCK_CODEX=0`은 실제 생성, `1`은 모의 실행이다. Mock APK는 설치 가능한 앱 검증에 사용할 수 없다.

호스트 주소는 빌드 시 `VIBE_SERVER_BASE_URL`, 생성 앱 주소는 서버의 `SERVER_BASE_URL`에서 주입된다.
실기기에서 접근 가능한 주소를 사용한다. USB 검증용 `adb reverse tcp:8000 tcp:8000`을 설정한 경우에만
실기기에서 `127.0.0.1:8000`으로 Mac 서버에 접근할 수 있다. IP·기기 일련번호·PID는 매번 확인하고 고정하지 않는다.

변경 범위에 맞춰 다음 검증을 선택한다. 아래 명령은 저장소 루트 기준이며 서버 재시작을 필요로 하지 않는다.

```bash
# 서버 단위·계약 테스트 (기존 .venv에 requirements가 설치되어 있어야 함)
server/.venv/bin/python -m unittest discover -s server/tests -p 'test_*.py'

# 호스트 앱 단위 테스트와 빌드
./vibefactory/gradlew -p vibefactory :app:testDebugUnitTest :app:assembleDebug

# 생성 템플릿 단위 테스트와 lint
./BaseProject/gradlew -p BaseProject :app:testDebugUnitTest :app:lintDebug

# 설치가 요청된 경우: 기기와 기존 호스트의 서명을 확인한 후 업데이트
adb devices -l
adb -s <device-serial> install -r vibefactory/app/build/outputs/apk/debug/app-debug.apk
```

- 빌드 정책 변경은 `test_native_build_pipeline.py`, `test_project_identity.py`; API 변경은 `test_host_api_contract.py`;
  UI 기능은 `test_ui_editor_api.py`, `test_ui_catalog.py`와 호스트의 관련 단위·instrumentation 테스트를 검토한다.
- 생성 release APK는 고정 서명키를 사용한다. sideload 버전 코드는 `2100000000`이며
  과거 리비전 재설치를 위한 정책이므로 Android version code를 리비전 번호처럼 올리지 않는다.
  release 검증에는 서명 환경을 로드하고 `:app:assembleRelease` 및 `apksigner verify`가 필요하다.
- 실기기 검증 요청이 있으면 해당 사용자 흐름을 실제로 수행하고 logcat·서버 결과·DB 기록을 대조한다.
  백그라운드 완료, 채팅 전환, 회전·키보드, 초안·스크롤 유지, APK 재설치·열기를 변경 범위에 맞춰 확인한다.
- 지연은 요청·Codex·Gradle·다운로드·화면 반영 단계를 나누어 측정한다. 컴파일 성공이나 Mock 성공을 실제 E2E 성공으로 보고하지 않는다.
- 문서만 수정한 경우 파일 경로·링크·명령의 근거를 검증하며 불필요한 실제 앱 생성이나 기기 조작을 하지 않는다.

## 추가 문서

- [서버 실행·빌드](server/README.md), [서버-호스트 API 계약](server/HOST_APP_INTEGRATION_NOTES.md)
- [사용설명서 카탈로그](server/UI_CATALOG.md), [외부 API 연동](server/EXTERNAL_INTEGRATIONS.md)
- [Native AWS 배포](aws/README.md), [이전 Flutter 보관소](flutter/README.md)
- 과거 기록: [Native 전환](docs/history/NATIVE_ANDROID_MIGRATION_PLAN.md),
  [초기 XML 편집 개발](docs/history/XML_UI_EDITOR_GOAL.md), [사용설명서 5x5 검증](docs/history/GENERATED_UI_GUIDE_5X5_E2E_REPORT.md).
  과거 보고서의 성공·미완료 표시를 현재 운영 환경의 검증 결과로 사용하지 않는다.
