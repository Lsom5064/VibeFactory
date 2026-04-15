import os
import sys
import types
import unittest
from unittest.mock import patch

os.environ.setdefault("OPENAI_API_KEY", "test-key")

if "uvicorn" not in sys.modules:
    uvicorn_stub = types.ModuleType("uvicorn")
    uvicorn_stub.run = lambda *args, **kwargs: None
    sys.modules["uvicorn"] = uvicorn_stub

if "pydantic" not in sys.modules:
    pydantic_stub = types.ModuleType("pydantic")

    class BaseModel:
        def __init__(self, **kwargs):
            for key, value in kwargs.items():
                setattr(self, key, value)

    pydantic_stub.BaseModel = BaseModel
    sys.modules["pydantic"] = pydantic_stub

if "fastapi" not in sys.modules:
    fastapi_stub = types.ModuleType("fastapi")

    class HTTPException(Exception):
        def __init__(self, status_code=None, detail=None):
            super().__init__(detail)
            self.status_code = status_code
            self.detail = detail

    class BackgroundTasks:
        def add_task(self, *args, **kwargs):
            return None

    class FastAPI:
        def __init__(self, *args, **kwargs):
            pass

        def post(self, *args, **kwargs):
            return lambda fn: fn

        def get(self, *args, **kwargs):
            return lambda fn: fn

    fastapi_stub.FastAPI = FastAPI
    fastapi_stub.BackgroundTasks = BackgroundTasks
    fastapi_stub.HTTPException = HTTPException
    sys.modules["fastapi"] = fastapi_stub

if "fastapi.responses" not in sys.modules:
    responses_stub = types.ModuleType("fastapi.responses")

    class FileResponse:
        def __init__(self, *args, **kwargs):
            self.args = args
            self.kwargs = kwargs

    responses_stub.FileResponse = FileResponse
    sys.modules["fastapi.responses"] = responses_stub

import vibe_factory
import server


