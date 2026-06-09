from server import build_intent_decision, decide_intent, fallback_decide_intent


def test_existing_app_modification_does_not_get_new_app_default_questions():
    decision = build_intent_decision(
        mode="ask_confirmation",
        task_id="task-1",
        existing_task=True,
        existing_workspace_ready=True,
        user_prompt="다크모드 추가해줘",
        request_scope="existing_app_modification",
    )

    assert decision.request_scope == "existing_app_modification"
    assert decision.questions == []


def test_existing_app_modification_fallback_builds_clear_change_request():
    decision = fallback_decide_intent(
        "다크모드 추가해줘",
        "task-1",
        existing_task=True,
        existing_workspace_ready=True,
    )

    assert decision.mode == "build"
    assert decision.request_scope == "existing_app_modification"
    assert decision.questions == []


def test_existing_app_modification_confirmation_answer_does_not_repeat_question():
    previous_state = {
        "awaiting_confirmation": True,
        "pending_user_prompt": "계산기 앱에 다크모드를 추가해줘",
        "latest_assistant_questions": [
            "기존에 만들던 계산기 앱을 이어서 수정할까요, 아니면 새 계산기 앱으로 만들까요?"
        ],
        "request_scope": "existing_app_modification",
    }

    decision = decide_intent(
        "기존 계산기 앱 이어서 수정해줘",
        "task-1",
        existing_task=True,
        existing_workspace_ready=True,
        previous_conversation_state=previous_state,
    )

    assert decision.mode == "build"
    assert decision.request_scope == "existing_app_modification"
    assert decision.questions == []
    assert decision.used_previous_pending_prompt is True
    assert "계산기 앱에 다크모드를 추가해줘" in decision.effective_user_prompt


def test_existing_app_modification_confirmation_button_starts_revision_build():
    previous_state = {
        "awaiting_confirmation": True,
        "pending_user_prompt": "계산기 앱에 다크모드를 추가해줘",
        "latest_assistant_questions": ["정리한 수정 방향대로 바로 반영을 시작할까요?"],
        "request_scope": "existing_app_modification",
        "pending_acceptance_criteria": ["다크모드 전환 버튼이 있다"],
    }

    decision = decide_intent(
        "네, 이 내용으로 앱 수정을 시작해줘",
        "task-1",
        existing_task=True,
        existing_workspace_ready=True,
        previous_conversation_state=previous_state,
    )

    assert decision.mode == "build"
    assert decision.request_scope == "existing_app_modification"
    assert decision.questions == []
    assert decision.acceptance_criteria == ["다크모드 전환 버튼이 있다"]


def test_existing_app_short_chat_does_not_become_modification():
    decision = fallback_decide_intent(
        "hi",
        "task-1",
        existing_task=True,
        existing_workspace_ready=True,
    )

    assert decision.mode == "answer_question"
    assert decision.request_scope == "non_app_request"
    assert decision.questions == []
