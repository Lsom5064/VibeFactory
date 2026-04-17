# Agentic Loop 수정사항

eng review + 테스트 결과 기반. 우선순위순.

---

## P0 — fetch_http SSRF 취약점

`execute_tool`의 `fetch_http`가 LLM이 요청한 임의 URL을 그대로 요청함. private IP 차단 없음.
LLM에게 `http://169.254.169.254/latest/meta-data/` 요청하게 유도하면 AWS IAM 크레덴셜 탈취 가능.
`http://localhost:8000/`으로 내부 서비스 스캔도 가능.

**해결:** fetch_http 핸들러에 private IP 차단 추가:
```python
from ipaddress import ip_address
from urllib.parse import urlparse

def is_private_url(url):
    host = urlparse(url).hostname
    try:
        return ip_address(host).is_private
    except ValueError:
        return host in ("localhost", "metadata.google.internal")
```
fetch_http 시작 부분에 `if is_private_url(url): return {"status": "error", "message": "private URL blocked"}` 추가.

---

## P0 — .env gitignore 누락 + 하드코딩 경로

1. `.gitignore`에 `.env` 없음. 실수로 커밋하면 API 키 유출.
   **해결:** `.gitignore`에 `.env` 추가.

2. `vibe_factory.py:28-29` fallback 경로가 `/Users/hai/Desktop/...`. 환경변수 없으면 다른 사용자 홈 접근.
   **해결:** fallback 제거, 환경변수 필수로 (`VIBE_BASE_PROJECT` 없으면 raise).

---

## P0 — 패키지명 한글 필터링

미세먼지앱 빌드 실패 원인: `namespace = "kr.ac.kangwon.hai.miseongji.ttest_미세먼지"`
Android namespace/applicationId는 ASCII만 허용. 한글 들어가면 Gradle 빌드 100% 실패.
Debugger가 7번 diagnosis 해도 못 찾음 (빌드 에러에서 "한글이 문제"라는 힌트를 못 읽음).

비교:
- 긱뉴스 ✅ `kr.ac.kangwon.hai.geek_news` — 영문
- 학식 ✅ `kr.ac.kangwon.hai.kangwon_university_meal_app` — 영문  
- 미세먼지 ❌ `kr.ac.kangwon.hai.miseongji.ttest_미세먼지` — 한글 포함

**해결:** 패키지명 생성 단계에서 한글 강제 필터링. `re.sub(r'[^a-z0-9_.]', '', pkg)` 같은 정규식으로 코드레벨에서 걸러야 함. LLM 판단에 맡기면 안 됨.

---

## P0 — Engineer가 자기가 쓴 코드를 검증 안 함

세 가지 사례 전부 같은 뿌리: Engineer가 코드 쓰고 나서 실제로 동작하는지 확인을 안 함.

### 사례 1: 긱뉴스 `[id]` vs `$id`
`main.dart:50`에서 `'https://...item/[id].json'` — 문자열 보간 실패.
`$id`여야 하는데 `[id]`로 씀. analyze는 유효한 Dart 문법이라 통과.
실행하면 존재하지 않는 URL로 요청 → 뉴스 안 뜸.

### 사례 2: 학식앱 HTML을 JSON으로 파싱
`main.dart:43`에서 `https://kangwon.ac.kr/ko/extn/37/wkmenu-mngr/list.do` 호출.
이건 JSON API가 아니라 HTML 웹페이지. `json.decode(response.body)` → FormatException → 앱 크래시 → 흰 화면.
try-catch도 없어서 에러 핸들링 자체가 없음.
응답 키도 `['식당이름']`, `['메뉴항목']` 등 추측한 한글 키 — 실제 응답 구조 확인 안 함.

### 사례 3: 미세먼지앱 `YOUR_SERVICE_KEY`
`main.dart:44`에서 `serviceKey=YOUR_SERVICE_KEY` — 플레이스홀더 API 키 그대로.
공공데이터포털 API는 실제 키 없으면 401 반환.

