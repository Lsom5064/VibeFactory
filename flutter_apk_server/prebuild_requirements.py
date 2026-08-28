from __future__ import annotations

import hashlib
import os
import re
from dataclasses import dataclass
from typing import Any, Mapping, Sequence


@dataclass(frozen=True)
class IntegrationDefinition:
    integration_id: str
    title: str
    requirement_type: str
    execution_location: str
    trigger_groups: tuple[tuple[str, ...], ...]
    environment_groups: tuple[tuple[str, ...], ...]
    client_build_environment: tuple[str, ...]
    setup_steps: tuple[str, ...]
    setup_url: str
    security_note: str
    setup_owner: str = "researcher"
    participant_credential_environment: str = ""
    participant_setup_steps: tuple[str, ...] = ()
    credential_pattern: str = ""
    package_allowlist_environment: str = ""
    supported: bool = True


EXTERNAL_CREDENTIAL_ENV_KEYS = (
    "APP_RUNTIME_OPENAI_API_KEY",
    "GOOGLE_MAPS_API_KEY",
    "GOOGLE_MAPS_ALLOWED_PACKAGES",
    "GOOGLE_OAUTH_CLIENT_ID",
    "GOOGLE_OAUTH_CLIENT_SECRET",
    "FIREBASE_CONFIG_JSON",
    "OPENWEATHER_API_KEY",
    "DATA_GO_KR_SERVICE_KEY",
    "SENDGRID_API_KEY",
    "AWS_SES_ACCESS_KEY_ID",
    "AWS_SES_SECRET_ACCESS_KEY",
    "TWILIO_ACCOUNT_SID",
    "TWILIO_AUTH_TOKEN",
    "SOLAPI_API_KEY",
    "SOLAPI_API_SECRET",
    "TOSS_PAYMENTS_CLIENT_KEY",
    "TOSS_PAYMENTS_SECRET_KEY",
    "KAKAO_NATIVE_APP_KEY",
    "KAKAO_REST_API_KEY",
    "NAVER_MAP_CLIENT_ID",
    "NAVER_CLIENT_ID",
    "NAVER_CLIENT_SECRET",
    "YOUTUBE_API_KEY",
    "SUPABASE_URL",
    "SUPABASE_ANON_KEY",
    "SUPABASE_SERVICE_ROLE_KEY",
)


