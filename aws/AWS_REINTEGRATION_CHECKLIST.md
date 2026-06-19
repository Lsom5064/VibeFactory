# AWS Re-integration Checklist

서버 코드와 Android 호스트 앱 코드가 크게 바뀐 뒤, AWS 연동을 다시 진행하기 위한 작업 목록이다.

현재 `aws/README.md`, `bootstrap-ec2.sh`, `rsync-to-ec2.sh`, systemd/Nginx/env 템플릿은 이전 서버 계약 기준으로 만들어졌다. 재연동 전에는 그대로 실행하지 말고 아래 항목을 먼저 검증한다.

## 현재 보류 상태

- AWS 배포/자동 백업/정리 작업은 보류한다.
- 로컬 서버와 호스트 앱의 최신 API 계약을 먼저 확정한다.
- AWS의 기존 EC2, NAS 연결, Tailscale 연결 정보는 참고만 하고, 최신 코드 기준으로 재검증한다.

## 재개 전 필수 확인

- 서버 실행 진입점 확인
  - FastAPI 앱 객체 이름이 여전히 `server:app`인지 확인한다.
  - 실행 디렉터리가 여전히 `flutter_apk_server/`인지 확인한다.
  - 필요한 Python 의존성이 `requirements.txt`에 모두 반영되어 있는지 확인한다.

- 환경변수 확인
  - `BASE_PROJECT_PATH`
  - `WORKSPACES_ROOT`
  - `DB_PATH`
  - `SERVER_BASE_URL`
  - `CODEX_COMMAND`
  - `FLUTTER_COMMAND`
  - `MAX_CONCURRENT_CODEX_RUNS`
  - OpenAI/Codex 관련 key와 인증 방식
  - 새로 추가되거나 이름이 바뀐 환경변수

- API 계약 확인
  - `/health`
  - `/generate`
  - `/status/{task_id}`
  - `/download/{task_id}`
  - `/tasks`
  - runtime error reporting endpoint
  - generated app runtime LLM endpoint
  - admin endpoint
  - 요청/응답 JSON 필드가 Android 앱의 Retrofit 모델과 일치하는지 확인한다.

- DB schema 확인
  - `tasks`
  - `task_events`
  - `task_project_snapshots`
  - app runtime LLM 관련 테이블
  - 새 migration 또는 새 테이블이 생겼는지 확인한다.
  - AWS 기존 DB를 재사용할지, 새 DB로 시작할지 결정한다.

- workspace 구조 확인
  - task root 구조가 이전과 같은지 확인한다.
  - `revisions/rev_xxxx/project` 구조가 유지되는지 확인한다.
  - `project`/`current` symlink 또는 대체 구조가 바뀌었는지 확인한다.
  - APK 산출물 경로가 여전히 `project/build/app/outputs/flutter-apk/app-debug.apk`인지 확인한다.
  - 로그 경로가 여전히 `logs/`인지 확인한다.

- Android 호스트 앱 확인
  - 서버 base URL 설정 위치가 여전히 `HostAppConfig.BASE_URL`인지 확인한다.
  - HTTP를 계속 쓸지, HTTPS/domain으로 갈지 결정한다.
  - cleartext traffic 설정이 필요한지 확인한다.
  - 다운로드 URL 조합 로직이 absolute URL과 relative URL을 모두 안전하게 처리하는지 확인한다.

## AWS 재배포 체크리스트

1. 로컬에서 최신 서버 smoke test를 통과시킨다.
   - `MOCK_CODEX=1`로 `/health`, `/generate`, `/status`, `/download` 흐름 확인
   - 실제 Codex/Flutter 빌드는 별도 단계로 확인

2. AWS 배포 파일을 최신 코드에 맞게 수정한다.
   - `aws/vibefactory-server.env.example`
   - `aws/vibefactory-server.service`
   - `aws/nginx-vibefactory.conf`
   - `aws/bootstrap-ec2.sh`
   - `aws/rsync-to-ec2.sh`

3. AWS로 복사하기 전 제외 목록을 재검토한다.
   - 반드시 제외: `aws/*.pem`, secret env, local runtime DB, local workspaces
   - 유지 필요: 최신 source, template project, deployment scripts
   - build/cache 제외: `build/`, `.dart_tool/`, `.gradle/`, `.tooling/`

4. AWS에서 mock mode로 먼저 실행한다.
   - systemd 시작
   - Nginx reverse proxy 확인
   - `curl http://SERVER/health`
   - mock `/generate` 확인

5. 실제 빌드 모드 전환 전 toolchain을 확인한다.
   - `codex --version`
   - `codex login` 또는 현재 인증 방식
   - `/opt/flutter/bin/flutter doctor -v`
   - Android SDK/platform/build-tools/license
   - JDK 버전

6. 실제 빌드 테스트를 제한적으로 수행한다.
   - 새 앱 1개 생성
   - 상태 polling
   - APK 다운로드
   - 생성 앱 설치
   - 수정 요청 1회
   - 실패 task 로그 확인

