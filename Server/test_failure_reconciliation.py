import os
import tempfile
import unittest
from unittest.mock import patch

os.environ.setdefault("OPENAI_API_KEY", "test-key")

import server


class FailureReconciliationTests(unittest.TestCase):
    def test_failed_result_becomes_success_when_existing_apk_revalidates(self):
        with tempfile.TemporaryDirectory() as project_path:
            apk_path = os.path.join(project_path, "build/app/outputs/flutter-apk/app-debug.apk")
            os.makedirs(os.path.dirname(apk_path), exist_ok=True)
            with open(apk_path, "wb") as apk_file:
                apk_file.write(b"apk")

            task = {
                "task_id": "task-1",
                "app_name": "테스트 앱",
                "project_path": project_path,
                "package_name": "kr.test.app",
                "final_app_spec": "{}",
            }
            failed_result = {
                "status": "failed",
                "error_log": "Engineer finalize contract failed",
                "project_path": project_path,
                "package_name": "kr.test.app",
            }

            with (
                patch.object(server, "get_task", return_value={}),
                patch.object(server, "run_static_preflight_checks", return_value={"status": "pass", "issues": []}) as preflight,
                patch.object(server, "run_flutter_analyze", return_value=(True, "No issues found")) as analyze,
                patch.object(server, "run_flutter_build", return_value=(True, apk_path)) as build,
                patch.object(
                    server,
                    "verify_release_external_data_gate",
                    return_value={"status": "not_applicable", "summary": "검증 불필요", "issues": []},
                ) as verification,
            ):
                result = server.reconcile_failed_result_with_verified_artifact(task, failed_result)

        self.assertEqual(result["status"], "success")
        self.assertEqual(result["apk_path"], apk_path)
        self.assertTrue(result["reconciled_from_failure"])
        preflight.assert_called_once()
        analyze.assert_called_once()
        build.assert_called_once()
        verification.assert_called_once()

    def test_failed_result_without_apk_is_not_reconciled(self):
        with tempfile.TemporaryDirectory() as project_path:
            task = {
                "task_id": "task-1",
                "project_path": project_path,
                "package_name": "kr.test.app",
            }
            failed_result = {"status": "failed", "project_path": project_path}

            with (
                patch.object(server, "get_task", return_value={}),
                patch.object(server, "run_static_preflight_checks") as preflight,
            ):
                result = server.reconcile_failed_result_with_verified_artifact(task, failed_result)

        self.assertIs(result, failed_result)
        preflight.assert_not_called()


if __name__ == "__main__":
    unittest.main()