INTEGRATION_CATALOG: tuple[IntegrationDefinition, ...] = (
    IntegrationDefinition(
        integration_id="openai",
        title="OpenAI API",
        requirement_type="server_api_key",
        execution_location="server_proxy",
        trigger_groups=(("openai", "chatgpt", "gpt"), ("ai 채팅", "ai 분석", "ai 상담", "llm")),
        environment_groups=(("APP_RUNTIME_OPENAI_API_KEY",),),
        client_build_environment=(),
        setup_steps=(
            "OpenAI Platform에서 프로젝트를 만들고 API 키를 발급합니다.",
            "결제 수단과 프로젝트 사용량 한도를 설정합니다.",
            "서버의 APP_RUNTIME_OPENAI_API_KEY에 등록한 뒤 서버를 다시 시작합니다.",
        ),
        setup_url="https://platform.openai.com/api-keys",
        security_note="입력한 키는 연구 기록으로 저장되며, 생성 앱에는 넣지 않고 VibeFactory 서버가 대신 호출합니다.",
        setup_owner="participant",
        participant_credential_environment="APP_RUNTIME_OPENAI_API_KEY",
        participant_setup_steps=(
            "OpenAI Platform에서 연구용 프로젝트를 만들고 API 키를 발급합니다.",
            "결제 수단과 프로젝트 사용량 한도를 확인합니다.",
        ),
        credential_pattern=r"sk-[A-Za-z0-9_-]{10,}",
    ),
    IntegrationDefinition(
        integration_id="google_maps_platform",
        title="Google Maps Platform",
        requirement_type="android_api_key",
        execution_location="client_build",
        trigger_groups=(
            ("google maps", "google map", "구글 지도", "places api", "places sdk", "google places"),
            ("nearby cafe", "nearby place", "주변 카페", "주변 장소"),
        ),
        environment_groups=(("GOOGLE_MAPS_API_KEY",),),
        client_build_environment=("GOOGLE_MAPS_API_KEY",),
        setup_steps=(
            "Google Cloud 프로젝트를 만들고 결제 계정을 연결합니다.",
            "Maps SDK for Android와 필요한 Places API 또는 Routes API를 활성화합니다.",
            "API 키를 만든 뒤 Android 앱 제한에 패키지명 {package_name}과 VibeFactory 배포 서명 SHA를 등록합니다.",
            "API 제한에는 실제로 사용할 Maps API만 선택합니다.",
            "서버의 GOOGLE_MAPS_API_KEY에 키를 등록하고 GOOGLE_MAPS_ALLOWED_PACKAGES에 패키지명을 추가한 뒤 서버를 다시 시작합니다.",
        ),
        setup_url="https://developers.google.com/maps/documentation/android-sdk/get-api-key",
        security_note="Android 키는 APK에 포함될 수 있으므로 패키지명, 서명 SHA, API 제한이 반드시 필요합니다.",
        setup_owner="researcher",
        package_allowlist_environment="GOOGLE_MAPS_ALLOWED_PACKAGES",
    ),
    IntegrationDefinition(
        integration_id="firebase",
        title="Firebase",
        requirement_type="cloud_project",
        execution_location="provider_sdk",
        trigger_groups=(("firebase", "파이어베이스", "firestore", "fcm", "firebase auth"),),
        environment_groups=(("FIREBASE_CONFIG_JSON",),),
        client_build_environment=(),
        setup_steps=(
            "Firebase Console에서 프로젝트와 Android 앱을 생성합니다.",
            "생성 앱의 패키지명을 등록하고 google-services.json을 발급합니다.",
            "Authentication, Firestore, Storage 또는 FCM 중 필요한 서비스만 활성화합니다.",
            "보안 규칙과 사용량 한도를 검토한 뒤 FIREBASE_CONFIG_JSON 경로로 등록합니다.",
        ),
        setup_url="https://firebase.google.com/docs/android/setup",
        security_note="설정 파일 자체보다 Firestore와 Storage 보안 규칙을 잘못 여는 것이 더 큰 위험입니다.",
        supported=False,
    ),
    IntegrationDefinition(
        integration_id="supabase",
        title="Supabase",
        requirement_type="cloud_project",
        execution_location="provider_sdk",
        trigger_groups=(("supabase", "수파베이스"),),
        environment_groups=(("SUPABASE_URL",), ("SUPABASE_ANON_KEY",)),
        client_build_environment=(),
        setup_steps=(
            "Supabase에서 프로젝트를 만들고 데이터 저장 위치를 확인합니다.",
            "앱에서 사용할 테이블과 Authentication 제공자를 구성합니다.",
            "모든 테이블에 Row Level Security 정책을 만들고 익명 사용자의 허용 범위를 제한합니다.",
            "프로젝트 URL과 anon key만 클라이언트용 설정에 등록하고 service role key는 서버에만 보관합니다.",
        ),
        setup_url="https://supabase.com/docs/guides/getting-started/quickstarts/kotlin",
        security_note="service role key는 APK에 포함하면 안 되며, RLS가 없는 anon key 사용도 허용하면 안 됩니다.",
        supported=False,
    ),
    IntegrationDefinition(
        integration_id="google_oauth",
        title="Google 계정 OAuth",
        requirement_type="oauth",
        execution_location="server_oauth",
        trigger_groups=(("gmail", "구글 캘린더", "google calendar", "google drive", "구글 드라이브", "구글 로그인"),),
        environment_groups=(("GOOGLE_OAUTH_CLIENT_ID",), ("GOOGLE_OAUTH_CLIENT_SECRET",)),
        client_build_environment=(),
        setup_steps=(
            "Google Cloud에서 OAuth 동의 화면을 구성합니다.",
            "필요한 API와 최소 범위만 활성화하고 OAuth 클라이언트를 발급합니다.",
            "테스트 사용자를 등록하고 검증이 필요한 민감 범위인지 확인합니다.",
            "클라이언트 ID와 비밀 값은 서버의 전용 OAuth 설정에 등록합니다.",
        ),
        setup_url="https://developers.google.com/identity/protocols/oauth2",
        security_note="참가자의 비밀번호를 받지 않고 공식 OAuth 동의 화면과 사용자별 토큰을 사용해야 합니다.",
        supported=False,
    ),
    IntegrationDefinition(
        integration_id="weather",
        title="실시간 날씨 API",
        requirement_type="server_api_key",
        execution_location="client_build",
        trigger_groups=(("openweather", "weatherapi", "실시간 날씨", "현재 날씨", "날씨 예보"),),
        environment_groups=(("OPENWEATHER_API_KEY",),),
        client_build_environment=("OPENWEATHER_API_KEY",),
        setup_steps=(
            "날씨 제공자를 선택하고 개발자 계정을 만듭니다.",
            "호출량과 상업적 이용 조건을 확인한 뒤 API 키를 발급합니다.",
            "키를 서버의 OPENWEATHER_API_KEY에 등록합니다.",
        ),
        setup_url="https://openweathermap.org/api",
        security_note="입력한 연구용 키는 연구 기록과 Task 설정에 저장되며 생성 앱 빌드에 사용됩니다.",
        setup_owner="participant",
        participant_credential_environment="OPENWEATHER_API_KEY",
        participant_setup_steps=(
            "OpenWeather 개발자 계정을 만들고 사용할 API 상품을 선택합니다.",
            "호출량과 비용 조건을 확인한 뒤 연구용 API 키를 발급합니다.",
        ),
        credential_pattern=r"[A-Fa-f0-9]{16,64}",
    ),
    IntegrationDefinition(
        integration_id="public_data_portal",
        title="공공데이터포털 API",
        requirement_type="server_api_key",
        execution_location="client_build",
        trigger_groups=(("공공데이터", "data.go.kr", "공공 api", "공공api"),),
        environment_groups=(("DATA_GO_KR_SERVICE_KEY",),),
        client_build_environment=("DATA_GO_KR_SERVICE_KEY",),
        setup_steps=(
            "공공데이터포털 계정을 만들고 사용할 API의 활용 신청을 합니다.",
            "승인 상태, 일일 호출 한도, 응답 형식을 확인합니다.",
            "발급된 일반 인증키를 DATA_GO_KR_SERVICE_KEY에 등록합니다.",
        ),
        setup_url="https://www.data.go.kr/",
        security_note="입력한 연구용 인증키는 연구 기록과 Task 설정에 저장되며 생성 앱 빌드에 사용됩니다.",
        setup_owner="participant",
        participant_credential_environment="DATA_GO_KR_SERVICE_KEY",
        participant_setup_steps=(
            "공공데이터포털에 가입하고 사용할 API의 활용 신청을 합니다.",
            "승인이 완료되면 발급된 일반 인증키를 확인합니다.",
        ),
        credential_pattern=r"[^\s]{10,4096}",
    ),
    IntegrationDefinition(
        integration_id="email_delivery",
        title="자동 이메일 발송 서비스",
        requirement_type="server_credential",
        execution_location="server_adapter",
        trigger_groups=(("smtp", "sendgrid", "aws ses", "자동 메일", "메일 자동", "이메일 발송"),),
        environment_groups=(("SENDGRID_API_KEY", "AWS_SES_ACCESS_KEY_ID"),),
        client_build_environment=(),
        setup_steps=(
            "SendGrid 또는 AWS SES 같은 발송 제공자를 선택합니다.",
            "발신 주소나 도메인을 인증하고 테스트 또는 운영 발송 제한을 확인합니다.",
            "발급 자격증명을 서버에만 등록하고 예약 발송 작업도 서버에서 실행합니다.",
        ),
        setup_url="https://docs.aws.amazon.com/ses/latest/dg/setting-up.html",
        security_note="SMTP 비밀번호와 발송 API 키는 APK에 포함하면 안 됩니다.",
        supported=False,
    ),
    IntegrationDefinition(
        integration_id="sms_delivery",
        title="문자 메시지 발송 서비스",
        requirement_type="server_credential",
        execution_location="server_adapter",
        trigger_groups=(("twilio", "solapi", "문자 자동", "sms 발송", "문자 발송"),),
        environment_groups=(("TWILIO_AUTH_TOKEN", "SOLAPI_API_SECRET"),),
        client_build_environment=(),
        setup_steps=(
            "문자 발송 제공자 계정을 만들고 발신번호 인증을 완료합니다.",
            "API 키와 비밀 값을 발급하고 발송 한도와 비용 알림을 설정합니다.",
            "자격증명은 서버 전용 설정에 등록합니다.",
        ),
        setup_url="https://www.twilio.com/docs/messaging/quickstart",
        security_note="발송 자격증명은 서버에만 두고 사용자별 발송량을 제한해야 합니다.",
        supported=False,
    ),
    IntegrationDefinition(
        integration_id="payments",
        title="온라인 결제 서비스",
        requirement_type="payment_account",
        execution_location="server_adapter",
        trigger_groups=(("토스페이먼츠", "toss payments", "stripe", "실제 결제", "온라인 결제"),),
        environment_groups=(("TOSS_PAYMENTS_CLIENT_KEY",), ("TOSS_PAYMENTS_SECRET_KEY",)),
        client_build_environment=(),
        setup_steps=(
            "결제 제공자에 가맹점 또는 테스트 계정을 만듭니다.",
            "클라이언트 키와 서버 비밀 키를 각각 발급합니다.",
            "결제 승인과 취소는 서버에서 처리하고 웹훅 검증을 설정합니다.",
        ),
        setup_url="https://docs.tosspayments.com/guides/v2/get-started/payment-flow",
        security_note="비밀 키를 APK에 넣으면 안 되며 결제 성공 여부는 서버 승인 결과로 판단해야 합니다.",
        supported=False,
    ),
    IntegrationDefinition(
        integration_id="kakao_platform",
        title="Kakao Developers",
        requirement_type="provider_account",
        execution_location="mixed",
        trigger_groups=(("카카오 로그인", "카카오맵", "kakao map", "카카오 api", "카카오톡 공유"),),
        environment_groups=(("KAKAO_NATIVE_APP_KEY",),),
        client_build_environment=("KAKAO_NATIVE_APP_KEY",),
        setup_steps=(
            "Kakao Developers에서 애플리케이션을 등록합니다.",
            "Android 플랫폼에 생성 앱 패키지명과 키 해시를 등록합니다.",
            "사용할 제품과 동의 항목을 활성화하고 네이티브 앱 키를 등록합니다.",
        ),
        setup_url="https://developers.kakao.com/docs/ko/tutorial/start",
        security_note="REST 비밀 값과 사용자 토큰은 서버에서 관리하고 필요한 제품만 활성화합니다.",
        supported=False,
    ),
    IntegrationDefinition(
        integration_id="naver_platform",
        title="NAVER Developers / Maps",
        requirement_type="provider_account",
        execution_location="mixed",
        trigger_groups=(("네이버 로그인", "네이버 지도", "naver map", "네이버 api", "clova"),),
        environment_groups=(("NAVER_MAP_CLIENT_ID", "NAVER_CLIENT_ID"),),
        client_build_environment=("NAVER_MAP_CLIENT_ID",),
        setup_steps=(
            "NAVER Developers 또는 Naver Cloud Platform에서 애플리케이션을 등록합니다.",
            "사용할 API와 Android 패키지 정보를 등록합니다.",
            "클라이언트 ID와 비밀 값은 용도에 따라 앱 빌드와 서버 설정으로 분리합니다.",
        ),
        setup_url="https://developers.naver.com/docs/common/openapiguide/appregister.md",
        security_note="클라이언트 비밀 값은 APK에 넣지 않습니다.",
        supported=False,
    ),
    IntegrationDefinition(
        integration_id="youtube_data",
        title="YouTube Data API",
        requirement_type="server_api_key",
        execution_location="server_adapter",
        trigger_groups=(("youtube data api", "유튜브 api", "유튜브 검색", "youtube 검색"),),
        environment_groups=(("YOUTUBE_API_KEY",),),
        client_build_environment=(),
        setup_steps=(
            "Google Cloud 프로젝트에서 YouTube Data API v3를 활성화합니다.",
            "API 키 또는 사용자 데이터가 필요하면 OAuth 클라이언트를 발급합니다.",
            "일일 할당량을 확인하고 서버 설정에 등록합니다.",
        ),
        setup_url="https://developers.google.com/youtube/v3/getting-started",
        security_note="검색 키와 사용자 계정 OAuth 토큰을 구분해서 관리해야 합니다.",
        supported=False,
    ),
)


