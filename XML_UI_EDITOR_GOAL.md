# VibeFactory XML UI Editor Historical Development Goal

- 작성일: 2026-08-20
- 문서 상태: **완료된 격리 개발 기록. 현재 실행 지침으로 사용하지 않음**
- 기준 저장소: `/Users/hai/Desktop/buildingAppswithCodex`
- 기준 소스: GitHub가 아니라 Goal 시작 시점의 현재 로컬 파일
- 실험 작업 루트: `/Users/hai/Desktop/buildingAppswithCodex/ui_xml_editor_workspace`

> **주의:** XML UI 편집 기능은 현재 저장소의 `flutter_apk_server/`,
> `vibefactory/`, `BaseProject/`에 병합되었고 격리 작업 디렉터리는 제거되었다.
> 아래의 디렉터리 복사, 8100 포트 실행, 원본 무변경 지침은 당시 개발 과정의
> 재현 기록일 뿐이며 새 작업에서 실행하면 안 된다. 현재 구현과 검증은 저장소
> 루트의 `AGENTS.md`, `NATIVE_ANDROID_MIGRATION_PLAN.md`와 실제 코드를 기준으로 한다.

## 1. Goal

현재 작동 중인 VibeFactory 서비스를 수정하거나 중단하지 않은 상태에서 서버, Android 호스트 앱, Native Android BaseProject의 핵심 소스만 별도 디렉터리에 물리적으로 복사한다. 복사본을 기반으로 생성 앱의 Android XML 화면을 불러와 편집 가능한 화면으로 재구성하고, 사용자가 기존 UI 요소를 이동·수정·삭제하거나 버튼·텍스트·이미지 등을 추가하고 각 요소의 의도와 동작을 설명할 수 있는 호스트 앱 XML UI 편집기를 구현한다. 모든 편집 결과는 기준 Revision의 기존 XML·Kotlin 코드와 함께 Codex에 전달하며, Codex가 새 Revision에서 실제 Android XML·Kotlin·리소스를 수정하고 서버가 검증·APK 빌드하도록 한다.

이 Goal의 완료 범위는 격리된 실험 디렉터리 안에서 기능과 실기기 검증을 끝내는 것까지다. 사용자 승인 없이 결과를 현재 운영 소스에 병합하거나 현재 서비스에 배포하지 않는다.

### Goals에 붙여넣을 문구

```text
작업을 시작하기 전에 저장소 루트의 XML_UI_EDITOR_GOAL.md 전체를 읽고, 이 문서를 구현 순서·안전 규칙·구조·검증 기준·완료 조건의 유일한 기준으로 사용한다. Goal 시작 시점의 현재 로컬 파일을 최신 원본으로 취급하되, 현재 작동 중인 서비스의 flutter_apk_server/, vibefactory/, BaseProject/, 기존 DB, workspace, profiles, 서버 프로세스와 8000번 포트는 절대 수정·이동·삭제·중단하지 않는다. 먼저 /Users/hai/Desktop/buildingAppswithCodex/ui_xml_editor_workspace/를 만들고, 문서에 정의된 제외 규칙에 따라 서버·호스트 앱·Native BaseProject의 핵심 소스만 core/ 아래에 물리적으로 복사한다. symlink를 사용하지 않고 원본·복사본 소스 manifest와 SHA-256을 기록하여 이후 원본 무변경 여부를 검증한다. 모든 구현·테스트·실행·DB·workspace·빌드 산출물은 격리 디렉터리 안에서만 수행하고 실험 서버는 8100번 포트와 별도 DB·workspace를 사용한다. 생성 앱의 layout XML과 관련 strings/colors/dimens/styles/drawable을 불러와 호스트 앱의 편집용 View로 재구성하고, 기존 요소 이동·크기·속성 변경·삭제, 새 버튼·텍스트·입력창·이미지·카드 추가, 요소별 표시 내용과 동작 설명, 실행 취소·다시 실행, 초안 저장·회전 복구를 구현한다. XML을 UI의 canonical source로 유지하고 JSON을 XML 렌더링 원본으로 사용하지 않는다. 호스트 내부에서는 XML을 UiNode 객체 트리로 다루며 알 수 없는 태그와 속성은 삭제하지 않고 잠긴 요소로 보존한다. 모든 시각적·동작 변경은 예외 없이 Codex에 전달하고, Codex는 복사된 새 Revision 안에서 변경 전 XML, 편집 후 XML, diff, 요소별 설명, 첨부 이미지, 기존 Kotlin 코드를 바탕으로 실제 XML·Kotlin·리소스를 수정한다. 기준 Revision은 직접 수정하지 않고 빌드 실패 시에도 그대로 유지한다. 서버 단위·계약·보안·round-trip 테스트, 호스트 unit·instrumentation 테스트, Native lint/release 빌드, ADB 실기기에서 XML 불러오기부터 새 Revision APK 설치까지의 E2E 검증, 지연·회전·초안 복구 검증을 모두 통과한 뒤에만 Goal을 완료 처리한다. 작업 중 XML_UI_EDITOR_GOAL.md의 체크리스트와 작업 기록을 실제 상태대로 계속 갱신한다. 검증이 끝나도 사용자 승인 전에는 원본 서비스로 병합·배포·포트 전환하지 않는다.
```

## 2. 확정된 결정

