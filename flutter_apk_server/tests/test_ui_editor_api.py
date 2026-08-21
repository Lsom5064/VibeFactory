import base64
import hashlib
import io
import shutil
import tempfile
import unittest
from dataclasses import replace
from pathlib import Path
from unittest.mock import Mock, patch

from fastapi.testclient import TestClient
from PIL import Image

import flutter_apk_server.server as server_module
from flutter_apk_server.server import (
    AppDataDatabase,
    CodexTaskRunner,
    Database,
    create_app,
    load_settings,
    utc_now_iso,
)
from flutter_apk_server.ui_editor_server import build_ui_editor_codex_prompt, structural_xml_diff


LAYOUT_XML = """<?xml version="1.0" encoding="utf-8"?>
<androidx.constraintlayout.widget.ConstraintLayout
    xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    xmlns:tools="http://schemas.android.com/tools"
    android:id="@+id/root"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    tools:vibeCustom="must-survive">
    <TextView
        android:id="@+id/title"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:text="@string/editor_title"
        android:textColor="@color/editor_accent" />
    <ImageView
        android:id="@+id/image"
        android:layout_width="120dp"
        android:layout_height="80dp"
        app:srcCompat="@drawable/editor_frame" />
    <com.example.UnsupportedWidget
        android:id="@+id/unsupported"
        android:layout_width="32dp"
        android:layout_height="32dp"
        android:tag="preserve-me" />
</androidx.constraintlayout.widget.ConstraintLayout>
"""


