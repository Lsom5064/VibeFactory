import base64
import json
import os
import tempfile
import unittest
from io import BytesIO
from pathlib import Path
from unittest.mock import patch

from fastapi.testclient import TestClient
from PIL import Image

from flutter_apk_server.server import (
    Database,
    GenerateAttachmentPayload,
    REFERENCE_IMAGE_MAX_DIMENSION,
    REFERENCE_IMAGE_MAX_STORED_BYTES,
    REFERENCE_PDF_MAX_BYTES,
    append_followup_prompt,
    create_app,
    load_settings,
    normalize_reference_attachments,
    run_spec_clarification_agent,
    save_reference_file_attachment_result,
    save_reference_image_attachment_result,
    utc_now_iso,
)


class FullLoggingAndImageStorageTests(unittest.TestCase):
    def test_generate_attachment_payload_supports_installed_pydantic_version(self) -> None:
        attachment = GenerateAttachmentPayload(
            type="image",
            mime_type="image/png",
            name="screen.png",
            base64="encoded-image",
        )

        normalized = normalize_reference_attachments([attachment])

        self.assertEqual(
            normalized,
            [
                {
                    "type": "image",
                    "mime_type": "image/png",
                    "name": "screen.png",
                    "base64": "encoded-image",
                    "workspace_path": "",
                }
            ],
        )

    def test_normalize_reference_attachments_preserves_pdf_and_text_files(self) -> None:
        normalized = normalize_reference_attachments(
            [
                GenerateAttachmentPayload(
                    type="pdf",
                    mime_type="application/pdf",
                    name="requirements.pdf",
                    base64="pdf-data",
                ),
                GenerateAttachmentPayload(
                    type="text",
                    mime_type="text/markdown",
                    name="notes.md",
                    base64="text-data",
                ),
            ]
        )

        self.assertEqual([item["type"] for item in normalized], ["pdf", "text"])
        self.assertEqual(normalized[0]["mime_type"], "application/pdf")
        self.assertEqual(normalized[1]["mime_type"], "text/markdown")

    def test_reference_pdf_is_saved_without_being_treated_as_an_image(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            pdf_bytes = b"%PDF-1.4\n1 0 obj\n<<>>\nendobj\n%%EOF\n"

            result = save_reference_file_attachment_result(
                Path(temp_dir),
                attachment_type="pdf",
                attachment_name="requirements.pdf",
                attachment_mime_type="application/pdf",
                attachment_base64=base64.b64encode(pdf_bytes).decode("ascii"),
            )

            self.assertEqual(result["status"], "saved")
            self.assertEqual(result["mime_type"], "application/pdf")
            self.assertLessEqual(result["size_bytes"], REFERENCE_PDF_MAX_BYTES)
            stored_path = Path(temp_dir) / result["workspace_path"]
            self.assertEqual(stored_path.read_bytes(), pdf_bytes)
            self.assertEqual(stored_path.parent.name, "reference_files")

    def test_generate_logs_and_persists_pdf_attachment(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            pdf_bytes = b"%PDF-1.4\n1 0 obj\n<<>>\nendobj\n%%EOF\n"
            environment = {
                "BASE_PROJECT_PATH": str(root / "base"),
                "WORKSPACES_ROOT": str(root / "workspaces"),
                "DB_PATH": str(root / "tasks.db"),
                "APP_DATA_DB_PATH": str(root / "app_data.db"),
                "MOCK_CODEX": "1",
                "INTENT_AGENT_ENABLED": "0",
            }

            with patch.dict(os.environ, environment, clear=False):
                app = create_app()
                with TestClient(app) as client:
                    response = client.post(
                        "/generate",
                        json={
                            "device_id": "test-device",
                            "prompt": "Use the attached requirements.",
                            "attachments": [
                                {
                                    "type": "pdf",
                                    "mime_type": "application/pdf",
                                    "name": "requirements.pdf",
                                    "base64": base64.b64encode(pdf_bytes).decode("ascii"),
                                }
                            ],
                        },
                    )

                    self.assertEqual(response.status_code, 200, response.text)
                    task_id = response.json()["task_id"]
                    user_event = next(
                        event
                        for event in app.state.db.list_events(task_id)
                        if event["event_type"] == "user_message"
                    )
                    event_payload = json.loads(user_event["payload_json"])
                    self.assertEqual(event_payload["attachment_count"], 1)
                    attachments = app.state.db.list_task_attachments(task_id)
                    self.assertEqual(len(attachments), 1)
                    self.assertEqual(attachments[0]["kind"], "pdf")
                    self.assertTrue(Path(attachments[0]["absolute_path"]).is_file())
                    task = app.state.db.get_task(task_id)
                    conversation_state = json.loads(task["codex_result_json"])["conversation_state"]
                    saved_reference = conversation_state["reference_attachments"][0]
                    self.assertEqual(saved_reference["type"], "pdf")
                    self.assertTrue(saved_reference["workspace_path"].endswith(".pdf"))

    def test_spec_agent_context_includes_attachment_metadata_without_base64(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            with patch.dict(
                os.environ,
                {
                    "BASE_PROJECT_PATH": str(Path(temp_dir) / "base"),
                    "WORKSPACES_ROOT": str(Path(temp_dir) / "workspaces"),
                    "DB_PATH": str(Path(temp_dir) / "tasks.db"),
                    "APP_DATA_DB_PATH": str(Path(temp_dir) / "app_data.db"),
                },
                clear=False,
            ):
                settings = load_settings()
                with patch(
                    "flutter_apk_server.server.run_openai_structured_agent",
                    return_value=None,
                ) as mocked_agent:
                    result = run_spec_clarification_agent(
                        settings,
                        prompt="Use the attached requirements.",
                        task_id="task-pdf-context",
                        existing_task=False,
                        reference_attachments=[
                            {
                                "type": "pdf",
                                "mime_type": "application/pdf",
                                "name": "requirements.pdf",
                                "base64": "sensitive-payload",
                                "workspace_path": "reference_files/requirements.pdf",
                            }
                        ],
                    )

                self.assertIsNone(result)
                user_content = mocked_agent.call_args.kwargs["user_content"]
                context_text = next(
                    item["text"]
                    for item in user_content
                    if item.get("type") == "input_text" and item.get("text", "").startswith("Context JSON:\n")
                )
                context = json.loads(context_text.removeprefix("Context JSON:\n"))
                self.assertEqual(context["reference_attachments"][0]["type"], "pdf")
                self.assertEqual(context["reference_attachments"][0]["name"], "requirements.pdf")
                self.assertNotIn("base64", context["reference_attachments"][0])

    def test_followup_prompt_lists_saved_pdf_for_codex(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            workspace = Path(temp_dir)
            (workspace / "prompt.md").write_text("# Prompt\n", encoding="utf-8")

            append_followup_prompt(
                workspace,
                "Use the attached PDF.",
                reference_attachments=[
                    {
                        "type": "pdf",
                        "mime_type": "application/pdf",
                        "name": "requirements.pdf",
                        "workspace_path": "reference_files/requirements.pdf",
                    }
                ],
            )

            prompt_text = (workspace / "prompt.md").read_text(encoding="utf-8")
            self.assertIn("종류: pdf", prompt_text)
            self.assertIn("reference_files/requirements.pdf", prompt_text)

    def test_generate_accepts_image_without_prompt_text(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            source_image = Image.new("RGB", (64, 64), "#4caf50")
            source_output = BytesIO()
            source_image.save(source_output, format="PNG")
            source_image.close()
            environment = {
                "BASE_PROJECT_PATH": str(root / "base"),
                "WORKSPACES_ROOT": str(root / "workspaces"),
                "DB_PATH": str(root / "tasks.db"),
                "APP_DATA_DB_PATH": str(root / "app_data.db"),
                "MOCK_CODEX": "1",
                "INTENT_AGENT_ENABLED": "0",
            }

            with patch.dict(os.environ, environment, clear=False):
                app = create_app()
                with TestClient(app) as client:
                    response = client.post(
                        "/generate",
                        json={
                            "device_id": "test-device",
                            "prompt": "",
                            "display_prompt": "",
                            "attachments": [
                                {
                                    "type": "image",
                                    "mime_type": "image/png",
                                    "name": "screen.png",
                                    "base64": base64.b64encode(source_output.getvalue()).decode("ascii"),
                                }
                            ],
                        },
                    )

                    self.assertEqual(response.status_code, 200, response.text)
                    task_id = response.json()["task_id"]
                    user_event = next(
                        event
                        for event in app.state.db.list_events(task_id)
                        if event["event_type"] == "user_message"
                    )
                    event_payload = json.loads(user_event["payload_json"])
                    self.assertEqual(user_event["message_text"], "")
                    self.assertEqual(event_payload["raw_prompt"], "")
                    self.assertEqual(event_payload["display_prompt"], "")
                    self.assertEqual(event_payload["attachment_count"], 1)
                    self.assertEqual(len(app.state.db.list_task_attachments(task_id)), 1)

    def test_task_event_preserves_full_message_and_payload(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            database = Database(Path(temp_dir) / "tasks.db")
            database.init_db()
            now = utc_now_iso()
            database.create_task(
                {
                    "task_id": "task-full-log",
                    "user_id": "test-user",
                    "device_id": "test-device",
                    "prompt": "test",
                    "status": "Success",
                    "message": "ready",
                    "created_at": now,
                    "updated_at": now,
                }
            )
            message = "사용자 입력 " + ("가나다라마바사" * 10_000)
            payload_text = "stdout\n" + ("0123456789" * 20_000)

            database.log_event(
                "task-full-log",
                actor="system",
                event_type="full_log_test",
                message_text=message,
                payload={"stdout": payload_text},
            )

            events = database.list_events("task-full-log")
            self.assertEqual(len(events), 1)
            self.assertEqual(events[0]["message_text"], message)
            payload = json.loads(events[0]["payload_json"])
            self.assertEqual(payload["stdout"], payload_text)

    def test_reference_image_is_resized_and_saved_as_bounded_jpeg(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            source = Image.effect_noise((4200, 2800), 80).convert("RGB")
            source_output = BytesIO()
            source.save(source_output, format="JPEG", quality=95)
            source.close()
            source_bytes = source_output.getvalue()

            result = save_reference_image_attachment_result(
                Path(temp_dir),
                reference_image_name="large-reference.png",
                reference_image_base64=base64.b64encode(source_bytes).decode("ascii"),
            )

            self.assertEqual(result["status"], "saved")
            self.assertEqual(result["mime_type"], "image/jpeg")
            self.assertLessEqual(result["size_bytes"], REFERENCE_IMAGE_MAX_STORED_BYTES)
            self.assertLessEqual(result["stored_width"], REFERENCE_IMAGE_MAX_DIMENSION)
            self.assertLessEqual(result["stored_height"], REFERENCE_IMAGE_MAX_DIMENSION)
            self.assertEqual(result["original_width"], 4200)
            self.assertEqual(result["original_height"], 2800)
            stored_path = Path(temp_dir) / result["workspace_path"]
            self.assertTrue(stored_path.is_file())
            with Image.open(stored_path) as stored_image:
                self.assertEqual(stored_image.format, "JPEG")
                self.assertEqual(stored_image.size, (result["stored_width"], result["stored_height"]))

    def test_app_llm_interaction_logs_full_text_and_persists_image(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            task_id = "task-runtime-log"
            package_name = "kr.ac.kangwon.hai.generated.runtime"
            user_message = "사용자 질문 " + ("원문보존" * 4_000)
            response_message = "모델 응답 " + ("전문응답" * 4_000)
            raw_response_text = "raw " + ("response-data" * 4_000)
            source_image = Image.new("RGB", (2400, 1800), "#4caf50")
            source_output = BytesIO()
            source_image.save(source_output, format="PNG")
            source_image.close()

            environment = {
                "BASE_PROJECT_PATH": str(root / "base"),
                "WORKSPACES_ROOT": str(root / "workspaces"),
                "DB_PATH": str(root / "tasks.db"),
                "APP_DATA_DB_PATH": str(root / "app_data.db"),
                "MOCK_CODEX": "1",
                "INTENT_AGENT_ENABLED": "0",
            }
            with patch.dict(os.environ, environment, clear=False):
                app = create_app()
                with TestClient(app) as client:
                    database = app.state.db
                    now = utc_now_iso()
                    database.create_task(
                        {
                            "task_id": task_id,
                            "user_id": "test-user",
                            "device_id": "test-device",
                            "prompt": "test",
                            "status": "Success",
                            "message": "ready",
                            "package_name": package_name,
                            "created_at": now,
                            "updated_at": now,
                        }
                    )
                    database.upsert_app_llm_config(
                        task_id,
                        {
                            "enabled": True,
                            "provider": "openai",
                            "model": "test-model",
                            "api_key": "test-key",
                            "base_url": "https://example.invalid/v1/responses",
                            "system_prompt": "전체 시스템 프롬프트",
                            "daily_request_limit": 100,
                            "daily_token_limit": 50_000,
                            "max_output_tokens": 0,
                            "temperature": 0.4,
                        },
                    )

                    with patch(
                        "flutter_apk_server.server.invoke_app_runtime_model",
                        return_value={
                            "message": response_message,
                            "usage": {
                                "input_tokens": 100,
                                "output_tokens": 200,
                                "total_tokens": 300,
                            },
                            "raw_response": {"output_text": raw_response_text},
                        },
                    ):
                        response = client.post(
                            f"/apps/{task_id}/llm/respond",
                            json={
                                "package_name": package_name,
                                "user_message": user_message,
                                "context": "전체 컨텍스트",
                                "image_base64": base64.b64encode(source_output.getvalue()).decode("ascii"),
                                "image_mime_type": "image/png",
                            },
                        )

                    self.assertEqual(response.status_code, 200, response.text)
                    events = database.list_events(task_id)
                    request_event = next(event for event in events if event["event_type"] == "app_llm_request")
                    response_event = next(event for event in events if event["event_type"] == "app_llm_response")
                    attachment_event = next(event for event in events if event["event_type"] == "attachment_saved")
                    self.assertEqual(request_event["message_text"], user_message)
                    self.assertEqual(response_event["message_text"], response_message)
                    response_payload = json.loads(response_event["payload_json"])
                    self.assertEqual(response_payload["raw_response"]["output_text"], raw_response_text)
                    attachment_payload = json.loads(attachment_event["payload_json"])
                    self.assertGreater(attachment_payload["original_width"], attachment_payload["stored_width"])
                    self.assertGreater(attachment_payload["original_size_bytes"], attachment_payload["size_bytes"])

                    attachments = database.list_task_attachments(task_id)
                    self.assertEqual(len(attachments), 1)
                    self.assertEqual(attachments[0]["event_id"], request_event["event_id"])
                    self.assertEqual(attachments[0]["status"], "saved")
                    stored_path = Path(attachments[0]["absolute_path"])
                    self.assertTrue(stored_path.is_file())
                    with Image.open(stored_path) as stored_image:
                        self.assertLessEqual(max(stored_image.size), REFERENCE_IMAGE_MAX_DIMENSION)

    def test_app_llm_missing_key_is_logged_but_not_exposed_to_the_client(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            task_id = "task-runtime-missing-key"
            package_name = "kr.ac.kangwon.hai.generated.runtime.missingkey"
            environment = {
                "BASE_PROJECT_PATH": str(root / "base"),
                "WORKSPACES_ROOT": str(root / "workspaces"),
                "DB_PATH": str(root / "tasks.db"),
                "APP_DATA_DB_PATH": str(root / "app_data.db"),
                "MOCK_CODEX": "1",
                "INTENT_AGENT_ENABLED": "0",
                "APP_RUNTIME_OPENAI_API_KEY": "",
            }
            with patch.dict(os.environ, environment, clear=False):
                app = create_app()
                with TestClient(app) as client:
                    database = app.state.db
                    now = utc_now_iso()
                    database.create_task(
                        {
                            "task_id": task_id,
                            "user_id": "test-user",
                            "device_id": "test-device",
                            "prompt": "test",
                            "status": "Success",
                            "message": "ready",
                            "package_name": package_name,
                            "created_at": now,
                            "updated_at": now,
                        }
                    )
                    database.upsert_app_llm_config(
                        task_id,
                        {
                            "enabled": True,
                            "provider": "openai",
                            "model": "test-model",
                            "api_key": "",
                            "base_url": "https://api.openai.com/v1/responses",
                            "system_prompt": "test prompt",
                            "daily_request_limit": 100,
                            "daily_token_limit": 50_000,
                            "max_output_tokens": 0,
                            "temperature": 0.4,
                        },
                    )

                    response = client.post(
                        f"/apps/{task_id}/llm/respond",
                        json={
                            "package_name": package_name,
                            "user_message": "설정 오류 로깅 확인",
                        },
                    )

                    self.assertEqual(503, response.status_code)
                    self.assertEqual("app llm runtime unavailable", response.json()["detail"])
                    self.assertNotIn("api key", response.text.lower())
                    error_event = next(
                        event
                        for event in database.list_events(task_id)
                        if event["event_type"] == "app_llm_error"
                    )
                    self.assertEqual("missing api key", error_event["message_text"])
                    error_payload = json.loads(error_event["payload_json"])
                    self.assertEqual("configuration_error", error_payload["error_type"])


if __name__ == "__main__":
    unittest.main()
