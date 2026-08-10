import sqlite3
import tempfile
import unittest
from pathlib import Path

from flutter_apk_server.server import (
    Database,
    GENERATED_APK_SIDELOAD_VERSION_CODE,
    apply_project_defaults,
    build_codex_followup_build_summary,
    build_intent_decision,
    built_apk_identity,
    ensure_project_revision_version,
    flutter_no_pub_args,
    project_android_identity_issues,
    render_task_agents_md,
    revision_request_summary,
    serialize_project_revision,
    should_attempt_server_side_build,
    with_codex_reasoning_effort,
)


class ProjectIdentityTests(unittest.TestCase):
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

    def test_task_agent_contract_keeps_server_managed_identity(self) -> None:
        instructions = render_task_agents_md("test-task")

        for immutable_identity in (
            "Task ID",
            "Android package name",
            "`applicationId`",
            "`namespace`",
            "Kotlin package",
            "`MainActivity`",
            "release debug signing",
            "`CrashHandler.initialize(...)`",
        ):
            self.assertIn(immutable_identity, instructions)
        self.assertIn('"task_id": "test-task"', instructions)
        self.assertIn("app-release.apk", instructions)

    def test_task_agent_contract_delegates_final_apk_build_to_server(self) -> None:
        instructions = render_task_agents_md("test-task")

        self.assertIn("`flutter build apk`를 직접 실행하지 않는다", instructions)
        self.assertIn("`flutter pub get`과 `flutter analyze`", instructions)
        self.assertIn(
            "project/build/app/outputs/flutter-apk/app-release.apk",
            instructions,
        )

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

    def test_flutter_no_pub_is_used_only_with_resolved_dependencies(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            project_root = Path(temp_dir)

            self.assertEqual(flutter_no_pub_args(project_root), [])

            package_config = project_root / ".dart_tool/package_config.json"
            package_config.parent.mkdir(parents=True)
            package_config.write_text("{}", encoding="utf-8")

            self.assertEqual(flutter_no_pub_args(project_root), ["--no-pub"])

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
        self.assertFalse(
            should_attempt_server_side_build(
                result_exists=False,
                identity_changed=True,
                timed_out=True,
                engine_issue=None,
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

    def test_apply_project_defaults_restores_branch_identity(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            project_root = Path(temp_dir)
            manifest_path = project_root / "android/app/src/main/AndroidManifest.xml"
            gradle_path = project_root / "android/app/build.gradle.kts"
            kotlin_path = project_root / "android/app/src/main/kotlin/example/MainActivity.kt"
            dart_path = project_root / "lib/main.dart"

            manifest_path.parent.mkdir(parents=True)
            gradle_path.parent.mkdir(parents=True, exist_ok=True)
            kotlin_path.parent.mkdir(parents=True)
            dart_path.parent.mkdir(parents=True)

            manifest_path.write_text(
                '<application android:label="Wrong App" />\n',
                encoding="utf-8",
            )
            gradle_path.write_text(
                """
android {
    namespace = "example.wrong"
    defaultConfig {
        applicationId = "example.wrong"
    }
}

flutter {
    source = "../.."
}
""".strip()
                + "\n",
                encoding="utf-8",
            )
            kotlin_path.write_text(
                "package example.wrong\n\nclass MainActivity\n",
                encoding="utf-8",
            )
            dart_path.write_text(
                """
const String _packageName = 'example.branch';
const String _taskId = 'wrong-task-id';
const String _runtimeEndpoint =
    'http://server.example/apps/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa/llm/respond';

void main() {
  CrashHandler.initialize("source-task", "example.wrong");
  final client = Client(
    taskId: 'source-task',
    packageName: 'example.branch',
  );
  runApp(const App());
}
""".strip()
                + "\n",
                encoding="utf-8",
            )

            expected_package = "kr.ac.kangwon.hai.generated.original"
            changed = apply_project_defaults(
                project_root,
                "branched-task",
                "Branch App",
                expected_package,
            )

            self.assertTrue(changed)
            self.assertIn('android:label="Branch App"', manifest_path.read_text(encoding="utf-8"))
            self.assertIn(f'namespace = "{expected_package}"', gradle_path.read_text(encoding="utf-8"))
            self.assertIn(f'applicationId = "{expected_package}"', gradle_path.read_text(encoding="utf-8"))
            self.assertIn("signingConfigs.getByName(\"debug\")", gradle_path.read_text(encoding="utf-8"))
            self.assertIn(f"package {expected_package}", kotlin_path.read_text(encoding="utf-8"))
            self.assertIn(
                f'CrashHandler.initialize("branched-task", "{expected_package}");',
                dart_path.read_text(encoding="utf-8"),
            )
            self.assertIn(
                f"const String _packageName = '{expected_package}';",
                dart_path.read_text(encoding="utf-8"),
            )
            self.assertIn(
                "const String _taskId = 'branched-task';",
                dart_path.read_text(encoding="utf-8"),
            )
            self.assertIn(
                "/apps/branched-task/llm/respond",
                dart_path.read_text(encoding="utf-8"),
            )
            self.assertIn(
                "packageName: 'kr.ac.kangwon.hai.generated.original'",
                dart_path.read_text(encoding="utf-8"),
            )
            self.assertIn(
                "taskId: 'branched-task'",
                dart_path.read_text(encoding="utf-8"),
            )
            self.assertFalse(
                apply_project_defaults(
                    project_root,
                    "branched-task",
                    "Branch App",
                    expected_package,
                )
            )

    def test_apply_project_defaults_restores_all_kotlin_package_references(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            project_root = Path(temp_dir)
            gradle_path = project_root / "android/app/build.gradle.kts"
            kotlin_root = project_root / "android/app/src/main/kotlin/example"
            main_activity_path = kotlin_root / "MainActivity.kt"
            service_path = kotlin_root / "NotificationCaptureService.kt"
            store_path = kotlin_root / "NotificationInboxStore.kt"

            gradle_path.parent.mkdir(parents=True)
            kotlin_root.mkdir(parents=True)
            gradle_path.write_text(
                """
android {
    namespace = "kr.ac.kangwon.hai.generated.customappsource01"
    defaultConfig {
        applicationId = "kr.ac.kangwon.hai.generated.customappsource01"
    }
}
""".strip()
                + "\n",
                encoding="utf-8",
            )
            main_activity_path.write_text(
                """
package kr.ac.kangwon.hai.generated.customappsource01

import kr.ac.kangwon.hai.generated.customappbranch02.NotificationInboxStore

class MainActivity {
    val store = NotificationInboxStore()
}
""".strip()
                + "\n",
                encoding="utf-8",
            )
            service_path.write_text(
                """
package kr.ac.kangwon.hai.generated.customappbranch02

class NotificationCaptureService
""".strip()
                + "\n",
                encoding="utf-8",
            )
            store_path.write_text(
                """
package kr.ac.kangwon.hai.generated.customappbranch02

class NotificationInboxStore
""".strip()
                + "\n",
                encoding="utf-8",
            )

            expected_package = "kr.ac.kangwon.hai.generated.customappsource01"
            self.assertTrue(
                apply_project_defaults(
                    project_root,
                    "branched-task",
                    "Branch App",
                    expected_package,
                )
            )

            for kotlin_path in (main_activity_path, service_path, store_path):
                kotlin_text = kotlin_path.read_text(encoding="utf-8")
                self.assertIn(f"package {expected_package}", kotlin_text)
                self.assertNotIn("customappbranch02", kotlin_text)
            self.assertEqual(project_android_identity_issues(project_root, expected_package), [])
            self.assertFalse(
                apply_project_defaults(
                    project_root,
                    "branched-task",
                    "Branch App",
                    expected_package,
                )
            )

    def test_project_android_identity_issues_reports_unmanaged_kotlin_package(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            project_root = Path(temp_dir)
            gradle_path = project_root / "android/app/build.gradle.kts"
            kotlin_path = project_root / "android/app/src/main/kotlin/example/Service.kt"
            gradle_path.parent.mkdir(parents=True)
            kotlin_path.parent.mkdir(parents=True)
            gradle_path.write_text(
                """
android {
    namespace = "example.expected"
    defaultConfig {
        applicationId = "example.expected"
    }
}
""".strip()
                + "\n",
                encoding="utf-8",
            )
            kotlin_path.write_text("package example.unexpected\n", encoding="utf-8")

            issues = project_android_identity_issues(project_root, "example.expected")

            self.assertEqual(len(issues), 1)
            self.assertIn("example.unexpected", issues[0])

    def test_project_defaults_remove_package_suffixes_and_override_all_application_ids(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            project_root = Path(temp_dir)
            gradle_path = project_root / "android/app/build.gradle.kts"
            gradle_path.parent.mkdir(parents=True)
            gradle_path.write_text(
                """
android {
    namespace = "example.wrong"
    defaultConfig {
        applicationId = "example.wrong"
        applicationIdSuffix = ".revision"
    }
    productFlavors {
        create("demo") {
            applicationId = "example.demo"
        }
    }
}

flutter {
    source = "../.."
}
""".strip()
                + "\n",
                encoding="utf-8",
            )

            expected_package = "kr.ac.kangwon.hai.generated.stable"
            self.assertTrue(
                apply_project_defaults(
                    project_root,
                    "task-1",
                    "Stable App",
                    expected_package,
                )
            )

            gradle_text = gradle_path.read_text(encoding="utf-8")
            self.assertEqual(gradle_text.count(f'applicationId = "{expected_package}"'), 2)
            self.assertNotIn("applicationIdSuffix", gradle_text)

    def test_all_revisions_use_one_sideload_version_code(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            project_root = Path(temp_dir)
            pubspec_path = project_root / "pubspec.yaml"
            pubspec_path.write_text("name: sample\nversion: 1.2.3+4\n", encoding="utf-8")

            self.assertTrue(ensure_project_revision_version(project_root, "rev_0009"))
            self.assertIn(
                f"version: 1.2.3+{GENERATED_APK_SIDELOAD_VERSION_CODE}",
                pubspec_path.read_text(encoding="utf-8"),
            )
            self.assertFalse(ensure_project_revision_version(project_root, "rev_0001"))

    def test_built_apk_identity_reads_gradle_output_metadata(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            project_root = Path(temp_dir)
            apk_path = (
                project_root
                / "build/app/outputs/flutter-apk/app-release.apk"
            )
            apk_path.parent.mkdir(parents=True)
            apk_path.write_bytes(b"apk")
            metadata_path = (
                project_root
                / "build/app/outputs/apk/release/output-metadata.json"
            )
            metadata_path.parent.mkdir(parents=True)
            metadata_path.write_text(
                """
{
  "applicationId": "kr.ac.kangwon.hai.generated.sample",
  "elements": [
    {
      "versionCode": 1900000000,
      "outputFile": "app-release.apk"
    }
  ]
}
""".strip(),
                encoding="utf-8",
            )

            self.assertEqual(
                built_apk_identity(project_root, apk_path),
                ("kr.ac.kangwon.hai.generated.sample", 1_900_000_000),
            )


if __name__ == "__main__":
    unittest.main()
