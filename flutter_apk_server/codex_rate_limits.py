import json
import selectors
import subprocess
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Optional, cast

import httpx

try:
    from .server_settings import build_subprocess_environment, infer_codex_cli_binary
except ImportError:
    from server_settings import build_subprocess_environment, infer_codex_cli_binary


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


def parse_backend_rate_limit_window(payload: Any) -> Optional[CodexRateLimitWindow]:
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


def parse_backend_rate_limits_payload(payload: Any) -> CodexRateLimitSnapshot:
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
                line = cast(Any, key.fileobj).readline()
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
) -> CodexRateLimitSnapshot:
    process_env = build_subprocess_environment(env)
    process = subprocess.Popen(
        [codex_binary, "app-server", "--listen", "stdio://"],
        stdin=subprocess.PIPE,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
        bufsize=1,
        env=process_env,
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

        snapshot_payload = result.get("rateLimits")
        if not isinstance(snapshot_payload, dict):
            by_limit_id = result.get("rateLimitsByLimitId")
            if isinstance(by_limit_id, dict):
                snapshot_payload = by_limit_id.get("codex")
        if not isinstance(snapshot_payload, dict):
            raise RuntimeError("Codex rate limit snapshot이 없습니다.")
        return CodexRateLimitSnapshot(
            limit_name=str(snapshot_payload.get("limitName") or snapshot_payload.get("limitId") or "codex"),
            primary=_parse_app_server_window(snapshot_payload.get("primary")),
            secondary=_parse_app_server_window(snapshot_payload.get("secondary")),
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


def _parse_app_server_window(payload: Any) -> Optional[CodexRateLimitWindow]:
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


def fetch_codex_rate_limits_via_backend(
    timeout_seconds: float = 20.0,
    *,
    home_path: Optional[Path] = None,
) -> CodexRateLimitSnapshot:
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
) -> CodexRateLimitSnapshot:
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
