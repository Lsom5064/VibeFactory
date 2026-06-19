# Host App Integration Notes

검토 대상:
- `/Users/hai/AndroidStudioProjects/vibefactory/AGENTS.md`
- `/Users/hai/AndroidStudioProjects/vibefactory/app/src/main/java/kr/ac/kangwon/hai/vibefactory/ApiService.kt`
- `/Users/hai/AndroidStudioProjects/vibefactory/app/src/main/java/kr/ac/kangwon/hai/vibefactory/HostAppConfig.kt`
- `/Users/hai/AndroidStudioProjects/vibefactory/app/src/main/java/kr/ac/kangwon/hai/vibefactory/MainActivity.kt`
- `/Users/hai/AndroidStudioProjects/vibefactory/app/src/main/java/kr/ac/kangwon/hai/vibefactory/BuildMonitorService.kt`

핵심 참고사항:

- 호스트 앱은 `task` 중심 API를 사용한다.
- 이번 최소 서버가 실제로 맞춰야 하는 필수 엔드포인트는 우선 `/generate`, `/status/{task_id}`, `/tasks`, `/download/{task_id}`다.
- 같은 채팅방의 후속 요청은 새 task가 아니라 기존 `task_id`에 대한 후속 요청이어야 한다.
- 따라서 최소 서버에서는 `POST /generate`가 `task_id` optional 입력을 받아 새 task 생성과 기존 task 후속 요청을 모두 처리한다.
- 호스트 앱은 `/generate` 요청에 `device_info`, `interview_consent`, `reference_image_*` 같은 추가 필드를 실어 보낸다.
- 사용자 식별은 `phone_number` 우선, 없으면 `device_id` 기준으로 보는 것이 맞다.
- 호스트 앱은 `user_id`를 항상 보내지 않는다. 따라서 서버는 `user_id` 없이도 동작해야 한다.
- 호스트 앱은 `/tasks` 호출 시 현재 `user_id = null`, `device_id = <stored_device_id>`, `phone_number = <stored_phone>` 패턴을 사용한다.
- 호스트 앱은 `/download/{task_id}`에 `device_id`, `user_id`, `phone_number` query를 붙여도 호출한다.
- 호스트 앱은 `/status/{task_id}` 응답에서 최소한 `task_id`, `status`, `apk_url`, `app_name|generated_app_name`, `package_name`, `log|full_log|log_lines`, `status_display_text`, `status_message`, `build_success`를 기대하는 쪽으로 작성되어 있다.
- 호스트 앱은 `/tasks` 응답에서 `initial_user_prompt`, `app_name|generated_app_name`, `package_name`, `apk_url`, `status_display_text`, `created_at`, `updated_at`를 쓰는 경로가 있다.

이번 서버에서 반영한 호환 포인트:

- `/generate`는 `phone_number`가 있으면 이를 기준으로, 없으면 `device_id`를 기준으로 내부 소유자 식별값을 만든다.
- `/tasks`는 `phone_number` 우선, 없으면 `device_id` 기준으로 조회할 수 있다.
- `/status/{task_id}`와 `/download/{task_id}`도 `phone_number` 또는 `device_id`로 접근 검사를 수행한다.
- `/status/{task_id}`는 최소 서버 내부 모델에서 호스트 앱이 소비하는 보조 필드들을 함께 내려준다.
- `/tasks`는 호스트 앱 DTO에 맞는 보조 필드(`initial_user_prompt`, `generated_app_name`, `build_success`)를 함께 내려준다.

정리된 판단:

- 예전 `/generate/continue`, `/refine`, `/retry`, `/feedback/route` 분리는 기존 멀티 에이전트 서버 구조에서 나온 것이다.
- 이번 최소 서버는 그 분리를 없애되, 기존 task에 대한 후속 요청 자체는 유지한다.
- 정리하면 "새 채팅방이면 새 task", "기존 채팅방이면 같은 task에 대해 `/generate`로 후속 요청"이 현재 계약이다.
