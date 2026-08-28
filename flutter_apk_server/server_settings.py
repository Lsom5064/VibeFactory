from __future__ import annotations

import logging
import os
import shlex
from dataclasses import dataclass
from pathlib import Path
from typing import Mapping, Optional

try:
    from .prebuild_requirements import EXTERNAL_CREDENTIAL_ENV_KEYS
except ImportError:
    from prebuild_requirements import EXTERNAL_CREDENTIAL_ENV_KEYS  # type: ignore[no-redef]


GENERATED_APP_SIGNING_ENV_KEYS = (
    "GENERATED_APP_KEYSTORE_PATH",
    "GENERATED_APP_KEYSTORE_PASSWORD",
    "GENERATED_APP_KEY_ALIAS",
    "GENERATED_APP_KEY_PASSWORD",
)
SUBPROCESS_SECRET_ENV_KEYS = (
    *GENERATED_APP_SIGNING_ENV_KEYS,
    *EXTERNAL_CREDENTIAL_ENV_KEYS,
    "ADMIN_API_TOKEN",
)


class UvicornAccessLogQueryFilter(logging.Filter):
    """Keep access logs useful without exposing identity query parameters."""

    def filter(self, record: logging.LogRecord) -> bool:
        args = record.args
        if isinstance(args, tuple) and len(args) >= 3:
            sanitized = list(args)
            sanitized[2] = str(sanitized[2]).partition("?")[0]
            record.args = tuple(sanitized)
        return True


def install_uvicorn_access_log_query_filter() -> None:
    logger = logging.getLogger("uvicorn.access")
    if any(isinstance(item, UvicornAccessLogQueryFilter) for item in logger.filters):
        return
    logger.addFilter(UvicornAccessLogQueryFilter())


def build_subprocess_environment(
    source: Optional[Mapping[str, str]] = None,
) -> dict[str, str]:
    env = dict(os.environ if source is None else source)
    for key in SUBPROCESS_SECRET_ENV_KEYS:
        env.pop(key, None)
    return env


def resolve_path(value: str, default: Path, root: Path) -> Path:
    candidate = Path(value).expanduser() if value else default
    if not candidate.is_absolute():
        candidate = (root / candidate).resolve()
    return candidate


def default_base_project_path(root: Path) -> Path:
    local_default = root / "base_android_project"
    sibling_default = root.parent / "BaseProject"
    if local_default.exists():
        return local_default
    if sibling_default.exists():
        return sibling_default
    return local_default


def detect_codex_binary() -> str:
    native_codex = (
        Path.home()
        / ".npm-global"
        / "lib"
        / "node_modules"
        / "@openai"
        / "codex"
        / "vendor"
        / "aarch64-apple-darwin"
        / "codex"
        / "codex"
    )
    user_local_codex = Path.home() / ".npm-global" / "bin" / "codex"
    if native_codex.exists():
        return str(native_codex)
    if user_local_codex.exists():
        return str(user_local_codex)
    return "codex"


def env_flag(name: str, default: bool) -> bool:
    raw_value = os.getenv(name)
    if raw_value is None:
        return default
    normalized = raw_value.strip().lower()
    if normalized in {"1", "true", "yes", "on"}:
        return True
    if normalized in {"0", "false", "no", "off"}:
        return False
    return default


def default_codex_command(root: Path) -> str:
    _ = root
    args = [
        shlex.quote(detect_codex_binary()),
        "exec",
        "--skip-git-repo-check",
        "--json",
    ]
    codex_model = os.getenv("CODEX_MODEL", "gpt-5.6-sol").strip() or "gpt-5.6-sol"
    args.extend(["--model", shlex.quote(codex_model)])
    reasoning_effort = os.getenv("CODEX_REASONING_EFFORT", "default").strip().lower() or "default"
    if reasoning_effort == "default":
        reasoning_effort = "medium"
    args.extend(["-c", shlex.quote(f'model_reasoning_effort="{reasoning_effort}"')])
    service_tier = os.getenv("CODEX_SERVICE_TIER", "default").strip().lower() or "default"
    args.extend(["-c", shlex.quote(f'service_tier="{service_tier}"')])
    if env_flag("CODEX_FAST_MODE", service_tier in {"priority", "fast"}):
        args.extend(["--enable", "fast_mode"])
    if env_flag("CODEX_DANGEROUS_BYPASS", False):
        args.append("--dangerously-bypass-approvals-and-sandbox")
    else:
        sandbox_mode = os.getenv("CODEX_SANDBOX_MODE", "workspace-write").strip() or "workspace-write"
        args.extend(["--sandbox", shlex.quote(sandbox_mode)])
        args.extend(["--add-dir", shlex.quote("{build_cache}")])
    return f'{" ".join(args)} "{{prompt}}"'