- 현재 서비스는 그대로 유지한다.
- 현재 로컬 파일을 복사 시점의 최신 소스로 사용한다.
- 실험은 `ui_xml_editor_workspace/` 안에서만 진행한다.
- 원본 디렉터리와 실험 디렉터리 사이에 symlink를 만들지 않는다.
- 생성 앱 UI의 canonical source는 Android XML이다.
- JSON은 XML을 대신하는 UI 원본이나 렌더링 필수 계층으로 사용하지 않는다.
- 호스트 앱 내부에서는 XML을 Kotlin `UiNode` 객체 트리로 변환해 편집한다.
- 생성 앱의 XML을 호스트 앱에서 직접 `LayoutInflater`로 inflate하지 않는다.
- 지원하는 XML 태그는 대응하는 호스트 편집용 View로 재구성한다.
- 동적 데이터는 더미 데이터로 표시하되 원본 XML과 속성을 보존한다.
- 지원하지 않는 태그는 삭제하지 않고 크기와 위치를 가진 잠긴 자리 표시자로 보여준다.
- 기존 UI 수정과 빈 캔버스 기반 새 화면 생성을 모두 지원한다.
- 단순 색상·문구·위치 변경도 포함해 모든 최종 변경을 Codex가 처리한다.
- 기존 Revision과 workspace를 직접 수정하지 않고 항상 새 Revision을 만든다.
- Codex는 편집 결과를 임의로 재설계하지 않고 사용자 배치를 우선한다.
- 원본 서비스 통합과 배포는 이 Goal에 포함하지 않으며 별도 사용자 승인이 필요하다.

## 3. 절대 안전 규칙

- 원본 `flutter_apk_server/`, `vibefactory/`, `BaseProject/` 파일을 수정하지 않는다.
- 원본 `tasks.db`, `native_tasks.db`, `app_data.db`를 쓰기 모드로 열지 않는다.
- 원본 `workspaces/`, `native_workspaces/`, `profiles/`를 수정·이동·삭제하지 않는다.
- 현재 8000번 서버를 중지·재시작·교체하지 않는다.
- 현재 서버 PID에 signal을 보내지 않는다.
- 원본 호스트 앱을 덮어 설치하지 않는다.
- 원본 package name과 다른 실험용 host application ID를 사용한다.
- 실험 서버는 기본적으로 `127.0.0.1:8100`을 사용한다.
- 실험 DB와 workspace는 `ui_xml_editor_workspace/runtime/` 아래만 사용한다.
- AWS 및 NAS에 배포·업로드·삭제하지 않는다.
- API 키, `.env`, PEM, keystore, 비밀번호를 복사하거나 Git에 추가하지 않는다.
- `git reset --hard`, `git checkout --`, 강제 push, history rewrite를 사용하지 않는다.
- 원본과 실험 복사본의 차이를 원본에 역적용하지 않는다.
- 실패한 검증 항목을 완료 처리하지 않는다.
- 사용자 승인 없이 실험 결과를 현재 서비스에 병합하지 않는다.

## 4. 격리 작업 구조

```text
ui_xml_editor_workspace/
├── README.md
├── SOURCE_MANIFEST.sha256
├── core/
│   ├── flutter_apk_server/
│   ├── vibefactory/
│   └── BaseProject/
├── runtime/
│   ├── db/
│   ├── workspaces/
│   ├── profiles/
│   ├── artifacts/
│   └── logs/
├── fixtures/
│   ├── layouts/
│   └── resources/
├── scripts/
└── docs/
    ├── API_CONTRACT.md
    ├── XML_SUPPORT_MATRIX.md
    ├── TEST_REPORT.md
    └── PERFORMANCE_REPORT.md
```

## 5. 핵심 소스 복사 정책

### 복사 대상

- 서버 Python 소스, 테스트, requirements, 실행에 필요한 문서와 설정
- 호스트 앱 Gradle 설정, wrapper, `app/src`, 테스트 소스
- Native BaseProject Gradle 설정, wrapper, `app/src`, 테스트 소스
- XML UI 편집기 구현에 필요한 공용 스크립트만 개별 검토 후 복사

### 복사 제외 대상

- `.git/`
- `.gradle/`, `.kotlin/`, `.idea/`
- `build/`, `__pycache__/`, `.pytest_cache/`, `.mypy_cache/`
- `.venv/`, `.tooling/`, `.native_tooling/`
- `tasks.db`, `native_tasks.db`, `app_data.db` 및 journal/WAL 파일
- `workspaces/`, `native_workspaces/`, `profiles/`
- `exports/`, `debug_workspaces/`, `downloaded_apks/`
- APK와 빌드 산출물
- `.env`와 로컬 비밀 설정
- PEM, keystore, signing key와 비밀번호
- `local.properties`

### 복사 절차

- 복사 전에 원본 핵심 소스 목록과 SHA-256 manifest를 기록한다.
- 제외 규칙을 적용한 물리 복사를 수행하며 `--delete` 옵션은 사용하지 않는다.
- 복사 후 실험 `core/`의 manifest를 별도로 기록한다.
- 복사본의 Python import, Gradle project root, BaseProject 경로를 실험 루트 기준으로 변경한다.
- 변경은 복사본에만 수행한다.
- 주요 단계가 끝날 때 원본 manifest를 재계산해 의도하지 않은 변경을 검사한다.

## 6. 독립 실행 환경

실험 환경은 다음 경로와 포트를 사용한다.

```text
SERVER_PORT=8100
DB_PATH=ui_xml_editor_workspace/runtime/db/tasks.db
APP_DATA_DB_PATH=ui_xml_editor_workspace/runtime/db/app_data.db
WORKSPACES_ROOT=ui_xml_editor_workspace/runtime/workspaces
PROFILES_ROOT=ui_xml_editor_workspace/runtime/profiles
BASE_PROJECT_PATH=ui_xml_editor_workspace/core/BaseProject
SERVER_BASE_URL=http://127.0.0.1:8100
```

- 실기기 연결에는 필요할 때만 `adb reverse tcp:8100 tcp:8100`을 사용한다.
- 기존 `tcp:8000` reverse 설정을 제거하지 않는다.
- 실험 호스트 앱은 기존 호스트 앱과 동시에 설치할 수 있는 application ID를 사용한다.
- 실험 앱 이름에는 개발용임을 식별할 수 있는 접미사를 사용한다.
- 실험 서버 시작·종료 스크립트는 PID 파일을 `runtime/`에 기록한다.
- 종료 스크립트는 해당 PID와 8100번 프로세스만 대상으로 한다.

