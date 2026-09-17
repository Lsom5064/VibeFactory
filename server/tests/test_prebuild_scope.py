import json
import os
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from fastapi.testclient import TestClient

from server.prebuild_requirements import (
    INTEGRATION_CATALOG,
    client_build_environment_for_requirements,
    missing_blocking_requirements,
    requirement_snapshot,
    resolve_prebuild_requirements,
)
from server.server import (
    build_initial_prompt_review_decision,
    build_initial_prompt_submission_decision,
    build_intent_decision,
    build_prebuild_recheck_decision,
    build_prebuild_requirements_review_decision,
    create_app,
    current_task_prebuild_requirements,
    decide_intent,
    decision_ui_flags,
    make_decision_state,
    render_prompt_md,
    run_spec_clarification_agent,
)
from server.server_settings import load_settings


def decision(prompt="제품 가격과 배송비를 합산하고 이력을 그래프로 보여줘", requirements=None):
    return build_intent_decision(
        mode="build", task_id="scope-task", existing_task=False,
        user_prompt=prompt, prebuild_requirements=requirements,
        suggested_app_name="비교 기록", secondary_scope_confirmed=True,
    )


def task_with_state(state):
    return {"prompt": "처음 요청", "codex_result_json": json.dumps({"conversation_state": state})}