CATALOG_BY_ID = {item.integration_id: item for item in INTEGRATION_CATALOG}


def _normalized(value: Any) -> str:
    return re.sub(r"\s+", " ", str(value or "")).strip()


def _matches(definition: IntegrationDefinition, prompt: str) -> bool:
    lowered = prompt.lower()
    return any(any(token.lower() in lowered for token in group) for group in definition.trigger_groups)


def _configured_packages(environment: Mapping[str, str], environment_name: str) -> set[str]:
    if not environment_name:
        return set()
    return {
        value
        for value in re.split(r"[\s,;]+", _normalized(environment.get(environment_name)))
        if value
    }


def _credentials_configured(definition: IntegrationDefinition, environment: Mapping[str, str]) -> bool:
    return all(any(_normalized(environment.get(key)) for key in group) for group in definition.environment_groups)


def _catalog_requirement(
    definition: IntegrationDefinition,
    *,
    reason: str,
    environment: Mapping[str, str],
    package_name: str,
) -> dict[str, Any]:
    normalized_package_name = _normalized(package_name)
    credentials_configured = _credentials_configured(definition, environment)
    package_registration_required = bool(definition.package_allowlist_environment and normalized_package_name)
    package_registered = (
        not package_registration_required
        or normalized_package_name
        in _configured_packages(environment, definition.package_allowlist_environment)
    )
    configured = credentials_configured and package_registered
    setup_steps = [
        step.replace("{package_name}", normalized_package_name or "생성 앱의 패키지명")
        for step in definition.setup_steps
    ]
    environment_names = [key for group in definition.environment_groups for key in group]
    if definition.package_allowlist_environment:
        environment_names.append(definition.package_allowlist_environment)
    return {
        "id": definition.integration_id,
        "title": definition.title,
        "type": definition.requirement_type,
        "reason": _normalized(reason) or f"요청한 기능에 {definition.title} 연결이 필요합니다.",
        "blocking": True,
        "configured": configured,
        "supported": definition.supported,
        "execution_location": definition.execution_location,
        "setup_steps": setup_steps,
        "participant_setup_steps": list(definition.participant_setup_steps),
        "setup_url": definition.setup_url,
        "security_note": definition.security_note,
        "setup_owner": definition.setup_owner,
        "participant_can_register": bool(definition.participant_credential_environment),
        "participant_credential_environment": definition.participant_credential_environment,
        "credential_pattern": definition.credential_pattern,
        "environment_names": environment_names,
        "client_build_environment": list(definition.client_build_environment),
        "credentials_configured": credentials_configured,
        "package_name": normalized_package_name,
        "package_registration_required": package_registration_required,
        "package_registered": package_registered,
    }


