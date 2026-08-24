import base64
import json
import shutil
import tempfile
import unittest
from dataclasses import replace
from pathlib import Path
from unittest.mock import patch

from fastapi.testclient import TestClient

from flutter_apk_server.server import (
    AppDataDatabase,
    CodexTaskRunner,
    Database,
    create_app,
    load_settings,
    utc_now_iso,
)
from flutter_apk_server.project_builder import NativeAndroidProjectBuilder


REQUIRED_ROUTES = {
    ("GET", "/tasks"),
    ("POST", "/generate"),
    ("GET", "/status/{task_id}"),
    ("POST", "/tasks/{task_id}/cancel"),
    ("PATCH", "/tasks/{task_id}"),
    ("GET", "/tasks/{task_id}/usage"),
    ("GET", "/tasks/{task_id}/revisions"),
    ("GET", "/tasks/{task_id}/revisions/{revision_label}/ui/layouts"),
    ("GET", "/tasks/{task_id}/revisions/{revision_label}/ui/layouts/{layout_name}"),
    ("GET", "/tasks/{task_id}/revisions/{revision_label}/ui/resource"),
    ("GET", "/tasks/{task_id}/revisions/{revision_label}/ui/drafts/{layout_name}"),
    ("PUT", "/tasks/{task_id}/revisions/{revision_label}/ui/drafts/{layout_name}"),
    ("POST", "/tasks/{task_id}/revisions/{revision_label}/ui/drafts/{draft_id}/images"),
    ("GET", "/tasks/{task_id}/revisions/{revision_label}/ui/drafts/{draft_id}/images/{image_id}"),
    ("POST", "/tasks/{task_id}/revisions/{revision_label}/ui/drafts/{draft_id}/submit"),
    ("POST", "/tasks/{task_id}/revisions/{revision_label}/branch"),
    ("POST", "/tasks/{task_id}/runtime-error"),
    ("GET", "/download/{task_id}"),
    ("POST", "/apps/{task_id}/llm/respond"),
    ("GET", "/apps/{task_id}/data/{collection}"),
    ("POST", "/apps/{task_id}/data/{collection}"),
    ("GET", "/apps/{task_id}/data/{collection}/{record_id}"),
    ("PATCH", "/apps/{task_id}/data/{collection}/{record_id}"),
    ("DELETE", "/apps/{task_id}/data/{collection}/{record_id}"),
}

STATUS_FIELDS = {
    "task_id",
    "status",
    "status_display_text",
    "app_name",
    "generated_app_name",
    "package_name",
    "apk_url",
    "apk_path",
    "apk_size_bytes",
    "build_success",
    "conversation_state",
    "timeline_events",
    "timeline_cursor",
    "progress_mode",
    "cancel_allowed",
    "created_at",
    "updated_at",
}

SUMMARY_FIELDS = {
    "task_id",
    "status",
    "status_display_text",
    "app_name",
    "generated_app_name",
    "package_name",
    "initial_user_prompt",
    "apk_url",
    "build_success",
    "created_at",
    "updated_at",
    "last_bubble_at",
    "conversation_state",
}

REVISION_FIELDS = {
    "snapshot_id",
    "task_id",
    "revision_label",
    "version_name",
    "source",
    "created_at",
    "request_summary",
    "apk_path",
    "apk_url",
    "apk_size_bytes",
    "has_apk",
    "can_branch",
    "is_current",
}