class PrebuildScopeTests(unittest.TestCase):
    def test_semantic_empty_snapshot_beats_mentions_and_exclusions_for_every_provider(self):
        for provider in INTEGRATION_CATALOG:
            with self.subTest(provider=provider.integration_id):
                mention = provider.trigger_groups[0][0]
                prompt = f"{mention} 연동은 제외하고 기기에 입력한 기록만 보여준다."
                self.assertEqual([], resolve_prebuild_requirements(prompt, [], environment={}))
                reviewed = build_initial_prompt_review_decision(decision(prompt, []), environment={})
                self.assertEqual("submit_initial_prompt", reviewed.confirmation_action)

    def test_scope_is_not_inferred_from_ambiguous_labels(self):
        for prompt in (
            "상품 가격 + 배송비를 합친 실제 결제 금액을 보여줘.",
            "실제 결제는 필요 없음. 금액만 확인할 수 있으면 됨.",
            "Google Calendar 같은 모양으로 기기에 일정을 저장한다. 계정 연동 제외.",
            "OpenAI 연결 없이 사용자가 작성한 상담 기록을 저장한다.",
            "실시간 날씨 API 대신 사용자가 입력한 기온 이력을 그래프로 본다.",
        ):
            with self.subTest(prompt=prompt):
                reviewed = build_initial_prompt_review_decision(decision(prompt, []), environment={})
                self.assertEqual([], reviewed.prebuild_requirements)
                self.assertEqual("submit_initial_prompt", reviewed.confirmation_action)

    def test_catalog_configuration_remains_authoritative_for_all_integrations(self):
        for provider in INTEGRATION_CATALOG:
            with self.subTest(provider=provider.integration_id):
                raw = {"id": provider.integration_id, "configured": True, "supported": True,
                       "resolution": "implementation", "blocking": False}
                requirements = resolve_prebuild_requirements("실제 연동 요청", [raw], environment={})
                self.assertTrue(missing_blocking_requirements(requirements))
                environment = {key: "unit-test-value" for group in provider.environment_groups for key in group}
                if provider.package_allowlist_environment:
                    environment[provider.package_allowlist_environment] = "kr.test.app"
                ready = resolve_prebuild_requirements("실제 연동 요청", [raw], environment=environment, package_name="kr.test.app")
                self.assertEqual(provider.supported, not bool(missing_blocking_requirements(ready)))

    def test_only_current_selected_integrations_are_resolved(self):
        requirements = resolve_prebuild_requirements(
            "OpenAI 답변을 사용하되 Google Maps와 실제 결제는 제외",
            [{"id": "openai"}], environment={},
        )
        self.assertEqual(["openai"], [item["id"] for item in requirements])

    def test_implementation_capabilities_skip_setup_and_preserve_plan(self):
        for capability, requirement_type, plan in (
            ("local_app", "data_source", "입력한 가격과 날짜를 기기에 저장해 가격 이력 그래프에 표시한다."),
            ("shared_records", "backend", "VibeDataClient로 공유 목록의 등록, 조회, 수정, 삭제를 구현한다."),
            ("public_http", "data_source", "확정된 공개 JSON 주소를 조회하고 실패 시 다시 시도할 수 있게 한다."),
            ("local_app", "hardware", "기기의 가속도계 값을 읽고 중립 보정과 필터링으로 패들을 이동한다."),
            ("local_app", "hardware", "기기에 있는 카메라의 프리뷰와 촬영 생명주기를 구현한다."),
            ("local_app", "special_permission", "알림 접근 기능을 구현하고 실행 시 시스템 설정으로 안내한다."),
            ("local_app", "background_execution", "Android가 허용하는 예약 실행을 구현하고 제한을 앱에서 안내한다."),
        ):
            with self.subTest(capability=capability):
                reviewed = build_initial_prompt_review_decision(decision(requirements=[{
                    "id": "ordinary_work", "title": "데이터 처리", "type": requirement_type,
                    "blocking": True, "resolution": "implementation", "capability": capability,
                    "implementation_plan": plan,
                }]), environment={})
                self.assertEqual("submit_initial_prompt", reviewed.confirmation_action)
                self.assertEqual([], missing_blocking_requirements(reviewed.prebuild_requirements))
                self.assertIn(plan, reviewed.prepared_prompt)
                self.assertEqual("", reviewed.prebuild_requirements[0]["question"])
                self.assertIn("## 자동 구현 사항", reviewed.prepared_prompt)

    def test_advisories_never_require_separate_approval_and_preserve_usage_steps(self):
        for blocking in (False, True):
            with self.subTest(blocking=blocking):
                steps = ["앱 설치 후 알림 접근 설정에서 허용한다."]
                initial = decision(requirements=[{
                    "title": "알림 접근 권한", "type": "special_permission",
                    "resolution": "advisory", "blocking": blocking,
                    "reason": "권한 요청과 사용 안내는 앱에 구현한다.", "setup_steps": steps,
                }])
                reviewed = build_initial_prompt_review_decision(initial, environment={})
                self.assertEqual("submit_initial_prompt", reviewed.confirmation_action)
                self.assertFalse(reviewed.prebuild_requirements[0]["blocking"])
                self.assertIn("## 앱 사용 시 안내", reviewed.prepared_prompt)
                self.assertIn("앱 설치 후 알림 접근 설정에서 허용", reviewed.prepared_prompt)
                modification = build_prebuild_requirements_review_decision(initial, environment={})
                self.assertEqual("build", modification.mode)

    def test_unknown_unsupported_work_has_an_answerable_question_not_implementation_steps(self):
        raw = {
            "title": "전용 측정 장비 연결", "type": "hardware",
            "resolution": "implementation", "capability": "external_device",
            "implementation_plan": "외부 장비의 비공개 연결 방식을 사용한다.",
            "setup_steps": ["장비 프로토콜을 연결한다"],
        }
        reviewed = build_initial_prompt_review_decision(decision(requirements=[raw]), environment={})
        self.assertEqual("", reviewed.confirmation_action)
        requirement = reviewed.prebuild_requirements[0]
        self.assertTrue(requirement["blocking"])
        self.assertIn("?", requirement["question"])
        self.assertIn("채팅", reviewed.message)
        self.assertNotIn("장비 프로토콜을 연결한다", reviewed.message)

    def test_explicit_hardware_choice_is_not_silently_replaced_by_implementation(self):
        question = "외부 심박계를 연결할까요, 직접 입력한 기록만 보여줄까요?"
        reviewed = build_initial_prompt_review_decision(decision(requirements=[{
            "title": "심박 기록 출처", "type": "hardware", "resolution": "clarification",
            "capability": "local_app", "question": question,
        }]), environment={})
        self.assertTrue(reviewed.prebuild_requirements[0]["blocking"])
        self.assertIn(question, reviewed.message)

    def test_advisory_and_connected_integrations_are_not_mixed_into_a_missing_question(self):
        raw = [
            {"id": "weather"},
            {"title": "기기 알림", "type": "special_permission", "resolution": "advisory"},
            {"title": "자료 출처", "type": "data_source", "resolution": "clarification",
             "question": "어떤 파일의 자료를 가져올까요?"},
        ]
        reviewed = build_initial_prompt_review_decision(decision(requirements=raw), environment={"OPENWEATHER_API_KEY": "test-key"})
        self.assertEqual(3, len(reviewed.prebuild_requirements))
        self.assertIn("어떤 파일", reviewed.message)
        self.assertNotIn("기기 알림", reviewed.message)
        self.assertNotIn("날씨", reviewed.message)

    def test_unresolved_legacy_work_asks_real_question_without_recheck_loop(self):
        for requirement_type in ("backend", "data_source", "background_execution"):
            with self.subTest(requirement_type=requirement_type):
                question = "가격을 직접 입력할까요, 정해진 판매처에서 자동으로 불러올까요?"
                reviewed = build_initial_prompt_review_decision(decision(requirements=[{
                    "id": "source_choice", "title": "가격 이력 수집", "type": requirement_type,
                    "blocking": True, "setup_steps": [question],
                }]), environment={})
                self.assertEqual("ask_confirmation", reviewed.mode)
                self.assertEqual("", reviewed.confirmation_action)
                self.assertTrue(decision_ui_flags(reviewed)["requires_user_input"])
                self.assertIn(question, reviewed.message)
                self.assertNotIn("담당 연구원", reviewed.message)

    def test_explicit_nonblocking_work_is_not_forced_into_external_setup(self):
        for requirement_type in ("backend", "data_source"):
            with self.subTest(requirement_type=requirement_type):
                requirements = resolve_prebuild_requirements("기기에 저장", [{
                    "title": "기록 보관", "type": requirement_type, "blocking": False,
                }], environment={})
                self.assertEqual([], missing_blocking_requirements(requirements))

    def test_missing_plan_or_unavailable_capability_is_not_claimed_implemented(self):
        for capability, plan in (("shared_records", ""), ("arbitrary_server_deployment", "매분 외부 사이트를 수집")):
            with self.subTest(capability=capability):
                reviewed = build_initial_prompt_review_decision(decision(requirements=[{
                    "title": "서버 수집", "type": "backend", "resolution": "implementation",
                    "capability": capability, "implementation_plan": plan,
                }]), environment={})
                self.assertEqual("ask_confirmation", reviewed.mode)
                self.assertEqual("", reviewed.confirmation_action)

    def test_unknown_credentials_cannot_be_declared_implementation(self):
        requirements = resolve_prebuild_requirements("외부 연동", [{
            "title": "외부 비밀 키", "type": "server_credential", "blocking": False,
            "resolution": "implementation", "capability": "local_app", "implementation_plan": "연결 구현",
        }], environment={})
        self.assertTrue(missing_blocking_requirements(requirements))

    def test_credentials_and_missing_external_hardware_still_block(self):
        for requirement_type in ("api_key", "oauth", "server_credential", "cloud_project", "hardware"):
            with self.subTest(requirement_type=requirement_type):
                requirements = resolve_prebuild_requirements("필수 외부 연결", [{
                    "title": "준비되지 않은 외부 연결", "type": requirement_type,
                    "resolution": "external_setup" if requirement_type == "hardware" else "advisory",
                    "blocking": False, "capability": "local_app",
                }], environment={})
                self.assertTrue(missing_blocking_requirements(requirements))

    def test_unsupported_provider_does_not_offer_futile_registration_recheck(self):
        reviewed = build_initial_prompt_review_decision(decision(requirements=[{"id": "payments"}]), environment={})
        self.assertEqual("ask_confirmation", reviewed.mode)
        self.assertEqual("", reviewed.confirmation_action)
        self.assertIn("키 등록만으로", reviewed.message)

    def test_explicit_empty_state_clears_dependencies_through_review_submit_and_build(self):
        previous = {"latest_prebuild_requirements": [{"id": "payments"}], "awaiting_confirmation": True,
                    "pending_prebuild_requirements": [{"id": "payments"}]}
        reviewed = build_initial_prompt_review_decision(decision("결제 제외, 금액과 그래프만 표시", []), environment={})
        state = make_decision_state(task_with_state(previous), reviewed)
        current = state["conversation_state"]
        self.assertEqual([], current["latest_prebuild_requirements"])
        self.assertEqual([], current["pending_prebuild_requirements"])
        submitted = build_initial_prompt_submission_decision(
            task_id="scope-task", final_prompt=reviewed.prepared_prompt, previous_conversation_state=current,
        )
        self.assertEqual([], submitted.prebuild_requirements)
        self.assertEqual([], current_task_prebuild_requirements({"codex_result_json": json.dumps(state)}))
        self.assertEqual({}, client_build_environment_for_requirements(submitted.prebuild_requirements, environment={}))

    def test_pending_empty_list_beats_stale_latest_and_does_not_rescan_old_prompt(self):
        state = {"awaiting_confirmation": True, "pending_prebuild_requirements": [],
                 "latest_prebuild_requirements": [{"id": "payments"}], "pending_user_prompt": "실제 결제 금액 표시"}
        self.assertEqual([], requirement_snapshot(state))
        reviewed = build_prebuild_recheck_decision(task_id="task", previous_conversation_state=state, existing_workspace_ready=False)
        ready = build_initial_prompt_review_decision(reviewed, environment={})
        self.assertEqual("submit_initial_prompt", ready.confirmation_action)

    def test_answer_and_unanalyzed_ui_change_preserve_existing_runtime_dependencies(self):
        previous = {"latest_prebuild_requirements": [{"id": "weather"}], "awaiting_confirmation": False,
                    "pending_prebuild_requirements": []}
        for current in (
            build_intent_decision(mode="answer_question", task_id="task", existing_task=True, user_prompt="사용법 알려줘"),
            decision("버튼을 오른쪽으로 옮겨줘"),
        ):
            with self.subTest(mode=current.mode):
                state = make_decision_state(task_with_state(previous), current)
                self.assertEqual(["weather"], [item["id"] for item in current_task_prebuild_requirements({"codex_result_json": json.dumps(state)})])

    def test_excluded_provider_can_be_explicitly_added_back(self):
        reviewed = build_initial_prompt_review_decision(decision("날씨 API 연동을 다시 추가", [{"id": "weather"}]), environment={})
        self.assertEqual("recheck_prebuild_requirements", reviewed.confirmation_action)

    def test_production_analysis_failure_does_not_invent_keyword_blockers(self):
        with patch.dict(os.environ, {"MOCK_CODEX": "0", "INTENT_AGENT_ENABLED": "1"}), patch(
            "server.server.run_spec_clarification_agent", return_value=None,
        ):
            result = decide_intent("실제 결제 금액 표시 앱 만들어줘", "task", settings=load_settings())
        self.assertEqual("ask_confirmation", result.mode)
        self.assertEqual([], result.prebuild_requirements)
        self.assertIn("일시적인 문제", "\n".join(result.questions))

    def test_spec_schema_and_policy_offer_only_existing_implementation_capabilities(self):
        with patch("server.server.run_openai_structured_agent", return_value=None) as agent:
            run_spec_clarification_agent(load_settings(), prompt="기록 그래프", task_id="task", existing_task=False)
        call = agent.call_args.kwargs
        props = call["schema"]["properties"]["prebuild_requirements"]["items"]["properties"]
        self.assertIn("implementation", props["resolution"]["enum"])
        self.assertIn("complete current", call["instructions"])
        self.assertIn("VibeDataClient", call["instructions"])
        self.assertIn("NOT include deploying arbitrary", call["instructions"])


class SubmittedPrebuildScopeTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        root = Path(self.temp.name)
        self.env = patch.dict(os.environ, {
            "BASE_PROJECT_PATH": str(root / "base"), "WORKSPACES_ROOT": str(root / "workspaces"),
            "BUILD_CACHE_ROOT": str(root / "cache"), "DB_PATH": str(root / "tasks.db"),
            "APP_DATA_DB_PATH": str(root / "data.db"), "MOCK_CODEX": "1", "INTENT_AGENT_ENABLED": "1",
            "APP_RUNTIME_OPENAI_API_KEY": "", "OPENWEATHER_API_KEY": "",
        })
        self.env.start()
        self.addCleanup(self.env.stop)
        def fake_workspace(settings, task):
            workspace = root / "workspaces" / task["task_id"]
            project = workspace / "revisions" / "rev_0001" / "project"
            project.mkdir(parents=True)
            (workspace / "prompt.md").write_text(render_prompt_md(task, settings), encoding="utf-8")
            return workspace, project
        workspace_patch = patch("server.server.build_task_workspace", side_effect=fake_workspace)
        self.workspace_builder = workspace_patch.start()
        self.addCleanup(workspace_patch.stop)
        self.app = create_app()
        self.client = TestClient(self.app)
        self.client.__enter__()
        self.addCleanup(self.client.__exit__, None, None, None)
        with patch("server.server.decide_intent", return_value=decision(requirements=[])):
            initial = self.client.post("/generate", json={"device_id": "scope-device", "prompt": "가격 기록 앱"})
        self.assertEqual(200, initial.status_code, initial.text)
        self.initial = initial.json()

    def submit(self, prompt):
        return self.client.post("/generate", json={
            "task_id": self.initial["task_id"], "device_id": "scope-device", "prompt": prompt,
            "request_action": "submit_initial_prompt",
        })

    def test_sensor_implementation_reaches_prompt_then_build_without_extra_setup_approval(self):
        plan = "가속도계 값을 필터링하고 중립 보정을 적용해 패들을 이동한다."
        raw = {"id": "tilt_sensor_game", "title": "가속도계 조작", "type": "hardware",
               "resolution": "implementation", "capability": "local_app", "blocking": True,
               "implementation_plan": plan, "question": ""}
        with patch("server.server.decide_intent", return_value=decision("가속도계로 벽돌 깨기", [raw])):
            response = self.client.post("/generate", json={
                "task_id": self.initial["task_id"], "device_id": "scope-device", "prompt": "가속도계",
            })
        self.assertEqual(200, response.status_code, response.text)
        self.assertEqual("submit_initial_prompt", response.json()["confirmation_action"])
        self.assertIn(plan, response.json()["prepared_prompt"])
        self.assertFalse(self.app.state.db.get_task(self.initial["task_id"])["workspace_path"])
        with patch("server.server.run_spec_clarification_agent") as agent:
            submitted = self.submit(response.json()["prepared_prompt"])
        agent.assert_not_called()
        self.assertEqual(200, submitted.status_code, submitted.text)
        self.assertEqual("build_started", submitted.json()["interaction_type"])
        self.assertFalse(submitted.json()["prebuild_requirements"][0]["blocking"])
        task = self.app.state.db.get_task(self.initial["task_id"])
        self.assertIn(plan, task["build_request_prompt"])
        self.assertTrue(task["workspace_path"])

    def test_edit_adding_provider_is_checked_before_workspace_creation(self):
        edited = self.initial["prepared_prompt"] + "\nOpenAI로 추천 설명을 생성한다."
        with patch("server.server.run_spec_clarification_agent", return_value={
            "mode": "build", "prebuild_requirements": [{"id": "openai"}],
        }) as agent:
            result = self.submit(edited)
        self.assertEqual(200, result.status_code, result.text)
        self.assertEqual("recheck_prebuild_requirements", result.json()["confirmation_action"])
        self.assertTrue(agent.call_args.kwargs["prebuild_review_only"])
        self.assertEqual(edited, agent.call_args.kwargs["prompt"])
        self.assertFalse(self.app.state.db.get_task(self.initial["task_id"])["workspace_path"])

    def test_failed_final_review_is_retried_even_with_same_edited_prompt(self):
        edited = self.initial["prepared_prompt"] + "\n실시간 날씨를 추가한다."
        with patch("server.server.run_spec_clarification_agent", side_effect=[None, {
            "mode": "build", "prebuild_requirements": [{"id": "weather"}],
        }]) as agent:
            first = self.submit(edited)
            self.assertEqual("submit_initial_prompt", first.json()["confirmation_action"])
            second = self.submit(edited)
        self.assertEqual(2, agent.call_count)
        self.assertEqual("recheck_prebuild_requirements", second.json()["confirmation_action"])
        self.assertFalse(self.app.state.db.get_task(self.initial["task_id"])["workspace_path"])

    def test_followup_removes_stale_constraints_and_submission_stays_empty(self):
        task_id = self.initial["task_id"]
        task = self.app.state.db.get_task(task_id)
        state = json.loads(task["codex_result_json"])
        state["conversation_state"].update({
            "latest_prebuild_requirements": [{"id": "payments"}],
            "pending_prebuild_requirements": [{"id": "payments"}],
        })
        self.app.state.db.update_task(task_id, codex_result_json=json.dumps(state))
        with patch("server.server.decide_intent", return_value=decision("실제 결제 제외, 가격과 그래프 유지", [])):
            revised = self.client.post("/generate", json={
                "task_id": task_id, "device_id": "scope-device", "prompt": "결제 제외. 그래프는 유지.",
            })
        self.assertEqual("submit_initial_prompt", revised.json()["confirmation_action"])
        saved = json.loads(self.app.state.db.get_task(task_id)["codex_result_json"])
        self.assertEqual([], saved["conversation_state"]["latest_prebuild_requirements"])
        submitted = self.submit(revised.json()["prepared_prompt"])
        self.assertEqual(200, submitted.status_code, submitted.text)
        self.assertEqual("build_started", submitted.json()["interaction_type"])
        self.assertEqual([], submitted.json()["prebuild_requirements"])
        self.assertEqual([], current_task_prebuild_requirements(self.app.state.db.get_task(task_id)))

    def test_mixed_source_choice_and_key_registration_preserve_both_flows(self):
        requirements = [
            {"id": "openai"},
            {"id": "source_choice", "title": "데이터 출처", "type": "data_source", "blocking": True,
             "resolution": "clarification", "question": "입력한 기록을 쓸까요, 특정 판매처의 데이터를 쓸까요?"},
        ]
        with patch("server.server.decide_intent", return_value=decision(requirements=requirements)):
            pending = self.client.post("/generate", json={
                "task_id": self.initial["task_id"], "device_id": "scope-device", "prompt": "AI 요약과 외부 데이터를 추가",
            })
        self.assertEqual("needs_clarification", pending.json()["interaction_type"])
        secret = "sk-" + "test-only-" + "x" * 24
        with patch("server.server.run_spec_clarification_agent") as agent:
            registered = self.client.post("/generate", json={
                "task_id": self.initial["task_id"], "device_id": "scope-device", "prompt": secret,
            })
        agent.assert_not_called()
        self.assertNotIn(secret, registered.text)
        self.assertEqual("needs_clarification", registered.json()["interaction_type"])
        self.assertIn("API 키를 등록했어요", registered.json()["message"])
        self.assertIn("특정 판매처", registered.text)
        self.assertEqual(1, len(self.app.state.db.list_task_integration_credentials(self.initial["task_id"])))

    def test_unchanged_submission_rechecks_actual_configuration(self):
        task_id = self.initial["task_id"]
        saved = json.loads(self.app.state.db.get_task(task_id)["codex_result_json"])
        for key in ("latest_prebuild_requirements", "pending_prebuild_requirements"):
            saved["conversation_state"][key] = [{"id": "openai", "configured": True, "supported": True}]
        self.app.state.db.update_task(task_id, codex_result_json=json.dumps(saved))
        with patch("server.server.run_spec_clarification_agent") as agent:
            result = self.submit(self.initial["prepared_prompt"])
        agent.assert_not_called()
        self.assertEqual("recheck_prebuild_requirements", result.json()["confirmation_action"])
        self.workspace_builder.assert_not_called()

    def test_edited_prompt_can_clear_an_old_external_requirement(self):
        task_id = self.initial["task_id"]
        saved = json.loads(self.app.state.db.get_task(task_id)["codex_result_json"])
        for key in ("latest_prebuild_requirements", "pending_prebuild_requirements"):
            saved["conversation_state"][key] = [{"id": "payments"}]
        self.app.state.db.update_task(task_id, codex_result_json=json.dumps(saved))
        edited = self.initial["prepared_prompt"] + "\n실제 결제는 제외하고 총 금액만 표시한다."
        with patch("server.server.run_spec_clarification_agent", return_value={"mode": "build", "prebuild_requirements": []}):
            result = self.submit(edited)
        self.assertEqual(200, result.status_code, result.text)
        self.assertEqual("build_started", result.json()["interaction_type"])
        self.assertEqual([], result.json()["prebuild_requirements"])
        task = self.app.state.db.get_task(task_id)
        self.assertEqual(edited, task["build_request_prompt"])
        self.assertEqual([], current_task_prebuild_requirements(task))


if __name__ == "__main__":
    unittest.main()
