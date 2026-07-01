import base64
import binascii
import json
import os
import queue
import re
import selectors
import shlex
import shutil
import sqlite3
import subprocess
import threading
import time
import uuid
from contextlib import asynccontextmanager
from dataclasses import dataclass, replace
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Optional

import httpx
from fastapi import FastAPI, Header, HTTPException, Query
from fastapi.responses import FileResponse
from pydantic import BaseModel, Field


def utc_now_iso() -> str:
    return datetime.now(timezone.utc).replace(microsecond=0).isoformat()


def utc_now_compact() -> str:
    return datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")


def resolve_path(value: str, default: Path, root: Path) -> Path:
    candidate = Path(value).expanduser() if value else default
    if not candidate.is_absolute():
        candidate = (root / candidate).resolve()
    return candidate


def default_base_project_path(root: Path) -> Path:
    local_default = root / "base_flutter_project"
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


def parse_local_properties(path: Path) -> dict[str, str]:
    if not path.exists() or not path.is_file():
        return {}
    properties: dict[str, str] = {}
    for raw_line in path.read_text(encoding="utf-8", errors="replace").splitlines():
        line = raw_line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        properties[key.strip()] = value.strip()
    return properties


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


def default_codex_add_dirs(root: Path) -> list[Path]:
    add_dirs: list[Path] = []
    seen: set[Path] = set()

    def add_path(candidate: Optional[Path]) -> None:
        if candidate is None:
            return
        resolved = candidate.expanduser().resolve()
        if resolved.exists() and resolved not in seen:
            seen.add(resolved)
            add_dirs.append(resolved)

    flutter_root_env = os.environ.get("FLUTTER_ROOT") or os.environ.get("FLUTTER_HOME")
    if flutter_root_env:
        add_path(Path(flutter_root_env) / "bin" / "cache")

    for properties_path in (
        root / "base_flutter_project" / "android" / "local.properties",
        root.parent / "BaseProject" / "android" / "local.properties",
    ):
        flutter_sdk = parse_local_properties(properties_path).get("flutter.sdk")
        if flutter_sdk:
            add_path(Path(flutter_sdk) / "bin" / "cache")

    add_path(Path.home() / "Desktop" / "flutter" / "bin" / "cache")
    return add_dirs


def default_codex_command(root: Path) -> str:
    args = [
        shlex.quote(detect_codex_binary()),
        "exec",
        "--skip-git-repo-check",
        "--json",
    ]
    reasoning_effort = (os.getenv("CODEX_REASONING_EFFORT", "default").strip().lower() or "default")
    if reasoning_effort not in {"default", "medium"}:
        args.extend(["--reasoning-effort", shlex.quote(reasoning_effort)])
    if env_flag("CODEX_DANGEROUS_BYPASS", True):
        args.append("--dangerously-bypass-approvals-and-sandbox")
    else:
        args.extend(
            [
                "--sandbox",
                shlex.quote(os.getenv("CODEX_SANDBOX_MODE", "danger-full-access").strip() or "danger-full-access"),
            ]
        )
    for add_dir in default_codex_add_dirs(root):
        args.extend(["--add-dir", shlex.quote(str(add_dir))])
    return f'{" ".join(args)} "{{prompt}}"'


def default_flutter_command(root: Path) -> str:
    flutter_command = os.getenv("FLUTTER_COMMAND", "").strip()
    if flutter_command:
        return flutter_command

    flutter_root_env = os.environ.get("FLUTTER_ROOT") or os.environ.get("FLUTTER_HOME")
    if flutter_root_env:
        flutter_bin = Path(flutter_root_env).expanduser() / "bin" / "flutter"
        if flutter_bin.exists():
            return str(flutter_bin)

    for properties_path in (
        root / "base_flutter_project" / "android" / "local.properties",
        root.parent / "BaseProject" / "android" / "local.properties",
    ):
        flutter_sdk = parse_local_properties(properties_path).get("flutter.sdk")
        if flutter_sdk:
            flutter_bin = Path(flutter_sdk).expanduser() / "bin" / "flutter"
            if flutter_bin.exists():
                return str(flutter_bin)

    fallback_flutter = Path.home() / "Desktop" / "flutter" / "bin" / "flutter"
    if fallback_flutter.exists():
        return str(fallback_flutter)
    return "flutter"


def sanitize_component(value: str) -> str:
    safe = "".join(
        ch if (ch.isascii() and (ch.isalnum() or ch in ("-", "_", "."))) else "_"
        for ch in value.strip()
    )
    safe = safe.strip("._")
    return safe or "unknown"


def infer_codex_cli_binary(codex_command: str) -> str:
    try:
        args = shlex.split(codex_command)
    except ValueError:
        return detect_codex_binary()
    if not args:
        return detect_codex_binary()
    return args[0]


def read_codex_auth_access_token(home_path: Optional[Path] = None) -> Optional[str]:
    resolved_home = home_path or Path.home()
    auth_path = resolved_home / ".codex" / "auth.json"
    if not auth_path.exists():
        return None
    try:
        payload = json.loads(auth_path.read_text(encoding="utf-8"))
    except (json.JSONDecodeError, OSError):
        return None
    tokens = payload.get("tokens")
    if not isinstance(tokens, dict):
        return None
    access_token = tokens.get("access_token")
    if not isinstance(access_token, str) or not access_token.strip():
        return None
    return access_token.strip()
def parse_backend_rate_limit_window(payload: Any) -> Optional["CodexRateLimitWindow"]:
    if not isinstance(payload, dict):
        return None
    used_percent = payload.get("used_percent")
    if used_percent is None:
        return None
    try:
        used_percent_value = int(used_percent)
    except (TypeError, ValueError):
        return None
    window_seconds = payload.get("limit_window_seconds")
    reset_at = payload.get("reset_at")
    return CodexRateLimitWindow(
        used_percent=used_percent_value,
        window_duration_mins=int(window_seconds // 60) if isinstance(window_seconds, (int, float)) else None,
        resets_at=int(reset_at) if isinstance(reset_at, (int, float)) else None,
    )


def parse_backend_rate_limits_payload(payload: Any) -> "CodexRateLimitSnapshot":
    if not isinstance(payload, dict):
        raise RuntimeError("usage 응답이 JSON 객체가 아닙니다.")
    rate_limit = payload.get("rate_limit")
    if not isinstance(rate_limit, dict):
        raise RuntimeError("usage 응답에 rate_limit 필드가 없습니다.")
    return CodexRateLimitSnapshot(
        limit_name="codex",
        primary=parse_backend_rate_limit_window(rate_limit.get("primary_window")),
        secondary=parse_backend_rate_limit_window(rate_limit.get("secondary_window")),
    )


def format_duration_korean(total_seconds: int) -> str:
    seconds = max(0, int(total_seconds))
    days, remainder = divmod(seconds, 86400)
    hours, remainder = divmod(remainder, 3600)
    minutes, remainder = divmod(remainder, 60)
    parts: list[str] = []
    if days:
        parts.append(f"{days}일")
    if hours:
        parts.append(f"{hours}시간")
    if minutes:
        parts.append(f"{minutes}분")
    if not parts:
        parts.append(f"{remainder}초")
    return " ".join(parts[:2])


def format_rate_limit_window_korean(label: str, window: Optional["CodexRateLimitWindow"], *, now_ts: Optional[int] = None) -> str:
    if window is None:
        return f"{label} 정보 없음"
    remaining_percent = max(0, 100 - int(window.used_percent))
    current_ts = int(now_ts if now_ts is not None else time.time())
    if window.resets_at is None:
        reset_text = "초기화 시각 미상"
    else:
        reset_text = f"{format_duration_korean(max(0, window.resets_at - current_ts))} 후 초기화"
    return f"{label} 잔여 {remaining_percent}% (사용 {window.used_percent}%, {reset_text})"


def format_codex_rate_limit_summary(snapshot: "CodexRateLimitSnapshot", *, now_ts: Optional[int] = None) -> str:
    return " | ".join(
        [
            format_rate_limit_window_korean("5시간 한도", snapshot.primary, now_ts=now_ts),
            format_rate_limit_window_korean("주간 한도", snapshot.secondary, now_ts=now_ts),
        ]
    )


CODEX_ENGINE_CONTACT_MESSAGE = (
    "앱 생성 서버의 작업 엔진이 요청을 완료하지 못했어요. "
    "같은 문제가 반복되면 담당자 이정민(010-8187-6512)에게 알려주세요."
)
CODEX_ENGINE_AUTH_MESSAGE = (
    "앱 생성 서버의 작업 엔진 인증이 만료되어 요청을 진행하지 못했어요. "
    "담당자 이정민(010-8187-6512)에게 알려주세요."
)
CODEX_ENGINE_QUOTA_MESSAGE = (
    "현재 앱 생성 작업 엔진의 사용 한도가 초과되어 요청을 진행하지 못했어요. "
    "잠시 후 다시 시도하거나 담당자 이정민(010-8187-6512)에게 알려주세요."
)


def looks_like_codex_quota_error(text: str) -> bool:
    normalized = text.lower()
    if not normalized.strip():
        return False
    markers = (
        "rate limit",
        "rate_limit",
        "quota",
        "usage limit",
        "limit exceeded",
        "too many requests",
        "429",
        "한도 초과",
        "사용 한도",
        "요청 한도",
        "호출량 초과",
        "사용량 한도",
        "사용량 초과",
        "쿼터",
    )
    return any(marker in normalized for marker in markers)


def looks_like_codex_auth_error(text: str) -> bool:
    normalized = text.lower()
    if not normalized.strip():
        return False
    markers = (
        "not logged in",
        "not authenticated",
        "authentication required",
        "auth required",
        "please login",
        "please log in",
        "login required",
        "please sign in",
        "sign in to",
        "unauthorized",
        "401",
        "token expired",
        "expired token",
        "invalid token",
        "access token expired",
        "no auth credentials",
        "codex login",
        "인증이 만료",
        "로그인이 필요",
        "로그인 만료",
        "인증 필요",
        "인증 실패",
    )
    return any(marker in normalized for marker in markers)


def codex_engine_issue_from_logs(
    log_text: str,
    exit_code: Optional[int],
) -> Optional[tuple[str, str, str, str]]:
    if looks_like_codex_quota_error(log_text):
        return (
            "RateLimited",
            CODEX_ENGINE_QUOTA_MESSAGE,
            "codex_quota_exceeded",
            "앱 생성 한도",
        )
    if looks_like_codex_auth_error(log_text):
        return (
            "Error",
            CODEX_ENGINE_AUTH_MESSAGE,
            "codex_auth_error",
            "앱 생성 작업 엔진",
        )
    if exit_code not in (0, None):
        return (
            "Error",
            CODEX_ENGINE_CONTACT_MESSAGE,
            "codex_engine_error",
            "앱 생성 작업 엔진",
        )
    return None


def _read_jsonrpc_result(process: subprocess.Popen[str], request_id: int, timeout_seconds: float) -> Any:
    if process.stdout is None or process.stderr is None:
        raise RuntimeError("Codex app-server 표준 입출력을 열지 못했습니다.")
    selector = selectors.DefaultSelector()
    selector.register(process.stdout, selectors.EVENT_READ, data="stdout")
    selector.register(process.stderr, selectors.EVENT_READ, data="stderr")
    stderr_lines: list[str] = []
    deadline = time.monotonic() + timeout_seconds
    try:
        while True:
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                break
            events = selector.select(timeout=remaining)
            if not events and process.poll() is not None:
                break
            for key, _ in events:
                line = key.fileobj.readline()
                if not line:
                    continue
                if key.data == "stderr":
                    stderr_lines.append(line.strip())
                    continue
                stripped = line.strip()
                if not stripped:
                    continue
                try:
                    payload = json.loads(stripped)
                except json.JSONDecodeError:
                    continue
                if payload.get("id") != request_id:
                    continue
                if "error" in payload:
                    error_payload = payload.get("error") or {}
                    message = str(error_payload.get("message") or "알 수 없는 오류")
                    raise RuntimeError(message)
                return payload.get("result")
    finally:
        selector.close()
    stderr_text = " | ".join(part for part in stderr_lines if part)
    if stderr_text:
        raise RuntimeError(f"응답 시간 초과 또는 종료됨: {stderr_text}")
    raise RuntimeError("응답 시간 초과 또는 종료됨")


def fetch_codex_rate_limits_via_app_server(
    codex_binary: str,
    timeout_seconds: float = 20.0,
    *,
    env: Optional[dict[str, str]] = None,
) -> "CodexRateLimitSnapshot":
    process = subprocess.Popen(
        [codex_binary, "app-server", "--listen", "stdio://"],
        stdin=subprocess.PIPE,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
        bufsize=1,
        env=env,
    )
    try:
        if process.stdin is None:
            raise RuntimeError("Codex app-server 표준 입력을 열지 못했습니다.")
        process.stdin.write(
            json.dumps(
                {
                    "jsonrpc": "2.0",
                    "id": 1,
                    "method": "initialize",
                    "params": {"clientInfo": {"name": "flutter_apk_server", "version": "1.0"}},
                },
                ensure_ascii=False,
            )
            + "\n"
        )
        process.stdin.flush()
        _read_jsonrpc_result(process, 1, min(timeout_seconds, 5.0))
        process.stdin.write(
            json.dumps(
                {
                    "jsonrpc": "2.0",
                    "id": 2,
                    "method": "account/rateLimits/read",
                },
                ensure_ascii=False,
            )
            + "\n"
        )
        process.stdin.flush()
        result = _read_jsonrpc_result(process, 2, timeout_seconds)
        if not isinstance(result, dict):
            raise RuntimeError("account/rateLimits/read 응답이 JSON 객체가 아닙니다.")

        def parse_window(payload: Any) -> Optional["CodexRateLimitWindow"]:
            if not isinstance(payload, dict):
                return None
            used_percent = payload.get("usedPercent")
            if used_percent is None:
                return None
            try:
                used_percent_value = int(used_percent)
            except (TypeError, ValueError):
                return None
            window_duration_mins = payload.get("windowDurationMins")
            resets_at = payload.get("resetsAt")
            return CodexRateLimitWindow(
                used_percent=used_percent_value,
                window_duration_mins=int(window_duration_mins) if isinstance(window_duration_mins, (int, float)) else None,
                resets_at=int(resets_at) if isinstance(resets_at, (int, float)) else None,
            )

        snapshot_payload = result.get("rateLimits")
        if not isinstance(snapshot_payload, dict):
            by_limit_id = result.get("rateLimitsByLimitId")
            if isinstance(by_limit_id, dict):
                snapshot_payload = by_limit_id.get("codex")
        if not isinstance(snapshot_payload, dict):
            raise RuntimeError("Codex rate limit snapshot이 없습니다.")
        return CodexRateLimitSnapshot(
            limit_name=str(snapshot_payload.get("limitName") or snapshot_payload.get("limitId") or "codex"),
            primary=parse_window(snapshot_payload.get("primary")),
            secondary=parse_window(snapshot_payload.get("secondary")),
        )
    finally:
        try:
            process.terminate()
            process.wait(timeout=2)
        except Exception:
            try:
                process.kill()
                process.wait(timeout=2)
            except Exception:
                pass


def fetch_codex_rate_limits_via_backend(timeout_seconds: float = 20.0, *, home_path: Optional[Path] = None) -> "CodexRateLimitSnapshot":
    access_token = read_codex_auth_access_token(home_path)
    if not access_token:
        raise RuntimeError("~/.codex/auth.json에서 access_token을 찾지 못했습니다.")
    response = httpx.get(
        "https://chatgpt.com/backend-api/wham/usage",
        headers={
            "Authorization": f"Bearer {access_token}",
            "Accept": "application/json",
            "User-Agent": "flutter_apk_server/codex-limit-probe",
        },
        timeout=timeout_seconds,
    )
    response.raise_for_status()
    return parse_backend_rate_limits_payload(response.json())


def fetch_codex_rate_limits(
    codex_command: str,
    timeout_seconds: float = 20.0,
    *,
    env: Optional[dict[str, str]] = None,
    home_path: Optional[Path] = None,
) -> "CodexRateLimitSnapshot":
    app_server_error: Optional[Exception] = None
    codex_binary = infer_codex_cli_binary(codex_command)
    try:
        return fetch_codex_rate_limits_via_app_server(codex_binary, timeout_seconds=timeout_seconds, env=env)
    except Exception as exc:
        app_server_error = exc
    try:
        return fetch_codex_rate_limits_via_backend(timeout_seconds=timeout_seconds, home_path=home_path)
    except Exception as backend_exc:
        if app_server_error is not None:
            raise RuntimeError(f"app-server: {app_server_error}; backend: {backend_exc}") from backend_exc
        raise


def log_codex_rate_limits_to_server_log(
    task_id: str,
    codex_command: str,
    *,
    env: Optional[dict[str, str]] = None,
    home_path: Optional[Path] = None,
) -> None:
    try:
        snapshot = fetch_codex_rate_limits(codex_command, env=env, home_path=home_path)
        print(f"[codex-limit] task_id={task_id} {format_codex_rate_limit_summary(snapshot)}", flush=True)
    except Exception as exc:
        print(f"[codex-limit] task_id={task_id} 한도 조회 실패: {exc}", flush=True)


def ensure_within_root(path: Path, root: Path) -> bool:
    try:
        path.resolve().relative_to(root.resolve())
        return True
    except ValueError:
        return False


def resolve_workspace_path(workspace_root: Path, candidate: str) -> Path:
    path = Path(candidate)
    resolved = path.resolve() if path.is_absolute() else (workspace_root / path).resolve()
    if not ensure_within_root(resolved, workspace_root):
        raise ValueError("path escapes workspace")
    return resolved


def resolve_task_artifact_path(workspace_root: Path, candidate: str, project_root: Optional[Path] = None) -> Path:
    path = Path(candidate)
    if path.is_absolute():
        resolved = path.resolve()
        if not ensure_within_root(resolved, workspace_root):
            raise ValueError("path escapes workspace")
        return resolved

    candidates: list[Path] = []
    if project_root is not None:
        project_root = project_root.resolve()
        candidates.append((project_root.parent / path).resolve())
        candidates.append((project_root / path).resolve())
    candidates.append((workspace_root / path).resolve())

    seen: set[Path] = set()
    fallback: Optional[Path] = None
    for candidate_path in candidates:
        if candidate_path in seen:
            continue
        seen.add(candidate_path)
        if not ensure_within_root(candidate_path, workspace_root):
            continue
        if fallback is None:
            fallback = candidate_path
        if candidate_path.exists():
            return candidate_path

    if fallback is None:
        raise ValueError("path escapes workspace")
    return fallback


def normalize_reference_image_name(value: Optional[str]) -> str:
    normalized = normalize_whitespace(str(value or ""))
    if not normalized:
        return ""
    normalized = normalized.replace("\\", "/").split("/")[-1].strip()
    if not normalized:
        return ""
    return normalized[:120]


def normalize_reference_image_base64(value: Optional[str]) -> str:
    normalized = str(value or "").strip()
    if not normalized:
        return ""
    if normalized.startswith("data:"):
        _, _, normalized = normalized.partition(",")
    return "".join(normalized.split())


def infer_reference_image_suffix(reference_image_name: str) -> str:
    suffix = Path(reference_image_name).suffix.lower()
    return suffix if suffix in {".png", ".jpg", ".jpeg", ".webp", ".gif"} else ".png"


def build_reference_image_summary(reference_image_name: str) -> str:
    if not reference_image_name:
        return ""
    return f"참고 이미지 `{reference_image_name}`를 함께 전달받았어요. 앱 구조, UI, 스타일, 콘텐츠 맥락을 이 이미지를 참고해 해석합니다."


def save_reference_image_attachment(
    workspace_root: Path,
    *,
    reference_image_name: str,
    reference_image_base64: str,
) -> Optional[str]:
    normalized_name = normalize_reference_image_name(reference_image_name)
    normalized_base64 = normalize_reference_image_base64(reference_image_base64)
    if not normalized_name or not normalized_base64:
        return None
    try:
        image_bytes = base64.b64decode(normalized_base64, validate=False)
    except (ValueError, binascii.Error):
        return None
    if not image_bytes:
        return None

    image_dir = workspace_root / "reference_images"
    image_dir.mkdir(parents=True, exist_ok=True)
    suffix = infer_reference_image_suffix(normalized_name)
    safe_stem = sanitize_component(Path(normalized_name).stem or "reference_image")
    filename = f"{utc_now_compact()}_{safe_stem}{suffix}"
    image_path = image_dir / filename
    image_path.write_bytes(image_bytes)
    return str(image_path.relative_to(workspace_root))


def read_text_if_exists(path: Path, limit: Optional[int] = 20000) -> str:
    if not path.exists() or not path.is_file():
        return ""
    text = path.read_text(encoding="utf-8", errors="replace")
    if limit is None or limit <= 0:
        return text
    return text[-limit:]


def extract_codex_agent_message_jsonl(path: Path, max_messages: Optional[int] = 120) -> str:
    if not path.exists() or not path.is_file():
        return ""
    messages: list[str] = []
    with path.open("r", encoding="utf-8", errors="replace") as handle:
        for raw_line in handle:
            line = raw_line.strip()
            if not line.startswith("{") or not line.endswith("}"):
                continue
            try:
                payload = json.loads(line)
            except json.JSONDecodeError:
                continue
            if payload.get("type") != "item.completed":
                continue
            item = payload.get("item")
            if not isinstance(item, dict) or item.get("type") != "agent_message":
                continue
            messages.append(line)
            if max_messages is not None and max_messages > 0 and len(messages) > max_messages:
                messages = messages[-max_messages:]
    return "\n".join(messages)


def tail_lines(text: str, limit: int) -> list[str]:
    if not text:
        return []
    lines = [line for line in text.splitlines() if line.strip()]
    return lines[-limit:]


def sanitize_user_visible_text(text: str) -> str:
    if not text:
        return ""
    sanitized = text.replace("\r\n", "\n").replace("\r", "\n")
    sanitized = re.sub(r"(?i)codex", "작업 엔진", sanitized)
    sanitized = re.sub(r"logs/작업 엔진_stdout\.log", "작업 표준 출력 로그", sanitized)
    sanitized = re.sub(r"logs/작업 엔진_stderr\.log", "작업 오류 출력 로그", sanitized)
    return sanitized


@dataclass(frozen=True)
class Settings:
    base_project_path: Path
    workspaces_root: Path
    codex_command: str
    flutter_command: str
    codex_timeout_seconds: Optional[int]
    server_base_url: str
    max_concurrent_codex_runs: int
    db_path: Path
    mock_codex: bool
    status_log_line_limit: int
    intent_agent_enabled: bool
    intent_agent_model: str
    intent_agent_timeout_seconds: int
    codex_existing_task_followup_enabled: bool
    codex_followup_decision_timeout_seconds: int
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
            root / "workspaces",
            root,
        ),
        codex_command=os.getenv("CODEX_COMMAND", default_codex_command(root)),
        flutter_command=default_flutter_command(root),
        codex_timeout_seconds=codex_timeout_seconds,
        server_base_url=os.getenv("SERVER_BASE_URL", "http://127.0.0.1:8000").rstrip("/"),
        max_concurrent_codex_runs=max(1, int(os.getenv("MAX_CONCURRENT_CODEX_RUNS", "1"))),
        db_path=resolve_path(
            os.getenv("DB_PATH", ""),
            root / "tasks.db",
            root,
        ),
        mock_codex=mock_codex,
        status_log_line_limit=max(1, int(os.getenv("STATUS_LOG_LINE_LIMIT", "50"))),
        intent_agent_enabled=env_flag("INTENT_AGENT_ENABLED", not mock_codex),
        intent_agent_model=os.getenv("INTENT_AGENT_MODEL", "gpt-5.4").strip() or "gpt-5.4",
        intent_agent_timeout_seconds=max(5, int(os.getenv("INTENT_AGENT_TIMEOUT_SECONDS", "20"))),
        codex_existing_task_followup_enabled=env_flag("CODEX_EXISTING_TASK_FOLLOWUP_ENABLED", True),
        codex_followup_decision_timeout_seconds=max(10, int(os.getenv("CODEX_FOLLOWUP_DECISION_TIMEOUT_SECONDS", "90"))),
        app_runtime_enabled_by_default=runtime_enabled_default,
        app_runtime_provider=os.getenv("APP_RUNTIME_PROVIDER", "openai").strip() or "openai",
        app_runtime_model=os.getenv("APP_RUNTIME_MODEL", "gpt-5.4-mini").strip() or "gpt-5.4-mini",
        app_runtime_api_key=runtime_api_key,
        app_runtime_base_url=os.getenv("APP_RUNTIME_BASE_URL", "https://api.openai.com/v1/responses").strip() or "https://api.openai.com/v1/responses",
        app_runtime_system_prompt=os.getenv(
            "APP_RUNTIME_SYSTEM_PROMPT",
            "사용자가 보낸 텍스트와 이미지를 바탕으로 실용적이고 구체적인 조언을 한국어로 제공하세요. 추측은 줄이고, 관찰 가능한 내용과 실행 가능한 제안을 우선하세요.",
        ).strip(),
        app_runtime_daily_request_limit=max(1, int(os.getenv("APP_RUNTIME_DAILY_REQUEST_LIMIT", "100"))),
        app_runtime_daily_token_limit=max(1, int(os.getenv("APP_RUNTIME_DAILY_TOKEN_LIMIT", "50000"))),
        app_runtime_max_output_tokens=max(64, int(os.getenv("APP_RUNTIME_MAX_OUTPUT_TOKENS", "700"))),
        app_runtime_temperature=float(os.getenv("APP_RUNTIME_TEMPERATURE", "0.4")),
        admin_api_token=os.getenv("ADMIN_API_TOKEN", "").strip(),
    )


@dataclass(frozen=True)
class CodexRateLimitWindow:
    used_percent: int
    window_duration_mins: Optional[int]
    resets_at: Optional[int]


@dataclass(frozen=True)
class CodexRateLimitSnapshot:
    limit_name: Optional[str]
    primary: Optional[CodexRateLimitWindow]
    secondary: Optional[CodexRateLimitWindow]


@dataclass(frozen=True)
class IntentDecision:
    mode: str
    status: str
    tool: str
    message: str
    summary: str
    questions: list[str]
    reason: str
    request_scope: str
    requires_existing_task_context: bool
    app_name: str
    package_name: str
    normalized_prompt: str
    feature_points: list[str]
    primary_user_flow: str
    secondary_requirements: list[str]
    secondary_scope_confirmed: bool
    acceptance_criteria: list[str]
    effective_user_prompt: str
    used_previous_pending_prompt: bool
    confirmation_action: str = ""
    confirmation_payload: str = ""
    image_reference_summary: str = ""
    image_conflict_note: str = ""


@dataclass(frozen=True)
class CodexUsage:
    input_tokens: int
    cached_input_tokens: int
    output_tokens: int
    reasoning_output_tokens: int
    total_tokens: int


@dataclass(frozen=True)
class TaskUsageRecord:
    source: str
    model: str
    input_tokens: Optional[int]
    cached_input_tokens: Optional[int]
    output_tokens: Optional[int]
    cached_output_tokens: Optional[int]
    reasoning_output_tokens: Optional[int]
    total_tokens: Optional[int]
    status: str
    raw_output_text: str = ""
    payload: Optional[dict[str, Any]] = None


class DeviceInfoPayload(BaseModel):
    model: str = Field(..., min_length=1)
    sdk: int = Field(..., ge=1)
    width: int = Field(..., ge=1)
    height: int = Field(..., ge=1)
    sensors: list[str] = Field(default_factory=list)


def normalize_whitespace(value: str) -> str:
    return re.sub(r"\s+", " ", value).strip()


def normalize_task_app_name(value: str) -> str:
    return normalize_whitespace(value)[:80].strip()


def contains_korean_text(value: str) -> bool:
    return bool(re.search(r"[가-힣]", value))


def korean_text_or_fallback(value: str, fallback: str) -> str:
    normalized = normalize_whitespace(value)
    return normalized if contains_korean_text(normalized) else fallback


def serialize_device_info(device_info: Optional[DeviceInfoPayload | dict[str, Any]]) -> dict[str, Any]:
    if isinstance(device_info, DeviceInfoPayload):
        payload = device_info.dict() if hasattr(device_info, "dict") else device_info.model_dump()
    elif isinstance(device_info, dict):
        payload = dict(device_info)
    else:
        return {}

    sensors = payload.get("sensors")
    payload["sensors"] = [str(item).strip() for item in sensors] if isinstance(sensors, list) else []
    for key in ("model",):
        payload[key] = str(payload.get(key) or "").strip()
    for key in ("sdk", "width", "height"):
        try:
            payload[key] = int(payload.get(key) or 0)
        except (TypeError, ValueError):
            payload[key] = 0
    if not payload.get("model"):
        return {}
    return payload


def render_device_info_summary(device_info: Optional[dict[str, Any]]) -> str:
    info = serialize_device_info(device_info)
    if not info:
        return "(없음)"
    sensor_count = len(info.get("sensors") or [])
    return f"{info.get('model')} / Android SDK {info.get('sdk')} / {info.get('width')}x{info.get('height')} / sensors {sensor_count}개"


def extract_response_output_text(payload: dict[str, Any]) -> str:
    output_text = payload.get("output_text")
    if isinstance(output_text, str) and output_text.strip():
        return output_text.strip()

    output_items = payload.get("output")
    if not isinstance(output_items, list):
        return ""

    for item in output_items:
        if not isinstance(item, dict) or item.get("type") != "message":
            continue
        content_items = item.get("content")
        if not isinstance(content_items, list):
            continue
        for content_item in content_items:
            if not isinstance(content_item, dict):
                continue
            if content_item.get("type") == "output_text":
                text = content_item.get("text")
                if isinstance(text, str) and text.strip():
                    return text.strip()
    return ""


def parse_response_usage_payload(payload: dict[str, Any]) -> dict[str, Optional[int]]:
    usage_payload = payload.get("usage") if isinstance(payload.get("usage"), dict) else {}
    input_details = usage_payload.get("input_tokens_details") if isinstance(usage_payload.get("input_tokens_details"), dict) else {}
    output_details = usage_payload.get("output_tokens_details") if isinstance(usage_payload.get("output_tokens_details"), dict) else {}

    def as_optional_int(value: Any) -> Optional[int]:
        if value in (None, ""):
            return None
        try:
            return int(value)
        except (TypeError, ValueError):
            return None

    input_tokens = as_optional_int(usage_payload.get("input_tokens"))
    cached_input_tokens = as_optional_int(
        input_details.get(
            "cached_tokens",
            input_details.get("cached_input_tokens", usage_payload.get("cached_input_tokens")),
        )
    )
    output_tokens = as_optional_int(usage_payload.get("output_tokens"))
    cached_output_tokens = as_optional_int(
        output_details.get("cached_tokens", output_details.get("cached_output_tokens", usage_payload.get("cached_output_tokens")))
    )
    reasoning_output_tokens = as_optional_int(
        output_details.get(
            "reasoning_tokens",
            output_details.get("reasoning_output_tokens", usage_payload.get("reasoning_output_tokens")),
        )
    )
    total_tokens = as_optional_int(usage_payload.get("total_tokens"))
    if total_tokens is None and (input_tokens is not None or output_tokens is not None):
        total_tokens = int(input_tokens or 0) + int(output_tokens or 0)
    return {
        "input_tokens": input_tokens,
        "cached_input_tokens": cached_input_tokens,
        "output_tokens": output_tokens,
        "cached_output_tokens": cached_output_tokens,
        "reasoning_output_tokens": reasoning_output_tokens,
        "total_tokens": total_tokens,
    }


def slugify_package_segment(value: str) -> str:
    cleaned = re.sub(r"[^a-z0-9]+", ".", value.lower())
    cleaned = cleaned.strip(".")
    segments = [segment for segment in cleaned.split(".") if segment]
    if not segments:
        return "customapp"
    if segments[0][0].isdigit():
        segments[0] = f"app{segments[0]}"
    return ".".join(segments[:4])


def extract_explicit_app_name(prompt: str) -> str:
    patterns = (
        r'[\"“”\'"]([^"\n]{2,40}?)[\"“”\'"]\s*(?:앱|어플|application)',
        r'(?:앱 이름|애플리케이션 이름|서비스 이름)[은는:\s]+([A-Za-z0-9가-힣 _-]{2,40})',
        r'([A-Za-z0-9가-힣 _-]{2,30})\s*(?:앱|어플)(?:을|를|으로|로)?',
    )
    for pattern in patterns:
        match = re.search(pattern, prompt, re.IGNORECASE)
        if not match:
            continue
        candidate = normalize_whitespace(match.group(1))
        if any(token in candidate for token in ("만들", "정리", "대화", "수 있는", "기능", "화면", "추가", "변경")):
            continue
        if candidate and len(candidate) <= 24:
            return candidate
    return ""


def normalize_app_topic_candidate(value: str) -> str:
    candidate = normalize_whitespace(value)
    if not candidate:
        return ""
    candidate = re.sub(r"\b(json|yaml|schema|output|agent|prompt)\b", "", candidate, flags=re.IGNORECASE)
    candidate = re.sub(r"(다른\s*agent.*$|다른\s*에이전트.*$|json.*$|yaml.*$|스키마.*$|포맷.*$)", "", candidate, flags=re.IGNORECASE)
    candidate = re.sub(r"(앱|어플|application|서비스|프로그램)(을|를|으로|로)?", "", candidate, flags=re.IGNORECASE)
    candidate = re.sub(r"(만들어줘|생성해줘|개발해줘|구현해줘|빌드해줘|정리해줘|추천해줘|도와줘)$", "", candidate)
    candidate = re.sub(r"^(사용자가|유저가|사진을|사진으로|이미지로)\s+", "", candidate)
    candidate = candidate.strip(" .,!?:;-")
    return normalize_whitespace(candidate)