## 7. 기능 범위

### 7.1 XML 화면 조회

- Task와 Revision을 선택한다.
- 해당 Revision의 `app/src/main/res/layout/*.xml` 목록을 조회한다.
- 화면별 XML과 직접 참조하는 리소스를 가져온다.
- 현재 XML SHA-256과 기준 Revision을 함께 제공한다.
- 아카이브되어 source를 사용할 수 없는 Revision은 편집 불가 사유를 표시한다.

### 7.2 기존 XML 화면 편집

- XML 계층을 편집용 View 계층으로 재구성한다.
- 기존 요소를 선택·이동·크기 조절·복제·삭제한다.
- 문구, 색상, 여백, 정렬과 기본 스타일을 수정한다.
- `android:id`는 명시적 교체가 아니면 유지한다.
- 이동 결과는 절대 좌표가 아니라 constraint, margin, 정렬 관계로 변환한다.
- 사용하지 않는 속성과 namespace를 임의로 삭제하지 않는다.

### 7.3 빈 캔버스 편집

- 새 layout 이름과 화면 유형을 정한다.
- 빈 `ConstraintLayout` 또는 `LinearLayout`에서 시작한다.
- 기본 UI 요소를 끌어다 배치한다.
- 새 화면을 기존 Activity/Fragment와 연결할 동작을 설명한다.

### 7.4 지원할 기본 요소

- `ConstraintLayout`, `LinearLayout`, `FrameLayout`
- `ScrollView`, `NestedScrollView`
- `TextView`, `MaterialTextView`
- `Button`, `MaterialButton`
- `EditText`, `TextInputLayout`, `TextInputEditText`
- `ImageView`
- `CheckBox`, `Switch`, `RadioButton`
- `CardView`, `MaterialCardView`
- `RecyclerView` 컨테이너와 item layout 자리 표시자

### 7.5 초기 제한 요소

- 임의 Custom View
- Data Binding 표현식
- 코드에서 동적으로만 생성되는 View
- 실제 API 데이터에 의존하는 실행 상태
- 복잡한 `include`, `merge`, Fragment 중첩
- Canvas 직접 그리기, WebView 내부 문서, 지도 및 카메라 실시간 화면

초기 제한 요소는 잠긴 자리 표시자로 보여주고 원본 XML은 그대로 보존한다.

## 8. 호스트 앱 UI/UX

### 진입점

- Revision 로그 화면에 `UI 편집` 명령을 추가한다.
- 기준 Task와 Revision을 항상 상단에 표시한다.
- `기존 화면 편집`과 `새 화면 만들기`를 구분한다.

### 편집기

- 상단 도구 모음: 화면 선택, 실행 취소, 다시 실행, 미리보기, 저장
- 중앙: 확대·축소 가능한 캔버스
- 하단 요소 서랍: 텍스트, 버튼, 입력창, 이미지, 카드, 목록 등
- 선택 요소 속성 패널: 크기, 여백, 정렬, 색상, 문구
- 요소 설명 패널: 표시 내용, 사용자 의도, 눌렀을 때 동작, 데이터 처리
- 레이어 목록: 계층 확인과 앞뒤 순서 변경
- 삭제 전에 Kotlin 참조 가능성을 경고한다.

### 상태 보존

- 편집 초안을 자동 저장한다.
- 회전, 백그라운드 복귀, 프로세스 재생성 후 초안을 복구한다.
- 전송 성공 시에만 초안을 제출됨 상태로 변경한다.
- 서버 상태 업데이트가 편집 화면과 입력 내용을 초기화하지 않게 한다.
- 모든 네트워크 및 XML 파싱은 메인 스레드 밖에서 수행한다.

## 9. XML 처리 계약

- Android XML이 canonical source다.
- XML은 namespace를 보존하는 구조적 파서로 처리한다.
- DTD와 외부 entity를 금지한다.
- 호스트 내부에서 `UiNode` 객체 트리를 사용한다.
- 원본 XML DOM과 노드 ID의 대응 관계를 유지한다.
- 알 수 없는 태그와 속성은 round-trip 과정에서 보존한다.
- `@string`, `@color`, `@dimen`, `@drawable`, `@style`, theme attribute를 해석한다.
- 리소스를 해석하지 못하면 원문 참조와 경고를 함께 유지한다.
- no-op 저장 시 XML의 의미와 모든 리소스 참조가 유지되어야 한다.
- XML 주석만을 요소 설명 저장소로 사용하지 않는다.

JSON은 draft 전송이나 DB 직렬화에 내부적으로 사용할 수 있지만 XML을 대신하는 UI 원본이 아니며 사용자가 다룰 필요가 없다.

## 10. 요소 설명 계약

각 요소는 다음 설명을 가질 수 있다.

```text
표시 내용
사용 목적
탭 또는 입력 시 동작
읽고 쓰는 데이터
화면 이동 대상
오류 및 빈 상태 처리
접근성 설명
```

- 설명은 stable element ID와 연결한다.
- 새 요소에는 고유한 임시 ID를 발급하고 Codex 요청 전 Android resource ID로 정규화한다.
- 설명은 별도 draft metadata에 전문 저장한다.
- 필요하면 편집 후 XML에 `tools:vibeDescription`을 추가하되 런타임 동작에는 사용하지 않는다.

## 11. Codex 요청 계약

모든 편집 요청에서 Codex를 실행한다. 다음 자료를 빠짐없이 제공한다.

