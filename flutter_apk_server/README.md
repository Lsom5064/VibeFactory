# Flutter APK Builder Server

Android host app에서 앱 생성 요청을 받아 task별 workspace를 만들고, Codex CLI로 Flutter Android APK 생성을 수행한 뒤, 결과를 조회하거나 APK를 다운로드할 수 있게 하는 최소 FastAPI 서버다.

사용자 식별 기준:

- `phone_number`가 있으면 이를 우선 사용자 식별자로 사용
- `phone_number`가 없으면 `device_id`를 사용자 식별자로 사용
- 이 최소 서버에서는 별도 `user_id`를 공개 계약의 필수 식별자로 쓰지 않는다

## 구성 파일

- `server.py`: FastAPI 서버, SQLite, workspace 준비, background worker, Codex/mock 실행
- `test_server.py`: `MOCK_CODEX=1` 기반 smoke test
- `AGENTS.md`: 이 저장소 작업 지침

## 설치 방법

```bash
cd flutter_apk_server
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
```

## 환경변수 설정

필수 또는 권장 환경변수:

- `BASE_PROJECT_PATH`: task workspace에 복사할 Base Flutter 프로젝트 경로
- `WORKSPACES_ROOT`: 생성된 task workspace 루트 경로
- `CODEX_COMMAND`: Codex CLI 실행 명령 템플릿
- `CODEX_DANGEROUS_BYPASS`: 기본값 `1`. `1`이면 Codex를 `--dangerously-bypass-approvals-and-sandbox`로 실행
- `CODEX_SANDBOX_MODE`: `CODEX_DANGEROUS_BYPASS=0`일 때 사용할 Codex sandbox mode. 기본값 `danger-full-access`
- `INTENT_AGENT_ENABLED`: 기본값은 실제 서버에서 `1`, `MOCK_CODEX=1`일 때는 `0`. `1`이면 후속 요청/확인 메시지 해석에 Codex 기반 intent agent 사용
- `INTENT_AGENT_MODEL`: 최초 명세 판단에 사용할 OpenAI Responses API 모델. 기본값 `gpt-5.4`
- `INTENT_AGENT_TIMEOUT_SECONDS`: intent agent 판단 timeout 초, 기본값 `20`
- `FLUTTER_COMMAND`: 서버가 직접 `flutter pub get`, `flutter analyze`, `flutter build apk`를 실행할 때 사용할 Flutter 명령
- `CODEX_TIMEOUT_SECONDS`: Codex 실행 timeout 초
- `SERVER_BASE_URL`: 다운로드 URL 생성에 사용할 서버 기본 주소
- `MAX_CONCURRENT_CODEX_RUNS`: 동시에 실행할 Codex 작업 수

추가 환경변수:

- `DB_PATH`: SQLite 파일 경로, 기본값 `./tasks.db`
- `MOCK_CODEX=1`: 실제 Codex 대신 가짜 APK와 결과 JSON을 생성
- `APP_RUNTIME_OPENAI_API_KEY`: 생성된 앱이 서버를 통해 호출할 런타임 LLM 전용 OpenAI API key
- `APP_RUNTIME_ENABLED`: 기본값은 `APP_RUNTIME_OPENAI_API_KEY`가 있으면 `1`
- `APP_RUNTIME_MODEL`: 생성 앱 런타임에 사용할 모델. 기본값 `gpt-5.4-mini`
- `APP_RUNTIME_BASE_URL`: 런타임 LLM 호출 endpoint. 기본값 `https://api.openai.com/v1/responses`
- `APP_RUNTIME_SYSTEM_PROMPT`: 생성 앱 런타임 LLM의 기본 시스템 프롬프트
- `APP_RUNTIME_DAILY_REQUEST_LIMIT`: 앱별 일일 호출 횟수 제한
- `APP_RUNTIME_DAILY_TOKEN_LIMIT`: 앱별 일일 총 토큰 제한
- `APP_RUNTIME_MAX_OUTPUT_TOKENS`: 앱별 런타임 응답 최대 출력 토큰
- `APP_RUNTIME_TEMPERATURE`: 앱별 런타임 temperature
- `ADMIN_API_TOKEN`: 설정 시 `/admin/*` 관리 endpoint 호출에 필요한 관리자 토큰

