import tempfile
import unittest
from pathlib import Path

from flutter_apk_server.server import Database, task_event_to_timeline_event, utc_now_iso


class TimelineCursorTests(unittest.TestCase):
    def test_list_events_returns_only_events_after_cursor(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            db = Database(Path(temp_dir) / "tasks.db")
            db.init_db()
            now = utc_now_iso()
            db.create_task(
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
            first_id = db.log_event(
                "task-1",
                actor="user",
                event_type="user_message",
                message_text="first",
                payload={"raw_prompt": "first"},
            )
            second_id = db.log_event(
                "task-1",
                actor="assistant",
                event_type="assistant_message",
                message_text="second",
                payload={},
            )

            events = db.list_events("task-1", after_event_id=first_id)

            self.assertEqual([second_id], [event["event_id"] for event in events])
            self.assertEqual(second_id, db.latest_event_id("task-1"))

    def test_image_only_user_event_keeps_blank_display_body(self) -> None:
        event = task_event_to_timeline_event(
            {
                "event_id": "event-1",
                "task_id": "task-1",
                "actor": "user",
                "event_type": "user_message",
                "message_text": "",
                "payload_json": (
                    '{"raw_prompt":"첨부한 이미지를 참고해서 앱 수정을 진행해줘.",'
                    '"display_prompt":"","attachment_count":1}'
                ),
                "created_at": "2026-07-30T00:00:00+00:00",
            }
        )

        self.assertIsNotNone(event)
        self.assertEqual("", event["body"])

    def test_ui_editor_auto_save_events_are_not_shown_in_chat_timeline(self) -> None:
        for event_type in ("ui_editor_draft_created", "ui_editor_draft_saved"):
            with self.subTest(event_type=event_type):
                event = task_event_to_timeline_event(
                    {
                        "event_id": f"event-{event_type}",
                        "task_id": "task-1",
                        "actor": "user",
                        "event_type": event_type,
                        "message_text": "UI 편집 초안을 자동 저장했습니다.",
                        "payload_json": "{}",
                        "created_at": "2026-08-25T00:00:00+00:00",
                    }
                )

                self.assertIsNone(event)


if __name__ == "__main__":
    unittest.main()
