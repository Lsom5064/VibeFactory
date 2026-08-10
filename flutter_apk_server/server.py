import base64
import binascii
import hashlib
import importlib
import json
import os
import queue
import re
import selectors
import shlex
import shutil
import signal
import sqlite3
import subprocess
import threading
import time
import uuid
from contextlib import asynccontextmanager
from dataclasses import dataclass, field, replace
from datetime import datetime, timezone
from io import BytesIO
from pathlib import Path
from typing import Any, Optional, cast

import httpx
from fastapi import FastAPI, Header, HTTPException, Query
from fastapi.responses import FileResponse
from PIL import Image, ImageOps, UnidentifiedImageError  # type: ignore[import-untyped]
from pydantic import BaseModel, Field


REFERENCE_IMAGE_MAX_SOURCE_BYTES = 20 * 1024 * 1024
REFERENCE_IMAGE_MAX_STORED_BYTES = 2 * 1024 * 1024
REFERENCE_IMAGE_MAX_DIMENSION = 1600
REFERENCE_IMAGE_JPEG_QUALITIES = (88, 82, 76, 68, 60, 52, 44)
REFERENCE_IMAGE_DIMENSION_STEPS = (1600, 1400, 1200, 1024, 800)
# Generated APKs are distributed outside an app store. Keeping one high, fixed
# version code allows any saved revision to replace any other saved revision.
GENERATED_APK_SIDELOAD_VERSION_CODE = 1_900_000_000


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
    codex_model = os.getenv("CODEX_MODEL", "gpt-5.4").strip() or "gpt-5.4"
    args.extend(["--model", shlex.quote(codex_model)])
    reasoning_effort = (os.getenv("CODEX_REASONING_EFFORT", "default").strip().lower() or "default")
    if reasoning_effort == "default":
        reasoning_effort = "medium"
    args.extend(["-c", shlex.quote(f'model_reasoning_effort="{reasoning_effort}"')])
    service_tier = (os.getenv("CODEX_SERVICE_TIER", "default").strip().lower() or "default")
    args.extend(["-c", shlex.quote(f'service_tier="{service_tier}"')])
    if env_flag("CODEX_FAST_MODE", service_tier in {"priority", "fast"}):
        args.extend(["--enable", "fast_mode"])
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


def should_attempt_server_side_build(
    *,
    result_exists: bool,
    identity_changed: bool,
    timed_out: bool,
    engine_issue: Optional[tuple[str, str, str, str]],
) -> bool:
    if result_exists:
        return identity_changed
    return not timed_out and engine_issue is None


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


def reference_attachment_file_metadata(workspace_root: Path, workspace_path: str) -> Optional[dict[str, Any]]:
    normalized_path = normalize_whitespace(str(workspace_path or ""))
    if not normalized_path:
        return None
    candidate = (workspace_root / normalized_path).resolve()
    if not ensure_within_root(candidate, workspace_root.resolve()) or not candidate.is_file():
        return None
    try:
        data = candidate.read_bytes()
    except OSError:
        return None
    return {
        "workspace_path": str(candidate.relative_to(workspace_root.resolve())),
        "absolute_path": str(candidate),
        "size_bytes": len(data),
        "sha256": hashlib.sha256(data).hexdigest(),
    }


def optimize_reference_image_bytes(image_bytes: bytes) -> dict[str, Any]:
    original_size_bytes = len(image_bytes)
    if original_size_bytes > REFERENCE_IMAGE_MAX_SOURCE_BYTES:
        return {
            "status": "failed",
            "error_message": (
                f"image exceeds source size limit "
                f"({original_size_bytes} > {REFERENCE_IMAGE_MAX_SOURCE_BYTES} bytes)"
            ),
        }

    try:
        with Image.open(BytesIO(image_bytes)) as opened_image:
            opened_image.load()
            transposed_image = ImageOps.exif_transpose(opened_image)
            original_width, original_height = transposed_image.size
            if "A" in transposed_image.getbands():
                rgba_image = transposed_image.convert("RGBA")
                prepared_image = Image.new("RGB", rgba_image.size, "white")
                prepared_image.paste(rgba_image, mask=rgba_image.getchannel("A"))
                rgba_image.close()
            else:
                prepared_image = transposed_image.convert("RGB")
    except (Image.DecompressionBombError, UnidentifiedImageError, OSError, ValueError) as exc:
        return {
            "status": "failed",
            "error_message": f"invalid or unsupported image: {exc}",
        }

    smallest_candidate: Optional[bytes] = None
    stored_width = prepared_image.width
    stored_height = prepared_image.height
    try:
        for max_dimension in REFERENCE_IMAGE_DIMENSION_STEPS:
            candidate_image = prepared_image.copy()
            candidate_image.thumbnail(
                (max_dimension, max_dimension),
                Image.Resampling.LANCZOS,
            )
            try:
                for quality in REFERENCE_IMAGE_JPEG_QUALITIES:
                    output = BytesIO()
                    candidate_image.save(
                        output,
                        format="JPEG",
                        quality=quality,
                        optimize=True,
                    )
                    candidate = output.getvalue()
                    if smallest_candidate is None or len(candidate) < len(smallest_candidate):
                        smallest_candidate = candidate
                        stored_width, stored_height = candidate_image.size
                    if len(candidate) <= REFERENCE_IMAGE_MAX_STORED_BYTES:
                        return {
                            "status": "optimized",
                            "data": candidate,
                            "mime_type": "image/jpeg",
                            "suffix": ".jpg",
                            "original_size_bytes": original_size_bytes,
                            "size_bytes": len(candidate),
                            "original_width": original_width,
                            "original_height": original_height,
                            "stored_width": candidate_image.width,
                            "stored_height": candidate_image.height,
                            "optimized": (
                                candidate != image_bytes
                                or original_width != candidate_image.width
                                or original_height != candidate_image.height
                            ),
                        }
            finally:
                candidate_image.close()
    finally:
        prepared_image.close()

    return {
        "status": "failed",
        "error_message": (
            "image could not be compressed below storage limit "
            f"({len(smallest_candidate or b'')} > {REFERENCE_IMAGE_MAX_STORED_BYTES} bytes, "
            f"last dimensions {stored_width}x{stored_height})"
        ),
    }


def save_reference_image_attachment_result(
    workspace_root: Path,
    *,
    reference_image_name: str,
    reference_image_base64: str,
) -> dict[str, Any]:
    normalized_name = normalize_reference_image_name(reference_image_name)
    normalized_base64 = normalize_reference_image_base64(reference_image_base64)
    if not normalized_name or not normalized_base64:
        return {
            "status": "failed",
            "error_message": "missing image name or base64 payload",
        }
    try:
        image_bytes = base64.b64decode(normalized_base64, validate=False)
    except (ValueError, binascii.Error):
        return {
            "status": "failed",
            "error_message": "invalid base64 image payload",
        }
    if not image_bytes:
        return {
            "status": "failed",
            "error_message": "empty decoded image payload",
        }

    optimized = optimize_reference_image_bytes(image_bytes)
    if optimized.get("status") != "optimized":
        return optimized
    stored_bytes = bytes(optimized.get("data") or b"")
    if not stored_bytes:
        return {
            "status": "failed",
            "error_message": "image optimization produced an empty payload",
        }

    image_dir = workspace_root / "reference_images"
    image_dir.mkdir(parents=True, exist_ok=True)
    safe_stem = sanitize_component(Path(normalized_name).stem or "reference_image")
    filename = f"{utc_now_compact()}_{safe_stem}_{uuid.uuid4().hex[:8]}.jpg"
    image_path = image_dir / filename
    image_path.write_bytes(stored_bytes)
    return {
        "status": "saved",
        "workspace_path": str(image_path.relative_to(workspace_root)),
        "absolute_path": str(image_path),
        "mime_type": "image/jpeg",
        "size_bytes": len(stored_bytes),
        "sha256": hashlib.sha256(stored_bytes).hexdigest(),
        "original_size_bytes": int(optimized.get("original_size_bytes") or len(image_bytes)),
        "original_width": int(optimized.get("original_width") or 0),
        "original_height": int(optimized.get("original_height") or 0),
        "stored_width": int(optimized.get("stored_width") or 0),
        "stored_height": int(optimized.get("stored_height") or 0),
        "optimized": bool(optimized.get("optimized")),
    }


def normalize_reference_attachments(value: Any) -> list[dict[str, str]]:
    if not isinstance(value, list):
        return []
    normalized: list[dict[str, str]] = []
    for item in value[:8]:
        if isinstance(item, BaseModel):
            raw = pydantic_model_to_dict(item)
        elif isinstance(item, dict):
            raw = item
        else:
            continue
        attachment_type = normalize_whitespace(str(raw.get("type") or raw.get("payload_type") or "")).lower()
        mime_type = normalize_whitespace(str(raw.get("mime_type") or raw.get("mimeType") or ""))
        name = normalize_reference_image_name(raw.get("name") or raw.get("displayName") or raw.get("reference_image_name"))
        base64_value = normalize_reference_image_base64(raw.get("base64") or raw.get("reference_image_base64"))
        workspace_path = normalize_whitespace(str(raw.get("workspace_path") or raw.get("reference_image_workspace_path") or ""))
        if not name:
            name = "reference_image"
        is_image = attachment_type == "image" or mime_type.lower().startswith("image/")
        if not is_image:
            continue
        if not base64_value and not workspace_path:
            continue
        normalized.append(
            {
                "type": "image",
                "mime_type": mime_type or f"image/{infer_reference_image_suffix(name).lstrip('.')}",
                "name": name,
                "base64": base64_value,
                "workspace_path": workspace_path,
            }
        )
    return normalized


def pydantic_model_to_dict(model: BaseModel) -> dict[str, Any]:
    model_dump = getattr(model, "model_dump", None)
    payload = model_dump() if callable(model_dump) else model.dict()
    return dict(payload) if isinstance(payload, dict) else {}


def request_reference_attachments(request: "GenerateRequest") -> list[dict[str, str]]:
    attachments = normalize_reference_attachments(request.attachments)
    legacy_name = normalize_reference_image_name(request.reference_image_name)
    legacy_base64 = normalize_reference_image_base64(request.reference_image_base64)
    if legacy_name and legacy_base64:
        legacy = {
            "type": "image",
            "mime_type": f"image/{infer_reference_image_suffix(legacy_name).lstrip('.')}",
            "name": legacy_name,
            "base64": legacy_base64,
            "workspace_path": "",
        }
        if not any(item.get("base64") == legacy_base64 for item in attachments):
            attachments.insert(0, legacy)
    return attachments[:8]


def first_reference_attachment(attachments: list[dict[str, str]]) -> dict[str, str]:
    return next((item for item in attachments if item.get("base64") or item.get("workspace_path")), {})


def save_reference_attachments(workspace_root: Path, attachments: list[dict[str, str]]) -> list[dict[str, Any]]:
    saved: list[dict[str, Any]] = []
    for attachment in normalize_reference_attachments(attachments):
        workspace_path = attachment.get("workspace_path") or ""
        save_result: dict[str, Any] = {}
        existing_metadata = reference_attachment_file_metadata(workspace_root, workspace_path)
        if existing_metadata:
            save_result = {
                "status": "existing",
                **existing_metadata,
            }
        if attachment.get("base64"):
            if not existing_metadata:
                save_result = save_reference_image_attachment_result(
                    workspace_root,
                    reference_image_name=attachment.get("name") or "reference_image",
                    reference_image_base64=attachment.get("base64") or "",
                )
            workspace_path = str(save_result.get("workspace_path") or workspace_path)
        elif not save_result:
            save_result = {
                "status": "pending",
                "error_message": "image payload is referenced by path only or has not been saved yet",
            }
        saved.append(
            {
                **attachment,
                "mime_type": str(save_result.get("mime_type") or attachment.get("mime_type") or ""),
                "workspace_path": workspace_path,
                "base64": "" if workspace_path else attachment.get("base64", ""),
                "save_status": str(save_result.get("status") or ""),
                "absolute_path": str(save_result.get("absolute_path") or ""),
                "size_bytes": str(save_result.get("size_bytes") or ""),
                "sha256": str(save_result.get("sha256") or ""),
                "original_size_bytes": str(save_result.get("original_size_bytes") or ""),
                "original_width": str(save_result.get("original_width") or ""),
                "original_height": str(save_result.get("original_height") or ""),
                "stored_width": str(save_result.get("stored_width") or ""),
                "stored_height": str(save_result.get("stored_height") or ""),
                "optimized": bool(save_result.get("optimized")),
                "error_message": str(save_result.get("error_message") or ""),
            }
        )
    return saved


def reference_attachment_event_payload(attachment: dict[str, Any]) -> dict[str, Any]:
    return {
        "type": attachment.get("type") or "image",
        "mime_type": attachment.get("mime_type") or "",
        "name": attachment.get("name") or "reference_image",
        "workspace_path": attachment.get("workspace_path") or "",
        "absolute_path": attachment.get("absolute_path") or "",
        "size_bytes": int(attachment.get("size_bytes") or 0),
        "sha256": attachment.get("sha256") or "",
        "original_size_bytes": int(attachment.get("original_size_bytes") or 0),
        "original_width": int(attachment.get("original_width") or 0),
        "original_height": int(attachment.get("original_height") or 0),
        "stored_width": int(attachment.get("stored_width") or 0),
        "stored_height": int(attachment.get("stored_height") or 0),
        "optimized": bool(attachment.get("optimized")),
        "status": attachment.get("save_status") or "",
        "error_message": attachment.get("error_message") or "",
    }


def reference_attachments_summary(attachments: list[dict[str, str]]) -> str:
    normalized = normalize_reference_attachments(attachments)
    if not normalized:
        return ""
    names = [item.get("name") or "reference_image" for item in normalized]
    if len(names) == 1:
        return build_reference_image_summary(names[0])
    preview = ", ".join(names[:3])
    suffix = f" 외 {len(names) - 3}개" if len(names) > 3 else ""
    return f"참고 이미지 {len(names)}개({preview}{suffix})를 함께 전달받았어요. 각 이미지의 UI, 레이아웃, 콘텐츠 맥락을 함께 참고합니다."


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
    build_cache_root: Path
    codex_command: str
    flutter_command: str
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
    shared_build_cache_enabled: bool
    optimized_download_apk_enabled: bool
    android_only_workspace_enabled: bool
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
        build_cache_root=resolve_path(
            os.getenv("BUILD_CACHE_ROOT", ""),
            root / ".tooling",
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
        app_data_db_path=resolve_path(
            os.getenv("APP_DATA_DB_PATH", ""),
            root / "app_data.db",
            root,
        ),
        mock_codex=mock_codex,
        status_log_line_limit=max(1, int(os.getenv("STATUS_LOG_LINE_LIMIT", "50"))),
        intent_agent_enabled=env_flag("INTENT_AGENT_ENABLED", not mock_codex),
        intent_agent_model=os.getenv("INTENT_AGENT_MODEL", "gpt-5.4").strip() or "gpt-5.4",
        intent_agent_timeout_seconds=max(5, int(os.getenv("INTENT_AGENT_TIMEOUT_SECONDS", "20"))),
        codex_existing_task_followup_enabled=env_flag("CODEX_EXISTING_TASK_FOLLOWUP_ENABLED", True),
        codex_followup_decision_timeout_seconds=max(10, int(os.getenv("CODEX_FOLLOWUP_DECISION_TIMEOUT_SECONDS", "90"))),
        codex_followup_reasoning_effort=normalize_codex_reasoning_effort(
            os.getenv("CODEX_FOLLOWUP_REASONING_EFFORT", "low"),
            default="low",
        ),
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
        app_runtime_max_output_tokens=max(0, int(os.getenv("APP_RUNTIME_MAX_OUTPUT_TOKENS", "0"))),
        app_runtime_temperature=float(os.getenv("APP_RUNTIME_TEMPERATURE", "0.4")),
        shared_build_cache_enabled=env_flag("SHARED_BUILD_CACHE_ENABLED", True),
        optimized_download_apk_enabled=env_flag("OPTIMIZED_DOWNLOAD_APK_ENABLED", True),
        android_only_workspace_enabled=env_flag("ANDROID_ONLY_WORKSPACE_ENABLED", True),
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
    prepared_prompt: str = ""
    target_users: list[str] = field(default_factory=list)
    key_screens: list[str] = field(default_factory=list)
    storage_mode: str = "unspecified"
    stored_data: list[str] = field(default_factory=list)


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
        payload = pydantic_model_to_dict(device_info)
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
    raw_usage_payload = payload.get("usage")
    usage_payload: dict[str, Any] = raw_usage_payload if isinstance(raw_usage_payload, dict) else {}
    raw_input_details = usage_payload.get("input_tokens_details")
    input_details: dict[str, Any] = raw_input_details if isinstance(raw_input_details, dict) else {}
    raw_output_details = usage_payload.get("output_tokens_details")
    output_details: dict[str, Any] = raw_output_details if isinstance(raw_output_details, dict) else {}

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
    if decision.mode == "ask_confirmation" and decision.confirmation_action == "submit_initial_prompt":
        return {
            "interaction_type": "needs_initial_prompt_review",
            "render_mode": "prompt_review_bubble",
            "requires_user_input": False,
            "requires_confirmation": True,
            "pending_decision_reason": "initial_prompt_review",
            "suppress_assistant_bubble": False,
        }
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
        "검색, 필터, 알림 중 이번 버전에 꼭 필요한 기능이 있을까요?",
        "앱을 다시 열었을 때 어떤 정보가 저장되어 있어야 할까요?",
    ]
    if "sns" in lowered or "대화" in lowered:
        questions = [
            "묶고 싶은 SNS나 메신저 종류는 무엇인가요?",
            "한 화면 통합 피드로 볼까요, 서비스별 탭으로 나눌까요?",
            "이번 버전은 보기 중심이면 될까요, 답장이나 작성 흐름도 필요할까요?",
            "대화나 게시글을 검색하거나 즐겨찾기하는 기능도 필요할까요?",
            "알림이나 새 메시지 표시가 이번 버전에 꼭 필요할까요?",
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


def normalize_prompt_items(items: Optional[list[Any]], *, max_items: int) -> list[str]:
    values: list[str] = []
    seen: set[str] = set()
    for item in items or []:
        text = normalize_whitespace(str(item or ""))
        text = re.sub(r"^[-*•]\s*", "", text).strip(" .,!?:;-")
        if not text or text in seen:
            continue
        seen.add(text)
        values.append(text)
        if len(values) >= max_items:
            break
    return values


def normalize_target_users(items: Optional[list[Any]]) -> list[str]:
    generic_audiences = {
        "일반 사용자",
        "모든 사용자",
        "일반 android 사용자",
        "일반 android 스마트폰 사용자",
        "android 스마트폰 사용자",
        "스마트폰 사용자",
    }
    return [
        item
        for item in normalize_prompt_items(items, max_items=5)
        if item.lower() not in generic_audiences
    ]


def normalize_storage_mode(value: Any) -> str:
    normalized = normalize_whitespace(str(value or "")).lower()
    aliases = {
        "none": "none",
        "없음": "none",
        "local": "local",
        "로컬": "local",
        "기기": "local",
        "server": "server",
        "서버": "server",
        "cloud": "server",
        "클라우드": "server",
        "unspecified": "unspecified",
        "미정": "unspecified",
    }
    return aliases.get(normalized, "unspecified")


def infer_target_users(prompt: str) -> list[str]:
    normalized = normalize_whitespace(prompt)
    candidate_roles = (
        "원장", "수강생", "학부모", "학생", "교사", "강사", "의사", "간호사", "환자",
        "관리자", "직원", "고객", "보호자", "참가자", "운전자", "보행자", "판매자", "구매자",
    )
    return [role for role in candidate_roles if role in normalized][:5]


def infer_storage_details(prompt: str) -> tuple[str, list[str]]:
    normalized = normalize_whitespace(prompt)
    lowered = normalized.lower()
    server_markers = (
        "여러 사용자", "사용자 간", "공유", "동기화", "여러 기기", "다중 기기", "계정",
        "로그인", "서버", "클라우드",
    )
    local_markers = (
        "저장", "기록", "히스토리", "보관", "목록 유지", "메모", "즐겨찾기", "체크리스트",
        "달력", "일정", "일기", "가계부", "진도", "출석",
    )
    storage_mode = "server" if any(marker in lowered for marker in server_markers) else (
        "local" if any(marker in lowered for marker in local_markers) else "none"
    )
    stored_data = [
        item
        for item in extract_feature_points(prompt)
        if any(marker in item.lower() for marker in local_markers + server_markers)
    ][:6]
    return storage_mode, stored_data


def infer_key_screens(prompt: str, feature_points: list[str]) -> list[str]:
    normalized = normalize_whitespace(prompt)
    screen_names: list[str] = []
    explicit_patterns = (
        r"([가-힣A-Za-z0-9 ]{2,20}(?:화면|페이지|탭|대시보드|달력|목록))",
    )
    for pattern in explicit_patterns:
        for match in re.finditer(pattern, normalized):
            candidate = normalize_whitespace(match.group(1)).strip(" .,!?:;-")
            if candidate and candidate not in screen_names:
                screen_names.append(candidate)
            if len(screen_names) >= 6:
                return screen_names
    return normalize_prompt_items(feature_points[:4], max_items=4)


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
            "앱을 다시 열었을 때 메모가 유지되어야 할까요?",
            "중요 메모 고정이나 즐겨찾기도 이번 버전에 필요할까요?",
        ]

    return [
        "기본 등록 기능만 있으면 될까요, 수정과 삭제도 같이 필요할까요?",
        "첫 화면은 입력 중심으로 할까요, 목록이나 대시보드 중심으로 할까요?",
        "검색, 필터, 알림 중 이번에 꼭 필요한 보조 기능이 있을까요?",
        "앱을 다시 열었을 때 어떤 정보가 저장되어 있어야 할까요?",
        "목록 정렬이나 즐겨찾기 같은 관리 기능도 필요할까요?",
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


def build_codex_followup_build_summary(
    app_name: str,
    change_summary: str,
    user_prompt: str,
) -> str:
    app_label = app_name if app_name and app_name != "맞춤 앱" else "요청하신 앱"
    if len(app_label) <= 1 or app_label in {"이", "그", "저"}:
        app_label = "앱"
    intro = f"기존 {append_object_particle_korean(app_label)} 수정할게요."
    fallback = f"{intro} 요청한 변경 내용을 현재 앱의 구성에 맞게 반영해요."
    sanitized = normalize_whitespace(sanitize_codex_followup_user_text(change_summary))
    if not sanitized:
        return fallback
    normalized_summary = re.sub(r"[\s\"'`.,!?]+", "", sanitized).lower()
    normalized_prompt = re.sub(
        r"[\s\"'`.,!?]+",
        "",
        normalize_whitespace(user_prompt),
    ).lower()
    if normalized_prompt and normalized_prompt in normalized_summary:
        return fallback
    if sanitized.startswith(("기존 ", "현재 앱", "요청하신 앱")):
        return sanitized
    return f"{intro} {sanitized}"


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
    user_visible_summary: str = "",
    core_features: Optional[list[str]] = None,
    target_users: Optional[list[str]] = None,
    key_screens: Optional[list[str]] = None,
    storage_mode: str = "unspecified",
    stored_data: Optional[list[str]] = None,
) -> IntentDecision:
    raw_effective_prompt = effective_user_prompt or user_prompt
    effective_prompt = normalize_whitespace(raw_effective_prompt)
    normalized_user_prompt = normalize_whitespace(user_prompt)
    feature_points = normalize_prompt_items(core_features, max_items=8) or extract_feature_points(raw_effective_prompt)
    app_name = normalize_whitespace(suggested_app_name)
    if app_name in {"맞춤 앱", "요청 앱", "새 앱", "앱"}:
        app_name = ""
    if not app_name:
        app_name = infer_app_name(effective_prompt)
    package_name = infer_package_name(app_name, task_id)
    resolved_primary_user_flow = normalize_whitespace(primary_user_flow) or infer_primary_user_flow(raw_effective_prompt, feature_points, app_name)
    resolved_secondary_requirements = normalize_secondary_requirements(secondary_requirements)
    resolved_acceptance_criteria = normalize_acceptance_criteria(acceptance_criteria)
    resolved_target_users = normalize_target_users(target_users) or infer_target_users(raw_effective_prompt)
    resolved_key_screens = normalize_prompt_items(key_screens, max_items=6) or infer_key_screens(
        raw_effective_prompt,
        feature_points,
    )
    inferred_storage_mode, inferred_stored_data = infer_storage_details(raw_effective_prompt)
    resolved_storage_mode = normalize_storage_mode(storage_mode)
    if resolved_storage_mode == "unspecified":
        resolved_storage_mode = inferred_storage_mode
    resolved_stored_data = normalize_prompt_items(stored_data, max_items=6) or inferred_stored_data
    if mode in {"build", "ask_confirmation"} and not resolved_acceptance_criteria:
        resolved_acceptance_criteria = infer_acceptance_criteria(raw_effective_prompt, feature_points)
    resolved_request_scope = request_scope or ("existing_app_modification" if existing_task else "new_app")
    if mode == "answer_question":
        answer_message = assistant_message or make_answer_message(user_prompt)
        answer_request_scope = resolved_request_scope
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
            target_users=[],
            key_screens=[],
            storage_mode="unspecified",
            stored_data=[],
        )
    if mode == "build":
        continue_existing_app = resolved_request_scope == "existing_app_modification"
        explicit_summary = normalize_whitespace(user_visible_summary)
        if continue_existing_app:
            summary = explicit_summary or build_build_summary(
                app_name,
                feature_points,
                existing_task=True,
                primary_user_flow=resolved_primary_user_flow,
                secondary_requirements=resolved_secondary_requirements,
            )
            message = ""
        else:
            summary = explicit_summary or build_build_summary(
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
            target_users=resolved_target_users,
            key_screens=resolved_key_screens,
            storage_mode=resolved_storage_mode,
            stored_data=resolved_stored_data,
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
        target_users=resolved_target_users,
        key_screens=resolved_key_screens,
        storage_mode=resolved_storage_mode,
        stored_data=resolved_stored_data,
    )


