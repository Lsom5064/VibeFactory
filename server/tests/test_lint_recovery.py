import json
import os
import tempfile
import unittest
from dataclasses import replace
from pathlib import Path
from unittest.mock import patch

from server.lint_recovery import LintValidationGuard, diagnose_lint_failure
from server.server import CodexTaskRunner, Database, build_task_workspace, load_settings, utc_now_iso


ROOT = Path(__file__).resolve().parents[2]
ERROR_A = "app/src/main/res/layout/activity_main.xml:10: Error: Missing label [LabelFor]\n"
ERROR_B = "e: file:///project/app/src/main/kotlin/MainActivity.kt:18:4 Unresolved reference 'title'\n"
ERROR_C = "app/src/main/res/values/strings.xml:15: error: resource color/purple not found\n"


class LintDiagnosticTests(unittest.TestCase):
    def test_source_failures_and_location_independent_signature(self):
        for diagnostic in (ERROR_A, ERROR_B, ERROR_C, "error: resource string/title not found"):
            self.assertEqual("source", diagnose_lint_failure(diagnostic).category)
        self.assertEqual(diagnose_lint_failure(ERROR_A).fingerprint,
                         diagnose_lint_failure(ERROR_A.replace(":10:", ":88:")).fingerprint)
        self.assertNotEqual(diagnose_lint_failure(ERROR_A).fingerprint,
                            diagnose_lint_failure(ERROR_B).fingerprint)

    def test_infrastructure_and_unknown_failures_do_not_request_source_changes(self):
        for text in ("No space left on device", "SDK location not found", "Permission denied",
                     "Could not resolve all files for configuration", "java.lang.OutOfMemoryError",
                     "Gradle build daemon disappeared", "Connection timed out"):
            self.assertEqual("environment", diagnose_lint_failure(ERROR_A + text).category)
        self.assertEqual("unknown", diagnose_lint_failure("BUILD FAILED").category)

    def test_configuration_and_new_suppressions_cannot_bypass_validation(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "app/src/main/MainActivity.kt"
            source.parent.mkdir(parents=True)
            source.write_text('@Suppress("UNUSED_PARAMETER")\nclass MainActivity', encoding="utf-8")
            gradle = root / "app/build.gradle.kts"
            gradle.write_text("lint { abortOnError = true }", encoding="utf-8")
            guard = LintValidationGuard.capture(root)
            source.write_text(source.read_text() + "\n// source fix", encoding="utf-8")
            guard.validate(root)
            for suppression in ('@file:Suppress("all")', '@android.annotation.SuppressLint("all")',
                                '<View tools:ignore="all"/>', '<View x:ignore="all"/>', '//noinspection MissingPermission'):
                source.write_text(suppression, encoding="utf-8")
                with self.assertRaises(ValueError):
                    guard.validate(root)
            source.write_text("class MainActivity", encoding="utf-8")
            gradle.write_text("lint { abortOnError = false }", encoding="utf-8")
            with self.assertRaises(ValueError):
                guard.validate(root)

    def test_settings_have_bounded_defaults_and_can_disable_repair(self):
        with patch.dict(os.environ, {"LINT_RECOVERY_MAX_ATTEMPTS": "99", "LINT_RECOVERY_TIMEOUT_SECONDS": "9999"}):
            self.assertEqual(2, load_settings().lint_recovery_max_attempts)
            self.assertEqual(600, load_settings().lint_recovery_timeout_seconds)
        with patch.dict(os.environ, {"LINT_RECOVERY_MAX_ATTEMPTS": "0"}):
            self.assertEqual(0, load_settings().lint_recovery_max_attempts)


class LintRecoveryPipelineTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        root = Path(self.temp.name)
        keystore = root / "test.jks"
        keystore.write_bytes(b"test-only")
        self.settings = replace(
            load_settings(), base_project_path=ROOT / "BaseProject", workspaces_root=root / "workspaces",
            build_cache_root=root / "cache", db_path=root / "tasks.db", app_data_db_path=root / "app.db",
            mock_codex=False, intent_agent_enabled=False, generated_app_keystore_path=str(keystore),
            generated_app_keystore_password="test", generated_app_key_alias="test", generated_app_key_password="test",
            lint_recovery_max_attempts=2, lint_recovery_timeout_seconds=600,
        )
        self.db = Database(self.settings.db_path)
        self.db.init_db()
        self.task = dict(task_id="lint-recovery-test", user_id="test-user", device_id="test-device",
                         prompt="카운터 동작을 유지해줘", normalized_prompt="카운터 동작을 유지해줘",
                         status="Running", message="검증 중", app_name="복구 테스트",
                         package_name="kr.ac.kangwon.hai.generated.recoverytest",
                         created_at=utc_now_iso(), updated_at=utc_now_iso())
        self.db.create_task(self.task)
        self.workspace, self.project = build_task_workspace(self.settings, self.task)
        self.db.update_task(self.task["task_id"], workspace_path=str(self.workspace), project_path=str(self.project))
        self.runner = CodexTaskRunner(self.settings, self.db)
        self.result_path = self.workspace / ".codex_result/task_result.json"
        self.result_path.write_text(json.dumps({"status": "success", "message": "original", "known_limitations": []}))
        self.commands = []
        self.repair_prompts = []
        self.lint_results = []
        self.repair_action = None
        self.repair_exit = (0, False)
        for target, kwargs in (
            ("server.server.project_looks_like_placeholder_app", {"return_value": False}),
            ("server.server.validate_built_apk_install_contract", {}),
        ):
            context = patch(target, **kwargs)
            context.start()
            self.addCleanup(context.stop)
        context = patch.object(self.runner, "run_logged_command", side_effect=self.command)
        context.start()
        self.addCleanup(context.stop)
        context = patch.object(self.runner, "run_codex", side_effect=self.repair)
        self.mock_repair = context.start()
        self.addCleanup(context.stop)

    def command(self, args, *, cwd, env, log_path, task_id, timeout_seconds=None):
        self.commands.append((args[1], timeout_seconds))
        if args[1] == ":app:assembleRelease":
            apk = self.project / "app/build/outputs/apk/release/app-release.apk"
            apk.parent.mkdir(parents=True, exist_ok=True)
            apk.write_bytes(b"test-apk")
            return 0, False, .1
        code, timed_out, output = self.lint_results.pop(0)
        with log_path.open("a") as log:
            log.write(output)
        return code, timed_out, .1

    def repair(self, task, workspace, stdout_path, stderr_path, *, prompt_override, timeout_seconds):
        self.assertGreater(timeout_seconds, 0)
        self.assertLessEqual(timeout_seconds, 600)
        self.repair_prompts.append(prompt_override)
        stdout_path.write_text(json.dumps({"type": "turn.completed", "usage": {"input_tokens": 10, "output_tokens": 3}}))
        stderr_path.write_text("")
        if self.repair_action:
            self.repair_action()
        return self.repair_exit

    def build(self, results):
        self.lint_results = list(results)
        self.runner.attempt_server_side_build(self.task["task_id"], self.workspace, self.result_path, 0)
        return json.loads(self.result_path.read_text())

    def test_success_after_first_repair_rechecks_lint_then_builds_release(self):
        result = self.build([(1, False, ERROR_A), (0, False, "BUILD SUCCESSFUL")])
        self.assertEqual("success", result["status"])
        self.assertEqual("recovered", result["lint_recovery"]["stop_reason"])
        self.assertEqual([":app:lintDebug", ":app:lintDebug", ":app:assembleRelease"], [c[0] for c in self.commands])
        self.assertIn("원래 요청과 기능", self.repair_prompts[0])
        self.assertIn("카운터", self.repair_prompts[0])
        self.assertEqual([], result["known_limitations"])
        self.assertEqual(str(self.project), self.db.get_task(self.task["task_id"])["project_path"])
        self.assertTrue(any("(1/2)" in event["message_text"] for event in self.db.list_events(self.task["task_id"])))

    def test_two_repairs_at_most_and_final_failure_contains_count(self):
        result = self.build([(1, False, ERROR_A), (1, False, ERROR_B), (1, False, ERROR_C)])
        self.assertEqual("failed", result["status"])
        self.assertEqual("attempt_limit", result["lint_recovery"]["stop_reason"])
        self.assertEqual(2, self.mock_repair.call_count)
        self.assertEqual(3, len(self.commands))
        self.assertIn("자동 수정 2회", result["message"])

    def test_can_succeed_on_second_repair(self):
        result = self.build([(1, False, ERROR_A), (1, False, ERROR_B), (0, False, "ok")])
        self.assertEqual("success", result["status"])
        self.assertEqual(2, self.mock_repair.call_count)

    def test_unchanged_error_stops_early_even_when_line_numbers_change(self):
        result = self.build([(1, False, ERROR_A), (1, False, ERROR_A.replace(":10:", ":30:"))])
        self.assertEqual("unchanged_errors", result["lint_recovery"]["stop_reason"])
        self.assertEqual(1, self.mock_repair.call_count)

    def test_environment_error_does_not_invoke_codex(self):
        result = self.build([(1, False, "SDK location not found")])
        self.assertEqual("environment", result["lint_recovery"]["stop_reason"])
        self.mock_repair.assert_not_called()

    def test_unknown_error_does_not_invoke_codex(self):
        self.build([(1, False, "unclassified failure")])
        self.mock_repair.assert_not_called()

    def test_initial_timeout_does_not_invoke_codex(self):
        result = self.build([(1, True, ERROR_A)])
        self.assertEqual("failed", result["status"])
        self.mock_repair.assert_not_called()

    def test_repair_timeout_and_auth_failure_stop_without_running_lint_again(self):
        for repair_exit, reason in (((1, True), "time_limit"), ((1, False), "repair_engine_failed")):
            with self.subTest(reason=reason):
                self.repair_exit = repair_exit
                result = self.build([(1, False, ERROR_A)])
                self.assertEqual(reason, result["lint_recovery"]["stop_reason"])
        self.assertEqual(2, len(self.commands))

    def test_recheck_has_only_remaining_total_budget(self):
        clock = [100.0]
        self.repair_action = lambda: clock.__setitem__(0, 220.0)
        with patch("server.server.time.monotonic", side_effect=lambda: clock[0]):
            self.build([(1, False, ERROR_A), (0, False, "ok")])
        self.assertEqual(480, self.commands[1][1])

    def test_exhausted_total_budget_stops_before_recheck(self):
        clock = [100.0]
        self.repair_action = lambda: clock.__setitem__(0, 701.0)
        with patch("server.server.time.monotonic", side_effect=lambda: clock[0]):
            result = self.build([(1, False, ERROR_A)])
        self.assertEqual("time_limit", result["lint_recovery"]["stop_reason"])
        self.assertEqual(1, len(self.commands))

    def test_build_configuration_change_is_rejected_before_recheck(self):
        self.repair_action = lambda: (self.project / "app/build.gradle.kts").write_text("lint { abortOnError = false }")
        result = self.build([(1, False, ERROR_A)])
        self.assertEqual("validation_guard", result["lint_recovery"]["stop_reason"])
        self.assertEqual(1, len(self.commands))

    def test_new_suppression_is_rejected_before_recheck(self):
        self.repair_action = lambda: (self.project / "app/src/main/kotlin/Bypass.kt").write_text('@file:Suppress("all")')
        result = self.build([(1, False, ERROR_A)])
        self.assertEqual("validation_guard", result["lint_recovery"]["stop_reason"])
        self.assertEqual(1, len(self.commands))

    def test_original_request_is_restored_when_agent_changes_it(self):
        prompt_path = self.workspace / "prompt.md"
        original = prompt_path.read_bytes()
        self.repair_action = lambda: prompt_path.write_text("changed")
        result = self.build([(1, False, ERROR_A)])
        self.assertEqual(original, prompt_path.read_bytes())
        self.assertEqual("validation_guard", result["lint_recovery"]["stop_reason"])

    def test_cancellation_during_repair_does_not_build_or_replace_cancelled_status(self):
        self.repair_action = lambda: self.db.update_task(self.task["task_id"], status="Cancelled")
        self.build([(1, False, ERROR_A)])
        self.runner.finalize_task(self.task["task_id"], self.workspace, self.result_path, 0, False)
        self.assertEqual("Cancelled", self.db.get_task(self.task["task_id"])["status"])
        self.assertEqual(1, len(self.commands))

    def test_repair_logs_and_usage_are_persisted_and_counted_once(self):
        (self.workspace / "logs/codex_stdout.log").write_text(json.dumps({"usage": {"input_tokens": 5, "output_tokens": 2}}))
        result = self.build([(1, False, ERROR_A), (1, False, ERROR_B), (1, False, ERROR_C)])
        self.runner.finalize_task(self.task["task_id"], self.workspace, self.result_path, 0, False)
        task = self.db.get_task(self.task["task_id"])
        self.assertEqual("Failed", task["status"])
        self.assertEqual(25, task["input_tokens"])
        self.assertEqual(8, task["output_tokens"])
        events = [event for event in self.db.list_events(self.task["task_id"]) if event["event_type"] == "lint_repair_output"]
        self.assertEqual(2, len(events))
        for record in result["lint_recovery"]["attempts"]:
            self.assertTrue((self.workspace / record["stdout_path"]).is_file())


if __name__ == "__main__":
    unittest.main()