def _custom_requirement(item: Mapping[str, Any]) -> dict[str, Any] | None:
    title = _normalized(item.get("title") or item.get("provider") or item.get("capability"))
    reason = _normalized(item.get("reason"))
    requirement_type = _normalized(item.get("type") or "external_requirement").lower()
    if not title and not reason:
        return None
    blocking_types = {
        "api_key",
        "server_api_key",
        "android_api_key",
        "oauth",
        "external_account",
        "provider_account",
        "payment_account",
        "server_credential",
        "cloud_project",
        "backend",
        "data_source",
    }
    blocking = bool(item.get("blocking")) or requirement_type in blocking_types
    requirement_id = _normalized(item.get("id"))
    if not requirement_id:
        digest = hashlib.sha256(f"{title}|{reason}".encode("utf-8")).hexdigest()[:8]
        requirement_id = f"custom_{digest}"
    return {
        "id": requirement_id,
        "title": title or "추가 준비사항",
        "type": requirement_type,
        "reason": reason or "앱 생성 전에 준비 여부를 확인해야 합니다.",
        "blocking": blocking,
        "configured": not blocking,
        "supported": not blocking,
        "acknowledgement_only": not blocking,
        "execution_location": _normalized(item.get("execution_location")) or "user_setup",
        "setup_steps": [
            _normalized(step)
            for step in item.get("setup_steps", [])
            if _normalized(step)
        ] or ["필요한 계정, 기기 설정 또는 서비스 이용 조건을 확인합니다."],
        "setup_url": _normalized(item.get("setup_url")),
        "security_note": _normalized(item.get("security_note"))
        or "담당 연구원이 필요한 연결 방법을 확인합니다.",
        "participant_setup_steps": [],
        "setup_owner": "researcher" if blocking else "participant",
        "participant_can_register": False,
        "participant_credential_environment": "",
        "credential_pattern": "",
        "environment_names": [],
        "client_build_environment": [],
    }