def build_prepared_generation_prompt(decision: IntentDecision) -> str:
    app_name = normalize_whitespace(decision.app_name) or infer_app_name(decision.effective_user_prompt)
    purpose = normalize_whitespace(decision.primary_user_flow) or "사용자가 요청한 목적을 중심으로 Flutter Android 앱을 만든다."
    feature_points = normalize_prompt_items(decision.feature_points, max_items=8) or extract_feature_points(
        decision.effective_user_prompt
    )
    secondary_requirements = normalize_secondary_requirements(decision.secondary_requirements)
    acceptance_criteria = normalize_acceptance_criteria(decision.acceptance_criteria)
    target_users = normalize_target_users(decision.target_users)
    key_screens = normalize_prompt_items(decision.key_screens, max_items=6) or infer_key_screens(
        decision.effective_user_prompt,
        feature_points,
    )
    storage_mode = normalize_storage_mode(decision.storage_mode)
    stored_data = normalize_prompt_items(decision.stored_data, max_items=6)
    image_reference_summary = normalize_whitespace(decision.image_reference_summary)
    image_conflict_note = normalize_whitespace(decision.image_conflict_note)
    original_request = normalize_whitespace(decision.effective_user_prompt)

    sections: list[str] = ["# 앱 생성 프롬프트"]

    def add_text_section(title: str, value: str) -> None:
        normalized = normalize_whitespace(value)
        if normalized:
            sections.extend(["", f"## {title}", normalized])

    def add_list_section(title: str, values: list[str]) -> None:
        normalized_values = normalize_prompt_items(values, max_items=8)
        if normalized_values:
            sections.extend(["", f"## {title}", *(f"- {item}" for item in normalized_values)])

    add_text_section("앱 이름", app_name)
    add_text_section("앱 목적", purpose)
    add_list_section("주요 사용자", target_users)
    add_list_section("주요 화면", key_screens)
    add_list_section("핵심 기능", feature_points)
    add_list_section("추가 기능", secondary_requirements)

    if storage_mode == "local":
        storage_lines = stored_data or ["앱에서 사용자가 작성하거나 변경한 정보"]
        storage_lines = [*storage_lines, "해당 정보는 앱을 다시 열어도 유지되도록 기기에 저장한다."]
        add_list_section("저장할 정보와 방식", storage_lines)
    elif storage_mode == "server":
        storage_lines = stored_data or ["여러 사용자 또는 여러 기기가 함께 사용하는 정보"]
        storage_lines = [*storage_lines, "공유 정보는 서버 데이터 API를 통해 저장하고 불러온다."]
        add_list_section("공유 데이터와 저장 방식", storage_lines)

    attachment_lines = []
    if image_reference_summary:
        attachment_lines.append(image_reference_summary)
    if image_conflict_note:
        attachment_lines.append(f"주의: {image_conflict_note}")
    add_list_section("첨부 자료 반영", attachment_lines)
    add_list_section("완성 조건", acceptance_criteria)
    add_text_section("구체화된 앱 생성 요청", original_request)
    return "\n".join(sections).strip()


def build_initial_prompt_review_decision(decision: IntentDecision) -> IntentDecision:
    if decision.request_scope != "new_app" or decision.mode not in {"build", "ask_confirmation"}:
        return decision
    prepared_prompt = build_prepared_generation_prompt(decision)
    return replace(
        decision,
        mode="ask_confirmation",
        status="Pending Decision",
        tool="ask_confirmation",
        message="앱 생성 프롬프트를 준비했어요. 확인하거나 수정한 뒤 전송해 주세요.",
        summary="아래 프롬프트를 확인한 뒤 그대로 보내거나 필요한 내용을 직접 고쳐서 보낼 수 있어요.",
        questions=[],
        reason="사용자가 최종 앱 생성 프롬프트를 확인한 뒤 빌드를 시작합니다.",
        effective_user_prompt=prepared_prompt,
        normalized_prompt=build_normalized_prompt(
            decision.app_name,
            decision.package_name,
            prepared_prompt,
            extract_feature_points(prepared_prompt),
            decision.primary_user_flow,
            decision.secondary_requirements,
            decision.acceptance_criteria,
        ),
        confirmation_action="submit_initial_prompt",
        confirmation_payload=prepared_prompt,
        prepared_prompt=prepared_prompt,
    )


def build_initial_prompt_submission_decision(
    *,
    task_id: str,
    final_prompt: str,
    previous_conversation_state: dict[str, Any],
) -> IntentDecision:
    submitted_prompt = final_prompt.strip()
    app_name = normalize_whitespace(
        str(
            previous_conversation_state.get("pending_app_name")
            or previous_conversation_state.get("app_name")
            or previous_conversation_state.get("generated_app_name")
            or ""
        )
    )
    package_name = normalize_whitespace(
        str(previous_conversation_state.get("pending_package_name") or previous_conversation_state.get("package_name") or "")
    )
    decision = build_intent_decision(
        mode="build",
        task_id=task_id,
        existing_task=False,
        existing_workspace_ready=False,
        user_prompt=submitted_prompt,
        effective_user_prompt=submitted_prompt,
        reason="사용자가 확인한 최종 앱 생성 프롬프트를 그대로 사용해 빌드를 시작합니다.",
        request_scope="new_app",
        suggested_app_name=app_name,
        primary_user_flow=normalize_whitespace(
            str(
                previous_conversation_state.get("pending_primary_user_flow")
                or previous_conversation_state.get("latest_primary_user_flow")
                or ""
            )
        ),
        secondary_requirements=normalize_secondary_requirements(
            previous_conversation_state.get("pending_secondary_requirements")
            or previous_conversation_state.get("latest_secondary_requirements")
        ),
        secondary_scope_confirmed=bool(
            previous_conversation_state.get("pending_secondary_scope_confirmed")
            or previous_conversation_state.get("latest_secondary_scope_confirmed")
        ),
        acceptance_criteria=normalize_acceptance_criteria(
            previous_conversation_state.get("pending_acceptance_criteria")
            or previous_conversation_state.get("latest_acceptance_criteria")
        ),
        target_users=normalize_target_users(
            previous_conversation_state.get("pending_target_users")
            or previous_conversation_state.get("latest_target_users")
        ),
        key_screens=normalize_prompt_items(
            previous_conversation_state.get("pending_key_screens")
            or previous_conversation_state.get("latest_key_screens"),
            max_items=6,
        ),
        storage_mode=str(
            previous_conversation_state.get("pending_storage_mode")
            or previous_conversation_state.get("latest_storage_mode")
            or "unspecified"
        ),
        stored_data=normalize_prompt_items(
            previous_conversation_state.get("pending_stored_data")
            or previous_conversation_state.get("latest_stored_data"),
            max_items=6,
        ),
        image_reference_summary=normalize_whitespace(str(previous_conversation_state.get("image_reference_summary") or "")),
        image_conflict_note=normalize_whitespace(str(previous_conversation_state.get("image_conflict_note") or "")),
    )
    if package_name:
        decision = replace(decision, package_name=package_name)
    return replace(
        decision,
        effective_user_prompt=submitted_prompt,
        prepared_prompt=str(previous_conversation_state.get("prepared_prompt") or "").strip(),
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
    request_started_at = time.monotonic()

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
        "raw_response": {
            **response_payload,
            "server_elapsed_seconds": round(time.monotonic() - request_started_at, 3),
        },
        "usage": parse_response_usage_payload(response_payload),
    }
    return enriched_result


def build_agent_conversation_history(db: Optional["Database"], task_id: str) -> list[dict[str, Any]]:
    if db is None or not normalize_whitespace(task_id):
        return []
    history: list[dict[str, Any]] = []
    for event in db.list_events(task_id):
        actor = normalize_whitespace(str(event.get("actor") or ""))
        event_type = normalize_whitespace(str(event.get("event_type") or ""))
        if actor == "user" and event_type != "user_message":
            continue
        if actor == "assistant" and event_type != "assistant_message":
            continue
        if actor not in {"user", "assistant"}:
            continue
        message = str(event.get("message_text") or "").strip()
        attachment_names: list[str] = []
        raw_payload = event.get("payload_json")
        if raw_payload:
            try:
                payload = json.loads(str(raw_payload))
            except (TypeError, json.JSONDecodeError):
                payload = {}
            if isinstance(payload, dict):
                attachments = payload.get("attachments")
                if isinstance(attachments, list):
                    attachment_names = normalize_prompt_items(
                        [
                            item.get("name") or item.get("original_name")
                            for item in attachments
                            if isinstance(item, dict)
                        ],
                        max_items=20,
                    )
        if not message and not attachment_names:
            continue
        entry = {
            "role": actor,
            "content": message,
            "attachment_names": attachment_names,
            "created_at": str(event.get("created_at") or ""),
        }
        if history and all(
            history[-1].get(key) == entry.get(key)
            for key in ("role", "content", "attachment_names")
        ):
            continue
        history.append(entry)
    return history


def contains_conversation_placeholder(value: Any) -> bool:
    normalized = normalize_whitespace(str(value or ""))
    if not normalized:
        return False
    patterns = (
        r"이전\s*대화(?:\s*맥락)?",
        r"앞(?:선|서)\s*(?:대화|내용|요청)",
        r"위\s*(?:내용|요청|대화)(?:과|을|를|대로)?",
        r"기존\s*(?:요청|대화)\s*(?:을|를)?\s*그대로",
        r"(?:그|이)\s*내용대로",
        r"앞에서\s*정한",
    )
    return any(re.search(pattern, normalized) for pattern in patterns)


def spec_payload_needs_standalone_rewrite(
    payload: Optional[dict[str, Any]],
    conversation_history: list[dict[str, Any]],
) -> bool:
    if not payload or str(payload.get("mode") or "") not in {"build", "ask_confirmation"}:
        return False
    values: list[Any] = [
        payload.get("effective_user_prompt"),
        payload.get("primary_user_flow"),
        *(payload.get("key_screens") or []),
        *(payload.get("core_features") or []),
        *(payload.get("acceptance_criteria") or []),
    ]
    if any(contains_conversation_placeholder(value) for value in values):
        return True
    effective_prompt = normalize_whitespace(str(payload.get("effective_user_prompt") or ""))
    substantive_prior_messages = [
        entry
        for entry in conversation_history[:-1]
        if len(normalize_whitespace(str(entry.get("content") or ""))) >= 20
    ]
    return bool(substantive_prior_messages and len(effective_prompt) < 40)


def materialize_conversation_spec_payload(
    payload: dict[str, Any],
    conversation_history: list[dict[str, Any]],
    latest_prompt: str,
) -> dict[str, Any]:
    concrete_entries = [
        entry
        for entry in conversation_history
        if normalize_whitespace(str(entry.get("content") or ""))
    ]
    if not concrete_entries:
        return payload
    transcript_lines = ["생성 전 대화에서 확인된 앱 요구사항은 다음과 같다."]
    for entry in concrete_entries:
        role = "참가자" if entry.get("role") == "user" else "생성 전 안내"
        content = normalize_whitespace(str(entry.get("content") or ""))
        attachment_names = normalize_prompt_items(entry.get("attachment_names"), max_items=20)
        attachment_suffix = f" (첨부: {', '.join(attachment_names)})" if attachment_names else ""
        transcript_lines.append(f"- {role}: {content}{attachment_suffix}")
    standalone_prompt = "\n".join(transcript_lines)
    participant_text = "\n".join(
        normalize_whitespace(str(entry.get("content") or ""))
        for entry in concrete_entries
        if entry.get("role") == "user" and not looks_like_generic_confirmation(str(entry.get("content") or ""))
    ).strip() or normalize_whitespace(latest_prompt)
    feature_points = extract_feature_points(participant_text)
    app_name = normalize_whitespace(str(payload.get("app_name") or ""))
    if not app_name or app_name in {"새앱", "새 앱", "요청 앱", "맞춤 앱", "앱"}:
        app_name = infer_app_name(participant_text)
    primary_user_flow = normalize_whitespace(str(payload.get("primary_user_flow") or ""))
    if contains_conversation_placeholder(primary_user_flow):
        primary_user_flow = infer_primary_user_flow(participant_text, feature_points, app_name)
    storage_mode, stored_data = infer_storage_details(participant_text)
    materialized = {
        **payload,
        "app_name": app_name,
        "effective_user_prompt": standalone_prompt,
        "primary_user_flow": primary_user_flow,
        "target_users": normalize_target_users(payload.get("target_users"))
        or infer_target_users(participant_text),
        "key_screens": normalize_prompt_items(payload.get("key_screens"), max_items=6),
        "core_features": normalize_prompt_items(payload.get("core_features"), max_items=8),
        "storage_mode": normalize_storage_mode(payload.get("storage_mode")),
        "stored_data": normalize_prompt_items(payload.get("stored_data"), max_items=6),
    }
    if not materialized["key_screens"] or any(
        contains_conversation_placeholder(item) for item in materialized["key_screens"]
    ):
        materialized["key_screens"] = infer_key_screens(participant_text, feature_points)
    if not materialized["core_features"] or any(
        contains_conversation_placeholder(item) for item in materialized["core_features"]
    ):
        materialized["core_features"] = feature_points[:8]
    if materialized["storage_mode"] == "unspecified":
        materialized["storage_mode"] = storage_mode
    if not materialized["stored_data"]:
        materialized["stored_data"] = stored_data
    criteria = normalize_acceptance_criteria(payload.get("acceptance_criteria"))
    if not criteria or any(contains_conversation_placeholder(item) for item in criteria):
        materialized["acceptance_criteria"] = infer_acceptance_criteria(participant_text, feature_points)
    return materialized


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
    conversation_history: Optional[list[dict[str, Any]]] = None,
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
        "conversation_history": conversation_history or [],
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
            "target_users",
            "key_screens",
            "core_features",
            "secondary_requirements",
            "secondary_scope_confirmed",
            "storage_mode",
            "stored_data",
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
            "target_users": {
                "type": "array",
                "items": {"type": "string"},
                "maxItems": 5,
            },
            "key_screens": {
                "type": "array",
                "items": {"type": "string"},
                "maxItems": 6,
            },
            "core_features": {
                "type": "array",
                "items": {"type": "string"},
                "maxItems": 8,
            },
            "secondary_requirements": {
                "type": "array",
                "items": {"type": "string"},
                "maxItems": 5,
            },
            "secondary_scope_confirmed": {"type": "boolean"},
            "storage_mode": {
                "type": "string",
                "enum": ["none", "local", "server", "unspecified"],
            },
            "stored_data": {
                "type": "array",
                "items": {"type": "string"},
                "maxItems": 6,
            },
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
                "maxItems": 5,
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
- For answer_question about whether a proposed new app is feasible or how it could be implemented, set request_scope=new_app so later messages continue in the same pre-build conversation.
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
- For build or ask_confirmation, effective_user_prompt must be a standalone, concrete app specification that can be understood without conversation_history or previous_conversation_state.
- When the latest message says "그렇게 해줘", "그대로 진행해줘", or otherwise refers back to the conversation, reconstruct the actual agreed app behavior from conversation_history and write those details out explicitly.
- Never write placeholders such as "이전 대화 맥락의 요청", "위 내용대로", "기존 요청을 그대로 사용", or "앞서 정한 기능" in effective_user_prompt, primary_user_flow, key_screens, core_features, or acceptance_criteria.
- Preserve every concrete requirement the participant provided. If an earlier assistant explained a technical limitation and proposed a feasible alternative, include only the alternative the participant clearly accepted. Ask one concrete choice question if multiple alternatives remain ambiguous.
- Fill target_users with specific user roles only when they were stated or are strongly implied by the requested workflow, such as 원장, 수강생, 학부모, 의사, or 환자.
- Do not put generic audiences such as "일반 Android 스마트폰 사용자", "일반 사용자", or "모든 사용자" in target_users. Leave target_users empty when no meaningful audience is known.
- Fill key_screens with 1-6 concrete user-facing screens or tabs needed by this app. Do not copy whole request sentences into this field.
- Fill core_features with 1-8 concrete behaviors that must work in the first version. Each item must describe one observable feature.
- For build or ask_confirmation, always fill secondary_requirements with 0-5 enhancement items that are nice-to-have, second-phase, or optional polish beyond the first-release core flow.
- For a new app request, do not use mode=build until both of these are decided:
  1. primary_user_flow is concrete enough,
  2. secondary_scope_confirmed is true and secondary_requirements are either explicitly listed or explicitly confirmed as none for now.
- If the user's message does not clearly separate first-release core flow from second-phase enhancements, use mode=ask_confirmation and propose 2-5 short feature questions yourself.
- Do not ask the user to write or organize 1차 핵심 흐름 and 2차 고도화 요구 from scratch.
- This build system does not provide a backend database, account system, cloud storage, or multi-device sync for each generated app by default.
- Do not ask whether login, account creation, server storage, cloud sync, or multi-device sync is needed unless the user explicitly requested login, accounts, sharing across users, teams, cloud sync, or multi-device use.
- If persistent storage is implied or requested, assume local on-device persistence by default.
- Do not ask where data should be stored. Only ask what user-visible data or actions must be saved when that materially changes the app.
- Set storage_mode=none when the app has no information that must survive an app restart.
- Set storage_mode=local when one device should retain records, settings, favorites, history, or user-created content.
- Set storage_mode=server only when the request explicitly needs sharing between users/devices, accounts, collaboration, or synchronization.
- Set storage_mode=unspecified only for answer_question. For build or ask_confirmation, choose none, local, or server.
- Fill stored_data with the actual records that must persist, such as 일정, 출석 기록, 메모, 즐겨찾기, or 사용자별 진도. Leave it empty when storage_mode=none.
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
- If you ask clarification questions for a new app, prefer 1-5 short Korean feature questions with concrete options.
- Avoid wording like "적어주세요", "알려주세요", "나눠서 답해 주세요", or other open-ended authoring requests when short option questions would work.
- For build or ask_confirmation, do not leave acceptance_criteria empty.
- For answer_question, acceptance_criteria must be an empty array.
- For answer_question, secondary_scope_confirmed must be false.
- For answer_question, target_users, key_screens, core_features, and stored_data must be empty arrays, and storage_mode must be unspecified.

Return JSON only.
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

    result = run_openai_structured_agent(
        settings,
        schema=schema,
        schema_name="spec_clarification_decision",
        instructions=agent_prompt,
        user_content=user_content,
    )
    normalized_history = conversation_history or []
    if not spec_payload_needs_standalone_rewrite(result, normalized_history):
        return result

    previous_result = {
        key: value
        for key, value in (result or {}).items()
        if key != "__agent_meta"
    }
    repair_instructions = f"""{agent_prompt}

The previous result below was rejected because it referred to prior conversation instead of writing the agreed requirements explicitly.
Rewrite it as a standalone specification. Expand all references such as "그렇게", "이전 대화", or "위 내용" into concrete screens, features, users, storage behavior, and acceptance criteria from conversation_history.
If the conversation does not establish one implementable interpretation, return ask_confirmation with one concrete choice question instead of inventing a placeholder.

Rejected result:
{json.dumps(previous_result, ensure_ascii=False, indent=2)}
"""
    repaired = run_openai_structured_agent(
        settings,
        schema=schema,
        schema_name="spec_clarification_decision_repair",
        instructions=repair_instructions,
        user_content=user_content,
    )
    if repaired and not spec_payload_needs_standalone_rewrite(repaired, normalized_history):
        return repaired
    fallback_source = repaired or result
    if not fallback_source:
        return None
    fallback_meta = fallback_source.get("__agent_meta")
    materialized = materialize_conversation_spec_payload(
        {key: value for key, value in fallback_source.items() if key != "__agent_meta"},
        normalized_history,
        prompt,
    )
    if fallback_meta:
        materialized["__agent_meta"] = fallback_meta
    return materialized


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
        conversation_history = build_agent_conversation_history(db, task_id)
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
            conversation_history=conversation_history,
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
            spec_target_users = normalize_target_users(spec_payload.get("target_users"))
            spec_key_screens = normalize_prompt_items(spec_payload.get("key_screens"), max_items=6)
            spec_core_features = normalize_prompt_items(spec_payload.get("core_features"), max_items=8)
            spec_secondary_requirements = normalize_secondary_requirements(spec_payload.get("secondary_requirements"))
            spec_secondary_scope_confirmed = bool(spec_payload.get("secondary_scope_confirmed"))
            spec_storage_mode = normalize_storage_mode(spec_payload.get("storage_mode"))
            spec_stored_data = normalize_prompt_items(spec_payload.get("stored_data"), max_items=6)
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
                        core_features=spec_core_features,
                        target_users=spec_target_users,
                        key_screens=spec_key_screens,
                        storage_mode=spec_storage_mode,
                        stored_data=spec_stored_data,
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
                        core_features=spec_core_features,
                        target_users=spec_target_users,
                        key_screens=spec_key_screens,
                        storage_mode=spec_storage_mode,
                        stored_data=spec_stored_data,
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
                        core_features=spec_core_features,
                        target_users=spec_target_users,
                        key_screens=spec_key_screens,
                        storage_mode=spec_storage_mode,
                        stored_data=spec_stored_data,
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
                        core_features=spec_core_features,
                        target_users=spec_target_users,
                        key_screens=spec_key_screens,
                        storage_mode=spec_storage_mode,
                        stored_data=spec_stored_data,
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
                    core_features=spec_core_features,
                    target_users=spec_target_users,
                    key_screens=spec_key_screens,
                    storage_mode=spec_storage_mode,
                    stored_data=spec_stored_data,
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


class GenerateAttachmentPayload(BaseModel):
    type: str = ""
    mime_type: str = ""
    name: str = ""
    base64: str = ""


class GenerateRequest(BaseModel):
    task_id: Optional[str] = None
    device_id: str = Field(..., min_length=1)
    phone_number: Optional[str] = None
    prompt: str = ""
    display_prompt: Optional[str] = None
    request_action: Optional[str] = None
    device_info: Optional[DeviceInfoPayload] = None
    reference_image_path: Optional[str] = None
    reference_image_name: Optional[str] = None
    reference_image_base64: Optional[str] = None
    attachments: list[GenerateAttachmentPayload] = Field(default_factory=list)


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
    max_output_tokens: int = Field(default=0, ge=0)
    temperature: float = Field(default=0.4, ge=0.0, le=2.0)


