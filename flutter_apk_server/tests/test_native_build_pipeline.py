import json
import os
import shutil
import tempfile
import unittest
from dataclasses import replace
from pathlib import Path
from unittest.mock import patch

from flutter_apk_server.server import (
    CodexTaskRunner,
    Database,
    build_task_workspace,
    load_settings,
    utc_now_iso,
)


REPOSITORY_ROOT = Path(__file__).resolve().parents[2]


class NativeBuildPipelineTests(unittest.TestCase):
    def test_default_storage_paths_are_isolated_from_flutter_service(self) -> None:
        with patch.dict(
            os.environ,
            {
                "WORKSPACES_ROOT": "",
                "BUILD_CACHE_ROOT": "",
                "DB_PATH": "",
                "APP_DATA_DB_PATH": "",
            },
            clear=False,
        ):
            settings = load_settings()

        self.assertEqual("native_workspaces", settings.workspaces_root.name)
        self.assertEqual(".native_tooling", settings.build_cache_root.name)
        self.assertEqual("native_tasks.db", settings.db_path.name)
        self.assertEqual("native_app_data.db", settings.app_data_db_path.name)
        self.assertNotEqual(settings.workspaces_root.parent / "workspaces", settings.workspaces_root)
        self.assertNotEqual(settings.db_path.parent / "tasks.db", settings.db_path)
        self.assertNotEqual(settings.app_data_db_path.parent / "app_data.db", settings.app_data_db_path)

    def test_codex_and_gradle_use_separate_secret_environments(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir, patch.dict(
            os.environ,
            {
                "APP_RUNTIME_OPENAI_API_KEY": "runtime-secret",
                "ADMIN_API_TOKEN": "admin-secret",
            },
            clear=False,
        ):
            root = Path(temp_dir)
            settings = replace(
                load_settings(),
                build_cache_root=root / ".native_tooling",
                generated_app_keystore_path="/secret/generated-app.jks",
                generated_app_keystore_password="store-secret",
                generated_app_key_alias="generated",
                generated_app_key_password="key-secret",
            )
            runner = CodexTaskRunner(settings, Database(root / "tasks.db"))

            codex_env = runner.build_task_env(root / "workspace")
            gradle_env = runner.build_task_env(root / "workspace", include_signing=True)

            for key in (
                "GENERATED_APP_KEYSTORE_PATH",
                "GENERATED_APP_KEYSTORE_PASSWORD",
                "GENERATED_APP_KEY_ALIAS",
                "GENERATED_APP_KEY_PASSWORD",
                "APP_RUNTIME_OPENAI_API_KEY",
                "ADMIN_API_TOKEN",
            ):
                self.assertNotIn(key, codex_env)
            self.assertEqual("/secret/generated-app.jks", gradle_env["GENERATED_APP_KEYSTORE_PATH"])
            self.assertEqual("store-secret", gradle_env["GENERATED_APP_KEYSTORE_PASSWORD"])
            self.assertNotIn("APP_RUNTIME_OPENAI_API_KEY", gradle_env)
            self.assertNotIn("ADMIN_API_TOKEN", gradle_env)

    def test_mock_worker_uses_native_workspace_and_apk_contract(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            settings = replace(
                load_settings(),
                base_project_path=REPOSITORY_ROOT / "BaseProject",
                workspaces_root=root / "native_workspaces",
                build_cache_root=root / ".native_tooling",
                db_path=root / "native_tasks.db",
                app_data_db_path=root / "native_app_data.db",
                server_base_url="http://testserver",
                mock_codex=True,
                intent_agent_enabled=False,
            )
            database = Database(settings.db_path)
            database.init_db()
            now = utc_now_iso()
            task = {
                "task_id": "native-pipeline-task",
                "user_id": "test-user",
                "device_id": "test-device",
                "prompt": "간단한 네이티브 앱을 만들어줘",
                "normalized_prompt": "간단한 네이티브 앱",
                "build_request_prompt": "간단한 네이티브 앱을 만들어줘",
                "status": "Queued",
                "message": "대기 중",
                "app_name": "네이티브 테스트",
                "package_name": "kr.ac.kangwon.hai.generated.nativepipeline",
                "created_at": now,
                "updated_at": now,
            }
            database.create_task(task)
            workspace_path, project_path = build_task_workspace(settings, task)
            database.update_task(
                task["task_id"],
                workspace_path=str(workspace_path),
                project_path=str(project_path),
            )

            runner = CodexTaskRunner(settings, database)
            runner.process_task(task["task_id"])

            completed = database.get_task(task["task_id"])
            self.assertIsNotNone(completed)
            self.assertEqual("Success", completed["status"])
            self.assertEqual(task["package_name"], completed["package_name"])
            self.assertIn(
                "project/app/build/outputs/apk/release/app-release.apk",
                str(completed["apk_path"]),
            )
            self.assertTrue(Path(str(completed["apk_path"])).is_file())
            self.assertTrue((project_path / "settings.gradle.kts").is_file())
            self.assertTrue(
                (
                    project_path
                    / "app/src/main/kotlin/kr/ac/kangwon/hai/generated/MainActivity.kt"
                ).is_file()
            )
            self.assertTrue((project_path / "app/src/main/res/layout/activity_main.xml").is_file())
            self.assertFalse((project_path / "pubspec.yaml").exists())

    def test_gradle_failure_becomes_task_failure_message(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            keystore = root / "generated-app.jks"
            keystore.write_bytes(b"test-keystore-placeholder")
            settings = replace(
                load_settings(),
                base_project_path=REPOSITORY_ROOT / "BaseProject",
                workspaces_root=root / "native_workspaces",
                build_cache_root=root / ".native_tooling",
                db_path=root / "native_tasks.db",
                app_data_db_path=root / "native_app_data.db",
                mock_codex=False,
                intent_agent_enabled=False,
                generated_app_keystore_path=str(keystore),
                generated_app_keystore_password="password",
                generated_app_key_alias="alias",
                generated_app_key_password="password",
            )
            database = Database(settings.db_path)
            database.init_db()
            now = utc_now_iso()
            task = {
                "task_id": "native-build-failure",
                "user_id": "test-user",
                "device_id": "test-device",
                "prompt": "실패 전달 검증",
                "status": "Running",
                "message": "빌드 중",
                "app_name": "실패 검증",
                "package_name": "kr.ac.kangwon.hai.generated.failure",
                "created_at": now,
                "updated_at": now,
            }
            database.create_task(task)
            workspace_path = root / "native_workspaces/user_test/task_failure"
            project_path = workspace_path / "revisions/rev_0001/project"
            project_path.parent.mkdir(parents=True)
            shutil.copytree(REPOSITORY_ROOT / "BaseProject", project_path)
            (workspace_path / "logs").mkdir(parents=True)
            (workspace_path / ".codex_result").mkdir(parents=True)
            database.update_task(
                task["task_id"],
                workspace_path=str(workspace_path),
                project_path=str(project_path),
            )
            runner = CodexTaskRunner(settings, database)
            runner.run_logged_command = lambda *args, **kwargs: (1, False, 0.2)  # type: ignore[method-assign]
            result_path = workspace_path / ".codex_result/task_result.json"

            runner.attempt_server_side_build(
                task["task_id"], workspace_path, result_path, codex_exit_code=0
            )
            result = json.loads(result_path.read_text(encoding="utf-8"))
            self.assertEqual("failed", result["status"])
            self.assertEqual("lint", result["error_stage"])
            runner.finalize_task(task["task_id"], workspace_path, result_path, 0, False)
            failed = database.get_task(task["task_id"])
            self.assertEqual("Failed", failed["status"])
            self.assertIn("Android lint", failed["message"])


if __name__ == "__main__":
    unittest.main()
