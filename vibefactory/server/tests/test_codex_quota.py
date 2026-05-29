from server import (
    CodexRateLimitSnapshot,
    CodexRateLimitWindow,
    codex_quota_exceeded_message,
    looks_like_codex_quota_error,
)


def test_codex_quota_message_when_primary_window_exhausted():
    snapshot = CodexRateLimitSnapshot(
        limit_name="codex",
        primary=CodexRateLimitWindow(used_percent=100, window_duration_mins=300, resets_at=1_700_003_600),
        secondary=CodexRateLimitWindow(used_percent=20, window_duration_mins=10_080, resets_at=None),
    )

    message = codex_quota_exceeded_message(snapshot, now_ts=1_700_000_000)

    assert message == "지금은 앱 생성 한도를 모두 사용했어요. 약 1시간 후 다시 시도할 수 있어요."


def test_codex_quota_message_allows_non_exhausted_windows():
    snapshot = CodexRateLimitSnapshot(
        limit_name="codex",
        primary=CodexRateLimitWindow(used_percent=99, window_duration_mins=300, resets_at=None),
        secondary=CodexRateLimitWindow(used_percent=99, window_duration_mins=10_080, resets_at=None),
    )

    assert codex_quota_exceeded_message(snapshot) is None


def test_codex_quota_error_log_detection():
    assert looks_like_codex_quota_error("HTTP 429: rate limit exceeded")
    assert looks_like_codex_quota_error("이번 주 사용량 한도에 도달했습니다")
    assert not looks_like_codex_quota_error("Flutter analyze 단계에 실패했어요.")