def resolve_prebuild_requirements(
    prompt: str,
    agent_requirements: Sequence[Mapping[str, Any]] | None = None,
    *,
    environment: Mapping[str, str] | None = None,
    package_name: str = "",
) -> list[dict[str, Any]]:
    env = os.environ if environment is None else environment
    resolved: list[dict[str, Any]] = []
    seen: set[str] = set()

    def append(item: dict[str, Any] | None) -> None:
        if not item:
            return
        requirement_id = _normalized(item.get("id"))
        if not requirement_id or requirement_id in seen:
            return
        seen.add(requirement_id)
        resolved.append(item)

    for raw_item in agent_requirements or []:
        requirement_id = _normalized(raw_item.get("id")).lower()
        definition = CATALOG_BY_ID.get(requirement_id)
        if definition is not None:
            append(
                _catalog_requirement(
                    definition,
                    reason=_normalized(raw_item.get("reason")),
                    environment=env,
                    package_name=package_name,
                )
            )
        else:
            append(_custom_requirement(raw_item))

    for definition in INTEGRATION_CATALOG:
        if definition.integration_id not in seen and _matches(definition, prompt):
            append(
                _catalog_requirement(
                    definition,
                    reason="",
                    environment=env,
                    package_name=package_name,
                )
            )

    return resolved[:8]