DB 기록:

- `tasks` 테이블은 각 task의 최신 상태와 현재 `package_name`, `app_name`, `apk_url` 같은 최신 필드를 유지한다.
- `tasks` 테이블에는 `input_tokens`, `cached_input_tokens`, `output_tokens`, `reasoning_output_tokens`, `total_tokens`도 함께 저장한다.
- `task_events` 테이블은 사용자 입력, 서버 assistant 응답, 확인 버튼 같은 상호작용, 상태 변경, 패키지명 확정 이벤트를 append-only로 기록한다.
- `task_events`에는 `token_usage_recorded` 이벤트도 남겨서 토큰 사용량 이력을 추적할 수 있다.
- `app_llm_configs` 테이블은 생성 앱별 LLM provider/model/api_key/한도 설정을 저장한다.
- `app_llm_usage` 테이블은 생성 앱이 런타임 LLM endpoint를 호출할 때의 일별 사용량 집계를 위한 사용 기록을 저장한다.
- 따라서 같은 task에 대한 후속 수정 요청과 확인 절차도 DB에서 시간순으로 추적할 수 있다.
- 서버는 OpenAI Responses API 기반 spec clarification agent를 사용해, 추가 명세가 필요하면 먼저 질문하고 충분히 구체화된 뒤에만 build를 시작한다.
- 기존 앱 수정 요청은 원래 앱 task의 workspace가 있을 때만 그 workspace에서 진행한다. 기존 앱을 수정하겠다는 요청이지만 현재 thread에 원래 workspace가 없으면 build를 막고, 기존 앱 대화에서 이어서 요청하도록 안내한다.

예시:

```bash
export BASE_PROJECT_PATH=/absolute/path/to/base_flutter_project
export WORKSPACES_ROOT=/absolute/path/to/flutter_apk_server/workspaces
export CODEX_DANGEROUS_BYPASS=1
export INTENT_AGENT_ENABLED=1
export INTENT_AGENT_MODEL=gpt-5.4
export FLUTTER_COMMAND=/absolute/path/to/flutter/bin/flutter
export CODEX_TIMEOUT_SECONDS=1800
export SERVER_BASE_URL=http://127.0.0.1:8000
export MAX_CONCURRENT_CODEX_RUNS=1
export APP_RUNTIME_OPENAI_API_KEY=sk-...
export APP_RUNTIME_MODEL=gpt-5.4-mini
export APP_RUNTIME_DAILY_REQUEST_LIMIT=100
export APP_RUNTIME_DAILY_TOKEN_LIMIT=50000
export MOCK_CODEX=1
```

생성 앱용 LLM runtime endpoint:

- `GET /apps/{task_id}/llm-config`
  - 소유자 확인용 설정 조회
- `POST /apps/{task_id}/llm-config`
  - 소유자 확인용 설정 저장/수정
- `POST /apps/{task_id}/llm/respond`
  - 생성된 앱이 실제 LLM 기능을 호출하는 공통 runtime endpoint
  - 요청 본문에 `package_name`, `user_message`, 선택적으로 `context`, `image_base64`, `image_mime_type`를 넣는다

관리 endpoint:

- `GET /admin/app-llm-defaults`
  - 현재 서버 전역 기본 LLM 설정 조회
- `POST /admin/app-llm-defaults`
  - 전역 기본 LLM 설정 저장
  - `apply_to_existing_tasks=true`면 기존 모든 task의 `app_llm_configs`도 같은 값으로 일괄 갱신
  - `ADMIN_API_TOKEN`이 설정된 경우 `X-Admin-Token` 헤더가 필요

