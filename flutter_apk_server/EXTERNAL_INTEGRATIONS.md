# External Integrations

## 동작 원칙

- 새 앱의 최종 생성 프롬프트를 보여주기 전에 외부 API, 계정, 결제, OAuth, 백엔드, 특수 권한, 하드웨어, 백그라운드 실행 제한을 검사한다.
- 필수 연동이 없으면 앱 생성을 시작하지 않고 참가자 발급형과 연구원 설정형을 구분해 먼저 안내한다.
- 계정 가입과 단일 연구용 키 발급만 필요한 지원 API는 참가자가 채팅에 키를 입력할 수 있다. 원문은 `task_events`, 실제 Task 설정은 `task_integration_credentials`에 저장한다.
- 참가자가 입력한 키는 명세 Agent와 Codex 대화 이력에서 등록 완료 표기로 치환하며 생성 프롬프트, Codex 환경, 소스 코드, 결과 JSON에는 넣지 않는다.
- 패키지명, 배포 서명, OAuth 리디렉션, 서비스 계정, 결제 계정 또는 제공자 앱 등록이 필요하면 참가자에게 기술 값을 요구하지 않고 담당 연구원 문의를 안내한다.
- 로컬 서버는 `~/.vibefactory/integrations.env`를 읽는다. AWS 서버는 root 전용 `EnvironmentFile` 또는 AWS Secrets Manager에서 같은 이름의 환경변수를 서버 프로세스에 주입한다.
- Android 앱에 들어갈 수밖에 없는 클라이언트 키도 패키지명, 배포 서명 SHA, 허용 API로 제한한다.
- 서버 비밀 키를 요구하는 API는 서버 어댑터가 준비되기 전까지 등록 여부와 관계없이 생성을 차단한다.

## 우선 등록 권장

| 우선순위 | 제공자 | 환경변수 | 현재 처리 |
|---|---|---|---|
| 1 | OpenAI API | `APP_RUNTIME_OPENAI_API_KEY` | 기존 VibeFactory 서버 프록시 지원 |
| 1 | Google Maps Platform | `GOOGLE_MAPS_API_KEY` | Android 최종 빌드 주입 지원, 앱별 제한 필요 |
| 2 | Firebase | `FIREBASE_CONFIG_JSON` | 프로젝트·보안 규칙·생성 앱별 등록 기능 추가 필요 |
| 2 | Supabase | `SUPABASE_URL`, `SUPABASE_ANON_KEY` | RLS·사용자 격리·클라이언트 주입 기능 추가 필요 |
| 2 | Google OAuth | `GOOGLE_OAUTH_CLIENT_ID`, `GOOGLE_OAUTH_CLIENT_SECRET` | 사용자별 OAuth 토큰 서버 저장 기능 추가 필요 |
| 2 | 공공데이터포털 | `DATA_GO_KR_SERVICE_KEY` | 참가자 연구용 키 등록 및 최종 빌드 주입 지원 |
| 2 | 실시간 날씨 | `OPENWEATHER_API_KEY` | 참가자 연구용 키 등록 및 최종 빌드 주입 지원 |
| 3 | 이메일 발송 | `SENDGRID_API_KEY` 또는 AWS SES 자격증명 | 서버 예약·발송 어댑터 추가 필요 |
| 3 | 문자 발송 | Twilio 또는 Solapi 자격증명 | 서버 발송 어댑터 추가 필요 |
| 3 | Kakao Developers | `KAKAO_NATIVE_APP_KEY`, `KAKAO_REST_API_KEY` | 제품별 SDK·서버 어댑터 추가 필요 |
| 3 | NAVER Developers / Maps | `NAVER_MAP_CLIENT_ID`, `NAVER_CLIENT_ID`, `NAVER_CLIENT_SECRET` | 제품별 SDK·서버 어댑터 추가 필요 |
| 3 | YouTube Data API | `YOUTUBE_API_KEY` | 서버 어댑터와 할당량 관리 추가 필요 |
| 3 | Toss Payments | `TOSS_PAYMENTS_CLIENT_KEY`, `TOSS_PAYMENTS_SECRET_KEY` | 결제 승인·웹훅 서버 구현 추가 필요 |