### 공통 원인
Engineer가 search → fetch_http로 URL은 찾았는데, 코드에 URL 넣은 뒤 **실제 응답을 fetch_http로 검증하는 단계가 없음**.

**해결 (택 1, 둘 다 해도 됨):**
1. **ENGINEER_SYSTEM_V2 프롬프트에 규칙 추가:** "코드에 API URL을 넣었으면 반드시 fetch_http로 해당 URL을 호출해서 응답이 예상대로인지 확인. JSON이면 키 구조 확인, HTML이면 파싱 전략 변경."
2. **코드레벨 강제:** finalize 호출 전에 코드 내 URL을 자동 추출 → fetch_http로 검증 → 실패하면 finalize 거부

---

## P1 — 지금 테스트에서 터지는 것

### 1. request_diagnosis 무한루프

미세먼지v2 테스트: 빌드 실패 → request_diagnosis → report_diagnosis → write_file → 빌드 또 실패 → 같은 사이클 7번 반복 → max_turns 초과. 근본 원인(패키지명 한글)을 Debugger가 못 찾음.

**해결:** 같은 에러로 request_diagnosis 3번 했으면 포기. "문제되는 기능 빼고 빌드" 또는 "현재 상태로 사용자에게 전달 + 뭐가 안 되는지 알림" 전략.

### 2. analyze 무한루프 (30턴 소진)

긱뉴스/미세먼지 테스트에서 발생. analyze 실패 → write_file 수정 → 또 실패 → 같은 패턴 반복 → max_turns 초과.

프롬프트에 "3회 실패 시 request_diagnosis" 규칙 있지만 LLM이 안 지킴. 코드레�� 강제 필요.

**해결 (택 1):**
- execute_tool에서 연속 analyze 실패 카운터 추적 → 3회 시 tool result에 "반드시 request_diagnosis를 호출하세요" 메시지 강제 주입
- 또는 3회 시 자동으로 request_diagnosis 호출 (LLM 판단 안 기다림)

### 2. max_turns 부족

search + fetch_http 추가되면서 초기 턴 소모 증���. 30턴으로는 부족.

**해결:** max_turns=50으로 올리기. 당장 급한 건 이거.

### 3. 토큰 누적 폭발

매 턴마다 system + 전체 messages 전송. write_file content가 dart 파일 전체라 수만 토큰 누적. 컨텍스트 윈도 초과 가능.

**해결:** messages 누적 토큰 추적 + 한계 초과 시 오래된 tool result의 content를 요약/절단

### 4. tool_calls None 직렬��

`run_agentic_loop` line 5438: `[] or None` → None 보내면 일부 API에서 400 에러.

**해결:**
```python
msg = {"role": "assistant", "content": assistant_msg.content or ""}
if tool_calls:
    msg["tool_calls"] = [...]
messages.append(msg)
```

---

## P2 — 안전장치

### 5. 재귀 깊이 제한

execute_tool의 request_diagnosis → run_agentic_loop → execute_tool 재귀 가능. 현재 방어 없음.

**해결:** execute_tool에 depth 파라미터 추가, max_depth=1

### 6. pub_get 불필요 호출

LLM이 pubspec 안 바꿔도 매번 pub_get 호출 (긱뉴스 테스트에서 4번 연속).

**해결:** 프롬프트 강화 또는 execute_tool에서 pubspec.yaml 변경 여부 체크 후 실행

---

## RUFF 린터 결과 (남은 5개, 수동 수정 필요)

ruff 설치 완료 (`pip install ruff`). auto-fix 6개 적용됨. bare except 1개 수정함. 남은 5개:

### 버그 — current_files 미정의 (NameError 터짐)
- `vibe_factory.py:6738` — `relevant_files=current_files` 
- `vibe_factory.py:6761` — `current_files += [...]`
- 아젠틱 루프로 교체하면서 `current_files = []` 선언이 빠짐. verification 로직에서 참조 중.
- **해결:** `run_vibe_factory` 상단에 `current_files = []` 추가하거나, verification 로직에서 lib/ 스캔으로 교체 (Reviewer에 넘기는 것처럼)