def infer_descriptive_app_name(prompt: str) -> str:
    raw_parts = re.split(r"[\n\r]+|[.?!]| 그리고 |, 그리고 |, 또한 | 및 |,", prompt)
    for raw_part in raw_parts:
        candidate = normalize_app_topic_candidate(raw_part)
        if not candidate:
            continue
        if len(candidate) < 2:
            continue
        if any(token in candidate for token in ("요청", "명세", "형식", "출력", "전달")):
            continue
        if len(candidate) <= 14:
            return candidate
        shortened = candidate[:14].strip()
        if shortened:
            return shortened
    return ""


def infer_app_name(prompt: str) -> str:
    explicit = extract_explicit_app_name(prompt)
    if explicit:
        return explicit
    descriptive = infer_descriptive_app_name(prompt)
    if descriptive:
        return descriptive
    return "요청 앱"


def infer_package_name(app_name: str, task_id: str) -> str:
    slug = slugify_package_segment(app_name)
    task_suffix = re.sub(r"[^a-z0-9]+", "", task_id[:8].lower()) or "task"
    segments = [segment for segment in slug.split(".") if segment]
    if not segments:
        segments = ["customapp"]
    segments[-1] = f"{segments[-1]}{task_suffix}"
    return f"kr.ac.kangwon.hai.generated.{'.'.join(segments[:4])}"


def extract_feature_points(prompt: str) -> list[str]:
    raw_parts = re.split(r"[\n\r]+|[.?!]| 그리고 |, 그리고 |, 또한 | 및 ", prompt)
    features: list[str] = []
    seen: set[str] = set()
    for raw_part in raw_parts:
        part = normalize_whitespace(raw_part)
        if len(part) < 4:
            continue
        if part in seen:
            continue
        seen.add(part)
        features.append(part)
    if not features:
        return [normalize_whitespace(prompt)]
    return features[:6]


def looks_like_question(prompt: str) -> bool:
    lowered = prompt.lower()
    return (
        "?" in prompt
        or prompt.strip().endswith("요")
        and any(token in lowered for token in ("가능", "어떻게", "왜", "무엇", "설명", "차이", "추천", "알려"))
        or any(token in lowered for token in ("가능해", "될까", "어떻게", "왜", "무엇", "설명해", "알려줘", "추천해"))
    )


def looks_like_build_request(prompt: str, existing_task: bool) -> bool:
    lowered = prompt.lower()
    build_tokens = (
        "앱", "어플", "application", "apk", "flutter", "안드로이드", "android",
        "만들", "생성", "구현", "개발", "빌드", "수정", "추가", "변경", "화면", "기능", "디자인", "ui"
    )
    if any(token in lowered for token in build_tokens):
        return True
    if existing_task and any(token in lowered for token in ("바꿔", "고쳐", "수정", "추가", "다시", "반영", "변경")):
        return True
    return False


def looks_like_existing_app_reference(prompt: str) -> bool:
    lowered = prompt.lower()
    explicit_tokens = (
        "기존 앱", "기존 어플", "이전 앱", "저번 앱", "아까 만든 앱", "전에 만든 앱",
        "이미 만든 앱", "만들어둔 앱", "기존 작업", "이 앱 수정", "그 앱 수정", "업데이트해줘",
    )
    if any(token in lowered for token in explicit_tokens):
        return True
    return (
        "수정" in lowered or "변경" in lowered or "추가" in lowered or "업데이트" in lowered
    ) and any(token in lowered for token in ("기존", "이전", "저번", "아까", "이미 만든", "만들어둔", "그 앱", "이 앱"))


def looks_like_generic_confirmation(prompt: str) -> bool:
    normalized = normalize_whitespace(prompt).lower()
    generic_confirmations = {
        "네",
        "예",
        "응",
        "어",
        "어.",
        "좋아",
        "좋습니다",
        "진행",
        "계속",
        "시작",
        "시작해줘",
        "진행해줘",
        "계속해줘",
        "네 진행해줘",
        "네 시작해줘",
        "네, 진행해줘",
        "네, 시작해줘",
        "네 이 내용으로 앱 생성을 시작해줘",
        "네, 이 내용으로 앱 생성을 시작해줘",
    }
    return normalized in generic_confirmations


def looks_like_runtime_repair_request(prompt: str) -> bool:
    normalized = normalize_whitespace(prompt)
    lowered = normalized.lower()
    return (
        "런타임 오류" in normalized
        and "stack_trace:" in lowered
        and "package_name:" in lowered
        and "error_summary:" in lowered
    )


def looks_like_structured_agent_spec_prompt(prompt: str) -> bool:
    normalized = normalize_whitespace(prompt)
    lowered = normalized.lower()
    if looks_like_question(prompt):
        return False
    structure_markers = (
        "json", "yaml", "output", "출력", "포맷", "형식", "schema", "스키마",
        "agent", "에이전트", "전달", "프롬프트", "system prompt", "instruction",
    )
    if not any(marker in lowered for marker in structure_markers):
        return False
    app_spec_markers = (
        "앱", "어플", "application", "apk", "flutter", "안드로이드", "android",
        "기능", "화면", "로그인", "저장", "통계", "기록", "gps", "사진", "메모",
        "체크리스트", "카메라", "지도", "타이머", "가계부", "일정", "운동",
    )
    has_feature_shape = any(token in normalized for token in ("\n", "-", "•", ","))
    return (
        looks_like_build_request(prompt, existing_task=False)
        or has_feature_shape
        or any(marker in lowered for marker in app_spec_markers)
    )


def decision_ui_flags(decision: "IntentDecision") -> dict[str, Any]:
    confirmation_pending = bool(decision.confirmation_action)
    if decision.mode == "build":
        return {
            "interaction_type": "build_started",
            "render_mode": "status_only",
            "requires_user_input": False,
            "requires_confirmation": False,
            "pending_decision_reason": "",
            "suppress_assistant_bubble": True,
        }
    if decision.mode == "ask_confirmation" and confirmation_pending:
        return {
            "interaction_type": "needs_prebuild_confirmation",
            "render_mode": "confirmation_bubble",
            "requires_user_input": False,
            "requires_confirmation": True,
            "pending_decision_reason": "prebuild_confirmation",
            "suppress_assistant_bubble": True,
        }
    if decision.mode == "ask_confirmation":
        return {
            "interaction_type": "needs_clarification",
            "render_mode": "assistant_message",
            "requires_user_input": True,
            "requires_confirmation": False,
            "pending_decision_reason": "clarification",
            "suppress_assistant_bubble": False,
        }
    return {
        "interaction_type": "answer_only",
        "render_mode": "assistant_message",
        "requires_user_input": False,
        "requires_confirmation": False,
        "pending_decision_reason": "",
        "suppress_assistant_bubble": False,
    }


def questions_accept_generic_confirmation(questions: list[str]) -> bool:
    confirmation_markers = (
        "진행할까요",
        "진행할까",
        "계속할까요",
        "계속할까",
        "시작할까요",
        "시작할까",
        "빼고 진행할까요",
        "제외하고 진행할까요",
        "괜찮을까요",
    )
    return any(any(marker in question for marker in confirmation_markers) for question in questions)


def make_answer_message(prompt: str) -> str:
    normalized = normalize_whitespace(prompt)
    if "무엇을 할 수" in normalized or "뭐가 가능" in normalized:
        return "이 서버는 Flutter Android 앱 생성을 위한 작업을 처리해요. 원하는 화면, 기능, 앱 분위기를 말해주면 실제 APK 빌드까지 이어갈 수 있어요."
    return "이 메시지는 바로 앱 빌드로 보내기보다 먼저 대화로 정리하는 편이 좋아 보여요. 원하는 앱의 목적, 핵심 화면, 꼭 필요한 기능을 조금 더 구체적으로 알려주세요."


def detect_unsupported_android_request(prompt: str) -> Optional[str]:
    normalized = normalize_whitespace(prompt)
    lowered = normalized.lower()
    unsupported_rules = [
        (
            any(token in normalized for token in ("빅스비", "Bixby", "bixby"))
            and any(token in normalized for token in ("연동", "제어", "호출", "자동화", "실행", "트리거")),
            "이 요청은 일반 안드로이드 앱으로 바로 제공하기 어려워요. 빅스비와의 깊은 연동이나 자동 제어는 삼성 전용 공개 범위와 기기별 정책 영향을 크게 받아서, 일반 Flutter 앱만으로 안정적으로 구현된다고 약속하기 어렵습니다. 대신 앱 내부 음성 명령이나 자체 AI 비서 흐름처럼 안드로이드에서 확실히 동작하는 방향으로 바꾸는 게 좋습니다.",
        ),
        (
            any(token in lowered for token in ("private api", "비공개 api", "숨겨진 api", "hidden api")),
            "이 요청은 비공개 API에 의존할 가능성이 커서 일반 안드로이드 앱으로 진행하면 배포와 안정성 문제가 생길 수 있어요. 공개 SDK나 공식 연동 방식으로 바꿔서 다시 정리해 주시면 가능한 범위로 설계해볼게요.",
        ),
        (
            any(token in normalized for token in ("시스템 앱", "루팅", "root", "device owner", "디바이스 오너"))
            and any(token in normalized for token in ("권한", "필수", "필요", "전제")),
            "이 요청은 일반 사용자용 안드로이드 앱보다 시스템 권한이나 관리 권한이 필요한 방향에 가까워요. 그런 권한은 보통 일반 배포 앱에서 바로 쓸 수 없어서, 현재 서버가 만드는 일반 Android APK 범위로는 진행하기 어렵습니다.",
        ),
    ]
    for matched, message in unsupported_rules:
        if matched:
            return message
    return None


def revise_prompt_for_supported_android_scope(prompt: str) -> Optional[dict[str, str]]:
    normalized = normalize_whitespace(prompt)
    if any(token in normalized for token in ("빅스비", "Bixby", "bixby")) and any(
        token in normalized for token in ("연동", "제어", "호출", "자동화", "실행", "트리거")
    ):
        revised = prompt
        replacements = (
            ("제미나이, 빅스비 연동", "앱 내부 음성 입력"),
            ("빅스비 연동", "앱 내부 음성 입력"),
            ("빅스비와 연동해서", ""),
            ("빅스비와 연동하여", ""),
            ("빅스비로", "앱 내부 음성 입력으로"),
        )
        for old, new in replacements:
            revised = revised.replace(old, new)
        revised = re.sub(r"\(\s*앱 내부 음성 입력\s*\)", "(앱 내부 음성 입력)", revised)
        revised = re.sub(r"\(\s*,\s*", "(", revised)
        revised = re.sub(r"\s{2,}", " ", revised)
        revised = re.sub(r"\n{3,}", "\n\n", revised).strip()
        revised = normalize_whitespace(revised)
        if revised and revised != normalized:
            return {
                "effective_user_prompt": revised,
                "question": "빅스비 연동을 제외하고 앱 내부 음성 입력 기능으로 진행할까요?",
                "message": "빅스비 연동은 일반 안드로이드 앱 범위에서 바로 제공하기 어려워요. 대신 앱 내부 음성 입력 기능으로 바꿔서 진행할 수 있어요.",
                "summary": "지원되지 않는 연동을 제외한 명세로 진행할지 확인하고 있어요.",
                "reason": "정책상 어려운 연동만 제외하면 같은 앱 명세로 계속 진행할 수 있어요.",
            }
    return None


def build_supported_revision_confirmation_decision(
    *,
    task_id: str,
    existing_task: bool,
    existing_workspace_ready: bool,
    user_prompt: str,
    revised_prompt: str,
    question: str,
    message: str,
    summary: str,
    reason: str,
) -> IntentDecision:
    effective_prompt = normalize_whitespace(revised_prompt)
    feature_points = extract_feature_points(revised_prompt)
    acceptance_criteria = infer_acceptance_criteria(revised_prompt, feature_points)
    primary_user_flow = infer_primary_user_flow(revised_prompt, feature_points, "")
    app_name = infer_app_name(effective_prompt)
    package_name = infer_package_name(app_name, task_id)
    request_scope = "existing_app_modification" if existing_workspace_ready else "new_app"
    return IntentDecision(
        mode="ask_confirmation",
        status="Pending Decision",
        tool="ask_confirmation",
        message=message,
        summary=summary,
        questions=[question],
        reason=reason,
        request_scope=request_scope,
        requires_existing_task_context=False,
        app_name=app_name,
        package_name=package_name,
        normalized_prompt=build_normalized_prompt(
            app_name,
            package_name,
            effective_prompt,
            feature_points,
            primary_user_flow,
            [],
            acceptance_criteria,
        ),
        feature_points=feature_points,
        primary_user_flow=primary_user_flow,
        secondary_requirements=[],
        secondary_scope_confirmed=False,
        acceptance_criteria=acceptance_criteria,
        effective_user_prompt=effective_prompt,
        used_previous_pending_prompt=False,
    )


def build_clarification_questions(prompt: str) -> list[str]:
    lowered = prompt.lower()
    questions = [
        "앱 이름을 어떻게 할까요?",
        "첫 화면은 입력 중심으로 할까요, 목록이나 대시보드 중심으로 할까요?",
        "등록한 항목은 작성만 있으면 될까요, 수정과 삭제도 같이 필요할까요?",
    ]
    if "sns" in lowered or "대화" in lowered:
        questions = [
            "묶고 싶은 SNS나 메신저 종류는 무엇인가요?",
            "한 화면 통합 피드로 볼까요, 서비스별 탭으로 나눌까요?",
            "이번 버전은 보기 중심이면 될까요, 답장이나 작성 흐름도 필요할까요?",
        ]
    return questions


def build_existing_task_reference_questions() -> list[str]:
    return [
        "수정할 기존 앱 대화에서 다시 요청해 주세요.",
        "새 앱으로 진행하려면 새로 만들 앱 요구사항이라고 명확히 적어 주세요.",
    ]


def normalize_acceptance_criteria(items: Optional[list[Any]]) -> list[str]:
    criteria: list[str] = []
    seen: set[str] = set()
    for item in items or []:
        text = normalize_whitespace(str(item or ""))
        if not text:
            continue
        text = re.sub(r"^[-*•]\s*", "", text).strip()
        text = text.strip(" .,!?:;-")
        if not text or text in seen:
            continue
        seen.add(text)
        criteria.append(text)
        if len(criteria) >= 8:
            break
    return criteria


def infer_acceptance_criteria(prompt: str, feature_points: list[str]) -> list[str]:
    normalized_prompt = normalize_whitespace(prompt)
    lowered = normalized_prompt.lower()
    criteria: list[str] = []

    def append(value: str) -> None:
        text = normalize_whitespace(value)
        if not text or text in criteria:
            return
        criteria.append(text)

    for feature in feature_points[:4]:
        cleaned = normalize_whitespace(feature)
        if cleaned:
            append(f"{cleaned} 기능이 실제로 동작해야 함")

    if any(token in normalized_prompt for token in ("카메라", "촬영", "사진 찍", "사진 촬영", "스캔")):
        append("카메라 촬영 또는 이미지 선택 흐름이 실제로 동작해야 함")
    if "ocr" in lowered or any(token in normalized_prompt for token in ("문자 인식", "텍스트 추출", "영수증 인식")):
        append("OCR 또는 문자 인식 결과가 자동으로 추출되어 앱 흐름에 반영돼야 함")
    if any(token in normalized_prompt for token in ("AI", "공연 정보", "외부 정보", "추천", "분석", "조언", "상담", "요약", "분류", "불러오기", "조회")):
        append("AI 또는 외부 정보 기능은 실제 서버/API 호출로 동작해야 함")
    if any(token in normalized_prompt for token in ("저장", "기록", "히스토리", "목록 유지", "보관", "DB", "데이터 유지")):
        append("입력한 데이터가 앱을 다시 열어도 유지되도록 저장돼야 함")

    append("핵심 기능을 더미 데이터, 예시 문구, 수동 붙여넣기만으로 대체하면 안 됨")
    return criteria[:8]


def normalize_secondary_requirements(items: Optional[list[Any]]) -> list[str]:
    requirements: list[str] = []
    seen: set[str] = set()
    for item in items or []:
        text = normalize_whitespace(str(item or ""))
        if not text:
            continue
        text = re.sub(r"^[-*•]\s*", "", text).strip()
        text = text.strip(" .,!?:;-")
        if not text or text in {"없음", "없어요", "없습니다"}:
            continue
        if text in seen:
            continue
        seen.add(text)
        requirements.append(text)
        if len(requirements) >= 5:
            break
    return requirements


def infer_primary_user_flow(prompt: str, feature_points: list[str], app_name: str) -> str:
    clauses = build_summary_clauses(feature_points, app_name)
    if clauses:
        return normalize_whitespace(", ".join(clauses[:2]))
    first_line = (
        prompt.replace("\r\n", "\n")
        .replace("\r", "\n")
        .split("\n")[0]
        .strip()
    )
    return normalize_whitespace(first_line)


def build_scope_clarification_questions(
    prompt: str,
    app_name: str,
    primary_user_flow: str,
    secondary_requirements: Optional[list[str]] = None,
) -> list[str]:
    combined_text = " ".join(
        filter(
            None,
            [
                normalize_whitespace(prompt).lower(),
                normalize_whitespace(app_name).lower(),
                normalize_whitespace(primary_user_flow).lower(),
                " ".join(item.lower() for item in normalize_secondary_requirements(secondary_requirements)),
            ],
        )
    )
    if any(token in combined_text for token in ("메모", "노트", "일기")):
        return [
            "메모 작성만 있으면 될까요, 수정과 삭제도 같이 필요할까요?",
            "검색이나 폴더·태그 같은 정리 기능도 이번에 필요할까요?",
            "첫 화면은 메모 목록으로 할까요, 빠른 작성 화면으로 할까요?",
        ]

    return [
        "기본 등록 기능만 있으면 될까요, 수정과 삭제도 같이 필요할까요?",
        "첫 화면은 입력 중심으로 할까요, 목록이나 대시보드 중심으로 할까요?",
        "검색, 필터, 알림 중 이번에 꼭 필요한 보조 기능이 있을까요?",
    ]


def build_normalized_prompt(
    app_name: str,
    package_name: str,
    prompt: str,
    feature_points: list[str],
    primary_user_flow: str = "",
    secondary_requirements: Optional[list[str]] = None,
    acceptance_criteria: Optional[list[str]] = None,
) -> str:
    lines = [
        f"- 목표 앱 이름: {app_name}",
        f"- Android package name: {package_name}",
        "- 구현 대상: Flutter Android 앱",
        f"- 1차 핵심 흐름: {normalize_whitespace(primary_user_flow) or '(미정)'}",
        "- 2차 고도화 요구:",
    ]
    normalized_secondary = normalize_secondary_requirements(secondary_requirements)
    if normalized_secondary:
        lines.extend(f"  - {item}" for item in normalized_secondary)
    else:
        lines.append("  - 없음 또는 미정")
    lines.extend(
        [
        "- 핵심 요구사항:",
        ]
    )
    lines.extend(f"  - {feature}" for feature in feature_points)
    normalized_criteria = normalize_acceptance_criteria(acceptance_criteria)
    if normalized_criteria:
        lines.extend(
            [
                "",
                "## 빌드 성공 조건",
            ]
        )
        lines.extend(f"- {criterion}" for criterion in normalized_criteria)
    lines.extend(
        [
            "",
            "## 원본 사용자 요청",
            prompt.strip(),
        ]
    )
    return "\n".join(lines).strip()


def merge_clarification_into_prompt(pending_prompt: str, answer_prompt: str) -> str:
    pending = normalize_whitespace(pending_prompt)
    answer = normalize_whitespace(answer_prompt)
    if not pending:
        return answer
    if not answer or answer in pending:
        return pending
    return f"{pending}\n- 추가 명세: {answer}"


def looks_like_substantive_clarification_answer(prompt: str) -> bool:
    normalized = normalize_whitespace(prompt)
    if len(normalized) < 6:
        return False
    if looks_like_generic_confirmation(normalized):
        return False
    if looks_like_question(normalized):
        return False
    return True


def should_preserve_unbuilt_new_app_scope(
    *,
    existing_workspace_ready: bool,
    previous_conversation_state: Optional[dict[str, Any]],
    prompt: str,
    used_previous_pending_prompt: bool,
) -> bool:
    if existing_workspace_ready:
        return False
    previous_state = previous_conversation_state or {}
    previous_request_scope = normalize_whitespace(str(previous_state.get("request_scope") or ""))
    if previous_request_scope not in {"new_app", "non_app_request"}:
        return False
    pending_prompt = normalize_whitespace(str(previous_state.get("pending_user_prompt") or ""))
    latest_effective_user_prompt = normalize_whitespace(str(previous_state.get("latest_effective_user_prompt") or ""))
    initial_user_prompt = normalize_whitespace(str(previous_state.get("initial_user_prompt") or ""))
    has_unbuilt_request_context = bool(pending_prompt or latest_effective_user_prompt or initial_user_prompt)
    if not has_unbuilt_request_context:
        return False
    if looks_like_existing_app_reference(prompt):
        return False
    return used_previous_pending_prompt or bool(previous_state.get("awaiting_confirmation"))


def effective_followup_request_scope(
    previous_request_scope: str,
    *,
    existing_workspace_ready: bool,
) -> str:
    if previous_request_scope:
        return previous_request_scope
    return "existing_app_modification" if existing_workspace_ready else "new_app"


def build_summary_clauses(feature_points: list[str], app_name: str) -> list[str]:
    clauses: list[str] = []
    seen: set[str] = set()
    skip_patterns = (
        "앱 만들어줘",
        "앱 생성해줘",
        "앱 빌드해줘",
        "앱 개발해줘",
        "앱 구현해줘",
        "앱 수정해줘",
        "앱 추가해줘",
        "어플 만들어줘",
    )
    for feature in feature_points:
        clause = normalize_whitespace(feature)
        if not clause or any(token in clause for token in skip_patterns):
            continue
        clause = re.sub(r"^[-*•]\s*", "", clause).strip()
        clause = re.sub(r"(해줘|해주세요|해 줘|해 주세요)$", "", clause).strip()
        clause = re.sub(r"(으로 해|로 해|으로 구성해|로 구성해)$", "", clause).strip()
        clause = clause.strip(" .,!?:;-")
        if not clause or clause == app_name:
            continue

        lowered = clause.lower()
        normalized_clause = clause
        if "로그인은 없" in clause or "로그인 없이" in clause or "로그인 필요 없" in clause or "로그인 없음" in clause:
            normalized_clause = "로그인 없음"
        elif "로그인" in clause and ("필요" in clause or "넣" in clause or "사용" in clause):
            normalized_clause = "로그인 기능"
        elif any(token in clause for token in ("내부 저장", "로컬 저장", "기기 저장")):
            normalized_clause = "내부 저장 기능"
        elif "저장" in clause or "기록" in clause:
            normalized_clause = "데이터 저장 기능"
        elif "알림" in clause:
            normalized_clause = "알림 기능"
        elif "다크모드" in lowered or "다크 모드" in lowered:
            normalized_clause = "다크모드"

        normalized_clause = normalized_clause.strip(" .,!?:;-")
        if not normalized_clause or normalized_clause in seen:
            continue
        seen.add(normalized_clause)
        clauses.append(normalized_clause)
        if len(clauses) >= 3:
            break
    return clauses


def append_object_particle_korean(value: str) -> str:
    normalized = normalize_whitespace(value)
    if not normalized:
        return value
    last_char = normalized[-1]
    code = ord(last_char)
    if 0xAC00 <= code <= 0xD7A3:
        has_final_consonant = (code - 0xAC00) % 28 != 0
        return f"{normalized}{'을' if has_final_consonant else '를'}"
    return f"{normalized}을"


def summarize_user_flow_clause(text: str) -> str:
    clause = normalize_whitespace(text)
    if not clause:
        return ""
    replacements = (
        ("사용자는 ", ""),
        ("선택한 범위는 ", ""),
        ("1차 핵심 흐름은 ", ""),
        ("2차 고도화 요구는 ", ""),
        ("우선 구현한다", "먼저 구현"),
        ("우선 구현해요", "먼저 구현"),
    )
    for source, target in replacements:
        clause = clause.replace(source, target)
    meta_patterns = (
        r"^A안으로[,. ]*",
        r"^B안으로[,. ]*",
        r"^A안[,: ]*",
        r"^B안[,: ]*",
        r"^사용자는 .* 원한다[,. ]*",
    )
    for pattern in meta_patterns:
        clause = re.sub(pattern, "", clause).strip()
    clause = re.sub(r"(을|를)?\s*원한다$", "", clause).strip()
    clause = re.sub(r"(을|를)?\s*원해요$", "", clause).strip()
    clause = re.sub(r"(합니다|해요|한다)$", "", clause).strip()
    clause = clause.strip(" .,!?:;-")
    return clause


def build_build_summary(
    app_name: str,
    feature_points: list[str],
    *,
    existing_task: bool,
    primary_user_flow: str = "",
    secondary_requirements: Optional[list[str]] = None,
) -> str:
    app_label = app_name if app_name and app_name != "맞춤 앱" else "요청하신 앱"
    if len(app_label) <= 1 or app_label in {"이", "그", "저"}:
        app_label = "앱"
    app_object = append_object_particle_korean(app_label)
    intro = f"기존 {app_object} 수정할게요." if existing_task else f"{app_object} 만들게요."
    clauses: list[str] = []
    seen: set[str] = set()

    primary_clause = summarize_user_flow_clause(primary_user_flow)
    if primary_clause and primary_clause not in seen:
        seen.add(primary_clause)
        clauses.append(primary_clause)

    for item in normalize_secondary_requirements(secondary_requirements):
        clause = summarize_user_flow_clause(item)
        if not clause or clause in seen:
            continue
        seen.add(clause)
        clauses.append(clause)
        if len(clauses) >= 3:
            break

    if not clauses:
        clauses = build_summary_clauses(feature_points, app_name)
    if not clauses:
        return intro
    if existing_task:
        return f"{intro} 이번 수정은 {', '.join(clauses)}를 반영해요."
    return f"{intro} 주요 기능은 {', '.join(clauses)}예요."


def build_intent_decision(
    *,
    mode: str,
    task_id: str,
    existing_task: bool,
    existing_workspace_ready: bool = False,
    user_prompt: str,
    effective_user_prompt: Optional[str] = None,
    questions: Optional[list[str]] = None,
    reason: str = "",
    used_previous_pending_prompt: bool = False,
    request_scope: Optional[str] = None,
    requires_existing_task_context: bool = False,
    assistant_message: str = "",
    suggested_app_name: str = "",
    primary_user_flow: str = "",
    secondary_requirements: Optional[list[str]] = None,
    secondary_scope_confirmed: bool = False,
    acceptance_criteria: Optional[list[str]] = None,
    image_reference_summary: str = "",
    image_conflict_note: str = "",
) -> IntentDecision:
    raw_effective_prompt = effective_user_prompt or user_prompt
    effective_prompt = normalize_whitespace(raw_effective_prompt)
    normalized_user_prompt = normalize_whitespace(user_prompt)
    feature_points = extract_feature_points(raw_effective_prompt)
    app_name = normalize_whitespace(suggested_app_name)
    if app_name in {"맞춤 앱", "요청 앱", "새 앱", "앱"}:
        app_name = ""
    if not app_name:
        app_name = infer_app_name(effective_prompt)
    package_name = infer_package_name(app_name, task_id)
    resolved_primary_user_flow = normalize_whitespace(primary_user_flow) or infer_primary_user_flow(raw_effective_prompt, feature_points, app_name)
    resolved_secondary_requirements = normalize_secondary_requirements(secondary_requirements)
    resolved_acceptance_criteria = normalize_acceptance_criteria(acceptance_criteria)
    if mode in {"build", "ask_confirmation"} and not resolved_acceptance_criteria:
        resolved_acceptance_criteria = infer_acceptance_criteria(raw_effective_prompt, feature_points)
    resolved_request_scope = request_scope or ("existing_app_modification" if existing_task else "new_app")
    if mode == "answer_question":
        answer_message = assistant_message or make_answer_message(user_prompt)
        answer_request_scope = resolved_request_scope if existing_task else "non_app_request"
        if answer_request_scope not in {"new_app", "existing_app_modification"}:
            answer_request_scope = "non_app_request"
        return IntentDecision(
            mode="answer_question",
            status="Pending Decision",
            tool="answer_question",
            message=answer_message,
            summary="",
            questions=[],
            reason=reason or "질문 또는 상담으로 해석됐어요.",
            request_scope=answer_request_scope,
            requires_existing_task_context=False,
            app_name="",
            package_name="",
            normalized_prompt=normalized_user_prompt,
            feature_points=extract_feature_points(user_prompt),
            primary_user_flow="",
            secondary_requirements=[],
            secondary_scope_confirmed=False,
            acceptance_criteria=[],
            effective_user_prompt=effective_prompt,
            used_previous_pending_prompt=used_previous_pending_prompt,
            confirmation_action="",
            confirmation_payload="",
            image_reference_summary=image_reference_summary,
            image_conflict_note=image_conflict_note,
        )
    if mode == "build":
        continue_existing_app = resolved_request_scope == "existing_app_modification"
        if continue_existing_app:
            summary = build_build_summary(
                app_name,
                feature_points,
                existing_task=True,
                primary_user_flow=resolved_primary_user_flow,
                secondary_requirements=resolved_secondary_requirements,
            )
            message = ""
        else:
            summary = build_build_summary(
                app_name,
                feature_points,
                existing_task=False,
                primary_user_flow=resolved_primary_user_flow,
                secondary_requirements=resolved_secondary_requirements,
            )
            message = ""
        return IntentDecision(
            mode="build",
            status="Queued",
            tool="codex",
            message=message,
            summary=summary,
            questions=[],
            reason=reason,
            request_scope=resolved_request_scope,
            requires_existing_task_context=requires_existing_task_context,
            app_name=app_name,
            package_name=package_name,
            normalized_prompt=build_normalized_prompt(
                app_name,
                package_name,
                effective_prompt,
                feature_points,
                resolved_primary_user_flow,
                resolved_secondary_requirements,
                resolved_acceptance_criteria,
            ),
            feature_points=feature_points,
            primary_user_flow=resolved_primary_user_flow,
            secondary_requirements=resolved_secondary_requirements,
            secondary_scope_confirmed=secondary_scope_confirmed,
            acceptance_criteria=resolved_acceptance_criteria,
            effective_user_prompt=effective_prompt,
            used_previous_pending_prompt=used_previous_pending_prompt,
            confirmation_action="",
            confirmation_payload="",
            image_reference_summary=image_reference_summary,
            image_conflict_note=image_conflict_note,
        )
    clarification_questions = questions or build_clarification_questions(effective_prompt)
    clarification_message = "수정을 시작하기 전에 몇 가지만 확인할게요." if resolved_request_scope == "existing_app_modification" else "앱 생성을 시작하기 전에 몇 가지만 확인할게요."
    clarification_summary = "수정 방향은 파악됐지만, 바로 반영하기엔 명세가 조금 더 필요해요." if resolved_request_scope == "existing_app_modification" else "앱 목적은 파악됐지만, 바로 빌드하기엔 명세가 조금 더 필요해요."
    return IntentDecision(
        mode="ask_confirmation",
        status="Pending Decision",
        tool="ask_confirmation",
        message=clarification_message,
        summary=clarification_summary,
        questions=clarification_questions,
        reason=reason or "핵심 화면이나 필수 기능처럼 빌드 결과를 크게 바꾸는 명세가 조금 더 필요해요.",
        request_scope=resolved_request_scope,
        requires_existing_task_context=requires_existing_task_context,
        app_name=app_name,
        package_name=package_name,
        normalized_prompt=build_normalized_prompt(
            app_name,
            package_name,
            effective_prompt,
            feature_points,
            resolved_primary_user_flow,
            resolved_secondary_requirements,
            resolved_acceptance_criteria,
        ),
        feature_points=feature_points,
        primary_user_flow=resolved_primary_user_flow,
        secondary_requirements=resolved_secondary_requirements,
        secondary_scope_confirmed=secondary_scope_confirmed,
        acceptance_criteria=resolved_acceptance_criteria,
        effective_user_prompt=effective_prompt,
        used_previous_pending_prompt=used_previous_pending_prompt,
        confirmation_action="",
        confirmation_payload="",
        image_reference_summary=image_reference_summary,
        image_conflict_note=image_conflict_note,
    )


def build_pre_build_confirmation_decision(decision: IntentDecision, *, existing_task: bool) -> IntentDecision:
    if decision.mode != "build":
        return decision
    question = "정리한 명세대로 바로 앱 생성을 시작할까요?" if not existing_task else "정리한 수정 방향대로 바로 반영을 시작할까요?"
    message = "빌드 전에 정리한 명세를 한 번만 확인해 주세요." if not existing_task else "수정 전에 정리한 방향을 한 번만 확인해 주세요."
    return IntentDecision(
        mode="ask_confirmation",
        status="Pending Decision",
        tool="ask_confirmation",
        message=message,
        summary=decision.summary,
        questions=[question],
        reason="정리된 요구사항이 맞는지 확인받은 뒤 빌드를 시작해요.",
        request_scope=decision.request_scope,
        requires_existing_task_context=decision.requires_existing_task_context,
        app_name=decision.app_name,
        package_name=decision.package_name,
        normalized_prompt=decision.normalized_prompt,
        feature_points=decision.feature_points,
        primary_user_flow=decision.primary_user_flow,
        secondary_requirements=decision.secondary_requirements,
        secondary_scope_confirmed=decision.secondary_scope_confirmed,
        acceptance_criteria=decision.acceptance_criteria,
        effective_user_prompt=decision.effective_user_prompt,
        used_previous_pending_prompt=decision.used_previous_pending_prompt,
        confirmation_action="generate_confirm",
        confirmation_payload="네, 이 내용으로 앱 생성을 시작해줘" if not existing_task else "네, 이 내용으로 앱 수정을 시작해줘",
        image_reference_summary=decision.image_reference_summary,
        image_conflict_note=decision.image_conflict_note,
    )