def normalize_codex_reasoning_effort(value: str, *, default: str = "medium") -> str:
    normalized = value.strip().lower()
    return normalized if normalized in {"minimal", "low", "medium", "high", "xhigh"} else default


def with_codex_reasoning_effort(args: list[str], reasoning_effort: str) -> list[str]:
    normalized_effort = normalize_codex_reasoning_effort(reasoning_effort, default="low")
    updated = list(args)
    config_value = f'model_reasoning_effort="{normalized_effort}"'
    for index in range(len(updated) - 1):
        if updated[index] != "-c":
            continue
        if updated[index + 1].split("=", 1)[0].strip() != "model_reasoning_effort":
            continue
        updated[index + 1] = config_value
        return updated

    insert_at = max(0, len(updated) - 1)
    updated[insert_at:insert_at] = ["-c", config_value]
    return updated


def sanitize_component(value: str) -> str:
    safe = "".join(
        ch if (ch.isascii() and (ch.isalnum() or ch in ("-", "_", "."))) else "_"
        for ch in value.strip()
    )
    return safe.strip("._") or "unknown"


def infer_codex_cli_binary(codex_command: str) -> str:
    try:
        args = shlex.split(codex_command)
    except ValueError:
        return detect_codex_binary()
    return args[0] if args else detect_codex_binary()


@dataclass(frozen=True)
class Settings:
    base_project_path: Path
    workspaces_root: Path
    build_cache_root: Path
    codex_command: str
    codex_timeout_seconds: Optional[int]
    server_base_url: str
    max_concurrent_codex_runs: int
    db_path: Path
    app_data_db_path: Path
    mock_codex: bool
    status_log_line_limit: int
    intent_agent_enabled: bool
    intent_agent_model: str
    intent_agent_timeout_seconds: int
    codex_existing_task_followup_enabled: bool
    codex_followup_decision_timeout_seconds: int
    codex_followup_reasoning_effort: str
    app_runtime_enabled_by_default: bool
    app_runtime_provider: str
    app_runtime_model: str
    app_runtime_api_key: str
    app_runtime_base_url: str
    app_runtime_system_prompt: str
    app_runtime_daily_request_limit: int
    app_runtime_daily_token_limit: int
    app_runtime_max_output_tokens: int
    app_runtime_temperature: float
    generated_app_keystore_path: str
    generated_app_keystore_password: str
    generated_app_key_alias: str
    generated_app_key_password: str
    shared_build_cache_enabled: bool
    admin_api_token: str


