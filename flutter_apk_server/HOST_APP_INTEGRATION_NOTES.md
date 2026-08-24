# Host App Integration Notes

이 문서는 현재 Native Android 서비스의 서버-호스트 계약을 요약한다. 구현을
변경할 때는 저장소 상대경로의 다음 파일과 계약 테스트를 함께 검토한다.

- `vibefactory/app/src/main/java/kr/ac/kangwon/hai/vibefactory/ApiService.kt`
- `vibefactory/app/src/main/java/kr/ac/kangwon/hai/vibefactory/ApiModels.kt`
- `vibefactory/app/src/main/java/kr/ac/kangwon/hai/vibefactory/HostAppConfig.kt`
- `flutter_apk_server/api_models.py`
- `flutter_apk_server/tests/test_host_api_contract.py`

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
- 취소·분기·리비전: `/tasks/{task_id}/cancel`, `/tasks/{task_id}/branch`,
  `/tasks/{task_id}/revisions`
- APK: `/download/{task_id}`
- 런타임: `/tasks/{task_id}/runtime-error`, `/apps/{task_id}/llm`,
  `/apps/{task_id}/data/{collection}`
- XML UI 편집: `/tasks/{task_id}/ui/editor-context`와 revision 하위의
  layout·draft·image API

## 변경 원칙

- 필드 추가는 기본값이 있는 nullable 필드로 시작해 이전 호스트와의 호환성을 유지한다.
- 필드 삭제·이름 변경·상태 문자열 변경은 서버와 호스트를 동시에 수정하고 계약 테스트를 추가한다.
- `/status` polling은 선택된 채팅방을 바꾸거나 작성 중인 초안·스크롤을 초기화하면 안 된다.
- 런타임 오류와 app data 요청의 `package_name`은 Task에 저장된 패키지와 일치해야 한다.
- 내부 workspace 경로, 명령줄, 인증정보, 전화번호와 device ID를 사용자 화면이나
  일반 Android 로그에 노출하지 않는다.
