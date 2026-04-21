import json
import os
import unittest
from types import SimpleNamespace
from unittest.mock import patch

os.environ.setdefault("OPENAI_API_KEY", "test-key")

import vibe_factory


def make_tool_response(tool_name, arguments=None):
    arguments = arguments or {}
    tool_call = SimpleNamespace(
        id=f"call_{tool_name}",
        function=SimpleNamespace(name=tool_name, arguments=json.dumps(arguments)),
    )
    message = SimpleNamespace(content="", tool_calls=[tool_call])
    return SimpleNamespace(choices=[SimpleNamespace(message=message)], usage=None)


class FakeCompletions:
    def __init__(self, tool_sequence):
        self._responses = [make_tool_response(name, args) for name, args in tool_sequence]

    def create(self, **kwargs):
        if not self._responses:
            raise AssertionError("unexpected model call")
        return self._responses.pop(0)


class FinalizePreflightGateTests(unittest.TestCase):
    def run_engineer_sequence(self, final_preflight_result):
        tool_sequence = [
            (
                "report_file_manifest",
                {
                    "files": [
                        {
                            "path": "lib/main.dart",
                            "purpose": "entrypoint",
                            "change_type": "modify",
                        }
                    ],
                },
            ),
            ("run_static_preflight", {}),
            ("edit_file", {"path": "lib/main.dart", "old_text": "a", "new_text": "b", "reason": "fix"}),
            ("run_flutter_analyze", {}),
            ("run_flutter_build", {}),
            ("finalize", {"summary": "done", "built_files": ["lib/main.dart"]}),
        ]
        fake_client = SimpleNamespace(
            chat=SimpleNamespace(completions=FakeCompletions(tool_sequence))
        )

        def fake_execute_tool(tool_name, args, *unused_args, **unused_kwargs):
            if tool_name == "report_file_manifest":
                return {
                    "status": "ok",
                    "files": [{"path": "lib/main.dart"}],
                }
            if tool_name == "run_static_preflight":
                return {"status": "pass", "issues": []}
            if tool_name == "edit_file":
                return {"status": "ok", "path": "lib/main.dart"}
            if tool_name == "run_flutter_analyze":
                return {"status": "pass", "output": "No issues found"}
            if tool_name == "run_flutter_build":
                return {"status": "pass", "output": "Built build/app/outputs/flutter-apk/app-debug.apk"}
            if tool_name == "finalize":
                return {"status": "done", **args}
            raise AssertionError(f"unexpected tool: {tool_name}")

        logs = []
        with (
            patch.object(vibe_factory, "client", fake_client),
            patch.object(vibe_factory, "execute_tool", side_effect=fake_execute_tool),
            patch.object(
                vibe_factory,
                "run_final_preflight_verification",
                return_value=final_preflight_result,
            ) as final_preflight,
        ):
            result = vibe_factory.run_agentic_loop(
                task_id="test_task",
                user_request="build",
                tools=[],
                system="system",
                project_path="/tmp/project",
                callback_log=logs.append,
                pkg="kr.test.app",
                agent_label="Engineer",
            )
        return result, final_preflight, logs

    def test_finalize_auto_preflight_allows_success_after_last_edit(self):
        result, final_preflight, logs = self.run_engineer_sequence((True, []))

        self.assertEqual(result["tool"], "finalize")
        self.assertEqual(result["result"]["status"], "done")
        final_preflight.assert_called_once()
        self.assertTrue(any("finalize" in line for line in logs))

    def test_finalize_auto_preflight_failure_still_blocks_success(self):
        result, final_preflight, _logs = self.run_engineer_sequence((False, ["missing CrashHandler"]))

        self.assertEqual(result["tool"], "finalize")
        self.assertEqual(result["result"]["status"], "error")
        self.assertIn("missing CrashHandler", result["result"]["warnings"])
        final_preflight.assert_called_once()

    def test_finalize_auto_runs_missing_analyze_and_build_checks(self):
        tool_sequence = [
            (
                "report_file_manifest",
                {
                    "files": [
                        {
                            "path": "lib/main.dart",
                            "purpose": "entrypoint",
                            "change_type": "modify",
                        }
                    ],
                },
            ),
            ("run_static_preflight", {}),
            ("finalize", {"summary": "done", "built_files": ["lib/main.dart"]}),
        ]
        fake_client = SimpleNamespace(
            chat=SimpleNamespace(completions=FakeCompletions(tool_sequence))
        )

        def fake_execute_tool(tool_name, args, *unused_args, **unused_kwargs):
            if tool_name == "report_file_manifest":
                return {"status": "ok", "files": [{"path": "lib/main.dart"}]}
            if tool_name == "run_static_preflight":
                return {"status": "pass", "issues": []}
            if tool_name == "finalize":
                return {"status": "done", **args}
            raise AssertionError(f"unexpected tool: {tool_name}")

        logs = []
        with (
            patch.object(vibe_factory, "client", fake_client),
            patch.object(vibe_factory, "execute_tool", side_effect=fake_execute_tool),
            patch.object(vibe_factory, "run_final_analyze_verification", return_value=(True, "")) as final_analyze,
            patch.object(vibe_factory, "run_final_build_verification", return_value=(True, "")) as final_build,
        ):
            result = vibe_factory.run_agentic_loop(
                task_id="test_task",
                user_request="build",
                tools=[],
                system="system",
                project_path="/tmp/project",
                callback_log=logs.append,
                pkg="kr.test.app",
                agent_label="Engineer",
            )

        self.assertEqual(result["tool"], "finalize")
        self.assertEqual(result["result"]["status"], "done")
        final_analyze.assert_called_once()
        final_build.assert_called_once()

    def test_three_analyze_failures_force_debugger_diagnosis(self):
        tool_sequence = [
            ("run_flutter_analyze", {}),
            ("run_flutter_analyze", {}),
            ("run_flutter_analyze", {}),
            ("report_diagnosis", {"summary": "diagnosed"}),
        ]
        fake_client = SimpleNamespace(
            chat=SimpleNamespace(completions=FakeCompletions(tool_sequence))
        )
        called_tools = []

        def fake_execute_tool(tool_name, args, *unused_args, **unused_kwargs):
            called_tools.append(tool_name)
            if tool_name == "run_flutter_analyze":
                return {"status": "fail", "output": "Undefined name 'Foo'."}
            if tool_name == "request_diagnosis":
                return {"status": "ok", "diagnosis": {"status": "success", "result": {"summary": "fix Foo"}}}
            if tool_name == "report_diagnosis":
                return {"status": "done", **args}
            raise AssertionError(f"unexpected tool: {tool_name}")

        logs = []
        with (
            patch.object(vibe_factory, "client", fake_client),
            patch.object(vibe_factory, "execute_tool", side_effect=fake_execute_tool),
        ):
            result = vibe_factory.run_agentic_loop(
                task_id="test_task",
                user_request="build",
                tools=[],
                system="system",
                project_path="/tmp/project",
                callback_log=logs.append,
                pkg="kr.test.app",
                agent_label="Engineer",
            )

        self.assertEqual(result["tool"], "report_diagnosis")
        self.assertEqual(called_tools.count("request_diagnosis"), 1)
        self.assertTrue(any("analyze 자동 진단" in line for line in logs))


if __name__ == "__main__":
    unittest.main()
