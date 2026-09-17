"""Classify engine failures without treating generated content as diagnostics."""

from __future__ import annotations

import json
import re


def _http_error(text: str, code: int, description: str) -> bool:
    # A timestamp, token count, source literal or request ID is not an HTTP status.
    return bool(re.search(
        rf"\b(?:HTTP(?:/\d(?:\.\d)?)?(?:\s+error)?|(?:unexpected\s+)?status(?:\s+code)?)"
        rf"\s*[:=]?\s*{code}\b|\b{code}\s+{description}\b",
        text, re.IGNORECASE,
    ))


def looks_like_codex_auth_error(text: str) -> bool:
    return _http_error(text, 401, "unauthori[sz]ed") or bool(re.search(
        r"\b(?:not logged in|not authenticated|authentication required|auth required|"
        r"please log ?in|login required|please sign in|unauthori[sz]ed|"
        r"(?:access |authentication )?token (?:has )?expired|expired token|invalid token|"
        r"no auth credentials|invalid_api_key|authentication_error|"
        r"refresh_token_(?:expired|reused|invalidated)|invalid_grant)\b"
        r"|인증이 만료|로그인이 필요|로그인 만료|인증 필요|인증 실패",
        text, re.IGNORECASE,
    ))


def looks_like_codex_quota_error(text: str) -> bool:
    return _http_error(text, 429, "too many requests") or bool(re.search(
        r"\b(?:rate[ _]limit(?:ed|_exceeded)?|usage limit|quota exceeded|"
        r"insufficient_quota|too many requests|exceeded your (?:current )?quota)\b"
        r"|한도 초과|사용 한도|요청 한도|호출량 초과|사용량 한도|사용량 초과",
        text, re.IGNORECASE,
    ))


def looks_like_codex_sandbox_error(text: str) -> bool:
    return any(
        re.search(r"\b(?:bwrap|sandbox|landlock)\b", line, re.IGNORECASE)
        and re.search(
            r"operation not permitted|permission denied|failed|error|denied",
            line, re.IGNORECASE,
        )
        for line in text.splitlines()
    )


def codex_failure_text(stdout: str, stderr: str) -> str:
    """Keep provider errors and CLI diagnostics, excluding tool/source/chat output.

    In JSON mode, command output and agent messages can contain arbitrary app
    code (including HTTP 401/429 handling). They must not drive engine status.
    Raw logs are retained separately by the caller.
    """
    messages: list[str] = []
    for line in stdout.splitlines():
        try:
            event = json.loads(line)
        except ValueError:
            # Older CLI versions can emit a plain error instead of JSONL.
            if re.match(r"\s*(?:error\b|fatal\b|HTTP\b)", line, re.IGNORECASE):
                messages.append(line)
            continue
        if not isinstance(event, dict):
            continue
        value = None
        if event.get("type") == "error":
            value = event.get("message") or event.get("error")
        elif event.get("type") == "turn.failed":
            value = event.get("error")
        elif event.get("type") in {"item.started", "item.completed"}:
            item = event.get("item")
            if isinstance(item, dict) and item.get("type") == "error":
                value = item.get("message") or item.get("error")
        if isinstance(value, dict):
            messages.append(" ".join(str(value.get(k) or "") for k in ("code", "message")))
        elif isinstance(value, str):
            messages.append(value)
    messages.append(stderr)
    return "\n".join(messages)