## 로컬 등록 예시

```bash
mkdir -p ~/.vibefactory
chmod 700 ~/.vibefactory
touch ~/.vibefactory/integrations.env
chmod 600 ~/.vibefactory/integrations.env
```

`~/.vibefactory/integrations.env`에는 실제로 준비한 항목만 기록한다.

```bash
export APP_RUNTIME_OPENAI_API_KEY='...'
export GOOGLE_MAPS_API_KEY='...'
export GOOGLE_MAPS_ALLOWED_PACKAGES='kr.ac.kangwon.hai.generated.example'
```

관리자가 사전 등록한 값은 `run-local-server.sh`로 서버를 다시 시작하면 전체 Task에서 사용할 수 있다. 참가자가 채팅에 등록한 지원 API 키는 해당 Task와 그 Task에서 분기한 작업에만 적용되며 서버 재시작이 필요 없다.

## 참가자와 연구원 역할

### 참가자가 채팅에 등록할 수 있는 항목

- OpenAI 연구용 API 키: 서버 런타임 프록시에서 해당 Task에만 사용
- OpenWeather 연구용 API 키: 해당 Task의 최종 앱 빌드에 주입
- 공공데이터포털 일반 인증키: 해당 Task의 최종 앱 빌드에 주입

채팅에는 키만 입력한다. 서버는 형식을 확인한 뒤 등록 상태를 다시 검사하고, 모든 필수 조건이 준비되면 생성 프롬프트 확인 단계로 자동 전환한다.

### 담당 연구원이 설정하는 항목

- Android 패키지명과 배포 인증서 지문 등록
- Firebase·Kakao·NAVER 등 제공자 애플리케이션 등록
- OAuth 클라이언트, 리디렉션 주소와 동의 화면
- 서비스 계정, 서버 비밀 값, 발신 도메인·전화번호, 결제 계정과 웹훅

참가자 화면에는 패키지명이나 인증서 지문 대신 `담당 연구원에게 문의해 주세요`라고 표시한다. 기술 절차와 실제 설정값은 관리자 문서와 서버 설정에서만 관리한다.

## 주요 발급 경로

- OpenAI: https://platform.openai.com/api-keys
- Google Maps Platform: https://developers.google.com/maps/documentation/android-sdk/get-api-key
- Firebase Android: https://firebase.google.com/docs/android/setup
- Supabase Kotlin: https://supabase.com/docs/guides/getting-started/quickstarts/kotlin
- Google OAuth: https://developers.google.com/identity/protocols/oauth2
- 공공데이터포털: https://www.data.go.kr/
- OpenWeather: https://openweathermap.org/api
- Kakao Developers: https://developers.kakao.com/docs/ko/tutorial/start
- NAVER Developers: https://developers.naver.com/docs/common/openapiguide/appregister.md
- YouTube Data API: https://developers.google.com/youtube/v3/getting-started
- AWS SES: https://docs.aws.amazon.com/ses/latest/dg/setting-up.html
- Toss Payments: https://docs.tosspayments.com/guides/v2/get-started/payment-flow

## Google Maps 추가 주의

`GOOGLE_MAPS_API_KEY`를 한 번 등록하는 것만으로 보안 설정이 끝나지 않는다. 생성 앱마다 패키지명이 다르므로 Google Cloud Console의 Android 앱 제한에 해당 패키지명과 VibeFactory 배포 서명 인증서 SHA를 추가해야 한다. 등록을 마친 패키지명은 `GOOGLE_MAPS_ALLOWED_PACKAGES`에도 쉼표로 구분해 추가한다. 서버는 키와 현재 생성 앱 패키지명이 모두 등록된 경우에만 준비 완료로 판정한다. Maps SDK for Android, Places API, Routes API 등 실제 사용하는 API만 허용한다.

대량 앱 생성에서는 앱마다 별도 키를 만드는 방식이 프로젝트 키 개수와 운영 비용 측면에서 확장되지 않을 수 있다. 연구용 앱 수, 키 재사용 범위, 앱별 패키지 제한, 사용량 상한을 먼저 정해야 한다.