class GlobalAppLlmDefaultsRequest(AppLlmConfigRequest):
    apply_to_existing_tasks: bool = True


class AppLlmRuntimeRequest(BaseModel):
    package_name: str = Field(..., min_length=1)
    user_message: str = Field(..., min_length=1)
    context: Optional[str] = None
    image_base64: Optional[str] = None
    image_mime_type: Optional[str] = None


class AppDataCreateRequest(BaseModel):
    package_name: str = Field(..., min_length=1)
    owner_id: Optional[str] = None
    data: dict[str, Any] = Field(default_factory=dict)


class AppDataUpdateRequest(BaseModel):
    package_name: str = Field(..., min_length=1)
    owner_id: Optional[str] = None
    data: dict[str, Any] = Field(default_factory=dict)
    replace: bool = False


class RuntimeErrorReportRequest(BaseModel):
    package_name: str = Field(..., min_length=1)
    summary: str = Field(..., min_length=1)
    stack_trace: str = Field(..., min_length=1)
    error_message: Optional[str] = None
    report_kind: Optional[str] = None


APP_DATA_COLLECTION_RE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9_.-]{0,63}$")


def normalize_app_data_collection(collection: str) -> str:
    value = collection.strip()
    if not APP_DATA_COLLECTION_RE.fullmatch(value):
        raise HTTPException(status_code=400, detail="invalid collection")
    return value


def normalize_app_data_owner_id(owner_id: Optional[str]) -> str:
    return normalize_whitespace(owner_id or "")[:120]


def decode_app_data_json(value: str) -> dict[str, Any]:
    try:
        parsed = json.loads(value or "{}")
    except json.JSONDecodeError:
        return {}
    return parsed if isinstance(parsed, dict) else {}


class AppDataDatabase:
    def __init__(self, db_path: Path) -> None:
        self.db_path = db_path

    def connect(self) -> sqlite3.Connection:
        connection = sqlite3.connect(self.db_path, timeout=30, check_same_thread=False)
        connection.row_factory = sqlite3.Row
        return connection

    def init_db(self) -> None:
        self.db_path.parent.mkdir(parents=True, exist_ok=True)
        with self.connect() as connection:
            connection.execute("PRAGMA journal_mode=WAL")
            connection.execute(
                """
                CREATE TABLE IF NOT EXISTS app_data_records (
                    record_id TEXT PRIMARY KEY,
                    task_id TEXT NOT NULL,
                    package_name TEXT NOT NULL,
                    collection TEXT NOT NULL,
                    owner_id TEXT,
                    data_json TEXT NOT NULL,
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL,
                    deleted_at TEXT
                )
                """
            )
            connection.execute(
                """
                CREATE INDEX IF NOT EXISTS idx_app_data_records_task_collection_updated
                ON app_data_records(task_id, collection, deleted_at, updated_at)
                """
            )
            connection.execute(
                """
                CREATE INDEX IF NOT EXISTS idx_app_data_records_owner
                ON app_data_records(task_id, collection, owner_id, deleted_at)
                """
            )
            connection.commit()

    def serialize_record(self, row: sqlite3.Row) -> dict[str, Any]:
        return {
            "record_id": row["record_id"],
            "task_id": row["task_id"],
            "package_name": row["package_name"],
            "collection": row["collection"],
            "owner_id": row["owner_id"] or "",
            "data": decode_app_data_json(str(row["data_json"] or "{}")),
            "created_at": row["created_at"],
            "updated_at": row["updated_at"],
            "deleted_at": row["deleted_at"],
        }

    def create_record(
        self,
        *,
        task_id: str,
        package_name: str,
        collection: str,
        owner_id: str,
        data: dict[str, Any],
    ) -> dict[str, Any]:
        now = utc_now_iso()
        record_id = uuid.uuid4().hex
        with self.connect() as connection:
            connection.execute(
                """
                INSERT INTO app_data_records (
                    record_id, task_id, package_name, collection, owner_id,
                    data_json, created_at, updated_at, deleted_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, NULL)
                """,
                (
                    record_id,
                    task_id,
                    package_name,
                    collection,
                    owner_id or None,
                    json.dumps(data, ensure_ascii=False),
                    now,
                    now,
                ),
            )
            connection.commit()
            row = connection.execute(
                """
                SELECT record_id, task_id, package_name, collection, owner_id,
                       data_json, created_at, updated_at, deleted_at
                FROM app_data_records
                WHERE record_id = ?
                """,
                (record_id,),
            ).fetchone()
        return self.serialize_record(row)

    def list_records(
        self,
        *,
        task_id: str,
        package_name: str,
        collection: str,
        owner_id: str,
        include_deleted: bool,
        limit: int,
    ) -> list[dict[str, Any]]:
        clauses = ["task_id = ?", "package_name = ?", "collection = ?"]
        params: list[Any] = [task_id, package_name, collection]
        if owner_id:
            clauses.append("owner_id = ?")
            params.append(owner_id)
        if not include_deleted:
            clauses.append("deleted_at IS NULL")
        params.append(limit)
        with self.connect() as connection:
            rows = connection.execute(
                f"""
                SELECT record_id, task_id, package_name, collection, owner_id,
                       data_json, created_at, updated_at, deleted_at
                FROM app_data_records
                WHERE {" AND ".join(clauses)}
                ORDER BY updated_at DESC, created_at DESC
                LIMIT ?
                """,
                params,
            ).fetchall()
        return [self.serialize_record(row) for row in rows]

    def get_record(
        self,
        *,
        task_id: str,
        package_name: str,
        collection: str,
        record_id: str,
        include_deleted: bool = False,
    ) -> Optional[dict[str, Any]]:
        clauses = [
            "record_id = ?",
            "task_id = ?",
            "package_name = ?",
            "collection = ?",
        ]
        params: list[Any] = [record_id, task_id, package_name, collection]
        if not include_deleted:
            clauses.append("deleted_at IS NULL")
        with self.connect() as connection:
            row = connection.execute(
                f"""
                SELECT record_id, task_id, package_name, collection, owner_id,
                       data_json, created_at, updated_at, deleted_at
                FROM app_data_records
                WHERE {" AND ".join(clauses)}
                """,
                params,
            ).fetchone()
        return self.serialize_record(row) if row else None

    def update_record(
        self,
        *,
        task_id: str,
        package_name: str,
        collection: str,
        record_id: str,
        owner_id: str,
        data: dict[str, Any],
        replace: bool,
    ) -> Optional[dict[str, Any]]:
        existing = self.get_record(
            task_id=task_id,
            package_name=package_name,
            collection=collection,
            record_id=record_id,
        )
        if not existing:
            return None
        merged_data = data if replace else {**existing["data"], **data}
        resolved_owner_id = owner_id or str(existing.get("owner_id") or "")
        now = utc_now_iso()
        with self.connect() as connection:
            connection.execute(
                """
                UPDATE app_data_records
                SET owner_id = ?, data_json = ?, updated_at = ?
                WHERE record_id = ? AND task_id = ? AND package_name = ? AND collection = ?
                  AND deleted_at IS NULL
                """,
                (
                    resolved_owner_id or None,
                    json.dumps(merged_data, ensure_ascii=False),
                    now,
                    record_id,
                    task_id,
                    package_name,
                    collection,
                ),
            )
            connection.commit()
        return self.get_record(
            task_id=task_id,
            package_name=package_name,
            collection=collection,
            record_id=record_id,
        )

    def delete_record(
        self,
        *,
        task_id: str,
        package_name: str,
        collection: str,
        record_id: str,
    ) -> bool:
        now = utc_now_iso()
        with self.connect() as connection:
            cursor = connection.execute(
                """
                UPDATE app_data_records
                SET deleted_at = ?, updated_at = ?
                WHERE record_id = ? AND task_id = ? AND package_name = ? AND collection = ?
                  AND deleted_at IS NULL
                """,
                (now, now, record_id, task_id, package_name, collection),
            )
            connection.commit()
        return cursor.rowcount > 0


class Database:
    def __init__(self, db_path: Path) -> None:
        self.db_path = db_path

    def connect(self) -> sqlite3.Connection:
        connection = sqlite3.connect(self.db_path, timeout=30, check_same_thread=False)
        connection.row_factory = sqlite3.Row
        connection.execute("PRAGMA busy_timeout=30000")
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
            connection.execute("PRAGMA journal_mode=WAL")
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
                    normalized_prompt TEXT,
                    build_request_prompt TEXT,
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
            self.ensure_column(connection, "tasks", "normalized_prompt", "TEXT")
            self.ensure_column(connection, "tasks", "build_request_prompt", "TEXT")
            connection.execute("CREATE INDEX IF NOT EXISTS idx_tasks_user_id ON tasks(user_id)")
            connection.execute(
                "CREATE INDEX IF NOT EXISTS idx_tasks_user_id_created_at ON tasks(user_id, created_at DESC)"
            )
            connection.execute(
                "CREATE INDEX IF NOT EXISTS idx_tasks_device_id_created_at ON tasks(device_id, created_at DESC)"
            )
            connection.execute(
                "CREATE INDEX IF NOT EXISTS idx_tasks_phone_number_created_at ON tasks(phone_number, created_at DESC)"
            )
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
                CREATE TABLE IF NOT EXISTS task_attachments (
                    attachment_id TEXT PRIMARY KEY,
                    task_id TEXT NOT NULL,
                    event_id TEXT,
                    source TEXT NOT NULL,
                    kind TEXT NOT NULL,
                    original_name TEXT,
                    mime_type TEXT,
                    workspace_path TEXT,
                    absolute_path TEXT,
                    size_bytes INTEGER,
                    sha256 TEXT,
                    status TEXT NOT NULL,
                    error_message TEXT,
                    created_at TEXT NOT NULL
                )
                """
            )
            connection.execute(
                "CREATE INDEX IF NOT EXISTS idx_task_attachments_task_id_created_at ON task_attachments(task_id, created_at)"
            )
            connection.execute(
                "CREATE INDEX IF NOT EXISTS idx_task_attachments_sha256 ON task_attachments(sha256)"
            )
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
                    request_summary TEXT,
                    created_at TEXT NOT NULL
                )
                """
            )
            self.ensure_column(connection, "task_project_snapshots", "request_summary", "TEXT")
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
                    normalized_prompt, build_request_prompt,
                    input_tokens, cached_input_tokens, output_tokens, reasoning_output_tokens, total_tokens,
                    codex_result_json, log, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
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
                    task.get("normalized_prompt"),
                    task.get("build_request_prompt"),
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

    def update_task_if_status(self, task_id: str, allowed_statuses: set[str], **fields: Any) -> bool:
        if not fields or not allowed_statuses:
            return False
        fields["updated_at"] = utc_now_iso()
        assignments = ", ".join(f"{key} = ?" for key in fields)
        status_values = [status.strip().lower() for status in allowed_statuses if status.strip()]
        placeholders = ", ".join("?" for _ in status_values)
        values = list(fields.values()) + [task_id] + status_values
        with self.connect() as connection:
            cursor = connection.execute(
                f"UPDATE tasks SET {assignments} WHERE task_id = ? AND lower(status) IN ({placeholders})",
                values,
            )
            connection.commit()
            return cursor.rowcount > 0

    def log_event(
        self,
        task_id: str,
        *,
        actor: str,
        event_type: str,
        message_text: str = "",
        payload: Optional[dict[str, Any]] = None,
    ) -> str:
        created_at = utc_now_iso()
        event_id = uuid.uuid4().hex
        payload_json = json.dumps(payload, ensure_ascii=False) if payload is not None else None
        with self.connect() as connection:
            connection.execute(
                """
                INSERT INTO task_events (
                    event_id, task_id, actor, event_type, message_text, payload_json, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                (
                    event_id,
                    task_id,
                    actor,
                    event_type,
                    message_text,
                    payload_json,
                    created_at,
                ),
            )
            connection.commit()
        return event_id

    def record_task_attachment(
        self,
        *,
        task_id: str,
        event_id: Optional[str],
        source: str,
        kind: str,
        original_name: str,
        mime_type: str,
        workspace_path: str,
        absolute_path: str,
        size_bytes: Optional[int],
        sha256: str,
        status: str,
        error_message: str = "",
    ) -> str:
        attachment_id = uuid.uuid4().hex
        with self.connect() as connection:
            connection.execute(
                """
                INSERT INTO task_attachments (
                    attachment_id, task_id, event_id, source, kind, original_name, mime_type,
                    workspace_path, absolute_path, size_bytes, sha256, status, error_message, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                (
                    attachment_id,
                    task_id,
                    event_id,
                    source,
                    kind,
                    original_name,
                    mime_type,
                    workspace_path,
                    absolute_path,
                    size_bytes,
                    sha256,
                    status,
                    error_message,
                    utc_now_iso(),
                ),
            )
            connection.commit()
        return attachment_id

    def list_task_attachments(self, task_id: str) -> list[dict[str, Any]]:
        with self.connect() as connection:
            rows = connection.execute(
                """
                SELECT attachment_id, task_id, event_id, source, kind, original_name, mime_type,
                       workspace_path, absolute_path, size_bytes, sha256, status, error_message, created_at
                FROM task_attachments
                WHERE task_id = ?
                ORDER BY rowid ASC
                """,
                (task_id,),
            ).fetchall()
            return [dict(row) for row in rows]

    def get_task_attachment(self, task_id: str, attachment_id: str) -> Optional[dict[str, Any]]:
        with self.connect() as connection:
            row = connection.execute(
                """
                SELECT attachment_id, task_id, event_id, source, kind, original_name, mime_type,
                       workspace_path, absolute_path, size_bytes, sha256, status, error_message, created_at
                FROM task_attachments
                WHERE task_id = ? AND attachment_id = ?
                LIMIT 1
                """,
                (task_id, attachment_id),
            ).fetchone()
            return dict(row) if row else None

    def list_events(
        self,
        task_id: str,
        *,
        limit: Optional[int] = None,
        after_event_id: Optional[str] = None,
    ) -> list[dict[str, Any]]:
        with self.connect() as connection:
            normalized_after_event_id = normalize_whitespace(after_event_id or "")
            after_rowid: Optional[int] = None
            if normalized_after_event_id:
                cursor_row = connection.execute(
                    """
                    SELECT rowid
                    FROM task_events
                    WHERE task_id = ? AND event_id = ?
                    LIMIT 1
                    """,
                    (task_id, normalized_after_event_id),
                ).fetchone()
                if cursor_row is not None:
                    after_rowid = int(cursor_row["rowid"])

            if after_rowid is not None:
                limit_clause = "LIMIT ?" if limit is not None and limit > 0 else ""
                values: list[Any] = [task_id, after_rowid]
                if limit_clause:
                    values.append(limit)
                rows = connection.execute(
                    f"""
                    SELECT event_id, task_id, actor, event_type, message_text, payload_json, created_at
                    FROM task_events
                    WHERE task_id = ? AND rowid > ?
                    ORDER BY rowid ASC
                    {limit_clause}
                    """,
                    values,
                ).fetchall()
            elif limit is not None and limit > 0:
                rows = connection.execute(
                    """
                    SELECT event_id, task_id, actor, event_type, message_text, payload_json, created_at
                    FROM (
                        SELECT rowid AS event_rowid, event_id, task_id, actor, event_type,
                               message_text, payload_json, created_at
                        FROM task_events
                        WHERE task_id = ?
                        ORDER BY rowid DESC
                        LIMIT ?
                    )
                    ORDER BY event_rowid ASC
                    """,
                    (task_id, limit),
                ).fetchall()
            else:
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

    def latest_event_id(self, task_id: str) -> str:
        with self.connect() as connection:
            row = connection.execute(
                """
                SELECT event_id
                FROM task_events
                WHERE task_id = ?
                ORDER BY rowid DESC
                LIMIT 1
                """,
                (task_id,),
            ).fetchone()
            return str(row["event_id"] or "") if row else ""

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
                    int(config.get("max_output_tokens") or 0),
                    float(config.get("temperature") or 0.4),
                    created_at,
                    now,
                ),
            )
            connection.commit()

    def get_app_llm_config(self, task_id: str) -> Optional[dict[str, Any]]:
        with self.connect() as connection:
            row = connection.execute("SELECT * FROM app_llm_configs WHERE task_id = ?", (task_id,)).fetchone()
            if not row:
                return None
            config = dict(row)
            config["max_output_tokens"] = 0
            return config

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
        request_summary: str = "",
    ) -> None:
        with self.connect() as connection:
            connection.execute(
                """
                INSERT INTO task_project_snapshots (
                    snapshot_id, task_id, revision_label, source, workspace_path, project_path,
                    request_summary, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                (
                    uuid.uuid4().hex,
                    task_id,
                    revision_label,
                    source,
                    workspace_path,
                    project_path,
                    compact_revision_request_summary(request_summary) if request_summary else "",
                    utc_now_iso(),
                ),
            )
            connection.commit()

    def list_project_snapshots(self, task_id: str) -> list[dict[str, Any]]:
        with self.connect() as connection:
            rows = connection.execute(
                """
                SELECT snapshot_id, task_id, revision_label, source, workspace_path, project_path,
                       request_summary, created_at
                FROM task_project_snapshots
                WHERE task_id = ?
                ORDER BY rowid ASC
                """,
                (task_id,),
            ).fetchall()
            return [dict(row) for row in rows]

    def get_project_snapshot(self, task_id: str, revision_label: str) -> Optional[dict[str, Any]]:
        with self.connect() as connection:
            row = connection.execute(
                """
                SELECT snapshot_id, task_id, revision_label, source, workspace_path, project_path,
                       request_summary, created_at
                FROM task_project_snapshots
                WHERE task_id = ? AND revision_label = ?
                ORDER BY rowid DESC
                LIMIT 1
                """,
                (task_id, revision_label),
            ).fetchone()
            return dict(row) if row else None

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
                       codex_result_json, created_at, updated_at,
                       (
                           SELECT MAX(event.created_at)
                           FROM task_events AS event
                           WHERE event.task_id = tasks.task_id
                             AND event.event_type IN (
                                 'user_message',
                                 'assistant_message',
                                 'task_branched',
                                 'task_status',
                                 'task_succeeded',
                                 'task_failed',
                                 'task_error',
                                 'task_timeout',
                                 'task_cancelled',
                                 'user_interaction'
                             )
                       ) AS last_bubble_at
                FROM tasks
                WHERE {where_clause}
                ORDER BY created_at DESC
                """,
                values,
            ).fetchall()
            return [dict(row) for row in rows]


def task_workspace_root_for(settings: Settings, task: dict[str, Any]) -> Path:
    safe_user_id = sanitize_component(str(task["user_id"]))
    safe_task_id = sanitize_component(str(task["task_id"]))
    return settings.workspaces_root / f"user_{safe_user_id}" / f"task_{safe_task_id}"


def persist_reference_attachments_for_task(
    db: Database,
    settings: Settings,
    task: dict[str, Any],
    attachments: list[dict[str, str]],
    *,
    source: str,
    event_id: Optional[str] = None,
    fail_on_error: bool = True,
) -> list[dict[str, str]]:
    normalized = normalize_reference_attachments(attachments)
    if not normalized:
        return []

    task_root = task_workspace_root_for(settings, task)
    saved_attachments = save_reference_attachments(task_root, normalized)
    first_attachment = first_reference_attachment(saved_attachments)
    first_workspace_path = normalize_whitespace(str(first_attachment.get("workspace_path") or ""))
    if saved_attachments:
        task["reference_attachments"] = saved_attachments
    if first_workspace_path:
        task["reference_image_workspace_path"] = first_workspace_path

    failed_messages: list[str] = []
    for attachment in saved_attachments:
        payload = reference_attachment_event_payload(attachment)
        status = str(payload.get("status") or "unknown")
        error_message = str(payload.get("error_message") or "")
        attachment_id = db.record_task_attachment(
            task_id=str(task["task_id"]),
            event_id=event_id,
            source=source,
            kind="image",
            original_name=str(payload.get("name") or "reference_image"),
            mime_type=str(payload.get("mime_type") or ""),
            workspace_path=str(payload.get("workspace_path") or ""),
            absolute_path=str(payload.get("absolute_path") or ""),
            size_bytes=int(payload.get("size_bytes") or 0) or None,
            sha256=str(payload.get("sha256") or ""),
            status=status,
            error_message=error_message,
        )
        payload = {
            **payload,
            "attachment_id": attachment_id,
            "source": source,
            "linked_event_id": event_id or "",
        }
        if status in {"saved", "existing"}:
            db.log_event(
                str(task["task_id"]),
                actor="system",
                event_type="attachment_saved",
                message_text=f"이미지 첨부 저장됨: {payload.get('name') or 'reference_image'}",
                payload=payload,
            )
        elif status == "pending":
            db.log_event(
                str(task["task_id"]),
                actor="system",
                event_type="attachment_received",
                message_text=f"이미지 첨부 정보 수신됨: {payload.get('name') or 'reference_image'}",
                payload=payload,
            )
        else:
            failed_messages.append(error_message or f"failed to save {payload.get('name') or 'reference_image'}")
            db.log_event(
                str(task["task_id"]),
                actor="system",
                event_type="attachment_save_failed",
                message_text=f"이미지 첨부 저장 실패: {payload.get('name') or 'reference_image'}",
                payload=payload,
            )

    if fail_on_error and failed_messages:
        raise ValueError("; ".join(failed_messages))
    return saved_attachments