## 서버 실행

```bash
uvicorn server:app --host 127.0.0.1 --port 8000
```

주의:

- 이 예제는 인증이 없다.
- localhost 또는 private network 용도로만 두는 것이 안전하다.
- 외부 공개 시 인증, TLS, 요청 제한, 다운로드 권한 검증이 추가로 필요하다.

## Flutter / Android SDK 필요 조건

실제 빌드를 하려면 다음이 서버 실행 환경에 설치되어 있어야 한다.

- Flutter SDK
- Android SDK
- JDK
- `flutter doctor`가 Android 빌드 가능 상태여야 함
- Base Flutter 프로젝트가 `flutter pub get`과 `flutter build apk`를 수행할 수 있어야 함

## Codex CLI 필요 조건

실제 Codex 실행 모드에서는 다음이 필요하다.

- `codex` CLI가 PATH에 있어야 함
- 서버 프로세스가 Codex CLI를 subprocess로 실행할 수 있어야 함
- Codex가 workspace 내부 `AGENTS.md`와 `prompt.md`를 읽고 `project/` 안에서 작업할 수 있어야 함

기본 명령 템플릿:

```bash
codex exec --skip-git-repo-check --json --dangerously-bypass-approvals-and-sandbox "{prompt}"
```

명령 템플릿에서는 `{prompt}`, `{task_id}`, `{workspace}`, `{project}` 플레이스홀더를 사용할 수 있다.

권한 관련 참고:

- 기본 설정은 비대화형 빌드 서버에서 Codex가 승인 대기 없이 Flutter/Gradle 명령을 실행할 수 있도록 `--dangerously-bypass-approvals-and-sandbox`를 사용한다.
- 서버가 이미 VM, 컨테이너, 별도 계정 같은 외부 격리 환경 안에서 돌고 있을 때만 이 기본값을 유지하는 것이 안전하다.
- 더 제한적으로 운용하려면 `CODEX_DANGEROUS_BYPASS=0`과 함께 `CODEX_SANDBOX_MODE=workspace-write` 또는 `danger-full-access`를 지정하거나, `CODEX_COMMAND`를 직접 오버라이드하면 된다.
- 후속 수정 요청에서 사용자가 `네, 진행해줘`처럼 확인만 보냈을 때는 intent agent가 직전의 확인 대기 명세를 복원해서 빌드 입력으로 사용한다. 따라서 호스트 앱이 확인 문구만 재전송해도 서버는 가능한 한 원래 수정 명세를 유지한다.
- intent agent는 명세가 충분히 구체적일 때만 build를 시작한다. 핵심 요구사항이 모호하면 먼저 1-3개의 질문으로 멈추고, 사용자의 답변이 들어온 뒤에만 build로 넘어간다.
- 명세 보강용 spec clarification agent는 새 앱 생성과 기존 앱 수정을 구분한다. 기존 앱 수정처럼 보이지만 현재 task에 기존 workspace가 없으면 새 앱으로 오인해 빌드하지 않는다.

실행 흐름:

- 서버는 먼저 Codex CLI로 workspace 안의 `project/` 수정을 시도한다.
- Codex가 `.codex_result/task_result.json`을 남기면 서버가 이를 검증한다.
- Codex가 결과 JSON을 남기지 못해도 서버는 `flutter pub get`, `flutter analyze`, `flutter build apk --debug`를 직접 실행해 최종 성공/실패를 확정한다.
- 진행 중에는 `logs/codex_stdout.log`, `logs/codex_stderr.log`, `logs/build.log` 내용이 `/status/{task_id}` 응답에 반영된다.
- 기본 Codex 명령은 `--json`을 사용하므로 `logs/codex_stdout.log`에는 JSONL 이벤트가 기록되고, 서버는 여기서 입력/출력/캐시 토큰 사용량을 파싱해 DB에 저장한다.