def fallback_decide_intent(
    prompt: str,
    task_id: str,
    *,
    existing_task: bool,
    existing_workspace_ready: bool = False,
    previous_conversation_state: Optional[dict[str, Any]] = None,
    reference_image_name: Optional[str] = None,
) -> IntentDecision:
    lowered = prompt.lower()
    explicit_build_request = any(token in lowered for token in ("만들어줘", "생성해줘", "빌드해줘", "개발해줘", "수정해줘", "추가해줘", "구현해줘"))
    previous_state = previous_conversation_state or {}
    pending_prompt = normalize_whitespace(str(previous_state.get("pending_user_prompt") or ""))
    awaiting_confirmation = bool(previous_state.get("awaiting_confirmation"))
    previous_request_scope = normalize_whitespace(str(previous_state.get("request_scope") or ""))
    requires_existing_task_context = bool(previous_state.get("requires_existing_task_context"))
    pending_questions = [
        normalize_whitespace(str(item))
        for item in previous_state.get("latest_assistant_questions", [])
        if normalize_whitespace(str(item))
    ]
    if existing_task and awaiting_confirmation and pending_prompt and looks_like_generic_confirmation(prompt):
        followup_scope = effective_followup_request_scope(
            previous_request_scope,
            existing_workspace_ready=existing_workspace_ready,
        )
        if pending_questions and not questions_accept_generic_confirmation(pending_questions):
            return build_intent_decision(
                mode="ask_confirmation",
                task_id=task_id,
                existing_task=existing_task,
                existing_workspace_ready=existing_workspace_ready,
                user_prompt=prompt,
                effective_user_prompt=pending_prompt,
                questions=pending_questions,
                reason="질문에 대한 구체적인 답변이 있어야 빌드를 시작할 수 있어요.",
                used_previous_pending_prompt=True,
                request_scope=followup_scope,
                requires_existing_task_context=requires_existing_task_context,
            )
        return build_intent_decision(
            mode="build",
            task_id=task_id,
            existing_task=existing_task,
            existing_workspace_ready=existing_workspace_ready,
            user_prompt=prompt,
            effective_user_prompt=pending_prompt,
            reason="저장된 명세 정정안을 기준으로 빌드를 이어갑니다.",
            used_previous_pending_prompt=True,
            request_scope=followup_scope,
            requires_existing_task_context=requires_existing_task_context,
        )
    unsupported_message = detect_unsupported_android_request(prompt)
    supported_revision = revise_prompt_for_supported_android_scope(prompt)

    if supported_revision and looks_like_build_request(prompt, existing_task):
        return build_supported_revision_confirmation_decision(
            task_id=task_id,
            existing_task=existing_task,
            existing_workspace_ready=existing_workspace_ready,
            user_prompt=prompt,
            revised_prompt=supported_revision["effective_user_prompt"],
            question=supported_revision["question"],
            message=supported_revision["message"],
            summary=supported_revision["summary"],
            reason=supported_revision["reason"],
        )

    if unsupported_message and looks_like_build_request(prompt, existing_task):
        return build_intent_decision(
            mode="answer_question",
            task_id=task_id,
            existing_task=existing_task,
            existing_workspace_ready=existing_workspace_ready,
            user_prompt=prompt,
            reason="요청한 기능이 일반 안드로이드 앱 범위나 공개 연동 정책을 벗어날 가능성이 있어 바로 빌드하지 않았어요.",
            assistant_message=unsupported_message,
        )

    if (
        existing_task
        and awaiting_confirmation
        and pending_prompt
        and pending_questions
        and not requires_existing_task_context
        and looks_like_substantive_clarification_answer(prompt)
    ):
        return build_intent_decision(
            mode="build",
            task_id=task_id,
            existing_task=existing_task,
            existing_workspace_ready=existing_workspace_ready,
            user_prompt=prompt,
            effective_user_prompt=merge_clarification_into_prompt(pending_prompt, prompt),
            reason="핵심 명세가 확보되어 남은 세부사항은 기본 가정으로 진행합니다.",
            used_previous_pending_prompt=True,
            request_scope=effective_followup_request_scope(
                previous_request_scope,
                existing_workspace_ready=existing_workspace_ready,
            ),
            requires_existing_task_context=requires_existing_task_context,
        )

    if previous_request_scope == "existing_app_modification" and requires_existing_task_context and not existing_workspace_ready:
        if "새 앱" in lowered and any(token in lowered for token in ("만들", "생성", "진행")):
            return build_intent_decision(
                mode="build",
                task_id=task_id,
                existing_task=existing_task,
                existing_workspace_ready=existing_workspace_ready,
                user_prompt=prompt,
                request_scope="new_app",
            )
        return build_intent_decision(
            mode="ask_confirmation",
            task_id=task_id,
            existing_task=existing_task,
            existing_workspace_ready=existing_workspace_ready,
            user_prompt=prompt,
            questions=build_existing_task_reference_questions(),
            reason="기존 앱 수정은 원래 앱 작업 대화에서 이어서 진행해야 해요.",
            request_scope="existing_app_modification",
            requires_existing_task_context=True,
        )

    if not existing_workspace_ready and looks_like_existing_app_reference(prompt):
        return build_intent_decision(
            mode="ask_confirmation",
            task_id=task_id,
            existing_task=existing_task,
            existing_workspace_ready=existing_workspace_ready,
            user_prompt=prompt,
            questions=build_existing_task_reference_questions(),
            reason="이 요청은 기존 앱 수정으로 보여요. 원래 앱 workspace를 알아야 안전하게 이어서 수정할 수 있어요.",
            request_scope="existing_app_modification",
            requires_existing_task_context=True,
        )

    if looks_like_question(prompt) and not explicit_build_request:
        followup_scope = effective_followup_request_scope(
            previous_request_scope,
            existing_workspace_ready=existing_workspace_ready,
        ) if existing_task else "non_app_request"
        return build_intent_decision(
            mode="answer_question",
            task_id=task_id,
            existing_task=existing_task,
            existing_workspace_ready=existing_workspace_ready,
            user_prompt=prompt,
            request_scope=followup_scope,
            assistant_message=build_contextual_app_answer_message(prompt, previous_state),
        )

    if looks_like_build_request(prompt, existing_task):
        return build_intent_decision(
            mode="build",
            task_id=task_id,
            existing_task=existing_task,
            existing_workspace_ready=existing_workspace_ready,
            user_prompt=prompt,
            image_reference_summary=build_reference_image_summary(normalize_reference_image_name(reference_image_name)),
        )

    return build_intent_decision(
        mode="ask_confirmation",
        task_id=task_id,
        existing_task=existing_task,
        existing_workspace_ready=existing_workspace_ready,
        user_prompt=prompt,
        image_reference_summary=build_reference_image_summary(normalize_reference_image_name(reference_image_name)),
    )


def run_openai_structured_agent(
    settings: Settings,
    *,
    schema: dict[str, Any],
    schema_name: str,
    instructions: str,
    user_content: Optional[list[dict[str, Any]]] = None,
) -> Optional[dict[str, Any]]:
    api_key = os.getenv("OPENAI_API_KEY", "").strip()
    if not api_key:
        return None

    base_url = os.getenv("OPENAI_RESPONSES_URL", "https://api.openai.com/v1/responses").strip() or "https://api.openai.com/v1/responses"
    payload = {
        "model": settings.intent_agent_model,
        "instructions": instructions,
        "input": [
            {
                "role": "user",
                "content": user_content or [{"type": "input_text", "text": "JSON 스키마에 맞는 결과만 반환하세요."}],
            }
        ],
        "text": {
            "verbosity": "low",
            "format": {
                "type": "json_schema",
                "name": schema_name,
                "strict": True,
                "schema": schema,
            },
        },
    }

    try:
        with httpx.Client(
            timeout=httpx.Timeout(
                timeout=float(settings.intent_agent_timeout_seconds),
                connect=min(10.0, float(settings.intent_agent_timeout_seconds)),
            )
        ) as client:
            response = client.post(
                base_url,
                headers={
                    "Authorization": f"Bearer {api_key}",
                    "Content-Type": "application/json",
                },
                json=payload,
            )
            response.raise_for_status()
    except (httpx.HTTPError, ValueError):
        return None

    try:
        response_payload = response.json()
    except json.JSONDecodeError:
        return None

    output_text = extract_response_output_text(response_payload)
    if not output_text:
        return None

    try:
        result = json.loads(output_text)
    except json.JSONDecodeError:
        return None
    if not isinstance(result, dict):
        return None
    enriched_result = dict(result)
    enriched_result["__agent_meta"] = {
        "model": settings.intent_agent_model,
        "raw_output_text": output_text,
        "raw_response": response_payload,
        "usage": parse_response_usage_payload(response_payload),
    }
    return enriched_result


def run_intent_agent(
    settings: Settings,
    *,
    prompt: str,
    task_id: str,
    existing_task: bool,
    existing_workspace_ready: bool = False,
    previous_conversation_state: Optional[dict[str, Any]] = None,
) -> Optional[dict[str, Any]]:
    return None


def run_spec_clarification_agent(
    settings: Settings,
    *,
    prompt: str,
    task_id: str,
    existing_task: bool,
    existing_workspace_ready: bool = False,
    previous_conversation_state: Optional[dict[str, Any]] = None,
    device_info: Optional[dict[str, Any]] = None,
    reference_image_name: Optional[str] = None,
    reference_image_base64: Optional[str] = None,
) -> Optional[dict[str, Any]]:
    normalized_reference_image_name = normalize_reference_image_name(reference_image_name)
    normalized_reference_image_base64 = normalize_reference_image_base64(reference_image_base64)
    context_payload = {
        "task_id": task_id,
        "existing_task": existing_task,
        "existing_workspace_ready": existing_workspace_ready,
        "latest_user_prompt": prompt,
        "device_info": device_info or {},
        "previous_conversation_state": previous_conversation_state or {},
        "current_app_context": build_current_app_context(previous_conversation_state),
        "reference_image_attached": bool(normalized_reference_image_base64),
        "reference_image_name": normalized_reference_image_name,
    }
    schema = {
        "type": "object",
        "additionalProperties": False,
        "required": [
            "mode",
            "request_scope",
            "app_name",
            "effective_user_prompt",
            "primary_user_flow",
            "secondary_requirements",
            "secondary_scope_confirmed",
            "acceptance_criteria",
            "use_previous_pending_request",
            "requires_existing_task_context",
            "reason",
            "questions",
            "assistant_reply",
        ],
        "properties": {
            "mode": {
                "type": "string",
                "enum": ["build", "ask_confirmation", "answer_question"],
            },
            "request_scope": {
                "type": "string",
                "enum": ["new_app", "existing_app_modification", "non_app_request"],
            },
            "app_name": {"type": "string"},
            "effective_user_prompt": {"type": "string"},
            "primary_user_flow": {"type": "string"},
            "secondary_requirements": {
                "type": "array",
                "items": {"type": "string"},
                "maxItems": 5,
            },
            "secondary_scope_confirmed": {"type": "boolean"},
            "acceptance_criteria": {
                "type": "array",
                "items": {"type": "string"},
                "maxItems": 8,
            },
            "use_previous_pending_request": {"type": "boolean"},
            "requires_existing_task_context": {"type": "boolean"},
            "reason": {"type": "string"},
            "assistant_reply": {"type": "string"},
            "questions": {
                "type": "array",
                "items": {"type": "string"},
                "maxItems": 3,
            },
        },
    }
    agent_prompt = f"""You are the dedicated pre-build spec clarification agent for a Flutter Android APK build server.
You are not a general chat assistant and you are not a downstream formatting assistant.
Your only job is to turn the latest user message into one of these outcomes:
- ordinary non-build question -> answer_question
- app request that is not ready yet -> ask_confirmation
- app request that is ready enough -> build

Treat any mention of JSON, YAML, schema, output format, agent handoff, prompt format, or downstream instructions as metadata about how the build request may later be organized.
Do not answer that metadata instruction directly.
Do not promise compliance with a format.
Do not say things like "JSON 스키마 형식으로만 반환하겠습니다", "요청하신 형식대로 답하겠습니다", or similar meta-compliance replies.
If the end goal is still app creation or app modification, stay in the app-spec workflow and classify it as build or ask_confirmation instead of answer_question.

Rules:
- mode=answer_question when the user is asking a question, chatting, asking for explanation, or discussing possibilities and the server should not build yet.
- For mode=answer_question, keep questions empty, and write a natural Korean assistant_reply in 1-3 short sentences.
- If existing_task=true and the user asks about the current app, its usage, what was built, limitations, APK, or previous conversation, answer from current_app_context and previous_conversation_state. Do not say there is no completed app information when current_app_context has app_name, implemented_requirements, latest_effective_user_prompt, or build_success=true.
- For answer_question about the current app, set request_scope=existing_app_modification so the app thread keeps its context. For unrelated general chat, set request_scope=non_app_request.
- Never use mode=answer_question merely because the user included schema, formatting, or downstream-agent instructions inside an app request.
- Distinguish carefully between:
  - a question/discussion about what is possible,
  - an app build request,
  - a follow-up answer that clarifies an earlier app request.
- If the requested app depends on Android-restricted capabilities, OEM-private integrations, inaccessible third-party platform hooks, non-public APIs, or other conditions that a normal Android app cannot reliably ship with, do not ask for more spec details and do not build.
- In that case use mode=answer_question and explain clearly in Korean that the request is not feasible as a normal Android app, and briefly explain why. Suggest a nearby feasible alternative when helpful.
- If a single unsupported feature can simply be removed or replaced while preserving the rest of the same new app request, prefer mode=ask_confirmation instead of mode=answer_question.
- In that case, set effective_user_prompt to the revised buildable request, ask one short Korean confirmation question such as whether to proceed without that unsupported feature, and keep the rest of the app context intact.
- Examples of likely-infeasible requests include deep Bixby integration or device-vendor/private assistant control that depends on private or policy-restricted capabilities.
- request_scope=new_app when the user is asking to create a brand-new app.
- request_scope=existing_app_modification when the user is trying to change an app that already exists.
- Always propose app_name for build or ask_confirmation.
- app_name must be a short Korean app name that a non-technical user can recognize in the task list.
- Prefer 2-12 visible characters and reflect the app's purpose or core content.
- Do not use generic names such as "맞춤 앱", "요청 앱", "새 앱", "앱", or vague placeholders.
- If the user explicitly named the app, preserve that name unless it is clearly unusable.
- For answer_question, app_name may be an empty string.
- For build or ask_confirmation, always fill primary_user_flow with the single most important first-release user flow in short Korean.
- primary_user_flow should describe what the user can do first in the app, not a technical implementation detail.
- For build or ask_confirmation, always fill secondary_requirements with 0-5 enhancement items that are nice-to-have, second-phase, or optional polish beyond the first-release core flow.
- For a new app request, do not use mode=build until both of these are decided:
  1. primary_user_flow is concrete enough,
  2. secondary_scope_confirmed is true and secondary_requirements are either explicitly listed or explicitly confirmed as none for now.
- If the user's message does not clearly separate first-release core flow from second-phase enhancements, use mode=ask_confirmation and propose 2-3 short feature questions yourself.
- Do not ask the user to write or organize 1차 핵심 흐름 and 2차 고도화 요구 from scratch.
- This build system does not provide a backend database, account system, cloud storage, or multi-device sync for each generated app by default.
- Do not ask whether login, account creation, server storage, cloud sync, or multi-device sync is needed unless the user explicitly requested login, accounts, sharing across users, teams, cloud sync, or multi-device use.
- If persistent storage is implied or requested, assume local on-device persistence by default.
- Do not ask where data should be stored. Only ask what user-visible data or actions must be saved when that materially changes the app.
- Ask concrete option questions about user-visible behavior, such as:
  - 작성만 있으면 될까요, 수정과 삭제도 같이 필요할까요?
  - 첫 화면은 입력 중심으로 할까요, 목록이나 대시보드 중심으로 할까요?
  - 검색 기능도 이번에 필요할까요?
- Keep the questions short and easy for non-technical users to answer.
- Do not guess the second-phase scope silently when the user has not decided it yet, but do guide the user with concrete feature questions first.
- If the user explicitly says there is no second-phase scope for now, set secondary_scope_confirmed=true and leave secondary_requirements empty.
- For build or ask_confirmation, always fill acceptance_criteria with 3-8 short Korean bullet-style conditions that describe what must really work in the finished app.
- acceptance_criteria must capture user-visible must-have behavior, not internal implementation trivia.
- If the user requested camera capture, OCR, AI analysis, external information loading, or persistent storage, mention those explicitly in acceptance_criteria.
- Do not omit a difficult requested capability from acceptance_criteria just because it would be easier to fake with manual text input, hardcoded sample data, or temporary in-memory state.
- acceptance_criteria must make it obvious when a dummy implementation would be unacceptable.
- If existing_task=true and existing_workspace_ready=true, this thread already has an app workspace. Treat app-change requests as existing_app_modification.
- If existing_task=true but existing_workspace_ready=false, the app has not entered build execution yet. In that case, follow-up revisions like removing, replacing, or refining features are still part of the same new_app request unless the user clearly refers to a previously built/existing app.
- Do not classify a follow-up like "그 기능 빼고 진행해줘", "이 부분만 제외하고 진행", or "그럼 그 연동은 빼고 만들어줘" as existing_app_modification when the current thread has no workspace yet.
- If existing_workspace_ready=false and the user appears to be asking to modify an already existing app, do not build. Use mode=ask_confirmation and set requires_existing_task_context=true.
- When requires_existing_task_context=true, ask the user to continue from the original app task/thread or to clearly say they want a brand-new app instead.
- mode=build when the core workflow and key features are concrete enough to implement, even if some secondary preferences remain unspecified.
- If the user already provided a structured build spec, agent handoff format, JSON/YAML output template, or other downstream-agent instructions, but the clear end goal is still to build an app, treat it as an app request rather than a discussion.
- When the user provides an app spec plus formatting instructions, ignore the formatting instruction in assistant_reply and focus on extracting the buildable app intent into effective_user_prompt.
- mode=ask_confirmation when the request is still missing blocking details or when the request references an existing app but the current thread has no existing app workspace.
- Consider the provided Android device information when it materially affects feasibility or implementation shape, such as wearable support, sensor usage, navigation constraints, or Android-version-specific capabilities.
- If the latest user message answers earlier clarification questions, merge the previous pending prompt and the new answer into effective_user_prompt.
- Never replace a concrete saved request with a generic confirmation phrase.
- Keep questions empty unless mode=ask_confirmation.
- Ask only blocking questions that materially change the product.
- Prefer one decisive interpretation over hedging. Do not ask clarification questions when the message is plainly a question or discussion.
- After one clarification round, prefer build if the remaining uncertainty can be handled with reasonable defaults.
- After two clarification exchanges, stop trying to fully spec the product and choose build unless the app would otherwise be materially wrong.
- All user-facing natural-language outputs must be written in Korean.
- This includes assistant_reply, reason, and every question.
- Do not output English sentences for user-facing fields, even if the user's prompt mixes English and Korean.
- If you must mention a technical term or product name, keep the surrounding sentence in Korean.
- Write user-facing text for non-technical users.
- Keep user-facing sentences short, plain, and easy to understand.
- The host app can render limited Markdown in assistant_reply: short paragraphs, "- " bullet lists, "1. " numbered lists, **bold**, and short inline `code`.
- Do not use Markdown tables, images, HTML, or long fenced code blocks in user-facing replies.
- Keep questions as plain Korean question strings without Markdown bullets or numbering. The host app formats the question list.
- Avoid developer-facing wording such as schema, JSON, YAML, agent, prompt format, internal workflow, or output policy unless the user explicitly asked about those topics.
- effective_user_prompt is internal machine input, so preserve the user's requested app details faithfully there, but keep all explanatory text fields in Korean.
- For build or ask_confirmation, assistant_reply should be an empty string.
- For answer_question, assistant_reply must answer the user's real question or explain feasibility. It must never describe your own formatting behavior, schema behavior, or output-policy compliance.
- If you ask clarification questions for a new app, prefer 1-3 short Korean feature questions with concrete options.
- Avoid wording like "적어주세요", "알려주세요", "나눠서 답해 주세요", or other open-ended authoring requests when short option questions would work.
- For build or ask_confirmation, do not leave acceptance_criteria empty.
- For answer_question, acceptance_criteria must be an empty array.
- For answer_question, secondary_scope_confirmed must be false.

Return JSON only.

Context JSON:
{json.dumps(context_payload, ensure_ascii=False, indent=2)}
"""

    user_content: list[dict[str, Any]] = [
        {
            "type": "input_text",
            "text": "JSON 스키마에 맞는 결과만 반환하세요.",
        },
        {
            "type": "input_text",
            "text": f"Context JSON:\n{json.dumps(context_payload, ensure_ascii=False, indent=2)}",
        },
    ]
    if normalized_reference_image_base64:
        user_content.append(
            {
                "type": "input_text",
                "text": (
                    "참고 이미지가 함께 전달되었습니다. "
                    "이미지의 레이아웃, UI 스타일, 구성 요소, 텍스트, 화면 맥락을 요청 해석에 반영하세요."
                ),
            }
        )
        user_content.append(
            {
                "type": "input_image",
                "image_url": f"data:image/{infer_reference_image_suffix(normalized_reference_image_name).lstrip('.')};base64,{normalized_reference_image_base64}",
            }
        )

    return run_openai_structured_agent(
        settings,
        schema=schema,
        schema_name="spec_clarification_decision",
        instructions=agent_prompt,
        user_content=user_content,
    )


def decide_intent(
    prompt: str,
    task_id: str,
    *,
    existing_task: bool = False,
    existing_workspace_ready: bool = False,
    previous_conversation_state: Optional[dict[str, Any]] = None,
    device_info: Optional[dict[str, Any]] = None,
    reference_image_name: Optional[str] = None,
    reference_image_base64: Optional[str] = None,
    settings: Optional[Settings] = None,
    db: Optional["Database"] = None,
) -> IntentDecision:
    previous_state = previous_conversation_state or {}
    pending_prompt = normalize_whitespace(str(previous_state.get("pending_user_prompt") or ""))
    pending_acceptance_criteria = normalize_acceptance_criteria(previous_state.get("pending_acceptance_criteria"))
    pending_questions = [
        normalize_whitespace(str(item))
        for item in previous_state.get("latest_assistant_questions", [])
        if normalize_whitespace(str(item))
    ]
    previous_request_scope = normalize_whitespace(str(previous_state.get("request_scope") or ""))
    requires_existing_task_context = bool(previous_state.get("requires_existing_task_context"))
    if existing_task and looks_like_runtime_repair_request(prompt):
        return build_intent_decision(
            mode="build",
            task_id=task_id,
            existing_task=True,
            existing_workspace_ready=existing_workspace_ready,
            user_prompt=prompt,
            effective_user_prompt=prompt,
            reason="런타임 오류 복구 요청으로 인식해 추가 확인 없이 바로 수정 빌드를 시작합니다.",
            used_previous_pending_prompt=False,
            request_scope="existing_app_modification",
            requires_existing_task_context=False,
        )
    if existing_task and bool(previous_state.get("awaiting_confirmation")) and pending_prompt and looks_like_generic_confirmation(prompt):
        followup_scope = effective_followup_request_scope(
            previous_request_scope,
            existing_workspace_ready=existing_workspace_ready,
        )
        if pending_questions and not questions_accept_generic_confirmation(pending_questions):
            return build_intent_decision(
                mode="ask_confirmation",
                task_id=task_id,
                existing_task=existing_task,
                existing_workspace_ready=existing_workspace_ready,
                user_prompt=prompt,
                effective_user_prompt=pending_prompt,
                questions=pending_questions,
                reason="질문에 대한 구체적인 답변이 있어야 빌드를 시작할 수 있어요.",
                used_previous_pending_prompt=True,
                request_scope=followup_scope,
                requires_existing_task_context=requires_existing_task_context,
                acceptance_criteria=pending_acceptance_criteria,
            )
        return build_intent_decision(
            mode="build",
            task_id=task_id,
            existing_task=existing_task,
            existing_workspace_ready=existing_workspace_ready,
            user_prompt=prompt,
            effective_user_prompt=pending_prompt,
            reason="저장된 명세 정정안을 기준으로 빌드를 이어갑니다.",
            used_previous_pending_prompt=True,
            request_scope=followup_scope,
            requires_existing_task_context=requires_existing_task_context,
            acceptance_criteria=pending_acceptance_criteria,
        )

    if settings and settings.intent_agent_enabled:
        spec_payload = run_spec_clarification_agent(
            settings,
            prompt=prompt,
            task_id=task_id,
            existing_task=existing_task,
            existing_workspace_ready=existing_workspace_ready,
            previous_conversation_state=previous_conversation_state,
            device_info=device_info,
            reference_image_name=reference_image_name,
            reference_image_base64=reference_image_base64,
        )
        if spec_payload:
            agent_meta = spec_payload.get("__agent_meta") if isinstance(spec_payload.get("__agent_meta"), dict) else None
            if db and agent_meta:
                raw_output_text = str(agent_meta.get("raw_output_text") or "")
                raw_response = agent_meta.get("raw_response") if isinstance(agent_meta.get("raw_response"), dict) else {}
                usage = agent_meta.get("usage") if isinstance(agent_meta.get("usage"), dict) else {}
                parsed_result = {key: value for key, value in spec_payload.items() if key != "__agent_meta"}
                log_agent_output_event(
                    db,
                    task_id,
                    agent_name="spec_clarification_agent",
                    model=str(agent_meta.get("model") or settings.intent_agent_model),
                    raw_output_text=raw_output_text,
                    parsed_result=parsed_result,
                    usage={
                        "input_tokens": usage.get("input_tokens"),
                        "cached_input_tokens": usage.get("cached_input_tokens"),
                        "output_tokens": usage.get("output_tokens"),
                        "cached_output_tokens": usage.get("cached_output_tokens"),
                        "reasoning_output_tokens": usage.get("reasoning_output_tokens"),
                        "total_tokens": usage.get("total_tokens"),
                    },
                    raw_response=raw_response,
                )
            spec_mode = str(spec_payload.get("mode") or "").strip()
            spec_app_name = normalize_whitespace(str(spec_payload.get("app_name") or ""))
            spec_primary_user_flow = normalize_whitespace(str(spec_payload.get("primary_user_flow") or ""))
            spec_secondary_requirements = normalize_secondary_requirements(spec_payload.get("secondary_requirements"))
            spec_secondary_scope_confirmed = bool(spec_payload.get("secondary_scope_confirmed"))
            spec_acceptance_criteria = normalize_acceptance_criteria(spec_payload.get("acceptance_criteria"))
            supported_revision = revise_prompt_for_supported_android_scope(prompt)
            if supported_revision and looks_like_build_request(prompt, existing_task):
                return build_supported_revision_confirmation_decision(
                    task_id=task_id,
                    existing_task=existing_task,
                    existing_workspace_ready=existing_workspace_ready,
                    user_prompt=prompt,
                    revised_prompt=supported_revision["effective_user_prompt"],
                    question=supported_revision["question"],
                    message=supported_revision["message"],
                    summary=supported_revision["summary"],
                    reason=supported_revision["reason"],
                )
            unsupported_message = detect_unsupported_android_request(prompt)
            if unsupported_message and looks_like_build_request(prompt, existing_task):
                return build_intent_decision(
                    mode="answer_question",
                    task_id=task_id,
                    existing_task=existing_task,
                    existing_workspace_ready=existing_workspace_ready,
                    user_prompt=prompt,
                    reason="요청한 기능이 일반 안드로이드 앱 범위나 공개 연동 정책을 벗어날 가능성이 있어 바로 빌드하지 않았어요.",
                    assistant_message=unsupported_message,
                )
            if spec_mode == "answer_question":
                structured_effective_prompt = normalize_whitespace(str(spec_payload.get("effective_user_prompt") or prompt))
                answer_request_scope = normalize_whitespace(str(spec_payload.get("request_scope") or ""))
                if answer_request_scope not in {"new_app", "existing_app_modification", "non_app_request"}:
                    answer_request_scope = (
                        effective_followup_request_scope(
                            previous_request_scope,
                            existing_workspace_ready=existing_workspace_ready,
                        )
                        if existing_task
                        else "non_app_request"
                    )
                if (
                    not existing_task
                    and looks_like_structured_agent_spec_prompt(prompt)
                    and structured_effective_prompt
                ):
                    return build_intent_decision(
                        mode="build",
                        task_id=task_id,
                        existing_task=existing_task,
                        existing_workspace_ready=existing_workspace_ready,
                        user_prompt=prompt,
                        effective_user_prompt=structured_effective_prompt,
                        reason="구조화된 앱 명세로 판단되어 질의 응답 대신 확인 단계로 넘깁니다.",
                        used_previous_pending_prompt=bool(spec_payload.get("use_previous_pending_request")),
                        request_scope="new_app",
                        requires_existing_task_context=False,
                        suggested_app_name=spec_app_name,
                        primary_user_flow=spec_primary_user_flow,
                        secondary_requirements=spec_secondary_requirements,
                        secondary_scope_confirmed=spec_secondary_scope_confirmed,
                        acceptance_criteria=spec_acceptance_criteria,
                    )
                return build_intent_decision(
                    mode="answer_question",
                    task_id=task_id,
                    existing_task=existing_task,
                    existing_workspace_ready=existing_workspace_ready,
                    user_prompt=prompt,
                    reason=korean_text_or_fallback(
                        str(spec_payload.get("reason") or ""),
                        "질문이나 상담 요청으로 보여서 바로 빌드하지 않고 먼저 대화로 정리합니다.",
                    ),
                    assistant_message=korean_text_or_fallback(
                        str(spec_payload.get("assistant_reply") or ""),
                        build_contextual_app_answer_message(prompt, previous_state) or make_answer_message(prompt),
                    ),
                    request_scope=answer_request_scope,
                    suggested_app_name=spec_app_name,
                    primary_user_flow=spec_primary_user_flow,
                    secondary_requirements=spec_secondary_requirements,
                    secondary_scope_confirmed=spec_secondary_scope_confirmed,
                    acceptance_criteria=spec_acceptance_criteria,
                )
            effective_user_prompt_raw = str(spec_payload.get("effective_user_prompt") or "")
            effective_user_prompt = normalize_whitespace(effective_user_prompt_raw)
            questions = [
                normalize_whitespace(str(item))
                for item in spec_payload.get("questions", [])
                if normalize_whitespace(str(item))
            ]
            if questions and not all(contains_korean_text(item) for item in questions):
                questions = build_clarification_questions(effective_user_prompt_raw or prompt)
            request_scope = normalize_whitespace(str(spec_payload.get("request_scope") or ""))
            used_previous_pending_request = bool(spec_payload.get("use_previous_pending_request"))
            if request_scope == "existing_app_modification" and should_preserve_unbuilt_new_app_scope(
                existing_workspace_ready=existing_workspace_ready,
                previous_conversation_state=previous_conversation_state,
                prompt=prompt,
                used_previous_pending_prompt=used_previous_pending_request,
            ):
                request_scope = "new_app"
                spec_payload["requires_existing_task_context"] = False
            if spec_mode in {"build", "ask_confirmation"} and request_scope in {"new_app", "existing_app_modification"}:
                if (
                    spec_mode == "ask_confirmation"
                    and not existing_task
                    and request_scope == "new_app"
                    and looks_like_structured_agent_spec_prompt(prompt)
                    and effective_user_prompt
                ):
                    return build_intent_decision(
                        mode="build",
                        task_id=task_id,
                        existing_task=existing_task,
                        existing_workspace_ready=existing_workspace_ready,
                        user_prompt=prompt,
                        effective_user_prompt=effective_user_prompt,
                        reason="구조화된 명세로 판단되어 추가 질문 대신 확인 단계로 넘깁니다.",
                        used_previous_pending_prompt=used_previous_pending_request,
                        request_scope=request_scope,
                        requires_existing_task_context=bool(spec_payload.get("requires_existing_task_context")),
                        suggested_app_name=spec_app_name,
                        primary_user_flow=spec_primary_user_flow,
                        secondary_requirements=spec_secondary_requirements,
                        secondary_scope_confirmed=spec_secondary_scope_confirmed,
                        acceptance_criteria=spec_acceptance_criteria,
                    )
                if (
                    spec_mode == "ask_confirmation"
                    and existing_task
                    and bool(previous_state.get("awaiting_confirmation"))
                    and pending_questions
                    and not bool(spec_payload.get("requires_existing_task_context"))
                    and looks_like_substantive_clarification_answer(prompt)
                ):
                    return build_intent_decision(
                        mode="build",
                        task_id=task_id,
                        existing_task=existing_task,
                        existing_workspace_ready=existing_workspace_ready,
                        user_prompt=prompt,
                        effective_user_prompt=effective_user_prompt_raw or merge_clarification_into_prompt(
                            pending_prompt,
                            prompt,
                        ),
                        reason="핵심 명세가 확보되어 남은 세부사항은 기본 가정으로 진행합니다.",
                        used_previous_pending_prompt=True,
                        request_scope=request_scope,
                        requires_existing_task_context=bool(spec_payload.get("requires_existing_task_context")),
                        suggested_app_name=spec_app_name,
                        primary_user_flow=spec_primary_user_flow,
                        secondary_requirements=spec_secondary_requirements,
                        secondary_scope_confirmed=spec_secondary_scope_confirmed,
                        acceptance_criteria=spec_acceptance_criteria,
                    )
                if request_scope == "new_app" and not spec_secondary_scope_confirmed:
                    forced_questions = questions or build_scope_clarification_questions(
                        prompt,
                        spec_app_name,
                        spec_primary_user_flow,
                        spec_secondary_requirements,
                    )
                    return build_intent_decision(
                        mode="ask_confirmation",
                        task_id=task_id,
                        existing_task=existing_task,
                        existing_workspace_ready=existing_workspace_ready,
                        user_prompt=prompt,
                        effective_user_prompt=effective_user_prompt_raw or prompt,
                        questions=forced_questions,
                        reason="앱을 만들기 전에 1차 핵심 흐름과 2차 고도화 요구를 함께 확정하고 있어요.",
                        used_previous_pending_prompt=used_previous_pending_request,
                        request_scope=request_scope,
                        requires_existing_task_context=bool(spec_payload.get("requires_existing_task_context")),
                        suggested_app_name=spec_app_name,
                        primary_user_flow=spec_primary_user_flow,
                        secondary_requirements=spec_secondary_requirements,
                        secondary_scope_confirmed=False,
                        acceptance_criteria=spec_acceptance_criteria,
                    )
                return build_intent_decision(
                    mode=spec_mode,
                    task_id=task_id,
                    existing_task=existing_task,
                    existing_workspace_ready=existing_workspace_ready,
                    user_prompt=prompt,
                    effective_user_prompt=effective_user_prompt_raw or prompt,
                    questions=questions,
                    reason=korean_text_or_fallback(
                        str(spec_payload.get("reason") or ""),
                        "핵심 화면이나 필수 기능처럼 결과를 크게 바꾸는 명세가 조금 더 필요해요.",
                    ),
                    used_previous_pending_prompt=used_previous_pending_request,
                    request_scope=request_scope,
                    requires_existing_task_context=bool(spec_payload.get("requires_existing_task_context")),
                    suggested_app_name=spec_app_name,
                    primary_user_flow=spec_primary_user_flow,
                    secondary_requirements=spec_secondary_requirements,
                    secondary_scope_confirmed=spec_secondary_scope_confirmed,
                    acceptance_criteria=spec_acceptance_criteria,
                    image_reference_summary=build_reference_image_summary(normalize_reference_image_name(reference_image_name)),
                )

    return fallback_decide_intent(
        prompt,
        task_id,
        existing_task=existing_task,
        existing_workspace_ready=existing_workspace_ready,
        previous_conversation_state=previous_conversation_state,
        reference_image_name=reference_image_name,
    )


