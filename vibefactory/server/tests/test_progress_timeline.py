from pathlib import Path

from server import build_codex_progress_event, task_event_to_timeline_event


def test_build_stage_events_are_visible_status_timeline_events():
    event = task_event_to_timeline_event(
        {
            "event_id": "evt-1",
            "created_at": "2026-06-05T00:00:00+00:00",
            "actor": "system",
            "event_type": "build_stage_started",
            "message_text": "Flutter 코드를 분석하고 있어요.",
            "payload_json": '{"stage":"analyze","phase":"started","detail":"명령을 실행하고 있어요."}',
        }
    )

    assert event is not None
    assert event["kind"] == "status"
    assert event["title"] == "빌드"
    assert event["body"] == "Flutter 코드를 분석하고 있어요."
    assert event["detail"] == ""


def test_codex_command_execution_becomes_status_progress_event(tmp_path: Path):
    item = {
        "id": "item-1",
        "type": "command_execution",
        "command": "/bin/zsh -lc 'flutter analyze'",
        "status": "in_progress",
        "exit_code": None,
    }

    event = build_codex_progress_event(item, task_id="task-1", workspace_path=tmp_path)

    assert event is not None
    assert event["actor"] == "system"
    assert event["event_type"] == "command_execution"
    assert event["message_text"] == "앱 코드 점검"
    assert event["payload"]["command"] == "flutter analyze"
    assert event["payload"]["phase"] == "started"
    assert event["payload"]["title"] == "점검"


def test_codex_file_change_uses_project_relative_path(tmp_path: Path):
    workspace = tmp_path / "workspace"
    project_file = workspace / "project" / "lib" / "main.dart"
    project_file.parent.mkdir(parents=True)
    project_file.write_text("void main() {}\n", encoding="utf-8")
    item = {
        "id": "item-2",
        "type": "file_change",
        "status": "completed",
        "changes": [{"path": str(project_file), "kind": "update"}],
    }

    event = build_codex_progress_event(item, task_id="task-1", workspace_path=workspace)

    assert event is not None
    assert event["message_text"] == "앱 파일 수정 완료"
    assert event["payload"]["paths"] == ["lib/main.dart"]
    assert event["payload"]["phase"] == "completed"


def test_agent_message_becomes_assistant_timeline_event():
    event = task_event_to_timeline_event(
        {
            "event_id": "evt-2",
            "created_at": "2026-06-05T00:00:00+00:00",
            "actor": "assistant",
            "event_type": "agent_message",
            "message_text": "앱 구조를 확인하고 화면 구성을 준비하고 있어요.",
            "payload_json": "{}",
        }
    )

    assert event is not None
    assert event["kind"] == "assistant"
    assert event["title"] == "AI"
    assert event["body"] == "앱 구조를 확인하고 화면 구성을 준비하고 있어요."