class UiEditorApiTests(unittest.TestCase):
    def setUp(self) -> None:
        self.temp_dir = tempfile.TemporaryDirectory()
        root = Path(self.temp_dir.name)
        self.workspaces_root = root / "workspaces"
        self.workspace = self.workspaces_root / "user_editor" / "task_editor"
        self.project = self.workspace / "revisions" / "rev_0001" / "project"
        resource_root = self.project / "app" / "src" / "main" / "res"
        (resource_root / "layout").mkdir(parents=True)
        (resource_root / "values").mkdir(parents=True)
        (resource_root / "drawable").mkdir(parents=True)
        (resource_root / "layout" / "activity_main.xml").write_text(LAYOUT_XML, encoding="utf-8")
        (resource_root / "values" / "strings.xml").write_text(
            '<resources><string name="editor_title">Editor title</string></resources>',
            encoding="utf-8",
        )
        (resource_root / "values" / "colors.xml").write_text(
            '<resources><color name="editor_accent">#126E52</color></resources>',
            encoding="utf-8",
        )
        (resource_root / "drawable" / "editor_frame.xml").write_text(
            '<shape xmlns:android="http://schemas.android.com/apk/res/android">'
            '<solid android:color="@color/editor_accent" /></shape>',
            encoding="utf-8",
        )
        self.photo_bytes = b"\x89PNG\r\n\x1a\neditor-test"
        (resource_root / "drawable" / "editor_photo.png").write_bytes(self.photo_bytes)

        self.db = Database(root / "tasks.db")
        self.db.init_db()
        self.app_data_db = AppDataDatabase(root / "app_data.db")
        self.app_data_db.init_db()
        self.task_id = "xml-editor-task"
        self.device_id = "xml-editor-device"
        self.phone_number = "01012345678"
        now = utc_now_iso()
        self.db.create_task(
            {
                "task_id": self.task_id,
                "user_id": f"phone_{self.phone_number}",
                "device_id": self.device_id,
                "phone_number": self.phone_number,
                "prompt": "XML editor fixture",
                "status": "Success",
                "message": "ready",
                "workspace_path": str(self.workspace),
                "project_path": str(self.project),
                "app_name": "XML editor fixture",
                "package_name": "example.xml.editor",
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
            request_summary="XML editor fixture",
        )
        self.settings = replace(
            load_settings(),
            base_project_path=root / "BaseProject",
            workspaces_root=self.workspaces_root,
            build_cache_root=root / "build-cache",
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
        self.base_sha = hashlib.sha256(LAYOUT_XML.encode("utf-8")).hexdigest()

    def tearDown(self) -> None:
        self.temp_dir.cleanup()

    def query(self) -> str:
        return f"device_id={self.device_id}&phone_number={self.phone_number}"

    def endpoint(self, suffix: str) -> str:
        return f"/tasks/{self.task_id}/revisions/rev_0001/ui/{suffix}"

    def test_layout_list_and_document_preserve_canonical_xml(self) -> None:
        list_response = self.client.get(f"{self.endpoint('layouts')}?{self.query()}")
        self.assertEqual(200, list_response.status_code, list_response.text)
        list_payload = list_response.json()
        self.assertTrue(list_payload["source_available"])
        self.assertEqual("activity_main", list_payload["layouts"][0]["layout_name"])
        self.assertNotIn(str(self.project), list_response.text)

        detail_response = self.client.get(
            f"{self.endpoint('layouts/activity_main')}?configuration=layout&{self.query()}"
        )
        self.assertEqual(200, detail_response.status_code, detail_response.text)
        detail = detail_response.json()
        self.assertEqual(LAYOUT_XML, detail["xml"])
        self.assertIn('tools:vibeCustom="must-survive"', detail["xml"])
        self.assertIn("com.example.UnsupportedWidget", detail["xml"])
        self.assertEqual(64, len(detail["sha256"]))
        resource_paths = {item["resource_path"] for item in detail["resource_files"]}
        self.assertIn("res/values/strings.xml", resource_paths)
        self.assertIn("res/values/colors.xml", resource_paths)
        self.assertIn("res/drawable/editor_frame.xml", resource_paths)
        self.assertEqual([], detail["unresolved_resources"])

    def test_binary_resource_download_uses_allowlisted_relative_path(self) -> None:
        response = self.client.get(
            self.endpoint("resource"),
            params={
                "resource_path": "res/drawable/editor_photo.png",
                "device_id": self.device_id,
                "phone_number": self.phone_number,
            },
        )
        self.assertEqual(200, response.status_code, response.text)
        self.assertEqual(self.photo_bytes, response.content)
        self.assertEqual("image/png", response.headers["content-type"])

    def test_access_control_matches_task_ownership(self) -> None:
        response = self.client.get(
            self.endpoint("layouts"),
            params={"device_id": "other-device", "phone_number": "01099999999"},
        )
        self.assertEqual(404, response.status_code)

    def test_invalid_paths_and_revision_labels_are_rejected(self) -> None:
        traversal = self.client.get(
            self.endpoint("resource"),
            params={"resource_path": "res/drawable/../../secret.pem", "device_id": self.device_id},
        )
        self.assertEqual(400, traversal.status_code)

        invalid_configuration = self.client.get(
            self.endpoint("layouts/activity_main"),
            params={"configuration": "layout/../../tmp", "device_id": self.device_id},
        )
        self.assertEqual(400, invalid_configuration.status_code)

        invalid_revision = self.client.get(
            f"/tasks/{self.task_id}/revisions/current/ui/layouts",
            params={"device_id": self.device_id},
        )
        self.assertEqual(400, invalid_revision.status_code)

    def test_dtd_and_entity_declarations_are_rejected(self) -> None:
        unsafe_xml = (
            '<?xml version="1.0"?><!DOCTYPE x [<!ENTITY read SYSTEM "file:///etc/passwd">]>'
            '<TextView xmlns:android="http://schemas.android.com/apk/res/android" '
            'android:text="&read;" />'
        )
        layout_path = self.project / "app" / "src" / "main" / "res" / "layout" / "unsafe.xml"
        layout_path.write_text(unsafe_xml, encoding="utf-8")
        response = self.client.get(
            self.endpoint("layouts/unsafe"),
            params={"device_id": self.device_id},
        )
        self.assertEqual(400, response.status_code)
        self.assertNotIn("/etc/passwd", response.text)

    def test_source_outside_server_workspace_is_reported_unavailable(self) -> None:
        outside_project = Path(self.temp_dir.name) / "outside" / "project"
        outside_project.mkdir(parents=True)
        self.db.record_project_snapshot(
            task_id=self.task_id,
            revision_label="rev_0002",
            source="test",
            workspace_path=str(outside_project.parent),
            project_path=str(outside_project),
        )
        response = self.client.get(
            f"/tasks/{self.task_id}/revisions/rev_0002/ui/layouts",
            params={"device_id": self.device_id},
        )
        self.assertEqual(200, response.status_code, response.text)
        self.assertFalse(response.json()["source_available"])
        self.assertEqual([], response.json()["layouts"])
        self.assertNotIn(str(outside_project), response.text)

    def test_symlink_resource_cannot_escape_project(self) -> None:
        outside_file = Path(self.temp_dir.name) / "outside.png"
        outside_file.write_bytes(b"outside")
        symlink = self.project / "app" / "src" / "main" / "res" / "drawable" / "escaped.png"
        try:
            symlink.symlink_to(outside_file)
        except OSError as exc:
            self.skipTest(f"symlink is unavailable: {exc}")
        response = self.client.get(
            self.endpoint("resource"),
            params={"resource_path": "res/drawable/escaped.png", "device_id": self.device_id},
        )
        self.assertEqual(400, response.status_code)

    def save_draft(self, *, edited_xml: str, draft_id: str | None = None, version: int | None = None):
        payload = {
            "draft_id": draft_id,
            "configuration": "layout",
            "base_xml_sha256": self.base_sha,
            "original_xml": LAYOUT_XML,
            "edited_xml": edited_xml,
            "descriptions": {"id:title": "첫 화면 제목이며 앱의 목적을 설명합니다."},
            "expected_version": version,
            "is_new_layout": False,
        }
        return self.client.put(
            self.endpoint("drafts/activity_main"),
            params={"device_id": self.device_id, "phone_number": self.phone_number},
            json=payload,
        )

    def test_draft_auto_save_uses_optimistic_lock_and_preserves_base_revision(self) -> None:
        edited_v1 = LAYOUT_XML.replace("@string/editor_title", "첫 번째 편집")
        created = self.save_draft(edited_xml=edited_v1)
        self.assertEqual(200, created.status_code, created.text)
        created_payload = created.json()
        self.assertEqual(1, created_payload["version"])
        self.assertEqual("draft", created_payload["status"])

        edited_v2 = edited_v1.replace("첫 번째 편집", "두 번째 편집")
        updated = self.save_draft(
            edited_xml=edited_v2,
            draft_id=created_payload["draft_id"],
            version=1,
        )
        self.assertEqual(200, updated.status_code, updated.text)
        self.assertEqual(2, updated.json()["version"])

        stale = self.save_draft(
            edited_xml=edited_v1,
            draft_id=created_payload["draft_id"],
            version=1,
        )
        self.assertEqual(409, stale.status_code, stale.text)
        self.assertEqual(2, stale.json()["detail"]["current"]["version"])
        self.assertEqual(
            LAYOUT_XML,
            (self.project / "app/src/main/res/layout/activity_main.xml").read_text(encoding="utf-8"),
        )
        saved_events = [
            event
            for event in self.db.list_events(self.task_id)
            if event["event_type"] == "ui_editor_draft_saved"
        ]
        self.assertEqual(1, len(saved_events))
        self.assertIn("두 번째 편집", saved_events[0]["payload_json"])

        with self.db.connect() as connection:
            draft_foreign_keys = connection.execute(
                "PRAGMA foreign_key_list(ui_editor_drafts)"
            ).fetchall()
            image_foreign_keys = connection.execute(
                "PRAGMA foreign_key_list(ui_editor_images)"
            ).fetchall()
        self.assertTrue(any(row["table"] == "tasks" for row in draft_foreign_keys))
        self.assertTrue(any(row["table"] == "ui_editor_drafts" for row in image_foreign_keys))

    def test_ui_editor_image_is_optimized_stored_and_linked_to_draft(self) -> None:
        created = self.save_draft(edited_xml=LAYOUT_XML)
        draft_id = created.json()["draft_id"]
        image_buffer = io.BytesIO()
        Image.new("RGB", (40, 20), "#15803D").save(image_buffer, format="PNG")
        image_response = self.client.post(
            self.endpoint(f"drafts/{draft_id}/images"),
            params={"device_id": self.device_id, "phone_number": self.phone_number},
            json={
                "image_id": "editor_image_1",
                "element_stable_id": "id:image",
                "original_name": "reference.png",
                "mime_type": "image/png",
                "resource_name": "vibe_editor_reference",
                "base64": base64.b64encode(image_buffer.getvalue()).decode("ascii"),
            },
        )
        self.assertEqual(200, image_response.status_code, image_response.text)
        image = image_response.json()["image"]
        self.assertEqual("image/jpeg", image["mime_type"])
        stored = self.workspace / image["workspace_path"]
        self.assertTrue(stored.is_file())
        self.assertEqual(hashlib.sha256(stored.read_bytes()).hexdigest(), image["sha256"])
        self.assertEqual(40, image["metadata"]["original_width"])
        downloaded = self.client.get(
            self.endpoint(f"drafts/{draft_id}/images/{image['image_id']}"),
            params={"device_id": self.device_id, "phone_number": self.phone_number},
        )
        self.assertEqual(200, downloaded.status_code, downloaded.text)
        self.assertEqual(stored.read_bytes(), downloaded.content)

        attachments = self.db.list_task_attachments(self.task_id)
        self.assertTrue(any(item["source"] == "ui_editor" for item in attachments))

    def test_submit_creates_new_revision_prompt_without_mutating_base(self) -> None:
        edited = LAYOUT_XML.replace("@string/editor_title", "사용자가 확정한 제목")
        draft = self.save_draft(edited_xml=edited).json()
        (self.workspace / "prompt.md").write_text("# Original request\n", encoding="utf-8")
        fake_runner = Mock()
        self.app.state.runner = fake_runner

        def fake_create_revision(workspace: Path, source: Path, _base: Path):
            destination = workspace / "revisions" / "rev_0002" / "project"
            shutil.copytree(source, destination)
            return destination, "rev_0002"

        with (
            patch.object(server_module, "create_followup_project_revision", side_effect=fake_create_revision),
            patch.object(server_module, "apply_project_defaults", return_value=False),
        ):
            submitted = self.client.post(
                self.endpoint(f"drafts/{draft['draft_id']}/submit"),
                params={"device_id": self.device_id, "phone_number": self.phone_number},
                json={"expected_version": draft["version"]},
            )
        self.assertEqual(202, submitted.status_code, submitted.text)
        fake_runner.enqueue.assert_called_once_with(self.task_id)
        self.assertEqual("submitting", submitted.json()["draft"]["status"])
        self.assertEqual("rev_0002", submitted.json()["draft"]["generated_revision_label"])
        self.assertEqual(
            LAYOUT_XML,
            (self.project / "app/src/main/res/layout/activity_main.xml").read_text(encoding="utf-8"),
        )
        prompt = (self.workspace / "prompt.md").read_text(encoding="utf-8")
        self.assertIn("사용자가 확정한 제목", prompt)
        self.assertIn("기준 Revision: `rev_0001`", prompt)
        self.assertIn("현재 Kotlin/Java/XML 소스 전문", prompt)
        queued = self.db.get_task(self.task_id)
        self.assertEqual("Queued", queued["status"])
        self.assertEqual("rev_0002", Path(queued["project_path"]).parent.name)

        input_events = [
            event
            for event in self.db.list_events(self.task_id)
            if event["event_type"] == "ui_editor_codex_input"
        ]
        self.assertEqual(1, len(input_events))
        self.assertIn("사용자가 확정한 제목", input_events[0]["payload_json"])

    def test_new_layout_draft_and_no_op_prompt_still_require_codex(self) -> None:
        blank_xml = """<?xml version="1.0" encoding="utf-8"?>
<androidx.constraintlayout.widget.ConstraintLayout
    xmlns:android="http://schemas.android.com/apk/res/android"
    android:id="@+id/root_detail"
    android:layout_width="match_parent"
    android:layout_height="match_parent" />"""
        created = self.client.put(
            self.endpoint("drafts/activity_detail"),
            params={"device_id": self.device_id, "phone_number": self.phone_number},
            json={
                "configuration": "layout",
                "base_xml_sha256": hashlib.sha256(blank_xml.encode("utf-8")).hexdigest(),
                "original_xml": blank_xml,
                "edited_xml": blank_xml,
                "descriptions": {},
                "is_new_layout": True,
            },
        )
        self.assertEqual(200, created.status_code, created.text)
        self.assertTrue(created.json()["is_new_layout"])

        prompt, payload = build_ui_editor_codex_prompt(
            task_id=self.task_id,
            base_revision_label="rev_0001",
            generated_revision_label="rev_0002",
            layout_name="activity_detail",
            configuration="layout",
            original_xml=blank_xml,
            edited_xml=blank_xml,
            descriptions={},
            images=[],
            preview_workspace_path="",
            package_name="example.xml.editor",
            app_name="XML editor fixture",
            source_context=[],
        )
        self.assertFalse(payload["diff"]["changed"])
        self.assertIn("단순 변경이라도 반드시 실제 프로젝트 코드를 수정", prompt)

        changed = structural_xml_diff(
            LAYOUT_XML,
            LAYOUT_XML.replace("<ImageView", "<Button").replace("</androidx", "</androidx"),
        )
        self.assertTrue(changed["changed"])

    def test_codex_output_and_draft_completion_are_logged_without_truncation(self) -> None:
        draft = self.save_draft(edited_xml=LAYOUT_XML).json()
        self.assertTrue(
            self.db.transition_ui_editor_draft(
                draft["draft_id"],
                expected_status="draft",
                status="submitting",
                expected_version=draft["version"],
            )
        )
        self.db.update_task(
            self.task_id,
            codex_result_json=(
                '{"task_operation":"ui_editor_revision","ui_editor_draft_id":"'
                + draft["draft_id"]
                + '","ui_editor_base_revision":"rev_0001","ui_editor_generated_revision":"rev_0002"}'
            ),
        )
        logs = self.workspace / "logs"
        result_path = self.workspace / ".codex_result" / "task_result.json"
        logs.mkdir(parents=True, exist_ok=True)
        result_path.parent.mkdir(parents=True, exist_ok=True)
        full_marker = "FULL-CODEX-OUTPUT-" + ("x" * 5000)
        (logs / "codex_stdout.log").write_text(full_marker, encoding="utf-8")
        (logs / "codex_stderr.log").write_text("full stderr", encoding="utf-8")
        result_path.write_text('{"status":"failed","message":"full result"}', encoding="utf-8")

        self.runner.log_ui_editor_codex_completion(
            task_id=self.task_id,
            workspace_path=self.workspace,
            result_path=result_path,
            exit_code=1,
            timed_out=False,
            usage=None,
        )
        self.runner.finalize_ui_editor_draft(self.task_id, "failed")

        output_event = next(
            event
            for event in self.db.list_events(self.task_id)
            if event["event_type"] == "ui_editor_codex_output"
        )
        self.assertIn(full_marker, output_event["payload_json"])
        self.assertEqual("failed", self.db.get_ui_editor_draft(draft["draft_id"])["status"])


if __name__ == "__main__":
    unittest.main()
