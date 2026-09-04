import base64
import hashlib
import io
import json
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
from flutter_apk_server.ui_editor_server import (
    build_ui_editor_codex_prompt,
    structural_xml_diff,
    validate_ui_annotation_xml,
)


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
    <androidx.recyclerview.widget.RecyclerView
        android:id="@+id/list"
        android:layout_width="match_parent"
        android:layout_height="120dp"
        tools:itemCount="2"
        tools:listitem="@layout/editor_item" />
    <LinearLayout
        android:id="@+id/dynamicList"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:orientation="vertical" />
    <TextView
        android:id="@+id/dynamicTitle"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content" />
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
        (resource_root / "xml").mkdir(parents=True)
        (resource_root / "layout" / "activity_main.xml").write_text(LAYOUT_XML, encoding="utf-8")
        (resource_root / "layout" / "editor_item.xml").write_text(
            '<TextView xmlns:android="http://schemas.android.com/apk/res/android" '
            'xmlns:tools="http://schemas.android.com/tools" '
            'android:layout_width="match_parent" android:layout_height="48dp" '
            'tools:text="Preview row" />',
            encoding="utf-8",
        )
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
        self.catalog_path = resource_root / "xml" / "vf_ui_catalog.xml"
        self.catalog_path.write_text(
            """<?xml version="1.0" encoding="utf-8"?>
<ui-catalog schemaVersion="1" guideVersion="rev_0001">
    <layout layoutName="activity_main" configuration="layout" displayName="편집 메인 화면" kind="screen" activityClass="example.MainActivity">
        <element viewId="title" title="제목" description="현재 문서 제목을 확인합니다." order="1" />
    </layout>
    <layout layoutName="editor_item" configuration="layout" displayName="편집 목록 항목" kind="item" />
</ui-catalog>
""",
            encoding="utf-8",
        )
        kotlin_root = self.project / "app" / "src" / "main" / "kotlin" / "example"
        kotlin_root.mkdir(parents=True)
        (kotlin_root / "MainActivity.kt").write_text(
            """fun render() {
    val row = layoutInflater.inflate(R.layout.editor_item, binding.dynamicList, false)
    binding.dynamicList.addView(row)
    binding.dynamicTitle.text = loadDynamicTitle()
}
""",
            encoding="utf-8",
        )

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
        self.assertEqual("편집 메인 화면", list_payload["layouts"][0]["display_name"])
        self.assertEqual("screen", list_payload["layouts"][0]["layout_kind"])
        self.assertTrue(list_payload["layouts"][0]["guide_available"])
        self.assertEqual(1, list_payload["layouts"][0]["guide_element_count"])
        self.assertNotIn(str(self.project), list_response.text)

        detail_response = self.client.get(
            f"{self.endpoint('layouts/activity_main')}?configuration=layout&{self.query()}"
        )
        self.assertEqual(200, detail_response.status_code, detail_response.text)
        detail = detail_response.json()
        self.assertEqual(LAYOUT_XML, detail["xml"])
        self.assertEqual("편집 메인 화면", detail["display_name"])
        self.assertEqual("screen", detail["layout_kind"])
        self.assertTrue(detail["guide_available"])
        self.assertIn('tools:vibeCustom="must-survive"', detail["xml"])
        self.assertIn("com.example.UnsupportedWidget", detail["xml"])
        self.assertEqual(64, len(detail["sha256"]))
        resource_paths = {item["resource_path"] for item in detail["resource_files"]}
        self.assertIn("res/values/strings.xml", resource_paths)
        self.assertIn("res/values/colors.xml", resource_paths)
        self.assertIn("res/drawable/editor_frame.xml", resource_paths)
        self.assertIn("res/layout/editor_item.xml", resource_paths)
        self.assertEqual([], detail["unresolved_resources"])
        self.assertEqual(
            [{"container_id": "dynamicList", "layout_name": "editor_item", "sample_count": 1}],
            detail["preview_children"],
        )
        self.assertEqual(["dynamicTitle"], detail["preview_dynamic_text_view_ids"])

    def test_layout_list_uses_safe_fallback_without_catalog(self) -> None:
        self.catalog_path.unlink()

        response = self.client.get(f"{self.endpoint('layouts')}?{self.query()}")

        self.assertEqual(200, response.status_code, response.text)
        layouts = {item["layout_name"]: item for item in response.json()["layouts"]}
        self.assertEqual("메인 화면", layouts["activity_main"]["display_name"])
        self.assertEqual("screen", layouts["activity_main"]["layout_kind"])
        self.assertFalse(layouts["activity_main"]["guide_available"])
        self.assertEqual("편집 항목", layouts["editor_item"]["display_name"])

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

    def annotation_xml(
        self,
        instruction: str,
        *,
        action: str = "behavior",
        annotation_id: str = "annotation_1",
    ) -> str:
        destination = (
            '<vf:destination-point x="0.750000" y="0.300000" />'
            if action == "move"
            else ""
        )
        return f'''<?xml version="1.0" encoding="utf-8"?>
<vf:ui-annotations xmlns:vf="urn:vibefactory:ui-annotations" schemaVersion="1" taskId="{self.task_id}" revisionLabel="rev_0001" layoutName="activity_main" configuration="layout" baseXmlSha256="{self.base_sha}">
  <vf:annotation id="{annotation_id}" action="{action}" createdAt="2026-09-03T00:00:00Z">
    <vf:target stableId="id:title" resourceId="@+id/title" hierarchyPath="0.0" className="TextView" text="Editor title" contentDescription="" previousSibling="" nextSibling="id:image" left="0.100000" top="0.100000" right="0.600000" bottom="0.200000" />
    {destination}
    <vf:instruction>{instruction}</vf:instruction>
  </vf:annotation>
</vf:ui-annotations>
'''

    def save_draft(
        self,
        *,
        annotation_xml: str | None = None,
        edited_xml: str = LAYOUT_XML,
        draft_id: str | None = None,
        version: int | None = None,
    ):
        payload = {
            "draft_id": draft_id,
            "configuration": "layout",
            "base_xml_sha256": self.base_sha,
            "original_xml": LAYOUT_XML,
            "edited_xml": edited_xml,
            "annotation_xml": annotation_xml or self.annotation_xml("제목을 더 이해하기 쉽게 바꿔 주세요."),
            "descriptions": {},
            "expected_version": version,
            "is_new_layout": False,
        }
        return self.client.put(
            self.endpoint("drafts/activity_main"),
            params={"device_id": self.device_id, "phone_number": self.phone_number},
            json=payload,
        )

    def confirm_draft(self, draft: dict[str, object]):
        return self.client.post(
            self.endpoint(f"drafts/{draft['draft_id']}/confirm"),
            params={"device_id": self.device_id, "phone_number": self.phone_number},
            json={"expected_version": draft["version"]},
        )

    def test_annotation_auto_save_uses_optimistic_lock_and_preserves_base_revision(self) -> None:
        annotation_v1 = self.annotation_xml("첫 번째 변경 표시")
        created = self.save_draft(annotation_xml=annotation_v1)
        self.assertEqual(200, created.status_code, created.text)
        created_payload = created.json()
        self.assertEqual(1, created_payload["version"])
        self.assertEqual("draft", created_payload["status"])

        annotation_v2 = self.annotation_xml("두 번째 변경 표시")
        updated = self.save_draft(
            annotation_xml=annotation_v2,
            draft_id=created_payload["draft_id"],
            version=1,
        )
        self.assertEqual(200, updated.status_code, updated.text)
        self.assertEqual(2, updated.json()["version"])

        stale = self.save_draft(
            annotation_xml=annotation_v1,
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
        self.assertIn("두 번째 변경 표시", saved_events[0]["payload_json"])
        self.assertEqual(LAYOUT_XML, created_payload["original_xml"])
        self.assertEqual(LAYOUT_XML, created_payload["edited_xml"])

        rejected_direct_edit = self.save_draft(
            edited_xml=LAYOUT_XML.replace("@string/editor_title", "직접 수정하면 안 됨"),
            annotation_xml=annotation_v2,
        )
        self.assertEqual(422, rejected_direct_edit.status_code, rejected_direct_edit.text)
        self.assertIn("cannot modify source XML", rejected_direct_edit.text)

        with self.db.connect() as connection:
            draft_foreign_keys = connection.execute(
                "PRAGMA foreign_key_list(ui_editor_drafts)"
            ).fetchall()
            image_foreign_keys = connection.execute(
                "PRAGMA foreign_key_list(ui_editor_images)"
            ).fetchall()
        self.assertTrue(any(row["table"] == "tasks" for row in draft_foreign_keys))
        self.assertTrue(any(row["table"] == "ui_editor_drafts" for row in image_foreign_keys))

    def test_legacy_direct_xml_draft_is_preserved_but_does_not_block_annotations(self) -> None:
        legacy_xml = LAYOUT_XML.replace("@string/editor_title", "Legacy direct edit")
        legacy, created = self.db.save_ui_editor_draft(
            task_id=self.task_id,
            base_revision_label="rev_0001",
            layout_name="activity_main",
            configuration="layout",
            base_xml_sha256=self.base_sha,
            original_xml=LAYOUT_XML,
            edited_xml=legacy_xml,
            annotation_xml="",
            descriptions={"legacy": "direct XML draft"},
            is_new_layout=False,
            draft_id=None,
            expected_version=None,
        )
        self.assertTrue(created)

        hidden = self.client.get(
            self.endpoint("drafts/activity_main"),
            params={"device_id": self.device_id, "phone_number": self.phone_number},
        )
        self.assertEqual(404, hidden.status_code, hidden.text)

        replacement = self.save_draft()
        self.assertEqual(200, replacement.status_code, replacement.text)
        self.assertNotEqual(legacy["draft_id"], replacement.json()["draft_id"])
        self.assertEqual(
            "superseded",
            self.db.get_ui_editor_draft(str(legacy["draft_id"]))["status"],
        )
        self.assertEqual(legacy_xml, self.db.get_ui_editor_draft(str(legacy["draft_id"]))["edited_xml"])

    def test_ui_editor_image_is_optimized_stored_and_linked_to_draft(self) -> None:
        created = self.save_draft()
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

    def test_annotation_image_reference_requires_a_matching_saved_image(self) -> None:
        annotation_xml = self.annotation_xml("이 이미지를 참고해 동작을 바꿔 주세요.").replace(
            "    <vf:instruction>이 이미지를 참고해 동작을 바꿔 주세요.</vf:instruction>",
            "    <vf:instruction>이 이미지를 참고해 동작을 바꿔 주세요.</vf:instruction>\n"
            '    <vf:image-ref id="annotation_image_1" />',
        )
        draft = self.save_draft(annotation_xml=annotation_xml).json()
        missing = self.confirm_draft(draft)
        self.assertEqual(400, missing.status_code, missing.text)
        self.assertIn("referenced UI annotation image is missing", missing.text)

        image_buffer = io.BytesIO()
        Image.new("RGB", (80, 60), "#2563EB").save(image_buffer, format="PNG")
        uploaded = self.client.post(
            self.endpoint(f"drafts/{draft['draft_id']}/images"),
            params={"device_id": self.device_id, "phone_number": self.phone_number},
            json={
                "image_id": "annotation_image_1",
                "element_stable_id": "annotation_1",
                "original_name": "behavior-reference.png",
                "mime_type": "image/png",
                "resource_name": "vibe_annotation_reference",
                "base64": base64.b64encode(image_buffer.getvalue()).decode("ascii"),
            },
        )
        self.assertEqual(200, uploaded.status_code, uploaded.text)
        confirmed = self.confirm_draft(draft)
        self.assertEqual(200, confirmed.status_code, confirmed.text)

        prompt, payload = build_ui_editor_codex_prompt(
            task_id=self.task_id,
            base_revision_label="rev_0001",
            generated_revision_label="rev_0002",
            layout_name="activity_main",
            configuration="layout",
            original_xml=LAYOUT_XML,
            annotation_xml=annotation_xml,
            annotations=[{
                "annotation_id": "annotation_1",
                "action": "behavior",
                "image_ids": ["annotation_image_1"],
            }],
            preview_workspace_path="",
            package_name="example.xml.editor",
            app_name="XML editor fixture",
            source_context=[],
            images=[{
                "annotation_id": "annotation_1",
                "image_id": "annotation_image_1",
                "workspace_path": ".ui_editor_drafts/reference.jpg",
            }],
        )
        self.assertEqual("annotation_image_1", payload["images"][0]["image_id"])
        self.assertIn("부연 설명에 첨부된 참고 이미지", prompt)
        self.assertIn(".ui_editor_drafts/reference.jpg", prompt)

    def test_annotation_schema_and_annotated_preview_are_persisted_with_integrity(self) -> None:
        move_xml = self.annotation_xml("카드 아래로 이동", action="move")
        parsed_move = validate_ui_annotation_xml(
            move_xml,
            task_id=self.task_id,
            revision_label="rev_0001",
            layout_name="activity_main",
            configuration="layout",
            base_xml_sha256=self.base_sha,
        )
        self.assertEqual({"x": 0.75, "y": 0.3}, parsed_move[0]["destination_point"])
        move_prompt, _ = build_ui_editor_codex_prompt(
            task_id=self.task_id,
            base_revision_label="rev_0001",
            generated_revision_label="rev_0002",
            layout_name="activity_main",
            configuration="layout",
            original_xml=LAYOUT_XML,
            annotation_xml=move_xml,
            annotations=parsed_move,
            preview_workspace_path="",
            package_name="example.xml.editor",
            app_name="XML editor fixture",
            source_context=[],
        )
        self.assertIn("사용자가 화살표 끝을 놓은 정확한 정규화 좌표", move_prompt)
        self.assertIn("그 View의 중심으로 좌표를 바꾸지 않는다", move_prompt)
        draft = self.save_draft(annotation_xml=move_xml).json()
        preview_buffer = io.BytesIO()
        Image.new("RGB", (320, 640), "#F5F5F5").save(preview_buffer, format="PNG")
        confirmed = self.client.post(
            self.endpoint(f"drafts/{draft['draft_id']}/confirm"),
            params={"device_id": self.device_id, "phone_number": self.phone_number},
            json={
                "expected_version": draft["version"],
                "preview_image_base64": base64.b64encode(preview_buffer.getvalue()).decode("ascii"),
            },
        )
        self.assertEqual(200, confirmed.status_code, confirmed.text)
        stored = self.db.get_ui_editor_draft(draft["draft_id"])
        self.assertEqual(move_xml, stored["annotation_xml"])
        self.assertEqual(LAYOUT_XML, stored["edited_xml"])
        self.assertEqual(64, len(stored["preview_sha256"]))
        self.assertGreater(stored["preview_size_bytes"], 0)
        preview_path = self.workspace / stored["preview_workspace_path"]
        self.assertTrue(preview_path.is_file())
        self.assertEqual(hashlib.sha256(preview_path.read_bytes()).hexdigest(), stored["preview_sha256"])

        malformed = move_xml.replace('action="move"', 'action="unknown"')
        rejected = self.save_draft(annotation_xml=malformed)
        self.assertEqual(400, rejected.status_code, rejected.text)
        self.assertIn("invalid UI annotation action", rejected.text)

    def test_confirmed_draft_is_available_to_chat_until_materially_changed(self) -> None:
        annotation = self.annotation_xml("저장한 UI 제목으로 변경")
        draft = self.save_draft(annotation_xml=annotation).json()
        before = self.client.get(
            f"/tasks/{self.task_id}/ui/editor-context",
            params={"device_id": self.device_id, "phone_number": self.phone_number},
        )
        self.assertEqual(200, before.status_code, before.text)
        self.assertFalse(before.json()["has_saved_ui"])

        confirmed = self.confirm_draft(draft)
        self.assertEqual(200, confirmed.status_code, confirmed.text)
        self.assertEqual("saved", confirmed.json()["status"])
        self.assertTrue(confirmed.json()["draft"]["confirmed_at"])

        available = self.client.get(
            f"/tasks/{self.task_id}/ui/editor-context",
            params={"device_id": self.device_id, "phone_number": self.phone_number},
        )
        self.assertEqual(200, available.status_code, available.text)
        self.assertTrue(available.json()["has_saved_ui"])
        self.assertEqual(1, available.json()["saved_draft_count"])

        unchanged = self.save_draft(
            annotation_xml=annotation,
            draft_id=draft["draft_id"],
            version=draft["version"],
        )
        self.assertEqual(200, unchanged.status_code, unchanged.text)
        self.assertTrue(unchanged.json()["confirmed_at"])

        changed = self.save_draft(
            annotation_xml=self.annotation_xml("다시 편집한 제목으로 변경"),
            draft_id=draft["draft_id"],
            version=unchanged.json()["version"],
        )
        self.assertEqual(200, changed.status_code, changed.text)
        self.assertIsNone(changed.json()["confirmed_at"])

    def test_checked_chat_request_attaches_confirmed_ui_to_normal_revision(self) -> None:
        draft = self.save_draft(
            annotation_xml=self.annotation_xml("채팅에서 반영할 제목으로 변경")
        ).json()
        confirmed = self.confirm_draft(draft)
        self.assertEqual(200, confirmed.status_code, confirmed.text)
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
            generated = self.client.post(
                "/generate",
                json={
                    "task_id": self.task_id,
                    "device_id": self.device_id,
                    "phone_number": self.phone_number,
                    "prompt": "저장한 배치를 유지하고 제목 동작도 수정해줘",
                    "use_ui_editor_draft": True,
                },
            )
        self.assertEqual(200, generated.status_code, generated.text)
        fake_runner.enqueue.assert_called_once_with(self.task_id)
        prompt = (self.workspace / "prompt.md").read_text(encoding="utf-8")
        self.assertIn("채팅 요청에 선택된 저장 UI", prompt)
        self.assertIn("채팅에서 반영할 제목으로 변경", prompt)
        self.assertIn("주석 전용 XML", prompt)
        self.assertIn("저장한 배치를 유지하고 제목 동작도 수정해줘", prompt)
        attached_event = next(
            event
            for event in self.db.list_events(self.task_id)
            if event["event_type"] == "ui_editor_chat_context_attached"
        )
        self.assertIn("채팅에서 반영할 제목으로 변경", attached_event["payload_json"])
        queued_task = self.db.get_task(self.task_id)
        self.assertEqual("rev_0002", Path(queued_task["project_path"]).parent.name)
        queued_state = json.loads(queued_task["codex_result_json"])
        self.assertEqual("ui_editor_revision", queued_state["task_operation"])
        self.assertEqual([draft["draft_id"]], queued_state["ui_editor_draft_ids"])
        submitted_draft = self.db.get_ui_editor_draft(draft["draft_id"])
        self.assertEqual("submitting", submitted_draft["status"])
        self.assertEqual("rev_0002", submitted_draft["generated_revision_label"])

        self.runner.finalize_ui_editor_draft(self.task_id, "succeeded")
        self.assertEqual("succeeded", self.db.get_ui_editor_draft(draft["draft_id"])["status"])

    def test_submit_creates_new_revision_prompt_without_mutating_base(self) -> None:
        draft = self.save_draft(
            annotation_xml=self.annotation_xml("사용자가 확정한 제목으로 변경")
        ).json()
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
        self.assertIn("사용자가 확정한 제목으로 변경", prompt)
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
        self.assertIn("사용자가 확정한 제목으로 변경", input_events[0]["payload_json"])

    def test_new_layout_is_rejected_and_annotation_prompt_requires_codex(self) -> None:
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
                "annotation_xml": "<vf:ui-annotations xmlns:vf=\"urn:vibefactory:ui-annotations\" />",
                "descriptions": {},
                "is_new_layout": True,
            },
        )
        self.assertEqual(422, created.status_code, created.text)
        self.assertIn("existing layout", created.text)

        prompt, payload = build_ui_editor_codex_prompt(
            task_id=self.task_id,
            base_revision_label="rev_0001",
            generated_revision_label="rev_0002",
            layout_name="activity_detail",
            configuration="layout",
            original_xml=LAYOUT_XML,
            annotation_xml=self.annotation_xml("이 요소를 제거해 주세요", action="delete"),
            annotations=[{"annotation_id": "annotation_1", "action": "delete"}],
            preview_workspace_path="",
            package_name="example.xml.editor",
            app_name="XML editor fixture",
            source_context=[],
        )
        self.assertEqual("delete", payload["annotations"][0]["action"])
        self.assertIn("실제 프로젝트 코드를 수정", prompt)
        self.assertIn("원본 XML은 사용자가 수정한 결과물이 아니다", prompt)

        changed = structural_xml_diff(
            LAYOUT_XML,
            LAYOUT_XML.replace("<ImageView", "<Button").replace("</androidx", "</androidx"),
        )
        self.assertTrue(changed["changed"])

    def test_codex_output_and_draft_completion_are_logged_without_truncation(self) -> None:
        draft = self.save_draft().json()
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