class HostApiContractTests(unittest.TestCase):
    def setUp(self) -> None:
        self.temp_dir = tempfile.TemporaryDirectory()
        root = Path(self.temp_dir.name)
        self.workspace = root / "workspaces" / "user_test" / "task_contract"
        self.project = self.workspace / "revisions" / "rev_0001" / "project"
        self.project.mkdir(parents=True)
        self.apk = self.project / "artifact.apk"
        self.apk.write_bytes(b"native-android-apk-contract")

        self.db = Database(root / "tasks.db")
        self.db.init_db()
        self.app_data_db = AppDataDatabase(root / "app_data.db")
        self.app_data_db.init_db()
        now = utc_now_iso()
        self.task_id = "contract-task"
        self.device_id = "contract-device"
        self.phone_number = "01000000000"
        self.package_name = "kr.ac.kangwon.hai.generated.contract"
        self.db.create_task(
            {
                "task_id": self.task_id,
                "user_id": f"phone_{self.phone_number}",
                "device_id": self.device_id,
                "phone_number": self.phone_number,
                "prompt": "계약 검증 앱을 만들어줘",
                "status": "Success",
                "message": "APK 빌드가 완료되었어요.",
                "workspace_path": str(self.workspace),
                "project_path": str(self.project),
                "apk_path": str(self.apk),
                "apk_url": f"http://testserver/download/{self.task_id}",
                "app_name": "계약 검증 앱",
                "package_name": self.package_name,
                "created_at": now,
                "updated_at": now,
            }
        )
        self.db.record_project_snapshot(
            task_id=self.task_id,
            revision_label="rev_0001",
            source="new_app",
            workspace_path=str(self.workspace),
            project_path=str(self.project),
            request_summary="계약 검증 앱을 만들어줘",
        )
        self.db.log_event(
            self.task_id,
            actor="user",
            event_type="user_message",
            message_text="계약 검증 앱을 만들어줘",
        )

        self.settings = replace(
            load_settings(),
            base_project_path=Path(__file__).resolve().parents[2] / "BaseProject",
            workspaces_root=root / "workspaces",
            build_cache_root=root / ".tooling",
            db_path=root / "tasks.db",
            app_data_db_path=root / "app_data.db",
            server_base_url="http://testserver",
            mock_codex=True,
            intent_agent_enabled=False,
        )
        self.runner = CodexTaskRunner(self.settings, self.db)
        self.app = create_app()
        self.app.state.settings = self.settings
        self.app.state.db = self.db
        self.app.state.app_data_db = self.app_data_db
        self.app.state.runner = self.runner
        self.client = TestClient(self.app)

    def tearDown(self) -> None:
        self.temp_dir.cleanup()

    def query(self) -> str:
        return f"device_id={self.device_id}&phone_number={self.phone_number}"

    def test_required_route_methods_and_paths_are_registered(self) -> None:
        actual = {
            (method, route.path)
            for route in self.app.routes
            for method in getattr(route, "methods", set())
        }
        self.assertTrue(REQUIRED_ROUTES.issubset(actual), REQUIRED_ROUTES - actual)

    def test_task_list_and_status_keep_host_required_fields(self) -> None:
        tasks_response = self.client.get(f"/tasks?{self.query()}")
        self.assertEqual(200, tasks_response.status_code)
        tasks = tasks_response.json()["tasks"]
        self.assertEqual(1, len(tasks))
        self.assertTrue(SUMMARY_FIELDS.issubset(tasks[0]), SUMMARY_FIELDS - set(tasks[0]))

        status_response = self.client.get(f"/status/{self.task_id}?{self.query()}")
        self.assertEqual(200, status_response.status_code)
        payload = status_response.json()
        self.assertTrue(STATUS_FIELDS.issubset(payload), STATUS_FIELDS - set(payload))
        self.assertEqual(self.task_id, payload["task_id"])
        self.assertEqual(self.package_name, payload["package_name"])
        self.assertTrue(payload["build_success"])
        self.assertIsInstance(payload["timeline_events"], list)

    def test_revision_response_keeps_host_required_fields(self) -> None:
        response = self.client.get(f"/tasks/{self.task_id}/revisions?{self.query()}")
        self.assertEqual(200, response.status_code)
        payload = response.json()
        self.assertEqual(self.task_id, payload["task_id"])
        self.assertEqual(1, len(payload["revisions"]))
        revision = payload["revisions"][0]
        self.assertTrue(REVISION_FIELDS.issubset(revision), REVISION_FIELDS - set(revision))
        self.assertEqual("rev_0001", revision["revision_label"])
        self.assertTrue(revision["request_summary"])

    def test_download_keeps_apk_headers_and_bytes(self) -> None:
        response = self.client.get(f"/download/{self.task_id}?{self.query()}")
        self.assertEqual(200, response.status_code)
        self.assertEqual(self.apk.read_bytes(), response.content)
        self.assertEqual(
            "application/vnd.android.package-archive",
            response.headers["content-type"],
        )
        self.assertEqual(str(self.apk.stat().st_size), response.headers["content-length"])
        self.assertIn("artifact.apk", response.headers["content-disposition"])

        range_response = self.client.get(
            f"/download/{self.task_id}?{self.query()}",
            headers={"Range": "bytes=0-5"},
        )
        self.assertEqual(206, range_response.status_code)
        self.assertEqual(self.apk.read_bytes()[:6], range_response.content)
        self.assertEqual(
            f"bytes 0-5/{self.apk.stat().st_size}",
            range_response.headers["content-range"],
        )

    def test_cancel_keeps_status_contract(self) -> None:
        now = utc_now_iso()
        task_id = "queued-contract-task"
        self.db.create_task(
            {
                "task_id": task_id,
                "user_id": f"phone_{self.phone_number}",
                "device_id": self.device_id,
                "phone_number": self.phone_number,
                "prompt": "취소 계약 검증",
                "status": "Queued",
                "message": "대기 중",
                "created_at": now,
                "updated_at": now,
            }
        )
        response = self.client.post(f"/tasks/{task_id}/cancel?{self.query()}")
        self.assertEqual(200, response.status_code)
        payload = response.json()
        self.assertTrue(STATUS_FIELDS.issubset(payload), STATUS_FIELDS - set(payload))
        self.assertEqual("Cancelled", payload["status"])
        self.assertFalse(payload["cancel_allowed"])

    def test_oversized_followup_attachment_preserves_existing_task_status(self) -> None:
        oversized_pdf = b"%PDF-1.4\n" + (b"x" * 32)

        with patch("flutter_apk_server.reference_attachments.REFERENCE_PDF_MAX_BYTES", 16):
            response = self.client.post(
                "/generate",
                json={
                    "task_id": self.task_id,
                    "device_id": self.device_id,
                    "phone_number": self.phone_number,
                    "prompt": "첨부한 문서를 반영해줘",
                    "attachments": [
                        {
                            "type": "pdf",
                            "mime_type": "application/pdf",
                            "name": "oversized.pdf",
                            "base64": base64.b64encode(oversized_pdf).decode("ascii"),
                        }
                    ],
                },
            )

        self.assertEqual(400, response.status_code)
        task = self.db.get_task(self.task_id)
        self.assertIsNotNone(task)
        self.assertEqual("Success", task["status"])
        self.assertEqual("APK 빌드가 완료되었어요.", task["message"])
        failed_attachment = self.db.list_task_attachments(self.task_id)[-1]
        self.assertEqual("failed", failed_attachment["status"])
        self.assertEqual("oversized.pdf", failed_attachment["original_name"])
        self.assertIn(
            "attachment_save_failed",
            [event["event_type"] for event in self.db.list_events(self.task_id)],
        )

    def test_branch_creates_immediate_task_with_preserved_package(self) -> None:
        response = self.client.post(
            f"/tasks/{self.task_id}/revisions/rev_0001/branch?{self.query()}"
        )
        self.assertEqual(202, response.status_code)
        payload = response.json()
        self.assertTrue(STATUS_FIELDS.issubset(payload), STATUS_FIELDS - set(payload))
        self.assertNotEqual(self.task_id, payload["task_id"])
        self.assertEqual(self.package_name, payload["package_name"])
        self.assertEqual("Queued", payload["status"])
        self.assertIsNotNone(self.db.get_task(payload["task_id"]))

    def test_branch_worker_copies_revision_and_rewrites_task_identity(self) -> None:
        builder = NativeAndroidProjectBuilder()
        base_project = Path(__file__).resolve().parents[2] / "BaseProject"
        shutil.copytree(
            base_project,
            self.project,
            dirs_exist_ok=True,
            ignore=builder.ignore_copy_entries,
        )
        builder.apply_identity(
            self.project,
            task_id=self.task_id,
            app_name="계약 검증 앱",
            application_id=self.package_name,
        )
        response = self.client.post(
            f"/tasks/{self.task_id}/revisions/rev_0001/branch?{self.query()}"
        )
        self.assertEqual(202, response.status_code)
        branched_task_id = response.json()["task_id"]
        branched_task = self.db.get_task(branched_task_id)
        self.assertIsNotNone(branched_task)

        task_state = json.loads(branched_task["codex_result_json"])
        prepared = self.runner.prepare_branched_task_workspace(branched_task, task_state)
        copied_project = Path(prepared["project_path"])

        self.assertTrue((copied_project / "artifact.apk").is_file())
        self.assertEqual(self.package_name, prepared["package_name"])
        properties = (copied_project / "gradle.properties").read_text(encoding="utf-8")
        self.assertIn(f"GENERATED_APP_TASK_ID={branched_task_id}", properties)
        self.assertNotIn(f"GENERATED_APP_TASK_ID={self.task_id}\n", properties)
        snapshot = self.db.get_project_snapshot(branched_task_id, "rev_0001")
        self.assertIsNotNone(snapshot)
        self.assertEqual(str(copied_project), snapshot["project_path"])

    def test_runtime_error_contract_preserves_full_payload(self) -> None:
        stack_trace = "line-1\nline-2\n" + ("full-stack\n" * 200)
        response = self.client.post(
            f"/tasks/{self.task_id}/runtime-error?{self.query()}",
            json={
                "package_name": self.package_name,
                "summary": "런타임 오류",
                "stack_trace": stack_trace,
                "error_message": "전체 오류 메시지",
                "report_kind": "uncaught_error",
            },
        )
        self.assertEqual(200, response.status_code)
        self.assertEqual(
            {"task_id": self.task_id, "logged": True, "summary": "런타임 오류"},
            response.json(),
        )
        event = self.db.list_events(self.task_id)[-1]
        event_payload = json.loads(event["payload_json"])
        self.assertEqual(stack_trace.strip(), event_payload["stack_trace"])

    def test_runtime_error_rejects_package_from_another_task(self) -> None:
        event_count = len(self.db.list_events(self.task_id))
        response = self.client.post(
            f"/tasks/{self.task_id}/runtime-error?{self.query()}",
            json={
                "package_name": "kr.ac.kangwon.hai.generated.other",
                "summary": "다른 앱에서 온 오류",
                "stack_trace": "java.lang.IllegalStateException: spoofed",
            },
        )

        self.assertEqual(400, response.status_code)
        self.assertEqual("package_name does not match task", response.json()["detail"])
        self.assertEqual(event_count, len(self.db.list_events(self.task_id)))

    def test_generated_app_data_crud_contract(self) -> None:
        collection_url = f"/apps/{self.task_id}/data/notes"
        create_response = self.client.post(
            collection_url,
            json={
                "package_name": self.package_name,
                "owner_id": "participant-1",
                "data": {"title": "첫 기록", "done": False},
            },
        )
        self.assertEqual(200, create_response.status_code)
        record = create_response.json()["record"]
        record_id = record["record_id"]
        self.assertEqual(self.task_id, record["task_id"])
        self.assertEqual(self.package_name, record["package_name"])

        list_response = self.client.get(
            collection_url,
            params={"package_name": self.package_name, "owner_id": "participant-1"},
        )
        self.assertEqual(200, list_response.status_code)
        self.assertEqual([record_id], [item["record_id"] for item in list_response.json()["records"]])

        record_url = f"{collection_url}/{record_id}"
        update_response = self.client.patch(
            record_url,
            json={
                "package_name": self.package_name,
                "owner_id": "participant-1",
                "data": {"done": True},
                "replace": False,
            },
        )
        self.assertEqual(200, update_response.status_code)
        self.assertEqual(
            {"title": "첫 기록", "done": True},
            update_response.json()["record"]["data"],
        )

        delete_response = self.client.delete(
            record_url,
            params={"package_name": self.package_name},
        )
        self.assertEqual(200, delete_response.status_code)
        self.assertTrue(delete_response.json()["deleted"])

        missing_response = self.client.get(
            record_url,
            params={"package_name": self.package_name},
        )
        self.assertEqual(404, missing_response.status_code)


if __name__ == "__main__":
    unittest.main()