- Task ID와 기준 Revision
- 변경 전 XML 전문
- 편집 후 XML 전문
- 구조적 XML diff
- 편집 화면 스크린샷
- 요소별 설명 전문
- 추가한 이미지 원본 또는 최적화 사본
- 기존 Kotlin 코드와 관련 리소스
- 기존 package name, runtime Task ID와 서버 계약
- 유지해야 할 기존 기능과 알려진 제한

Codex 지침은 다음을 강제한다.

- 사용자 편집 후 XML을 UI 의도의 우선 기준으로 사용한다.
- 사용자가 요구하지 않은 재디자인을 하지 않는다.
- 다양한 화면 크기에서 동작하도록 constraint와 scroll 구조를 보정한다.
- 기존 View ID와 Kotlin 동작을 가능한 한 유지한다.
- 추가·삭제된 요소에 맞춰 Kotlin 참조와 이벤트를 갱신한다.
- 기존 런타임 LLM, 데이터 API, 오류 보고 계약을 유지한다.
- 모든 구현 결과와 제한을 `task_result.json`에 기록한다.

## 12. Revision 및 빌드 흐름

```text
기준 Revision 선택
→ XML/리소스 읽기
→ UI draft 편집
→ 사용자 최종 확인
→ 새 Revision workspace 복사
→ 편집 자료와 기존 코드를 Codex에 전달
→ Codex XML/Kotlin/리소스 수정
→ XML parse 및 resource link 검사
→ Kotlin compile
→ lint
→ release APK 빌드
→ package/version/signature 검증
→ 새 Revision 성공 처리
```

- 기준 Revision은 읽기 전용으로 유지한다.
- Codex는 새 Revision 복사본에서만 실행한다.
- 빌드 실패 시 기준 Revision과 기존 APK를 유지한다.
- 실패 원인과 복구 가능 상태를 호스트 버블로 표시한다.
- 성공한 APK는 같은 Task 계열의 package name과 고정 서명을 유지한다.

## 13. DB 및 로깅

실험 DB에 UI draft 전용 저장 구조를 추가한다.

필수 정보:

- `draft_id`
- `task_id`
- `base_revision_label`
- `layout_name`
- 원본 XML SHA-256
- 원본 XML 전문
- 편집 XML 전문
- 요소별 설명 전문
- 추가 이미지 metadata와 저장 경로
- draft 상태
- 생성·수정·제출 시각
- 생성된 Revision label

로그에는 다음 이벤트를 전문 저장한다.

- UI 편집 시작
- layout XML 조회
- 요소 추가·이동·수정·삭제
- 설명 변경
- 초안 저장과 복구
- 제출
- Codex 입력 자료
- Codex 응답과 토큰 사용량
- XML·compile·lint·build 결과
- 최종 APK 및 Revision

로그의 사용자 화면에는 절대경로, 내부 변수명, 비밀정보를 노출하지 않는다.

## 14. 서버 구현 단계

### Phase 0. 격리와 기준선

- [x] 현재 8000번 서비스 health와 PID를 기록한다.
- [x] 원본 핵심 소스 SHA-256 manifest를 생성한다.
- [x] `ui_xml_editor_workspace/` 구조를 생성한다.
- [x] 제외 규칙대로 핵심 소스를 물리 복사한다.
- [x] 복사본 manifest와 복사 보고서를 작성한다.
- [x] 실험 전용 환경 변수와 8100번 실행 스크립트를 만든다.
- [x] 원본 디렉터리 무변경을 확인한다.

### Phase 1. 읽기 전용 XML API

- [x] Revision layout 목록 API를 구현한다.
- [x] XML 및 관련 리소스 조회 API를 구현한다.
- [x] Task 접근 권한을 기존 API와 동일하게 적용한다.
- [x] path traversal과 XML entity 공격을 차단한다.
- [x] XML SHA-256과 source availability를 반환한다.
- [x] 기존 Task API 계약 회귀 테스트를 통과한다.

### Phase 2. 호스트 XML 미리보기

- [x] 별도 `ui_editor` 패키지와 Activity를 만든다.
- [x] XML parser와 `UiNode` 모델을 구현한다.
- [x] 기본 layout 및 View tag renderer를 구현한다.
- [x] string/color/dimen/style resolver를 구현한다.
- [x] RecyclerView와 동적 영역에 더미 데이터를 표시한다.
- [x] 미지원 요소를 잠긴 상태로 보존한다.
- [x] no-op round-trip 테스트를 통과한다.

### Phase 3. 편집 기능

- [x] 선택·이동·크기 조절을 구현한다.
- [x] constraint와 margin 변환을 구현한다.
- [x] 요소 추가·복제·삭제를 구현한다.
- [x] 텍스트·색상·이미지·기본 속성 변경을 구현한다.
- [x] 레이어 및 계층 편집을 구현한다.
- [x] 요소별 설명 입력을 구현한다.
- [x] 실행 취소·다시 실행을 구현한다.
- [x] 회전·백그라운드·프로세스 재생성 후 초안을 복구한다.

### Phase 4. Draft 및 Codex 연동

- [x] 실험 DB에 UI draft 저장 구조를 추가한다.
- [x] 자동 저장, 충돌 검사, 제출 API를 구현한다.
- [x] 새 Revision 복사 후 Codex를 실행한다.
- [x] 변경 전/후 XML, diff, 설명, 이미지, Kotlin 코드를 전달한다.
- [x] 모든 변경 유형에서 Codex가 실행되는지 검증한다.
- [x] 전체 입력·응답·토큰·오류를 전문 로깅한다.

### Phase 5. 검증 및 APK

- [x] XML parse와 resource linking을 검증한다.
- [x] Kotlin compile과 lint를 실행한다.
- [x] release APK를 빌드한다.
- [x] package name, version code, signature를 검증한다.
- [x] 기존 앱 위에 Revision APK가 덮어 설치되는지 확인한다.
- [x] 성공·실패 버블과 Revision 목록을 확인한다.