7. Android 호스트 앱을 AWS URL로 다시 빌드한다.
   - base URL 변경
   - 네트워크 보안 설정 확인
   - 실제 기기에서 generate/status/download 흐름 확인

## NAS 백업/정리 재검토 항목

NAS archive/restore는 AWS 재연동 이후 별도 단계로 구현한다.

- NAS 연결 정보
  - NAS Tailscale IP: `100.66.226.106`
  - EC2 Tailscale IP는 재시작/재가입 후 다시 확인한다.
  - SSH user: 현재 테스트는 `hailab`
  - SSH key: `/home/ubuntu/.ssh/vf_nas_ed25519`
  - rsync path: `/usr/bin/rsync`

- rsync 기본 형태

```bash
rsync -az --progress \
  --rsync-path=/usr/bin/rsync \
  -e "ssh -i /home/ubuntu/.ssh/vf_nas_ed25519 -o IdentitiesOnly=yes -o BatchMode=yes -p 22" \
  SOURCE/ \
  hailab@100.66.226.106:/volume1/vibefactory-archive/DEST/
```

- 백업에서 제외할 캐시 후보
  - `build/`
  - `.tooling/`
  - `.gradle/`
  - `.dart_tool/`
  - `ephemeral/`
  - `.plugin_symlinks/`
  - `.cxx/`
  - `*.zip`

- 백업에 반드시 포함할 후보
  - task metadata
  - prompt
  - logs
  - `.codex_result`
  - reference images
  - revision source
  - APK artifact
  - archive manifest/checksum

## Archive/Restore 설계 요구사항

오래된 revision을 AWS에서 삭제하기 전, restore 설계가 필요하다.

- APK는 `build/` 밖의 stable artifact 경로로 복사해야 한다.
  - 예: `task_xxx/artifacts/rev_0003/app-debug.apk`
  - DB `apk_path`도 artifact 경로를 보게 해야 한다.

- revision archive manifest가 필요하다.
  - `task_id`
  - `revision_label`
  - `source`
  - `archive_status`
  - `archive_path`
  - `apk_path`
  - `apk_sha256`
  - `created_at`
  - `archived_at`

- rollback은 과거 revision을 직접 current로 바꾸지 않는다.
  - NAS에서 과거 revision을 복원한다.
  - 복원한 revision을 새 revision으로 복사한다.
  - 예: `rev_0008 = rev_0001`
  - DB와 symlink는 새 revision을 가리키게 한다.

- Codex가 과거 버전 비교를 해야 할 때는 대상 revision을 실행 전 복원해야 한다.

## 주의사항

- 기존 `aws/` 스크립트는 최신 코드 기준으로 검증되기 전까지 그대로 운영 반영하지 않는다.
- `tasks.db`와 `workspaces/`는 운영 데이터다. 삭제/덮어쓰기 전에 백업한다.
- SQLite DB는 파일 복사보다 `.backup` 명령을 사용한다.
- `Running`, `Queued` 작업은 백업 정리 대상에서 제외한다.
- NAS 업로드 검증 전 AWS 파일을 삭제하지 않는다.
- `rsync --delete`는 초기 구현에서 사용하지 않는다.
- `android/gradle/wrapper`와 `.gradle/`을 혼동하지 않는다. 전자는 소스, 후자는 캐시다.
- 전화번호가 path 또는 DB에 포함될 수 있으므로 NAS archive도 개인정보 데이터로 취급한다.
- AWS public IP는 stop/start 후 바뀔 수 있다. Elastic IP 또는 domain 사용 여부를 결정한다.
- HTTPS를 적용하지 않은 HTTP 운영은 테스트/폐쇄망 용도로만 사용한다.
- OpenAI/Codex key와 NAS SSH key는 Git에 절대 올리지 않는다.

## 재개 시 첫 명령

최신 코드 상태 확인:

```bash
git status --short
rg -n "FastAPI|uvicorn|SERVER_BASE_URL|WORKSPACES_ROOT|DB_PATH|/generate|/status|/download" flutter_apk_server vibefactory
```

AWS 서버 상태 확인:

```bash
curl http://15.165.191.202/health
ssh -i aws/vibeFactory.pem ubuntu@15.165.191.202 \
  'sudo systemctl status vibefactory-server --no-pager'
```

NAS 연결 확인:

```bash
ssh -i /home/ubuntu/.ssh/vf_nas_ed25519 \
  -o IdentitiesOnly=yes -o BatchMode=yes -p 22 \
  hailab@100.66.226.106 'echo ok'
```

## 보류 중 결정

- AWS는 1대 서버로 유지할지, 20명 단위 group 서버로 나눌지
- HTTP를 계속 쓸지, domain + HTTPS로 갈지
- AWS에는 최신 revision만 둘지, 최신+직전 성공 revision까지 둘지
- NAS archive 보관 기간
- 오래된 task download UX
- 오래된 revision restore를 자동화할지, admin 수동 복구로 시작할지