def missing_blocking_requirements(requirements: Sequence[Mapping[str, Any]]) -> list[dict[str, Any]]:
    return [
        dict(item)
        for item in requirements
        if bool(item.get("blocking")) and (not bool(item.get("configured")) or not bool(item.get("supported")))
    ]


def format_prebuild_requirements(requirements: Sequence[Mapping[str, Any]]) -> str:
    missing = missing_blocking_requirements(requirements)
    lines = ["**앱 생성 전에 준비사항을 확인해 주세요.**"]
    for item in requirements:
        title = _normalized(item.get("title")) or "추가 준비사항"
        configured = bool(item.get("configured"))
        supported = bool(item.get("supported"))
        acknowledgement_only = bool(item.get("acknowledgement_only"))
        participant_can_register = bool(item.get("participant_can_register"))
        setup_owner = _normalized(item.get("setup_owner")) or "researcher"
        if acknowledgement_only:
            state = "확인 필요"
        elif configured and supported:
            state = "연결됨"
        elif setup_owner == "researcher" or not supported:
            state = "담당 연구원 설정 필요"
        elif participant_can_register:
            state = "API 키 입력 필요"
        else:
            state = "등록 필요"
        lines.extend(["", f"**{title} · {state}**", _normalized(item.get("reason"))])
        if configured and supported and not acknowledgement_only:
            continue
        if setup_owner == "researcher" or not supported:
            lines.append("앱 등록 또는 서버 설정이 필요합니다. 담당 연구원에게 문의해 주세요.")
            continue
        steps = [
            _normalized(step)
            for step in (
                item.get("participant_setup_steps", [])
                if participant_can_register
                else item.get("setup_steps", [])
            )
            if _normalized(step)
        ]
        if acknowledgement_only or participant_can_register:
            lines.extend(f"{index}. {step}" for index, step in enumerate(steps, start=1))
            setup_url = _normalized(item.get("setup_url"))
            if setup_url:
                lines.append(f"발급 안내: {setup_url}")
        if participant_can_register:
            lines.append("발급한 연구용 API 키만 이 채팅에 입력해 주세요.")
        security_note = _normalized(item.get("security_note"))
        if security_note:
            lines.append(f"이용 안내: {security_note}")

    lines.extend(
        [
            "",
            (
                "필수 준비가 완료되면 등록 상태를 확인한 뒤 다음 단계로 진행합니다."
                if missing
                else "준비사항을 확인했다면 아래 버튼을 눌러 생성 프롬프트를 확인하세요."
            ),
        ]
    )
    return "\n".join(line for line in lines if line is not None).strip()