### Phase 6. 실기기 E2E

- [x] 기존 XML 화면을 불러온다.
- [x] 기존 버튼을 이동하고 설명을 추가한다.
- [x] 텍스트를 변경한다.
- [x] 이미지를 추가한다.
- [x] 요소를 삭제한다.
- [x] 빈 캔버스에서 새 화면을 만든다.
- [x] 편집 중 화면을 회전하고 초안 유지 여부를 확인한다.
- [x] 백그라운드 복귀 후 편집 상태를 확인한다.
- [x] 제출 후 새 Revision APK를 다운로드·설치·실행한다.
- [x] 기존 기능과 런타임 계약이 유지되는지 확인한다.
- [x] 원본 8000번 서비스가 계속 정상인지 확인한다.

## 15. 테스트 전략

### 서버

- XML parser 정상·비정상 입력
- DTD, 외부 entity, path traversal 차단
- layout/resource allowlist
- Revision 접근 권한
- draft 충돌과 재시도
- 원본 Revision 불변성
- Codex 실패·timeout·취소
- DB FK와 중복 ID 무결성
- 전체 로그 보존

### 호스트 앱

- XML tag와 속성 매핑
- 리소스 참조 해석
- drag/resize 좌표와 constraint 변환
- undo/redo
- draft 자동 저장
- 회전 및 process recreation
- 큰 XML에서 메인 스레드 정지 여부
- 화면 요소의 전체 터치 범위
- 미지원 요소 round-trip 보존

### 생성 앱

- 작은 화면과 큰 화면
- 세로·가로 회전
- 키보드 표시
- 긴 텍스트와 이미지 비율
- RecyclerView와 스크롤
- 기존 클릭 동작
- 추가한 요소 동작
- 이전 APK 위에 설치와 자동 실행

## 16. 성능 기준

- layout 목록 화면 진입 시 체감 정지 없음
- 일반 XML 화면의 첫 편집 미리보기 목표: 2초 이내
- drag/resize 중 메인 스레드 파일·네트워크 I/O 없음
- 캔버스 상호작용 목표: 60fps, 최소 허용 30fps
- 자동 저장은 debounce하며 입력과 드래그를 차단하지 않음
- 서버 상태 polling이 편집 View 전체를 다시 생성하지 않음
- 큰 drawable은 편집용 해상도로 downsample
- XML 파싱과 이미지 디코딩은 coroutine background dispatcher에서 수행

실측값은 `docs/PERFORMANCE_REPORT.md`에 기기, 화면, 파일 크기와 함께 기록한다.

## 17. 숨은 위험과 대응

| 위험 | 대응 |
|---|---|
| 다른 앱의 raw XML 직접 inflate 불가 | 지원 태그를 호스트 편집용 View로 재구성 |
| Kotlin에서 동적으로 생성한 UI가 XML에 없음 | 편집 불가 안내 또는 더미 영역 사용 |
| ID 삭제로 기존 코드가 깨짐 | 코드 참조 검사와 Codex 수정, compile 검증 |
| 절대 좌표로 다른 화면 크기에서 깨짐 | constraint·margin·비율로 변환하고 다중 크기 검증 |
| Custom View를 잘못 변환 | 잠긴 자리 표시자와 원본 XML 보존 |
| 리소스 참조를 해석하지 못함 | 원문 참조 보존, 경고 표시, Codex에 전체 리소스 전달 |
| Codex가 사용자의 UI를 재설계 | 편집 후 XML을 우선 계약으로 명시하고 screenshot 비교 |
| 오래된 Revision을 동시에 편집 | XML hash 기반 optimistic locking |
| 초안이 상태 polling이나 회전으로 사라짐 | 독립 ViewModel, SavedState, 서버 draft 자동 저장 |
| 큰 XML·이미지로 호스트가 멈춤 | background parse, pagination/lazy render, image downsample |
| 실험 코드가 현재 서비스에 섞임 | 물리 복사, 별도 포트·DB·package, 원본 hash 재검증 |
| 실험 서버 종료가 운영 서버를 종료 | 실험 PID 파일과 8100번 포트만 대상으로 제한 |

## 18. 완료 조건

다음 조건을 모두 만족해야 Goal을 완료할 수 있다.

- [x] 구현과 실행이 모두 `ui_xml_editor_workspace/` 안에서 이루어졌다.
- [x] 원본 핵심 소스 SHA-256이 Goal 시작 시점과 동일하다.
- [x] 현재 8000번 서비스가 작업 전후 모두 정상이다.
- [x] 원본 DB와 workspace가 변경되지 않았다.
- [x] 기존 XML 화면을 호스트에서 편집 가능한 형태로 불러온다.
- [x] 요소 이동·크기·속성·삭제와 새 요소 추가가 동작한다.
- [x] 요소별 설명과 이미지 첨부가 저장·복구된다.
- [x] no-op XML round-trip에서 의미와 미지원 속성이 보존된다.
- [ ] 모든 수정 요청이 Codex를 거친다.
- [ ] 새 Revision에서 XML·Kotlin·리소스가 함께 수정된다.
- [x] 기준 Revision은 변경되지 않는다.
- [x] 서버 전체 테스트가 통과한다.
- [x] 호스트 앱 unit 및 instrumentation 테스트가 통과한다.
- [x] Native lint와 release APK 빌드가 통과한다.
- [x] ADB 실기기 E2E가 통과한다.
- [x] 성능 보고서와 남은 제한사항이 작성됐다.
- [x] 사용자 승인 없이 원본 서비스에 병합·배포하지 않았다.

남은 두 항목의 코드 계약과 Mock E2E는 통과했다. 그러나 격리된
`CODEX_HOME`의 실제 CLI smoke test가 HTTP 401을 반환했으므로 실제 인증
Codex가 XML·Kotlin·리소스를 함께 수정하는 acceptance 전에는 완료로 표시하지
않는다. 운영 소스의 기존 Codex 프로필이나 비밀정보를 실험 폴더로 복사하지
않는 안전 규칙을 우선했다.