class SpecialistPlannerChainTest(unittest.TestCase):
    def test_flutter_analyze_info_only_is_not_blocking(self):
        info_only_output = """
Analyzing sample_app...

   info • The import of 'package:flutter/foundation.dart' is unnecessary • lib/foo.dart:3:8 • unnecessary_import

1 issue found. (ran in 0.9s)
"""
        warning_output = """
Analyzing sample_app...

   warning • The value of the local variable 'x' isn't used • lib/foo.dart:9:11 • unused_local_variable

1 issue found. (ran in 0.9s)
"""
        error_output = """
Analyzing sample_app...

   error • Undefined name 'missing' • lib/foo.dart:9:11 • undefined_identifier

1 issue found. (ran in 0.9s)
"""
        self.assertFalse(vibe_factory.has_blocking_flutter_analyze_issue(info_only_output))
        self.assertTrue(vibe_factory.has_blocking_flutter_analyze_issue(warning_output))
        self.assertTrue(vibe_factory.has_blocking_flutter_analyze_issue(error_output))

    def test_generate_decision_normalizes_summary_only_build_app(self):
        payload = vibe_factory.normalize_generate_tool_call_payload(
            {
                "tool": "build_app",
                "arguments": {
                    "summary": "버튼 하나로 시작과 정지를 토글하는 스톱워치 앱입니다.",
                },
            }
        )
        is_valid, error = vibe_factory.validate_generate_tool_call(payload)
        self.assertTrue(is_valid, error)
        self.assertEqual(payload["tool"], "build_app")
        self.assertIn("build_spec", payload["arguments"])
        self.assertEqual(payload["arguments"]["build_spec"]["target_platform"], "Android smartphone")

    def test_generate_decision_normalizes_legacy_ask_question(self):
        payload = vibe_factory.normalize_generate_tool_call_payload(
            {
                "tool": "ask_question",
                "arguments": {
                    "questions": ["저장 방식은 어떻게 할까요?"],
                    "summary": "저장 방식 확인 필요",
                },
            }
        )
        is_valid, error = vibe_factory.validate_generate_tool_call(payload)
        self.assertTrue(is_valid, error)
        self.assertEqual(payload["tool"], "ask_clarification")
        self.assertEqual(payload["arguments"]["missing_fields"], [])

    def test_runtime_build_spec_normalizes_external_data_fields(self):
        normalized = vibe_factory.normalize_runtime_build_spec(
            {
                "data_strategy": "runtime_web_fetch",
                "external_sources": [
                    {"title": "학식", "url": "https://example.edu/menu", "source_type": "official_page"}
                ],
                "required_permissions": [
                    {
                        "name": "android.permission.POST_NOTIFICATIONS",
                        "usage_reason": "새 식단 알림을 받기 위해 필요",
                    }
                ],
            }
        )
        self.assertEqual(normalized["data_source_type"], "web_scrape")
        self.assertEqual(normalized["source_access_mode"], "scraping")
        self.assertEqual(normalized["source_url_candidates"], ["https://example.edu/menu"])
        self.assertEqual(normalized["required_permissions"][0]["name"], "android.permission.POST_NOTIFICATIONS")
        self.assertTrue(normalized["required_permissions"][0]["runtime_request_required"])
        self.assertTrue(normalized["permission_requirements"]["runtime_prompt_required"])
        self.assertTrue(normalized["permission_requirements"]["denial_fallback_required"])
        self.assertTrue(normalized["verification_requirements"]["requires_external_data_verification"])
        self.assertTrue(normalized["verification_requirements"]["parser_smoke_test_required"])
        self.assertTrue(normalized["verification_requirements"]["cache_persistence_required"])

    def test_collect_external_data_verification_inputs_extracts_notice_and_date_signals(self):
        build_spec = {
            "data_source_type": "web_scrape",
            "source_url_candidates": ["https://example.edu/menu"],
            "verification_requirements": {"requires_external_data_verification": True},
        }
        with patch.object(
            vibe_factory,
            "test_http_request",
            return_value={"success": True, "status_code": 200, "response_preview": "ok", "final_url": "https://example.edu/menu"},
        ), patch.object(
            vibe_factory,
            "fetch_webpage",
            return_value={
                "status": "success",
                "final_url": "https://example.edu/menu",
                "title": "공지",
                "text_content": "이전 홈페이지 안내 04.15(월) 중식 메뉴",
            },
        ):
            evidence = vibe_factory.collect_external_data_verification_inputs(build_spec, task_id="t1")

        self.assertTrue(evidence["static_signals"]["has_successful_source_probe"])
        self.assertTrue(evidence["static_signals"]["has_migration_notice"])
        self.assertIn("04.15(월)", evidence["static_signals"]["sample_date_tokens"])

    def test_parser_contract_check_warns_when_strategy_changes_but_live_samples_exist(self):
        build_spec = {
            "data_source_type": "web_scrape",
            "source_url_candidates": ["https://example.edu/menu"],
            "web_data_contract": {
                "primary_url": "https://example.edu/menu",
                "candidate_urls": ["https://example.edu/menu?campus=1"],
                "parser_strategy": "html_table",
                "minimum_sample_records": 1,
            },
        }
        fetched_sources = [
            {
                "url": "https://example.edu/menu",
                "final_url": "https://example.edu/menu?campus=1",
                "web_data_analysis": {
                    "sample_records": ["04.15(월) 중식"],
                    "parser_contract": {
                        "parser_strategy": "discover_and_fetch_candidate_url",
                    },
                },
            }
        ]

        report = vibe_factory._deterministic_parser_contract_checks(build_spec, fetched_sources)

        self.assertEqual(report["status"], "warn")
        self.assertEqual(report["issues"], [])
        self.assertTrue(report["warnings"])
        self.assertEqual(report["sample_records"], ["04.15(월) 중식"])

    def test_verify_external_data_dependencies_returns_not_applicable_for_local_app(self):
        report = vibe_factory.verify_external_data_dependencies("/tmp/unused", {"data_source_type": "none"})
        self.assertEqual(report["status"], "not_applicable")
        self.assertEqual(report["checks"]["source_probe"], "not_applicable")

    def test_validate_external_data_build_context_requires_web_contract(self):
        context, error = vibe_factory.validate_external_data_build_context(
            {
                "build_spec": {
                    "data_source_type": "web_scrape",
                    "source_url_candidates": ["https://example.edu/menu"],
                    "verification_requirements": {"requires_external_data_verification": True},
                }
            }
        )
        self.assertIsNotNone(error)
        self.assertIn("web_data_contract", error)
        self.assertEqual(context["build_spec"]["data_source_type"], "web_scrape")

    def test_validate_external_data_build_context_accepts_actionable_web_contract(self):
        context, error = vibe_factory.validate_external_data_build_context(
            {
                "build_spec": {
                    "data_source_type": "web_scrape",
                    "source_url_candidates": ["https://example.edu/menu"],
                    "web_data_contract": {
                        "primary_url": "https://example.edu/menu",
                        "candidate_urls": ["https://example.edu/menu?campus=1"],
                        "parser_strategy": "discover_and_fetch_candidate_url",
                    },
                    "verification_requirements": {"requires_external_data_verification": True},
                }
            }
        )
        self.assertIsNone(error)
        self.assertEqual(context["build_spec"]["source_access_mode"], "scraping")

    def test_deterministic_external_data_checks_fail_on_mock_success_path_and_temp_cache(self):
        report = vibe_factory._deterministic_external_data_checks(
            {
                "data_source_type": "web_scrape",
                "source_url_candidates": ["https://example.edu/menu"],
                "verification_requirements": {
                    "requires_external_data_verification": True,
                    "cache_persistence_required": True,
                },
            },
            """
            class MenuCrawlerService {
              Future<void> fetch() async {
                await Future<void>.delayed(const Duration(milliseconds: 500));
                final sampleItems = [];
                final sourceMetadata = '공식 웹페이지 소스 미확정으로 로컬 파서 구조 사용';
              }
            }
            final dir = Directory.systemTemp.createTempSync('meal');
            """,
        )
        self.assertEqual(report["status"], "fail")
        self.assertTrue(any("샘플/목업" in issue for issue in report["issues"]))
        self.assertTrue(any("임시 디렉터리" in issue for issue in report["issues"]))

    def test_can_access_task_accepts_device_id_without_phone_number(self):
        self.assertTrue(
            server.can_access_task(
                {"device_id": "device-123", "user_id": None, "phone_number": None},
                device_id="device-123",
            )
        )

    def test_build_access_denied_reason_prefers_device_id_mismatch(self):
        reason = server.build_access_denied_reason(
            {"device_id": "device-123", "user_id": None, "phone_number": None},
            device_id="device-999",
        )
        self.assertEqual(reason, "device_id_mismatch")

    def test_force_external_research_for_latest_feed_build(self):
        decision = server.maybe_force_external_research_decision(
            {
                "tool": "build_app",
                "arguments": {
                    "summary": "긱뉴스 최신 글을 읽는 안드로이드 리더 앱",
                },
            },
            user_prompt="긱뉴스 최신 글 리더 앱 만들어줘",
            raw_user_message="긱뉴스 최신 글 리더 앱 만들어줘",
        )
        self.assertEqual(decision["tool"], "research_then_build")
        self.assertEqual(
            decision["arguments"]["research_query"],
            "긱뉴스 최신 글 리더 앱 만들어줘",
        )

    def test_force_external_research_when_external_contract_has_no_urls(self):
        decision = server.maybe_force_external_research_decision(
            {
                "tool": "build_app",
                "arguments": {
                    "summary": "춘천캠퍼스 학식 메뉴 앱",
                    "build_spec": {
                        "data_source_type": "web_scrape",
                        "source_access_mode": "scraping",
                    },
                },
            },
            user_prompt="강원대학교 춘천캠퍼스 학식 메뉴 알림 앱",
            raw_user_message="강원대학교 춘천캠퍼스 학식 메뉴 알림 앱",
        )
        self.assertEqual(decision["tool"], "research_then_build")
        self.assertIn("공식 출처", decision["arguments"]["research_reason"])

    def test_keep_local_build_app_when_no_external_signal(self):
        decision = server.maybe_force_external_research_decision(
            {
                "tool": "build_app",
                "arguments": {
                    "summary": "버튼 하나로 시작과 정지를 토글하는 스톱워치 앱",
                },
            },
            user_prompt="버튼 하나로 시작과 정지를 토글하는 스톱워치 앱",
            raw_user_message="버튼 하나로 시작과 정지를 토글하는 스톱워치 앱",
        )
        self.assertEqual(decision["tool"], "build_app")

    def test_specialist_plan_normalizers_accept_observed_model_shapes(self):
        ui_payload = vibe_factory.normalize_ui_layout_plan_payload(
            {
                "app_metadata": {
                    "app_name": "단순 타이머",
                    "design_goal": "큰 경과 시간 표시",
                    "navigation_pattern": "단일 화면 구조",
                },
                "global_style": {
                    "color_tokens": {"primary": "#6750A4"},
                    "typography": {"time_display": {"font_size": 72}},
                },
                "screens": [{"screen_id": "timer_home", "screen_name": "타이머 홈"}],
                "preservation_targets": {"must_keep": ["단일 화면 구조 유지"]},
            }
        )
        self.assertTrue(vibe_factory.validate_ui_layout_plan_payload(ui_payload)[0])
        self.assertIn("visual_identity", ui_payload)
        self.assertIn("screen_layouts", ui_payload)

        minimal_ui_payload = vibe_factory.normalize_ui_layout_plan_payload(
            {
                "preservation_targets": [
                    "단일 화면 구조 유지",
                    "큰 경과 시간 표시를 화면 중심 핵심 요소로 유지",
                ]
            }
        )
        self.assertTrue(vibe_factory.validate_ui_layout_plan_payload(minimal_ui_payload)[0])
        self.assertIn("style_summary", minimal_ui_payload["visual_identity"])
        self.assertTrue(minimal_ui_payload["screen_layouts"])

        logic_payload = vibe_factory.normalize_feature_logic_plan_payload(
            {
                "feature_logic_plan": {
                    "state_management": {"approach": "StatefulWidget"},
                    "data_model": {"fields": [{"name": "elapsedDuration"}]},
                    "screens": [{"screen_id": "timer_home", "events": ["toggle"]}],
                    "business_rules": ["버튼 하나로 시작/멈춤"],
                    "error_handling": {"cases": [{"case": "timer disposed"}]},
                }
            }
        )
        self.assertTrue(vibe_factory.validate_feature_logic_plan_payload(logic_payload)[0])
        self.assertIn("state_model", logic_payload)
        self.assertIn("screen_behaviors", logic_payload)

        minimal_logic_payload = vibe_factory.normalize_feature_logic_plan_payload(
            {
                "business_rules": ["버튼 하나로 시작과 정지를 토글합니다."],
                "data_operations": ["로컬 상태로 경과 시간을 계산합니다."],
                "error_handling": ["타이머 dispose 시 정리합니다."],
            }
        )
        self.assertTrue(vibe_factory.validate_feature_logic_plan_payload(minimal_logic_payload)[0])
        self.assertTrue(minimal_logic_payload["screen_behaviors"])

        contract_payload = vibe_factory.normalize_ui_contract_payload(
            {
                "global_components": ["상단 앱바", "대형 경과 시간 텍스트"],
                "interaction_patterns": ["단일 토글 버튼"],
                "preservation_rules": ["단일 화면 유지"],
            }
        )
        self.assertTrue(vibe_factory.validate_ui_contract_payload(contract_payload)[0])
        self.assertIn("visual_identity", contract_payload)
        self.assertIn("navigation", contract_payload)
        self.assertTrue(contract_payload["screens"])

    def test_specialist_planner_chain_returns_existing_plan_contract(self):
        calls = []

        def fake_call_agent_with_tools(system, user, **kwargs):
            trace = kwargs.get("trace") or {}
            agent_name = trace.get("agent_name")
            calls.append(agent_name)

            if agent_name == "Product_Planner":
                return {
                    "parsed_output": {
                        "summary": "개인 메모 앱",
                        "app_goal": "메모 작성과 조회",
                        "target_users": "단일 사용자",
                        "user_flows": ["메모 작성", "메모 확인"],
                        "screens": ["home"],
                        "data_model": "local",
                        "constraints": ["Android smartphone"],
                    },
                    "usage": {"prompt": 1, "completion": 1, "total": 2},
                }
            if agent_name == "UI_Layout_Designer":
                return {
                    "parsed_output": {
                        "visual_identity": {"style_summary": "카드형 밝은 UI"},
                        "navigation": {"type": "single_screen", "screens": ["home"]},
                        "screen_layouts": [{"screen_id": "home", "layout_summary": "헤더와 카드 리스트"}],
                        "style_tokens": {"primary": "blue"},
                        "preservation_targets": ["카드 리스트"],
                    },
                    "usage": {"prompt": 1, "completion": 1, "total": 2},
                }
            if agent_name == "Data_Model_Designer":
                context = kwargs.get("context") or {}
                self.assertIn("product_plan", context)
                self.assertIn("ui_layout_plan", context)
                self.assertEqual(context["build_spec"]["data_source_type"], "api")
                self.assertEqual(context["build_spec"]["source_access_mode"], "api")
                return {
                    "parsed_output": {
                        "entities": [
                            {
                                "name": "MenuRecord",
                                "fields": [
                                    {
                                        "name": "title",
                                        "type": "String",
                                        "required": True,
                                        "source_path": "title",
                                        "normalization": "trim",
                                    }
                                ],
                                "description": "메뉴 항목",
                            }
                        ],
                        "source_mapping": {
                            "source_kind": "api",
                            "primary_url": "https://example.edu/api/menu",
                            "candidate_urls": ["https://example.edu/api/menu"],
                            "parser_strategy": "json",
                            "response_root": "items",
                            "record_selector": "items[*]",
                        },
                        "normalization_rules": ["문자열 trim"],
                        "validation_rules": ["title 필수"],
                        "empty_state_rules": ["데이터 없으면 빈 상태 표시"],
                        "cache_model": {
                            "enabled": True,
                            "key_fields": ["title"],
                            "ttl_minutes": 30,
                            "stale_data_behavior": "last_known_good",
                        },
                    },
                    "usage": {"prompt": 1, "completion": 1, "total": 2},
                }
            if agent_name == "Feature_Logic_Designer":
                return {
                    "parsed_output": {
                        "state_model": {"notes": "List<String>"},
                        "screen_behaviors": [{"screen_id": "home", "events": ["add_note"]}],
                        "business_rules": ["빈 메모 방지"],
                        "data_operations": ["로컬 상태 저장"],
                        "error_handling": ["입력 검증"],
                    },
                    "usage": {"prompt": 1, "completion": 1, "total": 2},
                }
            if agent_name == "Integration_Planner":
                context = kwargs.get("context") or {}
                self.assertIn("product_plan", context)
                self.assertIn("ui_layout_plan", context)
                self.assertIn("feature_logic_plan", context)
                self.assertEqual(context["build_spec"]["data_source_type"], "api")
                self.assertEqual(context["build_spec"]["source_access_mode"], "api")
                self.assertEqual(
                    context["build_spec"]["required_permissions"][0]["name"],
                    "android.permission.CAMERA",
                )
                self.assertTrue(context["build_spec"]["permission_requirements"]["runtime_prompt_required"])
                return {
                    "parsed_output": {
                        "title": "MemoCard",
                        "package_name": "kr.ac.kangwon.hai.memocard",
                        "blueprint": "메모 카드 UI와 로컬 상태를 통합한다.",
                        "files_to_create": ["lib/main.dart"],
                    },
                    "usage": {"prompt": 1, "completion": 1, "total": 2},
                }
            return {"parsed_output": None, "usage": {}, "error": f"unexpected_agent:{agent_name}"}

        token_names = []

        with patch.object(vibe_factory, "call_agent_with_tools", side_effect=fake_call_agent_with_tools):
            plan = vibe_factory.build_specialized_generation_plan(
                "test_task",
                """{
                  "user_request": "학식 메뉴 앱 만들어줘",
                  "summary": "학교 학식 메뉴를 불러오는 앱",
                  "build_spec": {
                    "data_strategy": "runtime_api_fetch",
                    "source_url_candidates": ["https://example.edu/api/menu"],
                    "required_permissions": [
                      {
                        "name": "android.permission.CAMERA",
                        "usage_reason": "메뉴 인증 사진 촬영",
                        "runtime_request_required": true
                      }
                    ]
                  }
                }""",
                device_context={"model": "test-phone"},
                token_callback=lambda task_id, agent_name, usage: token_names.append(agent_name),
            )

        self.assertEqual(plan["status"], "success")
        self.assertEqual(plan["title"], "MemoCard")
        self.assertEqual(plan["files_to_create"], ["lib/main.dart"])
        self.assertIn("product_plan", plan)
        self.assertIn("ui_layout_plan", plan)
        self.assertIn("feature_logic_plan", plan)
        self.assertEqual(
            calls,
            ["Product_Planner", "UI_Layout_Designer", "Data_Model_Designer", "Feature_Logic_Designer", "Integration_Planner"],
        )
        self.assertEqual(
            token_names,
            ["Product_Planner", "UI_Layout_Designer", "Data_Model_Designer", "Feature_Logic_Designer", "Integration_Planner"],
        )


if __name__ == "__main__":
    unittest.main()