def load_settings() -> Settings:
    root = Path(__file__).resolve().parent
    mock_codex = os.getenv("MOCK_CODEX", "0") == "1"
    runtime_api_key = os.getenv("APP_RUNTIME_OPENAI_API_KEY", "").strip()
    runtime_enabled_default = env_flag("APP_RUNTIME_ENABLED", bool(runtime_api_key))
    codex_timeout_raw = int(os.getenv("CODEX_TIMEOUT_SECONDS", "0"))
    codex_timeout_seconds = codex_timeout_raw if codex_timeout_raw > 0 else None
    return Settings(
        base_project_path=resolve_path(
            os.getenv("BASE_PROJECT_PATH", ""),
            default_base_project_path(root),
            root,
        ),
        workspaces_root=resolve_path(
            os.getenv("WORKSPACES_ROOT", ""),
            root / "native_workspaces",
            root,
        ),
        build_cache_root=resolve_path(
            os.getenv("BUILD_CACHE_ROOT", ""),
            root / ".native_tooling",
            root,
        ),
        codex_command=os.getenv("CODEX_COMMAND", default_codex_command(root)),
        codex_timeout_seconds=codex_timeout_seconds,
        server_base_url=os.getenv("SERVER_BASE_URL", "http://127.0.0.1:8000").rstrip("/"),
        max_concurrent_codex_runs=max(1, int(os.getenv("MAX_CONCURRENT_CODEX_RUNS", "1"))),
        db_path=resolve_path(os.getenv("DB_PATH", ""), root / "native_tasks.db", root),
        app_data_db_path=resolve_path(
            os.getenv("APP_DATA_DB_PATH", ""),
            root / "native_app_data.db",
            root,
        ),
        mock_codex=mock_codex,
        status_log_line_limit=max(1, int(os.getenv("STATUS_LOG_LINE_LIMIT", "50"))),
        intent_agent_enabled=env_flag("INTENT_AGENT_ENABLED", not mock_codex),
        intent_agent_model=os.getenv("INTENT_AGENT_MODEL", "gpt-5.4").strip() or "gpt-5.4",
        intent_agent_timeout_seconds=max(5, int(os.getenv("INTENT_AGENT_TIMEOUT_SECONDS", "20"))),
        codex_existing_task_followup_enabled=env_flag("CODEX_EXISTING_TASK_FOLLOWUP_ENABLED", True),
        codex_followup_decision_timeout_seconds=max(
            10,
            int(os.getenv("CODEX_FOLLOWUP_DECISION_TIMEOUT_SECONDS", "90")),
        ),
        codex_followup_reasoning_effort=normalize_codex_reasoning_effort(
            os.getenv("CODEX_FOLLOWUP_REASONING_EFFORT", "low"),
            default="low",
        ),
        app_runtime_enabled_by_default=runtime_enabled_default,
        app_runtime_provider=os.getenv("APP_RUNTIME_PROVIDER", "openai").strip() or "openai",
        app_runtime_model=os.getenv("APP_RUNTIME_MODEL", "gpt-5.4-mini").strip() or "gpt-5.4-mini",
        app_runtime_api_key=runtime_api_key,
        app_runtime_base_url=(
            os.getenv("APP_RUNTIME_BASE_URL", "https://api.openai.com/v1/responses").strip()
            or "https://api.openai.com/v1/responses"
        ),
        app_runtime_system_prompt=os.getenv(
            "APP_RUNTIME_SYSTEM_PROMPT",
            "사용자가 보낸 텍스트와 이미지를 바탕으로 실용적이고 구체적인 조언을 한국어로 제공하세요. 추측은 줄이고, 관찰 가능한 내용과 실행 가능한 제안을 우선하세요.",
        ).strip(),
        app_runtime_daily_request_limit=max(
            1,
            int(os.getenv("APP_RUNTIME_DAILY_REQUEST_LIMIT", "100")),
        ),
        app_runtime_daily_token_limit=max(
            1,
            int(os.getenv("APP_RUNTIME_DAILY_TOKEN_LIMIT", "50000")),
        ),
        app_runtime_max_output_tokens=max(
            0,
            int(os.getenv("APP_RUNTIME_MAX_OUTPUT_TOKENS", "0")),
        ),
        app_runtime_temperature=float(os.getenv("APP_RUNTIME_TEMPERATURE", "0.4")),
        generated_app_keystore_path=os.getenv("GENERATED_APP_KEYSTORE_PATH", "").strip(),
        generated_app_keystore_password=os.getenv("GENERATED_APP_KEYSTORE_PASSWORD", "").strip(),
        generated_app_key_alias=os.getenv("GENERATED_APP_KEY_ALIAS", "").strip(),
        generated_app_key_password=os.getenv("GENERATED_APP_KEY_PASSWORD", "").strip(),
        shared_build_cache_enabled=env_flag("SHARED_BUILD_CACHE_ENABLED", True),
        admin_api_token=os.getenv("ADMIN_API_TOKEN", "").strip(),
    )