## Workspace 구조

```text
workspaces/
  user_{user_id}/
    task_{task_id}/
      current -> revisions/rev_0002/project
      project -> revisions/rev_0002/project
      revisions/
        rev_0001/
          project/
        rev_0002/
          project/
      logs/
        codex_stdout.log
        codex_stderr.log
        build.log
      .codex_result/
        task_result.json
      prompt.md
      AGENTS.md
```

## API 예시

### `POST /generate`

새 앱 생성:

```bash
curl -X POST http://127.0.0.1:8000/generate \
  -H 'Content-Type: application/json' \
  -d '{
    "device_id": "device_1",
    "phone_number": "01012345678",
    "prompt": "할 일 목록 앱을 만들어줘"
  }'
```

기존 채팅방(task)에 대한 후속 요청:

```bash
curl -X POST http://127.0.0.1:8000/generate \
  -H 'Content-Type: application/json' \
  -d '{
    "task_id": "TASK_ID",
    "device_id": "device_1",
    "phone_number": "01012345678",
    "prompt": "검색 기능을 추가해줘"
  }'
```

예상 응답:

```json
{
  "task_id": "3cc9d8b9348c4af9b0b3e9d0a8448f32",
  "status": "Queued",
  "message": "앱 생성을 시작했어요."
}
```

### `GET /status/{task_id}`

```bash
curl 'http://127.0.0.1:8000/status/TASK_ID?device_id=device_1&phone_number=01012345678'
```

### `GET /tasks`

```bash
curl 'http://127.0.0.1:8000/tasks?device_id=device_1'
```

또는:

```bash
curl 'http://127.0.0.1:8000/tasks?phone_number=01012345678'
```

### `GET /download/{task_id}`

```bash
curl -OJ 'http://127.0.0.1:8000/download/TASK_ID?device_id=device_1&phone_number=01012345678'
```

## 최소 API 철학

이 프로젝트는 이전 멀티 에이전트 서버의 세분화된 endpoint 구조를 그대로 복제하지 않는다.

- `/generate`: 새 채팅이면 새 task를 만들고, 기존 채팅이면 같은 task에 후속 요청을 넣는다
- `/status/{task_id}`: 현재 task 진행 상태 조회
- `/tasks`: 사용자의 task 목록 조회
- `/download/{task_id}`: 성공한 APK 다운로드

즉, 같은 채팅방 안의 후속 요청은 기존 `task_id`를 유지한 채 `/generate`로 다시 보낸다. 예전 `/generate/continue`, `/refine`, `/retry`, `/feedback/route` 같은 분리는 이 최소 서버의 1차 목표가 아니다.

## `task_result.json` 계약

Codex는 stdout을 최종 계약으로 사용하면 안 된다. 서버는 반드시 `.codex_result/task_result.json`을 읽고 검증한다.

성공 예시:

```json
{
  "status": "success",
  "task_id": "TASK_ID",
  "app_name": "Todo App",
  "package_name": "com.example.todoapp",
  "apk_path": "project/build/app/outputs/flutter-apk/app-debug.apk",
  "message": "APK build completed",
  "build_log_path": "logs/build.log"
}
```

실패 예시:

```json
{
  "status": "failed",
  "task_id": "TASK_ID",
  "error_stage": "analyze",
  "message": "패키지 이름 충돌로 빌드에 실패했습니다.",
  "build_log_path": "logs/build.log"
}
```

서버 검증 규칙:

- `status == "success"` 이어야 성공 처리
- `apk_path`가 존재해야 함
- `apk_path`는 해당 task workspace 내부 경로여야 함
- 확장자는 `.apk`여야 함
- 파일 크기가 0보다 커야 함
- `task_result.json`이 없거나 파싱 실패면 실패 처리
- Codex timeout이면 실패 처리

## 검증 방법

문법 검사:

```bash
python3 -m py_compile server.py
```
