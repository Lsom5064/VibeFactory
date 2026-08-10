import json
import tempfile
import unittest
from pathlib import Path

from flutter_apk_server.server import (
    CodexTaskRunner,
    Database,
    load_settings,
    serialize_task_for_status,
    utc_now_iso,
)


class TaskFailureContractTests(unittest.TestCase):
    def test_failure_updates_status_events_and_user_message_consistently(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            db = Database(Path(temp_dir) / "tasks.db")
            db.init_db()
            now = utc_now_iso()
            task_id = "task-failure-contract"
            db.create_task(
                {
                    "task_id": task_id,
                    "user_id": "test-user",
                    "device_id": "test-device",
                    "prompt": "test",
                    "status": "Running",
                    "message": "running",
                    "codex_result_json": json.dumps(
                        {
                            "message": "이전 작업 메시지",
                            "conversation_state": {"initial_user_prompt": "test"},
                        },
                        ensure_ascii=False,
                    ),
                    "created_at": now,
                    "updated_at": now,
                }
            )
            runner = CodexTaskRunner(load_settings(), db)

            runner.finalize_failure(
                task_id,
                message="APK 결과를 확인하지 못했어요.",
                log_text="full failure log",
                usage_update_fields={},
                usage=None,
                codex_model="gpt-test",
                stage="APK 결과 확인",
            )

            task = db.get_task(task_id)
            self.assertIsNotNone(task)
            self.assertEqual("Failed", task["status"])
            self.assertEqual("APK 결과를 확인하지 못했어요.", task["message"])
            self.assertEqual(
                ["task_failed", "build_stage_failed"],
                [event["event_type"] for event in db.list_events(task_id)],
            )
            status = serialize_task_for_status(db, task, log_line_limit=10)
            self.assertEqual("APK 결과를 확인하지 못했어요.", status["latest_failure_message"])
            self.assertEqual("이전 작업 메시지", status["latest_assistant_message"])


if __name__ == "__main__":
    unittest.main()