class GenerateRequest(BaseModel):
    task_id: Optional[str] = None
    device_id: str = Field(..., min_length=1)
    phone_number: Optional[str] = None
    prompt: str = Field(..., min_length=1)
    device_info: Optional[DeviceInfoPayload] = None
    reference_image_path: Optional[str] = None
    reference_image_name: Optional[str] = None
    reference_image_base64: Optional[str] = None


class TaskUpdateRequest(BaseModel):
    app_name: str = Field(..., min_length=1, max_length=80)


class AppLlmConfigRequest(BaseModel):
    enabled: bool = True
    provider: str = Field(default="openai", min_length=1)
    model: str = Field(default="gpt-5.4-mini", min_length=1)
    api_key: Optional[str] = None
    base_url: Optional[str] = None
    system_prompt: Optional[str] = None
    daily_request_limit: int = Field(default=100, ge=1)
    daily_token_limit: int = Field(default=50000, ge=1)
    max_output_tokens: int = Field(default=700, ge=64)
    temperature: float = Field(default=0.4, ge=0.0, le=2.0)


class GlobalAppLlmDefaultsRequest(AppLlmConfigRequest):
    apply_to_existing_tasks: bool = True


class AppLlmRuntimeRequest(BaseModel):
    package_name: str = Field(..., min_length=1)
    user_message: str = Field(..., min_length=1)
    context: Optional[str] = None
    image_base64: Optional[str] = None
    image_mime_type: Optional[str] = None


class RuntimeErrorReportRequest(BaseModel):
    package_name: str = Field(..., min_length=1)
    summary: str = Field(..., min_length=1)
    stack_trace: str = Field(..., min_length=1)
    error_message: Optional[str] = None
    report_kind: Optional[str] = None