## 19. 완료 후 처리

- 실험 결과, 테스트 보고서, 성능 수치와 알려진 제한을 사용자에게 보고한다.
- 원본 서비스로 옮길 파일과 API 변경 목록을 별도로 작성한다.
- 운영 통합은 별도 Goal과 사용자 승인 후 수행한다.
- 통합 시에도 현재 운영 소스를 직접 덮어쓰지 않고 단계적 port와 canary 검증을 사용한다.

## 20. 작업 기록

각 작업 회차가 끝날 때 아래 형식으로 추가한다.

```text
날짜/시간:
Phase:
수정 경로:
실행한 검증:
검증 결과:
발견된 문제:
해결 내용:
원본 무변경 검사:
다음 단계:
```

### 2026-08-20 17:55 KST

```text
날짜/시간: 2026-08-20 17:55 KST
Phase: Phase 0 - 격리와 기준선
수정 경로: ui_xml_editor_workspace/, XML_UI_EDITOR_GOAL.md
실행한 검증: 원본/복사본 210개 파일 SHA-256 cmp, symlink 및 제외 대상 탐색, 8000 health, 8100 독립 startup/health, 시작·종료 스크립트 문법 검사
검증 결과: manifest 완전 일치, symlink/DB/APK/키 없음, 원본 8000 정상, 실험 8100 startup 및 health 200
발견된 문제: native_app_data.db가 최초의 이름 기반 DB 제외 목록에 없어서 복사본에 포함됨; 실행 도구 세션이 종료되면 nohup 실험 프로세스도 종료됨
해결 내용: 복사본 DB만 제거하고 모든 *.db 제외로 강화한 뒤 manifest 재생성; API/E2E 검증 시 관리되는 foreground 세션으로 8100 실행 예정
원본 무변경 검사: SOURCE_MANIFEST.sha256과 재계산 결과 동일, 기존 Git 변경 상태 보존, 원본 DB/workspace에 쓰기 없음
다음 단계: 복사 서버의 Revision·권한·DB 계약을 분석하고 읽기 전용 XML API 및 보안 테스트 구현
```

### 2026-08-20 18:10 KST

```text
날짜/시간: 2026-08-20 18:10 KST
Phase: Phase 1 - 읽기 전용 XML API
수정 경로: ui_xml_editor_workspace/core/flutter_apk_server/ui_editor_server.py, server.py, tests/test_ui_editor_api.py, tests/test_host_api_contract.py, docs/API_CONTRACT.md, docs/TEST_REPORT.md
실행한 검증: 신규 XML API 테스트 7개, 복사 서버 전체 unittest 75개
검증 결과: 7/7 및 75/75 통과; XML 원문/SHA/미지원 태그 보존, 관련 리소스 탐색, 소유권 검증, traversal/symlink/DTD/entity 차단 확인
발견된 문제: Python 3.14 테스트 런타임이 기존 SQLite 연결 패턴에 ResourceWarning을 출력함
해결 내용: 기능 실패는 없으며 최종 검증에 운영과 같은 Python 3.11 호환 실행을 추가함
원본 무변경 검사: 구현과 테스트 파일은 모두 ui_xml_editor_workspace 안에만 존재; 원본 8000 프로세스에 signal/재시작 없음
다음 단계: 별도 ui_editor Activity, UiNode XML parser, resource resolver, 편집용 View renderer와 no-op round-trip 구현
```

### 2026-08-20 18:25 KST

```text
날짜/시간: 2026-08-20 18:25 KST
Phase: Phase 2 - 호스트 XML 미리보기
수정 경로: 복사 호스트의 ui_editor 패키지, ApiService.kt, TaskLogDetailActivity.kt, activity_ui_editor.xml, manifest/strings, XML_SUPPORT_MATRIX.md
실행한 검증: :app:compileDebugKotlin, :app:testDebugUnitTest
검증 결과: Kotlin 컴파일 통과, unit test 60/60 통과, exact no-op XML 및 미지원 태그 잠금 검증
발견된 문제: Android XMLConstants에는 ACCESS_EXTERNAL_DTD/SCHEMA 상수 심볼이 없어 최초 컴파일 실패
해결 내용: 동일한 표준 URI 문자열을 사용하고 DTD/entity 사전 차단과 SAX 기능 설정을 유지함
원본 무변경 검사: Gradle 다운로드·캐시는 실험 runtime/build_cache만 사용; 원본 프로젝트 빌드/수정 없음
다음 단계: 선택·이동·크기·속성·추가·삭제·설명·이미지·undo/redo와 draft 상태 보존 구현
```

### 2026-08-20 18:45 KST

```text
날짜/시간: 2026-08-20 18:45 KST
Phase: Phase 3 - 편집 기능
수정 경로: 복사 호스트 ui_editor의 DOM editor/history/draft store/ViewModel/Activity/renderer, activity_ui_editor.xml, strings, unit tests
실행한 검증: :app:testDebugUnitTest 전체 재실행
검증 결과: 69/69 통과; 이동·크기·constraint/margin·추가·복제·삭제·레이어 순서·이미지 참조·undo/redo·draft 복원 검증
발견된 문제: 최초 레이어 테스트가 한 단계 이동 계약과 달리 복제본까지 한 번에 이동한다고 가정함
해결 내용: UI와 구현의 한 단계 이동 계약에 맞춰 전진 1회와 후진 복귀를 검증함
원본 무변경 검사: 이미지/draft/Gradle 산출물은 모두 실험 디렉터리 또는 실험 앱 전용 내부 저장소만 사용하도록 구성
다음 단계: 실험 DB draft 테이블, optimistic locking, 이미지 업로드, 구조 diff, 새 Revision 복사와 Codex/build 연동 구현
```

