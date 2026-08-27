import logging
import shutil
import sqlite3
import tempfile
import unittest
from unittest import mock
from pathlib import Path

from flutter_apk_server.project_builder import native_android_project_builder
from flutter_apk_server.server import (
    Database,
    GENERATED_APK_SIDELOAD_VERSION_CODE,
    apply_project_defaults,
    build_subprocess_environment,
    build_codex_followup_build_summary,
    build_intent_decision,
    create_followup_project_revision,
    create_initial_project_revision,
    ensure_project_revision_version,
    project_android_identity_issues,
    render_task_agents_md,
    revision_request_summary,
    serialize_project_revision,
    should_attempt_server_side_build,
    utc_now_iso,
    with_codex_reasoning_effort,
)
from flutter_apk_server.server_settings import UvicornAccessLogQueryFilter, default_codex_command


REPOSITORY_ROOT = Path(__file__).resolve().parents[2]
BASE_PROJECT = REPOSITORY_ROOT / "BaseProject"


class ProjectIdentityTests(unittest.TestCase):
    def test_uvicorn_access_log_filter_removes_query_string(self) -> None:
        record = logging.LogRecord(
            name="uvicorn.access",
            level=logging.INFO,
            pathname=__file__,
            lineno=1,
            msg='%s - "%s %s HTTP/%s" %d',
            args=(
                "127.0.0.1:1234",
                "GET",
                "/status/task-1?phone_number=01000000000&device_id=secret",
                "1.1",
                200,
            ),
            exc_info=None,
        )

        self.assertTrue(UvicornAccessLogQueryFilter().filter(record))
        self.assertEqual("/status/task-1", record.args[2])

    def test_default_codex_command_uses_workspace_sandbox(self) -> None:
        with mock.patch.dict("os.environ", {}, clear=True):
            command = default_codex_command(REPOSITORY_ROOT)

        self.assertIn("--sandbox workspace-write", command)
        self.assertIn("--add-dir", command)
        self.assertIn("{build_cache}", command)
        self.assertIn("--model gpt-5.6-sol", command)
        self.assertNotIn("--dangerously-bypass-approvals-and-sandbox", command)

    def test_codex_subprocess_environment_removes_server_secrets(self) -> None:
        source = {
            "PATH": "/usr/bin",
            "GENERATED_APP_KEYSTORE_PATH": "/secret/generated-app.jks",
            "GENERATED_APP_KEYSTORE_PASSWORD": "store-secret",
            "GENERATED_APP_KEY_ALIAS": "generated",
            "GENERATED_APP_KEY_PASSWORD": "key-secret",
            "APP_RUNTIME_OPENAI_API_KEY": "runtime-secret",
            "ADMIN_API_TOKEN": "admin-secret",
        }

        self.assertEqual({"PATH": "/usr/bin"}, build_subprocess_environment(source))

    def test_codex_followup_summary_does_not_echo_raw_user_request(self) -> None:
        user_prompt = "첫 번째 카드 제목을 더 크게 바꾸고 오른쪽 버튼을 초록색으로 바꿔줘"
        summary = build_codex_followup_build_summary(
            "컬러카드",
            f"기존 컬러카드를 수정할게요. 이번 수정은 {user_prompt}를 반영해요.",
            user_prompt,
        )
        self.assertEqual(
            summary,
            "기존 컬러카드를 수정할게요. 요청한 변경 내용을 현재 앱의 구성에 맞게 반영해요.",
        )

    def test_build_intent_uses_explicit_codex_user_visible_summary(self) -> None:
        summary = "기존 컬러카드를 수정할게요. 카드 제목의 강조를 높이고 동작 버튼을 더 잘 보이게 정돈해요."
        decision = build_intent_decision(
            mode="build",
            task_id="task-1",
            existing_task=True,
            user_prompt="원문 요청",
            request_scope="existing_app_modification",
            suggested_app_name="컬러카드",
            user_visible_summary=summary,
        )
        self.assertEqual(decision.summary, summary)

    def test_followup_reasoning_override_only_replaces_reasoning_config(self) -> None:
        original = [
            "codex",
            "exec",
            "--model",
            "gpt-5.4",
            "-c",
            'model_reasoning_effort="medium"',
            "-c",
            'service_tier="default"',
            "prompt",
        ]
        updated = with_codex_reasoning_effort(original, "low")
        self.assertEqual(original[5], 'model_reasoning_effort="medium"')
        self.assertEqual(updated[5], 'model_reasoning_effort="low"')
        self.assertEqual(updated[7], 'service_tier="default"')
        self.assertEqual(updated[-1], "prompt")

    def test_followup_reasoning_override_inserts_missing_config_before_prompt(self) -> None:
        self.assertEqual(
            with_codex_reasoning_effort(["codex", "exec", "prompt"], "high"),
            ["codex", "exec", "-c", 'model_reasoning_effort="high"', "prompt"],
        )

    def test_task_agent_contract_is_native_android_and_server_managed(self) -> None:
        instructions = render_task_agents_md("test-task")
        for required_text in (
            "Android 전용 네이티브 앱",
            "Kotlin과 Android Views/XML",
            "`gradle.properties`",
            "MainActivity.kt",
            "activity_main.xml",
            "`BuildConfig.VIBE_TASK_ID`",
            "`BuildConfig.VIBE_SERVER_BASE_URL`",
            "release signing",
            "`./gradlew :app:lintDebug`",
            "`assembleRelease`를 직접 실행하지 않는다",
            "project/app/build/outputs/apk/release/app-release.apk",
            '"task_id": "test-task"',
        ):
            self.assertIn(required_text, instructions)
        self.assertNotIn("flutter build apk", instructions.lower())

    def test_database_has_owner_list_indexes(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            database = Database(Path(temp_dir) / "tasks.db")
            database.init_db()
            with sqlite3.connect(database.db_path) as connection:
                index_names = {
                    str(row[0])
                    for row in connection.execute(
                        "SELECT name FROM sqlite_master WHERE type = 'index'"
                    )
                }
        self.assertIn("idx_tasks_user_id_created_at", index_names)
        self.assertIn("idx_tasks_device_id_created_at", index_names)
        self.assertIn("idx_tasks_phone_number_created_at", index_names)

    def test_project_snapshot_persists_its_own_revision_summary(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            database = Database(Path(temp_dir) / "tasks.db")
            database.init_db()
            now = utc_now_iso()
            database.create_task(
                {
                    "task_id": "task-1",
                    "user_id": "test-user",
                    "device_id": "test-device",
                    "prompt": "test",
                    "status": "Success",
                    "message": "ready",
                    "created_at": now,
                    "updated_at": now,
                }
            )
            database.record_project_snapshot(
                task_id="task-1",
                revision_label="rev_0002",
                source="existing_app_modification",
                workspace_path="/tmp/workspace",
                project_path="/tmp/workspace/revisions/rev_0002/project",
                request_summary="두 번째 카드의 제목을 굵게 표시해줘",
            )
            snapshot = database.list_project_snapshots("task-1")[0]
        self.assertEqual(
            revision_request_summary({}, snapshot, []),
            "두 번째 카드의 제목을 굵게 표시해줘",
        )

    def test_current_revision_compares_resolved_project_paths(self) -> None:
        revision = serialize_project_revision(
            {
                "task_id": "task-1",
                "workspace_path": "/tmp/workspace",
                "project_path": "/tmp/workspace/revisions/rev_0002/../rev_0002/project",
            },
            {
                "task_id": "task-1",
                "revision_label": "rev_0002",
                "source": "existing_app_modification",
                "workspace_path": "/tmp/workspace",
                "project_path": "/tmp/workspace/revisions/rev_0002/project",
                "created_at": "2026-07-30T00:00:00+00:00",
            },
        )
        self.assertTrue(revision["is_current"])

    def test_native_project_copy_excludes_build_caches(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            source = Path(temp_dir) / "source"
            destination = Path(temp_dir) / "destination"
            (source / "app/src").mkdir(parents=True)
            (source / "app/src/source.kt").write_text("class Source", encoding="utf-8")
            for cache_name in ("build", ".gradle", ".tooling", ".kotlin"):
                cache_path = source / cache_name
                cache_path.mkdir()
                (cache_path / "cached.bin").write_bytes(b"cache")
            for runner_name in ("logs", ".codex_result"):
                runner_path = source / runner_name
                runner_path.mkdir()
                (runner_path / "internal.txt").write_text("internal", encoding="utf-8")
            (source / "app/src/logs").mkdir()
            (source / "app/src/logs/AppLog.kt").write_text("class AppLog", encoding="utf-8")
            native_android_project_builder.copy_project(source, destination)
            self.assertTrue((destination / "app/src/source.kt").is_file())
            self.assertTrue((destination / "app/src/logs/AppLog.kt").is_file())
            for cache_name in ("build", ".gradle", ".tooling", ".kotlin"):
                self.assertFalse((destination / cache_name).exists())
            for runner_name in ("logs", ".codex_result"):
                self.assertFalse((destination / runner_name).exists())

    def test_native_runtime_contracts_are_restored_without_overwriting_app_ui(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            project_root = Path(temp_dir) / "project"
            native_android_project_builder.copy_project(BASE_PROJECT, project_root)
            llm_relative = Path(
                "app/src/main/kotlin/kr/ac/kangwon/hai/generated/VibeLlmClient.kt"
            )
            activity_relative = Path(
                "app/src/main/kotlin/kr/ac/kangwon/hai/generated/MainActivity.kt"
            )
            (project_root / llm_relative).write_text("// stale runtime client\n", encoding="utf-8")
            activity_text = (project_root / activity_relative).read_text(encoding="utf-8")
            customized_activity = activity_text + "\n// participant UI customization\n"
            (project_root / activity_relative).write_text(customized_activity, encoding="utf-8")

            restored = native_android_project_builder.restore_runtime_contracts(
                BASE_PROJECT,
                project_root,
            )

            self.assertIn(llm_relative.as_posix(), restored)
            self.assertEqual(
                (BASE_PROJECT / llm_relative).read_bytes(),
                (project_root / llm_relative).read_bytes(),
            )
            self.assertEqual(
                customized_activity,
                (project_root / activity_relative).read_text(encoding="utf-8"),
            )

    def test_native_identity_collapses_duplicate_managed_properties(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            project_root = Path(temp_dir) / "project"
            native_android_project_builder.copy_project(BASE_PROJECT, project_root)
            properties_path = project_root / "gradle.properties"
            properties_path.write_text(
                properties_path.read_text(encoding="utf-8")
                + "GENERATED_APP_TASK_ID=stale-task\n"
                + "GENERATED_APP_APPLICATION_ID=kr.ac.kangwon.hai.generated.stale\n",
                encoding="utf-8",
            )

            native_android_project_builder.apply_identity(
                project_root,
                task_id="current-task",
                app_name="현재 앱",
                application_id="kr.ac.kangwon.hai.generated.current",
            )

            managed_lines = [
                line
                for line in properties_path.read_text(encoding="utf-8").splitlines()
                if line.startswith("GENERATED_APP_TASK_ID=")
                or line.startswith("GENERATED_APP_APPLICATION_ID=")
            ]
            self.assertEqual(
                [
                    "GENERATED_APP_APPLICATION_ID=kr.ac.kangwon.hai.generated.current",
                    "GENERATED_APP_TASK_ID=current-task",
                ],
                managed_lines,
            )

    def test_native_cache_pruning_preserves_release_apk_and_metadata(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            project_root = Path(temp_dir)
            release_root = project_root / "app/build/outputs/apk/release"
            release_root.mkdir(parents=True)
            (release_root / "app-release.apk").write_bytes(b"apk")
            (release_root / "output-metadata.json").write_text("{}", encoding="utf-8")
            (project_root / "app/build/intermediates/files").mkdir(parents=True)
            (project_root / "app/build/intermediates/files/cache.bin").write_bytes(b"cache")
            (project_root / "app/build/outputs/logs").mkdir(parents=True)
            (project_root / ".gradle").mkdir()
            (project_root / ".gradle/state.bin").write_bytes(b"cache")
            (project_root / "logs").mkdir()
            (project_root / "logs/build.log").write_text("duplicate", encoding="utf-8")
            (project_root / ".codex_result").mkdir()
            (project_root / ".codex_result/result.json").write_text("{}", encoding="utf-8")

            native_android_project_builder.prune_caches_preserving_release(project_root)

            self.assertTrue((release_root / "app-release.apk").is_file())
            self.assertTrue((release_root / "output-metadata.json").is_file())
            self.assertFalse((project_root / "app/build/intermediates").exists())
            self.assertFalse((project_root / "app/build/outputs/logs").exists())
            self.assertFalse((project_root / ".gradle").exists())
            self.assertFalse((project_root / "logs").exists())
            self.assertFalse((project_root / ".codex_result").exists())

    def test_server_rebuild_decision_prioritizes_valid_results_and_engine_errors(self) -> None:
        auth_issue = ("Error", "auth failed", "codex_auth_error", "engine")
        self.assertTrue(
            should_attempt_server_side_build(
                result_exists=True,
                identity_changed=True,
                timed_out=True,
                engine_issue=None,
            )
        )
        self.assertFalse(
            should_attempt_server_side_build(
                result_exists=True,
                identity_changed=False,
                timed_out=False,
                engine_issue=None,
            )
        )
        self.assertFalse(
            should_attempt_server_side_build(
                result_exists=False,
                identity_changed=True,
                timed_out=False,
                engine_issue=auth_issue,
            )
        )
        self.assertTrue(
            should_attempt_server_side_build(
                result_exists=False,
                identity_changed=False,
                timed_out=False,
                engine_issue=None,
            )
        )

    def test_native_identity_is_structural_and_idempotent(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            project_root = Path(temp_dir) / "project"
            shutil.copytree(BASE_PROJECT, project_root)
            package_name = "kr.ac.kangwon.hai.generated.sample"
            self.assertTrue(
                apply_project_defaults(project_root, "task-123", "샘플 앱", package_name)
            )
            self.assertEqual(
                project_android_identity_issues(project_root, package_name, "task-123"),
                [],
            )
            properties = (project_root / "gradle.properties").read_text(encoding="utf-8")
            self.assertIn(f"GENERATED_APP_APPLICATION_ID={package_name}", properties)
            self.assertIn("GENERATED_APP_TASK_ID=task-123", properties)
            self.assertIn(
                f"GENERATED_APP_VERSION_CODE={GENERATED_APK_SIDELOAD_VERSION_CODE}",
                properties,
            )
            strings = (
                project_root / "app/src/main/res/values/strings.xml"
            ).read_text(encoding="utf-8")
            self.assertIn("샘플 앱", strings)
            self.assertFalse(
                apply_project_defaults(project_root, "task-123", "샘플 앱", package_name)
            )

    def test_all_revisions_use_one_sideload_version_code(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            project_root = Path(temp_dir)
            (project_root / "gradle.properties").write_text(
                "GENERATED_APP_VERSION_CODE=4\nGENERATED_APP_VERSION_NAME=1.2.3\n",
                encoding="utf-8",
            )
            self.assertTrue(ensure_project_revision_version(project_root, "rev_0009"))
            properties = (project_root / "gradle.properties").read_text(encoding="utf-8")
            self.assertIn(
                f"GENERATED_APP_VERSION_CODE={GENERATED_APK_SIDELOAD_VERSION_CODE}",
                properties,
            )
            self.assertFalse(ensure_project_revision_version(project_root, "rev_0001"))

    def test_followup_revisions_preserve_one_install_identity(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            workspace = Path(temp_dir) / "task-workspace"
            task_id = "task-revision-identity"
            app_name = "리비전 검증 앱"
            package_name = "kr.ac.kangwon.hai.generated.revisionidentity"
            project_root, revision_label = create_initial_project_revision(
                workspace,
                BASE_PROJECT,
            )

            for expected_revision in range(1, 7):
                self.assertEqual(f"rev_{expected_revision:04d}", revision_label)
                apply_project_defaults(
                    project_root,
                    task_id,
                    app_name,
                    package_name,
                )
                self.assertEqual(
                    [],
                    project_android_identity_issues(project_root, package_name, task_id),
                )
                properties = (project_root / "gradle.properties").read_text(encoding="utf-8")
                self.assertEqual(
                    1,
                    properties.count(f"GENERATED_APP_APPLICATION_ID={package_name}"),
                )
                self.assertEqual(
                    1,
                    properties.count(f"GENERATED_APP_TASK_ID={task_id}"),
                )
                self.assertEqual(
                    1,
                    properties.count(
                        f"GENERATED_APP_VERSION_CODE={GENERATED_APK_SIDELOAD_VERSION_CODE}"
                    ),
                )
                if expected_revision < 6:
                    project_root, revision_label = create_followup_project_revision(
                        workspace,
                        project_root,
                        BASE_PROJECT,
                    )

    def test_native_validation_rejects_flutter_and_compose(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            flutter_project = Path(temp_dir) / "flutter-project"
            shutil.copytree(BASE_PROJECT, flutter_project)
            (flutter_project / "pubspec.yaml").write_text("name: forbidden\n", encoding="utf-8")
            with self.assertRaisesRegex(RuntimeError, "Flutter/Dart"):
                native_android_project_builder.validate_project_structure(flutter_project)

            compose_project = Path(temp_dir) / "compose-project"
            shutil.copytree(BASE_PROJECT, compose_project)
            main_activity = (
                compose_project
                / "app/src/main/kotlin/kr/ac/kangwon/hai/generated/MainActivity.kt"
            )
            main_activity.write_text(
                main_activity.read_text(encoding="utf-8")
                + "\nimport androidx.compose.runtime.Composable\n",
                encoding="utf-8",
            )
            with self.assertRaisesRegex(RuntimeError, "Compose"):
                native_android_project_builder.validate_project_structure(compose_project)

if __name__ == "__main__":
    unittest.main()
