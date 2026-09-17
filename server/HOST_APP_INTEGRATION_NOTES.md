# Host App Integration Notes

이 문서는 현재 Native Android 서비스의 서버-호스트 계약을 요약한다. 구현을
변경할 때는 저장소 상대경로의 다음 파일과 계약 테스트를 함께 검토한다.

- `vibefactory/app/src/main/java/kr/ac/kangwon/hai/vibefactory/ApiService.kt`
- `vibefactory/app/src/main/java/kr/ac/kangwon/hai/vibefactory/ApiModels.kt`
- `vibefactory/app/src/main/java/kr/ac/kangwon/hai/vibefactory/HostAppConfig.kt`
- `server/api_models.py`
- `server/tests/test_host_api_contract.py`

## Task 계약

- 새 채팅방은 `task_id` 없이 `POST /generate`를 호출해 새 Task를 만든다.
- 기존 채팅방의 수정·질문·오류 복구는 같은 `task_id`로 `POST /generate`를 호출한다.
- 서버는 `phone_number`를 우선 식별자로 사용하고, 없으면 `device_id`를 사용한다.
- 조회·다운로드·수정·취소·분기·UI 편집 API는 모두 Task 소유권을 확인한다.
- `task_id`, `package_name`, revision, APK 경로와 타임라인 이벤트는 서버가
  canonical source이며 호스트의 로컬 캐시는 화면 복구와 낙관적 표시 용도다.

## 주요 엔드포인트

- 생성·수정: `/generate`
- 목록·상태: `/tasks`, `/status/{task_id}`
- 취소·분기·리비전: `/tasks/{task_id}/cancel`, `/tasks/{task_id}/revisions/{revision_label}/branch`,
  `/tasks/{task_id}/revisions`
- APK: `/download/{task_id}`
- 런타임: `/tasks/{task_id}/runtime-error`, `/apps/{task_id}/llm/respond`,
  `/apps/{task_id}/data/{collection}`
- XML UI 편집: `/tasks/{task_id}/ui/editor-context`와 revision 하위의
  layout·draft·image API

## 현재 UI 시각 수정 흐름

- 채팅 입력부의 `UI 수정하기`는 `ui_editor/UiAnnotationEditorActivity.kt`로 진입한다.
- 원본 XML 미리보기 위에 삭제·이동·모양·기능 변경·추가 표시를 올리고,
  별도 `annotation_xml`에 대상·목적지·설명·첨부 이미지 연결 정보를 보존한다.
- 이동의 파란 점선은 이동할 원본 UI의 너비·높이로 그리며 화살표 끝이 사각형의 중심이다.
  드래그 중과 저장 후 모두 동일하게 표시한다. 목적지의 기존 UI 크기는 사용하지 않으며,
  화면 경계에서는 중심이나 크기를 보정하지 않고 화면 밖 부분만 잘라 표시한다.
- 추가(초록 ＋)를 빈 공간이나 기존 UI 위에 놓고 영역을 이동·확대/축소한 뒤 스케치와 설명을 입력한다.
  배경색 덮기는 그리기 보조 표시이며, 사용자가 `이 영역의 기존 UI 교체`를 선택한 경우에만
  영역 안에 완전히 포함된 명시적 대상들을 교체한다. 기본값은 기존 UI와 기능 유지다.
- `schemaVersion=1`의 `action="add"`는 기존 부모/주변 요소인 `target`과 `addition`을 함께 저장한다.
  `addition`의 화면 기준 0–1 bounds, ARGB 배경색, 영역 내부 기준 0–1 stroke/point,
  선택적 `replace-target`을 서버가 검증한다. 스케치는 벡터와 참고 이미지로 함께 전달하며
  실제 Views/XML 및 Kotlin 동작으로 구현한다. API DTO와 기존 세 동작의 형식은 유지한다.
- 새 호스트는 `schemaVersion=3`를 작성하며 서버는 기존 v1/v2도 읽는다. `behavior` 식별자는 유지하고
  모양·기능 변경을 뜻한다. v2/v3의 behavior/add는 설명이 필수이며, 기존 v1 그림만 있는 add는 읽을 수 있다.
- behavior의 선택적 `<vf:sketch backgroundColor="#AARRGGBB">`에는 선택 요소 원본 위에 그린
  stroke/point를 저장한다. 좌표는 target 영역 내부 0–1이며, 확대·스크롤 배율은 전송하지 않는다.
- v2/v3의 `<vf:image-ref id="..." role="reference|sketch"/>`는 참고 이미지 최대 5장과 스케치 1장을
  구분한다. 스케치 이미지에는 대응하는 벡터 선 또는 v3 캔버스 이미지가 있어야 하며 기존 이미지 소유권 검증을 유지한다.
  호스트 저장 시 참고 이미지 ID를 유지하면서 스케치 참조만 교체한다.
- 두 도구는 같은 그리기·첨부 편집기를 사용한다. 모양·기능 변경은 원본 위에 덧그리며 추가는
  배경색 위에 그린다. 한 손가락은 그리기/지우기, 두 손가락은 1–4배 확대/이동이다.
  지우개·확대는 원본을 변경하지 않으며, 서버는 스타일만 요청한 경우 기존 동작을 보존한다.
