from pathlib import Path

from admin_dashboard import build_dashboard_payload
from server import Database


def test_admin_dashboard_payload_summarizes_tasks(tmp_path: Path):
    db = Database(tmp_path / "tasks.db")
    db.init_db()
    db.create_task(
        {
            "task_id": "task-success",
            "user_id": "user-a",
            "device_id": "device-a",
            "phone_number": "01011112222",
            "prompt": "가계부 앱 만들어줘",
            "status": "Success",
            "message": "APK 빌드가 완료되었어요.",
            "app_name": "가계부",
            "package_name": "com.example.budget",
            "apk_url": "http://localhost/download/task-success",
            "input_tokens": 10,
            "cached_input_tokens": 2,
            "output_tokens": 20,
            "reasoning_output_tokens": 5,
            "total_tokens": 35,
            "codex_result_json": None,
            "log": None,
            "created_at": "2026-05-29T00:00:00+00:00",
            "updated_at": "2026-05-29T00:02:00+00:00",
        }
    )
    db.create_task(
        {
            "task_id": "task-failed",
            "user_id": "user-a",
            "device_id": "device-a",
            "phone_number": "01011112222",
            "prompt": "지도 앱 만들어줘",
            "status": "Failed",
            "message": "빌드 실패",
            "app_name": "지도",
            "package_name": "com.example.map",
            "apk_url": None,
            "input_tokens": 3,
            "cached_input_tokens": 0,
            "output_tokens": 7,
            "reasoning_output_tokens": 1,
            "total_tokens": 11,
            "codex_result_json": None,
            "log": None,
            "created_at": "2026-05-29T00:03:00+00:00",
            "updated_at": "2026-05-29T00:04:00+00:00",
        }
    )
    db.log_event(
        "task-failed",
        actor="system",
        event_type="runtime_error_detected",
        message_text="NullPointerException",
        payload={"package_name": "com.example.map", "error_message": "NPE"},
    )
    db.upsert_app_llm_config(
        "task-success",
        {
            "enabled": True,
            "provider": "openai",
            "model": "gpt-5.4-mini",
            "daily_request_limit": 10,
            "daily_token_limit": 1000,
            "max_output_tokens": 700,
            "temperature": 0.4,
        },
    )
    db.record_app_llm_usage(
        task_id="task-success",
        package_name="com.example.budget",
        input_tokens=100,
        output_tokens=50,
        total_tokens=150,
        status="success",
    )

    payload = build_dashboard_payload(db)

    assert payload["overview"]["total_tasks"] == 2
    assert payload["overview"]["success_tasks"] == 1
    assert payload["overview"]["failed_tasks"] == 1
    assert payload["overview"]["total_tokens"] == 46
    assert payload["users"][0]["task_count"] == 2
    assert payload["runtime_errors"][0]["package_name"] == "com.example.map"
    assert payload["app_ai_overview"]["enabled_app_count"] == 1
    assert payload["app_ai_overview"]["total_request_count"] == 1
    assert payload["app_ai_usage_by_app"][0]["today_request_count"] == 1
    assert payload["app_ai_usage_by_app"][0]["request_used_percent"] == 10
    assert payload["app_ai_usage_by_app"][0]["token_used_percent"] == 15