def pending_participant_credential_requirement(
    requirements: Sequence[Mapping[str, Any]],
) -> dict[str, Any] | None:
    for item in requirements:
        if not bool(item.get("blocking")) or bool(item.get("configured")):
            continue
        if not bool(item.get("supported")) or not bool(item.get("participant_can_register")):
            continue
        credential_name = _normalized(item.get("participant_credential_environment"))
        if credential_name:
            return dict(item)
    return None


def extract_participant_credential(
    prompt: str,
    requirement: Mapping[str, Any],
) -> str:
    value = str(prompt or "").strip()
    if not value or "\n" in value or "\r" in value:
        return ""
    value = re.sub(r"^(?:api\s*key|api\s*키|인증키|키)\s*[:=]\s*", "", value, flags=re.IGNORECASE).strip()
    if len(value) >= 2 and value[0] == value[-1] and value[0] in {'`', '"', "'"}:
        value = value[1:-1].strip()
    pattern = _normalized(requirement.get("credential_pattern")) or r"[^\s]{8,4096}"
    try:
        matches = re.fullmatch(pattern, value)
    except re.error:
        matches = re.fullmatch(r"[^\s]{8,4096}", value)
    return value if matches else ""


def client_build_environment_for_requirements(
    requirements: Sequence[Mapping[str, Any]],
    *,
    environment: Mapping[str, str] | None = None,
) -> dict[str, str]:
    env = os.environ if environment is None else environment
    result: dict[str, str] = {}
    for requirement in requirements:
        if not bool(requirement.get("configured")) or not bool(requirement.get("supported")):
            continue
        for key in requirement.get("client_build_environment", []):
            normalized_key = _normalized(key)
            value = _normalized(env.get(normalized_key))
            if normalized_key and value:
                result[normalized_key] = value
    return result