def render_task_agents_md(task_id: str) -> str:
    return f"""# Task Workspace Instructions

- Flutter Android 앱만 빌드한다.
- iOS/Xcode는 사용하지 않는다.
- 사용자의 명세를 반영해 `project` 폴더의 Flutter 앱을 수정한다.
- 가급적 `project/lib/main.dart`, `project/pubspec.yaml`, `project/android/app/` 아래만 집중해서 수정한다.
- `prompt.md`에 지정된 Task ID, 앱 이름, Android package name, `applicationId`, `namespace`, Kotlin package 선언과 `MainActivity` 경로는 서버가 관리하는 불변 식별자다. 새 앱 생성과 모든 수정에서 이 값을 바꾸거나 새 패키지를 만들지 않는다.
- 런타임 API의 `taskId`, `packageName`, endpoint 경로도 `prompt.md`의 값과 정확히 일치시킨다. `task_result.json`에는 실제 프로젝트와 동일한 `task_id` 및 `package_name`을 기록한다.
- 기존 Android release debug signing 설정과 `CrashHandler.initialize(...)`의 Task ID 및 package name을 유지한다.
- 코드 생성 중에는 `flutter pub get`과 `flutter analyze`로 의존성과 정적 오류를 검증한다.
- 최종 APK 빌드는 Task 식별자 보정 뒤 서버가 한 번만 수행하므로 `flutter build apk`를 직접 실행하지 않는다.
- `task_result.json`의 `apk_path`에는 서버가 생성할 예상 release APK 경로인 `project/build/app/outputs/flutter-apk/app-release.apk`를 기록한다.
- 사용자가 요청한 핵심 기능을 더 쉬운 대체 구현으로 바꾸지 않는다.
- `prompt.md`에 적힌 `1차 핵심 흐름`을 이번 빌드의 최우선 범위로 본다.
- `2차 고도화 요구`는 1차가 안정적으로 성립한 뒤에 반영한다. 시간이 부족하거나 충돌하면 1차를 우선하고, 못 넣은 2차 요구는 `known_limitations`에 남긴다.
- 예를 들어 카메라 요구를 수동 텍스트 입력으로, OCR 요구를 붙여넣기 전용 흐름으로, AI/외부정보 조회를 하드코딩 샘플 데이터로, 저장 기능을 메모리 리스트만으로 대체하면 안 된다.
- 핵심 기능이 실제로 동작하지 않으면 성공으로 보고하지 않는다.
- 실제 런타임 호출이 필요한 기능은 localhost나 `127.0.0.1` 같은 단말 내부 주소를 쓰지 말고 `prompt.md`에 적힌 서버 endpoint를 사용한다.
- 런타임 AI 호출이 필요한 앱은 `runtime_package_name`과 실제 요청 package name이 일치해야 한다.
- 사용자가 저장을 원했다면 앱 재실행 후에도 유지되는 저장 방식을 사용한다.
- 여러 사용자나 여러 기기가 같은 데이터를 봐야 하는 공유형 앱은 `prompt.md`의 서버 데이터 API를 사용한다.
- 단일 사용자 로컬 저장만 필요한 앱은 서버 데이터 API를 쓰지 말고 기기 내부 저장을 우선한다.
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
  "apk_path": "project/build/app/outputs/flutter-apk/app-release.apk",
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


def build_app_data_runtime_metadata(task: dict[str, Any], settings: Settings) -> dict[str, str]:
    package_name = str(task.get("package_name") or "").strip() or "(미정)"
    return {
        "data_available": "yes",
        "data_endpoint_base": f"{settings.server_base_url}/apps/{task['task_id']}/data",
        "package_name": package_name,
    }


def render_prompt_md(task: dict[str, Any], settings: Settings) -> str:
    phone_line = task.get("phone_number") or "(없음)"
    normalized_prompt = task.get("normalized_prompt") or task["prompt"]
    build_request_prompt = task.get("build_request_prompt") or task["prompt"]
    runtime_meta = build_app_runtime_metadata(task, settings)
    data_meta = build_app_data_runtime_metadata(task, settings)
    state_payload = load_task_state_payload(task)
    raw_conversation_state = state_payload.get("conversation_state")
    conversation_state: dict[str, Any] = (
        raw_conversation_state if isinstance(raw_conversation_state, dict) else {}
    )
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
    reference_attachments = normalize_reference_attachments(
        conversation_state.get("reference_attachments") or task.get("reference_attachments") or []
    )
    if not reference_attachments and reference_image_name:
        reference_attachments = [
            {
                "type": "image",
                "mime_type": f"image/{infer_reference_image_suffix(reference_image_name).lstrip('.')}",
                "name": reference_image_name,
                "base64": normalize_reference_image_base64(
                    conversation_state.get("reference_image_base64") or task.get("reference_image_base64")
                ),
                "workspace_path": reference_image_workspace_path,
            }
        ]
    secondary_section = "\n".join(f"- {item}" for item in secondary_requirements) if secondary_requirements else "- 없음 또는 미정"
    acceptance_section = "\n".join(f"- {criterion}" for criterion in acceptance_criteria) if acceptance_criteria else "- (없음)"
    if reference_attachments:
        reference_lines = []
        for index, attachment in enumerate(reference_attachments, start=1):
            reference_lines.append(
                f"- 이미지 {index}: {attachment.get('name') or 'reference_image'} "
                f"(workspace 경로: {attachment.get('workspace_path') or '(미저장)'})"
            )
        reference_image_section = "\n".join(reference_lines) + (
            "\n- 이 이미지들은 UI 스타일, 화면 구성, 참고 레이아웃, 대상 사물/장면, 텍스트 맥락을 해석하는 데 사용한다.\n"
            "- 사용자가 텍스트로 설명한 요구와 이미지가 함께 있으면 둘을 함께 반영하되, 충돌 시에는 사용자 텍스트 요청을 우선하고 차이를 명시한다."
        )
    else:
        reference_image_section = "- 첨부된 참고 이미지 없음"
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

## 서버 제공 생성앱 데이터 API

- data_available: {data_meta['data_available']}
- data_endpoint_base: {data_meta['data_endpoint_base']}
- data_package_name: {data_meta['package_name']}
- 공유형 앱, 여러 기기에서 같은 데이터를 봐야 하는 앱, 원장/학부모/수강생처럼 역할별 화면이 같은 데이터를 공유하는 앱은 이 서버 데이터 API를 사용한다.
- 단일 사용자 로컬 저장만 필요한 앱이면 서버 데이터 API를 쓰지 말고 기기 내부 영구 저장을 우선한다.
- 서버 데이터 API에는 인증/권한 기능이 없으므로 민감정보, 의료정보, 결제정보, 주민번호 같은 고위험 개인정보는 저장하지 않는다.
- 컬렉션별 endpoint는 `data_endpoint_base + "/{{collection}}"` 형식이다.
- 생성앱에서 요청할 때 반드시 `package_name`에 data_package_name 값을 넣는다.
- `POST /apps/{{task_id}}/data/{{collection}}`: body `{{"package_name": "...", "owner_id": "...", "data": {{...}}}}`
- `GET /apps/{{task_id}}/data/{{collection}}?package_name=...&owner_id=...`: 컬렉션 목록 조회
- `PATCH /apps/{{task_id}}/data/{{collection}}/{{record_id}}`: body `{{"package_name": "...", "owner_id": "...", "data": {{...}}, "replace": false}}`
- `DELETE /apps/{{task_id}}/data/{{collection}}/{{record_id}}?package_name=...`: 레코드 삭제
- BaseProject에 `lib/vibe_data_client.dart`가 있으면 이를 우선 사용한다.

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
        "max_output_tokens": 0,
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
        "max_output_tokens": 0,
        "temperature": temperature,
    }


def apply_codex_generated_app_llm_settings(
    db: Database,
    settings: Settings,
    *,
    task_id: str,
    result_payload: dict[str, Any],
) -> None:
    generated_prompt = str(result_payload.get("app_llm_system_prompt") or "").strip()
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
    reference_attachments: Optional[list[dict[str, str]]] = None,
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
    safe_reference_attachments = [
        {
            "type": attachment.get("type") or "image",
            "mime_type": attachment.get("mime_type") or "",
            "name": attachment.get("name") or "reference_image",
            "workspace_path": attachment.get("workspace_path") or "",
        }
        for attachment in normalize_reference_attachments(reference_attachments or [])
    ]
    context_payload = {
        "task_id": task.get("task_id") or "",
        "app_name": current_task_app_name(task, previous_conversation_state),
        "package_name": current_task_package_name(task, previous_conversation_state),
        "latest_user_prompt": prompt,
        "current_app_context": current_app_context,
        "previous_conversation_state": safe_previous_conversation_state,
        "device_info": device_info or {},
        "reference_image_name": normalize_reference_image_name(reference_image_name),
        "reference_attachments": safe_reference_attachments,
        "project_path": str(project_path),
    }
    context_json = json.dumps(context_payload, ensure_ascii=False, indent=2)
    codex_prompt = f"""You are the VibeFactory Codex follow-up decision agent for an existing Flutter Android app.

The existing app source code is already available in the `project` directory inside this workspace.
Read the actual code and project files as needed before deciding.
When `reference_attachments` contains workspace paths, inspect those image files as part of the latest request.
If `latest_user_prompt` is empty and reference images exist, treat the images themselves as the complete latest user message.

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
Also include `change_summary` as one or two short Korean sentences explaining what will visibly change.
`change_summary` must be a code-aware paraphrase, not a repetition or quotation of the user's message.
Do not use a template such as "이번 수정은 {{사용자 원문}}을 반영해요."
Do not include paths, code identifiers, commands, or developer terminology in `change_summary`.
If `previous_conversation_state.awaiting_confirmation` is true and the latest user message answers that pending question, merge the pending request and latest answer into `effective_user_prompt`.
For `ask_confirmation`, include 1-5 short Korean `questions`.

