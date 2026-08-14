import unittest
from unittest.mock import patch

from flutter_apk_server.server import (
    CodexRateLimitSnapshot,
    CodexRateLimitWindow,
    build_token_usage_response,
    load_settings,
)


class TokenUsageContractTests(unittest.TestCase):
    def test_weekly_only_limit_is_not_labeled_as_five_hour_limit(self) -> None:
        weekly = CodexRateLimitWindow(
            used_percent=36,
            window_duration_mins=7 * 24 * 60,
            resets_at=1_787_201_389,
        )
        snapshot = CodexRateLimitSnapshot(limit_name="codex", primary=weekly, secondary=None)

        with patch("flutter_apk_server.server.load_usage_rate_limits", return_value=(snapshot, None)):
            response = build_token_usage_response(settings=load_settings(), usage={})

        self.assertIsNone(response["primary_window"])
        self.assertEqual("주간 한도", response["secondary_window"]["window_label"])
        self.assertEqual(64, response["secondary_window"]["remaining_percent"])
        self.assertEqual(7 * 24 * 60, response["secondary_window"]["window_duration_mins"])

    def test_reversed_backend_windows_are_sorted_by_duration(self) -> None:
        weekly = CodexRateLimitWindow(used_percent=40, window_duration_mins=10_080, resets_at=20)
        short = CodexRateLimitWindow(used_percent=20, window_duration_mins=300, resets_at=10)
        snapshot = CodexRateLimitSnapshot(limit_name="codex", primary=weekly, secondary=short)

        with patch("flutter_apk_server.server.load_usage_rate_limits", return_value=(snapshot, None)):
            response = build_token_usage_response(settings=load_settings(), usage={})

        self.assertEqual(300, response["primary_window"]["window_duration_mins"])
        self.assertEqual("5시간 한도", response["primary_window"]["window_label"])
        self.assertEqual(10_080, response["secondary_window"]["window_duration_mins"])
        self.assertEqual("주간 한도", response["secondary_window"]["window_label"])


if __name__ == "__main__":
    unittest.main()