- 선택적 `canvasWidthDp`/`canvasHeightDp`는 미리보기의 기준 크기를 보존한다. 서버는 이를 bounds와
  곱한 `region_dp`도 전달해 추가 묶음이 부모의 빈 공간 전체로 확대되는 것을 방지한다.
  터치 최소 크기 보정 외에는 선택 영역과 도형별 상대 크기·간격을 유지하도록 생성에 지시한다.
- 적용된 표시를 다시 누르면 `이 표시를 지울까요?` 확인창에서 취소·수정·표시 삭제를 선택할 수 있다.
  겹친 표시는 먼저 대상을 고르며, 삭제한 표시는 되돌리기로 복원한다. 스크롤은 삭제를 실행하지 않는다.
- 저장 버튼은 draft를 저장하고 `/ui/drafts/{draft_id}/confirm`으로 확정한다.
  저장만으로 앱 빌드를 시작하지 않는다.
- 사용자가 `수정한 UI를 토대로 수정`을 선택해 채팅을 전송하면
  `/generate`의 `use_ui_editor_draft=true`를 통해 저장한 표시를 Codex 후속 작업에 전달한다.
- 화면 이름과 요소 설명의 계약은 [UI_CATALOG.md](UI_CATALOG.md)를 따른다.
  이전 XML 직접 편집 화면이나 draft `submit` API의 존재만으로 현재 사용자 흐름을 판단하지 않는다.

## 변경 원칙

- 필드 추가는 기본값이 있는 nullable 필드로 시작해 이전 호스트와의 호환성을 유지한다.
- 필드 삭제·이름 변경·상태 문자열 변경은 서버와 호스트를 동시에 수정하고 계약 테스트를 추가한다.
- `/status` polling은 선택된 채팅방을 바꾸거나 작성 중인 초안·스크롤을 초기화하면 안 된다.
- 런타임 오류와 app data 요청의 `package_name`은 Task에 저장된 패키지와 일치해야 한다.
- 내부 workspace 경로, 명령줄, 인증정보, 전화번호와 device ID를 사용자 화면이나
  일반 Android 로그에 노출하지 않는다.

## 사전조건 선택과 설정 확인

사전조건의 `resolution`은 구현 작업(`implementation`), 사용자 선택(`clarification`), 외부 설정(`external_setup`), 안내(`advisory`)를 구분한다.
호스트는 기존 응답의 `interaction_type`, `requires_user_input`, `confirmation_action`을 따라 표시한다.
사용자 선택이나 현재 지원 경로가 없는 연결은 `needs_clarification`으로 설명과 질문을 보여주며 등록 재확인 버튼을 표시하지 않는다.
서버가 설정을 확인할 수 있는 외부 연동은 기존 `recheck_prebuild_requirements` 흐름을 사용한다.
구현 작업 때문에 별도의 준비사항 승인 화면을 추가하지 않는다.
사용 가능한 기기 센서와 권한 요청 구현도 구현 작업에 포함한다. 단순 사용 안내와 이미 준비된 연동은
추가 확인 버튼 없이 최종 프롬프트로 진행한다. 선택이 필요하면 구체적인 질문과 채팅 답변 방법을 표시한다.

`prebuild_requirements=[]`는 현재 요청에 조건이 없다는 의미다. 호스트 캐시에서도 과거의 비어 있지 않은 목록으로 대체하지 않는다.
최종 프롬프트 편집 후 전송은 조건 재검토 때문에 생성 대신 질문·등록 안내·프롬프트 재전송 안내를 반환할 수 있다.
이 경우 서버 응답 상태를 표시하고 사용자가 전송한 최종 문구를 유지한다. 이 변경은 호스트 DTO에 필수 필드를 추가하지 않는다.

### 캔버스 이미지와 도구 모음 (2026-09-17)

- 새 저장은 schemaVersion=3이며 v1/v2 읽기와 서버 검증을 유지한다.
- `<vf:image-layer imageId="..." left="..." top="..." right="..." bottom="..."/>`는 해당 요소/추가 영역 내부 0~1 좌표다. 연결된 image-ref는 reference 역할이며 최대 5개, 고유 ID, 양수 크기, 영역 내부 좌표를 검증한다.
- 이미지 순서대로 합성한 뒤 펜 선을 위에 그린다. 합성 JPG는 sketch 역할, 개별 이미지 파일은 reference 역할로 보관한다. 이미지뿐인 캔버스도 설명과 함께 저장할 수 있다.
- 기본 배치 이미지는 생성 앱에서 해당 원본 파일을 실제 이미지 요소로 사용한다. 사용자가 설명에 참고용이라고 명시한 경우 참고 자료로 해석한다.
- 편집기는 이미지 이동·비율을 유지하는 크기 조절·삭제·undo/redo를 지원한다. 긴 누르기로 재선택하고 펜 선택으로 그리기에 돌아간다.
- 한 행의 펜·지우개·undo·redo·이미지 추가·원본 보기 아이콘과 별도의 전체 보기 버튼을 사용한다. 선택된 펜을 재선택하면 굵기를 선택한다.
