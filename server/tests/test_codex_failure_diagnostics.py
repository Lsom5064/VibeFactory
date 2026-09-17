import json
import tempfile
import time
import unittest
from pathlib import Path
from unittest import mock

from server.codex_failure_diagnostics import (
    codex_failure_text,
    looks_like_codex_auth_error,
    looks_like_codex_quota_error,
)
from server.server import (
    CodexTaskRunner, Database, codex_engine_issue_from_logs,
    load_settings, utc_now_iso,
)


class CodexFailureDiagnosticsTests(unittest.TestCase):
    def test_numeric_values_and_instructions_are_not_authentication_errors(self):
        for text in [
            "2026-09-16T07:53:21.401234Z ERROR router: write failed",
            "request_id=req_401abcdef output_tokens=1401",
            "val color = 0xFF401ABC; HTTP examples are in the README",
            "Setup instructions: codex login --device-auth",
        ]:
            with self.subTest(text=text):
                self.assertFalse(looks_like_codex_auth_error(text))

    def test_actual_authentication_failures_are_recognized(self):
        for text in [
            "unexpected status 401: Missing bearer or basic authentication in header",
            "HTTP error: 401 Unauthorized",
            "Not logged in",
            "Your authentication token has expired",
            '{"code":"refresh_token_reused"}',
            '{"code":"invalid_api_key"}',
        ]:
            with self.subTest(text=text):
                self.assertTrue(looks_like_codex_auth_error(text))

    def test_quota_numbers_and_source_names_are_not_engine_failures(self):
        for text in ["2026-09-16T08:00:00.429000Z", "quota_label.text = 1429", "request_429abc"]:
            with self.subTest(text=text):
                self.assertFalse(looks_like_codex_quota_error(text))
        for text in ["HTTP 429", "429 Too Many Requests", "insufficient_quota", "rate_limit_exceeded"]:
            with self.subTest(text=text):
                self.assertTrue(looks_like_codex_quota_error(text))

    def test_generated_code_tool_output_and_agent_messages_do_not_classify_engine(self):
        stdout = "\n".join(json.dumps(event) for event in [
            {"type": "item.completed", "item": {"type": "agent_message", "text": "Handle HTTP 401 Unauthorized and rate limits in the app."}},
            {"type": "item.completed", "item": {"type": "command_execution", "aggregated_output": "test_http_401_unauthorized passed; rate_limit_exceeded case passed"}},
            {"type": "item.completed", "item": {"type": "file_change", "changes": [{"path": "quota_policy.kt"}]}},
            {"type": "turn.completed", "usage": {"input_tokens": 401, "output_tokens": 429}},
        ])
        self.assertIsNone(codex_engine_issue_from_logs(codex_failure_text(stdout, ""), 0))

    def test_provider_errors_are_retained(self):
        stdout = json.dumps({"type": "turn.failed", "error": {"message": "HTTP 401 Unauthorized"}})
        issue = codex_engine_issue_from_logs(codex_failure_text(stdout, ""), 1)
        self.assertEqual("codex_auth_error", issue[2])

    def test_bwrap_failure_with_401_timestamp_is_sandbox_error(self):
        stderr = "2026-09-16T07:53:21.401234Z ERROR router: bwrap: loopback: Failed RTM_NEWADDR: Operation not permitted"
        issue = codex_engine_issue_from_logs(codex_failure_text("", stderr), 0)
        self.assertEqual("codex_sandbox_error", issue[2])
        self.assertNotIn("인증", issue[1])

    def test_finalization_reports_sandbox_failure_and_preserves_raw_log(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "logs").mkdir()
            stderr = "2026-09-16T07:53:21.401234Z ERROR router: bwrap: loopback: Failed RTM_NEWADDR: Operation not permitted"
            (root / "logs/codex_stderr.log").write_text(stderr)
            (root / "logs/codex_stdout.log").write_text('{"type":"turn.completed"}\n')
            db = Database(root / "test.db")
            db.init_db()
            now = utc_now_iso()
            db.create_task({"task_id": "sandbox-failure", "user_id": "test", "device_id": "test", "prompt": "test", "status": "Running", "message": "running", "workspace_path": str(root), "created_at": now, "updated_at": now})
            runner = CodexTaskRunner(load_settings(), db)
            runner.finalize_task("sandbox-failure", root, root / ".codex_result/task_result.json", 0, False)
            task = db.get_task("sandbox-failure")
            self.assertEqual("Error", task["status"])
            self.assertIn("격리 실행", task["message"])
            self.assertIn(stderr, task["log"])
            self.assertIn("codex_sandbox_error", [e["event_type"] for e in db.list_events("sandbox-failure")])

    def test_sandbox_failure_does_not_build_untouched_template(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "logs").mkdir()
            (root / "logs/codex_stderr.log").write_text("bwrap: loopback: Failed RTM_NEWADDR: Operation not permitted")
            runner = CodexTaskRunner(load_settings(), mock.Mock())
            with mock.patch.object(runner, "enforce_task_project_identity", return_value=False), mock.patch.object(runner, "attempt_server_side_build") as build, mock.patch("server.server.log_build_stage_event") as stage:
                runner.complete_generation_stage_and_build(
                    task_id="sandbox-failure", workspace_path=root,
                    result_path=root / ".codex_result/task_result.json",
                    exit_code=0, timed_out=False, started_at=time.monotonic(),
                )
            build.assert_not_called()
            self.assertEqual("failed", stage.call_args.kwargs["phase"])


if __name__ == "__main__":
    unittest.main()
