import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from server import server


class StatusPerformanceTests(unittest.TestCase):
    def test_hidden_events_skip_payload_decoding_and_text_processing(self) -> None:
        for event_type in ("agent_raw_output", "app_llm_request", "app_llm_response", "ui_editor_draft_saved"):
            with self.subTest(event_type=event_type), patch.object(
                server, "parse_event_payload", side_effect=AssertionError("unneeded JSON decode")
            ), patch.object(
                server, "sanitize_user_visible_text", side_effect=AssertionError("unneeded text scan")
            ):
                self.assertIsNone(server.task_event_to_timeline_event({
                    "event_type": event_type, "message_text": "x" * 100_000,
                    "payload_json": '{"output":"' + "x" * 100_000 + '"}',
                }))

    def test_status_decodes_state_once_and_keeps_full_stored_records(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            db = server.Database(Path(directory) / "tasks.db")
            db.init_db()
            now = server.utc_now_iso()
            db.create_task(dict(task_id="task", user_id="test", device_id="test", prompt="요청",
                                status="Success", message="완료", created_at=now, updated_at=now))
            state = json.dumps({"status": "success", "verification_notes": ["검증"],
                                "diagnostics": "long record\n" * 100_000})
            db.update_task("task", codex_result_json=state)
            raw_event = db.log_event("task", actor="system", event_type="agent_raw_output",
                                    message_text="raw\n" * 100_000, payload={"raw": "x" * 100_000})
            visible_event = db.log_event("task", actor="user", event_type="user_message", message_text="수정 요청")
            task = db.get_task("task")
            with patch.object(server, "load_task_state_payload", wraps=server.load_task_state_payload) as load:
                status = server.serialize_task_for_status(db, task, 100)
            self.assertEqual(1, load.call_count)
            self.assertEqual([visible_event], [event["event_id"] for event in status["timeline_events"]])
            self.assertEqual(["검증"], status["conversation_state"]["verification_notes"])
            self.assertEqual(state, db.get_task("task")["codex_result_json"])
            stored = {event["event_id"]: event for event in db.list_events("task")}
            self.assertEqual("raw\n" * 100_000, stored[raw_event]["message_text"])

    def test_unchanged_cursor_skips_attachment_query(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            db = server.Database(Path(directory) / "tasks.db")
            db.init_db()
            now = server.utc_now_iso()
            db.create_task(dict(task_id="task", user_id="test", device_id="test", prompt="요청",
                                status="Success", message="완료", created_at=now, updated_at=now))
            cursor = db.log_event("task", actor="user", event_type="user_message", message_text="요청")
            with patch.object(db, "list_task_attachments", side_effect=AssertionError("unneeded query")):
                self.assertEqual([], server.build_task_timeline_events(db, "task", after_event_id=cursor))

    def test_explicit_empty_state_is_not_decoded_again(self) -> None:
        with patch.object(server, "load_task_state_payload", side_effect=AssertionError("extra decode")):
            state = server.build_task_conversation_state({"prompt": "요청"}, state_payload={})
        self.assertEqual("요청", state["initial_user_prompt"])
