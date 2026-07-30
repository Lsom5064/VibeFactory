import tempfile
import unittest
from pathlib import Path

from flutter_apk_server.server import apply_project_defaults, should_attempt_server_side_build


class ProjectIdentityTests(unittest.TestCase):
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


if __name__ == "__main__":
    unittest.main()