### 2026-08-20 19:45 KST

```text
날짜/시간: 2026-08-20 19:45 KST
Phase: Phase 4 - Draft 및 Codex 연동
수정 경로: 복사 서버 server.py/ui_editor_server.py/tests, 복사 호스트 ApiService.kt와 ui_editor 패키지, API_CONTRACT.md, TEST_REPORT.md
실행한 검증: UI editor API unittest 12개, 복사 서버 전체 unittest 80개, 원본 source manifest 재검산
검증 결과: 12/12 및 80/80 통과; draft FK/전문 저장, optimistic lock, 이미지 최적화·hash, 새 Revision 복사, 모든 변경의 Codex 경유, 입력·출력·오류 전문 로그 확인
발견된 문제: 관리형 sandbox에서 Gradle file-lock socket 생성이 거부되어 현재 회차의 호스트 재실행이 컴파일 전에 중단됨; 8000 listener는 존재하지만 sandbox curl은 연결 거부됨
해결 내용: Gradle과 health 확인은 권한을 높인 검증으로 재실행하며, 코드·서버 실패와 구분해 기록함
원본 무변경 검사: 원본 핵심 소스 210개 SHA-256 기준선 일치, core symlink 없음, 원본 8000 PID에 signal/재시작 없음
다음 단계: 호스트 재검증 후 격리된 테스트 서명키로 Native lint/release APK와 package/version/signature/덮어 설치 계약 검증
```

### 2026-08-20 21:01 KST

```text
날짜/시간: 2026-08-20 21:01 KST
Phase: Phase 5·6 - APK 및 SM-S908N E2E
수정 경로: ui_xml_editor_workspace/core의 복사 서버·호스트, runtime E2E 산출물, docs/TEST_REPORT.md, docs/PERFORMANCE_REPORT.md, docs/XML_SUPPORT_MATRIX.md, docs/INTEGRATION_HANDOFF.md
실행한 검증: 서버 unittest Python 3.11/3.14 각 80개, 호스트 unit 77개와 lint/debug build, instrumentation 1개, Native lint/release build, APK package/version/v2 signature, rev1/rev4 signer 일치, adb 덮어 설치·실행, 회전·백그라운드·draft 복구, 원본 manifest, 8000 health
검증 결과: 자동화 테스트와 Mock Codex 기반 실기기 E2E 통과; rev4가 기존 앱 위에 설치되고 즉시 실행됨; 로그 화면을 닫지 않아도 running→complete, v4 현재 버전, APK 카드가 자동 갱신됨; 원본 210개 source hash 일치; 기존 8000 health 정상
발견된 문제: Android 런타임 parser가 isXIncludeAware 설정을 거부함; theme/drawable 참조를 색상 검사에서 거부함; NestedScrollView에 두 번째 direct child를 넣을 수 있었음; Task 로그가 열린 동안 새 현재 Revision을 반영하지 않음; 격리 실제 Codex 호출은 HTTP 401
해결 내용: parser 설정을 안전한 runCatching으로 감쌈; theme/drawable allowlist 분리; single-child container의 실제 삽입 부모를 탐색; 현재 버전 추적과 과거 선택 보존 정책 및 3초 lifecycle polling 추가; 실제 Codex 인증 실패는 통합 전 필수 acceptance로 문서화
원본 무변경 검사: scripts/verify-isolation.sh 통과, core symlink 없음, 원본 PID 36658/8000에 signal·재시작·배포 없음, 원본 DB/workspace 쓰기 없음
다음 단계: 격리 CODEX_HOME을 인증한 뒤 MOCK_CODEX=0으로 Kotlin 동작이 필요한 UI 변경 1건을 제출·빌드·설치하여 남은 완료 조건 2개를 검증; 이후에만 별도 승인으로 운영 통합 검토
```

### 2026-08-20 21:41 KST

```text
날짜/시간: 2026-08-20 21:41 KST
Phase: Phase 6 - 실기기 회귀 검증
수정 경로: ui_xml_editor_workspace/core/vibefactory의 UiEditorViewModel.kt, UiEditorActivity.kt, unit/instrumentation test, docs/TEST_REPORT.md, docs/PERFORMANCE_REPORT.md, XML_UI_EDITOR_GOAL.md
실행한 검증: SM-S908N에서 host cold start, XML editor 진입, add/undo/redo, 회전, force-stop 복구, Mock Codex rev_0005 제출, APK v2 서명·package/version 검사, adb 덮어 설치·자동 실행, 10초 logcat, host unit/lint/build, instrumentation, 서버 Python 3.11 unittest, Native lint/release, DB integrity/FK
검증 결과: 회전과 process recreation에서 요소 7개와 경고 3개 유지; rev_0005 48초 성공; 동일 package/version/signer 유지; 새 TextView 실화면 표시; 치명적 예외 없음; 서버 80/80, host 77/77, instrumentation 1/1, host/native lint와 build 모두 통과
발견된 문제: Activity 재생성 시 unresolved resource 경고 수가 3에서 0으로 초기화됨; Gradle connectedDebugAndroidTest가 test APK 설치 전 대기함; 호스트 경유 설치는 Play Protect 앱 검사 화면에 도달함
해결 내용: unresolvedResourceCount를 retained UiEditorSession으로 이동하고 unit/instrumentation assertion 추가; instrumentation APK를 직접 설치·실행하여 tooling 지연과 기능을 분리 검증; 비공개 APK의 Google 전송 가능성이 있는 Play Protect 검사는 사용자 승인 없이 진행하지 않음
원본 무변경 검사: 직전 verify-isolation에서 원본 210개 hash 일치; 구현과 runtime 쓰기는 격리 디렉터리만 사용; 원본 8000 프로세스에 signal·재시작·배포 없음
다음 단계: 격리 CODEX_HOME 로그인 후 MOCK_CODEX=0으로 XML·Kotlin·리소스 동시 수정 acceptance를 실행하고, 성공 시 남은 완료 조건 2개와 Goal 상태를 완료 처리
```

