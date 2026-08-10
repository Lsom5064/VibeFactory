import os
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from fastapi.testclient import TestClient

from flutter_apk_server.server import (
    build_intent_decision,
    build_prepared_generation_prompt,
    contains_conversation_placeholder,
    create_app,
    materialize_conversation_spec_payload,
)


class DynamicPromptPreparationTests(unittest.TestCase):
    def test_prompt_omits_generic_audience_and_irrelevant_storage_sections(self) -> None:
        decision = build_intent_decision(
            mode="build",
            task_id="calculator-task",
            existing_task=False,
            user_prompt="두 숫자를 입력하면 합계를 계산하는 앱을 만들어줘",
            effective_user_prompt="두 숫자를 입력하면 합계를 계산하고 결과를 바로 보여준다.",
            suggested_app_name="간편 계산기",
            primary_user_flow="두 숫자를 입력하고 합계 결과를 확인한다.",
            core_features=["숫자 두 개 입력", "합계 계산", "결과 초기화"],
            key_screens=["계산 화면"],
            target_users=["일반 Android 스마트폰 사용자"],
            storage_mode="none",
            acceptance_criteria=["입력한 두 숫자의 합계가 정확하게 표시돼야 함"],
        )

        prompt = build_prepared_generation_prompt(decision)

        self.assertIn("## 주요 화면\n- 계산 화면", prompt)
        self.assertIn("## 핵심 기능\n- 숫자 두 개 입력", prompt)
        self.assertNotIn("## 주요 사용자", prompt)
        self.assertNotIn("일반 Android 스마트폰 사용자", prompt)
        self.assertNotIn("저장 방식", prompt)
        self.assertNotIn("## 첨부 자료 반영", prompt)

    def test_prompt_describes_local_records_dynamically(self) -> None:
        decision = build_intent_decision(
            mode="build",
            task_id="journal-task",
            existing_task=False,
            user_prompt="하루 감정과 메모를 기록하고 다시 볼 수 있는 앱",
            suggested_app_name="감정 기록장",
            primary_user_flow="오늘의 감정과 메모를 작성하고 지난 기록을 확인한다.",
            core_features=["감정 선택", "메모 작성", "날짜별 기록 조회"],
            key_screens=["오늘 기록 화면", "지난 기록 목록"],
            storage_mode="local",
            stored_data=["날짜별 감정", "사용자가 작성한 메모"],
            acceptance_criteria=["앱을 다시 열어도 작성한 기록이 유지돼야 함"],
        )

        prompt = build_prepared_generation_prompt(decision)

        self.assertIn("## 저장할 정보와 방식", prompt)
        self.assertIn("날짜별 감정", prompt)
        self.assertIn("사용자가 작성한 메모", prompt)
        self.assertIn("기기에 저장한다", prompt)
        self.assertNotIn("서버 데이터 API", prompt)

    def test_prompt_describes_shared_roles_and_server_data(self) -> None:
        decision = build_intent_decision(
            mode="build",
            task_id="academy-task",
            existing_task=False,
            user_prompt="원장, 수강생, 학부모가 피아노 진도와 출석을 공유하는 앱",
            suggested_app_name="피아노 진도장",
            primary_user_flow="원장이 진도와 출석을 등록하면 수강생과 학부모가 확인한다.",
            core_features=["수강생별 진도 등록", "출석 체크", "학부모 공유"],
            target_users=["원장", "수강생", "학부모"],
            key_screens=["전체 수강생 관리", "개인 진도 화면", "공지사항"],
            storage_mode="server",
            stored_data=["수강생별 진도", "출석 기록", "공지사항"],
            acceptance_criteria=["역할별로 같은 최신 정보를 확인할 수 있어야 함"],
        )

        prompt = build_prepared_generation_prompt(decision)

        self.assertIn("## 주요 사용자\n- 원장\n- 수강생\n- 학부모", prompt)
        self.assertIn("## 공유 데이터와 저장 방식", prompt)
        self.assertIn("수강생별 진도", prompt)
        self.assertIn("서버 데이터 API", prompt)

    def test_context_placeholder_is_materialized_from_conversation(self) -> None:
        history = [
            {
                "role": "user",
                "content": "공유하기로 받은 메시지에서 날짜를 찾아 달력에 일정 후보를 보여주는 앱을 만들고 싶어.",
                "attachment_names": [],
                "created_at": "2026-08-07T00:00:00+00:00",
            },
            {
                "role": "assistant",
                "content": "다른 앱의 대화 전체를 읽는 대신 공유하기로 받은 텍스트만 분석하는 방식은 가능해요.",
                "attachment_names": [],
                "created_at": "2026-08-07T00:01:00+00:00",
            },
            {
                "role": "user",
                "content": "그렇게 만들어줘",
                "attachment_names": [],
                "created_at": "2026-08-07T00:02:00+00:00",
            },
        ]
        payload = {
            "mode": "build",
            "request_scope": "new_app",
            "app_name": "새앱",
            "effective_user_prompt": "이전 대화 맥락의 앱 요청을 사용해 그대로 진행",
            "primary_user_flow": "이전 대화에서 정한 기능 사용",
            "target_users": [],
            "key_screens": ["이전 대화에서 정한 화면"],
            "core_features": ["이전 대화에서 정한 기능"],
            "secondary_requirements": [],
            "secondary_scope_confirmed": True,
            "storage_mode": "unspecified",
            "stored_data": [],
            "acceptance_criteria": ["이전 대화의 기능이 동작해야 함"],
        }

        materialized = materialize_conversation_spec_payload(payload, history, "그렇게 만들어줘")

        self.assertIn("공유하기로 받은 메시지", materialized["effective_user_prompt"])
        self.assertIn("달력에 일정 후보", materialized["effective_user_prompt"])
        self.assertFalse(contains_conversation_placeholder(materialized["primary_user_flow"]))
        self.assertTrue(materialized["core_features"])
        self.assertFalse(any(contains_conversation_placeholder(item) for item in materialized["core_features"]))

    def test_followup_after_prebuild_conversation_returns_prompt_review_on_same_task(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            environment = {
                "BASE_PROJECT_PATH": str(root / "base"),
                "WORKSPACES_ROOT": str(root / "workspaces"),
                "DB_PATH": str(root / "tasks.db"),
                "APP_DATA_DB_PATH": str(root / "app_data.db"),
                "MOCK_CODEX": "1",
                "INTENT_AGENT_ENABLED": "1",
                "OPENAI_API_KEY": "test-key",
            }
            agent_calls: list[dict] = []

            def fake_agent(*_args, **kwargs):
                agent_calls.append(kwargs)
                if len(agent_calls) == 1:
                    return {
                        "mode": "answer_question",
                        "request_scope": "new_app",
                        "app_name": "",
                        "effective_user_prompt": "",
                        "primary_user_flow": "",
                        "target_users": [],
                        "key_screens": [],
                        "core_features": [],
                        "secondary_requirements": [],
                        "secondary_scope_confirmed": False,
                        "storage_mode": "unspecified",
                        "stored_data": [],
                        "acceptance_criteria": [],
                        "use_previous_pending_request": False,
                        "requires_existing_task_context": False,
                        "reason": "가능한 구현 범위를 먼저 설명합니다.",
                        "questions": [],
                        "assistant_reply": "공유하기로 받은 메시지에서 일정 후보를 찾는 방식으로 구현할 수 있어요.",
                    }
                return {
                    "mode": "build",
                    "request_scope": "new_app",
                    "app_name": "일정 모아보기",
                    "effective_user_prompt": "공유하기로 전달받은 메시지에서 날짜와 약속 내용을 찾아 달력에 일정 후보로 표시한다.",
                    "primary_user_flow": "메시지를 공유받아 일정 후보를 확인하고 달력에 저장한다.",
                    "target_users": [],
                    "key_screens": ["일정 후보 확인", "달력"],
                    "core_features": ["공유 텍스트 받기", "날짜와 약속 내용 추출", "달력 저장"],
                    "secondary_requirements": [],
                    "secondary_scope_confirmed": True,
                    "storage_mode": "local",
                    "stored_data": ["확정한 일정"],
                    "acceptance_criteria": ["공유받은 텍스트에서 일정 후보가 표시돼야 함"],
                    "use_previous_pending_request": True,
                    "requires_existing_task_context": False,
                    "reason": "대화에서 구현 가능한 범위가 확정됐습니다.",
                    "questions": [],
                    "assistant_reply": "",
                }

            def fake_workspace(_settings, _task):
                workspace = root / "workspaces" / "conversation-task"
                project = workspace / "revisions" / "rev_0001" / "project"
                project.mkdir(parents=True, exist_ok=True)
                return workspace, project

            with patch.dict(os.environ, environment, clear=False), patch(
                "flutter_apk_server.server.run_spec_clarification_agent",
                side_effect=fake_agent,
            ), patch(
                "flutter_apk_server.server.build_task_workspace",
                side_effect=fake_workspace,
            ):
                app = create_app()
                with TestClient(app) as client:
                    first = client.post(
                        "/generate",
                        json={
                            "device_id": "conversation-device",
                            "prompt": "다른 앱의 메시지에서 일정을 모으는 앱을 만들 수 있을까?",
                        },
                    )
                    self.assertEqual(first.status_code, 200, first.text)
                    task_id = first.json()["task_id"]
                    self.assertEqual(first.json()["request_scope"], "new_app")

                    second = client.post(
                        "/generate",
                        json={
                            "task_id": task_id,
                            "device_id": "conversation-device",
                            "prompt": "그 방식으로 만들어줘",
                        },
                    )

                    self.assertEqual(second.status_code, 200, second.text)
                    result = second.json()
                    self.assertEqual(result["task_id"], task_id)
                    self.assertEqual(result["interaction_type"], "needs_initial_prompt_review")
                    self.assertIn("공유하기로 전달받은 메시지", result["prepared_prompt"])
                    self.assertNotIn("이전 대화 맥락", result["prepared_prompt"])
                    history = agent_calls[1]["conversation_history"]
                    self.assertEqual([entry["role"] for entry in history], ["user", "assistant", "user"])
                    self.assertIn("구현할 수 있어요", history[1]["content"])

                    submitted = client.post(
                        "/generate",
                        json={
                            "task_id": task_id,
                            "device_id": "conversation-device",
                            "prompt": result["prepared_prompt"],
                            "display_prompt": "만들어진 프롬프트대로 생성요청 문구를 보냈어요",
                            "request_action": "submit_initial_prompt",
                        },
                    )

                    self.assertEqual(submitted.status_code, 200, submitted.text)
                    submitted_result = submitted.json()
                    self.assertEqual(submitted_result["task_id"], task_id)
                    self.assertEqual(submitted_result["interaction_type"], "build_started")
                    self.assertEqual(submitted_result["tool"], "codex")
                    self.assertNotEqual(submitted_result.get("confirmation_action"), "submit_initial_prompt")


if __name__ == "__main__":
    unittest.main()
