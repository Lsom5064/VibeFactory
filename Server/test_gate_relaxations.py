import os
import tempfile
import unittest
from unittest.mock import patch

os.environ.setdefault("OPENAI_API_KEY", "test-key")

import vibe_factory


class GateRelaxationTests(unittest.TestCase):
    def test_base_project_runtime_additions_write_bootstrap_files(self):
        with tempfile.TemporaryDirectory() as project_path:
            os.makedirs(os.path.join(project_path, "lib"), exist_ok=True)

            vibe_factory.apply_base_project_runtime_additions(project_path)

            main_path = os.path.join(project_path, "lib", "main.dart")
            app_path = os.path.join(project_path, "lib", "app.dart")
            scaffold_path = os.path.join(project_path, "lib", "widgets", "app_scaffold.dart")

            self.assertTrue(os.path.isfile(main_path))
            self.assertTrue(os.path.isfile(app_path))
            self.assertTrue(os.path.isfile(scaffold_path))
            with open(main_path, "r", encoding="utf-8") as main_file:
                main_text = main_file.read()
            with open(app_path, "r", encoding="utf-8") as app_file:
                app_text = app_file.read()
            self.assertIn("CrashHandler.initialize", main_text)
            self.assertIn("GeneratedErrorView", main_text)
            self.assertIn("class MyApp", app_text)

    def test_class_method_requirement_matches_dart_member(self):
        snapshot = """
class CalculationState {
  Map<String, dynamic> toJson() => {};
}
"""
        self.assertTrue(
            vibe_factory.implementation_method_requirement_present(
                snapshot,
                "CalculationState.toJson",
            )
        )

    def test_external_api_does_not_force_cache_without_cache_requirement(self):
        spec = vibe_factory.normalize_runtime_build_spec(
            {
                "app_goal": "현재 날씨를 공개 API로 조회하는 앱",
                "data_source_type": "api",
                "source_url_candidates": ["https://api.example.com/weather"],
                "online_mode": "online",
            }
        )

        self.assertTrue(spec["verification_requirements"]["requires_external_data_verification"])
        self.assertFalse(spec["verification_requirements"]["cache_persistence_required"])

    def test_external_api_keeps_cache_when_user_requires_offline_cache(self):
        spec = vibe_factory.normalize_runtime_build_spec(
            {
                "app_goal": "날씨를 API로 조회하고 오프라인 캐시로 마지막 결과를 보여주는 앱",
                "data_source_type": "api",
                "source_url_candidates": ["https://api.example.com/weather"],
                "online_mode": "offline",
            }
        )

        self.assertTrue(spec["verification_requirements"]["cache_persistence_required"])

    def test_offline_counter_without_storage_does_not_trigger_local_state_contract(self):
        build_spec = {
            "app_goal": "오프라인 전용 단일 화면 카운터 앱",
            "data_model": "none",
            "online_mode": "offline",
            "constraints": ["저장 기능 없음", "재실행 후 복원 없음"],
            "implementation_contract": {
                "state_persistence_contract": {
                    "required": False,
                    "backend": "none",
                    "required_operations": [],
                    "restore_strategy": "not_applicable",
                }
            },
        }

        self.assertFalse(vibe_factory._requires_local_state_contract_verification(build_spec))

        with patch.object(vibe_factory, "get_current_project_snapshot") as snapshot:
            result = vibe_factory.verify_local_state_behavior_contract(
                "/tmp/project",
                build_spec,
                task_id="test_task",
            )

        self.assertEqual(result["status"], "not_applicable")
        snapshot.assert_not_called()

    def test_llm_pass_can_override_non_blocking_local_state_heuristic(self):
        build_spec = {
            "app_goal": "브랜치 저장과 복구가 있는 오프라인 계산기",
            "data_model": "local",
            "online_mode": "offline",
        }
        snapshot = """
import 'package:shared_preferences/shared_preferences.dart';

class SnapshotStore {
  Future<void> saveSnapshot() async {}
  Future<List<String>> loadSnapshots() async => [];
  Future<void> restoreSnapshot(String id) async {}
}
"""
        review = {
            "status": "pass",
            "summary": "로컬 상태 저장/복구 계약을 충족합니다.",
            "issues": [],
        }

        with (
            patch.object(vibe_factory, "get_current_project_snapshot", return_value=snapshot),
            patch.object(vibe_factory, "review_behavioral_contract_with_llm", return_value=(review, {}, None)),
        ):
            result = vibe_factory.verify_local_state_behavior_contract(
                "/tmp/project",
                build_spec,
                task_id="test_task",
            )

        self.assertEqual(result["status"], "pass")
        self.assertTrue(result["evidence"]["non_blocking_deterministic_issues"])

    def test_pubspec_name_falls_back_when_app_title_is_non_ascii(self):
        with tempfile.TemporaryDirectory() as project_path:
            pubspec_path = os.path.join(project_path, "pubspec.yaml")
            with open(pubspec_path, "w", encoding="utf-8") as pubspec_file:
                pubspec_file.write("name: baseproject\ndescription: test\n")

            vibe_factory.update_pubspec_name(project_path, "오프라인단일화면카운터앱")

            with open(pubspec_path, "r", encoding="utf-8") as pubspec_file:
                pubspec_text = pubspec_file.read()

        self.assertIn("name: generated_app", pubspec_text)
        self.assertNotIn("name: \n", pubspec_text)

    def test_dart_import_update_uses_same_safe_pubspec_name(self):
        with tempfile.TemporaryDirectory() as project_path:
            lib_path = os.path.join(project_path, "lib")
            os.makedirs(lib_path, exist_ok=True)
            dart_path = os.path.join(lib_path, "main.dart")
            with open(dart_path, "w", encoding="utf-8") as dart_file:
                dart_file.write("import 'package:baseproject/app.dart';\n")

            vibe_factory.update_dart_imports(project_path, "baseproject", "오프라인단일화면카운터앱")

            with open(dart_path, "r", encoding="utf-8") as dart_file:
                dart_text = dart_file.read()

        self.assertIn("package:generated_app/app.dart", dart_text)
        self.assertNotIn("package:/", dart_text)

    def test_reviewer_fast_path_allows_simple_verified_app(self):
        build_context = {
            "build_spec": {
                "app_goal": "오프라인 단일 화면 카운터",
                "data_model": "none",
                "online_mode": "offline",
                "verification_requirements": {},
            }
        }
        plan = {
            "title": "카운터",
            "blueprint": "단일 화면에서 숫자를 증가/감소합니다.",
            "files_to_create": ["lib/main.dart", "lib/app.dart"],
            "implementation_contract": {
                "state_persistence_contract": {"required": False},
            },
        }

        self.assertTrue(
            vibe_factory.reviewer_fast_path_allowed(
                build_context,
                plan,
                analyze_ok=True,
                engineer_finalize_result={"status": "done"},
                preflight={"status": "pass"},
            )
        )

    def test_reviewer_fast_path_keeps_full_review_for_external_api_app(self):
        build_context = {
            "build_spec": {
                "app_goal": "현재 날씨를 API로 조회하는 앱",
                "data_source_type": "api",
                "source_url_candidates": ["https://api.example.com/weather"],
                "verification_requirements": {
                    "requires_external_data_verification": True,
                },
            }
        }
        plan = {
            "title": "날씨",
            "blueprint": "API 응답을 파싱해 보여줍니다.",
            "files_to_create": ["lib/main.dart", "lib/weather_repository.dart"],
        }

        self.assertFalse(
            vibe_factory.reviewer_fast_path_allowed(
                build_context,
                plan,
                analyze_ok=True,
                engineer_finalize_result={"status": "done"},
                preflight={"status": "pass"},
            )
        )

    def test_repair_budget_does_not_extend_repeated_same_patch_failure(self):
        budget = vibe_factory.init_repair_budget("generate")
        vibe_factory.record_repair_round(
            budget,
            stage="build",
            attempt=1,
            success=False,
            failure_type="compile_error",
            error_log="Error: Undefined name Foo",
            touched_paths=["lib/main.dart"],
        )
        vibe_factory.record_repair_round(
            budget,
            stage="build",
            attempt=2,
            success=False,
            failure_type="compile_error",
            error_log="Error: Undefined name Foo",
            touched_paths=["lib/main.dart"],
        )

        self.assertTrue(vibe_factory.repair_attempts_are_repeating_without_progress(budget, "build"))

    def test_llm_pass_does_not_override_blocking_memory_only_state(self):
        build_spec = {
            "app_goal": "브랜치 저장과 복구가 있는 오프라인 계산기",
            "data_model": "local",
            "online_mode": "offline",
        }
        snapshot = """
class BranchScreen {
  final List<String> snapshots = [];
  void saveSnapshot() {}
  void restoreSnapshot() {}
}
"""
        review = {
            "status": "pass",
            "summary": "로컬 상태 저장/복구 계약을 충족합니다.",
            "issues": [],
        }

        with (
            patch.object(vibe_factory, "get_current_project_snapshot", return_value=snapshot),
            patch.object(vibe_factory, "review_behavioral_contract_with_llm", return_value=(review, {}, None)),
        ):
            result = vibe_factory.verify_local_state_behavior_contract(
                "/tmp/project",
                build_spec,
                task_id="test_task",
            )

        self.assertEqual(result["status"], "fail")
        self.assertTrue(any("메모리" in issue or "영속 저장" in issue for issue in result["issues"]))

    def test_compact_generated_app_title_trims_verbose_plan_label(self):
        title = vibe_factory.compact_generated_app_title(
            "브랜치 저장과 복귀 기능이 있는 오프라인 계산기 앱 구현 블루프린트"
        )

        self.assertTrue(title)
        self.assertLessEqual(len(title), 18)
        self.assertNotIn("블루프린트", title)
        self.assertNotIn("구현", title)
        self.assertNotIn("통합", title)
        self.assertIn("계산기", title)

    def test_compact_generated_app_title_keeps_short_title(self):
        self.assertEqual(
            vibe_factory.compact_generated_app_title("카운터 앱"),
            "카운터 앱",
        )

    def test_normalize_plan_title_uses_fallback_when_title_is_generic(self):
        plan = {"title": "앱 구현 블루프린트"}

        title = vibe_factory.normalize_plan_title(
            plan,
            product_plan={"app_goal": "오프라인 일정 관리 앱"},
            build_context={"summary": "일정을 저장하고 확인하는 단일 화면 앱"},
        )

        self.assertEqual(plan["title"], title)
        self.assertEqual(title, "일정 관리 앱")

    def test_runtime_build_spec_infers_scope_constraints_for_external_data_request(self):
        spec = vibe_factory.normalize_runtime_build_spec(
            {
                "app_goal": "강원대학교 춘천 캠퍼스 학식당 메뉴를 읽어와서 노티를 주는 앱",
                "data_source_type": "web_scrape",
            }
        )

        constraints = spec.get("source_selection_constraints") or {}
        self.assertIn("강원대학교", constraints.get("required_terms") or [])
        self.assertIn("춘천", constraints.get("required_terms") or [])
        self.assertGreaterEqual(constraints.get("minimum_required_matches") or 0, 2)

    def test_runtime_build_spec_does_not_learn_scope_from_generated_constraints(self):
        spec = vibe_factory.normalize_runtime_build_spec(
            {
                "app_goal": "강원대학교 학식당 메뉴를 읽어와서 노티를 주는 앱",
                "data_source_type": "web_scrape",
                "constraints": [
                    "검색 결과상 삼척캠퍼스 식단 표본이 확인되므로 이를 기준으로 구현",
                ],
            }
        )

        constraints = spec.get("source_selection_constraints") or {}
        required_terms = constraints.get("required_terms") or []
        self.assertIn("강원대학교", required_terms)
        self.assertNotIn("삼척", required_terms)
        self.assertNotIn("삼척캠퍼스", required_terms)

    def test_source_constraints_ignore_question_words_and_content_nouns(self):
        cafeteria = vibe_factory.infer_source_selection_constraints(
            "강원대학교 학식당 메뉴를 매일 아침 8시에 노티로 알려주는 앱을 만들어줘. "
            "어떤 식당의 메뉴를 노티 줄지는 내가 정할 수 있어야해."
        )
        news = vibe_factory.infer_source_selection_constraints(
            "긱뉴스 최신 글을 읽어와 보여주는 앱"
        )

        self.assertEqual(cafeteria.get("required_terms"), ["강원대학교"])
        self.assertNotIn("어떤", cafeteria.get("required_terms") or [])
        self.assertEqual(news.get("required_terms"), ["긱뉴스"])
        self.assertNotIn("글", news.get("required_terms") or [])

    def test_source_constraint_extractor_sanitizer_rejects_hallucinated_scope(self):
        payload = {
            "required_entities": [
                {
                    "text": "강원대학교",
                    "entity_type": "institution",
                    "must_match": True,
                    "evidence_span": "강원대학교",
                    "reason": "공식 출처 범위",
                },
                {
                    "text": "춘천",
                    "entity_type": "branch_or_campus",
                    "must_match": True,
                    "evidence_span": "춘천",
                    "reason": "캠퍼스 범위",
                },
                {
                    "text": "메뉴",
                    "entity_type": "domain_term",
                    "must_match": True,
                    "evidence_span": "메뉴",
                    "reason": "콘텐츠 유형",
                },
            ],
            "optional_domain_terms": ["학식당", "식당"],
            "forbidden_entities": [],
            "needs_clarification": True,
            "clarification_reason": "캠퍼스 범위가 명시되지 않았습니다.",
            "confidence": 0.8,
            "rationale": "원문 기반 추출",
        }

        constraints = vibe_factory.sanitize_source_constraint_extraction(
            payload,
            "강원대학교 학식당 메뉴를 매일 아침 8시에 알려주는 앱",
        )

        self.assertEqual(constraints.get("required_terms"), ["강원대학교"])
        self.assertNotIn("춘천", constraints.get("required_terms") or [])
        self.assertNotIn("메뉴", constraints.get("required_terms") or [])
        self.assertIn("메뉴", constraints.get("optional_terms") or [])

    def test_source_constraint_extractor_with_llm_uses_validated_payload(self):
        payload = {
            "required_entities": [
                {
                    "text": "강원대학교",
                    "entity_type": "institution",
                    "must_match": True,
                    "evidence_span": "강원대학교",
                    "reason": "출처 기관",
                },
                {
                    "text": "춘천",
                    "entity_type": "branch_or_campus",
                    "must_match": True,
                    "evidence_span": "춘천 캠퍼스",
                    "reason": "캠퍼스 범위",
                },
            ],
            "optional_domain_terms": ["학식당", "메뉴"],
            "forbidden_entities": [],
            "needs_clarification": False,
            "clarification_reason": "",
            "confidence": 0.93,
            "rationale": "기관과 캠퍼스가 명시됨",
        }

        with patch.object(
            vibe_factory,
            "call_agent_with_tools",
            return_value={
                "parsed_output": payload,
                "usage": {"prompt": 1, "completion": 1, "total": 2},
                "error": None,
            },
        ):
            constraints, usage, error = vibe_factory.extract_source_selection_constraints_with_llm(
                "강원대학교 춘천 캠퍼스 학식당 메뉴를 읽어와서 알려주는 앱",
                task_id="test_task",
            )

        self.assertIsNone(error)
        self.assertEqual(usage["total"], 2)
        self.assertEqual(constraints.get("required_terms"), ["강원대학교", "춘천"])
        self.assertIn("학식당", constraints.get("optional_terms") or [])

    def test_explicit_source_constraints_are_authoritative_during_normalization(self):
        spec = vibe_factory.normalize_runtime_build_spec(
            {
                "app_goal": "강원대학교 삼척캠퍼스 학생식당 메뉴",
                "data_source_type": "web_scrape",
                "source_selection_constraints": {
                    "required_terms": ["강원대학교"],
                    "optional_terms": ["학식당", "메뉴"],
                    "forbidden_terms": [],
                    "minimum_required_matches": 1,
                },
            }
        )

        required_terms = (spec.get("source_selection_constraints") or {}).get("required_terms") or []
        self.assertEqual(required_terms, ["강원대학교"])

    def test_web_data_analysis_extracts_content_link_samples(self):
        page_result = {
            "status": "success",
            "final_url": "https://news.hada.io/",
            "title": "GeekNews",
            "text_content": "GeekNews 최신 기술 뉴스",
            "web_structure": {
                "tables": [],
                "links": [
                    {
                        "url": "https://news.hada.io/topic?id=12345",
                        "text": "새로운 API 출시와 개발자 도구 업데이트",
                    },
                    {
                        "url": "https://news.hada.io/topic?id=12346",
                        "text": "Flutter 성능 개선 사례 정리",
                    },
                    {
                        "url": "https://news.hada.io/login",
                        "text": "로그인",
                    },
                ],
                "iframes": [],
                "scripts": [],
                "candidate_data_urls": [],
                "signals": {},
            },
        }

        analysis = vibe_factory.analyze_web_data_source(
            "https://news.hada.io/",
            user_goal="긱뉴스 최신 피드 앱",
            page_result=page_result,
            task_id="test_task",
        )

        self.assertEqual(analysis["source_kind"], "static_html_links")
        self.assertEqual(analysis["parser_contract"]["parser_strategy"], "html_link_list")
        self.assertEqual(len(analysis["sample_records"]), 2)

    def test_static_preflight_blocks_json_decode_for_html_contract(self):
        build_spec = vibe_factory.normalize_runtime_build_spec(
            {
                "app_goal": "HTML 페이지에서 최신 글을 읽어오는 앱",
                "data_source_type": "web_scrape",
                "web_data_contract": {
                    "source_kind": "static_html_links",
                    "primary_url": "https://news.example.com/",
                    "candidate_urls": ["https://news.example.com/"],
                    "parser_strategy": "html_link_list",
                    "minimum_sample_records": 1,
                    "sample_records": [{"title": "샘플", "url": "https://news.example.com/post/1"}],
                },
            }
        )
        with tempfile.TemporaryDirectory() as project_path:
            os.makedirs(os.path.join(project_path, "lib"), exist_ok=True)
            with open(os.path.join(project_path, "pubspec.yaml"), "w", encoding="utf-8") as pubspec:
                pubspec.write("name: generated_app\ndependencies:\n  flutter:\n    sdk: flutter\n")
            with open(os.path.join(project_path, "lib", "main.dart"), "w", encoding="utf-8") as dart_file:
                dart_file.write(
                    """
import 'dart:convert';
import 'package:flutter/material.dart';
import 'crash_handler.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  CrashHandler.initialize("task", "pkg");
  runApp(const MyApp());
}

class MyApp extends StatelessWidget {
  const MyApp({super.key});
  @override
  Widget build(BuildContext context) => const MaterialApp(home: SizedBox());
}

Object parseResponse(dynamic response) {
  return jsonDecode(response.body);
}
"""
                )
            with open(os.path.join(project_path, "lib", "crash_handler.dart"), "w", encoding="utf-8") as dart_file:
                dart_file.write("class CrashHandler { static void initialize(String a, String b) {} }\n")

            preflight = vibe_factory.run_static_preflight_checks(
                project_path,
                task_id="task",
                package_name="pkg",
                build_spec=build_spec,
            )

        self.assertEqual(preflight["status"], "fail")
        issues = "\n".join(preflight["issues"])
        self.assertIn("html dependency", issues)
        self.assertIn("package:html/parser.dart", issues)
        self.assertIn("jsonDecode/json.decode", issues)

    def test_static_preflight_accepts_html_parser_for_html_contract(self):
        build_spec = vibe_factory.normalize_runtime_build_spec(
            {
                "app_goal": "HTML 페이지에서 최신 글을 읽어오는 앱",
                "data_source_type": "web_scrape",
                "web_data_contract": {
                    "source_kind": "static_html_links",
                    "primary_url": "https://news.example.com/",
                    "candidate_urls": ["https://news.example.com/"],
                    "parser_strategy": "html_link_list",
                    "minimum_sample_records": 1,
                    "sample_records": [{"title": "샘플", "url": "https://news.example.com/post/1"}],
                },
            }
        )
        with tempfile.TemporaryDirectory() as project_path:
            os.makedirs(os.path.join(project_path, "lib"), exist_ok=True)
            with open(os.path.join(project_path, "pubspec.yaml"), "w", encoding="utf-8") as pubspec:
                pubspec.write("name: generated_app\ndependencies:\n  flutter:\n    sdk: flutter\n  html: ^0.15.4\n")
            with open(os.path.join(project_path, "lib", "main.dart"), "w", encoding="utf-8") as dart_file:
                dart_file.write(
                    """
import 'package:flutter/material.dart';
import 'package:html/parser.dart' as html_parser;
import 'crash_handler.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  CrashHandler.initialize("task", "pkg");
  runApp(const MyApp());
}

class MyApp extends StatelessWidget {
  const MyApp({super.key});
  @override
  Widget build(BuildContext context) => const MaterialApp(home: SizedBox());
}

List<String> parseResponse(dynamic response) {
  final document = html_parser.parse(response.body);
  return document.querySelectorAll('a').map((node) => node.text.trim()).toList();
}
"""
                )
            with open(os.path.join(project_path, "lib", "crash_handler.dart"), "w", encoding="utf-8") as dart_file:
                dart_file.write("class CrashHandler { static void initialize(String a, String b) {} }\n")

            preflight = vibe_factory.run_static_preflight_checks(
                project_path,
                task_id="task",
                package_name="pkg",
                build_spec=build_spec,
            )

        self.assertEqual(preflight["status"], "pass")

    def test_data_model_plan_is_aligned_to_html_web_contract(self):
        build_spec = vibe_factory.normalize_runtime_build_spec(
            {
                "app_goal": "HTML 페이지에서 최신 글을 읽어오는 앱",
                "data_source_type": "web_scrape",
                "web_data_contract": {
                    "source_kind": "static_html_links",
                    "primary_url": "https://news.example.com/",
                    "candidate_urls": ["https://news.example.com/"],
                    "parser_strategy": "html_link_list",
                    "minimum_sample_records": 1,
                    "sample_records": [{"title": "샘플", "url": "https://news.example.com/post/1"}],
                },
            }
        )
        plan = vibe_factory.align_data_model_plan_with_web_data_contract(
            {
                "entities": [
                    {
                        "name": "NewsItem",
                        "fields": [
                            {
                                "name": "title",
                                "type": "String",
                                "required": True,
                                "source_path": "$.title",
                                "normalization": "trim",
                            }
                        ],
                    }
                ],
                "source_mapping": {
                    "source_kind": "api",
                    "primary_url": "https://wrong.example.com/json",
                    "candidate_urls": ["https://wrong.example.com/json"],
                    "parser_strategy": "json",
                    "response_root": "$",
                    "record_selector": "$.items",
                },
                "validation_rules": [],
                "empty_state_rules": [],
                "cache_model": {"enabled": False, "key_fields": [], "ttl_minutes": 0, "stale_data_behavior": ""},
            },
            build_spec,
        )

        source_mapping = plan["source_mapping"]
        self.assertEqual(source_mapping["source_kind"], "static_html_links")
        self.assertEqual(source_mapping["primary_url"], "https://news.example.com/")
        self.assertEqual(source_mapping["parser_strategy"], "html_link_list")
        self.assertTrue(any("jsonDecode" in item for item in plan["validation_rules"]))

    def test_readiness_contract_augments_external_html_notification_app(self):
        build_context = {
            "user_request": "긱뉴스 최신 글을 읽어오고 새 글이 있으면 알림을 주는 앱",
            "summary": "긱뉴스 새 글 알림 앱",
            "build_spec": vibe_factory.normalize_runtime_build_spec(
                {
                    "app_goal": "긱뉴스 최신 글을 읽어오고 새 글이 있으면 알림을 주는 앱",
                    "data_source_type": "web_scrape",
                    "web_data_contract": {
                        "source_kind": "static_html_links",
                        "primary_url": "https://news.hada.io/",
                        "candidate_urls": ["https://news.hada.io/"],
                        "parser_strategy": "html_link_list",
                        "minimum_sample_records": 1,
                        "sample_records": [
                            {"title": "새 글", "url": "https://news.hada.io/topic?id=1"}
                        ],
                    },
                }
            ),
        }
        base_contract = {
            "app_pattern": "read_only_external_data",
            "required_files": ["lib/main.dart"],
            "required_classes": ["MyApp"],
            "required_methods": ["main", "CrashHandler.initialize"],
            "required_dependencies": [],
            "required_permissions": [],
            "state_persistence_contract": {
                "required": False,
                "backend": "none",
                "snapshot_model": "none",
                "required_operations": [],
                "restore_strategy": "not_applicable",
                "serialization_required": False,
            },
            "navigation_contract": {"screens": [], "routes": [], "restore_flow": "not_applicable"},
            "acceptance_checks": ["서버 static preflight 통과"],
            "repair_scope_rules": ["CrashHandler 파일은 재정의하지 않음"],
        }
        data_model_plan = {
            "entities": [{"name": "ArticleItem", "fields": [{"name": "title", "type": "String", "required": True, "source_path": "a", "normalization": "trim"}]}],
            "source_mapping": {"parser_strategy": "html_link_list"},
        }
        feature_logic_plan = {"lifecycle_and_resilience": "새 글이 있으면 알림을 표시하고 본 글 id를 저장"}

        contract = vibe_factory.augment_implementation_contract_for_readiness(
            base_contract,
            build_context=build_context,
            data_model_plan=data_model_plan,
            feature_logic_plan=feature_logic_plan,
        )
        readiness = vibe_factory.validate_implementation_readiness_contract(
            {"implementation_contract": contract},
            build_context,
        )

        self.assertEqual(readiness["status"], "pass")
        self.assertIn("ArticleItem", contract["required_classes"])
        self.assertIn("ExternalDataRepository", contract["required_classes"])
        self.assertIn("NotificationService", contract["required_classes"])
        self.assertIn("html", contract["required_dependencies"])
        self.assertIn("flutter_local_notifications", contract["required_dependencies"])
        self.assertIn("android.permission.POST_NOTIFICATIONS", contract["required_permissions"])

    def test_static_preflight_checks_contract_dependencies_and_permissions(self):
        build_spec = vibe_factory.normalize_runtime_build_spec(
            {
                "app_goal": "새 글 알림 앱",
                "data_source_type": "web_scrape",
                "source_url_candidates": ["https://news.example.com/"],
                "implementation_contract": {
                    "app_pattern": "read_only_external_data",
                    "required_files": ["lib/main.dart"],
                    "required_classes": ["MyApp"],
                    "required_methods": ["main", "CrashHandler.initialize"],
                    "required_dependencies": ["flutter_local_notifications"],
                    "required_permissions": ["android.permission.POST_NOTIFICATIONS"],
                    "state_persistence_contract": {
                        "required": False,
                        "backend": "none",
                        "snapshot_model": "none",
                        "required_operations": [],
                        "restore_strategy": "not_applicable",
                        "serialization_required": False,
                    },
                    "navigation_contract": {"screens": [], "routes": [], "restore_flow": "not_applicable"},
                    "acceptance_checks": ["서버 static preflight 통과"],
                    "repair_scope_rules": ["CrashHandler 파일은 재정의하지 않음"],
                },
            }
        )
        with tempfile.TemporaryDirectory() as project_path:
            os.makedirs(os.path.join(project_path, "lib"), exist_ok=True)
            os.makedirs(os.path.join(project_path, "android", "app", "src", "main"), exist_ok=True)
            with open(os.path.join(project_path, "pubspec.yaml"), "w", encoding="utf-8") as pubspec:
                pubspec.write("name: generated_app\ndependencies:\n  flutter:\n    sdk: flutter\n")
            with open(os.path.join(project_path, "android", "app", "src", "main", "AndroidManifest.xml"), "w", encoding="utf-8") as manifest:
                manifest.write("<manifest><application /></manifest>")
            with open(os.path.join(project_path, "lib", "main.dart"), "w", encoding="utf-8") as dart_file:
                dart_file.write(
                    """
import 'package:flutter/material.dart';
import 'crash_handler.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  CrashHandler.initialize("task", "pkg");
  runApp(const MyApp());
}

class MyApp extends StatelessWidget {
  const MyApp({super.key});
  @override
  Widget build(BuildContext context) => const MaterialApp(home: SizedBox());
}
"""
                )
            with open(os.path.join(project_path, "lib", "crash_handler.dart"), "w", encoding="utf-8") as dart_file:
                dart_file.write("class CrashHandler { static void initialize(String a, String b) {} }\n")

            preflight = vibe_factory.run_static_preflight_checks(
                project_path,
                task_id="task",
                package_name="pkg",
                build_spec=build_spec,
            )

        self.assertEqual(preflight["status"], "fail")
        issues = "\n".join(preflight["issues"])
        self.assertIn("required_dependencies", issues)
        self.assertIn("flutter_local_notifications", issues)
        self.assertIn("required_permissions", issues)
        self.assertIn("POST_NOTIFICATIONS", issues)

    def test_research_quality_fails_when_fetched_source_misses_required_scope_terms(self):
        result = {
            "title": "강원대학교 학생식당 메뉴",
            "url": "https://www.kangwon.ac.kr/menu",
            "snippet": "강원대학교 학생식당 메뉴 안내",
        }
        fetched_page = {
            "title": "강원대학교 삼척캠퍼스 학생식당 메뉴",
            "final_url": "https://www.kangwon.ac.kr/samcheok/menu",
            "text_content": "강원대학교 삼척캠퍼스 학생식당 오늘 식단",
        }

        quality = vibe_factory.evaluate_research_quality(
            "강원대학교 춘천 캠퍼스 학식당 메뉴",
            [result],
            [fetched_page],
            direct_fetch=False,
            task_id="test_task",
        )

        self.assertFalse(quality["research_quality_passed"])
        self.assertEqual(quality["research_quality_reason"], "source_constraints_unmatched")

    def test_source_constraint_verifier_rejects_wrong_branch_source(self):
        build_spec = vibe_factory.normalize_runtime_build_spec(
            {
                "app_goal": "강원대학교 춘천 캠퍼스 학식당 메뉴를 읽어와서 노티를 주는 앱",
                "data_source_type": "web_scrape",
                "source_url_candidates": ["https://www.kangwon.ac.kr/samcheok/menu"],
            }
        )
        fetched_sources = [
            {
                "url": "https://www.kangwon.ac.kr/samcheok/menu",
                "final_url": "https://www.kangwon.ac.kr/samcheok/menu",
                "title": "강원대학교 삼척캠퍼스 학생식당 메뉴",
                "text_content": "강원대학교 삼척캠퍼스 학생식당 오늘 식단",
                "web_data_analysis": {
                    "sample_records": [{"text": "삼척캠퍼스 학생식당 중식"}],
                },
            }
        ]

        report = vibe_factory._deterministic_source_constraint_checks(build_spec, fetched_sources)

        self.assertEqual(report["status"], "fail")
        self.assertTrue(any("필수 엔티티" in issue for issue in report["issues"]))


if __name__ == "__main__":
    unittest.main()