### 2026-08-20 22:11 KST

```text
날짜/시간: 2026-08-20 22:11 KST
Phase: Phase 6 - 실기기 시각 회귀 보완
수정 경로: ui_xml_editor_workspace/core/vibefactory의 UiDocumentEditor.kt, UiEditorActivity.kt, UiDocumentEditorTest.kt, docs/TEST_REPORT.md, docs/PERFORMANCE_REPORT.md, XML_UI_EDITOR_GOAL.md
실행한 검증: SM-S908N에서 팔레트 텍스트 추가, 편집기 미리보기 좌표 확인, 속성 패널 X/Y 변경, Mock Codex rev_0006·rev_0007 생성, APK 덮어 설치·cold launch, 최종 스크린샷 육안 검사, 10초 logcat, host unit/lint/debug/test APK build, instrumentation, v2 서명, DB integrity/FK
검증 결과: 탭 추가 요소가 마지막 ConstraintLayout 자식 아래에 배치됨; X/Y 변경이 기존 축 constraint를 제거하고 parent start/top 기준으로 정규화됨; rev_0007 최종 화면에서 제목·복제 제목·이미지·텍스트·본문이 겹치지 않음; 128ms cold launch와 치명적 예외 없음; host 81/81, lint/build 성공, instrumentation 1/1, DB 무결성 정상
발견된 문제: 팔레트 탭이 모든 새 요소를 16dp/16dp에 추가하여 기존 UI와 겹침; 속성 패널 X/Y가 margin만 바꾸고 bottom/end 등 기존 constraint를 남겨 표시 위치가 입력값과 달라짐
해결 내용: 탭과 드래그 위치 계약 분리, ConstraintLayout 자동 anchor·FrameLayout free vertical 위치 계산 추가, X/Y 변경 시 해당 constraint 축 정규화, 4개 unit test와 실기기 rev_0006·rev_0007 검증 추가
원본 무변경 검사: 모든 수정·빌드·Task·APK·증적은 ui_xml_editor_workspace 내부; 원본 서비스 배포·재시작 없음
다음 단계: 격리 CODEX_HOME 로그인 후 MOCK_CODEX=0 실제 Codex acceptance만 수행
```

### 2026-08-20 22:16 KST

```text
날짜/시간: 2026-08-20 22:16 KST
Phase: 최종 실제 Codex acceptance 사전 확인
수정 경로: ui_xml_editor_workspace/docs/TEST_REPORT.md, XML_UI_EDITOR_GOAL.md
실행한 검증: 격리 CODEX_HOME 로그인 상태, 8000/8100 listener, Goal 완료 조건 재검토
검증 결과: 격리 CODEX_HOME은 Not logged in; 8100 listener 없음; 원본 서버 PID 36658은 8000에서 계속 LISTEN 중
발견된 문제: 실제 Codex acceptance에 필요한 격리 인증이 없어 MOCK_CODEX=0 요청을 실행할 수 없음; sandbox 내부 localhost health 요청과 ADB daemon 시작은 권한 제약으로 실패
해결 내용: 이미 완료한 Mock Codex E2E와 실기기 검증 결과는 유지하고, 인증 없는 실제 Codex 결과를 완료로 간주하지 않음; 테스트 보고서의 assertion 수량 표현을 실제 항목에 맞게 정정
원본 무변경 검사: 원본 서버 PID에 signal·재시작 없음; 원본 소스·DB·workspace 수정 없음; 실험 서버 시작 없음
다음 단계: 격리 CODEX_HOME 로그인 후 MOCK_CODEX=0으로 XML·Kotlin·리소스 동시 수정 acceptance를 실행
```

### 2026-08-21 16:15 KST

```text
날짜/시간: 2026-08-21 16:15 KST
Phase: 실제 Codex 생성 XML의 호스트 미리보기 크래시 회귀 수정
수정 경로: ui_xml_editor_workspace/core/vibefactory의 UiPreviewRenderer.kt와 instrumentation test, docs/TEST_REPORT.md, docs/XML_SUPPORT_MATRIX.md, XML_UI_EDITOR_GOAL.md
실행한 검증: host unit/lint/debug/test APK build, SM-S908N instrumentation 2건, 실제 Codex 생성 노트 rev_0001의 로그 보기→UI 편집 진입, AndroidRuntime logcat, scripts/verify-isolation.sh, 8000/8100 health
검증 결과: Material XML을 포함한 편집 화면이 11개 요소로 정상 렌더링되고 제목/내용 힌트가 보이며 치명적 예외 없음; 최종 build/lint 2분 40초 성공; instrumentation 2/2 통과; 원본 210개 hash 일치; 두 서버 health 정상
발견된 문제: AppCompat 호스트 테마에서 생성 앱의 MaterialButton/TextInputLayout 등을 직접 생성해 MaterialComponents 테마 강제 검사로 크래시 발생
해결 내용: XML 문서와 제출 태그는 보존하고 호스트 미리보기 View만 theme-neutral Android TextView/Button/EditText/LinearLayout/FrameLayout로 재구성; RecyclerView 더미 행과 잠김 placeholder도 동일하게 테마 비종속화
원본 무변경 검사: 모든 코드·빌드·실기기 앱 변경은 ui_xml_editor_workspace에 한정; 원본 서버 PID 86545 및 사용자가 실행한 실험 서버 PID 93370을 종료·재시작하지 않음
다음 단계: 실제 Codex UI 변경 제출 acceptance와 회귀 범위를 계속 검증
```