### 버그 — Set/Tuple import 누락 (원래 코드)
- `server.py:2214` — `Set[Tuple[str, str, str]]` 사용하는데 import 안 됨
- **해결:** `from typing import Set, Tuple` 추가 (이미 `List, Dict, Any` 등은 import 돼 있음)

### 스타일 — lambda (무시해도 됨)
- `vibe_factory.py:6548` — E731 lambda 대신 def 쓰라. 동작에 문제 없음.

---

## P0 — API 키 필요한 서비스 처리 전략

미세먼지앱: 구조 31개 파일로 완벽하게 짰는데 실제 API 연동 안 됨.
`UnconfiguredAirQualityService`에서 `'외부 데이터 소스가 아직 확정되지 않았습니다.'` 던짐.

**해결:** ENGINEER_SYSTEM_V2 프롬프트에 규칙 추가:
1. API 키가 필요한 서비스면 **무료 공개 엔드포인트를 먼저 search로 찾아라**
   - 미세먼지: AQICN `https://api.waqi.info/feed/seoul/?token=demo` (demo 토큰, 즉시 사용)
   - 날씨: OpenWeatherMap 무료 티어
2. 공개 엔드포인트가 없으면 **사용자에게 키 입력받는 설정 화면을 만들어라** (빈 구현 대신)
3. `YOUR_SERVICE_KEY` 같은 플레이스홀더는 절대 코드에 넣지 마라

---

## 테스트 결과 요약 (v3, 2026-04-17)

| 앱 | 빌드 | 코드검증 | 에뮬 실행 | 비고 |
|---|---|---|---|---|
| 긱뉴스 | ✅ | ✅ | ❌ 크래시 | `[id]` vs `$id` 런타임 버그 |
| 미세먼지 | ✅ | ✅ | 미확인 | |
| 학식 | ✅ | ❌ | 실행됨, 메뉴 안 뜸 | HTML 파싱은 하는데 메뉴 셀렉터 미완성 |

### 학식앱 v4 분석 (현재 에뮬 설치된 버전)

v2 대비 크게 개선됨:
- 파일 14개로 구조 분리 (models/services/widgets/screens/utils)
- `package:html/parser.dart` 사용 — JSON 파싱 시도 안 함 ✓
- URL `https://knucoop.or.kr/restaurant/` — 강원대 생협 실제 사이트 ✓
- 식당 이름/위치/운영시간 HTML 스크래핑 성공
- 에러 핸들링 있음 (try-catch, timeout 15초)

**남은 문제:** `meal_parser.dart:89`에서 `'실제 오늘 메뉴 데이터는 현재 검증된 출처에서 제공되지 않아 운영 정보만 표시합니다.'` — 메뉴 항목 파싱 미구현. knucoop.or.kr에서 일별 메뉴 데이터의 HTML 셀렉터를 맞추면 해결.

**해결 방향:** Engineer 프롬프트에 "fetch_http로 페이지를 먼저 받아보고, 실제 메뉴 데이터가 어떤 HTML 구조로 들어있는지 확인한 뒤 파싱 로직을 작성하라" 규칙 추가. 지금은 식당 이름 하드코딩 + raw.contains() 매칭이라 메뉴 테이블 구조를 못 읽음.

---

## 참고 — 잘 된 것

- search + fetch_http 추가 잘함. 학식앱 ���공 (실제 API fetch 코드 생성 확인)
- 환경변수화 (BASE_PROJECT_PATH, BUILD_ROOT_DIR, MODEL_NAME) 좋음
- warning 제외하고 error만 blocking으로 바꾼 것 맞는 판단
- Debugger read-only 분리, execute_tool 디스패처 구조 깔끔