Result JSON schema:
{{
  "mode": "answer_question | build | ask_confirmation",
  "effective_user_prompt": "string",
  "assistant_reply": "string",
  "change_summary": "string",
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
    args = with_codex_reasoning_effort(args, settings.codex_followup_reasoning_effort)
    env = os.environ.copy()
    env["CI"] = "1"

    exit_code: Optional[int] = None
    timed_out = False
    decision_started_at = time.monotonic()
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
        stdout_text = read_text_if_exists(stdout_path, limit=None)
        stderr_text = read_text_if_exists(stderr_path, limit=None)
        db.log_event(
            str(task.get("task_id") or ""),
            actor="system",
            event_type="codex_followup_decision_failed",
            message_text="기존 앱 follow-up 판단 결과 파일이 생성되지 않았습니다.",
            payload={
                "exit_code": exit_code,
                "timed_out": timed_out,
                "elapsed_seconds": round(time.monotonic() - decision_started_at, 3),
                "stdout": stdout_text,
                "stderr": stderr_text,
            },
        )
        return None

    try:
        raw_output_text = result_path.read_text(encoding="utf-8")
    except OSError as exc:
        db.log_event(
            str(task.get("task_id") or ""),
            actor="system",
            event_type="codex_followup_decision_read_failed",
            message_text=str(exc),
            payload={
                "error": str(exc),
                "stdout": read_text_if_exists(stdout_path, limit=None),
                "stderr": read_text_if_exists(stderr_path, limit=None),
            },
        )
        return None
    usage = parse_codex_usage_from_jsonl(stdout_path)
    process_response = {
        "exit_code": exit_code,
        "timed_out": timed_out,
        "elapsed_seconds": round(time.monotonic() - decision_started_at, 3),
        "result_path": result_relative_path,
        "stdout": read_text_if_exists(stdout_path, limit=None),
        "stderr": read_text_if_exists(stderr_path, limit=None),
    }
    try:
        parsed = json.loads(raw_output_text)
    except json.JSONDecodeError as exc:
        log_agent_output_event(
            db,
            str(task.get("task_id") or ""),
            agent_name="codex_existing_task_followup",
            model=infer_model_name_from_codex_command(settings.codex_command),
            raw_output_text=raw_output_text,
            parsed_result={},
            usage=codex_usage_payload(usage),
            raw_response={
                **process_response,
                "parse_error": str(exc),
            },
        )
        return None
    if not isinstance(parsed, dict):
        log_agent_output_event(
            db,
            str(task.get("task_id") or ""),
            agent_name="codex_existing_task_followup",
            model=infer_model_name_from_codex_command(settings.codex_command),
            raw_output_text=raw_output_text,
            parsed_result={},
            usage=codex_usage_payload(usage),
            raw_response={
                **process_response,
                "validation_error": "result is not a JSON object",
            },
        )
        return None

    mode = normalize_whitespace(str(parsed.get("mode") or ""))
    if mode not in {"answer_question", "build", "ask_confirmation"}:
        log_agent_output_event(
            db,
            str(task.get("task_id") or ""),
            agent_name="codex_existing_task_followup",
            model=infer_model_name_from_codex_command(settings.codex_command),
            raw_output_text=raw_output_text,
            parsed_result=parsed,
            usage=codex_usage_payload(usage),
            raw_response={
                **process_response,
                "validation_error": f"unsupported mode: {mode}",
            },
        )
        return None
    if mode == "answer_question":
        parsed["assistant_reply"] = sanitize_codex_followup_user_text(str(parsed.get("assistant_reply") or ""))
    if mode == "build":
        parsed["change_summary"] = sanitize_codex_followup_user_text(str(parsed.get("change_summary") or ""))
    if mode == "ask_confirmation":
        parsed["questions"] = [
            sanitize_codex_followup_user_text(str(item))
            for item in parsed.get("questions", [])
            if normalize_whitespace(str(item))
        ]

    log_agent_output_event(
        db,
        str(task.get("task_id") or ""),
        agent_name="codex_existing_task_followup",
        model=infer_model_name_from_codex_command(settings.codex_command),
        raw_output_text=raw_output_text,
        parsed_result=parsed,
        usage=codex_usage_payload(usage),
        raw_response=process_response,
    )
    return parsed


def normalize_dart_project_identity(
    dart_text: str,
    *,
    task_id: str,
    package_name: str,
    previous_package_names: set[str],
) -> str:
    updated = dart_text
    for previous_package_name in previous_package_names:
        if previous_package_name and previous_package_name != package_name:
            updated = updated.replace(previous_package_name, package_name)

    string_declaration = (
        r"(?:(?:static\s+)?(?:const|final)\s+(?:String\s+)?|(?:static\s+)?String\s+)"
    )
    package_assignment = re.compile(
        rf"(?P<prefix>\b{string_declaration}"
        r"[A-Za-z_][A-Za-z0-9_]*package[A-Za-z0-9_]*\s*=\s*)"
        r"(?P<quote>['\"])[^'\"]*(?P=quote)",
        flags=re.IGNORECASE,
    )
    task_id_assignment = re.compile(
        rf"(?P<prefix>\b{string_declaration}"
        r"[A-Za-z_][A-Za-z0-9_]*task[A-Za-z0-9_]*id[A-Za-z0-9_]*\s*=\s*)"
        r"(?P<quote>['\"])[^'\"]*(?P=quote)",
        flags=re.IGNORECASE,
    )
    literal_package_argument = re.compile(
        r"(?P<prefix>\bpackageName\s*:\s*)(?P<quote>['\"])[^'\"]*(?P=quote)"
    )
    literal_task_argument = re.compile(
        r"(?P<prefix>\btaskId\s*:\s*)(?P<quote>['\"])[^'\"]*(?P=quote)"
    )

    def replace_string(match: re.Match[str], value: str) -> str:
        return f"{match.group('prefix')}{match.group('quote')}{value}{match.group('quote')}"

    updated = package_assignment.sub(lambda match: replace_string(match, package_name), updated)
    updated = task_id_assignment.sub(lambda match: replace_string(match, task_id), updated)
    updated = literal_package_argument.sub(lambda match: replace_string(match, package_name), updated)
    updated = literal_task_argument.sub(lambda match: replace_string(match, task_id), updated)
    updated = re.sub(
        r"(?P<prefix>/apps/)[0-9a-fA-F]{32}(?P<suffix>/(?:llm/respond|data)(?=[/'\"?]))",
        rf"\g<prefix>{task_id}\g<suffix>",
        updated,
    )
    return updated


def apply_project_defaults(project_root: Path, task_id: str, app_name: str, package_name: str) -> bool:
    changed = False
    previous_package_names: set[str] = set()
    manifest_path = project_root / "android" / "app" / "src" / "main" / "AndroidManifest.xml"
    manifest_text = read_text_if_exists(manifest_path, limit=None)
    if manifest_text:
        next_manifest_text = re.sub(
            r'android:label="[^"]*"',
            f'android:label="{app_name}"',
            manifest_text,
            count=1,
        )
        if next_manifest_text != manifest_text:
            manifest_path.write_text(next_manifest_text, encoding="utf-8")
            changed = True

    gradle_path = project_root / "android" / "app" / "build.gradle.kts"
    gradle_text = read_text_if_exists(gradle_path, limit=None)
    if gradle_text:
        previous_package_names.update(
            match.strip()
            for match in re.findall(
                r'(?:namespace|applicationId)\s*=\s*"([^"]+)"',
                gradle_text,
            )
            if match.strip()
        )
        next_gradle_text = re.sub(
            r'namespace\s*=\s*"[^"]+"',
            f'namespace = "{package_name}"',
            gradle_text,
        )
        next_gradle_text = re.sub(
            r'applicationId\s*=\s*"[^"]+"',
            f'applicationId = "{package_name}"',
            next_gradle_text,
        )
        next_gradle_text = re.sub(
            r"(?m)^[ \t]*applicationIdSuffix\s*=\s*[^\n]+\n?",
            "",
            next_gradle_text,
        )
        if next_gradle_text != gradle_text:
            gradle_path.write_text(next_gradle_text, encoding="utf-8")
            changed = True

    kotlin_root = project_root / "android" / "app" / "src" / "main" / "kotlin"
    kotlin_files = sorted(kotlin_root.rglob("*.kt")) if kotlin_root.exists() else []
    kotlin_sources: dict[Path, str] = {}
    generated_package_pattern = re.compile(
        r"\bkr\.ac\.kangwon\.hai\.generated\.customapp[A-Za-z0-9_]+\b"
    )
    stale_package_roots = {
        candidate
        for candidate in previous_package_names
        if candidate and candidate != package_name
    }
    stale_package_roots.add("kr.ac.kangwon.hai.baseproject")

    for kotlin_file in kotlin_files:
        kotlin_text = read_text_if_exists(kotlin_file, limit=None)
        if not kotlin_text:
            continue
        kotlin_sources[kotlin_file] = kotlin_text
        package_match = re.search(r"^package\s+([A-Za-z0-9_.]+)", kotlin_text, flags=re.MULTILINE)
        if not package_match:
            continue
        declared_package = package_match.group(1)
        generated_match = generated_package_pattern.match(declared_package)
        if generated_match and generated_match.group(0) != package_name:
            stale_package_roots.add(generated_match.group(0))
        elif declared_package != package_name and not declared_package.startswith(f"{package_name}."):
            matching_previous_root = next(
                (
                    previous
                    for previous in previous_package_names
                    if declared_package == previous or declared_package.startswith(f"{previous}.")
                ),
                None,
            )
            if matching_previous_root is None:
                stale_package_roots.add(declared_package)

    for kotlin_file, kotlin_text in kotlin_sources.items():
        next_kotlin_text = kotlin_text
        for stale_package in sorted(stale_package_roots, key=len, reverse=True):
            if stale_package == package_name:
                continue
            next_kotlin_text = re.sub(
                rf"(?<![A-Za-z0-9_]){re.escape(stale_package)}(?=\.|[^A-Za-z0-9_]|$)",
                package_name,
                next_kotlin_text,
            )
        if next_kotlin_text != kotlin_text:
            kotlin_file.write_text(next_kotlin_text, encoding="utf-8")
            changed = True

    lib_root = project_root / "lib"
    for dart_path in lib_root.rglob("*.dart"):
        dart_text = read_text_if_exists(dart_path, limit=None)
        if not dart_text:
            continue
        next_dart_text = normalize_dart_project_identity(
            dart_text,
            task_id=task_id,
            package_name=package_name,
            previous_package_names=previous_package_names,
        )
        if dart_path.name == "main.dart":
            next_dart_text = re.sub(
                r'CrashHandler\.initialize\(\s*.*?\s*\);\s*',
                f'CrashHandler.initialize("{task_id}", "{package_name}");\n',
                next_dart_text,
                count=1,
                flags=re.DOTALL,
            )
            next_dart_text = next_dart_text.replace("title: 'Generated App'", f"title: '{app_name}'")
            next_dart_text = next_dart_text.replace(
                'Text("Generated App Running")',
                f'Text("{app_name} 실행 중")',
            )
        if next_dart_text != dart_text:
            dart_path.write_text(next_dart_text, encoding="utf-8")
            changed = True

    if ensure_release_uses_debug_signing(project_root):
        changed = True
    return changed


def project_android_identity_issues(project_root: Path, package_name: str) -> list[str]:
    issues: list[str] = []
    gradle_path = project_root / "android" / "app" / "build.gradle.kts"
    gradle_text = read_text_if_exists(gradle_path, limit=None)
    for key, configured_package in re.findall(
        r"(namespace|applicationId)\s*=\s*\"([^\"]+)\"",
        gradle_text,
    ):
        if configured_package != package_name:
            issues.append(f"{key}={configured_package}")

    kotlin_root = project_root / "android" / "app" / "src" / "main" / "kotlin"
    if kotlin_root.exists():
        for kotlin_file in sorted(kotlin_root.rglob("*.kt")):
            kotlin_text = read_text_if_exists(kotlin_file, limit=None)
            if not kotlin_text:
                continue
            package_match = re.search(r"^package\s+([A-Za-z0-9_.]+)", kotlin_text, flags=re.MULTILINE)
            if package_match is None:
                issues.append(f"{kotlin_file.relative_to(project_root)}:missing-package")
                continue
            declared_package = package_match.group(1)
            if declared_package != package_name and not declared_package.startswith(f"{package_name}."):
                issues.append(f"{kotlin_file.relative_to(project_root)}:{declared_package}")
    return issues


def ensure_release_uses_debug_signing(project_root: Path) -> bool:
    gradle_path = project_root / "android" / "app" / "build.gradle.kts"
    gradle_text = read_text_if_exists(gradle_path, limit=100_000)
    if not gradle_text:
        return False
    if "signingConfigs.getByName(\"debug\")" in gradle_text or "signingConfigs.debug" in gradle_text:
        return False

    flutter_block_match = re.search(r"(?m)^flutter\s*\{", gradle_text)
    search_end = flutter_block_match.start() if flutter_block_match else len(gradle_text)
    android_end_index = gradle_text.rfind("}", 0, search_end)
    if android_end_index < 0:
        return False

    build_types_block = """

    buildTypes {
        getByName("release") {
            signingConfig = signingConfigs.getByName("debug")
        }
    }
"""
    next_text = gradle_text[:android_end_index] + build_types_block + gradle_text[android_end_index:]
    if next_text == gradle_text:
        return False
    gradle_path.write_text(next_text, encoding="utf-8")
    return True


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
    _ = revision_label or current_revision_label(project_root)
    pubspec_path = project_root / "pubspec.yaml"
    pubspec_text = read_text_if_exists(pubspec_path, limit=100_000)
    if not pubspec_text:
        return False

    version_match = re.search(r"^version:\s*([^\s#]+)(.*)$", pubspec_text, re.MULTILINE)
    if version_match:
        current_value = version_match.group(1).strip()
        suffix = version_match.group(2)
        version_name, _, _ = current_value.partition("+")
        version_name = version_name.strip() or "1.0.0"
        next_line = f"version: {version_name}+{GENERATED_APK_SIDELOAD_VERSION_CODE}{suffix}"
        next_text = (
            pubspec_text[: version_match.start()]
            + next_line
            + pubspec_text[version_match.end() :]
        )
    else:
        separator = "" if pubspec_text.endswith("\n") else "\n"
        next_text = (
            f"{pubspec_text}{separator}"
            f"version: 1.0.0+{GENERATED_APK_SIDELOAD_VERSION_CODE}\n"
        )

    if next_text == pubspec_text:
        return False
    pubspec_path.write_text(next_text, encoding="utf-8")
    return True


def built_apk_identity(project_root: Path, apk_path: Path) -> tuple[str, Optional[int]]:
    metadata_candidates = (
        project_root / "build" / "app" / "outputs" / "apk" / "release" / "output-metadata.json",
        project_root / "build" / "app" / "outputs" / "apk" / "debug" / "output-metadata.json",
    )
    for metadata_path in metadata_candidates:
        if not metadata_path.is_file():
            continue
        try:
            metadata = json.loads(metadata_path.read_text(encoding="utf-8"))
        except (json.JSONDecodeError, OSError):
            continue
        if not isinstance(metadata, dict):
            continue
        application_id = normalize_whitespace(str(metadata.get("applicationId") or ""))
        elements = metadata.get("elements")
        if not application_id or not isinstance(elements, list):
            continue
        matching_element: Optional[dict[str, Any]] = None
        fallback_element: Optional[dict[str, Any]] = None
        for element in elements:
            if not isinstance(element, dict):
                continue
            fallback_element = fallback_element or element
            if str(element.get("outputFile") or "") == apk_path.name:
                matching_element = element
                break
        selected_element = matching_element or fallback_element
        if selected_element is None:
            continue
        version_code = optional_int_value(selected_element.get("versionCode"))
        return application_id, version_code
    return "", None


def validate_built_apk_install_contract(
    project_root: Path,
    apk_path: Path,
    expected_package_name: str,
) -> None:
    application_id, version_code = built_apk_identity(project_root, apk_path)
    if not application_id:
        raise RuntimeError("APK 빌드 메타데이터에서 패키지 이름을 확인할 수 없습니다.")
    if application_id != expected_package_name:
        raise RuntimeError(
            "APK 패키지 이름이 Task 식별자와 일치하지 않습니다: "
            f"{application_id} != {expected_package_name}"
        )
    if version_code != GENERATED_APK_SIDELOAD_VERSION_CODE:
        raise RuntimeError(
            "APK 설치 버전 코드가 사이드로드 정책과 일치하지 않습니다: "
            f"{version_code} != {GENERATED_APK_SIDELOAD_VERSION_CODE}"
        )


def flutter_no_pub_args(project_root: Path) -> list[str]:
    package_config = project_root / ".dart_tool" / "package_config.json"
    return ["--no-pub"] if package_config.is_file() else []


ANDROID_ONLY_WORKSPACE_ROOT_IGNORES = {
    "ios",
    "macos",
    "windows",
    "linux",
    "web",
    "test",
}


def create_initial_project_revision(
    task_root: Path,
    base_project_path: Path,
    *,
    android_only: bool = True,
) -> tuple[Path, str]:
    revision_label = "rev_0001"
    revision_root = task_root / "revisions" / revision_label
    project_root = revision_root / "project"
    source_root = base_project_path.resolve()

    def ignore_initial_project_dirs(path: str, names: list[str]) -> set[str]:
        ignored = ignore_project_revision_cache_dirs(path, names)
        if android_only and Path(path).resolve() == source_root:
            ignored.update(name for name in names if name in ANDROID_ONLY_WORKSPACE_ROOT_IGNORES)
        return ignored

    shutil.copytree(base_project_path, project_root, ignore=ignore_initial_project_dirs)
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


def create_followup_project_revision(
    workspace_path: Path,
    source_project_path: Path,
    *,
    android_only: bool = True,
) -> tuple[Path, str]:
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
    source_root = source_project_path.resolve()

    def ignore_followup_project_dirs(path: str, names: list[str]) -> set[str]:
        ignored = ignore_project_revision_cache_dirs(path, names)
        if android_only and Path(path).resolve() == source_root:
            ignored.update(name for name in names if name in ANDROID_ONLY_WORKSPACE_ROOT_IGNORES)
        return ignored

    shutil.copytree(source_project_path, project_root, ignore=ignore_followup_project_dirs)
    ensure_project_revision_version(project_root, revision_label)
    ensure_workspace_project_link(workspace_path, project_root)
    return project_root, revision_label


BRANCH_TASK_ID_TEXT_SUFFIXES = {
    ".dart",
    ".gradle",
    ".java",
    ".json",
    ".kts",
    ".kt",
    ".md",
    ".properties",
    ".swift",
    ".txt",
    ".xml",
    ".yaml",
    ".yml",
}


def replace_project_task_id(project_root: Path, source_task_id: str, branched_task_id: str) -> int:
    if not source_task_id or source_task_id == branched_task_id:
        return 0
    changed_files = 0
    for candidate in project_root.rglob("*"):
        if not candidate.is_file() or candidate.is_symlink():
            continue
        if candidate.suffix.lower() not in BRANCH_TASK_ID_TEXT_SUFFIXES:
            continue
        try:
            if candidate.stat().st_size > 10 * 1024 * 1024:
                continue
            original = candidate.read_text(encoding="utf-8")
        except (OSError, UnicodeDecodeError):
            continue
        updated = original.replace(source_task_id, branched_task_id)
        if updated == original:
            continue
        candidate.write_text(updated, encoding="utf-8")
        changed_files += 1
    return changed_files


def create_branched_task_workspace(
    settings: Settings,
    task: dict[str, Any],
    source_project_path: Path,
    *,
    source_task_id: str,
) -> tuple[Path, Path, int]:
    task_root = task_workspace_root_for(settings, task)
    project_root = task_root / "revisions" / "rev_0001" / "project"
    source_root = source_project_path.resolve()

    if task_root.exists() and ((task_root / "project").exists() or (task_root / "revisions").exists()):
        raise RuntimeError(f"task workspace already exists: {task_root}")
    if not source_root.exists() or not source_root.is_dir():
        raise RuntimeError("source revision project does not exist")

    settings.workspaces_root.mkdir(parents=True, exist_ok=True)
    task_root.parent.mkdir(parents=True, exist_ok=True)
    (task_root / "logs").mkdir(parents=True, exist_ok=True)
    (task_root / ".codex_result").mkdir(parents=True, exist_ok=True)

    def ignore_branched_project_dirs(path: str, names: list[str]) -> set[str]:
        ignored = ignore_project_revision_cache_dirs(path, names)
        if settings.android_only_workspace_enabled and Path(path).resolve() == source_root:
            ignored.update(name for name in names if name in ANDROID_ONLY_WORKSPACE_ROOT_IGNORES)
        return ignored

    shutil.copytree(source_root, project_root, ignore=ignore_branched_project_dirs)
    replaced_file_count = replace_project_task_id(project_root, source_task_id, str(task["task_id"]))
    ensure_project_revision_version(project_root, "rev_0001")
    if task.get("app_name") and task.get("package_name"):
        apply_project_defaults(project_root, str(task["task_id"]), str(task["app_name"]), str(task["package_name"]))
    ensure_workspace_project_link(task_root, project_root)
    (task_root / "AGENTS.md").write_text(render_task_agents_md(str(task["task_id"])), encoding="utf-8")
    (task_root / "prompt.md").write_text(render_prompt_md(task, settings), encoding="utf-8")
    return task_root, project_root, replaced_file_count


def clear_previous_run_artifacts(workspace_path: Path) -> None:
    artifact_paths = (
        workspace_path / ".codex_result" / "task_result.json",
        workspace_path / "logs" / "codex_stdout.log",
        workspace_path / "logs" / "codex_stderr.log",
        workspace_path / "logs" / "build.log",
        workspace_path / "project" / "build" / "app" / "outputs" / "flutter-apk" / "app-release.apk",
        workspace_path / "project" / "build" / "app" / "outputs" / "flutter-apk" / "app-arm64-v8a-release.apk",
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
    if normalized in {"cancelled", "canceled"}:
        return (message or "앱 생성을 중단했어요.").strip()
    if normalized == "success":
        return "앱 생성이 완료되었어요."
    if normalized == "failed":
        return (message or "앱 생성에 실패했어요.").strip()
    if normalized == "error":
        return (message or "서버 오류가 발생했어요.").strip()
    if normalized == "ratelimited":
        return (message or "앱 생성 한도를 모두 사용했어요.").strip()
    return (message or status).strip() or "상태를 확인하고 있어요."


def is_cancellable_task_status(status: str) -> bool:
    return status.strip().lower() in {"pending decision", "queued", "running"}


def is_generate_blocked_task_status(status: str) -> bool:
    return status.strip().lower() in {"queued", "running"}


def is_cancelled_task_status(status: str) -> bool:
    return status.strip().lower() in {"cancelled", "canceled"}


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
    raw_existing_state = state_payload.get("conversation_state")
    existing_state: dict[str, Any] = raw_existing_state if isinstance(raw_existing_state, dict) else {}
    conversation_state: dict[str, Any] = dict(existing_state)
    conversation_state_override = task.get("conversation_state_override")
    if isinstance(conversation_state_override, dict):
        conversation_state.update(conversation_state_override)

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
        "target_users": normalize_context_list(
            state.get("latest_target_users") or state.get("pending_target_users")
        ),
        "key_screens": normalize_context_list(
            state.get("latest_key_screens") or state.get("pending_key_screens")
        ),
        "storage_mode": state.get("latest_storage_mode") or state.get("pending_storage_mode") or "unspecified",
        "stored_data": normalize_context_list(
            state.get("latest_stored_data") or state.get("pending_stored_data")
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
    reference_attachments = normalize_reference_attachments(task.get("reference_attachments") or [])
    if not reference_attachments and reference_image_name:
        reference_attachments = [
            {
                "type": "image",
                "mime_type": f"image/{infer_reference_image_suffix(reference_image_name).lstrip('.')}",
                "name": reference_image_name,
                "base64": reference_image_base64,
                "workspace_path": reference_image_workspace_path,
            }
        ]
    if reference_attachments and any(item.get("workspace_path") for item in reference_attachments):
        reference_image_base64 = ""
    ui_flags = decision_ui_flags(decision)
    awaiting_prompt_review = decision.confirmation_action == "submit_initial_prompt"
    prepared_prompt = decision.prepared_prompt or (
        decision.effective_user_prompt if awaiting_prompt_review else ""
    )
    recent_assistant_content = prepared_prompt if awaiting_prompt_review else decision.message
    recent_assistant_type = "prompt_review" if awaiting_prompt_review else ("confirmation" if decision.confirmation_action else ("status" if ui_flags["suppress_assistant_bubble"] else decision.tool))
    recent_assistant_role = "confirmation" if decision.confirmation_action else ("status" if ui_flags["suppress_assistant_bubble"] else "assistant")
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
    latest_target_users = decision.target_users or normalize_context_list(
        previous_conversation_state.get("latest_target_users")
    )
    latest_key_screens = decision.key_screens or normalize_context_list(
        previous_conversation_state.get("latest_key_screens")
    )
    latest_storage_mode = normalize_storage_mode(decision.storage_mode)
    if latest_storage_mode == "unspecified":
        latest_storage_mode = normalize_storage_mode(previous_conversation_state.get("latest_storage_mode"))
    latest_stored_data = decision.stored_data or normalize_context_list(
        previous_conversation_state.get("latest_stored_data")
    )
    latest_summary = decision.summary or str(previous_conversation_state.get("latest_summary") or "")
    return {
        "status": decision.status,
        "tool": decision.tool,
        "message": decision.message,
        "summary": decision.summary,
        "prepared_prompt": prepared_prompt,
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
        "target_users": decision.target_users,
        "key_screens": decision.key_screens,
        "storage_mode": decision.storage_mode,
        "stored_data": decision.stored_data,
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
            "prepared_prompt": prepared_prompt,
            "final_generation_prompt": previous_conversation_state.get("final_generation_prompt") or "",
            "latest_assistant_questions": decision.questions,
            "latest_primary_user_flow": latest_primary_user_flow,
            "latest_secondary_requirements": latest_secondary_requirements,
            "latest_secondary_scope_confirmed": decision.secondary_scope_confirmed or bool(previous_conversation_state.get("latest_secondary_scope_confirmed")),
            "latest_acceptance_criteria": latest_acceptance_criteria,
            "latest_target_users": latest_target_users,
            "latest_key_screens": latest_key_screens,
            "latest_storage_mode": latest_storage_mode,
            "latest_stored_data": latest_stored_data,
            "awaiting_confirmation": decision.mode == "ask_confirmation",
            "awaiting_prompt_review": awaiting_prompt_review,
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
            "pending_target_users": decision.target_users if decision.mode == "ask_confirmation" else [],
            "pending_key_screens": decision.key_screens if decision.mode == "ask_confirmation" else [],
            "pending_storage_mode": decision.storage_mode if decision.mode == "ask_confirmation" else "unspecified",
            "pending_stored_data": decision.stored_data if decision.mode == "ask_confirmation" else [],
            "used_previous_pending_prompt": decision.used_previous_pending_prompt,
            "request_scope": state_request_scope,
            "requires_existing_task_context": decision.requires_existing_task_context,
            "device_info": device_info,
            "reference_image_name": reference_image_name,
            "reference_image_base64": reference_image_base64,
            "reference_image_workspace_path": reference_image_workspace_path,
            "reference_attachments": reference_attachments,
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
                "role": recent_assistant_role,
                "message_type": recent_assistant_type,
                "content": recent_assistant_content,
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
        "target_users": decision.target_users,
        "key_screens": decision.key_screens,
        "storage_mode": decision.storage_mode,
        "stored_data": decision.stored_data,
        "confirmation_action": decision.confirmation_action,
        "confirmation_payload": decision.confirmation_payload,
        "image_reference_summary": decision.image_reference_summary,
        "image_conflict_note": decision.image_conflict_note,
        "prepared_prompt": decision.prepared_prompt,
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
    cancel_allowed = is_cancellable_task_status(str(task.get("status") or ""))
    state_payload = load_task_state_payload(task)
    return {
        "status": task.get("status") or "",
        "message": task.get("message") or "",
        "prepared_prompt": str(state_payload.get("prepared_prompt") or ""),
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
        "cancel_allowed": cancel_allowed,
        "allowed_next_actions": ["cancel"] if cancel_allowed else [],
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


def task_event_to_timeline_event(row: dict[str, Any]) -> Optional[dict[str, Any]]:
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
        if "display_prompt" in payload:
            body = sanitize_user_visible_text(str(payload.get("display_prompt") or ""))
        else:
            body = message_text or sanitize_user_visible_text(str(payload.get("raw_prompt") or ""))
    elif event_type == "assistant_message":
        render_mode = str(payload.get("render_mode") or "")
        confirmation_action = str(payload.get("confirmation_action") or "")
        prepared_prompt = sanitize_user_visible_text(str(payload.get("prepared_prompt") or ""))
        kind = "confirmation" if render_mode == "prompt_review_bubble" and confirmation_action == "submit_initial_prompt" else "assistant"
        title = "AI"
        body = prepared_prompt if kind == "confirmation" and prepared_prompt else message_text
    elif event_type == "task_branched":
        kind = "assistant"
        title = "AI"
        body = message_text or "선택한 버전에서 새 Task를 만들었어요. 앱을 준비하고 있어요."
    elif event_type in {"task_status", "task_succeeded", "task_failed", "task_error", "task_timeout", "task_cancelled"}:
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
        if event_type == "agent_raw_output" or event_type.startswith("app_llm_"):
            return None
        kind = "log" if actor == "system" else "assistant"
        title = "로그" if kind == "log" else "AI"
        body = message_text or sanitize_user_visible_text(str(payload.get("message") or ""))

    body = sanitize_user_visible_text(body).strip()
    detail = sanitize_user_visible_text(detail).strip()
    try:
        attachment_count = int(payload.get("attachment_count") or 0)
    except (TypeError, ValueError):
        attachment_count = 0
    has_user_attachments = event_type == "user_message" and (
        attachment_count > 0 or bool(payload.get("attachments"))
    )
    if not body and not has_user_attachments:
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
    for key in ("confirmation_action", "confirmation_payload", "prepared_prompt", "render_mode"):
        value = sanitize_user_visible_text(str(payload.get(key) or ""))
        if value:
            event[key] = value
    if payload.get("apk_size_bytes") is not None:
        event["apk_size_bytes"] = str(payload.get("apk_size_bytes"))
    return event


def build_task_timeline_events(
    db: Database,
    task_id: str,
    *,
    limit: int = 120,
    after_event_id: Optional[str] = None,
) -> list[dict[str, Any]]:
    attachments_by_event_id: dict[str, list[dict[str, Any]]] = {}
    for attachment in db.list_task_attachments(task_id):
        event_id = str(attachment.get("event_id") or "").strip()
        if not event_id or str(attachment.get("status") or "") != "saved":
            continue
        attachments_by_event_id.setdefault(event_id, []).append(
            {
                "attachment_id": str(attachment.get("attachment_id") or ""),
                "kind": str(attachment.get("kind") or ""),
                "name": str(attachment.get("original_name") or ""),
                "mime_type": str(attachment.get("mime_type") or ""),
                "size_bytes": int(attachment.get("size_bytes") or 0),
            }
        )

    timeline: list[dict[str, Any]] = []
    source_limit = limit * 3 if limit > 0 else None
    for row in db.list_events(
        task_id,
        limit=source_limit,
        after_event_id=after_event_id,
    ):
        event = task_event_to_timeline_event(row)
        if event:
            event_id = str(row.get("event_id") or "").strip()
            event_attachments = attachments_by_event_id.get(event_id, [])
            if event_attachments:
                event["attachments"] = event_attachments
            timeline.append(event)
    return timeline[-limit:] if limit > 0 else timeline


def derive_current_build_stage(task: dict[str, Any], timeline_events: list[dict[str, Any]]) -> tuple[str, str]:
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
        "temperature": float(config.get("temperature") or 0.4),
    }
    # Keep per-call output uncapped. The daily token quota remains enforced after
    # the response, but adding max_output_tokens can truncate structured JSON.

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
        self.active_task_processes: dict[str, subprocess.Popen] = {}
        self.process_lock = threading.Lock()
        self.sideload_build_lock = threading.Lock()

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

    def register_process(self, process: subprocess.Popen, task_id: Optional[str] = None) -> None:
        with self.process_lock:
            self.active_processes[id(process)] = process
            if task_id:
                self.active_task_processes[task_id] = process

    def unregister_process(self, process: subprocess.Popen) -> None:
        with self.process_lock:
            self.active_processes.pop(id(process), None)
            for task_id, active_process in list(self.active_task_processes.items()):
                if active_process is process:
                    self.active_task_processes.pop(task_id, None)

    def terminate_process(self, process: subprocess.Popen) -> None:
        if process.poll() is not None:
            self.unregister_process(process)
            return
        try:
            os.killpg(os.getpgid(process.pid), signal.SIGTERM)
        except ProcessLookupError:
            self.unregister_process(process)
            return
        except Exception:
            try:
                process.terminate()
            except ProcessLookupError:
                self.unregister_process(process)
                return
        try:
            process.wait(timeout=2)
        except subprocess.TimeoutExpired:
            try:
                os.killpg(os.getpgid(process.pid), signal.SIGKILL)
            except ProcessLookupError:
                pass
            except Exception:
                try:
                    process.kill()
                except ProcessLookupError:
                    pass
            process.wait()
        finally:
            self.unregister_process(process)

    def terminate_active_processes(self) -> None:
        with self.process_lock:
            processes = list(self.active_processes.values())
        for process in processes:
            self.terminate_process(process)

    def terminate_task_process(self, task_id: str) -> None:
        with self.process_lock:
            process = self.active_task_processes.get(task_id)
        if process is not None:
            self.terminate_process(process)

    def is_task_cancelled(self, task_id: str) -> bool:
        task = self.db.get_task(task_id)
        return str(task.get("status") or "").strip().lower() in {"cancelled", "canceled"} if task else False

    def cancel_task(self, task_id: str) -> dict[str, Any]:
        task = self.db.get_task(task_id)
        if not task:
            raise KeyError("task not found")
        status = str(task.get("status") or "")
        if not is_cancellable_task_status(status):
            return task
        did_cancel = self.db.update_task_if_status(
            task_id,
            {"pending decision", "queued", "running"},
            status="Cancelled",
            message="앱 생성을 중단했어요.",
        )
        if not did_cancel:
            return self.db.get_task(task_id) or task
        updated_task = self.db.get_task(task_id) or task
        log_task_status_event(self.db, updated_task, event_type="task_cancelled")
        log_build_stage_event(
            self.db,
            task_id,
            stage="앱 생성 작업",
            phase="cancelled",
            body="사용자가 앱 생성을 중단했어요.",
        )
        self.terminate_task_process(task_id)
        return updated_task

    def enqueue(self, task_id: str) -> None:
        self.queue.put(task_id)

    def prepare_branched_task_workspace(
        self,
        task: dict[str, Any],
        task_state: dict[str, Any],
    ) -> dict[str, Any]:
        task_id = str(task["task_id"])
        branch_origin = task_state.get("branch_origin")
        if not isinstance(branch_origin, dict):
            raise RuntimeError("분기 원본 정보가 없습니다.")

        source_task_id = normalize_whitespace(str(branch_origin.get("source_task_id") or ""))
        source_revision_label = normalize_whitespace(str(branch_origin.get("source_revision_label") or ""))
        if not source_task_id or not re.fullmatch(r"rev_\d{4}", source_revision_label):
            raise RuntimeError("분기 원본 버전 정보가 올바르지 않습니다.")

        source_task = self.db.get_task(source_task_id)
        if not source_task:
            raise RuntimeError("분기 원본 Task를 찾을 수 없습니다.")
        snapshot = self.db.get_project_snapshot(source_task_id, source_revision_label)
        if snapshot is None:
            current_project_value = normalize_whitespace(str(source_task.get("project_path") or ""))
            if current_project_value and current_revision_label(Path(current_project_value)) == source_revision_label:
                snapshot = {
                    "task_id": source_task_id,
                    "revision_label": source_revision_label,
                    "workspace_path": source_task.get("workspace_path"),
                    "project_path": current_project_value,
                }
            else:
                raise RuntimeError("분기 원본 버전을 찾을 수 없습니다.")

        source_workspace_path = Path(str(snapshot.get("workspace_path") or "")).resolve()
        source_project_path = Path(str(snapshot.get("project_path") or "")).resolve()
        if (
            not source_workspace_path.exists()
            or not source_workspace_path.is_dir()
            or not source_project_path.exists()
            or not source_project_path.is_dir()
            or not ensure_within_root(source_project_path, source_workspace_path)
        ):
            raise RuntimeError("분기 원본 버전의 파일을 사용할 수 없습니다.")

        log_build_stage_event(
            self.db,
            task_id,
            stage="분기 workspace 복사",
            phase="started",
            body="선택한 버전을 새 Task로 복사하고 있어요.",
        )
        workspace_path, project_path, replaced_file_count = create_branched_task_workspace(
            self.settings,
            task,
            source_project_path,
            source_task_id=source_task_id,
        )
        branch_origin["replaced_task_id_file_count"] = replaced_file_count
        task_state["branch_origin"] = branch_origin
        workspace_update = {
            "workspace_path": str(workspace_path),
            "project_path": str(project_path),
            "codex_result_json": json.dumps(task_state, ensure_ascii=False),
        }
        if not self.is_task_cancelled(task_id):
            workspace_update["message"] = "선택한 버전을 복사했어요. APK를 준비하고 있어요."
        self.db.update_task(task_id, **workspace_update)
        self.db.record_project_snapshot(
            task_id=task_id,
            revision_label="rev_0001",
            source="branched_revision",
            workspace_path=str(workspace_path),
            project_path=str(project_path),
            request_summary="선택한 버전에서 새 Task로 분기",
        )
        self.db.log_event(
            task_id,
            actor="system",
            event_type="branch_workspace_ready",
            message_text="분기된 workspace 복사가 완료되었어요.",
            payload={
                **branch_origin,
                "workspace_path": str(workspace_path),
                "project_path": str(project_path),
            },
        )
        if self.is_task_cancelled(task_id):
            return self.db.get_task(task_id) or {
                **task,
                "workspace_path": str(workspace_path),
                "project_path": str(project_path),
                "codex_result_json": json.dumps(task_state, ensure_ascii=False),
            }
        log_build_stage_event(
            self.db,
            task_id,
            stage="분기 workspace 복사",
            phase="succeeded",
            body="선택한 버전을 새 Task로 복사했어요.",
        )
        return self.db.get_task(task_id) or {
            **task,
            "workspace_path": str(workspace_path),
            "project_path": str(project_path),
            "codex_result_json": json.dumps(task_state, ensure_ascii=False),
        }

    def enforce_task_project_identity(self, task_id: str) -> bool:
        task = self.db.get_task(task_id)
        if not task:
            return False
        project_path_value = normalize_whitespace(str(task.get("project_path") or ""))
        app_name = current_task_app_name(task)
        package_name = current_task_package_name(task)
        if not project_path_value or not app_name or not package_name:
            return False
        project_path = Path(project_path_value).resolve()
        if not project_path.exists() or not project_path.is_dir():
            return False

        changed = apply_project_defaults(project_path, task_id, app_name, package_name)
        if ensure_project_revision_version(project_path):
            changed = True
        if changed:
            self.db.log_event(
                task_id,
                actor="system",
                event_type="project_identity_enforced",
                message_text="앱 식별 정보를 Task 기준으로 복원했어요.",
                payload={
                    "app_name": app_name,
                    "package_name": package_name,
                    "project_path": str(project_path),
                },
            )
        return changed

    def worker_loop(self) -> None:
        while not self.stop_event.is_set():
            task_id = self.queue.get()
            if task_id is None:
                self.queue.task_done()
                break
            try:
                self.process_task(task_id)
            except Exception as exc:
                if self.is_task_cancelled(task_id):
                    continue
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
        if self.is_task_cancelled(task_id):
            return

        task_state = load_task_state_payload(task)
        is_branch_rebuild = task_state.get("task_operation") == "branch_rebuild"
        did_start = self.db.update_task_if_status(
            task_id,
            {"queued"},
            status="Running",
            message=(
                "선택한 버전을 복사하고 APK를 준비하고 있어요."
                if is_branch_rebuild
                else "앱 생성 작업을 진행하고 있습니다."
            ),
        )
        if not did_start:
            return
        updated_task = self.db.get_task(task_id)
        if updated_task:
            log_task_status_event(self.db, updated_task)

        if is_branch_rebuild and (not task.get("workspace_path") or not task.get("project_path")):
            task = self.prepare_branched_task_workspace(task, task_state)
            if self.is_task_cancelled(task_id):
                return

        workspace_path_value = normalize_whitespace(str(task.get("workspace_path") or ""))
        if not workspace_path_value:
            raise RuntimeError("작업 workspace가 준비되지 않았습니다.")
        workspace_path = Path(workspace_path_value).resolve()
        result_path = workspace_path / ".codex_result" / "task_result.json"
        stdout_path = workspace_path / "logs" / "codex_stdout.log"
        stderr_path = workspace_path / "logs" / "codex_stderr.log"
        clear_previous_run_artifacts(workspace_path)

        if is_branch_rebuild:
            log_build_stage_event(
                self.db,
                task_id,
                stage="분기 앱 준비",
                phase="started",
                body="선택한 버전의 독립 복제본을 검증하고 있어요.",
            )
            if self.settings.mock_codex:
                self.run_mock(task, workspace_path, stdout_path, stderr_path, result_path)
            else:
                self.attempt_server_side_build(task_id, workspace_path, result_path, None)
            self.finalize_task(task_id, workspace_path, result_path, None, False)
            return

        log_build_stage_event(
            self.db,
            task_id,
            stage="앱 설계와 코드 생성",
            phase="started",
            body="앱 설계와 코드 생성을 시작했어요.",
        )

        exit_code: Optional[int] = None
        timed_out = False
        codex_started_at = time.monotonic()

        if self.settings.mock_codex:
            exit_code, timed_out = self.run_mock(task, workspace_path, stdout_path, stderr_path, result_path)
        else:
            exit_code, timed_out = self.run_codex(task, workspace_path, stdout_path, stderr_path)
            if not self.is_task_cancelled(task_id):
                codex_elapsed_seconds = time.monotonic() - codex_started_at
                codex_phase = "failed" if timed_out or (exit_code not in (0, None) and not result_path.exists()) else "succeeded"
                codex_body = (
                    "앱 설계와 코드 생성 시간이 제한을 초과했어요."
                    if timed_out
                    else "앱 설계와 코드 생성 단계가 완료되었어요."
                    if codex_phase == "succeeded"
                    else "앱 설계와 코드 생성 단계에 실패했어요."
                )
                log_build_stage_event(
                    self.db,
                    task_id,
                    stage="앱 설계와 코드 생성",
                    phase=codex_phase,
                    body=codex_body,
                    detail=f"종료 코드: {exit_code if exit_code is not None else '-'}, 소요 시간: {codex_elapsed_seconds:.1f}초",
                )
            identity_changed = False
            if not self.is_task_cancelled(task_id):
                identity_changed = self.enforce_task_project_identity(task_id)
            result_exists = result_path.exists()
            if not self.is_task_cancelled(task_id):
                codex_log_text = collect_task_logs(workspace_path, "logs/build.log", full=True)
                engine_issue = None if result_exists else codex_engine_issue_from_logs(codex_log_text, exit_code)
                if should_attempt_server_side_build(
                    result_exists=result_exists,
                    identity_changed=identity_changed,
                    timed_out=timed_out,
                    engine_issue=engine_issue,
                ):
                    self.attempt_server_side_build(task_id, workspace_path, result_path, exit_code)

        self.finalize_task(task_id, workspace_path, result_path, exit_code, timed_out)

    def run_codex(
        self,
        task: dict[str, Any],
        workspace_path: Path,
        stdout_path: Path,
        stderr_path: Path,
    ) -> tuple[Optional[int], bool]:
        task_id = str(task["task_id"])
        if self.is_task_cancelled(task_id):
            return None, False
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
                start_new_session=True,
            )
            self.register_process(process, task_id=task_id)
            if self.is_task_cancelled(task_id):
                self.terminate_task_process(task_id)
                return process.returncode, False
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
        tool_cache_root = self.settings.build_cache_root if self.settings.shared_build_cache_enabled else workspace_path / ".tooling"
        pub_cache_path = tool_cache_root / "pub-cache"
        gradle_home_path = tool_cache_root / "gradle"
        temp_path = workspace_path / ".tooling" / "tmp"
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
        task_id: Optional[str] = None,
    ) -> tuple[int, bool, float]:
        if task_id and self.is_task_cancelled(task_id):
            return 1, False, 0.0
        log_path.parent.mkdir(parents=True, exist_ok=True)
        command_text = " ".join(shlex.quote(part) for part in args)
        started_at = time.monotonic()
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
                start_new_session=True,
            )
            self.register_process(process, task_id=task_id)
            if task_id and self.is_task_cancelled(task_id):
                self.terminate_task_process(task_id)
                elapsed = time.monotonic() - started_at
                log_file.write(f"\n[server] command cancelled after {elapsed:.1f}s\n")
                log_file.flush()
                return process.returncode or 1, False, elapsed
            try:
                exit_code = self.wait_for_process(process)
                elapsed = time.monotonic() - started_at
                log_file.write(f"\n[server] command finished exit_code={exit_code} duration={elapsed:.1f}s\n")
                log_file.flush()
                return exit_code, False, elapsed
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait()
                elapsed = time.monotonic() - started_at
                log_file.write(f"\n[server] command timed out after {elapsed:.1f}s\n")
                log_file.flush()
                return process.returncode or 1, True, elapsed
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
        debug_apk = (project_path / "build" / "app" / "outputs" / "flutter-apk" / "app-debug.apk").resolve()
        status_message = "설치 가능한 디버그 APK를 준비하고 있어요."
        did_update = self.db.update_task_if_status(
            task_id,
            {"running"},
            status="Running",
            message=status_message,
        )
        if not did_update:
            raise RuntimeError("앱 생성이 중단되었습니다.")
        running_task = self.db.get_task(task_id)
        if running_task:
            log_task_status_event(self.db, running_task, event_type="build_stage_debug_prepare")
        log_build_stage_event(
            self.db,
            task_id,
            stage="debug apk",
            phase="started",
            body=status_message,
            detail="최적화 APK 준비에 실패했거나 비활성화되어 debug APK를 준비합니다.",
        )
        exit_code, timed_out, elapsed_seconds = self.run_logged_command(
            flutter_args + ["build", "apk", "--debug"] + flutter_no_pub_args(project_path),
            cwd=project_path,
            env=env,
            log_path=build_log_path,
            task_id=task_id,
        )
        if self.is_task_cancelled(task_id):
            raise RuntimeError("앱 생성이 중단되었습니다.")
        if timed_out and not debug_apk.exists():
            raise RuntimeError("debug APK 빌드가 시간 제한을 초과했습니다.")
        if exit_code != 0 and not debug_apk.exists():
            raise RuntimeError(f"debug APK 빌드에 실패했습니다. exit code: {exit_code}")

        if not ensure_within_root(debug_apk, workspace_path) or not debug_apk.exists() or debug_apk.stat().st_size <= 0:
            raise RuntimeError("debug APK 산출물을 찾을 수 없습니다.")

        log_build_stage_event(
            self.db,
            task_id,
            stage="debug apk",
            phase="succeeded",
            body="설치 가능한 디버그 APK를 준비했어요.",
            detail=f"소요 시간: {elapsed_seconds:.1f}초",
        )
        return debug_apk

    def optimized_download_apk_candidates(self, project_path: Path, current_apk_path: Path) -> list[Path]:
        flutter_apk_root = project_path / "build" / "app" / "outputs" / "flutter-apk"
        candidates = [
            current_apk_path,
            flutter_apk_root / "app-release.apk",
            flutter_apk_root / "app-arm64-v8a-release.apk",
        ]
        if flutter_apk_root.exists():
            candidates.extend(sorted(flutter_apk_root.glob("*arm64*v8a*release*.apk")))
            candidates.extend(sorted(flutter_apk_root.glob("*release*.apk")))

        seen: set[Path] = set()
        ordered: list[Path] = []
        for candidate in candidates:
            resolved = candidate.resolve()
            if resolved in seen:
                continue
            seen.add(resolved)
            ordered.append(resolved)
        return ordered

    def find_existing_optimized_download_apk(
        self,
        workspace_path: Path,
        project_path: Path,
        current_apk_path: Path,
    ) -> Optional[Path]:
        for candidate in self.optimized_download_apk_candidates(project_path, current_apk_path):
            if "release" not in candidate.name.lower():
                continue
            if ensure_within_root(candidate, workspace_path) and candidate.exists() and candidate.stat().st_size > 0:
                return candidate
        return None

    def ensure_download_apk(
        self,
        task_id: str,
        workspace_path: Path,
        project_path: Path,
        current_apk_path: Path,
    ) -> Path:
        version_changed = ensure_project_revision_version(project_path)
        if not self.settings.optimized_download_apk_enabled or self.settings.mock_codex:
            return self.ensure_debug_apk(task_id, workspace_path, project_path, current_apk_path)

        existing_optimized_apk = self.find_existing_optimized_download_apk(
            workspace_path,
            project_path,
            current_apk_path,
        )
        if existing_optimized_apk is not None and not version_changed:
            return existing_optimized_apk

        build_log_path = workspace_path / "logs" / "build.log"
        env = self.build_task_env(workspace_path)
        flutter_args = shlex.split(self.settings.flutter_command)
        ensure_release_uses_debug_signing(project_path)
        status_message = "다운로드용 APK를 최적화하고 있어요."
        did_update = self.db.update_task_if_status(
            task_id,
            {"running"},
            status="Running",
            message=status_message,
        )
        if not did_update:
            raise RuntimeError("앱 생성이 중단되었습니다.")
        running_task = self.db.get_task(task_id)
        if running_task:
            log_task_status_event(self.db, running_task, event_type="build_stage_release_prepare")
        log_build_stage_event(
            self.db,
            task_id,
            stage="다운로드용 APK 최적화",
            phase="started",
            body=status_message,
            detail="arm64 release APK를 생성합니다.",
        )
        exit_code, timed_out, elapsed_seconds = self.run_logged_command(
            flutter_args
            + ["build", "apk", "--release", "--target-platform", "android-arm64"]
            + flutter_no_pub_args(project_path),
            cwd=project_path,
            env=env,
            log_path=build_log_path,
            task_id=task_id,
        )
        if self.is_task_cancelled(task_id):
            raise RuntimeError("앱 생성이 중단되었습니다.")

        optimized_apk = self.find_existing_optimized_download_apk(
            workspace_path,
            project_path,
            current_apk_path,
        )
        if not timed_out and exit_code == 0 and optimized_apk is not None:
            size_mb = optimized_apk.stat().st_size / (1024 * 1024)
            log_build_stage_event(
                self.db,
                task_id,
                stage="다운로드용 APK 최적화",
                phase="succeeded",
                body="다운로드용 APK를 작게 준비했어요.",
                detail=f"{optimized_apk.name}, {size_mb:.1f}MB, 소요 시간: {elapsed_seconds:.1f}초",
            )
            return optimized_apk

        reason = "시간 제한 초과" if timed_out else f"종료 코드: {exit_code}"
        log_build_stage_event(
            self.db,
            task_id,
            stage="다운로드용 APK 최적화",
            phase="failed",
            body="다운로드용 APK 최적화에 실패해 debug APK로 대체합니다.",
            detail=f"{reason}, 소요 시간: {elapsed_seconds:.1f}초",
        )
        return self.ensure_debug_apk(task_id, workspace_path, project_path, current_apk_path)

    def prepare_saved_revision_apk(
        self,
        task: dict[str, Any],
        workspace_path: Path,
        project_path: Path,
    ) -> Path:
        expected_package_name = current_task_package_name(task)
        app_name = current_task_app_name(task)
        if not expected_package_name or not app_name:
            raise RuntimeError("Task 앱 식별 정보가 없습니다.")

        with self.sideload_build_lock:
            identity_changed = apply_project_defaults(
                project_path,
                str(task["task_id"]),
                app_name,
                expected_package_name,
            )
            identity_issues = project_android_identity_issues(project_path, expected_package_name)
            if identity_issues:
                raise RuntimeError("Android 앱 식별 정보를 복원하지 못했습니다.")
            version_changed = ensure_project_revision_version(project_path)
            existing_apk = find_revision_apk(workspace_path, project_path, task)
            if (
                existing_apk is not None
                and not identity_changed
                and not version_changed
            ):
                try:
                    validate_built_apk_install_contract(
                        project_path,
                        existing_apk,
                        expected_package_name,
                    )
                    return existing_apk
                except RuntimeError:
                    pass

            ensure_release_uses_debug_signing(project_path)
            build_log_path = workspace_path / "logs" / "revision_download_build.log"
            env = self.build_task_env(workspace_path)
            flutter_args = shlex.split(self.settings.flutter_command)
            commands: list[list[str]] = []
            if not flutter_no_pub_args(project_path):
                commands.append(flutter_args + ["pub", "get"])
            commands.append(
                flutter_args
                + [
                    "build",
                    "apk",
                    "--release",
                    "--target-platform",
                    "android-arm64",
                    "--build-number",
                    str(GENERATED_APK_SIDELOAD_VERSION_CODE),
                ]
                + flutter_no_pub_args(project_path)
            )

            for args in commands:
                exit_code, timed_out, _ = self.run_logged_command(
                    args,
                    cwd=project_path,
                    env=env,
                    log_path=build_log_path,
                )
                if timed_out:
                    raise RuntimeError("과거 버전 설치용 APK 준비 시간이 초과되었습니다.")
                if exit_code != 0:
                    raise RuntimeError(
                        f"과거 버전 설치용 APK 빌드에 실패했습니다. exit code: {exit_code}"
                    )

            prepared_apk = find_revision_apk(workspace_path, project_path, task)
            if prepared_apk is None:
                raise RuntimeError("과거 버전 설치용 APK 산출물을 찾을 수 없습니다.")
            validate_built_apk_install_contract(
                project_path,
                prepared_apk,
                expected_package_name,
            )
            return prepared_apk

    def attempt_server_side_build(
        self,
        task_id: str,
        workspace_path: Path,
        result_path: Path,
        codex_exit_code: Optional[int],
    ) -> None:
        task = self.db.get_task(task_id) or {}
        project_path = Path(str(task.get("project_path") or workspace_path / "project"))
        expected_app_name = current_task_app_name(task)
        expected_package_name = current_task_package_name(task)
        if expected_app_name and expected_package_name:
            apply_project_defaults(
                project_path,
                task_id,
                expected_app_name,
                expected_package_name,
            )
            identity_issues = project_android_identity_issues(project_path, expected_package_name)
            if identity_issues:
                build_log_path = workspace_path / "logs" / "build.log"
                build_log_path.parent.mkdir(parents=True, exist_ok=True)
                build_log_path.write_text(
                    "[server] Android project identity validation failed.\n"
                    + "\n".join(identity_issues)
                    + "\n",
                    encoding="utf-8",
                )
                message = (
                    "앱 내부 구성 정보가 일치하지 않아 빌드를 시작하지 못했어요. "
                    "요청 내용은 보존되어 있으니 다시 시도해 주세요."
                )
                log_build_stage_event(
                    self.db,
                    task_id,
                    stage="앱 식별 정보 확인",
                    phase="failed",
                    body=message,
                    detail=f"불일치 항목: {len(identity_issues)}개",
                )
                write_result_json(
                    result_path,
                    {
                        "status": "failed",
                        "task_id": task_id,
                        "error_stage": "identity",
                        "message": message,
                        "build_log_path": "logs/build.log",
                    },
                )
                return
        ensure_project_revision_version(project_path)
        ensure_release_uses_debug_signing(project_path)
        build_log_path = workspace_path / "logs" / "build.log"
        env = self.build_task_env(workspace_path)
        flutter_args = shlex.split(self.settings.flutter_command)
        stages = [
            ("pub_get", "Flutter 의존성을 설치하고 있어요.", flutter_args + ["pub", "get"]),
            (
                "analyze",
                "Flutter 코드를 분석하고 있어요.",
                flutter_args + ["analyze", "--no-pub", "--no-fatal-warnings", "--no-fatal-infos"],
            ),
            (
                "build",
                "Android APK를 빌드하고 있어요.",
                flutter_args
                + ["build", "apk", "--release", "--target-platform", "android-arm64", "--no-pub"],
            ),
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
            if self.is_task_cancelled(task_id):
                return
            did_update = self.db.update_task_if_status(
                task_id,
                {"running"},
                status="Running",
                message=status_message,
            )
            if not did_update:
                return
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
            exit_code, timed_out, elapsed_seconds = self.run_logged_command(
                args,
                cwd=project_path,
                env=env,
                log_path=build_log_path,
                task_id=task_id,
            )
            if self.is_task_cancelled(task_id):
                return
            if timed_out:
                log_build_stage_event(
                    self.db,
                    task_id,
                    stage=stage_labels[stage_key],
                    phase="failed",
                    body=f"Flutter {stage_labels[stage_key]} 단계가 시간 제한을 초과했어요.",
                    detail=f"소요 시간: {elapsed_seconds:.1f}초",
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
                    detail=f"종료 코드: {exit_code}, 소요 시간: {elapsed_seconds:.1f}초",
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
                detail=f"소요 시간: {elapsed_seconds:.1f}초",
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

        apk_relative = Path("project/build/app/outputs/flutter-apk/app-release.apk")
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

    def finalize_failure(
        self,
        task_id: str,
        *,
        message: str,
        log_text: str,
        usage_update_fields: dict[str, Optional[int]],
        usage: Optional[CodexUsage],
        codex_model: str,
        stage: str,
        event_type: str = "task_failed",
        status: str = "Failed",
        stage_body: Optional[str] = None,
        stage_detail: Optional[str] = None,
        codex_result_json: Optional[str] = None,
    ) -> None:
        update_fields: dict[str, Any] = {
            "status": status,
            "message": message,
            "log": log_text,
            **usage_update_fields,
        }
        if codex_result_json is not None:
            update_fields["codex_result_json"] = codex_result_json
        self.db.update_task(task_id, **update_fields)

        updated_task = self.db.get_task(task_id)
        if updated_task:
            log_task_status_event(self.db, updated_task, event_type=event_type)
            if usage:
                log_token_usage_event(self.db, task_id, usage, model=codex_model)
        log_build_stage_event(
            self.db,
            task_id,
            stage=stage,
            phase="failed",
            body=stage_body or message,
            detail=stage_detail,
        )

    def finalize_task(
        self,
        task_id: str,
        workspace_path: Path,
        result_path: Path,
        exit_code: Optional[int],
        timed_out: bool,
    ) -> None:
        task = self.db.get_task(task_id) or {}
        if self.is_task_cancelled(task_id):
            return
        task_state = load_task_state_payload(task)
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
            message = "앱 생성 작업 시간이 제한을 초과했습니다."
            self.finalize_failure(
                task_id,
                message=message,
                log_text=log_text,
                usage_update_fields=usage_update_fields,
                usage=usage,
                codex_model=codex_model,
                stage="앱 생성 작업",
                event_type="task_timeout",
                codex_result_json=json.dumps(
                    make_error_result(task_id, message),
                    ensure_ascii=False,
                ),
            )
            return

        if not result_path.exists():
            log_text = collect_task_logs(workspace_path, "logs/build.log", full=True)
            engine_issue = codex_engine_issue_from_logs(log_text, exit_code)
            if engine_issue is not None:
                status, message, event_type, stage = engine_issue
                self.finalize_failure(
                    task_id,
                    status=status,
                    message=message,
                    log_text=log_text,
                    usage_update_fields=usage_update_fields,
                    usage=usage,
                    codex_model=codex_model,
                    stage=stage,
                    event_type=event_type,
                    codex_result_json=json.dumps(
                        make_error_result(task_id, message),
                        ensure_ascii=False,
                    ),
                )
                return
            message = "결과 파일이 생성되지 않았습니다."
            if exit_code not in (0, None):
                message = f"{message} worker exit code: {exit_code}"
            self.finalize_failure(
                task_id,
                message=message,
                log_text=log_text,
                usage_update_fields=usage_update_fields,
                usage=usage,
                codex_model=codex_model,
                stage="결과 확인",
            )
            return

        try:
            result = json.loads(result_path.read_text(encoding="utf-8"))
        except json.JSONDecodeError as exc:
            log_text = collect_task_logs(workspace_path, "logs/build.log", full=True)
            message = f"task_result.json 파싱 실패: {exc.msg}"
            self.finalize_failure(
                task_id,
                message=message,
                log_text=log_text,
                usage_update_fields=usage_update_fields,
                usage=usage,
                codex_model=codex_model,
                stage="결과 확인",
                stage_body=f"결과 파일 파싱에 실패했어요: {exc.msg}",
                codex_result_json=result_path.read_text(encoding="utf-8", errors="replace"),
            )
            return

        branch_origin = task_state.get("branch_origin") if isinstance(task_state.get("branch_origin"), dict) else None
        if task_state.get("task_operation") == "branch_rebuild":
            branch_conversation_state = (
                task_state.get("conversation_state")
                if isinstance(task_state.get("conversation_state"), dict)
                else {}
            )
            result["task_operation"] = "branch_ready"
            result["conversation_state"] = branch_conversation_state
            if branch_origin:
                result["branch_origin"] = branch_origin
            if result.get("status") == "success":
                result["message"] = "선택한 버전에서 새 Task를 만들었어요."

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
                self.finalize_failure(
                    task_id,
                    message="성공 결과에 apk_path가 없습니다.",
                    log_text=log_text,
                    usage_update_fields=usage_update_fields,
                    usage=usage,
                    codex_model=codex_model,
                    stage="APK 결과 확인",
                    codex_result_json=codex_result_json,
                )
                return

            current_project_path = Path(str(task.get("project_path") or "")).resolve() if task.get("project_path") else None
            try:
                apk_path = resolve_task_artifact_path(workspace_path, str(apk_value), current_project_path)
            except ValueError:
                self.finalize_failure(
                    task_id,
                    message="apk_path가 workspace 밖을 가리킵니다.",
                    log_text=log_text,
                    usage_update_fields=usage_update_fields,
                    usage=usage,
                    codex_model=codex_model,
                    stage="APK 결과 확인",
                    codex_result_json=codex_result_json,
                )
                return

            if current_project_path is not None:
                try:
                    apk_path = self.ensure_download_apk(task_id, workspace_path, current_project_path, apk_path)
                    if not self.settings.mock_codex and persisted_package_name:
                        validate_built_apk_install_contract(
                            current_project_path,
                            apk_path,
                            persisted_package_name,
                        )
                except Exception as exc:
                    if self.is_task_cancelled(task_id):
                        return
                    message = f"설치 가능한 APK 준비 실패: {exc}"
                    self.finalize_failure(
                        task_id,
                        message=message,
                        log_text=log_text,
                        usage_update_fields=usage_update_fields,
                        usage=usage,
                        codex_model=codex_model,
                        stage="APK 준비",
                        stage_body=f"설치 가능한 APK를 준비하지 못했어요: {exc}",
                        codex_result_json=codex_result_json,
                    )
                    return
                result["apk_path"] = str(apk_path.relative_to(workspace_path).as_posix())
                codex_result_json = json.dumps(result, ensure_ascii=False)
                log_text = collect_task_logs(workspace_path, build_log_hint, full=True)
                if self.is_task_cancelled(task_id):
                    return

                if project_looks_like_placeholder_app(current_project_path):
                    message = "생성 결과가 기본 템플릿 화면에서 벗어나지 않았어요."
                    self.finalize_failure(
                        task_id,
                        message=message,
                        log_text=log_text,
                        usage_update_fields=usage_update_fields,
                        usage=usage,
                        codex_model=codex_model,
                        stage="결과 확인",
                        codex_result_json=codex_result_json,
                    )
                    return

            if apk_path.suffix.lower() != ".apk":
                self.finalize_failure(
                    task_id,
                    message="apk_path 확장자가 .apk가 아닙니다.",
                    log_text=log_text,
                    usage_update_fields=usage_update_fields,
                    usage=usage,
                    codex_model=codex_model,
                    stage="APK 결과 확인",
                    codex_result_json=codex_result_json,
                )
                return

            if not apk_path.exists() or apk_path.stat().st_size <= 0:
                self.finalize_failure(
                    task_id,
                    message="APK 파일이 없거나 비어 있습니다.",
                    log_text=log_text,
                    usage_update_fields=usage_update_fields,
                    usage=usage,
                    codex_model=codex_model,
                    stage="APK 결과 확인",
                    codex_result_json=codex_result_json,
                )
                return

            apk_url = f"{self.settings.server_base_url}/download/{task_id}"
            success_message = (
                "선택한 버전에서 새 Task를 만들었어요."
                if task_state.get("task_operation") == "branch_rebuild"
                else "APK 빌드가 완료되었어요."
            )
            self.db.update_task(
                task_id,
                status="Success",
                message=success_message,
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
            updated_task = self.db.get_task(task_id)
            if updated_task:
                log_task_status_event(self.db, updated_task, event_type="task_succeeded")
                if usage:
                    log_token_usage_event(self.db, task_id, usage, model=codex_model)
                log_package_name_event(
                    self.db,
                    task_id,
                    package_name=str(updated_task.get("package_name") or result.get("package_name") or ""),
                    app_name=str(updated_task.get("app_name") or result.get("app_name") or ""),
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
            message = str(result.get("message", "앱 생성에 실패했습니다."))
            self.finalize_failure(
                task_id,
                message=message,
                codex_result_json=codex_result_json,
                log_text=log_text,
                usage_update_fields=usage_update_fields,
                usage=usage,
                codex_model=codex_model,
                stage="앱 생성 결과",
            )
            return

        message = "task_result.json status 값이 올바르지 않습니다."
        self.finalize_failure(
            task_id,
            message=message,
            codex_result_json=codex_result_json,
            log_text=log_text,
            usage_update_fields=usage_update_fields,
            usage=usage,
            codex_model=codex_model,
            stage="앱 생성 결과",
            stage_body="결과 상태 값이 올바르지 않습니다.",
        )


def build_task_workspace(settings: Settings, task: dict[str, Any]) -> tuple[Path, Path]:
    safe_user_id = sanitize_component(task["user_id"])
    safe_task_id = sanitize_component(task["task_id"])

    user_root = settings.workspaces_root / f"user_{safe_user_id}"
    task_root = user_root / f"task_{safe_task_id}"
    logs_root = task_root / "logs"
    result_root = task_root / ".codex_result"

    if task_root.exists() and ((task_root / "project").exists() or (task_root / "revisions").exists()):
        raise RuntimeError(f"task workspace already exists: {task_root}")

    settings.workspaces_root.mkdir(parents=True, exist_ok=True)
    user_root.mkdir(parents=True, exist_ok=True)
    logs_root.mkdir(parents=True, exist_ok=True)
    result_root.mkdir(parents=True, exist_ok=True)

    if not settings.base_project_path.exists() or not settings.base_project_path.is_dir():
        raise RuntimeError(
            f"BASE_PROJECT_PATH does not exist or is not a directory: {settings.base_project_path}"
        )

    project_root, _ = create_initial_project_revision(
        task_root,
        settings.base_project_path,
        android_only=settings.android_only_workspace_enabled,
    )
    reference_attachments = normalize_reference_attachments(task.get("reference_attachments") or [])
    if not reference_attachments:
        reference_attachments = [
            {
                "type": "image",
                "mime_type": "",
                "name": normalize_reference_image_name(task.get("reference_image_name")),
                "base64": normalize_reference_image_base64(task.get("reference_image_base64")),
                "workspace_path": "",
            }
        ]
    saved_reference_attachments = save_reference_attachments(task_root, reference_attachments)
    first_attachment = first_reference_attachment(saved_reference_attachments)
    reference_image_workspace_path = first_attachment.get("workspace_path") or ""
    if saved_reference_attachments:
        task["reference_attachments"] = saved_reference_attachments
    if reference_image_workspace_path:
        task["reference_image_workspace_path"] = reference_image_workspace_path
    if task.get("app_name") and task.get("package_name"):
        apply_project_defaults(project_root, task["task_id"], task["app_name"], task["package_name"])
    (task_root / "AGENTS.md").write_text(render_task_agents_md(task["task_id"]), encoding="utf-8")
    (task_root / "prompt.md").write_text(render_prompt_md(task, settings), encoding="utf-8")

    return task_root, project_root


def serialize_task_for_status(
    db: Database,
    task: dict[str, Any],
    log_line_limit: int,
    *,
    include_logs: bool = False,
    include_timeline: bool = True,
    timeline_after_event_id: Optional[str] = None,
) -> dict[str, Any]:
    if include_logs:
        log_text, log_lines = collect_live_task_logs(task, log_line_limit)
    else:
        log_text = ""
        log_lines = tail_lines(str(task.get("log") or ""), 1)
    success = task["status"] == "Success"
    status_text = status_display_text(task["status"], task.get("message"))
    timeline_events = (
        build_task_timeline_events(
            db,
            str(task["task_id"]),
            after_event_id=timeline_after_event_id,
        )
        if include_timeline
        else []
    )
    timeline_cursor = db.latest_event_id(str(task["task_id"]))
    current_build_stage, current_build_stage_detail = derive_current_build_stage(task, timeline_events)
    raw_log_sections: list[dict[str, str]] = []
    workspace_value = (task.get("workspace_path") or "").strip()
    if include_logs and workspace_value:
        workspace_root = Path(workspace_value)
        if workspace_root.exists() and workspace_root.is_dir():
            raw_log_sections = collect_raw_log_sections(workspace_root, "logs/build.log")
    state_payload = load_task_state_payload(task)
    conversation_state = build_task_conversation_state(task)
    task_message = sanitize_user_visible_text(str(task.get("message") or ""))
    latest_assistant_message = sanitize_user_visible_text(str(state_payload.get("message") or task_message))
    latest_assistant_message_type = str(state_payload.get("tool") or "status")
    latest_failure_message = (
        task_message if task["status"] in {"Failed", "Error"} else sanitize_user_visible_text(str(state_payload.get("latest_failure_message") or ""))
    )
    retry_allowed = task["status"] in {"Failed", "Error"}
    cancel_allowed = is_cancellable_task_status(str(task.get("status") or ""))
    allowed_next_actions = []
    if retry_allowed:
        allowed_next_actions.append("retry")
    if cancel_allowed:
        allowed_next_actions.append("cancel")
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
        "prepared_prompt": str(
            state_payload.get("prepared_prompt")
            or conversation_state.get("prepared_prompt")
            or ""
        ),
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
        "timeline_cursor": timeline_cursor,
        "raw_log_sections": raw_log_sections,
        "interaction_type": str(state_payload.get("interaction_type") or ""),
        "render_mode": str(state_payload.get("render_mode") or ""),
        "requires_user_input": bool(state_payload.get("requires_user_input")),
        "requires_confirmation": bool(state_payload.get("requires_confirmation")),
        "pending_decision_reason": str(state_payload.get("pending_decision_reason") or ""),
        "suppress_assistant_bubble": bool(state_payload.get("suppress_assistant_bubble")),
        "retry_allowed": retry_allowed,
        "cancel_allowed": cancel_allowed,
        "allowed_next_actions": allowed_next_actions,
        "conversation_state": conversation_state,
        "created_at": task["created_at"],
        "updated_at": task["updated_at"],
    }


def revision_version_name(revision_label: str) -> str:
    revision_number = revision_number_from_label(revision_label)
    return f"v{revision_number}" if revision_number > 0 else revision_label


def revision_request_summary(
    task: dict[str, Any],
    snapshot: dict[str, Any],
    events: list[dict[str, Any]],
) -> str:
    source = normalize_whitespace(str(snapshot.get("source") or "")).lower()
    if source == "new_app":
        return "최초 앱 생성"
    if source == "runtime_repair":
        return "감지된 실행 오류 자동 복구"
    if source == "branched_revision":
        return "선택한 버전에서 새 Task로 분기"
    stored_summary = normalize_whitespace(str(snapshot.get("request_summary") or ""))
    if stored_summary:
        return compact_revision_request_summary(stored_summary)

    snapshot_time = parse_iso_datetime(str(snapshot.get("created_at") or ""))
    eligible_events: list[dict[str, Any]] = []
    for event in events:
        event_time = parse_iso_datetime(str(event.get("created_at") or ""))
        if snapshot_time is not None and event_time is not None and event_time > snapshot_time:
            continue
        eligible_events.append(event)

    for event in reversed(eligible_events):
        if str(event.get("event_type") or "").strip().lower() != "agent_raw_output":
            continue
        raw_text = str(event.get("message_text") or "").strip()
        try:
            parsed = json.loads(raw_text)
        except json.JSONDecodeError:
            continue
        if not isinstance(parsed, dict):
            continue
        summary = normalize_whitespace(str(parsed.get("effective_user_prompt") or ""))
        if summary:
            return compact_revision_request_summary(summary)

    ignored_user_messages = {
        "만들어진 프롬프트대로 생성요청 문구를 보냈어요",
        "만들어진 프롬프트대로 생성 요청 문구를 보냈어요",
    }
    for event in reversed(eligible_events):
        if str(event.get("actor") or "").strip().lower() != "user":
            continue
        if str(event.get("event_type") or "").strip().lower() != "user_message":
            continue
        summary = normalize_whitespace(str(event.get("message_text") or ""))
        if not summary or summary in ignored_user_messages:
            continue
        return compact_revision_request_summary(summary)

    if source in {"existing_app_modification", "existing_app", "modification"}:
        return "사용자 수정 요청 반영"
    prompt = normalize_whitespace(str(task.get("build_request_prompt") or task.get("prompt") or ""))
    return compact_revision_request_summary(prompt) if prompt else "앱 버전 생성"


def parse_iso_datetime(value: str) -> Optional[datetime]:
    normalized = value.strip()
    if not normalized:
        return None
    try:
        parsed = datetime.fromisoformat(normalized.replace("Z", "+00:00"))
    except ValueError:
        return None
    if parsed.tzinfo is None:
        return parsed.replace(tzinfo=timezone.utc)
    return parsed.astimezone(timezone.utc)


def compact_revision_request_summary(value: str, *, limit: int = 320) -> str:
    sanitized = normalize_whitespace(sanitize_codex_followup_user_text(value))
    if len(sanitized) <= limit:
        return sanitized
    return sanitized[: limit - 1].rstrip() + "…"


def find_revision_apk(workspace_path: Path, project_path: Path, task: dict[str, Any]) -> Optional[Path]:
    candidates: list[Path] = []
    task_apk_path = normalize_whitespace(str(task.get("apk_path") or ""))
    task_project_path = normalize_whitespace(str(task.get("project_path") or ""))
    if task_apk_path and task_project_path and Path(task_project_path).resolve() == project_path.resolve():
        candidates.append(Path(task_apk_path))
    candidates.extend(
        [
            project_path / "build" / "app" / "outputs" / "flutter-apk" / "app-release.apk",
            project_path / "build" / "app" / "outputs" / "flutter-apk" / "app-arm64-v8a-release.apk",
            project_path / "build" / "app" / "outputs" / "flutter-apk" / "app-debug.apk",
            project_path / "build" / "app" / "outputs" / "apk" / "debug" / "app-debug.apk",
        ]
    )
    flutter_apk_root = project_path / "build" / "app" / "outputs" / "flutter-apk"
    if flutter_apk_root.exists():
        candidates.extend(sorted(flutter_apk_root.glob("*release*.apk")))
        candidates.extend(sorted(flutter_apk_root.glob("*.apk")))
    for candidate in candidates:
        apk_path = candidate if candidate.is_absolute() else workspace_path / candidate
        apk_path = apk_path.resolve()
        if ensure_within_root(apk_path, workspace_path) and apk_path.exists() and apk_path.is_file() and apk_path.suffix.lower() == ".apk":
            return apk_path
    return None


def project_path_for_apk_artifact(
    task: dict[str, Any],
    snapshots: list[dict[str, Any]],
    apk_path: Path,
) -> Optional[Path]:
    workspace_value = normalize_whitespace(str(task.get("workspace_path") or ""))
    if not workspace_value:
        return None
    workspace_path = Path(workspace_value).resolve()
    project_values = [
        str(task.get("project_path") or ""),
        *(str(snapshot.get("project_path") or "") for snapshot in snapshots),
    ]
    seen: set[Path] = set()
    for project_value in project_values:
        if not normalize_whitespace(project_value):
            continue
        project_path = Path(project_value).resolve()
        if project_path in seen:
            continue
        seen.add(project_path)
        if (
            ensure_within_root(project_path, workspace_path)
            and ensure_within_root(apk_path, project_path)
            and (project_path / "pubspec.yaml").is_file()
        ):
            return project_path
    return None


def serialize_project_revision(
    task: dict[str, Any],
    snapshot: dict[str, Any],
    *,
    request_summary: str = "",
) -> dict[str, Any]:
    workspace_path = Path(str(snapshot.get("workspace_path") or task.get("workspace_path") or "")).resolve()
    project_path = Path(str(snapshot.get("project_path") or "")).resolve()
    revision_label = str(snapshot.get("revision_label") or current_revision_label(project_path))
    apk_path = find_revision_apk(workspace_path, project_path, task) if workspace_path.exists() and project_path.exists() else None
    artifact_path = ""
    if apk_path is not None:
        try:
            artifact_path = str(apk_path.relative_to(workspace_path).as_posix())
        except ValueError:
            artifact_path = str(apk_path)
    task_project_path = normalize_whitespace(str(task.get("project_path") or ""))
    is_current = bool(task_project_path) and Path(task_project_path).resolve() == project_path
    return {
        "snapshot_id": str(snapshot.get("snapshot_id") or ""),
        "task_id": str(task.get("task_id") or snapshot.get("task_id") or ""),
        "revision_label": revision_label,
        "version_name": revision_version_name(revision_label),
        "source": str(snapshot.get("source") or ""),
        "created_at": str(snapshot.get("created_at") or ""),
        "request_summary": request_summary,
        "apk_path": artifact_path,
        "apk_url": f"/download/{task.get('task_id')}" if artifact_path else "",
        "apk_size_bytes": apk_path.stat().st_size if apk_path is not None else None,
        "has_apk": apk_path is not None,
        "can_branch": (
            workspace_path.exists()
            and workspace_path.is_dir()
            and project_path.exists()
            and project_path.is_dir()
            and ensure_within_root(project_path, workspace_path)
        ),
        "is_current": is_current,
    }


def serialize_task_summary(task: dict[str, Any]) -> dict[str, Any]:
    success = task["status"] == "Success"
    state_payload = load_task_state_payload(task)
    raw_stored_conversation_state = state_payload.get("conversation_state")
    stored_conversation_state: dict[str, Any] = (
        raw_stored_conversation_state
        if isinstance(raw_stored_conversation_state, dict)
        else {}
    )
    request_scope = normalize_whitespace(
        str(stored_conversation_state.get("request_scope") or state_payload.get("request_scope") or "")
    )
    if request_scope not in {"new_app", "existing_app_modification", "non_app_request"}:
        request_scope = "existing_app_modification" if task_has_app_context(task, state_payload) else "new_app"
    if task_has_app_context(task, state_payload) and request_scope == "non_app_request":
        request_scope = "existing_app_modification"
    latest_summary = sanitize_user_visible_text(
        str(stored_conversation_state.get("latest_summary") or state_payload.get("latest_summary") or "")
    ).strip()[:500]
    initial_user_prompt = sanitize_user_visible_text(str(task.get("prompt") or "")).strip()[:500]
    return {
        "task_id": task["task_id"],
        "status": task["status"],
        "status_display_text": status_display_text(task["status"], task.get("message")),
        "prompt": initial_user_prompt,
        "initial_user_prompt": initial_user_prompt,
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
        "last_bubble_at": task.get("last_bubble_at") or task["updated_at"],
        "conversation_state": {
            "request_scope": request_scope,
            "latest_summary": latest_summary,
        },
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


def require_app_data_package(db: Database, task_id: str, package_name: str) -> tuple[dict[str, Any], str]:
    task = db.get_task(task_id)
    if not task:
        raise HTTPException(status_code=404, detail="task not found")
    normalized_package_name = package_name.strip()
    if not normalized_package_name:
        raise HTTPException(status_code=400, detail="package_name is required")
    expected_package_name = str(task.get("package_name") or "").strip()
    if expected_package_name and normalized_package_name != expected_package_name:
        raise HTTPException(status_code=403, detail="package name mismatch")
    return task, normalized_package_name


@asynccontextmanager
async def lifespan(app: FastAPI):
    settings = load_settings()
    db = Database(settings.db_path)
    db.init_db()
    app_data_db = AppDataDatabase(settings.app_data_db_path)
    app_data_db.init_db()
    runner = CodexTaskRunner(settings, db)
    runner.start()

    app.state.settings = settings
    app.state.db = db
    app.state.app_data_db = app_data_db
    app.state.runner = runner
    try:
        yield
    finally:
        runner.stop()


def create_app() -> FastAPI:
    app = FastAPI(title="Flutter APK Builder Server", lifespan=lifespan)
    dashboard_module_name = (
        f"{__package__}.admin_dashboard"
        if __package__
        else "admin_dashboard"
    )
    dashboard_module = importlib.import_module(dashboard_module_name)
    dashboard_module.register_admin_dashboard_routes(app, require_admin_token)

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
        requested_reference_attachments = request_reference_attachments(request)
        requested_first_reference = first_reference_attachment(requested_reference_attachments)
        requested_reference_image_name = normalize_reference_image_name(
            requested_first_reference.get("name") or request.reference_image_name
        )
        requested_reference_image_base64 = normalize_reference_image_base64(
            requested_first_reference.get("base64") or request.reference_image_base64
        )
        if not request.prompt.strip() and not requested_reference_attachments:
            raise HTTPException(status_code=422, detail="prompt or image attachment is required")
        followup_task_id = (request.task_id or "").strip()
        request_action = normalize_whitespace(str(request.request_action or ""))

        if followup_task_id:
            task = db.get_task(followup_task_id)
            if not task:
                raise HTTPException(status_code=404, detail="task not found")
            if not is_task_access_allowed(task, device_id=request.device_id, phone_number=request.phone_number):
                raise HTTPException(status_code=404, detail="task not found")
            if is_generate_blocked_task_status(str(task.get("status") or "")):
                raise HTTPException(status_code=409, detail="task already in progress")
            previous_task_for_context = dict(task)
            previous_conversation_state = build_task_conversation_state(previous_task_for_context)
            existing_workspace_ready = bool(previous_task_for_context.get("workspace_path") and previous_task_for_context.get("project_path"))
            is_initial_prompt_submission = request_action == "submit_initial_prompt"
            if is_initial_prompt_submission and (
                existing_workspace_ready
                or not bool(previous_conversation_state.get("awaiting_prompt_review"))
                or previous_conversation_state.get("confirmation_action") != "submit_initial_prompt"
            ):
                raise HTTPException(status_code=409, detail="task is not waiting for initial prompt review")
            db.update_task(
                followup_task_id,
                status="Pending Decision",
                message="요청을 검토하고 있어요.",
                device_id=request.device_id,
                phone_number=request.phone_number,
            )
            pending_decision_task = db.get_task(followup_task_id)
            if pending_decision_task:
                task = pending_decision_task
                log_task_status_event(db, pending_decision_task)
            visible_user_prompt = (
                request.prompt
                if request.display_prompt is None
                else request.display_prompt
            )
            user_event_id = db.log_event(
                followup_task_id,
                actor="user",
                event_type="user_message",
                message_text=(
                    "만들어진 프롬프트대로 생성요청 문구를 보냈어요"
                    if is_initial_prompt_submission
                    else visible_user_prompt
                ),
                payload={
                    "task_id": followup_task_id,
                    "device_id": request.device_id,
                    "phone_number": request.phone_number,
                    "raw_prompt": request.prompt,
                    "display_prompt": (
                        "만들어진 프롬프트대로 생성요청 문구를 보냈어요"
                        if is_initial_prompt_submission
                        else visible_user_prompt
                    ),
                    "request_action": request_action,
                    "final_generation_prompt": request.prompt if is_initial_prompt_submission else "",
                    "attachment_count": len(requested_reference_attachments),
                    "attachments": [
                        reference_attachment_event_payload(attachment)
                        for attachment in requested_reference_attachments
                    ],
                },
            )
            if requested_reference_attachments:
                try:
                    saved_requested_attachments = persist_reference_attachments_for_task(
                        db,
                        settings,
                        task,
                        requested_reference_attachments,
                        source="followup_request",
                        event_id=user_event_id,
                        fail_on_error=True,
                    )
                except ValueError as exc:
                    raise HTTPException(status_code=400, detail=f"attachment save failed: {exc}") from exc
                if saved_requested_attachments:
                    requested_reference_attachments = saved_requested_attachments
            previous_reference_attachments = normalize_reference_attachments(
                previous_conversation_state.get("reference_attachments") or []
            )
            effective_reference_attachments = requested_reference_attachments or previous_reference_attachments
            effective_first_reference = first_reference_attachment(effective_reference_attachments)
            effective_reference_image_name = requested_reference_image_name or normalize_reference_image_name(
                effective_first_reference.get("name") or previous_conversation_state.get("reference_image_name")
            )
            effective_reference_image_base64 = requested_reference_image_base64 or normalize_reference_image_base64(
                effective_first_reference.get("base64") or previous_conversation_state.get("reference_image_base64")
            )
            existing_workspace_ready = bool(task.get("workspace_path") and task.get("project_path"))
            codex_followup_enabled = (
                settings.codex_existing_task_followup_enabled
                and not settings.mock_codex
                and existing_workspace_ready
                and not is_initial_prompt_submission
            )
            if is_initial_prompt_submission:
                decision = build_initial_prompt_submission_decision(
                    task_id=followup_task_id,
                    final_prompt=request.prompt,
                    previous_conversation_state=previous_conversation_state,
                )
                previous_conversation_state = {
                    **previous_conversation_state,
                    "awaiting_prompt_review": False,
                    "awaiting_confirmation": False,
                    "final_generation_prompt": request.prompt,
                    "latest_effective_user_prompt": request.prompt,
                    "pending_user_prompt": "",
                    "pending_normalized_prompt": "",
                    "confirmation_action": "",
                    "confirmation_payload": "",
                }
            elif codex_followup_enabled:
                codex_followup_payload = run_codex_existing_task_followup_decision(
                    settings,
                    db,
                    task,
                    prompt=request.prompt,
                    previous_conversation_state=previous_conversation_state,
                    device_info=request_device_info or previous_conversation_state.get("device_info"),
                    reference_image_name=effective_reference_image_name,
                    reference_attachments=effective_reference_attachments,
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
                    current_app_name = current_task_app_name(task, previous_conversation_state)
                    codex_change_summary = build_codex_followup_build_summary(
                        current_app_name,
                        str((codex_followup_payload or {}).get("change_summary") or ""),
                        request.prompt,
                    )
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
                        suggested_app_name=current_app_name,
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
                        user_visible_summary=codex_change_summary,
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
            cancelled_task = db.get_task(followup_task_id)
            if cancelled_task and is_cancelled_task_status(str(cancelled_task.get("status") or "")):
                return serialize_task_for_status(db, cancelled_task, settings.status_log_line_limit)
            if effective_reference_image_name and not decision.image_reference_summary:
                decision = replace(
                    decision,
                    image_reference_summary=reference_attachments_summary(effective_reference_attachments)
                    or build_reference_image_summary(effective_reference_image_name),
                )
            decision = preserve_followup_task_identity(decision, task, previous_conversation_state)
            if not existing_workspace_ready and not is_initial_prompt_submission:
                decision = build_initial_prompt_review_decision(decision)
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
                cancelled_task = db.get_task(followup_task_id)
                if cancelled_task and is_cancelled_task_status(str(cancelled_task.get("status") or "")):
                    return serialize_task_for_status(db, cancelled_task, settings.status_log_line_limit)
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
                                "reference_attachments": effective_reference_attachments,
                                "conversation_state_override": previous_conversation_state,
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
            saved_reference_attachments: list[dict[str, str]] = []
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
                    "reference_attachments": effective_reference_attachments,
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
                saved_reference_attachments = normalize_reference_attachments(
                    build_task.get("reference_attachments") or []
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
                    request_summary=decision.effective_user_prompt,
                )
            else:
                workspace_path_obj = Path(workspace_path_value)
                project_path_obj, revision_label = create_followup_project_revision(
                    workspace_path_obj,
                    Path(project_path_value),
                    android_only=settings.android_only_workspace_enabled,
                )
                project_path_value = str(project_path_obj)
                saved_reference_attachments = save_reference_attachments(
                    workspace_path_obj,
                    effective_reference_attachments,
                )
                saved_first_reference = first_reference_attachment(saved_reference_attachments)
                reference_image_workspace_path = saved_first_reference.get("workspace_path") or ""
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
                    request_summary=decision.effective_user_prompt,
                )

            cancelled_task = db.get_task(followup_task_id)
            if cancelled_task and is_cancelled_task_status(str(cancelled_task.get("status") or "")):
                return serialize_task_for_status(db, cancelled_task, settings.status_log_line_limit)
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
                normalized_prompt=decision.normalized_prompt,
                build_request_prompt=decision.effective_user_prompt,
                codex_result_json=json.dumps(
                    make_decision_state(
                        {
                            **task,
                            "device_info": request_device_info or previous_conversation_state.get("device_info") or {},
                            "reference_image_name": effective_reference_image_name,
                            "reference_image_base64": effective_reference_image_base64,
                            "reference_image_workspace_path": reference_image_workspace_path or previous_conversation_state.get("reference_image_workspace_path") or "",
                            "reference_attachments": saved_reference_attachments or effective_reference_attachments,
                            "conversation_state_override": previous_conversation_state,
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
                image_reference_summary=reference_attachments_summary(requested_reference_attachments)
                or build_reference_image_summary(requested_reference_image_name),
            )
        decision = build_initial_prompt_review_decision(decision)
        task = {
            "task_id": task_id,
            "user_id": resolved_user_id,
            "device_id": request.device_id,
            "phone_number": request.phone_number,
            "prompt": request.prompt,
            "device_info": request_device_info,
            "reference_image_name": requested_reference_image_name,
            "reference_image_base64": requested_reference_image_base64,
            "reference_attachments": requested_reference_attachments,
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
                        "reference_attachments": requested_reference_attachments,
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
                "attachment_count": len(requested_reference_attachments),
            },
        )
        visible_user_prompt = (
            request.prompt
            if request.display_prompt is None
            else request.display_prompt
        )
        user_event_id = db.log_event(
            task_id,
            actor="user",
            event_type="user_message",
            message_text=visible_user_prompt,
            payload={
                "task_id": task_id,
                "device_id": request.device_id,
                "phone_number": request.phone_number,
                "raw_prompt": request.prompt,
                "display_prompt": visible_user_prompt,
                "attachment_count": len(requested_reference_attachments),
                "attachments": [
                    reference_attachment_event_payload(attachment)
                    for attachment in requested_reference_attachments
                ],
            },
        )
        if requested_reference_attachments:
            try:
                saved_requested_attachments = persist_reference_attachments_for_task(
                    db,
                    settings,
                    task,
                    requested_reference_attachments,
                    source="initial_request",
                    event_id=user_event_id,
                    fail_on_error=True,
                )
            except ValueError as exc:
                db.update_task(
                    task_id,
                    status="Error",
                    message=f"첨부 이미지 저장 실패: {exc}",
                )
                failed_task = db.get_task(task_id)
                if failed_task:
                    log_task_status_event(db, failed_task, event_type="task_error")
                raise HTTPException(status_code=400, detail=f"attachment save failed: {exc}") from exc
            if saved_requested_attachments:
                requested_reference_attachments = saved_requested_attachments
                task["reference_attachments"] = saved_requested_attachments
                first_saved_reference = first_reference_attachment(saved_requested_attachments)
                task["reference_image_workspace_path"] = first_saved_reference.get("workspace_path") or ""
                task["reference_image_base64"] = ""
                task["codex_result_json"] = json.dumps(
                    make_decision_state(task, decision, request.prompt),
                    ensure_ascii=False,
                )
                db.update_task(task_id, codex_result_json=task["codex_result_json"])
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
                request_summary=decision.effective_user_prompt,
            )
            runner.enqueue(task_id)

        return build_decision_response(task_id, decision)

    @app.get("/status/{task_id}")
    def get_status(
        task_id: str,
        device_id: Optional[str] = Query(default=None),
        phone_number: Optional[str] = Query(default=None),
        user_id: Optional[str] = Query(default=None),
        include_logs: bool = Query(default=False),
        include_timeline: bool = Query(default=True),
        timeline_after_event_id: Optional[str] = Query(default=None),
    ) -> dict[str, Any]:
        db: Database = app.state.db
        settings: Settings = app.state.settings
        task = db.get_task(task_id)
        if not task:
            raise HTTPException(status_code=404, detail="task not found")
        if not is_task_access_allowed(task, device_id=device_id, phone_number=phone_number):
            raise HTTPException(status_code=404, detail="task not found")
        return serialize_task_for_status(
            db,
            task,
            settings.status_log_line_limit,
            include_logs=include_logs,
            include_timeline=include_timeline,
            timeline_after_event_id=timeline_after_event_id,
        )

    @app.post("/tasks/{task_id}/cancel")
    def cancel_task(
        task_id: str,
        device_id: Optional[str] = Query(default=None),
        phone_number: Optional[str] = Query(default=None),
        user_id: Optional[str] = Query(default=None),
    ) -> dict[str, Any]:
        _ = user_id
        db: Database = app.state.db
        settings: Settings = app.state.settings
        runner: CodexTaskRunner = app.state.runner
        task = db.get_task(task_id)
        if not task:
            raise HTTPException(status_code=404, detail="task not found")
        if not is_task_access_allowed(task, device_id=device_id, phone_number=phone_number):
            raise HTTPException(status_code=404, detail="task not found")
        if is_cancelled_task_status(str(task.get("status") or "")):
            return serialize_task_for_status(db, task, settings.status_log_line_limit)
        if not is_cancellable_task_status(str(task.get("status") or "")):
            raise HTTPException(status_code=409, detail="task is not cancellable")
        updated_task = runner.cancel_task(task_id)
        return serialize_task_for_status(db, updated_task, settings.status_log_line_limit)

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
            db.log_event(
                task_id,
                actor="system",
                event_type="app_llm_request_rejected",
                message_text="package name mismatch",
                payload={
                    "reason": "package_name_mismatch",
                    "expected_package_name": expected_package_name,
                    "received_package_name": request.package_name.strip(),
                },
            )
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

        runtime_instructions = build_app_runtime_instructions(config, request)
        request_event_id = db.log_event(
            task_id,
            actor="user",
            event_type="app_llm_request",
            message_text=request.user_message,
            payload={
                "package_name": request.package_name.strip(),
                "model": config.get("model") or "",
                "system_prompt": str(config.get("system_prompt") or ""),
                "runtime_instructions": runtime_instructions,
                "user_message": request.user_message,
                "context": request.context or "",
                "image_attached": bool(request.image_base64 and request.image_base64.strip()),
                "image_mime_type": request.image_mime_type or "",
            },
        )
        if request.image_base64 and request.image_base64.strip():
            runtime_image_mime_type = (request.image_mime_type or "image/jpeg").strip() or "image/jpeg"
            runtime_image_suffix = ".png" if runtime_image_mime_type.lower() == "image/png" else ".jpg"
            try:
                persist_reference_attachments_for_task(
                    db,
                    settings,
                    task,
                    [
                        {
                            "type": "image",
                            "mime_type": runtime_image_mime_type,
                            "name": f"app_llm_{utc_now_compact()}{runtime_image_suffix}",
                            "base64": request.image_base64,
                            "workspace_path": "",
                        }
                    ],
                    source="app_llm_runtime_request",
                    event_id=request_event_id,
                    fail_on_error=True,
                )
            except ValueError as exc:
                db.log_event(
                    task_id,
                    actor="system",
                    event_type="app_llm_request_rejected",
                    message_text=str(exc),
                    payload={
                        "request_event_id": request_event_id,
                        "error_type": "attachment_save_failed",
                        "error": str(exc),
                    },
                )
                raise HTTPException(status_code=400, detail="attached image could not be saved") from exc

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
            db.log_event(
                task_id,
                actor="system",
                event_type="app_llm_error",
                message_text=str(exc),
                payload={
                    "request_event_id": request_event_id,
                    "error_type": "configuration_error",
                    "error": str(exc),
                },
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
            upstream_response_text = exc.response.text
            db.log_event(
                task_id,
                actor="system",
                event_type="app_llm_error",
                message_text=upstream_response_text or str(exc),
                payload={
                    "request_event_id": request_event_id,
                    "error_type": "upstream_http_error",
                    "status_code": exc.response.status_code,
                    "response_text": upstream_response_text,
                },
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
            db.log_event(
                task_id,
                actor="system",
                event_type="app_llm_error",
                message_text=str(exc),
                payload={
                    "request_event_id": request_event_id,
                    "error_type": "network_error",
                    "error": str(exc),
                },
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
            db.log_event(
                task_id,
                actor="system",
                event_type="app_llm_response_rejected",
                message_text=str(model_response.get("message") or ""),
                payload={
                    "request_event_id": request_event_id,
                    "error_type": "daily_token_limit_exceeded",
                    "usage": usage,
                    "raw_response": model_response.get("raw_response") or {},
                },
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
        db.log_event(
            task_id,
            actor="system",
            event_type="app_llm_response",
            message_text=str(model_response.get("message") or ""),
            payload={
                "request_event_id": request_event_id,
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
                "raw_response": model_response.get("raw_response") or {},
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

    @app.get("/apps/{task_id}/data/{collection}")
    def list_app_data_records(
        task_id: str,
        collection: str,
        package_name: str = Query(..., min_length=1),
        owner_id: Optional[str] = Query(default=None),
        include_deleted: bool = Query(default=False),
        limit: int = Query(default=100, ge=1, le=1000),
    ) -> dict[str, Any]:
        db: Database = app.state.db
        app_data_db: AppDataDatabase = app.state.app_data_db
        _, normalized_package_name = require_app_data_package(db, task_id, package_name)
        normalized_collection = normalize_app_data_collection(collection)
        records = app_data_db.list_records(
            task_id=task_id,
            package_name=normalized_package_name,
            collection=normalized_collection,
            owner_id=normalize_app_data_owner_id(owner_id),
            include_deleted=include_deleted,
            limit=limit,
        )
        return {
            "task_id": task_id,
            "package_name": normalized_package_name,
            "collection": normalized_collection,
            "records": records,
        }

    @app.post("/apps/{task_id}/data/{collection}")
    def create_app_data_record(
        task_id: str,
        collection: str,
        request: AppDataCreateRequest,
    ) -> dict[str, Any]:
        db: Database = app.state.db
        app_data_db: AppDataDatabase = app.state.app_data_db
        _, normalized_package_name = require_app_data_package(db, task_id, request.package_name)
        normalized_collection = normalize_app_data_collection(collection)
        record = app_data_db.create_record(
            task_id=task_id,
            package_name=normalized_package_name,
            collection=normalized_collection,
            owner_id=normalize_app_data_owner_id(request.owner_id),
            data=request.data,
        )
        return {"record": record}

    @app.get("/apps/{task_id}/data/{collection}/{record_id}")
    def get_app_data_record(
        task_id: str,
        collection: str,
        record_id: str,
        package_name: str = Query(..., min_length=1),
        include_deleted: bool = Query(default=False),
    ) -> dict[str, Any]:
        db: Database = app.state.db
        app_data_db: AppDataDatabase = app.state.app_data_db
        _, normalized_package_name = require_app_data_package(db, task_id, package_name)
        normalized_collection = normalize_app_data_collection(collection)
        record = app_data_db.get_record(
            task_id=task_id,
            package_name=normalized_package_name,
            collection=normalized_collection,
            record_id=record_id,
            include_deleted=include_deleted,
        )
        if not record:
            raise HTTPException(status_code=404, detail="record not found")
        return {"record": record}

    @app.patch("/apps/{task_id}/data/{collection}/{record_id}")
    def update_app_data_record(
        task_id: str,
        collection: str,
        record_id: str,
        request: AppDataUpdateRequest,
    ) -> dict[str, Any]:
        db: Database = app.state.db
        app_data_db: AppDataDatabase = app.state.app_data_db
        _, normalized_package_name = require_app_data_package(db, task_id, request.package_name)
        normalized_collection = normalize_app_data_collection(collection)
        record = app_data_db.update_record(
            task_id=task_id,
            package_name=normalized_package_name,
            collection=normalized_collection,
            record_id=record_id,
            owner_id=normalize_app_data_owner_id(request.owner_id),
            data=request.data,
            replace=request.replace,
        )
        if not record:
            raise HTTPException(status_code=404, detail="record not found")
        return {"record": record}

    @app.delete("/apps/{task_id}/data/{collection}/{record_id}")
    def delete_app_data_record(
        task_id: str,
        collection: str,
        record_id: str,
        package_name: str = Query(..., min_length=1),
    ) -> dict[str, Any]:
        db: Database = app.state.db
        app_data_db: AppDataDatabase = app.state.app_data_db
        _, normalized_package_name = require_app_data_package(db, task_id, package_name)
        normalized_collection = normalize_app_data_collection(collection)
        deleted = app_data_db.delete_record(
            task_id=task_id,
            package_name=normalized_package_name,
            collection=normalized_collection,
            record_id=record_id,
        )
        if not deleted:
            raise HTTPException(status_code=404, detail="record not found")
        return {
            "task_id": task_id,
            "package_name": normalized_package_name,
            "collection": normalized_collection,
            "record_id": record_id,
            "deleted": True,
        }

    @app.get("/tasks/{task_id}/revisions")
    def list_task_revisions(
        task_id: str,
        device_id: Optional[str] = Query(default=None),
        phone_number: Optional[str] = Query(default=None),
        user_id: Optional[str] = Query(default=None),
    ) -> dict[str, Any]:
        db: Database = app.state.db
        task = db.get_task(task_id)
        if not task:
            raise HTTPException(status_code=404, detail="task not found")
        if not is_task_access_allowed(task, device_id=device_id, phone_number=phone_number):
            raise HTTPException(status_code=404, detail="task not found")
        snapshots = db.list_project_snapshots(task_id)
        if not snapshots and task.get("workspace_path") and task.get("project_path"):
            snapshots = [
                {
                    "snapshot_id": "",
                    "task_id": task_id,
                    "revision_label": current_revision_label(Path(str(task.get("project_path")))),
                    "source": "current",
                    "workspace_path": task.get("workspace_path"),
                    "project_path": task.get("project_path"),
                    "created_at": task.get("created_at"),
                }
            ]
        events = db.list_events(task_id)
        revisions = [
            serialize_project_revision(
                task,
                snapshot,
                request_summary=revision_request_summary(task, snapshot, events),
            )
            for snapshot in snapshots
        ]
        return {"task_id": task_id, "revisions": revisions}

    @app.post("/tasks/{task_id}/revisions/{revision_label}/branch", status_code=202)
    def branch_task_revision(
        task_id: str,
        revision_label: str,
        device_id: Optional[str] = Query(default=None),
        phone_number: Optional[str] = Query(default=None),
        user_id: Optional[str] = Query(default=None),
    ) -> dict[str, Any]:
        _ = user_id
        db: Database = app.state.db
        settings: Settings = app.state.settings
        runner: CodexTaskRunner = app.state.runner
        source_task = db.get_task(task_id)
        if not source_task:
            raise HTTPException(status_code=404, detail="task not found")
        if not is_task_access_allowed(source_task, device_id=device_id, phone_number=phone_number):
            raise HTTPException(status_code=404, detail="task not found")

        normalized_revision_label = normalize_whitespace(revision_label)
        if not re.fullmatch(r"rev_\d{4}", normalized_revision_label):
            raise HTTPException(status_code=400, detail="invalid revision label")
        snapshot = db.get_project_snapshot(task_id, normalized_revision_label)
        if snapshot is None:
            current_project_value = normalize_whitespace(str(source_task.get("project_path") or ""))
            if current_project_value and current_revision_label(Path(current_project_value)) == normalized_revision_label:
                snapshot = {
                    "task_id": task_id,
                    "revision_label": normalized_revision_label,
                    "source": "current",
                    "workspace_path": source_task.get("workspace_path"),
                    "project_path": current_project_value,
                    "created_at": source_task.get("created_at"),
                }
            else:
                raise HTTPException(status_code=404, detail="revision not found")

        source_workspace_path = Path(str(snapshot.get("workspace_path") or "")).resolve()
        source_project_path = Path(str(snapshot.get("project_path") or "")).resolve()
        if (
            not source_workspace_path.exists()
            or not source_workspace_path.is_dir()
            or not source_project_path.exists()
            or not source_project_path.is_dir()
            or not ensure_within_root(source_project_path, source_workspace_path)
        ):
            raise HTTPException(status_code=409, detail="revision source is unavailable")

        current_project_value = normalize_whitespace(str(source_task.get("project_path") or ""))
        if current_project_value and Path(current_project_value).resolve() == source_project_path:
            if is_generate_blocked_task_status(str(source_task.get("status") or "")):
                raise HTTPException(status_code=409, detail="current revision is still being updated")

        branched_task_id = uuid.uuid4().hex
        now = utc_now_iso()
        app_name = current_task_app_name(source_task) or infer_project_app_name(source_project_path)
        package_name = current_task_package_name(source_task) or infer_project_package_name(source_project_path)
        version_name = revision_version_name(normalized_revision_label)
        branch_prompt = f"{app_name or '앱'} {version_name} 버전에서 독립적으로 분기된 작업"
        branch_origin = {
            "source_task_id": task_id,
            "source_revision_label": normalized_revision_label,
            "source_snapshot_id": str(snapshot.get("snapshot_id") or ""),
        }
        branch_message = (
            f"{version_name} 버전에서 새 Task를 만들었어요. "
            "선택한 버전을 복사하고 APK를 준비하고 있어요."
        )
        branch_state = {
            "status": "queued",
            "tool": "build",
            "message": branch_message,
            "request_scope": "existing_app_modification",
            "task_operation": "branch_rebuild",
            "branch_origin": branch_origin,
            "conversation_state": {
                "initial_user_prompt": branch_prompt,
                "latest_effective_user_prompt": branch_prompt,
                "app_name": app_name,
                "generated_app_name": app_name,
                "package_name": package_name,
                "build_success": False,
                "request_scope": "existing_app_modification",
                "awaiting_confirmation": False,
                "awaiting_prompt_review": False,
                "suppress_initial_prompt_bubble": True,
                "branch_origin": branch_origin,
            },
            "recent_messages": [],
        }
        branched_task = {
            "task_id": branched_task_id,
            "user_id": str(source_task.get("user_id") or effective_owner_id(device_id or "", phone_number)),
            "device_id": normalize_whitespace(device_id or str(source_task.get("device_id") or "")),
            "phone_number": normalize_whitespace(phone_number or str(source_task.get("phone_number") or "")) or None,
            "prompt": branch_prompt,
            "status": "Queued",
            "message": branch_message,
            "workspace_path": None,
            "project_path": None,
            "apk_path": None,
            "apk_url": None,
            "app_name": app_name,
            "package_name": package_name,
            "normalized_prompt": branch_prompt,
            "build_request_prompt": branch_prompt,
            "input_tokens": None,
            "cached_input_tokens": None,
            "output_tokens": None,
            "reasoning_output_tokens": None,
            "total_tokens": None,
            "codex_result_json": json.dumps(branch_state, ensure_ascii=False),
            "log": None,
            "created_at": now,
            "updated_at": now,
        }

        db.create_task(branched_task)
        source_llm_config = db.get_app_llm_config(task_id)
        if source_llm_config:
            db.upsert_app_llm_config(branched_task_id, source_llm_config)
            db.log_event(
                branched_task_id,
                actor="system",
                event_type="app_llm_config_initialized",
                message_text=app_llm_config_event_message(source_llm_config),
                payload=app_llm_config_event_payload(source_llm_config, source="branched_task"),
            )
        else:
            ensure_default_app_llm_config(db, settings, branched_task_id)
        db.log_event(
            branched_task_id,
            actor="system",
            event_type="task_branched",
            message_text=branch_message,
            payload={
                **branch_origin,
                "branched_task_id": branched_task_id,
                "copied_conversation_history": False,
                "copied_attachments": False,
                "copied_app_data": False,
            },
        )
        db.log_event(
            task_id,
            actor="system",
            event_type="task_branched_out",
            message_text=f"{version_name} 버전에서 새 Task가 생성되었어요.",
            payload={
                "branched_task_id": branched_task_id,
                "source_revision_label": normalized_revision_label,
            },
        )
        queued_task = db.get_task(branched_task_id)
        if queued_task:
            log_task_status_event(db, queued_task)
        runner.enqueue(branched_task_id)
        return serialize_task_for_status(
            db,
            db.get_task(branched_task_id) or branched_task,
            settings.status_log_line_limit,
        )

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

    @app.get("/tasks/{task_id}/attachments/{attachment_id}")
    def download_task_attachment(
        task_id: str,
        attachment_id: str,
        device_id: Optional[str] = Query(default=None),
        phone_number: Optional[str] = Query(default=None),
        user_id: Optional[str] = Query(default=None),
    ) -> FileResponse:
        _ = user_id
        db: Database = app.state.db
        settings: Settings = app.state.settings
        if not (str(device_id or "").strip() or str(phone_number or "").strip()):
            raise HTTPException(status_code=404, detail="attachment not found")
        task = db.get_task(task_id)
        if not task or not is_task_access_allowed(task, device_id=device_id, phone_number=phone_number):
            raise HTTPException(status_code=404, detail="attachment not found")
        attachment = db.get_task_attachment(task_id, attachment_id)
        if not attachment or str(attachment.get("status") or "") != "saved":
            raise HTTPException(status_code=404, detail="attachment not found")

        workspace_root = Path(
            str(task.get("workspace_path") or task_workspace_root_for(settings, task))
        ).resolve()
        path_value = str(
            attachment.get("absolute_path")
            or attachment.get("workspace_path")
            or ""
        ).strip()
        if not path_value:
            raise HTTPException(status_code=404, detail="attachment not found")
        attachment_path = Path(path_value)
        if not attachment_path.is_absolute():
            attachment_path = workspace_root / attachment_path
        attachment_path = attachment_path.resolve()
        if not ensure_within_root(attachment_path, workspace_root):
            raise HTTPException(status_code=403, detail="invalid attachment path")
        if not attachment_path.exists() or not attachment_path.is_file():
            raise HTTPException(status_code=404, detail="attachment not found")

        return FileResponse(
            attachment_path,
            media_type=str(attachment.get("mime_type") or "application/octet-stream"),
            filename=str(attachment.get("original_name") or attachment_path.name),
        )

    @app.get("/download/{task_id}")
    def download_apk(
        task_id: str,
        device_id: Optional[str] = Query(default=None),
        phone_number: Optional[str] = Query(default=None),
        user_id: Optional[str] = Query(default=None),
        artifact_path: Optional[str] = Query(default=None),
    ) -> FileResponse:
        db: Database = app.state.db
        runner: CodexTaskRunner = app.state.runner
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

        project_path = project_path_for_apk_artifact(
            task,
            db.list_project_snapshots(task_id),
            apk_path,
        )
        if project_path is not None and not runner.settings.mock_codex:
            try:
                apk_path = runner.prepare_saved_revision_apk(
                    task,
                    workspace_path.resolve(),
                    project_path,
                )
            except Exception as exc:
                db.log_event(
                    task_id,
                    actor="system",
                    event_type="apk_install_contract_failed",
                    message_text="설치 가능한 APK 준비에 실패했습니다.",
                    payload={
                        "artifact_path": artifact_path or "",
                        "project_path": str(project_path),
                        "error": str(exc),
                    },
                )
                raise HTTPException(
                    status_code=409,
                    detail="installable apk preparation failed",
                ) from exc

        db.log_event(
            task_id,
            actor="system",
            event_type="apk_download_requested",
            message_text=f"APK 다운로드 요청: {apk_path.name}",
            payload={
                "apk_path": str(apk_path),
                "artifact_path": artifact_path or "",
                "size_bytes": apk_path.stat().st_size,
                "device_id": device_id or "",
                "phone_number": phone_number or "",
            },
        )
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