class Database:
    def __init__(self, db_path: Path) -> None:
        self.db_path = db_path

    def connect(self) -> sqlite3.Connection:
        connection = sqlite3.connect(self.db_path, timeout=30, check_same_thread=False)
        connection.row_factory = sqlite3.Row
        return connection

    def ensure_column(self, connection: sqlite3.Connection, table_name: str, column_name: str, column_sql: str) -> None:
        rows = connection.execute(f"PRAGMA table_info({table_name})").fetchall()
        existing_columns = {str(row["name"]) for row in rows}
        if column_name in existing_columns:
            return
        connection.execute(f"ALTER TABLE {table_name} ADD COLUMN {column_name} {column_sql}")

    def init_db(self) -> None:
        self.db_path.parent.mkdir(parents=True, exist_ok=True)
        with self.connect() as connection:
            connection.execute(
                """
                CREATE TABLE IF NOT EXISTS tasks (
                    task_id TEXT PRIMARY KEY,
                    user_id TEXT NOT NULL,
                    device_id TEXT NOT NULL,
                    phone_number TEXT,
                    prompt TEXT NOT NULL,
                    status TEXT NOT NULL,
                    message TEXT NOT NULL,
                    workspace_path TEXT,
                    project_path TEXT,
                    apk_path TEXT,
                    apk_url TEXT,
                    app_name TEXT,
                    package_name TEXT,
                    input_tokens INTEGER,
                    cached_input_tokens INTEGER,
                    output_tokens INTEGER,
                    reasoning_output_tokens INTEGER,
                    total_tokens INTEGER,
                    codex_result_json TEXT,
                    log TEXT,
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL
                )
                """
            )
            self.ensure_column(connection, "tasks", "input_tokens", "INTEGER")
            self.ensure_column(connection, "tasks", "cached_input_tokens", "INTEGER")
            self.ensure_column(connection, "tasks", "output_tokens", "INTEGER")
            self.ensure_column(connection, "tasks", "reasoning_output_tokens", "INTEGER")
            self.ensure_column(connection, "tasks", "total_tokens", "INTEGER")
            connection.execute("CREATE INDEX IF NOT EXISTS idx_tasks_user_id ON tasks(user_id)")
            connection.execute(
                """
                CREATE TABLE IF NOT EXISTS task_events (
                    event_id TEXT PRIMARY KEY,
                    task_id TEXT NOT NULL,
                    actor TEXT NOT NULL,
                    event_type TEXT NOT NULL,
                    message_text TEXT,
                    payload_json TEXT,
                    created_at TEXT NOT NULL
                )
                """
            )
            connection.execute("CREATE INDEX IF NOT EXISTS idx_task_events_task_id_created_at ON task_events(task_id, created_at)")
            connection.execute(
                """
                CREATE TABLE IF NOT EXISTS app_llm_configs (
                    task_id TEXT PRIMARY KEY,
                    enabled INTEGER NOT NULL,
                    provider TEXT NOT NULL,
                    model TEXT NOT NULL,
                    api_key TEXT,
                    base_url TEXT,
                    system_prompt TEXT,
                    daily_request_limit INTEGER NOT NULL,
                    daily_token_limit INTEGER NOT NULL,
                    max_output_tokens INTEGER NOT NULL,
                    temperature REAL NOT NULL,
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL
                )
                """
            )
            connection.execute(
                """
                CREATE TABLE IF NOT EXISTS app_llm_usage (
                    usage_id TEXT PRIMARY KEY,
                    task_id TEXT NOT NULL,
                    package_name TEXT NOT NULL,
                    input_tokens INTEGER,
                    output_tokens INTEGER,
                    total_tokens INTEGER,
                    status TEXT NOT NULL,
                    created_at TEXT NOT NULL
                )
                """
            )
            connection.execute("CREATE INDEX IF NOT EXISTS idx_app_llm_usage_task_id_created_at ON app_llm_usage(task_id, created_at)")
            connection.execute(
                """
                CREATE TABLE IF NOT EXISTS task_usage_records (
                    usage_id TEXT PRIMARY KEY,
                    task_id TEXT NOT NULL,
                    source TEXT NOT NULL,
                    model TEXT NOT NULL,
                    input_tokens INTEGER,
                    cached_input_tokens INTEGER,
                    output_tokens INTEGER,
                    cached_output_tokens INTEGER,
                    reasoning_output_tokens INTEGER,
                    total_tokens INTEGER,
                    status TEXT NOT NULL,
                    raw_output_text TEXT,
                    payload_json TEXT,
                    created_at TEXT NOT NULL
                )
                """
            )
            connection.execute("CREATE INDEX IF NOT EXISTS idx_task_usage_records_task_id_created_at ON task_usage_records(task_id, created_at)")
            connection.execute(
                """
                CREATE TABLE IF NOT EXISTS task_project_snapshots (
                    snapshot_id TEXT PRIMARY KEY,
                    task_id TEXT NOT NULL,
                    revision_label TEXT NOT NULL,
                    source TEXT NOT NULL,
                    workspace_path TEXT NOT NULL,
                    project_path TEXT NOT NULL,
                    created_at TEXT NOT NULL
                )
                """
            )
            connection.execute("CREATE INDEX IF NOT EXISTS idx_task_project_snapshots_task_id_created_at ON task_project_snapshots(task_id, created_at)")
            connection.execute(
                """
                CREATE TABLE IF NOT EXISTS server_settings (
                    setting_name TEXT PRIMARY KEY,
                    setting_json TEXT NOT NULL,
                    updated_at TEXT NOT NULL
                )
                """
            )
            connection.commit()

    def create_task(self, task: dict[str, Any]) -> None:
        with self.connect() as connection:
            connection.execute(
                """
                INSERT INTO tasks (
                    task_id, user_id, device_id, phone_number, prompt, status, message,
                    workspace_path, project_path, apk_path, apk_url, app_name, package_name,
                    input_tokens, cached_input_tokens, output_tokens, reasoning_output_tokens, total_tokens,
                    codex_result_json, log, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                (
                    task["task_id"],
                    task["user_id"],
                    task["device_id"],
                    task.get("phone_number"),
                    task["prompt"],
                    task["status"],
                    task["message"],
                    task.get("workspace_path"),
                    task.get("project_path"),
                    task.get("apk_path"),
                    task.get("apk_url"),
                    task.get("app_name"),
                    task.get("package_name"),
                    task.get("input_tokens"),
                    task.get("cached_input_tokens"),
                    task.get("output_tokens"),
                    task.get("reasoning_output_tokens"),
                    task.get("total_tokens"),
                    task.get("codex_result_json"),
                    task.get("log"),
                    task["created_at"],
                    task["updated_at"],
                ),
            )
            connection.commit()

    def update_task(self, task_id: str, **fields: Any) -> None:
        if not fields:
            return
        fields["updated_at"] = utc_now_iso()
        assignments = ", ".join(f"{key} = ?" for key in fields)
        values = list(fields.values()) + [task_id]
        with self.connect() as connection:
            connection.execute(f"UPDATE tasks SET {assignments} WHERE task_id = ?", values)
            connection.commit()

    def log_event(
        self,
        task_id: str,
        *,
        actor: str,
        event_type: str,
        message_text: str = "",
        payload: Optional[dict[str, Any]] = None,
    ) -> None:
        created_at = utc_now_iso()
        payload_json = json.dumps(payload, ensure_ascii=False) if payload is not None else None
        with self.connect() as connection:
            connection.execute(
                """
                INSERT INTO task_events (
                    event_id, task_id, actor, event_type, message_text, payload_json, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                (
                    uuid.uuid4().hex,
                    task_id,
                    actor,
                    event_type,
                    message_text,
                    payload_json,
                    created_at,
                ),
            )
            connection.commit()

    def list_events(self, task_id: str) -> list[dict[str, Any]]:
        with self.connect() as connection:
            rows = connection.execute(
                """
                SELECT event_id, task_id, actor, event_type, message_text, payload_json, created_at
                FROM task_events
                WHERE task_id = ?
                ORDER BY rowid ASC
                """,
                (task_id,),
            ).fetchall()
            return [dict(row) for row in rows]

    def upsert_app_llm_config(self, task_id: str, config: dict[str, Any]) -> None:
        now = utc_now_iso()
        with self.connect() as connection:
            existing = connection.execute(
                "SELECT task_id, created_at FROM app_llm_configs WHERE task_id = ?",
                (task_id,),
            ).fetchone()
            created_at = str(existing["created_at"]) if existing else now
            connection.execute(
                """
                INSERT INTO app_llm_configs (
                    task_id, enabled, provider, model, api_key, base_url, system_prompt,
                    daily_request_limit, daily_token_limit, max_output_tokens, temperature,
                    created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT(task_id) DO UPDATE SET
                    enabled = excluded.enabled,
                    provider = excluded.provider,
                    model = excluded.model,
                    api_key = excluded.api_key,
                    base_url = excluded.base_url,
                    system_prompt = excluded.system_prompt,
                    daily_request_limit = excluded.daily_request_limit,
                    daily_token_limit = excluded.daily_token_limit,
                    max_output_tokens = excluded.max_output_tokens,
                    temperature = excluded.temperature,
                    updated_at = excluded.updated_at
                """,
                (
                    task_id,
                    1 if bool(config.get("enabled")) else 0,
                    str(config.get("provider") or "openai"),
                    str(config.get("model") or "gpt-5.4-mini"),
                    config.get("api_key"),
                    config.get("base_url"),
                    config.get("system_prompt"),
                    int(config.get("daily_request_limit") or 100),
                    int(config.get("daily_token_limit") or 50000),
                    int(config.get("max_output_tokens") or 700),
                    float(config.get("temperature") or 0.4),
                    created_at,
                    now,
                ),
            )
            connection.commit()

    def get_app_llm_config(self, task_id: str) -> Optional[dict[str, Any]]:
        with self.connect() as connection:
            row = connection.execute("SELECT * FROM app_llm_configs WHERE task_id = ?", (task_id,)).fetchone()
            return dict(row) if row else None

    def list_all_task_ids(self) -> list[str]:
        with self.connect() as connection:
            rows = connection.execute("SELECT task_id FROM tasks ORDER BY created_at ASC").fetchall()
            return [str(row["task_id"]) for row in rows]

    def set_server_setting(self, setting_name: str, payload: dict[str, Any]) -> None:
        with self.connect() as connection:
            connection.execute(
                """
                INSERT INTO server_settings (setting_name, setting_json, updated_at)
                VALUES (?, ?, ?)
                ON CONFLICT(setting_name) DO UPDATE SET
                    setting_json = excluded.setting_json,
                    updated_at = excluded.updated_at
                """,
                (
                    setting_name,
                    json.dumps(payload, ensure_ascii=False),
                    utc_now_iso(),
                ),
            )
            connection.commit()

    def get_server_setting(self, setting_name: str) -> Optional[dict[str, Any]]:
        with self.connect() as connection:
            row = connection.execute(
                "SELECT setting_json FROM server_settings WHERE setting_name = ?",
                (setting_name,),
            ).fetchone()
            if not row:
                return None
            try:
                payload = json.loads(str(row["setting_json"]))
            except json.JSONDecodeError:
                return None
            return payload if isinstance(payload, dict) else None

    def record_app_llm_usage(
        self,
        *,
        task_id: str,
        package_name: str,
        input_tokens: Optional[int],
        output_tokens: Optional[int],
        total_tokens: Optional[int],
        status: str,
    ) -> None:
        with self.connect() as connection:
            connection.execute(
                """
                INSERT INTO app_llm_usage (
                    usage_id, task_id, package_name, input_tokens, output_tokens, total_tokens, status, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                (
                    uuid.uuid4().hex,
                    task_id,
                    package_name,
                    input_tokens,
                    output_tokens,
                    total_tokens,
                    status,
                    utc_now_iso(),
                ),
            )
            connection.commit()

    def get_app_llm_daily_usage(self, task_id: str, *, day_prefix: str) -> dict[str, int]:
        with self.connect() as connection:
            row = connection.execute(
                """
                SELECT COUNT(*) AS request_count, COALESCE(SUM(total_tokens), 0) AS total_tokens
                FROM app_llm_usage
                WHERE task_id = ? AND created_at >= ?
                """,
                (task_id, day_prefix),
            ).fetchone()
            return {
                "request_count": int(row["request_count"] or 0) if row else 0,
                "total_tokens": int(row["total_tokens"] or 0) if row else 0,
            }

    def record_task_usage(self, task_id: str, usage: TaskUsageRecord) -> None:
        payload_json = json.dumps(usage.payload, ensure_ascii=False) if usage.payload is not None else None
        with self.connect() as connection:
            connection.execute(
                """
                INSERT INTO task_usage_records (
                    usage_id, task_id, source, model,
                    input_tokens, cached_input_tokens, output_tokens, cached_output_tokens,
                    reasoning_output_tokens, total_tokens, status, raw_output_text, payload_json, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                (
                    uuid.uuid4().hex,
                    task_id,
                    usage.source,
                    usage.model,
                    usage.input_tokens,
                    usage.cached_input_tokens,
                    usage.output_tokens,
                    usage.cached_output_tokens,
                    usage.reasoning_output_tokens,
                    usage.total_tokens,
                    usage.status,
                    usage.raw_output_text,
                    payload_json,
                    utc_now_iso(),
                ),
            )
            connection.commit()

    def list_task_usage_records(self, task_id: str) -> list[dict[str, Any]]:
        with self.connect() as connection:
            rows = connection.execute(
                """
                SELECT usage_id, task_id, source, model,
                       input_tokens, cached_input_tokens, output_tokens, cached_output_tokens,
                       reasoning_output_tokens, total_tokens, status, raw_output_text, payload_json, created_at
                FROM task_usage_records
                WHERE task_id = ?
                ORDER BY rowid ASC
                """,
                (task_id,),
            ).fetchall()
            return [dict(row) for row in rows]

    def record_project_snapshot(
        self,
        *,
        task_id: str,
        revision_label: str,
        source: str,
        workspace_path: str,
        project_path: str,
    ) -> None:
        with self.connect() as connection:
            connection.execute(
                """
                INSERT INTO task_project_snapshots (
                    snapshot_id, task_id, revision_label, source, workspace_path, project_path, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                (
                    uuid.uuid4().hex,
                    task_id,
                    revision_label,
                    source,
                    workspace_path,
                    project_path,
                    utc_now_iso(),
                ),
            )
            connection.commit()

    def list_project_snapshots(self, task_id: str) -> list[dict[str, Any]]:
        with self.connect() as connection:
            rows = connection.execute(
                """
                SELECT snapshot_id, task_id, revision_label, source, workspace_path, project_path, created_at
                FROM task_project_snapshots
                WHERE task_id = ?
                ORDER BY rowid ASC
                """,
                (task_id,),
            ).fetchall()
            return [dict(row) for row in rows]

    def get_task(self, task_id: str) -> Optional[dict[str, Any]]:
        with self.connect() as connection:
            row = connection.execute("SELECT * FROM tasks WHERE task_id = ?", (task_id,)).fetchone()
            return dict(row) if row else None

    def list_tasks(self, user_id: str) -> list[dict[str, Any]]:
        with self.connect() as connection:
            rows = connection.execute(
                """
                SELECT task_id, status, prompt, app_name, apk_url,
                       input_tokens, cached_input_tokens, output_tokens, reasoning_output_tokens, total_tokens,
                       created_at, updated_at
                FROM tasks
                WHERE user_id = ?
                ORDER BY created_at DESC
                """,
                (user_id,),
            ).fetchall()
            return [dict(row) for row in rows]

    def query_tasks(
        self,
        *,
        user_id: Optional[str] = None,
        device_id: Optional[str] = None,
        phone_number: Optional[str] = None,
    ) -> list[dict[str, Any]]:
        filters: list[str]
        values: list[Any]

        if phone_number:
            filters = ["phone_number = ?"]
            values = [phone_number]
        elif device_id:
            filters = ["device_id = ?"]
            values = [device_id]
        elif user_id:
            filters = ["user_id = ?"]
            values = [user_id]
        else:
            return []

        where_clause = " AND ".join(filters)
        with self.connect() as connection:
            rows = connection.execute(
                f"""
                SELECT task_id, status, message, prompt, app_name, package_name, apk_url,
                       input_tokens, cached_input_tokens, output_tokens, reasoning_output_tokens, total_tokens,
                       codex_result_json, created_at, updated_at
                FROM tasks
                WHERE {where_clause}
                ORDER BY created_at DESC
                """,
                values,
            ).fetchall()
            return [dict(row) for row in rows]


def render_task_agents_md(task_id: str) -> str:
    return f"""# Task Workspace Instructions

- Flutter Android 앱만 빌드한다.
- iOS/Xcode는 사용하지 않는다.
- 사용자의 명세를 반영해 `project` 폴더의 Flutter 앱을 수정한다.
- 가급적 `project/lib/main.dart`, `project/pubspec.yaml`, `project/android/app/` 아래만 집중해서 수정한다.
- `flutter pub get`, `flutter analyze`, `flutter build apk --debug` 중 필요한 명령을 실행한다.
- APK가 필요하면 release 대신 debug APK만 만든다.
- `assembleRelease`나 `flutter build apk --release`는 실행하지 않는다.
- 사용자가 요청한 핵심 기능을 더 쉬운 대체 구현으로 바꾸지 않는다.
- `prompt.md`에 적힌 `1차 핵심 흐름`을 이번 빌드의 최우선 범위로 본다.
- `2차 고도화 요구`는 1차가 안정적으로 성립한 뒤에 반영한다. 시간이 부족하거나 충돌하면 1차를 우선하고, 못 넣은 2차 요구는 `known_limitations`에 남긴다.
- 예를 들어 카메라 요구를 수동 텍스트 입력으로, OCR 요구를 붙여넣기 전용 흐름으로, AI/외부정보 조회를 하드코딩 샘플 데이터로, 저장 기능을 메모리 리스트만으로 대체하면 안 된다.
- 핵심 기능이 실제로 동작하지 않으면 성공으로 보고하지 않는다.
- 실제 런타임 호출이 필요한 기능은 localhost나 `127.0.0.1` 같은 단말 내부 주소를 쓰지 말고 `prompt.md`에 적힌 서버 endpoint를 사용한다.
- 런타임 AI 호출이 필요한 앱은 `runtime_package_name`과 실제 요청 package name이 일치해야 한다.
- 사용자가 저장을 원했다면 앱 재실행 후에도 유지되는 저장 방식을 사용한다.
- 더미 데이터나 예시 문구는 UI 스켈레톤 확인용 보조로만 허용된다. 핵심 사용자 흐름을 더미 데이터만으로 완성 처리하면 안 된다.
- Flutter UI는 모든 화면 크기와 키보드/시스템 inset에서 `RenderFlex overflowed by ... pixels` 및 top/bottom/right/left overflow가 나지 않게 만든다.
- 세로로 내용이 늘어나는 화면은 `SafeArea`와 `SingleChildScrollView`, `ListView`, `CustomScrollView` 중 적절한 스크롤 컨테이너를 사용한다. 고정 높이 `Column`에 긴 콘텐츠를 그대로 넣지 않는다.
- `Column`/`Row` 안의 긴 텍스트, 버튼, 입력창, 카드 목록은 `Flexible`/`Expanded`, `Wrap`, `ConstrainedBox`, `LayoutBuilder`, `maxLines`/`overflow` 등을 사용해 작은 화면에서도 넘치지 않게 한다.
- 빌드 전에 작은 화면 기준으로 레이아웃을 점검하고, overflow 가능성이 있으면 성공으로 보고하지 않는다.
- 구현이 어려워 일부 요구사항을 못 지켰다면 숨기지 말고 실패 처리하거나 `known_limitations`에 명시한다.
- 빌드 성공 시 반드시 `.codex_result/task_result.json`을 valid JSON으로 작성한다.
- 빌드 실패 시에도 반드시 `.codex_result/task_result.json`을 valid JSON으로 작성한다.
- stdout 텍스트를 최종 결과로 쓰지 말고, `task_result.json`을 최종 계약으로 사용한다.
- workspace 밖의 파일을 수정하지 않는다.

`task_result.json` 성공 계약:
```json
{{
  "status": "success",
  "task_id": "{task_id}",
  "app_name": "...",
  "package_name": "...",
  "apk_path": "project/build/app/outputs/flutter-apk/app-debug.apk",
  "implemented_requirements": ["실제로 동작하는 핵심 요구사항 1", "실제로 동작하는 핵심 요구사항 2"],
  "verification_notes": ["직접 확인한 동작이나 점검 내용"],
  "known_limitations": [],
  "app_llm_enabled": true,
  "app_llm_model": "gpt-5.4-mini",
  "app_llm_system_prompt": "이 앱 사용자에게 ...",
  "message": "APK build completed",
  "build_log_path": "logs/build.log"
}}
```

`task_result.json` 실패 계약:
```json
{{
  "status": "failed",
  "task_id": "{task_id}",
  "error_stage": "analyze|build|unknown",
  "message": "짧은 한국어 오류 요약",
  "build_log_path": "logs/build.log"
}}
```
"""


def build_app_runtime_metadata(task: dict[str, Any], settings: Settings) -> dict[str, str]:
    package_name = str(task.get("package_name") or "").strip() or "(미정)"
    runtime_endpoint = f"{settings.server_base_url}/apps/{task['task_id']}/llm/respond"
    return {
        "runtime_available": "yes" if settings.app_runtime_enabled_by_default and bool(settings.app_runtime_api_key) else "no",
        "runtime_endpoint": runtime_endpoint,
        "package_name": package_name,
        "model": settings.app_runtime_model,
    }


def render_prompt_md(task: dict[str, Any], settings: Settings) -> str:
    phone_line = task.get("phone_number") or "(없음)"
    normalized_prompt = task.get("normalized_prompt") or task["prompt"]
    build_request_prompt = task.get("build_request_prompt") or task["prompt"]
    runtime_meta = build_app_runtime_metadata(task, settings)
    state_payload = load_task_state_payload(task)
    conversation_state = state_payload.get("conversation_state") if isinstance(state_payload.get("conversation_state"), dict) else {}
    primary_user_flow = normalize_whitespace(
        str(
            state_payload.get("primary_user_flow")
            or conversation_state.get("pending_primary_user_flow")
            or conversation_state.get("latest_primary_user_flow")
            or ""
        )
    )
    secondary_requirements = normalize_secondary_requirements(
        state_payload.get("secondary_requirements")
        or conversation_state.get("pending_secondary_requirements")
        or conversation_state.get("latest_secondary_requirements")
    )
    acceptance_criteria = normalize_acceptance_criteria(
        state_payload.get("acceptance_criteria")
        or conversation_state.get("pending_acceptance_criteria")
        or conversation_state.get("latest_acceptance_criteria")
    )
    device_info = serialize_device_info(task.get("device_info"))
    device_info_summary = render_device_info_summary(device_info)
    sensor_preview = ", ".join((device_info.get("sensors") or [])[:12]) if device_info else ""
    reference_image_name = normalize_reference_image_name(
        conversation_state.get("reference_image_name") or task.get("reference_image_name")
    )
    reference_image_workspace_path = normalize_whitespace(
        str(conversation_state.get("reference_image_workspace_path") or task.get("reference_image_workspace_path") or "")
    )
    secondary_section = "\n".join(f"- {item}" for item in secondary_requirements) if secondary_requirements else "- 없음 또는 미정"
    acceptance_section = "\n".join(f"- {criterion}" for criterion in acceptance_criteria) if acceptance_criteria else "- (없음)"
    reference_image_section = (
        f"- 첨부된 참고 이미지 이름: {reference_image_name}\n"
        f"- workspace 저장 경로: {reference_image_workspace_path or '(미저장)'}\n"
        "- 이 이미지는 UI 스타일, 화면 구성, 참고 레이아웃, 대상 사물/장면, 텍스트 맥락을 해석하는 데 사용한다.\n"
        "- 사용자가 텍스트로 설명한 요구와 이미지가 함께 있으면 둘을 함께 반영하되, 충돌 시에는 사용자 텍스트 요청을 우선하고 차이를 명시한다."
        if reference_image_name
        else "- 첨부된 참고 이미지 없음"
    )
    return f"""# Task Request

- task_id: {task['task_id']}
- user_id: {task['user_id']}
- device_id: {task['device_id']}
- phone_number: {phone_line}

## 현재 사용자 기기 정보

- device_info_summary: {device_info_summary}
- model: {device_info.get('model') or '(없음)'}
- android_sdk: {device_info.get('sdk') or 0}
- screen_width: {device_info.get('width') or 0}
- screen_height: {device_info.get('height') or 0}
- sensors: {sensor_preview or '(없음)'}

## 서버가 정리한 앱 메타데이터

- inferred_app_name: {task.get('app_name') or '(미정)'}
- inferred_package_name: {task.get('package_name') or '(미정)'}

## 첨부 참고 이미지

{reference_image_section}

## 사용자 요청

{task['prompt']}

## 실제 빌드 대상 요청

{build_request_prompt}

## 정리된 작업 명세

{normalized_prompt}

## 1차 핵심 흐름

{primary_user_flow or "(미정)"}

## 2차 고도화 요구

{secondary_section}

## 빌드 성공 조건

{acceptance_section}

## 서버 제공 앱 런타임 AI 정보

- runtime_available: {runtime_meta['runtime_available']}
- runtime_endpoint: {runtime_meta['runtime_endpoint']}
- runtime_package_name: {runtime_meta['package_name']}
- runtime_model: {runtime_meta['model']}

## 작업 규칙

- `AGENTS.md` 지침을 반드시 따른다.
- `project` 폴더 안의 Flutter 앱만 수정한다.
- 결과는 반드시 `.codex_result/task_result.json`에 기록한다.
- 성공 시 Android APK 경로를 넣고, 실패 시 짧은 한국어 오류 요약을 넣는다.
- `1차 핵심 흐름`은 이번 빌드에서 반드시 완성되어야 하는 첫 출시 범위다.
- `2차 고도화 요구`는 이번 빌드에 포함되면 좋지만, `1차 핵심 흐름`보다 우선순위가 낮다. 둘이 충돌하면 1차를 우선한다.
- 위의 `빌드 성공 조건`은 실제로 동작하는 사용자 기능 기준이다. 핵심 조건을 빠뜨린 채 UI만 그럴듯하게 만들면 성공이 아니다.
- 카메라, OCR, 외부 정보 조회, AI 분석, 영구 저장처럼 사용자가 명시한 기능은 실제 흐름으로 구현한다.
- 카메라 요구를 수동 텍스트 입력으로, OCR 요구를 붙여넣기 전용 입력으로, 외부 정보 조회를 하드코딩 목록으로, 저장 기능을 메모리 상태만으로 대체하지 않는다.
- 사용자가 사진 분석, 조언, 분류, 요약, 상담처럼 실제 AI 추론이 필요한 기능을 요청했다면, 예시 문구나 규칙 기반 하드코딩으로 끝내지 말고 서버 런타임 AI endpoint를 호출하는 실제 동작을 구현한다.
- 런타임 AI 호출 시 `package_name`은 runtime_package_name 값을 사용한다.
- 런타임 endpoint를 앱 코드에 넣을 때 `127.0.0.1`, `localhost`, 에뮬레이터 내부 루프백 주소를 사용하지 말고 `runtime_endpoint` 값을 그대로 사용한다.
- 서버 런타임 AI를 쓰는 기능은 네트워크 실패/한도 초과 시 사용자에게 자연스러운 오류 메시지를 보여준다.
- 런타임 AI를 쓰는 앱이라면 `task_result.json` 성공 결과에 `app_llm_enabled`, `app_llm_model`, `app_llm_system_prompt`를 함께 넣는다.
- `app_llm_system_prompt`는 이 앱 목적에 맞는 앱 전용 프롬프트여야 하며, 모든 앱에 공통으로 쓰는 고정 문구를 그대로 복사하지 않는다.
- 예를 들어 방 정리 조언 앱이라면 사진 속 공간 상태를 관찰하고, 우선순위와 실행 순서를 조언하는 방향이 드러나야 한다.
- 화면 레이아웃은 작은 Android 화면, 화면 회전, 키보드 표시 상태에서도 top/bottom/right/left overflow가 나지 않게 구현한다. 긴 콘텐츠는 `SafeArea`와 스크롤 가능한 컨테이너로 감싸고, `Row`/`Column`의 긴 자식은 `Flexible`/`Expanded`/`Wrap`/`overflow` 처리를 한다.
- 현재 사용자 기기 정보가 제공되면, UI 크기·Android 버전·센서/웨어러블 가능성 같은 구현 판단에 실제로 반영한다.
"""


def append_followup_prompt(
    workspace_path: Path,
    prompt: str,
    *,
    effective_user_prompt: Optional[str] = None,
    normalized_prompt: Optional[str] = None,
    reference_image_name: Optional[str] = None,
    reference_image_workspace_path: Optional[str] = None,
) -> None:
    prompt_path = workspace_path / "prompt.md"
    timestamp = utc_now_iso()
    effective_prompt = normalize_whitespace(effective_user_prompt or prompt)
    with prompt_path.open("a", encoding="utf-8") as handle:
        handle.write(
            f"\n\n## 후속 요청 ({timestamp})\n\n{prompt.strip()}\n"
        )
        if normalize_whitespace(prompt) != effective_prompt:
            handle.write(f"\n### 실제 반영할 요청\n\n{effective_prompt}\n")
        if normalized_prompt:
            handle.write(f"\n### 서버 정리 명세\n\n{normalized_prompt.strip()}\n")
        normalized_image_name = normalize_reference_image_name(reference_image_name)
        normalized_image_path = normalize_whitespace(str(reference_image_workspace_path or ""))
        if normalized_image_name:
            handle.write(
                "\n### 함께 전달된 참고 이미지\n\n"
                f"- 이름: {normalized_image_name}\n"
                f"- workspace 경로: {normalized_image_path or '(미저장)'}\n"
                "- 이 이미지를 UI/레이아웃/콘텐츠 참고 자료로 반영한다.\n"
            )


def default_app_llm_config(settings: Settings) -> dict[str, Any]:
    return {
        "enabled": settings.app_runtime_enabled_by_default and bool(settings.app_runtime_api_key),
        "provider": settings.app_runtime_provider,
        "model": settings.app_runtime_model,
        "api_key": settings.app_runtime_api_key,
        "base_url": settings.app_runtime_base_url,
        "system_prompt": settings.app_runtime_system_prompt,
        "daily_request_limit": settings.app_runtime_daily_request_limit,
        "daily_token_limit": settings.app_runtime_daily_token_limit,
        "max_output_tokens": settings.app_runtime_max_output_tokens,
        "temperature": settings.app_runtime_temperature,
    }


def resolve_default_app_llm_config(db: Database, settings: Settings) -> dict[str, Any]:
    stored = db.get_server_setting("app_llm_defaults")
    if stored:
        merged = default_app_llm_config(settings)
        merged.update(stored)
        return merged
    return default_app_llm_config(settings)


def ensure_default_app_llm_config(db: Database, settings: Settings, task_id: str) -> None:
    if db.get_app_llm_config(task_id):
        return
    config = resolve_default_app_llm_config(db, settings)
    db.upsert_app_llm_config(task_id, config)
    db.log_event(
        task_id,
        actor="system",
        event_type="app_llm_config_initialized",
        message_text=app_llm_config_event_message(config),
        payload=app_llm_config_event_payload(config, source="default"),
    )


def require_admin_token(settings: Settings, provided_token: Optional[str]) -> None:
    expected = settings.admin_api_token.strip()
    if not expected:
        return
    if (provided_token or "").strip() != expected:
        raise HTTPException(status_code=401, detail="invalid admin token")


def app_llm_config_response_payload(config: dict[str, Any]) -> dict[str, Any]:
    return {
        "enabled": bool(config.get("enabled")),
        "provider": str(config.get("provider") or "openai"),
        "model": str(config.get("model") or ""),
        "base_url": str(config.get("base_url") or ""),
        "system_prompt": str(config.get("system_prompt") or ""),
        "daily_request_limit": int(config.get("daily_request_limit") or 0),
        "daily_token_limit": int(config.get("daily_token_limit") or 0),
        "max_output_tokens": int(config.get("max_output_tokens") or 0),
        "temperature": float(config.get("temperature") or 0.0),
        "api_key_configured": bool(str(config.get("api_key") or "").strip()),
    }


def app_llm_config_changed_fields(previous: dict[str, Any], current: dict[str, Any]) -> list[str]:
    previous_payload = app_llm_config_response_payload(previous)
    current_payload = app_llm_config_response_payload(current)
    keys = (
        "enabled",
        "provider",
        "model",
        "base_url",
        "system_prompt",
        "daily_request_limit",
        "daily_token_limit",
        "max_output_tokens",
        "temperature",
        "api_key_configured",
    )
    changed = [key for key in keys if previous_payload.get(key) != current_payload.get(key)]
    if str(previous.get("api_key") or "") != str(current.get("api_key") or "") and "api_key" not in changed:
        changed.append("api_key")
    return changed


def app_llm_config_event_payload(
    config: dict[str, Any],
    *,
    previous_config: Optional[dict[str, Any]] = None,
    source: str = "",
) -> dict[str, Any]:
    payload = app_llm_config_response_payload(config)
    if source:
        payload["source"] = source
    if previous_config is not None:
        previous_system_prompt = str(previous_config.get("system_prompt") or "")
        current_system_prompt = str(config.get("system_prompt") or "")
        payload["previous_system_prompt"] = previous_system_prompt
        payload["system_prompt_changed"] = previous_system_prompt != current_system_prompt
        payload["previous_model"] = str(previous_config.get("model") or "")
        payload["previous_enabled"] = bool(previous_config.get("enabled"))
        payload["changed_fields"] = app_llm_config_changed_fields(previous_config, config)
    return payload


def app_llm_config_event_message(config: dict[str, Any]) -> str:
    return (
        f"enabled={bool(config.get('enabled'))} "
        f"provider={str(config.get('provider') or 'openai')} "
        f"model={str(config.get('model') or '')}\n"
        "system_prompt:\n"
        f"{str(config.get('system_prompt') or '')}"
    )


def merge_app_llm_config_values(
    existing: Optional[dict[str, Any]],
    *,
    enabled: bool,
    provider: str,
    model: str,
    api_key: Optional[str],
    base_url: Optional[str],
    system_prompt: Optional[str],
    daily_request_limit: int,
    daily_token_limit: int,
    max_output_tokens: int,
    temperature: float,
    settings: Settings,
) -> dict[str, Any]:
    existing_config = existing or {}
    return {
        "enabled": enabled,
        "provider": provider.strip(),
        "model": model.strip(),
        "api_key": (api_key if api_key is not None else existing_config.get("api_key")) or "",
        "base_url": (base_url if base_url is not None else existing_config.get("base_url")) or settings.app_runtime_base_url,
        "system_prompt": (system_prompt if system_prompt is not None else existing_config.get("system_prompt")) or "",
        "daily_request_limit": daily_request_limit,
        "daily_token_limit": daily_token_limit,
        "max_output_tokens": max_output_tokens,
        "temperature": temperature,
    }


def apply_codex_generated_app_llm_settings(
    db: Database,
    settings: Settings,
    *,
    task_id: str,
    result_payload: dict[str, Any],
) -> None:
    generated_prompt = normalize_whitespace(str(result_payload.get("app_llm_system_prompt") or ""))
    generated_model = normalize_whitespace(str(result_payload.get("app_llm_model") or ""))
    runtime_needed = result_payload.get("app_llm_enabled")

    if not generated_prompt and runtime_needed is None and not generated_model:
        return

    existing = db.get_app_llm_config(task_id) or resolve_default_app_llm_config(db, settings)
    merged = dict(existing)
    if generated_prompt:
        merged["system_prompt"] = generated_prompt
    if generated_model:
        merged["model"] = generated_model
    if isinstance(runtime_needed, bool):
        merged["enabled"] = runtime_needed

    db.upsert_app_llm_config(task_id, merged)
    db.log_event(
        task_id,
        actor="system",
        event_type="app_llm_config_generated",
        message_text=app_llm_config_event_message(merged),
        payload=app_llm_config_event_payload(merged, previous_config=existing, source="codex_result"),
    )


def infer_model_name_from_codex_command(command_text: str) -> str:
    match = re.search(r"--model\s+([^\s]+)", command_text)
    if match:
        return match.group(1).strip("\"'")
    return "codex-cli-default"


def log_agent_output_event(
    db: Database,
    task_id: str,
    *,
    agent_name: str,
    model: str,
    raw_output_text: str,
    parsed_result: dict[str, Any],
    usage: dict[str, Optional[int]],
    raw_response: Optional[dict[str, Any]] = None,
) -> None:
    db.log_event(
        task_id,
        actor="assistant",
        event_type="agent_raw_output",
        message_text=raw_output_text,
        payload={
            "agent_name": agent_name,
            "model": model,
            "raw_output_text": raw_output_text,
            "parsed_result": parsed_result,
            "usage": usage,
            "raw_response": raw_response or {},
        },
    )
    db.record_task_usage(
        task_id,
        TaskUsageRecord(
            source=agent_name,
            model=model,
            input_tokens=usage.get("input_tokens"),
            cached_input_tokens=usage.get("cached_input_tokens"),
            output_tokens=usage.get("output_tokens"),
            cached_output_tokens=usage.get("cached_output_tokens"),
            reasoning_output_tokens=usage.get("reasoning_output_tokens"),
            total_tokens=usage.get("total_tokens"),
            status="recorded",
            raw_output_text=raw_output_text,
            payload={
                "parsed_result": parsed_result,
                "raw_response": raw_response or {},
            },
        ),
    )


def codex_usage_payload(usage: Optional[CodexUsage]) -> dict[str, Optional[int]]:
    return {
        "input_tokens": usage.input_tokens if usage else None,
        "cached_input_tokens": usage.cached_input_tokens if usage else None,
        "output_tokens": usage.output_tokens if usage else None,
        "cached_output_tokens": None,
        "reasoning_output_tokens": usage.reasoning_output_tokens if usage else None,
        "total_tokens": usage.total_tokens if usage else None,
    }


def looks_like_technical_reference(value: str) -> bool:
    normalized = value.strip()
    if not normalized:
        return False
    if "/" in normalized or "\\" in normalized:
        return True
    if re.search(r"\.(dart|kt|java|xml|gradle|json|ya?ml|py|md|txt)$", normalized, re.IGNORECASE):
        return True
    if re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*(?:\.[A-Za-z_][A-Za-z0-9_]*)*(?:\(\))?", normalized):
        return True
    return bool(re.search(r"[a-z][A-Z]|_", normalized) and re.search(r"[A-Za-z]", normalized))


def sanitize_codex_followup_user_text(text: str) -> str:
    if not text:
        return ""

    sanitized = text.replace("\r\n", "\n").replace("\r", "\n")

    def replace_inline_code(match: re.Match[str]) -> str:
        content = match.group(1)
        return "앱 내부 구현" if looks_like_technical_reference(content) else content

    sanitized = re.sub(r"`([^`\n]{1,160})`", replace_inline_code, sanitized)
    sanitized = re.sub(
        r"(?<![\w가-힣])(?:/[\w./~@:+-]+|(?:project|lib|android|ios|web|test|build|src|app|res|values|logs|revisions|workspace|workspaces)[\w./-]+)",
        "앱 내부 구현",
        sanitized,
        flags=re.IGNORECASE,
    )
    sanitized = re.sub(
        r"(?<![\w가-힣])[\w.-]+(?:/[\w.-]+)+\.(?:dart|kt|java|xml|gradle|json|ya?ml|py|md|txt)(?![\w가-힣])",
        "앱 내부 구현",
        sanitized,
        flags=re.IGNORECASE,
    )
    sanitized = re.sub(
        r"(?<![\w가-힣])[A-Za-z_][A-Za-z0-9_]*(?:\.[A-Za-z_][A-Za-z0-9_]*)+\b(?:\(\))?",
        "앱 내부 구현",
        sanitized,
    )
    sanitized = re.sub(
        r"(?<![\w가-힣])[A-Za-z_][A-Za-z0-9_]*\(\)",
        "앱 내부 동작",
        sanitized,
    )
    sanitized = re.sub(
        r"(?<![\w가-힣])_?[A-Za-z]+(?:[A-Z][A-Za-z0-9]*|_[A-Za-z0-9]+)[A-Za-z0-9_]*(?![\w가-힣])",
        "앱 내부 구현",
        sanitized,
    )
    sanitized = re.sub(r"\bline\s*\d+\b", "해당 부분", sanitized, flags=re.IGNORECASE)
    sanitized = re.sub(r"\d+\s*번째\s*줄|\d+\s*번\s*줄|\d+\s*줄", "해당 부분", sanitized)
    sanitized = sanitized.replace("앱 내부 구현의 앱 내부 구현", "앱 내부 구현")
    sanitized = sanitized.replace("앱 내부 구현의 앱 내부 동작", "앱 내부 구현")
    sanitized = sanitized.replace("앱 내부 구현가", "앱 내부 구현이")
    sanitized = sanitized.replace("앱 내부 구현는", "앱 내부 구현은")
    sanitized = sanitized.replace("앱 내부 구현를", "앱 내부 구현을")
    return sanitized


def run_codex_existing_task_followup_decision(
    settings: Settings,
    db: Database,
    task: dict[str, Any],
    *,
    prompt: str,
    previous_conversation_state: dict[str, Any],
    device_info: Optional[dict[str, Any]] = None,
    reference_image_name: Optional[str] = None,
) -> Optional[dict[str, Any]]:
    workspace_value = normalize_whitespace(str(task.get("workspace_path") or ""))
    project_value = normalize_whitespace(str(task.get("project_path") or ""))
    if not workspace_value or not project_value:
        return None

    workspace_path = Path(workspace_value).resolve()
    project_path = Path(project_value).resolve()
    if not workspace_path.exists() or not workspace_path.is_dir():
        return None
    if not project_path.exists() or not project_path.is_dir():
        return None
    if not ensure_within_root(project_path, workspace_path):
        return None

    request_id = uuid.uuid4().hex[:12]
    result_relative_path = f".codex_result/followup_decision_{request_id}.json"
    result_path = workspace_path / result_relative_path
    stdout_path = workspace_path / "logs" / f"followup_decision_{request_id}_stdout.log"
    stderr_path = workspace_path / "logs" / f"followup_decision_{request_id}_stderr.log"
    result_path.parent.mkdir(parents=True, exist_ok=True)
    stdout_path.parent.mkdir(parents=True, exist_ok=True)

    current_app_context = build_current_app_context(previous_conversation_state)
    safe_previous_conversation_state = dict(previous_conversation_state)
    if safe_previous_conversation_state.get("reference_image_base64"):
        safe_previous_conversation_state["reference_image_base64"] = "[omitted]"
    context_payload = {
        "task_id": task.get("task_id") or "",
        "app_name": current_task_app_name(task, previous_conversation_state),
        "package_name": current_task_package_name(task, previous_conversation_state),
        "latest_user_prompt": prompt,
        "current_app_context": current_app_context,
        "previous_conversation_state": safe_previous_conversation_state,
        "device_info": device_info or {},
        "reference_image_name": normalize_reference_image_name(reference_image_name),
        "project_path": str(project_path),
    }
    context_json = json.dumps(context_payload, ensure_ascii=False, indent=2)
    codex_prompt = f"""You are the VibeFactory Codex follow-up decision agent for an existing Flutter Android app.

The existing app source code is already available in the `project` directory inside this workspace.
Read the actual code and project files as needed before deciding.

User-facing language must be Korean.
The user is a non-technical end user. User-visible text must explain behavior in plain words.

Hard rules:
- Do not modify source files.
- Do not build the app.
- Do not run destructive commands.
- Only write the final machine-readable result JSON to `{result_relative_path}`.
- Do not wrap the JSON in Markdown.

Decide the latest user message into exactly one mode:
- `answer_question`: the user is asking about the existing app, its implementation, files, behavior, cause of an issue, or what happened. Answer from the actual code/workspace.
- `build`: the user is asking to change, fix, add, remove, redesign, rebuild, or otherwise modify the existing app. Do not perform the change in this preflight step.
- `ask_confirmation`: the request cannot be safely answered or built without one or more blocking details.

For `answer_question`, include a concise but concrete Korean `assistant_reply` for a normal app user.
Do not include file paths, folder names, line numbers, package names, class names, function names, variable names, stack trace symbols, or code identifiers in `assistant_reply` or `questions`.
If implementation details matter, translate them into user-facing concepts such as "화면 전환 처리", "저장 처리", "AI 응답 처리", or "대화 기록 처리".
Put developer-facing references only in `referenced_files`, never in `assistant_reply`.
For `build`, leave `assistant_reply` empty and put the code-aware build instruction in `effective_user_prompt`.
If `previous_conversation_state.awaiting_confirmation` is true and the latest user message answers that pending question, merge the pending request and latest answer into `effective_user_prompt`.
For `ask_confirmation`, include 1-3 short Korean `questions`.

Result JSON schema:
{{
  "mode": "answer_question | build | ask_confirmation",
  "effective_user_prompt": "string",
  "assistant_reply": "string",
  "questions": ["string"],
  "reason": "string",
  "referenced_files": ["string"]
}}

Context:
```json
{context_json}
```
"""

    placeholder = "__CODEX_PROMPT_PLACEHOLDER_6F4A1F45__"
    try:
        command_text = settings.codex_command.format(
            prompt=placeholder,
            task_id=str(task.get("task_id") or ""),
            workspace=str(workspace_path),
            project=str(project_path),
        )
    except KeyError:
        return None

    args = shlex.split(command_text)
    args = [part.replace(placeholder, codex_prompt) for part in args]
    env = os.environ.copy()
    env["CI"] = "1"

    exit_code: Optional[int] = None
    timed_out = False
    try:
        with stdout_path.open("wb") as stdout_file, stderr_path.open("wb") as stderr_file:
            completed = subprocess.run(
                args,
                cwd=workspace_path,
                env=env,
                stdout=stdout_file,
                stderr=stderr_file,
                timeout=settings.codex_followup_decision_timeout_seconds,
                check=False,
            )
            exit_code = completed.returncode
    except subprocess.TimeoutExpired:
        timed_out = True
    except OSError:
        return None

    if not result_path.exists():
        stdout_text = read_text_if_exists(stdout_path, limit=12000)
        stderr_text = read_text_if_exists(stderr_path, limit=12000)
        db.log_event(
            str(task.get("task_id") or ""),
            actor="system",
            event_type="codex_followup_decision_failed",
            message_text="기존 앱 follow-up 판단 결과 파일이 생성되지 않았습니다.",
            payload={
                "exit_code": exit_code,
                "timed_out": timed_out,
                "stdout_tail": "\n".join(tail_lines(stdout_text, 40)),
                "stderr_tail": "\n".join(tail_lines(stderr_text, 40)),
            },
        )
        return None

    try:
        parsed = json.loads(result_path.read_text(encoding="utf-8"))
    except (json.JSONDecodeError, OSError):
        return None
    if not isinstance(parsed, dict):
        return None

    mode = normalize_whitespace(str(parsed.get("mode") or ""))
    if mode not in {"answer_question", "build", "ask_confirmation"}:
        return None
    if mode == "answer_question":
        parsed["assistant_reply"] = sanitize_codex_followup_user_text(str(parsed.get("assistant_reply") or ""))
    if mode == "ask_confirmation":
        parsed["questions"] = [
            sanitize_codex_followup_user_text(str(item))
            for item in parsed.get("questions", [])
            if normalize_whitespace(str(item))
        ]

    usage = parse_codex_usage_from_jsonl(stdout_path)
    raw_output_text = json.dumps(parsed, ensure_ascii=False)
    log_agent_output_event(
        db,
        str(task.get("task_id") or ""),
        agent_name="codex_existing_task_followup",
        model=infer_model_name_from_codex_command(settings.codex_command),
        raw_output_text=raw_output_text,
        parsed_result=parsed,
        usage=codex_usage_payload(usage),
        raw_response={
            "exit_code": exit_code,
            "timed_out": timed_out,
            "result_path": result_relative_path,
            "stdout_log": str(stdout_path.relative_to(workspace_path).as_posix()),
            "stderr_log": str(stderr_path.relative_to(workspace_path).as_posix()),
        },
    )
    return parsed


def apply_project_defaults(project_root: Path, task_id: str, app_name: str, package_name: str) -> None:
    manifest_path = project_root / "android" / "app" / "src" / "main" / "AndroidManifest.xml"
    manifest_text = read_text_if_exists(manifest_path, limit=100_000)
    if manifest_text:
        manifest_text = re.sub(r'android:label="[^"]*"', f'android:label="{app_name}"', manifest_text, count=1)
        manifest_path.write_text(manifest_text, encoding="utf-8")

    gradle_path = project_root / "android" / "app" / "build.gradle.kts"
    gradle_text = read_text_if_exists(gradle_path, limit=100_000)
    if gradle_text:
        gradle_text = re.sub(r'namespace\s*=\s*"[^"]+"', f'namespace = "{package_name}"', gradle_text, count=1)
        gradle_text = re.sub(r'applicationId\s*=\s*"[^"]+"', f'applicationId = "{package_name}"', gradle_text, count=1)
        gradle_path.write_text(gradle_text, encoding="utf-8")

    kotlin_root = project_root / "android" / "app" / "src" / "main" / "kotlin"
    for kotlin_file in kotlin_root.rglob("MainActivity.kt"):
        kotlin_text = read_text_if_exists(kotlin_file, limit=100_000)
        if not kotlin_text:
            continue
        kotlin_text = re.sub(r"^package\s+[A-Za-z0-9_.]+", f"package {package_name}", kotlin_text, count=1, flags=re.MULTILINE)
        kotlin_file.write_text(kotlin_text, encoding="utf-8")
        break

    main_dart_path = project_root / "lib" / "main.dart"
    main_dart_text = read_text_if_exists(main_dart_path, limit=100_000)
    if main_dart_text:
        main_dart_text = re.sub(
            r'CrashHandler\.initialize\(\s*.*?\s*\);\s*',
            f'CrashHandler.initialize("{task_id}", "{package_name}");\n',
            main_dart_text,
            count=1,
            flags=re.DOTALL,
        )
        main_dart_text = main_dart_text.replace("title: 'Generated App'", f"title: '{app_name}'")
        main_dart_text = main_dart_text.replace('Text("Generated App Running")', f'Text("{app_name} 실행 중")')
        main_dart_path.write_text(main_dart_text, encoding="utf-8")


def ensure_workspace_project_link(workspace_path: Path, project_root: Path) -> Path:
    link_path = workspace_path / "project"
    if link_path.is_symlink() or link_path.exists():
        if link_path.is_dir() and not link_path.is_symlink():
            shutil.rmtree(link_path)
        else:
            link_path.unlink()
    try:
        link_path.symlink_to(project_root, target_is_directory=True)
    except OSError:
        shutil.copytree(project_root, link_path)
    return link_path


def current_revision_label(project_root: Path) -> str:
    parent_name = project_root.parent.name
    return parent_name if parent_name.startswith("rev_") else "rev_0000"


def revision_number_from_label(revision_label: str) -> int:
    match = re.fullmatch(r"rev_0*(\d+)", revision_label.strip())
    if not match:
        return 1
    return max(1, int(match.group(1)))


def ensure_project_revision_version(project_root: Path, revision_label: Optional[str] = None) -> bool:
    label = revision_label or current_revision_label(project_root)
    revision_number = revision_number_from_label(label)
    pubspec_path = project_root / "pubspec.yaml"
    pubspec_text = read_text_if_exists(pubspec_path, limit=100_000)
    if not pubspec_text:
        return False

    version_match = re.search(r"^version:\s*([^\s#]+)(.*)$", pubspec_text, re.MULTILINE)
    if version_match:
        current_value = version_match.group(1).strip()
        suffix = version_match.group(2)
        version_name, _, build_number_text = current_value.partition("+")
        version_name = version_name.strip() or "1.0.0"
        existing_build_number = int(build_number_text) if build_number_text.isdigit() else 0
        build_number = max(existing_build_number, revision_number)
        next_line = f"version: {version_name}+{build_number}{suffix}"
        next_text = (
            pubspec_text[: version_match.start()]
            + next_line
            + pubspec_text[version_match.end() :]
        )
    else:
        separator = "" if pubspec_text.endswith("\n") else "\n"
        next_text = f"{pubspec_text}{separator}version: 1.0.0+{revision_number}\n"

    if next_text == pubspec_text:
        return False
    pubspec_path.write_text(next_text, encoding="utf-8")
    return True


def create_initial_project_revision(task_root: Path, base_project_path: Path) -> tuple[Path, str]:
    revision_label = "rev_0001"
    revision_root = task_root / "revisions" / revision_label
    project_root = revision_root / "project"
    shutil.copytree(base_project_path, project_root)
    ensure_project_revision_version(project_root, revision_label)
    ensure_workspace_project_link(task_root, project_root)
    return project_root, revision_label


def ignore_project_revision_cache_dirs(path: str, names: list[str]) -> set[str]:
    _ = path
    cache_names = {
        "build",
        ".dart_tool",
        ".gradle",
        ".tooling",
    }
    return {name for name in names if name in cache_names}


def create_followup_project_revision(workspace_path: Path, source_project_path: Path) -> tuple[Path, str]:
    revisions_root = workspace_path / "revisions"
    revisions_root.mkdir(parents=True, exist_ok=True)
    highest_index = 0
    for candidate in revisions_root.iterdir():
        if not candidate.is_dir():
            continue
        match = re.fullmatch(r"rev_(\d{4})", candidate.name)
        if match:
            highest_index = max(highest_index, int(match.group(1)))
    revision_label = f"rev_{highest_index + 1:04d}"
    revision_root = revisions_root / revision_label
    project_root = revision_root / "project"
    shutil.copytree(source_project_path, project_root, ignore=ignore_project_revision_cache_dirs)
    ensure_project_revision_version(project_root, revision_label)
    ensure_workspace_project_link(workspace_path, project_root)
    return project_root, revision_label


def clear_previous_run_artifacts(workspace_path: Path) -> None:
    artifact_paths = (
        workspace_path / ".codex_result" / "task_result.json",
        workspace_path / "logs" / "codex_stdout.log",
        workspace_path / "logs" / "codex_stderr.log",
        workspace_path / "logs" / "build.log",
        workspace_path / "project" / "build" / "app" / "outputs" / "flutter-apk" / "app-debug.apk",
    )
    for artifact_path in artifact_paths:
        try:
            if artifact_path.exists():
                artifact_path.unlink()
        except FileNotFoundError:
            continue


def effective_owner_id(device_id: str, phone_number: Optional[str]) -> str:
    normalized_phone = (phone_number or "").strip()
    if normalized_phone:
        return f"phone_{sanitize_component(normalized_phone)}"
    return f"device_{sanitize_component(device_id)}"


def is_task_access_allowed(
    task: dict[str, Any],
    *,
    device_id: Optional[str] = None,
    phone_number: Optional[str] = None,
) -> bool:
    normalized_phone = (phone_number or "").strip()
    normalized_device = (device_id or "").strip()

    if not normalized_phone and not normalized_device:
        return True
    if normalized_phone and task.get("phone_number") == normalized_phone:
        return True
    if normalized_device and task.get("device_id") == normalized_device:
        return True
    return False


def status_display_text(status: str, message: Optional[str] = None) -> str:
    normalized = status.strip().lower()
    if normalized == "queued":
        return "요청을 대기열에 넣었어요."
    if normalized == "running":
        return "앱을 생성하고 있어요."
    if normalized == "success":
        return "앱 생성이 완료되었어요."
    if normalized == "failed":
        return (message or "앱 생성에 실패했어요.").strip()
    if normalized == "error":
        return (message or "서버 오류가 발생했어요.").strip()
    if normalized == "ratelimited":
        return (message or "앱 생성 한도를 모두 사용했어요.").strip()
    return (message or status).strip() or "상태를 확인하고 있어요."


def build_attempts_for_task(task: dict[str, Any]) -> int:
    if task["status"] in {"Queued", "Running"}:
        return 0
    for key in ("apk_path", "project_path"):
        match = re.search(r"(?:^|[/\\])rev_0*(\d+)(?:[/\\]|$)", str(task.get(key) or ""))
        if match:
            return max(1, int(match.group(1)))
    return 1


def collect_raw_log_sections(
    workspace_root: Path,
    build_log_hint: Optional[str] = None,
    *,
    full: bool = False,
) -> list[dict[str, str]]:
    sections: list[dict[str, str]] = []
    if build_log_hint:
        try:
            build_log_path = resolve_workspace_path(workspace_root, build_log_hint)
            if build_log_path.exists():
                sections.append(
                    {
                        "title": "빌드 로그",
                        "content": sanitize_user_visible_text(
                            read_text_if_exists(build_log_path, limit=None if full else 20000)
                        ),
                    }
                )
        except ValueError:
            sections.append({"title": "빌드 로그", "content": "잘못된 로그 경로가 감지되었습니다."})

    for relative_path, title in (
        ("logs/codex_stdout.log", "작업 표준 출력"),
        ("logs/codex_stderr.log", "작업 오류 출력"),
    ):
        path = workspace_root / relative_path
        if path.exists():
            if relative_path == "logs/codex_stdout.log":
                agent_jsonl = extract_codex_agent_message_jsonl(path, max_messages=None if full else 120)
                if agent_jsonl:
                    sections.append(
                        {
                            "title": "작업 엔진 메시지",
                            "content": sanitize_user_visible_text(agent_jsonl),
                        }
                    )
            sections.append(
                {
                    "title": title,
                    "content": sanitize_user_visible_text(
                        read_text_if_exists(path, limit=None if full else 120000)
                    ),
                }
            )
    return [section for section in sections if section.get("content")]


def collect_task_logs(workspace_root: Path, build_log_hint: Optional[str] = None, *, full: bool = False) -> str:
    sections = collect_raw_log_sections(workspace_root, build_log_hint, full=full)
    return "\n\n".join(
        f"[{section['title']}]\n{section['content']}".strip()
        for section in sections
        if section.get("content")
    ).strip()


def collect_live_task_logs(task: dict[str, Any], log_line_limit: int) -> tuple[str, list[str]]:
    db_log_text = task.get("log") or ""
    workspace_value = (task.get("workspace_path") or "").strip()
    if not workspace_value:
        return db_log_text, tail_lines(db_log_text, log_line_limit)

    workspace_root = Path(workspace_value)
    if not workspace_root.exists() or not workspace_root.is_dir():
        return db_log_text, tail_lines(db_log_text, log_line_limit)

    live_log_text = collect_task_logs(workspace_root, "logs/build.log")
    if not live_log_text:
        live_log_text = db_log_text
    return live_log_text, tail_lines(live_log_text, log_line_limit)


def load_task_state_payload(task: dict[str, Any]) -> dict[str, Any]:
    raw_value = task.get("codex_result_json")
    if not raw_value:
        return {}
    try:
        payload = json.loads(raw_value)
    except (TypeError, json.JSONDecodeError):
        return {}
    return payload if isinstance(payload, dict) else {}


def update_task_state_app_name(task: dict[str, Any], app_name: str) -> Optional[str]:
    state_payload = load_task_state_payload(task)
    if not state_payload:
        return None
    state_payload["app_name"] = app_name
    state_payload["generated_app_name"] = app_name
    conversation_state = state_payload.get("conversation_state")
    if not isinstance(conversation_state, dict):
        conversation_state = {}
    conversation_state["app_name"] = app_name
    conversation_state["generated_app_name"] = app_name
    if normalize_whitespace(str(conversation_state.get("pending_app_name") or "")):
        conversation_state["pending_app_name"] = app_name
    state_payload["conversation_state"] = conversation_state
    return json.dumps(state_payload, ensure_ascii=False)


def current_task_app_name(task: dict[str, Any], conversation_state: Optional[dict[str, Any]] = None) -> str:
    state_payload = load_task_state_payload(task)
    state = conversation_state if isinstance(conversation_state, dict) else state_payload.get("conversation_state")
    if not isinstance(state, dict):
        state = {}
    for value in (
        task.get("app_name"),
        state.get("app_name"),
        state_payload.get("app_name"),
        state.get("generated_app_name"),
        state_payload.get("generated_app_name"),
        state.get("pending_app_name"),
    ):
        app_name = normalize_task_app_name(str(value or ""))
        if app_name and app_name != "맞춤 앱":
            return app_name
    return ""


def current_task_package_name(task: dict[str, Any], conversation_state: Optional[dict[str, Any]] = None) -> str:
    state_payload = load_task_state_payload(task)
    state = conversation_state if isinstance(conversation_state, dict) else state_payload.get("conversation_state")
    if not isinstance(state, dict):
        state = {}
    for value in (
        task.get("package_name"),
        state.get("package_name"),
        state_payload.get("package_name"),
        state.get("pending_package_name"),
    ):
        package_name = normalize_whitespace(str(value or ""))
        if package_name:
            return package_name
    return ""


def preserve_followup_task_identity(
    decision: IntentDecision,
    task: dict[str, Any],
    conversation_state: Optional[dict[str, Any]] = None,
) -> IntentDecision:
    app_name = current_task_app_name(task, conversation_state)
    package_name = current_task_package_name(task, conversation_state)
    replacements: dict[str, Any] = {}
    if app_name and decision.app_name != app_name:
        replacements["app_name"] = app_name
    if package_name and decision.package_name != package_name:
        replacements["package_name"] = package_name
    return replace(decision, **replacements) if replacements else decision


def normalize_context_list(value: Any, max_items: int = 8) -> list[str]:
    if not isinstance(value, list):
        return []
    items: list[str] = []
    seen: set[str] = set()
    for item in value:
        text = normalize_whitespace(str(item or ""))
        if not text or text in seen:
            continue
        seen.add(text)
        items.append(text)
        if len(items) >= max_items:
            break
    return items


def task_has_app_context(task: dict[str, Any], state_payload: Optional[dict[str, Any]] = None) -> bool:
    payload = state_payload if isinstance(state_payload, dict) else load_task_state_payload(task)
    return any(
        normalize_whitespace(str(value or ""))
        for value in (
            task.get("workspace_path"),
            task.get("project_path"),
            task.get("apk_url"),
            task.get("app_name"),
            payload.get("app_name"),
            payload.get("package_name"),
        )
    )


def build_task_conversation_state(task: dict[str, Any]) -> dict[str, Any]:
    state_payload = load_task_state_payload(task)
    existing_state = state_payload.get("conversation_state") if isinstance(state_payload.get("conversation_state"), dict) else {}
    conversation_state = dict(existing_state)

    initial_prompt = normalize_whitespace(
        str(conversation_state.get("initial_user_prompt") or task.get("prompt") or "")
    )
    if initial_prompt:
        conversation_state["initial_user_prompt"] = initial_prompt

    build_request_prompt = normalize_whitespace(
        str(task.get("build_request_prompt") or conversation_state.get("latest_effective_user_prompt") or task.get("prompt") or "")
    )
    if build_request_prompt:
        conversation_state.setdefault("latest_effective_user_prompt", build_request_prompt)

    normalized_prompt = normalize_whitespace(str(task.get("normalized_prompt") or state_payload.get("normalized_prompt") or ""))
    if normalized_prompt:
        conversation_state.setdefault("latest_normalized_prompt", normalized_prompt)

    app_name = normalize_whitespace(str(task.get("app_name") or state_payload.get("app_name") or conversation_state.get("app_name") or ""))
    package_name = normalize_whitespace(str(task.get("package_name") or state_payload.get("package_name") or conversation_state.get("package_name") or ""))
    if app_name:
        conversation_state["app_name"] = app_name
        conversation_state["generated_app_name"] = app_name
    if package_name:
        conversation_state["package_name"] = package_name

    task_status = normalize_whitespace(str(task.get("status") or ""))
    result_status = normalize_whitespace(str(state_payload.get("status") or ""))
    build_success = task_status == "Success" or result_status.lower() == "success"
    conversation_state["build_success"] = build_success
    if task_status:
        conversation_state["task_status"] = task_status
        conversation_state["status_display_text"] = status_display_text(task_status, task.get("message"))
    if task.get("apk_url"):
        conversation_state["apk_url"] = task.get("apk_url")

    for source_key, target_key in (
        ("implemented_requirements", "implemented_requirements"),
        ("verification_notes", "verification_notes"),
        ("known_limitations", "known_limitations"),
    ):
        items = normalize_context_list(state_payload.get(source_key))
        if items:
            conversation_state[target_key] = items

    for key in ("app_llm_enabled", "app_llm_model", "app_llm_system_prompt"):
        if key in state_payload and state_payload.get(key) not in (None, ""):
            conversation_state[key] = state_payload.get(key)

    request_scope = normalize_whitespace(str(conversation_state.get("request_scope") or ""))
    if request_scope not in {"new_app", "existing_app_modification", "non_app_request"}:
        request_scope = "existing_app_modification" if task_has_app_context(task, state_payload) else "new_app"
    if task_has_app_context(task, state_payload) and request_scope == "non_app_request":
        request_scope = "existing_app_modification"
    conversation_state["request_scope"] = request_scope
    return conversation_state


def build_current_app_context(conversation_state: Optional[dict[str, Any]]) -> dict[str, Any]:
    state = conversation_state or {}
    return {
        "app_name": state.get("generated_app_name") or state.get("app_name") or "",
        "package_name": state.get("package_name") or "",
        "build_success": bool(state.get("build_success")),
        "task_status": state.get("task_status") or "",
        "initial_user_prompt": state.get("initial_user_prompt") or "",
        "latest_effective_user_prompt": state.get("latest_effective_user_prompt") or "",
        "primary_user_flow": state.get("latest_primary_user_flow") or state.get("pending_primary_user_flow") or "",
        "secondary_requirements": normalize_context_list(
            state.get("latest_secondary_requirements") or state.get("pending_secondary_requirements")
        ),
        "acceptance_criteria": normalize_context_list(
            state.get("latest_acceptance_criteria") or state.get("pending_acceptance_criteria")
        ),
        "implemented_requirements": normalize_context_list(state.get("implemented_requirements")),
        "verification_notes": normalize_context_list(state.get("verification_notes")),
        "known_limitations": normalize_context_list(state.get("known_limitations")),
    }


def build_contextual_app_answer_message(prompt: str, conversation_state: Optional[dict[str, Any]]) -> str:
    context = build_current_app_context(conversation_state)
    app_name = normalize_whitespace(str(context.get("app_name") or ""))
    feature_items = (
        normalize_context_list(context.get("implemented_requirements"))
        or normalize_context_list(context.get("acceptance_criteria"))
        or normalize_context_list(context.get("secondary_requirements"))
    )
    primary_flow = normalize_whitespace(str(context.get("primary_user_flow") or ""))
    latest_effective_prompt = normalize_whitespace(str(context.get("latest_effective_user_prompt") or ""))
    has_context = bool(app_name or feature_items or primary_flow or latest_effective_prompt)
    if not has_context:
        return ""

    lowered = prompt.lower()
    asks_usage = any(token in lowered for token in ("사용법", "쓰는 법", "사용 방법", "어떻게 써", "어떻게 사용", "어떻게 하면"))
    asks_built = any(token in lowered for token in ("뭐 만들", "무슨 앱", "어떤 앱", "기능", "설명", "알려"))
    if not (asks_usage or asks_built):
        return ""

    title = app_name or "이 앱"
    feature_summary = ", ".join(feature_items[:3])
    if not feature_summary:
        feature_summary = primary_flow or latest_effective_prompt
    limitation_items = normalize_context_list(context.get("known_limitations"), max_items=2)
    limitation_sentence = f" 현재 제한사항은 {', '.join(limitation_items)}입니다." if limitation_items else ""

    if asks_usage:
        return f"{title}은 {feature_summary}을 중심으로 쓰는 앱이에요. 앱을 열고 첫 화면의 주요 입력이나 목록에서 필요한 항목을 추가한 뒤, 저장된 기록이나 결과 화면을 확인하면 됩니다.{limitation_sentence}"
    return f"{title}은 {feature_summary}을 위해 만들어진 앱이에요.{limitation_sentence}"


def make_decision_state(task: dict[str, Any], decision: IntentDecision, user_prompt: Optional[str] = None) -> dict[str, Any]:
    latest_user_prompt = user_prompt or task.get("prompt") or ""
    previous_conversation_state = build_task_conversation_state(task)
    pending_prompt = decision.effective_user_prompt if decision.mode == "ask_confirmation" else ""
    pending_normalized_prompt = decision.normalized_prompt if decision.mode == "ask_confirmation" else ""
    preserved_app_name = current_task_app_name(task, previous_conversation_state)
    preserved_package_name = current_task_package_name(task, previous_conversation_state)
    resolved_app_name = preserved_app_name or decision.app_name
    resolved_package_name = preserved_package_name or decision.package_name
    pending_app_name = resolved_app_name if decision.mode == "ask_confirmation" else preserved_app_name
    pending_package_name = resolved_package_name if decision.mode == "ask_confirmation" else preserved_package_name
    pending_acceptance_criteria = decision.acceptance_criteria if decision.mode == "ask_confirmation" else []
    device_info = serialize_device_info(task.get("device_info"))
    reference_image_name = normalize_reference_image_name(task.get("reference_image_name"))
    reference_image_base64 = normalize_reference_image_base64(task.get("reference_image_base64"))
    reference_image_workspace_path = normalize_whitespace(str(task.get("reference_image_workspace_path") or ""))
    ui_flags = decision_ui_flags(decision)
    state_request_scope = decision.request_scope
    previous_request_scope = normalize_whitespace(str(previous_conversation_state.get("request_scope") or ""))
    if decision.mode == "answer_question" and previous_request_scope in {"new_app", "existing_app_modification"}:
        state_request_scope = previous_request_scope
    latest_primary_user_flow = decision.primary_user_flow or str(previous_conversation_state.get("latest_primary_user_flow") or "")
    latest_secondary_requirements = decision.secondary_requirements or normalize_context_list(
        previous_conversation_state.get("latest_secondary_requirements")
    )
    latest_acceptance_criteria = decision.acceptance_criteria or normalize_context_list(
        previous_conversation_state.get("latest_acceptance_criteria")
    )
    latest_summary = decision.summary or str(previous_conversation_state.get("latest_summary") or "")
    return {
        "status": decision.status,
        "tool": decision.tool,
        "message": decision.message,
        "summary": decision.summary,
        "questions": decision.questions,
        "reason": decision.reason,
        "request_scope": decision.request_scope,
        "requires_existing_task_context": decision.requires_existing_task_context,
        "app_name": resolved_app_name,
        "package_name": resolved_package_name,
        "primary_user_flow": decision.primary_user_flow,
        "secondary_requirements": decision.secondary_requirements,
        "secondary_scope_confirmed": decision.secondary_scope_confirmed,
        "acceptance_criteria": decision.acceptance_criteria,
        "confirmation_action": decision.confirmation_action,
        "confirmation_payload": decision.confirmation_payload,
        "image_reference_summary": decision.image_reference_summary,
        "image_conflict_note": decision.image_conflict_note,
        **ui_flags,
        "conversation_state": {
            **previous_conversation_state,
            "initial_user_prompt": previous_conversation_state.get("initial_user_prompt") or task.get("prompt") or "",
            "app_name": resolved_app_name,
            "generated_app_name": resolved_app_name,
            "package_name": resolved_package_name,
            "latest_user_prompt": latest_user_prompt,
            "latest_effective_user_prompt": decision.effective_user_prompt,
            "latest_summary": latest_summary,
            "latest_assistant_questions": decision.questions,
            "latest_primary_user_flow": latest_primary_user_flow,
            "latest_secondary_requirements": latest_secondary_requirements,
            "latest_secondary_scope_confirmed": decision.secondary_scope_confirmed or bool(previous_conversation_state.get("latest_secondary_scope_confirmed")),
            "latest_acceptance_criteria": latest_acceptance_criteria,
            "awaiting_confirmation": decision.mode == "ask_confirmation",
            "confirmation_action": decision.confirmation_action,
            "confirmation_payload": decision.confirmation_payload,
            **ui_flags,
            "pending_user_prompt": pending_prompt,
            "pending_normalized_prompt": pending_normalized_prompt,
            "pending_app_name": pending_app_name,
            "pending_package_name": pending_package_name,
            "pending_primary_user_flow": decision.primary_user_flow if decision.mode == "ask_confirmation" else "",
            "pending_secondary_requirements": decision.secondary_requirements if decision.mode == "ask_confirmation" else [],
            "pending_secondary_scope_confirmed": decision.secondary_scope_confirmed if decision.mode == "ask_confirmation" else False,
            "pending_acceptance_criteria": pending_acceptance_criteria,
            "used_previous_pending_prompt": decision.used_previous_pending_prompt,
            "request_scope": state_request_scope,
            "requires_existing_task_context": decision.requires_existing_task_context,
            "device_info": device_info,
            "reference_image_name": reference_image_name,
            "reference_image_base64": reference_image_base64,
            "reference_image_workspace_path": reference_image_workspace_path,
            "image_reference_summary": decision.image_reference_summary,
            "image_conflict_note": decision.image_conflict_note,
        },
        "recent_messages": [
            {
                "role": "user",
                "message_type": "prompt",
                "content": latest_user_prompt,
                "reference_image_name": reference_image_name,
                "created_at": utc_now_iso(),
            },
            {
                "role": "confirmation" if decision.confirmation_action else ("status" if ui_flags["suppress_assistant_bubble"] else "assistant"),
                "message_type": "confirmation" if decision.confirmation_action else ("status" if ui_flags["suppress_assistant_bubble"] else decision.tool),
                "content": decision.message,
                "created_at": utc_now_iso(),
            }
        ],
    }


def build_assistant_response_payload(decision: IntentDecision) -> dict[str, Any]:
    ui_flags = decision_ui_flags(decision)
    return {
        "status": decision.status,
        "tool": decision.tool,
        "message": decision.message,
        "summary": decision.summary,
        "questions": decision.questions,
        "reason": decision.reason,
        "request_scope": decision.request_scope,
        "requires_existing_task_context": decision.requires_existing_task_context,
        "app_name": decision.app_name,
        "package_name": decision.package_name,
        "primary_user_flow": decision.primary_user_flow,
        "secondary_requirements": decision.secondary_requirements,
        "secondary_scope_confirmed": decision.secondary_scope_confirmed,
        "acceptance_criteria": decision.acceptance_criteria,
        "confirmation_action": decision.confirmation_action,
        "confirmation_payload": decision.confirmation_payload,
        "image_reference_summary": decision.image_reference_summary,
        "image_conflict_note": decision.image_conflict_note,
        **ui_flags,
        "effective_user_prompt": decision.effective_user_prompt,
        "used_previous_pending_prompt": decision.used_previous_pending_prompt,
    }


def build_decision_response(task_id: str, decision: IntentDecision) -> dict[str, Any]:
    payload = build_assistant_response_payload(decision)
    payload["task_id"] = task_id
    return payload


def build_task_status_payload(task: dict[str, Any]) -> dict[str, Any]:
    apk_path_value = str(task.get("apk_path") or "")
    apk_size_bytes = None
    if apk_path_value:
        apk_path = Path(apk_path_value)
        if apk_path.exists() and apk_path.is_file():
            apk_size_bytes = apk_path.stat().st_size
    return {
        "status": task.get("status") or "",
        "message": task.get("message") or "",
        "app_name": task.get("app_name") or "",
        "package_name": task.get("package_name") or "",
        "apk_url": task.get("apk_url") or "",
        "apk_path": task.get("apk_path") or "",
        "apk_size_bytes": apk_size_bytes,
        "input_tokens": task.get("input_tokens"),
        "cached_input_tokens": task.get("cached_input_tokens"),
        "output_tokens": task.get("output_tokens"),
        "reasoning_output_tokens": task.get("reasoning_output_tokens"),
        "total_tokens": task.get("total_tokens"),
    }


def log_task_status_event(db: Database, task: dict[str, Any], *, event_type: str = "task_status") -> None:
    db.log_event(
        str(task["task_id"]),
        actor="system",
        event_type=event_type,
        message_text=str(task.get("message") or task.get("status") or ""),
        payload=build_task_status_payload(task),
    )


def log_package_name_event(
    db: Database,
    task_id: str,
    *,
    package_name: str,
    app_name: str = "",
    event_type: str = "package_name_recorded",
) -> None:
    if not package_name:
        return
    db.log_event(
        task_id,
        actor="system",
        event_type=event_type,
        message_text=package_name,
        payload={
            "package_name": package_name,
            "app_name": app_name,
        },
    )


def log_token_usage_event(db: Database, task_id: str, usage: CodexUsage, *, model: str) -> None:
    db.log_event(
        task_id,
        actor="system",
        event_type="token_usage_recorded",
        message_text=f"total_tokens={usage.total_tokens}",
            payload={
                "source": "codex",
                "model": model,
                "input_tokens": usage.input_tokens,
                "cached_input_tokens": usage.cached_input_tokens,
            "output_tokens": usage.output_tokens,
            "reasoning_output_tokens": usage.reasoning_output_tokens,
            "total_tokens": usage.total_tokens,
        },
    )
    db.record_task_usage(
        task_id,
        TaskUsageRecord(
            source="codex",
            model=model,
            input_tokens=usage.input_tokens,
            cached_input_tokens=usage.cached_input_tokens,
            output_tokens=usage.output_tokens,
            cached_output_tokens=None,
            reasoning_output_tokens=usage.reasoning_output_tokens,
            total_tokens=usage.total_tokens,
            status="recorded",
            payload={
                "source": "codex",
            },
        ),
    )


def log_build_stage_event(
    db: Database,
    task_id: str,
    *,
    stage: str,
    phase: str,
    body: str,
    detail: str = "",
) -> None:
    db.log_event(
        task_id,
        actor="system",
        event_type=f"build_stage_{phase}",
        message_text=body,
        payload={
            "stage": stage,
            "phase": phase,
            "detail": detail,
        },
    )


def parse_event_payload(row: dict[str, Any]) -> dict[str, Any]:
    raw_payload = row.get("payload_json")
    if not raw_payload:
        return {}
    try:
        parsed = json.loads(str(raw_payload))
    except json.JSONDecodeError:
        return {}
    return parsed if isinstance(parsed, dict) else {}


def task_event_to_timeline_event(row: dict[str, Any]) -> Optional[dict[str, str]]:
    event_type = str(row.get("event_type") or "")
    actor = str(row.get("actor") or "")
    message_text = sanitize_user_visible_text(str(row.get("message_text") or ""))
    payload = parse_event_payload(row)
    created_at = str(row.get("created_at") or "")
    event_id = str(row.get("event_id") or "")

    kind = "log"
    title = "로그"
    body = message_text
    detail = ""

    if event_type == "user_message":
        kind = "user"
        title = "나"
        body = message_text or sanitize_user_visible_text(str(payload.get("raw_prompt") or ""))
    elif event_type == "assistant_message":
        kind = "assistant"
        title = "AI"
        body = message_text
    elif event_type in {"task_status", "task_succeeded", "task_failed", "task_error", "task_timeout"}:
        kind = "status"
        title = "상태"
        body = sanitize_user_visible_text(
            str(payload.get("message") or payload.get("status") or message_text or "상태가 바뀌었습니다.")
        )
        detail = sanitize_user_visible_text(str(payload.get("status") or ""))
    elif event_type.startswith("build_stage_"):
        kind = "log"
        title = "빌드"
        stage = sanitize_user_visible_text(str(payload.get("stage") or ""))
        phase = sanitize_user_visible_text(str(payload.get("phase") or ""))
        detail = sanitize_user_visible_text(str(payload.get("detail") or ""))
        body = message_text or "빌드 단계가 진행 중입니다."
        if stage and phase:
            detail = "\n".join(part for part in [f"단계: {stage}", f"상태: {phase}", detail] if part).strip()
    elif event_type == "user_interaction":
        kind = "status"
        title = "상호작용"
        body = message_text or "사용자 확인이 반영되었습니다."
        detail = sanitize_user_visible_text(str(payload.get("interaction") or ""))
    elif event_type == "runtime_error_detected":
        kind = "log"
        title = "오류"
        body = message_text or "런타임 오류가 감지되었습니다."
        detail = sanitize_user_visible_text(str(payload.get("stack_trace") or payload.get("summary") or ""))
    elif event_type in {"package_name_selected", "package_name_confirmed", "package_name_recorded"}:
        kind = "log"
        title = "메타데이터"
        app_name = sanitize_user_visible_text(str(payload.get("app_name") or ""))
        package_name = sanitize_user_visible_text(str(payload.get("package_name") or message_text))
        body = app_name or "앱 메타데이터가 확정되었습니다."
        detail = package_name
    elif event_type == "token_usage_recorded":
        kind = "log"
        title = "토큰"
        total_tokens = payload.get("total_tokens")
        body = f"토큰 사용량이 기록되었습니다. 총 {total_tokens}토큰" if total_tokens is not None else "토큰 사용량이 기록되었습니다."
        detail = sanitize_user_visible_text(
            "\n".join(
                filter(
                    None,
                    [
                        f"입력: {payload.get('input_tokens')}" if payload.get("input_tokens") is not None else "",
                        f"캐시 입력: {payload.get('cached_input_tokens')}" if payload.get("cached_input_tokens") is not None else "",
                        f"출력: {payload.get('output_tokens')}" if payload.get("output_tokens") is not None else "",
                        f"사고 출력: {payload.get('reasoning_output_tokens')}" if payload.get("reasoning_output_tokens") is not None else "",
                    ],
                )
            )
        )
    else:
        if event_type == "agent_raw_output" or event_type.startswith("app_llm_config_"):
            return None
        kind = "log" if actor == "system" else "assistant"
        title = "로그" if kind == "log" else "AI"
        body = message_text or sanitize_user_visible_text(str(payload.get("message") or ""))

    body = sanitize_user_visible_text(body).strip()
    detail = sanitize_user_visible_text(detail).strip()
    if not body:
        return None
    event = {
        "event_id": event_id,
        "created_at": created_at,
        "kind": kind,
        "title": title,
        "body": body,
        "detail": detail,
        "event_type": event_type,
    }
    for key in ("apk_url", "apk_path", "app_name", "package_name"):
        value = sanitize_user_visible_text(str(payload.get(key) or ""))
        if value:
            event[key] = value
    if payload.get("apk_size_bytes") is not None:
        event["apk_size_bytes"] = str(payload.get("apk_size_bytes"))
    return event


def build_task_timeline_events(db: Database, task_id: str) -> list[dict[str, str]]:
    timeline: list[dict[str, str]] = []
    for row in db.list_events(task_id):
        event = task_event_to_timeline_event(row)
        if event:
            timeline.append(event)
    return timeline


def derive_current_build_stage(task: dict[str, Any], timeline_events: list[dict[str, str]]) -> tuple[str, str]:
    status = str(task.get("status") or "")
    message = sanitize_user_visible_text(str(task.get("message") or ""))
    if status == "Running":
        for event in reversed(timeline_events):
            if event.get("event_type") == "build_stage_started":
                return event.get("body") or "빌드 진행 중", event.get("detail") or message
        return "빌드 진행 중", message
    if status == "Queued":
        return "작업 대기 중", message or status_display_text(status, message)
    if status == "Pending Decision":
        return "명세 확인 중", message or status_display_text(status, message)
    return "", ""


def write_result_json(result_path: Path, payload: dict[str, Any]) -> None:
    result_path.parent.mkdir(parents=True, exist_ok=True)
    result_path.write_text(
        json.dumps(payload, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )


def infer_project_app_name(project_root: Path) -> str:
    manifest_path = project_root / "android" / "app" / "src" / "main" / "AndroidManifest.xml"
    manifest_text = read_text_if_exists(manifest_path, limit=100_000)
    label_match = re.search(r'android:label="([^"]+)"', manifest_text)
    if label_match and label_match.group(1).strip():
        return label_match.group(1).strip()

    pubspec_path = project_root / "pubspec.yaml"
    pubspec_text = read_text_if_exists(pubspec_path, limit=50_000)
    name_match = re.search(r"^name:\s*([A-Za-z0-9_.-]+)\s*$", pubspec_text, re.MULTILINE)
    if name_match and name_match.group(1).strip():
        return name_match.group(1).strip()
    return "Generated App"


def infer_project_package_name(project_root: Path) -> str:
    gradle_path = project_root / "android" / "app" / "build.gradle.kts"
    gradle_text = read_text_if_exists(gradle_path, limit=100_000)
    app_id_match = re.search(r'applicationId\s*=\s*"([^"]+)"', gradle_text)
    if app_id_match and app_id_match.group(1).strip():
        return app_id_match.group(1).strip()
    namespace_match = re.search(r'namespace\s*=\s*"([^"]+)"', gradle_text)
    if namespace_match and namespace_match.group(1).strip():
        return namespace_match.group(1).strip()
    return "com.example.generatedapp"


def project_looks_like_placeholder_app(project_root: Path) -> bool:
    main_dart_path = project_root / "lib" / "main.dart"
    main_dart_text = read_text_if_exists(main_dart_path, limit=200_000)
    if not main_dart_text:
        return False
    normalized = re.sub(r"\s+", " ", main_dart_text)
    placeholder_markers = (
        'Text("Generated App")',
        "Text('Generated App')",
        'title: "Generated App"',
        "title: 'Generated App'",
    )
    return any(marker in normalized for marker in placeholder_markers)


def make_error_result(task_id: str, message: str, build_log_path: str = "logs/build.log") -> dict[str, Any]:
    return {
        "status": "failed",
        "task_id": task_id,
        "error_stage": "unknown",
        "message": message,
        "build_log_path": build_log_path,
    }


def parse_codex_usage_from_jsonl(path: Path) -> Optional[CodexUsage]:
    if not path.exists() or not path.is_file():
        return None

    latest_usage: Optional[CodexUsage] = None
    for raw_line in path.read_text(encoding="utf-8", errors="replace").splitlines():
        line = raw_line.strip()
        if not line or not line.startswith("{"):
            continue
        try:
            payload = json.loads(line)
        except json.JSONDecodeError:
            continue
        if not isinstance(payload, dict):
            continue

        usage_container: Optional[dict[str, Any]] = None
        if payload.get("type") == "event_msg":
            event_payload = payload.get("payload")
            if isinstance(event_payload, dict) and event_payload.get("type") == "token_count":
                info = event_payload.get("info")
                if isinstance(info, dict):
                    usage_container = info.get("total_token_usage") or info.get("last_token_usage")
        elif payload.get("type") == "token_count":
            info = payload.get("info")
            if isinstance(info, dict):
                usage_container = info.get("total_token_usage") or info.get("last_token_usage")
        elif isinstance(payload.get("usage"), dict):
            usage_container = payload.get("usage")
        elif isinstance(payload.get("response"), dict) and isinstance(payload.get("response", {}).get("usage"), dict):
            usage_container = payload.get("response", {}).get("usage")
        elif isinstance(payload.get("payload"), dict) and isinstance(payload.get("payload", {}).get("usage"), dict):
            usage_container = payload.get("payload", {}).get("usage")
        elif isinstance(payload.get("item"), dict) and isinstance(payload.get("item", {}).get("usage"), dict):
            usage_container = payload.get("item", {}).get("usage")

        if not isinstance(usage_container, dict):
            continue

        normalized_usage = parse_response_usage_payload({"usage": usage_container})
        if all(normalized_usage.get(field) is None for field in (
            "input_tokens",
            "cached_input_tokens",
            "output_tokens",
            "reasoning_output_tokens",
            "total_tokens",
        )):
            continue

        latest_usage = CodexUsage(
            input_tokens=int(normalized_usage.get("input_tokens") or 0),
            cached_input_tokens=int(normalized_usage.get("cached_input_tokens") or 0),
            output_tokens=int(normalized_usage.get("output_tokens") or 0),
            reasoning_output_tokens=int(normalized_usage.get("reasoning_output_tokens") or 0),
            total_tokens=int(normalized_usage.get("total_tokens") or 0),
        )
    return latest_usage


def utc_day_start_iso(now_value: Optional[datetime] = None) -> str:
    current = now_value or datetime.now(timezone.utc)
    return current.astimezone(timezone.utc).replace(hour=0, minute=0, second=0, microsecond=0).isoformat()


def build_app_runtime_instructions(config: dict[str, Any], request: AppLlmRuntimeRequest) -> str:
    base_prompt = str(config.get("system_prompt") or "").strip()
    package_name = request.package_name.strip()
    package_clause = f"이 응답은 Android 앱 패키지 `{package_name}`용 기능입니다."
    if base_prompt:
        return f"{base_prompt}\n\n{package_clause}"
    return (
        "사용자가 보낸 텍스트와 이미지를 바탕으로 실용적이고 구체적인 조언을 한국어로 제공하세요. "
        "추측은 줄이고, 관찰 가능한 내용과 실행 가능한 제안을 우선하세요.\n\n"
        f"{package_clause}"
    )


def invoke_app_runtime_model(config: dict[str, Any], request: AppLlmRuntimeRequest) -> dict[str, Any]:
    provider = str(config.get("provider") or "openai").strip().lower()
    if provider != "openai":
        raise ValueError("unsupported provider")

    api_key = str(config.get("api_key") or "").strip()
    if not api_key:
        raise ValueError("missing api key")

    base_url = str(config.get("base_url") or "https://api.openai.com/v1/responses").strip() or "https://api.openai.com/v1/responses"
    content_items: list[dict[str, Any]] = []
    user_parts = [request.user_message.strip()]
    if request.context and request.context.strip():
        user_parts.append(f"추가 맥락: {request.context.strip()}")
    content_items.append(
        {
            "type": "input_text",
            "text": "\n\n".join(part for part in user_parts if part),
        }
    )
    if request.image_base64 and request.image_base64.strip():
        mime_type = (request.image_mime_type or "image/jpeg").strip() or "image/jpeg"
        image_data = request.image_base64.strip()
        if not image_data.startswith("data:"):
            image_data = f"data:{mime_type};base64,{image_data}"
        content_items.append({"type": "input_image", "image_url": image_data})

    payload = {
        "model": str(config.get("model") or "gpt-5.4-mini"),
        "instructions": build_app_runtime_instructions(config, request),
        "input": [
            {
                "role": "user",
                "content": content_items,
            }
        ],
        "max_output_tokens": int(config.get("max_output_tokens") or 700),
        "temperature": float(config.get("temperature") or 0.4),
    }

    with httpx.Client(timeout=httpx.Timeout(timeout=60.0, connect=10.0)) as client:
        response = client.post(
            base_url,
            headers={
                "Authorization": f"Bearer {api_key}",
                "Content-Type": "application/json",
            },
            json=payload,
        )
        response.raise_for_status()
        response_payload = response.json()

    output_text = extract_response_output_text(response_payload)
    usage_payload = response_payload.get("usage") if isinstance(response_payload.get("usage"), dict) else {}
    return {
        "message": output_text,
        "usage": {
            "input_tokens": int(usage_payload.get("input_tokens") or 0),
            "output_tokens": int(usage_payload.get("output_tokens") or 0),
            "total_tokens": int(usage_payload.get("total_tokens") or 0),
        },
        "raw_response": response_payload,
    }


class CodexTaskRunner:
    def __init__(self, settings: Settings, db: Database) -> None:
        self.settings = settings
        self.db = db
        self.queue: queue.Queue[Optional[str]] = queue.Queue()
        self.stop_event = threading.Event()
        self.threads: list[threading.Thread] = []
        self.active_processes: dict[int, subprocess.Popen] = {}
        self.process_lock = threading.Lock()

    def start(self) -> None:
        if self.threads:
            return
        for index in range(self.settings.max_concurrent_codex_runs):
            thread = threading.Thread(
                target=self.worker_loop,
                name=f"codex-task-runner-{index}",
                daemon=True,
            )
            thread.start()
            self.threads.append(thread)

    def stop(self) -> None:
        self.stop_event.set()
        self.terminate_active_processes()
        for _ in self.threads:
            self.queue.put(None)
        for thread in self.threads:
            thread.join(timeout=5)
        self.threads.clear()

    def register_process(self, process: subprocess.Popen) -> None:
        with self.process_lock:
            self.active_processes[id(process)] = process

    def unregister_process(self, process: subprocess.Popen) -> None:
        with self.process_lock:
            self.active_processes.pop(id(process), None)

    def terminate_active_processes(self) -> None:
        with self.process_lock:
            processes = list(self.active_processes.values())
        for process in processes:
            if process.poll() is not None:
                self.unregister_process(process)
                continue
            try:
                process.terminate()
            except ProcessLookupError:
                self.unregister_process(process)
        for process in processes:
            try:
                process.wait(timeout=2)
            except subprocess.TimeoutExpired:
                try:
                    process.kill()
                except ProcessLookupError:
                    pass
                process.wait()
            finally:
                self.unregister_process(process)

    def enqueue(self, task_id: str) -> None:
        self.queue.put(task_id)

    def worker_loop(self) -> None:
        while not self.stop_event.is_set():
            task_id = self.queue.get()
            if task_id is None:
                self.queue.task_done()
                break
            try:
                self.process_task(task_id)
            except Exception as exc:
                self.db.update_task(
                    task_id,
                    status="Error",
                    message=f"서버 내부 오류: {exc}",
                )
                task = self.db.get_task(task_id)
                if task:
                    log_task_status_event(self.db, task, event_type="task_error")
            finally:
                self.queue.task_done()

    def process_task(self, task_id: str) -> None:
        task = self.db.get_task(task_id)
        if not task:
            return

        workspace_path = Path(task["workspace_path"])
        result_path = workspace_path / ".codex_result" / "task_result.json"
        stdout_path = workspace_path / "logs" / "codex_stdout.log"
        stderr_path = workspace_path / "logs" / "codex_stderr.log"

        clear_previous_run_artifacts(workspace_path)
        self.db.update_task(task_id, status="Running", message="앱 생성 작업을 진행하고 있습니다.")
        updated_task = self.db.get_task(task_id)
        if updated_task:
            log_task_status_event(self.db, updated_task)
        log_build_stage_event(
            self.db,
            task_id,
            stage="앱 설계와 코드 생성",
            phase="started",
            body="앱 설계와 코드 생성을 시작했어요.",
        )

        exit_code: Optional[int] = None
        timed_out = False

        if self.settings.mock_codex:
            exit_code, timed_out = self.run_mock(task, workspace_path, stdout_path, stderr_path, result_path)
        else:
            exit_code, timed_out = self.run_codex(task, workspace_path, stdout_path, stderr_path)
            if not timed_out and not result_path.exists():
                codex_log_text = collect_task_logs(workspace_path, "logs/build.log", full=True)
                if codex_engine_issue_from_logs(codex_log_text, exit_code) is None:
                    self.attempt_server_side_build(task_id, workspace_path, result_path, exit_code)

        self.finalize_task(task_id, workspace_path, result_path, exit_code, timed_out)

    def run_codex(
        self,
        task: dict[str, Any],
        workspace_path: Path,
        stdout_path: Path,
        stderr_path: Path,
    ) -> tuple[Optional[int], bool]:
        prompt = (workspace_path / "prompt.md").read_text(encoding="utf-8")
        project_path = Path(str(task.get("project_path") or workspace_path / "project"))
        prompt_placeholder = "__CODEX_PROMPT_PLACEHOLDER_6F4A1F45__"
        try:
            command_text = self.settings.codex_command.format(
                prompt=prompt_placeholder,
                task_id=task["task_id"],
                workspace=str(workspace_path),
                project=str(project_path),
            )
        except KeyError as exc:
            raise RuntimeError(f"CODEX_COMMAND placeholder error: {exc}") from exc

        args = shlex.split(command_text)
        args = [part.replace(prompt_placeholder, prompt) for part in args]
        env = self.build_task_env(workspace_path)
        with stdout_path.open("wb") as stdout_file, stderr_path.open("wb") as stderr_file:
            process = subprocess.Popen(
                args,
                cwd=workspace_path,
                env=env,
                stdout=stdout_file,
                stderr=stderr_file,
            )
            self.register_process(process)
            try:
                return self.wait_for_process(process), False
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait()
                return process.returncode, True
            finally:
                self.unregister_process(process)

    def wait_for_process(self, process: subprocess.Popen[Any]) -> int:
        timeout_seconds = self.settings.codex_timeout_seconds
        if timeout_seconds is None:
            return process.wait()
        return process.wait(timeout=timeout_seconds)

    def build_task_env(self, workspace_path: Path) -> dict[str, str]:
        tool_cache_root = workspace_path / ".tooling"
        pub_cache_path = tool_cache_root / "pub-cache"
        gradle_home_path = tool_cache_root / "gradle"
        temp_path = tool_cache_root / "tmp"
        pub_cache_path.mkdir(parents=True, exist_ok=True)
        gradle_home_path.mkdir(parents=True, exist_ok=True)
        temp_path.mkdir(parents=True, exist_ok=True)
        env = os.environ.copy()
        env["PUB_CACHE"] = str(pub_cache_path)
        env["GRADLE_USER_HOME"] = str(gradle_home_path)
        env["TMPDIR"] = str(temp_path)
        env["FLUTTER_SUPPRESS_ANALYTICS"] = "true"
        env["CI"] = "1"
        return env

    def run_logged_command(
        self,
        args: list[str],
        *,
        cwd: Path,
        env: dict[str, str],
        log_path: Path,
    ) -> tuple[int, bool]:
        log_path.parent.mkdir(parents=True, exist_ok=True)
        command_text = " ".join(shlex.quote(part) for part in args)
        with log_path.open("a", encoding="utf-8") as log_file:
            log_file.write(f"\n\n$ {command_text}\n")
            log_file.flush()
            process = subprocess.Popen(
                args,
                cwd=cwd,
                env=env,
                stdout=log_file,
                stderr=subprocess.STDOUT,
                text=True,
            )
            self.register_process(process)
            try:
                return self.wait_for_process(process), False
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait()
                log_file.write("\n[server] command timed out\n")
                log_file.flush()
                return process.returncode or 1, True
            finally:
                self.unregister_process(process)

    def ensure_debug_apk(
        self,
        task_id: str,
        workspace_path: Path,
        project_path: Path,
        current_apk_path: Path,
    ) -> Path:
        version_changed = ensure_project_revision_version(project_path)
        if current_apk_path.name == "app-debug.apk" and current_apk_path.exists() and not version_changed:
            return current_apk_path

        candidate_paths: list[Path] = []
        sibling_debug = current_apk_path.with_name("app-debug.apk")
        candidate_paths.append(sibling_debug)
        candidate_paths.append(
            workspace_path / "project" / "build" / "app" / "outputs" / "flutter-apk" / "app-debug.apk"
        )

        seen: set[Path] = set()
        for candidate in candidate_paths:
            resolved = candidate.resolve()
            if resolved in seen:
                continue
            seen.add(resolved)
            if ensure_within_root(resolved, workspace_path) and resolved.exists() and resolved.stat().st_size > 0:
                return resolved

        build_log_path = workspace_path / "logs" / "build.log"
        env = self.build_task_env(workspace_path)
        flutter_args = shlex.split(self.settings.flutter_command)
        status_message = "설치 가능한 디버그 APK를 준비하고 있어요."
        self.db.update_task(task_id, status="Running", message=status_message)
        running_task = self.db.get_task(task_id)
        if running_task:
            log_task_status_event(self.db, running_task, event_type="build_stage_debug_prepare")
        log_build_stage_event(
            self.db,
            task_id,
            stage="debug apk",
            phase="started",
            body=status_message,
            detail="release 결과 대신 debug APK를 우선 준비합니다.",
        )
        exit_code, timed_out = self.run_logged_command(
            flutter_args + ["build", "apk", "--debug"],
            cwd=project_path,
            env=env,
            log_path=build_log_path,
        )
        if timed_out:
            raise RuntimeError("debug APK 빌드가 시간 제한을 초과했습니다.")
        if exit_code != 0:
            raise RuntimeError(f"debug APK 빌드에 실패했습니다. exit code: {exit_code}")

        debug_apk = (workspace_path / "project" / "build" / "app" / "outputs" / "flutter-apk" / "app-debug.apk").resolve()
        if not ensure_within_root(debug_apk, workspace_path) or not debug_apk.exists() or debug_apk.stat().st_size <= 0:
            raise RuntimeError("debug APK 산출물을 찾을 수 없습니다.")

        log_build_stage_event(
            self.db,
            task_id,
            stage="debug apk",
            phase="succeeded",
            body="설치 가능한 디버그 APK를 준비했어요.",
        )
        return debug_apk

    def attempt_server_side_build(
        self,
        task_id: str,
        workspace_path: Path,
        result_path: Path,
        codex_exit_code: Optional[int],
    ) -> None:
        task = self.db.get_task(task_id) or {}
        project_path = Path(str(task.get("project_path") or workspace_path / "project"))
        ensure_project_revision_version(project_path)
        build_log_path = workspace_path / "logs" / "build.log"
        env = self.build_task_env(workspace_path)
        flutter_args = shlex.split(self.settings.flutter_command)
        stages = [
            ("pub_get", "Flutter 의존성을 설치하고 있어요.", flutter_args + ["pub", "get"]),
            ("analyze", "Flutter 코드를 분석하고 있어요.", flutter_args + ["analyze"]),
            ("build", "Android APK를 빌드하고 있어요.", flutter_args + ["build", "apk", "--debug"]),
        ]

        build_log_path.write_text(
            f"[server] 결과 파일이 없어 서버가 직접 Flutter 검증을 이어갑니다. worker_exit_code={codex_exit_code}\n",
            encoding="utf-8",
        )
        log_build_stage_event(
            self.db,
            task_id,
            stage="서버 검증 빌드",
            phase="started",
            body="서버가 직접 Flutter 검증 빌드를 이어가고 있어요.",
        )

        stage_labels = {
            "pub_get": "pub get",
            "analyze": "analyze",
            "build": "build",
        }

        for stage_key, status_message, args in stages:
            self.db.update_task(task_id, status="Running", message=status_message)
            running_task = self.db.get_task(task_id)
            if running_task:
                log_task_status_event(self.db, running_task, event_type=f"build_stage_{stage_key}")
            log_build_stage_event(
                self.db,
                task_id,
                stage=stage_labels[stage_key],
                phase="started",
                body=status_message,
                detail="명령을 실행하고 있어요.",
            )
            exit_code, timed_out = self.run_logged_command(args, cwd=project_path, env=env, log_path=build_log_path)
            if timed_out:
                log_build_stage_event(
                    self.db,
                    task_id,
                    stage=stage_labels[stage_key],
                    phase="failed",
                    body=f"Flutter {stage_labels[stage_key]} 단계가 시간 제한을 초과했어요.",
                )
                write_result_json(
                    result_path,
                    {
                        "status": "failed",
                        "task_id": task_id,
                        "error_stage": stage_key if stage_key != "pub_get" else "unknown",
                        "message": f"Flutter {stage_labels[stage_key]} 단계가 시간 제한을 초과했어요.",
                        "build_log_path": "logs/build.log",
                    },
                )
                return
            if exit_code != 0:
                log_build_stage_event(
                    self.db,
                    task_id,
                    stage=stage_labels[stage_key],
                    phase="failed",
                    body=f"Flutter {stage_labels[stage_key]} 단계에 실패했어요.",
                    detail=f"종료 코드: {exit_code}",
                )
                write_result_json(
                    result_path,
                    {
                        "status": "failed",
                        "task_id": task_id,
                        "error_stage": stage_key if stage_key != "pub_get" else "unknown",
                        "message": f"Flutter {stage_labels[stage_key]} 단계에 실패했어요.",
                        "build_log_path": "logs/build.log",
                    },
                )
                return
            log_build_stage_event(
                self.db,
                task_id,
                stage=stage_labels[stage_key],
                phase="succeeded",
                body=f"Flutter {stage_labels[stage_key]} 단계가 완료되었어요.",
            )

        if project_looks_like_placeholder_app(project_path):
            write_result_json(
                result_path,
                {
                    "status": "failed",
                    "task_id": task_id,
                    "error_stage": "codex",
                    "message": "Codex가 앱 내용을 만들지 못해 기본 템플릿 화면만 남았습니다.",
                    "build_log_path": "logs/build.log",
                },
            )
            return

        apk_relative = Path("project/build/app/outputs/flutter-apk/app-debug.apk")
        write_result_json(
            result_path,
            {
                "status": "success",
                "task_id": task_id,
                "app_name": infer_project_app_name(project_path),
                "package_name": infer_project_package_name(project_path),
                "apk_path": apk_relative.as_posix(),
                "message": "APK build completed",
                "build_log_path": "logs/build.log",
            },
        )

    def run_mock(
        self,
        task: dict[str, Any],
        workspace_path: Path,
        stdout_path: Path,
        stderr_path: Path,
        result_path: Path,
    ) -> tuple[int, bool]:
        mock_usage = {
            "input_tokens": 1200,
            "cached_input_tokens": 300,
            "output_tokens": 80,
            "reasoning_output_tokens": 20,
            "total_tokens": 1280,
        }
        stdout_path.write_text(
            json.dumps({"type": "thread.started", "thread_id": "mock-thread"}, ensure_ascii=False) + "\n"
            + json.dumps(
                {
                    "type": "event_msg",
                    "payload": {
                        "type": "token_count",
                        "info": {
                            "total_token_usage": mock_usage,
                            "last_token_usage": mock_usage,
                        },
                    },
                },
                ensure_ascii=False,
            )
            + "\n",
            encoding="utf-8",
        )
        stderr_path.write_text("", encoding="utf-8")

        build_log_path = workspace_path / "logs" / "build.log"
        build_log_path.write_text("Mock build log\n", encoding="utf-8")

        should_fail = "mock_fail" in task["prompt"].lower()
        if should_fail:
            result = {
                "status": "failed",
                "task_id": task["task_id"],
                "error_stage": "build",
                "message": "모의 빌드 실패",
                "build_log_path": "logs/build.log",
            }
        else:
            apk_relative = Path("project/build/app/outputs/flutter-apk/app-debug.apk")
            apk_path = workspace_path / apk_relative
            apk_path.parent.mkdir(parents=True, exist_ok=True)
            apk_path.write_bytes(b"mock-apk")
            result = {
                "status": "success",
                "task_id": task["task_id"],
                "app_name": "Mock App",
                "package_name": "com.example.mockapp",
                "apk_path": apk_relative.as_posix(),
                "app_llm_enabled": True,
                "app_llm_system_prompt": "사용자가 보낸 상황과 사진을 바탕으로 한국어로 실용적인 조언을 제공하세요. 우선순위와 실행 순서를 분명하게 제안하세요.",
                "message": "APK build completed",
                "build_log_path": "logs/build.log",
            }

        result_path.write_text(
            json.dumps(result, ensure_ascii=False, indent=2),
            encoding="utf-8",
        )
        return 0, False

    def finalize_task(
        self,
        task_id: str,
        workspace_path: Path,
        result_path: Path,
        exit_code: Optional[int],
        timed_out: bool,
    ) -> None:
        task = self.db.get_task(task_id) or {}
        usage = parse_codex_usage_from_jsonl(workspace_path / "logs" / "codex_stdout.log")
        codex_model = infer_model_name_from_codex_command(self.settings.codex_command)
        usage_update_fields = {
            "input_tokens": usage.input_tokens if usage else None,
            "cached_input_tokens": usage.cached_input_tokens if usage else None,
            "output_tokens": usage.output_tokens if usage else None,
            "reasoning_output_tokens": usage.reasoning_output_tokens if usage else None,
            "total_tokens": usage.total_tokens if usage else None,
        }

        if timed_out:
            log_text = collect_task_logs(workspace_path, "logs/build.log", full=True)
            self.db.update_task(
                task_id,
                status="Failed",
                message="앱 생성 작업 시간이 제한을 초과했습니다.",
                log=log_text,
                codex_result_json=json.dumps(
                    make_error_result(task_id, "앱 생성 작업 시간이 제한을 초과했습니다."),
                    ensure_ascii=False,
                ),
                **usage_update_fields,
            )
            task = self.db.get_task(task_id)
            if task:
                log_task_status_event(self.db, task, event_type="task_timeout")
                if usage:
                    log_token_usage_event(self.db, task_id, usage, model=codex_model)
            log_build_stage_event(
                self.db,
                task_id,
                stage="앱 생성 작업",
                phase="failed",
                body="앱 생성 작업 시간이 제한을 초과했습니다.",
            )
            return

        if not result_path.exists():
            log_text = collect_task_logs(workspace_path, "logs/build.log", full=True)
            engine_issue = codex_engine_issue_from_logs(log_text, exit_code)
            if engine_issue is not None:
                status, message, event_type, stage = engine_issue
                self.db.update_task(
                    task_id,
                    status=status,
                    message=message,
                    log=log_text,
                    codex_result_json=json.dumps(
                        make_error_result(task_id, message),
                        ensure_ascii=False,
                    ),
                    **usage_update_fields,
                )
                task = self.db.get_task(task_id)
                if task:
                    log_task_status_event(self.db, task, event_type=event_type)
                    if usage:
                        log_token_usage_event(self.db, task_id, usage, model=codex_model)
                log_build_stage_event(
                    self.db,
                    task_id,
                    stage=stage,
                    phase="failed",
                    body=message,
                )
                return
            message = "결과 파일이 생성되지 않았습니다."
            if exit_code not in (0, None):
                message = f"{message} worker exit code: {exit_code}"
            self.db.update_task(
                task_id,
                status="Failed",
                message=message,
                log=log_text,
                **usage_update_fields,
            )
            task = self.db.get_task(task_id)
            if task:
                log_task_status_event(self.db, task, event_type="task_failed")
                if usage:
                    log_token_usage_event(self.db, task_id, usage, model=codex_model)
            log_build_stage_event(
                self.db,
                task_id,
                stage="결과 확인",
                phase="failed",
                body=message,
            )
            return

        try:
            result = json.loads(result_path.read_text(encoding="utf-8"))
        except json.JSONDecodeError as exc:
            log_text = collect_task_logs(workspace_path, "logs/build.log", full=True)
            self.db.update_task(
                task_id,
                status="Failed",
                message=f"task_result.json 파싱 실패: {exc.msg}",
                log=log_text,
                codex_result_json=result_path.read_text(encoding="utf-8", errors="replace"),
                **usage_update_fields,
            )
            task = self.db.get_task(task_id)
            if task:
                log_task_status_event(self.db, task, event_type="task_failed")
                if usage:
                    log_token_usage_event(self.db, task_id, usage, model=codex_model)
            log_build_stage_event(
                self.db,
                task_id,
                stage="결과 확인",
                phase="failed",
                body=f"결과 파일 파싱에 실패했어요: {exc.msg}",
            )
            return

        persisted_app_name = current_task_app_name(task) or normalize_task_app_name(str(result.get("app_name") or ""))
        persisted_package_name = current_task_package_name(task) or normalize_whitespace(str(result.get("package_name") or ""))
        if persisted_app_name:
            result["app_name"] = persisted_app_name
        if persisted_package_name:
            result["package_name"] = persisted_package_name
        codex_result_json = json.dumps(result, ensure_ascii=False)
        build_log_hint = result.get("build_log_path")
        log_text = collect_task_logs(workspace_path, build_log_hint, full=True)

        if result.get("status") == "success":
            apk_value = result.get("apk_path")
            if not apk_value:
                self.db.update_task(
                    task_id,
                    status="Failed",
                    message="성공 결과에 apk_path가 없습니다.",
                    log=log_text,
                    codex_result_json=codex_result_json,
                    **usage_update_fields,
                )
                return

            current_project_path = Path(str(task.get("project_path") or "")).resolve() if task.get("project_path") else None
            try:
                apk_path = resolve_task_artifact_path(workspace_path, str(apk_value), current_project_path)
            except ValueError:
                self.db.update_task(
                    task_id,
                    status="Failed",
                    message="apk_path가 workspace 밖을 가리킵니다.",
                    log=log_text,
                    codex_result_json=codex_result_json,
                    **usage_update_fields,
                )
                return

            if current_project_path is not None:
                try:
                    apk_path = self.ensure_debug_apk(task_id, workspace_path, current_project_path, apk_path)
                except Exception as exc:
                    self.db.update_task(
                        task_id,
                        status="Failed",
                        message=f"설치 가능한 debug APK 준비 실패: {exc}",
                        log=log_text,
                        codex_result_json=codex_result_json,
                        **usage_update_fields,
                    )
                    failed_task = self.db.get_task(task_id)
                    if failed_task:
                        log_task_status_event(self.db, failed_task, event_type="task_failed")
                        if usage:
                            log_token_usage_event(self.db, task_id, usage, model=codex_model)
                    log_build_stage_event(
                        self.db,
                        task_id,
                        stage="debug apk",
                        phase="failed",
                        body=f"설치 가능한 debug APK를 준비하지 못했어요: {exc}",
                    )
                    return
                result["apk_path"] = str(apk_path.relative_to(workspace_path).as_posix())
                codex_result_json = json.dumps(result, ensure_ascii=False)
                log_text = collect_task_logs(workspace_path, build_log_hint, full=True)

                if project_looks_like_placeholder_app(current_project_path):
                    self.db.update_task(
                        task_id,
                        status="Failed",
                        message="생성 결과가 기본 템플릿 화면에서 벗어나지 않았어요.",
                        log=log_text,
                        codex_result_json=codex_result_json,
                        **usage_update_fields,
                    )
                    failed_task = self.db.get_task(task_id)
                    if failed_task:
                        log_task_status_event(self.db, failed_task, event_type="task_failed")
                        if usage:
                            log_token_usage_event(self.db, task_id, usage, model=codex_model)
                    log_build_stage_event(
                        self.db,
                        task_id,
                        stage="결과 확인",
                        phase="failed",
                        body="생성 결과가 기본 템플릿 화면에서 벗어나지 않았어요.",
                    )
                    return

            if apk_path.suffix.lower() != ".apk":
                self.db.update_task(
                    task_id,
                    status="Failed",
                    message="apk_path 확장자가 .apk가 아닙니다.",
                    log=log_text,
                    codex_result_json=codex_result_json,
                    **usage_update_fields,
                )
                return

            if not apk_path.exists() or apk_path.stat().st_size <= 0:
                self.db.update_task(
                    task_id,
                    status="Failed",
                    message="APK 파일이 없거나 비어 있습니다.",
                    log=log_text,
                    codex_result_json=codex_result_json,
                    **usage_update_fields,
                )
                return

            apk_url = f"{self.settings.server_base_url}/download/{task_id}"
            self.db.update_task(
                task_id,
                status="Success",
                message="APK 빌드가 완료되었어요.",
                apk_path=str(apk_path),
                apk_url=apk_url,
                app_name=result.get("app_name"),
                package_name=result.get("package_name"),
                codex_result_json=codex_result_json,
                log=log_text,
                **usage_update_fields,
            )
            apply_codex_generated_app_llm_settings(
                self.db,
                self.settings,
                task_id=task_id,
                result_payload=result,
            )
            task = self.db.get_task(task_id)
            if task:
                log_task_status_event(self.db, task, event_type="task_succeeded")
                if usage:
                    log_token_usage_event(self.db, task_id, usage, model=codex_model)
                log_package_name_event(
                    self.db,
                    task_id,
                    package_name=str(task.get("package_name") or result.get("package_name") or ""),
                    app_name=str(task.get("app_name") or result.get("app_name") or ""),
                    event_type="package_name_confirmed",
                )
            log_build_stage_event(
                self.db,
                task_id,
                stage="APK 생성",
                phase="succeeded",
                body="APK 생성이 완료되었어요.",
                detail=str(apk_path),
            )
            return

        if result.get("status") == "failed":
            self.db.update_task(
                task_id,
                status="Failed",
                message=result.get("message", "앱 생성에 실패했습니다."),
                codex_result_json=codex_result_json,
                log=log_text,
                **usage_update_fields,
            )
            task = self.db.get_task(task_id)
            if task:
                log_task_status_event(self.db, task, event_type="task_failed")
                if usage:
                    log_token_usage_event(self.db, task_id, usage, model=codex_model)
            log_build_stage_event(
                self.db,
                task_id,
                stage="앱 생성 결과",
                phase="failed",
                body=str(result.get("message", "앱 생성에 실패했습니다.")),
            )
            return

        self.db.update_task(
            task_id,
            status="Failed",
            message="task_result.json status 값이 올바르지 않습니다.",
            codex_result_json=codex_result_json,
            log=log_text,
            **usage_update_fields,
        )
        task = self.db.get_task(task_id)
        if task:
            log_task_status_event(self.db, task, event_type="task_failed")
            if usage:
                log_token_usage_event(self.db, task_id, usage, model=codex_model)
        log_build_stage_event(
            self.db,
            task_id,
            stage="앱 생성 결과",
            phase="failed",
            body="결과 상태 값이 올바르지 않습니다.",
        )


def build_task_workspace(settings: Settings, task: dict[str, Any]) -> tuple[Path, Path]:
    safe_user_id = sanitize_component(task["user_id"])
    safe_task_id = sanitize_component(task["task_id"])

    user_root = settings.workspaces_root / f"user_{safe_user_id}"
    task_root = user_root / f"task_{safe_task_id}"
    logs_root = task_root / "logs"
    result_root = task_root / ".codex_result"

    if task_root.exists():
        raise RuntimeError(f"task workspace already exists: {task_root}")

    settings.workspaces_root.mkdir(parents=True, exist_ok=True)
    user_root.mkdir(parents=True, exist_ok=True)
    logs_root.mkdir(parents=True, exist_ok=True)
    result_root.mkdir(parents=True, exist_ok=True)

    if not settings.base_project_path.exists() or not settings.base_project_path.is_dir():
        raise RuntimeError(
            f"BASE_PROJECT_PATH does not exist or is not a directory: {settings.base_project_path}"
        )

    project_root, _ = create_initial_project_revision(task_root, settings.base_project_path)
    reference_image_workspace_path = save_reference_image_attachment(
        task_root,
        reference_image_name=normalize_reference_image_name(task.get("reference_image_name")),
        reference_image_base64=normalize_reference_image_base64(task.get("reference_image_base64")),
    )
    if reference_image_workspace_path:
        task["reference_image_workspace_path"] = reference_image_workspace_path
    if task.get("app_name") and task.get("package_name"):
        apply_project_defaults(project_root, task["task_id"], task["app_name"], task["package_name"])
    (task_root / "AGENTS.md").write_text(render_task_agents_md(task["task_id"]), encoding="utf-8")
    (task_root / "prompt.md").write_text(render_prompt_md(task, settings), encoding="utf-8")

    return task_root, project_root


def serialize_task_for_status(db: Database, task: dict[str, Any], log_line_limit: int) -> dict[str, Any]:
    log_text, log_lines = collect_live_task_logs(task, log_line_limit)
    success = task["status"] == "Success"
    status_text = status_display_text(task["status"], task.get("message"))
    timeline_events = build_task_timeline_events(db, str(task["task_id"]))
    current_build_stage, current_build_stage_detail = derive_current_build_stage(task, timeline_events)
    raw_log_sections: list[dict[str, str]] = []
    workspace_value = (task.get("workspace_path") or "").strip()
    if workspace_value:
        workspace_root = Path(workspace_value)
        if workspace_root.exists() and workspace_root.is_dir():
            raw_log_sections = collect_raw_log_sections(workspace_root, "logs/build.log")
    state_payload = load_task_state_payload(task)
    conversation_state = build_task_conversation_state(task)
    latest_assistant_message = sanitize_user_visible_text(str(state_payload.get("message") or task.get("message") or ""))
    latest_assistant_message_type = str(state_payload.get("tool") or "status")
    latest_failure_message = (
        latest_assistant_message if task["status"] in {"Failed", "Error"} else sanitize_user_visible_text(str(state_payload.get("latest_failure_message") or ""))
    )
    sanitized_log_text = sanitize_user_visible_text(log_text)
    sanitized_log_lines = [sanitize_user_visible_text(line) for line in log_lines]
    apk_path_value = str(task.get("apk_path") or "")
    apk_size_bytes = None
    if apk_path_value:
        apk_path = Path(apk_path_value)
        if apk_path.exists() and apk_path.is_file():
            apk_size_bytes = apk_path.stat().st_size
    return {
        "task_id": task["task_id"],
        "status": task["status"],
        "status_display_text": status_text,
        "message": sanitize_user_visible_text(str(task["message"] or "")),
        "status_message": sanitize_user_visible_text(str(task["message"] or "")),
        "apk_url": task.get("apk_url") or "",
        "apk_path": apk_path_value,
        "apk_size_bytes": apk_size_bytes,
        "app_name": task.get("app_name") or "",
        "generated_app_name": task.get("app_name") or "",
        "package_name": task.get("package_name") or "",
        "build_success": success,
        "build_attempts": build_attempts_for_task(task),
        "input_tokens": task.get("input_tokens"),
        "cached_input_tokens": task.get("cached_input_tokens"),
        "output_tokens": task.get("output_tokens"),
        "reasoning_output_tokens": task.get("reasoning_output_tokens"),
        "total_tokens": task.get("total_tokens"),
        "log": sanitized_log_text,
        "full_log": sanitized_log_text,
        "log_lines": sanitized_log_lines,
        "latest_log": sanitized_log_lines[-1] if sanitized_log_lines else "",
        "progress_mode": "",
        "current_build_stage": current_build_stage,
        "current_build_stage_detail": current_build_stage_detail,
        "latest_assistant_message": latest_assistant_message,
        "latest_assistant_message_type": latest_assistant_message_type,
        "latest_failure_message": latest_failure_message,
        "recent_messages": state_payload.get("recent_messages", []),
        "timeline_events": timeline_events,
        "raw_log_sections": raw_log_sections,
        "interaction_type": str(state_payload.get("interaction_type") or ""),
        "render_mode": str(state_payload.get("render_mode") or ""),
        "requires_user_input": bool(state_payload.get("requires_user_input")),
        "requires_confirmation": bool(state_payload.get("requires_confirmation")),
        "pending_decision_reason": str(state_payload.get("pending_decision_reason") or ""),
        "suppress_assistant_bubble": bool(state_payload.get("suppress_assistant_bubble")),
        "retry_allowed": task["status"] in {"Failed", "Error"},
        "allowed_next_actions": ["retry"] if task["status"] in {"Failed", "Error"} else [],
        "conversation_state": conversation_state,
        "created_at": task["created_at"],
        "updated_at": task["updated_at"],
    }


def serialize_task_summary(task: dict[str, Any]) -> dict[str, Any]:
    success = task["status"] == "Success"
    state_payload = load_task_state_payload(task)
    conversation_state = build_task_conversation_state(task)
    return {
        "task_id": task["task_id"],
        "status": task["status"],
        "status_display_text": status_display_text(task["status"], task.get("message")),
        "prompt": task["prompt"],
        "initial_user_prompt": task["prompt"],
        "app_name": task.get("app_name") or "",
        "generated_app_name": task.get("app_name") or "",
        "package_name": task.get("package_name") or "",
        "apk_url": task.get("apk_url") or "",
        "build_success": success,
        "input_tokens": task.get("input_tokens"),
        "cached_input_tokens": task.get("cached_input_tokens"),
        "output_tokens": task.get("output_tokens"),
        "reasoning_output_tokens": task.get("reasoning_output_tokens"),
        "total_tokens": task.get("total_tokens"),
        "created_at": task["created_at"],
        "updated_at": task["updated_at"],
        "conversation_state": conversation_state,
        "interaction_type": str(state_payload.get("interaction_type") or ""),
        "render_mode": str(state_payload.get("render_mode") or ""),
    }


TOKEN_USAGE_FIELDS = (
    "input_tokens",
    "cached_input_tokens",
    "output_tokens",
    "cached_output_tokens",
    "reasoning_output_tokens",
    "total_tokens",
)


def optional_int_value(value: Any) -> Optional[int]:
    if value in (None, ""):
        return None
    try:
        return int(value)
    except (TypeError, ValueError):
        return None


def aggregate_usage_rows(rows: list[dict[str, Any]]) -> dict[str, Optional[int]]:
    totals: dict[str, int] = {field: 0 for field in TOKEN_USAGE_FIELDS}
    seen: set[str] = set()
    for row in rows:
        for field in TOKEN_USAGE_FIELDS:
            value = optional_int_value(row.get(field))
            if value is None:
                continue
            totals[field] += value
            seen.add(field)
    return {field: totals[field] if field in seen else None for field in TOKEN_USAGE_FIELDS}


def aggregate_task_token_usage(db: Database, task: dict[str, Any]) -> dict[str, Optional[int]]:
    records = db.list_task_usage_records(str(task["task_id"]))
    if records:
        return aggregate_usage_rows(records)
    return {
        "input_tokens": optional_int_value(task.get("input_tokens")),
        "cached_input_tokens": optional_int_value(task.get("cached_input_tokens")),
        "output_tokens": optional_int_value(task.get("output_tokens")),
        "cached_output_tokens": None,
        "reasoning_output_tokens": optional_int_value(task.get("reasoning_output_tokens")),
        "total_tokens": optional_int_value(task.get("total_tokens")),
    }


def aggregate_tasks_token_usage(db: Database, tasks: list[dict[str, Any]]) -> dict[str, Optional[int]]:
    return aggregate_usage_rows([aggregate_task_token_usage(db, task) for task in tasks])


def query_usage_tasks(
    db: Database,
    *,
    user_id: Optional[str],
    device_id: Optional[str],
    phone_number: Optional[str],
) -> list[dict[str, Any]]:
    if any((user_id, device_id, phone_number)):
        return db.query_tasks(user_id=user_id, device_id=device_id, phone_number=phone_number)
    return [
        task
        for task_id in db.list_all_task_ids()
        for task in [db.get_task(task_id)]
        if task is not None
    ]


def usage_window_payload(label: str, window: Optional[CodexRateLimitWindow]) -> Optional[dict[str, Any]]:
    if window is None:
        return None
    used_percent = max(0, min(100, int(window.used_percent)))
    return {
        "window_label": label,
        "used_percent": used_percent,
        "remaining_percent": max(0, 100 - used_percent),
        "resets_at": window.resets_at,
        "window_duration_mins": window.window_duration_mins,
    }


def mock_rate_limit_snapshot() -> CodexRateLimitSnapshot:
    now = int(time.time())
    return CodexRateLimitSnapshot(
        limit_name="codex",
        primary=CodexRateLimitWindow(used_percent=28, window_duration_mins=300, resets_at=now + 2 * 60 * 60),
        secondary=CodexRateLimitWindow(used_percent=46, window_duration_mins=7 * 24 * 60, resets_at=now + 3 * 24 * 60 * 60),
    )


def load_usage_rate_limits(settings: Settings) -> tuple[Optional[CodexRateLimitSnapshot], Optional[str]]:
    if settings.mock_codex:
        return mock_rate_limit_snapshot(), None
    try:
        return fetch_codex_rate_limits(settings.codex_command, timeout_seconds=8.0), None
    except Exception as exc:
        return None, str(exc)


def build_token_usage_response(
    *,
    settings: Settings,
    usage: dict[str, Optional[int]],
    task_id: str = "",
) -> dict[str, Any]:
    limits, limit_error = load_usage_rate_limits(settings)
    return {
        "task_id": task_id,
        "limit_name": limits.limit_name if limits and limits.limit_name else "codex",
        "primary_window": usage_window_payload("5시간 한도", limits.primary if limits else None),
        "secondary_window": usage_window_payload("주간 한도", limits.secondary if limits else None),
        "usage": usage,
        "status": "ready" if limit_error is None else "partial",
        "status_message": (
            "최신 토큰 사용량을 보여주고 있어요."
            if limit_error is None
            else f"DB 토큰 사용량은 표시했지만 Codex 한도 조회는 실패했어요. {limit_error}"
        ),
    }


@asynccontextmanager
async def lifespan(app: FastAPI):
    settings = load_settings()
    db = Database(settings.db_path)
    db.init_db()
    runner = CodexTaskRunner(settings, db)
    runner.start()

    app.state.settings = settings
    app.state.db = db
    app.state.runner = runner
    try:
        yield
    finally:
        runner.stop()


def create_app() -> FastAPI:
    app = FastAPI(title="Flutter APK Builder Server", lifespan=lifespan)
    try:
        from admin_dashboard import register_admin_dashboard_routes
    except ModuleNotFoundError:
        from .admin_dashboard import register_admin_dashboard_routes

    register_admin_dashboard_routes(app, require_admin_token)

    @app.get("/health")
    def health() -> dict[str, str]:
        return {"status": "ok"}

    @app.get("/admin/app-llm-defaults")
    def get_global_app_llm_defaults(
        x_admin_token: Optional[str] = Header(default=None),
    ) -> dict[str, Any]:
        settings: Settings = app.state.settings
        db: Database = app.state.db
        require_admin_token(settings, x_admin_token)
        config = resolve_default_app_llm_config(db, settings)
        return {
            "source": "server_settings" if db.get_server_setting("app_llm_defaults") else "environment",
            **app_llm_config_response_payload(config),
        }

    @app.post("/admin/app-llm-defaults")
    def upsert_global_app_llm_defaults(
        request: GlobalAppLlmDefaultsRequest,
        x_admin_token: Optional[str] = Header(default=None),
    ) -> dict[str, Any]:
        settings: Settings = app.state.settings
        db: Database = app.state.db
        require_admin_token(settings, x_admin_token)
        existing_defaults = db.get_server_setting("app_llm_defaults")
        config = merge_app_llm_config_values(
            existing_defaults,
            enabled=request.enabled,
            provider=request.provider,
            model=request.model,
            api_key=request.api_key,
            base_url=request.base_url,
            system_prompt=request.system_prompt,
            daily_request_limit=request.daily_request_limit,
            daily_token_limit=request.daily_token_limit,
            max_output_tokens=request.max_output_tokens,
            temperature=request.temperature,
            settings=settings,
        )
        db.set_server_setting("app_llm_defaults", config)
        updated_task_count = 0
        if request.apply_to_existing_tasks:
            task_ids = db.list_all_task_ids()
            for task_id in task_ids:
                existing_task_config = db.get_app_llm_config(task_id)
                merged_task_config = merge_app_llm_config_values(
                    existing_task_config,
                    enabled=request.enabled,
                    provider=request.provider,
                    model=request.model,
                    api_key=request.api_key,
                    base_url=request.base_url,
                    system_prompt=request.system_prompt,
                    daily_request_limit=request.daily_request_limit,
                    daily_token_limit=request.daily_token_limit,
                    max_output_tokens=request.max_output_tokens,
                    temperature=request.temperature,
                    settings=settings,
                )
                db.upsert_app_llm_config(task_id, merged_task_config)
                db.log_event(
                    task_id,
                    actor="system",
                    event_type="app_llm_config_bulk_updated",
                    message_text=app_llm_config_event_message(merged_task_config),
                    payload=app_llm_config_event_payload(
                        merged_task_config,
                        previous_config=existing_task_config,
                        source="global_defaults",
                    ),
                )
            updated_task_count = len(task_ids)
        return {
            "source": "server_settings",
            "apply_to_existing_tasks": request.apply_to_existing_tasks,
            "updated_task_count": updated_task_count,
            **app_llm_config_response_payload(config),
        }

    @app.post("/generate")
    def generate(request: GenerateRequest) -> dict[str, Any]:
        settings: Settings = app.state.settings
        db: Database = app.state.db
        runner: CodexTaskRunner = app.state.runner
        request_device_info = serialize_device_info(request.device_info)
        requested_reference_image_name = normalize_reference_image_name(request.reference_image_name)
        requested_reference_image_base64 = normalize_reference_image_base64(request.reference_image_base64)
        followup_task_id = (request.task_id or "").strip()

        if followup_task_id:
            task = db.get_task(followup_task_id)
            if not task:
                raise HTTPException(status_code=404, detail="task not found")
            if not is_task_access_allowed(task, device_id=request.device_id, phone_number=request.phone_number):
                raise HTTPException(status_code=404, detail="task not found")
            if task["status"] in {"Queued", "Running"}:
                raise HTTPException(status_code=409, detail="task already in progress")
            db.log_event(
                followup_task_id,
                actor="user",
                event_type="user_message",
                message_text=request.prompt,
                payload={
                    "task_id": followup_task_id,
                    "device_id": request.device_id,
                    "phone_number": request.phone_number,
                    "raw_prompt": request.prompt,
                },
            )
            previous_conversation_state = build_task_conversation_state(task)
            effective_reference_image_name = requested_reference_image_name or normalize_reference_image_name(
                previous_conversation_state.get("reference_image_name")
            )
            effective_reference_image_base64 = requested_reference_image_base64 or normalize_reference_image_base64(
                previous_conversation_state.get("reference_image_base64")
            )
            existing_workspace_ready = bool(task.get("workspace_path") and task.get("project_path"))
            codex_followup_enabled = (
                settings.codex_existing_task_followup_enabled
                and not settings.mock_codex
                and existing_workspace_ready
            )
            if codex_followup_enabled:
                codex_followup_payload = run_codex_existing_task_followup_decision(
                    settings,
                    db,
                    task,
                    prompt=request.prompt,
                    previous_conversation_state=previous_conversation_state,
                    device_info=request_device_info or previous_conversation_state.get("device_info"),
                    reference_image_name=effective_reference_image_name,
                )

                codex_followup_mode = normalize_whitespace(str((codex_followup_payload or {}).get("mode") or ""))
                if not codex_followup_payload:
                    decision = build_intent_decision(
                        mode="answer_question",
                        task_id=followup_task_id,
                        existing_task=True,
                        existing_workspace_ready=True,
                        user_prompt=request.prompt,
                        effective_user_prompt=request.prompt,
                        reason="기존 앱 workspace를 확인하는 Codex follow-up 단계가 완료되지 않았습니다.",
                        assistant_message="기존 앱을 확인하는 작업을 완료하지 못했어요. 잠시 후 다시 시도하거나 연구원에게 문의해 주세요.",
                        request_scope="existing_app_modification",
                    )
                elif codex_followup_mode == "answer_question":
                    assistant_reply = korean_text_or_fallback(
                        str((codex_followup_payload or {}).get("assistant_reply") or ""),
                        "기존 앱 코드를 확인했지만 답변을 정리하지 못했어요. 조금 더 구체적으로 물어봐 주세요.",
                    )
                    decision = build_intent_decision(
                        mode="answer_question",
                        task_id=followup_task_id,
                        existing_task=True,
                        existing_workspace_ready=True,
                        user_prompt=request.prompt,
                        effective_user_prompt=request.prompt,
                        reason=korean_text_or_fallback(
                            str((codex_followup_payload or {}).get("reason") or ""),
                            "기존 앱 workspace를 Codex가 직접 확인해 답변합니다.",
                        ),
                        assistant_message=assistant_reply,
                        request_scope="existing_app_modification",
                    )
                elif codex_followup_mode == "ask_confirmation":
                    codex_questions = [
                        normalize_whitespace(str(item))
                        for item in (codex_followup_payload or {}).get("questions", [])
                        if normalize_whitespace(str(item))
                    ]
                    decision = build_intent_decision(
                        mode="ask_confirmation",
                        task_id=followup_task_id,
                        existing_task=True,
                        existing_workspace_ready=True,
                        user_prompt=request.prompt,
                        effective_user_prompt=normalize_whitespace(
                            str((codex_followup_payload or {}).get("effective_user_prompt") or request.prompt)
                        ),
                        questions=codex_questions or build_clarification_questions(request.prompt),
                        reason=korean_text_or_fallback(
                            str((codex_followup_payload or {}).get("reason") or ""),
                            "기존 앱 코드를 확인했지만 수정 전에 막히는 세부사항이 있어요.",
                        ),
                        request_scope="existing_app_modification",
                        suggested_app_name=current_task_app_name(task, previous_conversation_state),
                    )
                else:
                    decision = build_intent_decision(
                        mode="build",
                        task_id=followup_task_id,
                        existing_task=True,
                        existing_workspace_ready=True,
                        user_prompt=request.prompt,
                        effective_user_prompt=normalize_whitespace(
                            str((codex_followup_payload or {}).get("effective_user_prompt") or request.prompt)
                        ),
                        reason=korean_text_or_fallback(
                            str((codex_followup_payload or {}).get("reason") or ""),
                            "기존 앱 workspace가 있으므로 명세 구체화 Agent를 거치지 않고 Codex가 직접 수정합니다.",
                        ),
                        request_scope="existing_app_modification",
                        suggested_app_name=current_task_app_name(task, previous_conversation_state),
                        primary_user_flow=normalize_whitespace(
                            str(previous_conversation_state.get("latest_primary_user_flow") or request.prompt)
                        ),
                        secondary_requirements=normalize_secondary_requirements(
                            previous_conversation_state.get("latest_secondary_requirements")
                        ),
                        secondary_scope_confirmed=True,
                        acceptance_criteria=normalize_acceptance_criteria(
                            previous_conversation_state.get("latest_acceptance_criteria")
                        ),
                    )
            else:
                decision = decide_intent(
                    request.prompt,
                    followup_task_id,
                    existing_task=True,
                    existing_workspace_ready=existing_workspace_ready,
                    previous_conversation_state=previous_conversation_state,
                    device_info=request_device_info or previous_conversation_state.get("device_info"),
                    reference_image_name=effective_reference_image_name,
                    reference_image_base64=effective_reference_image_base64,
                    settings=settings,
                    db=db,
                )
            if effective_reference_image_name and not decision.image_reference_summary:
                decision = replace(
                    decision,
                    image_reference_summary=build_reference_image_summary(effective_reference_image_name),
                )
            decision = preserve_followup_task_identity(decision, task, previous_conversation_state)
            if decision.used_previous_pending_prompt:
                db.log_event(
                    followup_task_id,
                    actor="user",
                    event_type="user_interaction",
                    message_text="확인 버튼 클릭 또는 일반 확인 응답",
                    payload={
                        "interaction": "confirm_pending_request",
                        "raw_prompt": request.prompt,
                        "effective_user_prompt": decision.effective_user_prompt,
                    },
                )
            if decision.mode != "build":
                db.update_task(
                    followup_task_id,
                    status=decision.status,
                    message=decision.message,
                    device_id=request.device_id,
                    phone_number=request.phone_number,
                    codex_result_json=json.dumps(
                        make_decision_state(
                            {
                                **task,
                                "device_info": request_device_info or previous_conversation_state.get("device_info") or {},
                                "reference_image_name": effective_reference_image_name,
                                "reference_image_base64": effective_reference_image_base64,
                                "reference_image_workspace_path": previous_conversation_state.get("reference_image_workspace_path") or "",
                            },
                            decision,
                            request.prompt,
                        ),
                        ensure_ascii=False,
                    ),
                )
                task_after_update = db.get_task(followup_task_id)
                if task_after_update:
                    log_task_status_event(db, task_after_update)
                db.log_event(
                    followup_task_id,
                    actor="assistant",
                    event_type="assistant_message",
                    message_text=decision.message,
                    payload=build_assistant_response_payload(decision),
                )
                return build_decision_response(followup_task_id, decision)

            resolved_app_name = current_task_app_name(task, previous_conversation_state) or decision.app_name
            resolved_package_name = current_task_package_name(task, previous_conversation_state) or decision.package_name
            if not resolved_package_name and resolved_app_name:
                resolved_package_name = infer_package_name(resolved_app_name, followup_task_id)

            workspace_path_value = task.get("workspace_path")
            project_path_value = task.get("project_path")
            reference_image_workspace_path = normalize_whitespace(
                str(previous_conversation_state.get("reference_image_workspace_path") or "")
            )
            if not workspace_path_value or not project_path_value:
                build_task = {
                    **task,
                    "app_name": resolved_app_name,
                    "package_name": resolved_package_name,
                    "normalized_prompt": decision.normalized_prompt,
                    "build_request_prompt": decision.effective_user_prompt,
                    "device_info": request_device_info or previous_conversation_state.get("device_info") or {},
                    "reference_image_name": effective_reference_image_name,
                    "reference_image_base64": effective_reference_image_base64,
                    "reference_image_workspace_path": previous_conversation_state.get("reference_image_workspace_path") or "",
                }
                try:
                    workspace_path, project_path = build_task_workspace(settings, build_task)
                except Exception as exc:
                    db.update_task(
                        followup_task_id,
                        status="Error",
                        message=f"workspace 준비 실패: {exc}",
                    )
                    failed_task = db.get_task(followup_task_id)
                    if failed_task:
                        log_task_status_event(db, failed_task, event_type="task_error")
                    raise HTTPException(status_code=500, detail="workspace preparation failed") from exc
                workspace_path_value = str(workspace_path)
                project_path_value = str(project_path)
                reference_image_workspace_path = normalize_whitespace(
                    str(build_task.get("reference_image_workspace_path") or reference_image_workspace_path)
                )
                db.update_task(
                    followup_task_id,
                    workspace_path=workspace_path_value,
                    project_path=project_path_value,
                )
                db.record_project_snapshot(
                    task_id=followup_task_id,
                    revision_label=current_revision_label(Path(project_path_value)),
                    source=decision.request_scope,
                    workspace_path=workspace_path_value,
                    project_path=project_path_value,
                )
            else:
                workspace_path_obj = Path(workspace_path_value)
                project_path_obj, revision_label = create_followup_project_revision(
                    workspace_path_obj,
                    Path(project_path_value),
                )
                project_path_value = str(project_path_obj)
                reference_image_workspace_path = save_reference_image_attachment(
                    workspace_path_obj,
                    reference_image_name=effective_reference_image_name,
                    reference_image_base64=effective_reference_image_base64,
                )
                apply_project_defaults(project_path_obj, followup_task_id, resolved_app_name, resolved_package_name)
                append_followup_prompt(
                    workspace_path_obj,
                    request.prompt,
                    effective_user_prompt=decision.effective_user_prompt,
                    normalized_prompt=decision.normalized_prompt,
                    reference_image_name=effective_reference_image_name,
                    reference_image_workspace_path=reference_image_workspace_path or previous_conversation_state.get("reference_image_workspace_path"),
                )
                db.record_project_snapshot(
                    task_id=followup_task_id,
                    revision_label=revision_label,
                    source="runtime_repair" if looks_like_runtime_repair_request(request.prompt) else decision.request_scope,
                    workspace_path=workspace_path_value,
                    project_path=project_path_value,
                )

            db.update_task(
                followup_task_id,
                status="Queued",
                message=decision.message,
                device_id=request.device_id,
                phone_number=request.phone_number,
                app_name=resolved_app_name,
                package_name=resolved_package_name,
                project_path=project_path_value,
                apk_path=None,
                apk_url=None,
                codex_result_json=json.dumps(
                    make_decision_state(
                        {
                            **task,
                            "device_info": request_device_info or previous_conversation_state.get("device_info") or {},
                            "reference_image_name": effective_reference_image_name,
                            "reference_image_base64": effective_reference_image_base64,
                            "reference_image_workspace_path": reference_image_workspace_path or previous_conversation_state.get("reference_image_workspace_path") or "",
                        },
                        decision,
                        request.prompt,
                    ),
                    ensure_ascii=False,
                ),
                log=None,
                input_tokens=None,
                cached_input_tokens=None,
                output_tokens=None,
                reasoning_output_tokens=None,
                total_tokens=None,
            )
            queued_task = db.get_task(followup_task_id)
            if queued_task:
                log_task_status_event(db, queued_task)
            log_package_name_event(
                db,
                followup_task_id,
                package_name=resolved_package_name or "",
                app_name=resolved_app_name or "",
                event_type="package_name_selected",
            )
            db.log_event(
                followup_task_id,
                actor="assistant",
                event_type="assistant_message",
                message_text=decision.message,
                payload=build_assistant_response_payload(decision),
            )
            runner.enqueue(followup_task_id)
            return build_decision_response(followup_task_id, decision)

        task_id = uuid.uuid4().hex
        now = utc_now_iso()
        resolved_user_id = effective_owner_id(request.device_id, request.phone_number)
        decision = decide_intent(
            request.prompt,
            task_id,
            existing_task=False,
            existing_workspace_ready=False,
            previous_conversation_state=None,
            device_info=request_device_info,
            reference_image_name=requested_reference_image_name,
            reference_image_base64=requested_reference_image_base64,
            settings=settings,
            db=db,
        )
        if requested_reference_image_name and not decision.image_reference_summary:
            decision = replace(
                decision,
                image_reference_summary=build_reference_image_summary(requested_reference_image_name),
            )
        if decision.mode == "build":
            decision = build_pre_build_confirmation_decision(decision, existing_task=False)
        task = {
            "task_id": task_id,
            "user_id": resolved_user_id,
            "device_id": request.device_id,
            "phone_number": request.phone_number,
            "prompt": request.prompt,
            "device_info": request_device_info,
            "reference_image_name": requested_reference_image_name,
            "reference_image_base64": requested_reference_image_base64,
            "reference_image_workspace_path": "",
            "status": decision.status,
            "message": decision.message,
            "workspace_path": None,
            "project_path": None,
            "apk_path": None,
            "apk_url": None,
            "app_name": decision.app_name or None,
            "package_name": decision.package_name or None,
            "input_tokens": None,
            "cached_input_tokens": None,
            "output_tokens": None,
            "reasoning_output_tokens": None,
            "total_tokens": None,
            "codex_result_json": json.dumps(
                make_decision_state(
                    {
                        "prompt": request.prompt,
                        "device_info": request_device_info,
                        "reference_image_name": requested_reference_image_name,
                        "reference_image_base64": requested_reference_image_base64,
                        "reference_image_workspace_path": "",
                    },
                    decision,
                    request.prompt,
                ),
                ensure_ascii=False,
            ),
            "log": None,
            "created_at": now,
            "updated_at": now,
            "normalized_prompt": decision.normalized_prompt,
            "build_request_prompt": decision.effective_user_prompt,
        }

        db.create_task(task)
        ensure_default_app_llm_config(db, settings, task_id)
        db.log_event(
            task_id,
            actor="system",
            event_type="task_created",
            message_text="task created",
            payload={
                "task_id": task_id,
                "device_id": request.device_id,
                "phone_number": request.phone_number,
                "raw_prompt": request.prompt,
            },
        )
        db.log_event(
            task_id,
            actor="user",
            event_type="user_message",
            message_text=request.prompt,
            payload={
                "task_id": task_id,
                "device_id": request.device_id,
                "phone_number": request.phone_number,
            },
        )
        log_task_status_event(db, task)
        log_package_name_event(
            db,
            task_id,
            package_name=str(task.get("package_name") or ""),
            app_name=str(task.get("app_name") or ""),
            event_type="package_name_selected",
        )
        db.log_event(
            task_id,
            actor="assistant",
            event_type="assistant_message",
            message_text=decision.message,
            payload=build_assistant_response_payload(decision),
        )
        if decision.mode == "build":
            try:
                workspace_path, project_path = build_task_workspace(settings, task)
            except Exception as exc:
                db.update_task(
                    task_id,
                    status="Error",
                    message=f"workspace 준비 실패: {exc}",
                )
                failed_task = db.get_task(task_id)
                if failed_task:
                    log_task_status_event(db, failed_task, event_type="task_error")
                raise HTTPException(status_code=500, detail="workspace preparation failed") from exc

            db.update_task(
                task_id,
                workspace_path=str(workspace_path),
                project_path=str(project_path),
            )
            db.record_project_snapshot(
                task_id=task_id,
                revision_label=current_revision_label(project_path),
                source=decision.request_scope,
                workspace_path=str(workspace_path),
                project_path=str(project_path),
            )
            runner.enqueue(task_id)

        return build_decision_response(task_id, decision)

    @app.get("/status/{task_id}")
    def get_status(
        task_id: str,
        device_id: Optional[str] = Query(default=None),
        phone_number: Optional[str] = Query(default=None),
        user_id: Optional[str] = Query(default=None),
    ) -> dict[str, Any]:
        db: Database = app.state.db
        settings: Settings = app.state.settings
        task = db.get_task(task_id)
        if not task:
            raise HTTPException(status_code=404, detail="task not found")
        if not is_task_access_allowed(task, device_id=device_id, phone_number=phone_number):
            raise HTTPException(status_code=404, detail="task not found")
        log_codex_rate_limits_to_server_log(task_id, settings.codex_command)
        return serialize_task_for_status(db, task, settings.status_log_line_limit)

    @app.patch("/tasks/{task_id}")
    def update_task_metadata(
        task_id: str,
        request: TaskUpdateRequest,
        device_id: Optional[str] = Query(default=None),
        phone_number: Optional[str] = Query(default=None),
        user_id: Optional[str] = Query(default=None),
    ) -> dict[str, Any]:
        _ = user_id
        db: Database = app.state.db
        task = db.get_task(task_id)
        if not task:
            raise HTTPException(status_code=404, detail="task not found")
        if not is_task_access_allowed(task, device_id=device_id, phone_number=phone_number):
            raise HTTPException(status_code=404, detail="task not found")
        app_name = normalize_task_app_name(request.app_name)
        if not app_name:
            raise HTTPException(status_code=400, detail="app_name is required")
        previous_app_name = normalize_whitespace(str(task.get("app_name") or ""))
        update_fields: dict[str, Any] = {"app_name": app_name}
        updated_state_json = update_task_state_app_name(task, app_name)
        if updated_state_json is not None:
            update_fields["codex_result_json"] = updated_state_json
        db.update_task(task_id, **update_fields)
        db.log_event(
            task_id,
            actor="user",
            event_type="task_renamed",
            message_text=f"앱 이름 변경: {previous_app_name or '(미정)'} -> {app_name}",
            payload={
                "previous_app_name": previous_app_name,
                "app_name": app_name,
                "device_id": device_id or "",
                "phone_number": phone_number or "",
            },
        )
        updated_task = db.get_task(task_id)
        if not updated_task:
            raise HTTPException(status_code=404, detail="task not found")
        return serialize_task_summary(updated_task)

    @app.get("/tasks/{task_id}/usage")
    def get_task_usage(
        task_id: str,
        device_id: Optional[str] = Query(default=None),
        phone_number: Optional[str] = Query(default=None),
        user_id: Optional[str] = Query(default=None),
    ) -> dict[str, Any]:
        db: Database = app.state.db
        settings: Settings = app.state.settings
        task = db.get_task(task_id)
        if not task:
            raise HTTPException(status_code=404, detail="task not found")
        if not is_task_access_allowed(task, device_id=device_id, phone_number=phone_number):
            raise HTTPException(status_code=404, detail="task not found")
        return build_token_usage_response(
            settings=settings,
            task_id=task_id,
            usage=aggregate_task_token_usage(db, task),
        )

    @app.get("/usage/codex")
    def get_codex_usage(
        user_id: Optional[str] = Query(default=None),
        device_id: Optional[str] = Query(default=None),
        phone_number: Optional[str] = Query(default=None),
    ) -> dict[str, Any]:
        db: Database = app.state.db
        settings: Settings = app.state.settings
        tasks = query_usage_tasks(
            db,
            user_id=user_id,
            device_id=device_id,
            phone_number=phone_number,
        )
        return build_token_usage_response(
            settings=settings,
            usage=aggregate_tasks_token_usage(db, tasks),
        )

    @app.post("/tasks/{task_id}/runtime-error")
    def report_runtime_error(
        task_id: str,
        request: RuntimeErrorReportRequest,
        device_id: Optional[str] = Query(default=None),
        phone_number: Optional[str] = Query(default=None),
    ) -> dict[str, Any]:
        db: Database = app.state.db
        task = db.get_task(task_id)
        if not task or not is_task_access_allowed(task, device_id=device_id, phone_number=phone_number):
            raise HTTPException(status_code=404, detail="task not found")
        summary = normalize_whitespace(request.summary)
        stack_trace = request.stack_trace.strip()
        db.log_event(
            task_id,
            actor="system",
            event_type="runtime_error_detected",
            message_text=summary,
            payload={
                "package_name": request.package_name.strip(),
                "summary": summary,
                "stack_trace": stack_trace,
                "error_message": normalize_whitespace(request.error_message) if request.error_message else None,
                "report_kind": normalize_whitespace(request.report_kind) if request.report_kind else None,
                "device_id": device_id,
                "phone_number": phone_number,
            },
        )
        return {
            "task_id": task_id,
            "logged": True,
            "summary": summary,
        }

    @app.get("/apps/{task_id}/llm-config")
    def get_app_llm_config(
        task_id: str,
        device_id: Optional[str] = Query(default=None),
        phone_number: Optional[str] = Query(default=None),
    ) -> dict[str, Any]:
        db: Database = app.state.db
        settings: Settings = app.state.settings
        task = db.get_task(task_id)
        if not task or not is_task_access_allowed(task, device_id=device_id, phone_number=phone_number):
            raise HTTPException(status_code=404, detail="task not found")
        config = db.get_app_llm_config(task_id)
        if not config:
            ensure_default_app_llm_config(db, settings, task_id)
            config = db.get_app_llm_config(task_id)
        if not config:
            raise HTTPException(status_code=404, detail="llm config not found")
        return {
            "task_id": task_id,
            "enabled": bool(config.get("enabled")),
            "provider": config.get("provider") or "openai",
            "model": config.get("model") or "",
            "base_url": config.get("base_url") or "",
            "system_prompt": config.get("system_prompt") or "",
            "daily_request_limit": int(config.get("daily_request_limit") or 0),
            "daily_token_limit": int(config.get("daily_token_limit") or 0),
            "max_output_tokens": int(config.get("max_output_tokens") or 0),
            "temperature": float(config.get("temperature") or 0.0),
            "api_key_configured": bool(str(config.get("api_key") or "").strip()),
        }

    @app.post("/apps/{task_id}/llm-config")
    def upsert_app_llm_config_endpoint(
        task_id: str,
        request: AppLlmConfigRequest,
        device_id: Optional[str] = Query(default=None),
        phone_number: Optional[str] = Query(default=None),
    ) -> dict[str, Any]:
        db: Database = app.state.db
        settings: Settings = app.state.settings
        task = db.get_task(task_id)
        if not task or not is_task_access_allowed(task, device_id=device_id, phone_number=phone_number):
            raise HTTPException(status_code=404, detail="task not found")
        existing = db.get_app_llm_config(task_id) or {}
        new_config = merge_app_llm_config_values(
            existing,
            enabled=request.enabled,
            provider=request.provider,
            model=request.model,
            api_key=request.api_key,
            base_url=request.base_url,
            system_prompt=request.system_prompt,
            daily_request_limit=request.daily_request_limit,
            daily_token_limit=request.daily_token_limit,
            max_output_tokens=request.max_output_tokens,
            temperature=request.temperature,
            settings=settings,
        )
        db.upsert_app_llm_config(task_id, new_config)
        db.log_event(
            task_id,
            actor="system",
            event_type="app_llm_config_updated",
            message_text=app_llm_config_event_message(new_config),
            payload=app_llm_config_event_payload(
                new_config,
                previous_config=existing,
                source="task_config_endpoint",
            ),
        )
        return get_app_llm_config(task_id, device_id=device_id, phone_number=phone_number)

    @app.post("/apps/{task_id}/llm/respond")
    def app_llm_respond(task_id: str, request: AppLlmRuntimeRequest) -> dict[str, Any]:
        db: Database = app.state.db
        settings: Settings = app.state.settings
        task = db.get_task(task_id)
        if not task:
            raise HTTPException(status_code=404, detail="task not found")
        config = db.get_app_llm_config(task_id)
        if not config:
            ensure_default_app_llm_config(db, settings, task_id)
            config = db.get_app_llm_config(task_id)
        if not config or not bool(config.get("enabled")):
            raise HTTPException(status_code=403, detail="app llm runtime disabled")

        expected_package_name = str(task.get("package_name") or "").strip()
        if expected_package_name and request.package_name.strip() != expected_package_name:
            raise HTTPException(status_code=403, detail="package name mismatch")

        day_start = utc_day_start_iso()
        usage_snapshot = db.get_app_llm_daily_usage(task_id, day_prefix=day_start)
        daily_request_limit = int(config.get("daily_request_limit") or 0)
        daily_token_limit = int(config.get("daily_token_limit") or 0)
        if daily_request_limit > 0 and usage_snapshot["request_count"] >= daily_request_limit:
            db.log_event(
                task_id,
                actor="system",
                event_type="app_llm_quota_exceeded",
                message_text="daily request limit exceeded",
                payload={"request_count": usage_snapshot["request_count"], "daily_request_limit": daily_request_limit},
            )
            raise HTTPException(status_code=429, detail="daily request limit exceeded")
        if daily_token_limit > 0 and usage_snapshot["total_tokens"] >= daily_token_limit:
            db.log_event(
                task_id,
                actor="system",
                event_type="app_llm_quota_exceeded",
                message_text="daily token limit exceeded",
                payload={"total_tokens": usage_snapshot["total_tokens"], "daily_token_limit": daily_token_limit},
            )
            raise HTTPException(status_code=429, detail="daily token limit exceeded")

        try:
            model_response = invoke_app_runtime_model(config, request)
        except ValueError as exc:
            db.record_app_llm_usage(
                task_id=task_id,
                package_name=request.package_name.strip(),
                input_tokens=None,
                output_tokens=None,
                total_tokens=None,
                status="configuration_error",
            )
            raise HTTPException(status_code=400, detail=str(exc)) from exc
        except httpx.HTTPStatusError as exc:
            db.record_app_llm_usage(
                task_id=task_id,
                package_name=request.package_name.strip(),
                input_tokens=None,
                output_tokens=None,
                total_tokens=None,
                status=f"http_{exc.response.status_code}",
            )
            raise HTTPException(status_code=502, detail="upstream llm request failed") from exc
        except httpx.HTTPError as exc:
            db.record_app_llm_usage(
                task_id=task_id,
                package_name=request.package_name.strip(),
                input_tokens=None,
                output_tokens=None,
                total_tokens=None,
                status="network_error",
            )
            raise HTTPException(status_code=502, detail="upstream llm network error") from exc

        usage = model_response.get("usage") or {}
        total_tokens = int(usage.get("total_tokens") or 0)
        if daily_token_limit > 0 and usage_snapshot["total_tokens"] + total_tokens > daily_token_limit:
            db.record_app_llm_usage(
                task_id=task_id,
                package_name=request.package_name.strip(),
                input_tokens=int(usage.get("input_tokens") or 0),
                output_tokens=int(usage.get("output_tokens") or 0),
                total_tokens=total_tokens,
                status="token_limit_exceeded",
            )
            raise HTTPException(status_code=429, detail="daily token limit exceeded")

        db.record_app_llm_usage(
            task_id=task_id,
            package_name=request.package_name.strip(),
            input_tokens=int(usage.get("input_tokens") or 0),
            output_tokens=int(usage.get("output_tokens") or 0),
            total_tokens=total_tokens,
            status="success",
        )
        runtime_instructions = build_app_runtime_instructions(config, request)
        db.log_event(
            task_id,
            actor="system",
            event_type="app_llm_response",
            message_text=str(model_response.get("message") or ""),
            payload={
                "package_name": request.package_name.strip(),
                "model": config.get("model") or "",
                "system_prompt": str(config.get("system_prompt") or ""),
                "runtime_instructions": runtime_instructions,
                "user_message": request.user_message,
                "context": request.context or "",
                "image_attached": bool(request.image_base64 and request.image_base64.strip()),
                "image_mime_type": request.image_mime_type or "",
                "input_tokens": int(usage.get("input_tokens") or 0),
                "output_tokens": int(usage.get("output_tokens") or 0),
                "total_tokens": total_tokens,
            },
        )
        return {
            "task_id": task_id,
            "message": str(model_response.get("message") or ""),
            "model": config.get("model") or "",
            "provider": config.get("provider") or "openai",
            "usage": usage,
            "daily_usage": {
                "request_count": usage_snapshot["request_count"] + 1,
                "total_tokens": usage_snapshot["total_tokens"] + total_tokens,
                "daily_request_limit": daily_request_limit,
                "daily_token_limit": daily_token_limit,
            },
        }

    @app.get("/tasks")
    def list_tasks(
        user_id: Optional[str] = Query(default=None),
        device_id: Optional[str] = Query(default=None),
        phone_number: Optional[str] = Query(default=None),
    ) -> dict[str, list[dict[str, Any]]]:
        db: Database = app.state.db
        if not any((device_id, phone_number, user_id)):
            raise HTTPException(status_code=400, detail="device_id or phone_number is required")
        tasks = db.query_tasks(user_id=user_id, device_id=device_id, phone_number=phone_number)
        return {"tasks": [serialize_task_summary(task) for task in tasks]}

    @app.get("/download/{task_id}")
    def download_apk(
        task_id: str,
        device_id: Optional[str] = Query(default=None),
        phone_number: Optional[str] = Query(default=None),
        user_id: Optional[str] = Query(default=None),
        artifact_path: Optional[str] = Query(default=None),
    ) -> FileResponse:
        db: Database = app.state.db
        task = db.get_task(task_id)
        if not task:
            raise HTTPException(status_code=404, detail="task not found")
        if not is_task_access_allowed(task, device_id=device_id, phone_number=phone_number):
            raise HTTPException(status_code=404, detail="task not found")
        if not task.get("workspace_path"):
            raise HTTPException(status_code=404, detail="apk not available")

        workspace_path = Path(task["workspace_path"])
        apk_path_value = normalize_whitespace(str(artifact_path or task.get("apk_path") or ""))
        if not apk_path_value:
            raise HTTPException(status_code=404, detail="apk not available")
        apk_path = Path(apk_path_value)
        if not apk_path.is_absolute():
            apk_path = workspace_path / apk_path
        apk_path = apk_path.resolve()
        if not ensure_within_root(apk_path, workspace_path):
            raise HTTPException(status_code=403, detail="invalid apk path")
        if not apk_path.exists() or apk_path.suffix.lower() != ".apk":
            raise HTTPException(status_code=404, detail="apk not found")

        return FileResponse(
            apk_path,
            media_type="application/vnd.android.package-archive",
            filename=apk_path.name,
        )

    return app


app = create_app()


# Warning: exposing this server beyond localhost/private networks requires proper authentication,
# TLS, and download authorization checks. The sample implementation intentionally keeps auth out
# of scope for a minimal local/private deployment.
