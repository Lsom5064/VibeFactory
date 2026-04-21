import os
import time
import uuid
import uvicorn
import sqlite3
import json
import traceback
import logging
import shutil
import re
import base64
import tempfile
import hashlib
from urllib.parse import parse_qsl, urlencode, urlparse

from fastapi import FastAPI, BackgroundTasks, HTTPException
from fastapi.responses import FileResponse
from pydantic import BaseModel
from typing import Optional, Dict, Any, List

# 멀티 에이전트 기반 vibe_factory.py 임포트
from vibe_factory import (
    BUILD_ROOT_DIR,
    run_vibe_factory,
    refine_vibe_app,
    create_refinement_plan_preview,
    retry_failed_vibe_app,
    get_current_project_snapshot,
    call_agent_with_tools,
    save_project_files,
    run_flutter_build,
    run_flutter_analyze,
    run_static_preflight_checks,
    DEBUGGER_SYSTEM,
    FILE_CHANGE_TOOL_SCHEMAS,
    decide_generate_action,
    decide_feedback_action,
    assess_research_requirement,
    summarize_runtime_error,
    summarize_build_failure,
    search_web_reference,
    search_api_docs,
    fetch_webpage,
    fetch_api_reference,
    parse_openapi_reference,
    analyze_web_data_source,
    evaluate_research_quality,
    select_best_api_source,
    test_http_request,
    analyze_reference_image,
    synthesize_researched_build,
    extract_ui_contract,
    verify_release_external_data_gate,
    validate_file_change_payload,
    normalize_file_change_payload,
    normalize_runtime_build_spec,
    extract_source_selection_constraints_with_llm,
    legacy_agent_response_detailed,
    classify_failure_type,
    apply_project_files_safely,
)

app = FastAPI(title="Vibe App Factory 4.0 - Smartphone App 2.0 Engine")

logger = logging.getLogger("vibe_server")
if not logger.handlers:
    handler = logging.StreamHandler()
    handler.setFormatter(logging.Formatter("%(message)s"))
    logger.addHandler(handler)
logger.setLevel(logging.INFO)
logger.propagate = False

DB_PATH = "tasks.db"
WORKSPACE_CLONE_EXCLUDE_PATTERNS = (
    "build",
    ".gradle",
    ".dart_tool",
    ".idea",
    ".flutter-plugins",
    ".flutter-plugins-dependencies",
    "*.iml",
)


def to_bool(value: Any) -> bool:
    return bool(value) if value is not None else False


def looks_like_url(value: str) -> bool:
    text = (value or "").strip().lower()
    return text.startswith("http://") or text.startswith("https://")


def looks_like_api_reference_url(value: str) -> bool:
    text = (value or "").strip().lower()
    return any(marker in text for marker in [
        "/swagger",
        "/openapi",
        "/api-docs",
        "/reference",
        "/docs",
        "swagger",
        "openapi",
        "redoc",
        "postman",
    ])


def looks_like_openapi_reference_url(value: str) -> bool:
    text = (value or "").strip().lower()
    return any(marker in text for marker in [
        "openapi.json",
        "swagger.json",
        "/v3/api-docs",
        "/v2/api-docs",
        "/openapi",
        "/swagger",
    ])


def looks_like_api_integration_request(user_prompt: str, research_query: str) -> bool:
    text = " ".join([(user_prompt or ""), (research_query or "")]).lower()
    return any(marker in text for marker in [
        " api",
        "api ",
        "연동",
        "oauth",
        "token",
        "endpoint",
        "swagger",
        "openapi",
        "developer",
        "developers",
        "reference",
        "sdk",
        "rest",
        "graphql",
        "webhook",
    ])


def looks_like_runtime_web_data_request(user_prompt: str, research_query: str) -> bool:
    text = " ".join([(user_prompt or ""), (research_query or "")]).lower()
    return any(marker in text for marker in [
        "식단",
        "메뉴",
        "학식",
        "급식",
        "최신",
        "rss",
        "feed",
        "뉴스",
        "기사",
        "article",
        "headline",
        "blog",
        "post",
        "meal",
        "menu",
        "cafeteria",
        "시간표",
        "일정",
        "schedule",
        "calendar",
        "조회",
        "표",
        "현황",
        "공지",
        "목록",
    ])


def choose_external_research_query(
    *,
    raw_user_message: str,
    user_prompt: str,
    summary: str,
    build_spec: Dict[str, Any],
) -> str:
    candidates = [
        summary,
        build_spec.get("app_goal") if isinstance(build_spec, dict) else "",
        user_prompt,
        raw_user_message,
    ]
    for candidate in candidates:
        if isinstance(candidate, str) and candidate.strip():
            return candidate.strip()
    return ""


def has_negative_external_dependency_signal(*texts: Any) -> bool:
    combined = " ".join(str(text or "") for text in texts).lower()
    if not combined.strip():
        return False
    negative_patterns = [
        "api는 필요 없어",
        "api 필요 없어",
        "api는 필요없어",
        "api 필요없어",
        "api 필요 없음",
        "api 안 써도",
        "api 없이",
        "web search 필요 없어",
        "검색 필요 없어",
        "웹 검색 필요 없어",
        "웹서치 필요 없어",
        "웹 서치 필요 없어",
        "웹 크롤링 필요 없어",
        "웹크롤링 필요 없어",
        "크롤링 필요 없어",
        "크롤링 없이",
        "외부 데이터 필요 없어",
        "외부데이터 필요 없어",
        "외부 연동 필요 없어",
        "연동 필요 없어",
        "오프라인이면 돼",
        "오프라인으로",
        "offline only",
        "no api",
        "without api",
        "without web",
        "no web search",
        "no scraping",
    ]
    return any(pattern in combined for pattern in negative_patterns)


def maybe_force_external_research_decision(
    decision: Dict[str, Any],
    *,
    user_prompt: str,
    raw_user_message: str,
    task_id: Optional[str] = None,
    token_callback=None,
) -> Dict[str, Any]:
    if not isinstance(decision, dict):
        return decision

    if decision.get("tool") != "build_app":
        return decision

    arguments = dict(decision.get("arguments") or {})
    build_spec = normalize_runtime_build_spec(arguments.get("build_spec") or {})
    arguments["build_spec"] = build_spec
    normalized_decision = dict(decision)
    normalized_decision["arguments"] = arguments

    if build_spec.get("source_url_candidates"):
        return normalized_decision

    combined_text = " ".join(
        part.strip()
        for part in [
            raw_user_message or "",
            user_prompt or "",
            arguments.get("summary") or "",
            build_spec.get("app_goal") or "",
        ]
        if isinstance(part, str) and part.strip()
    )
    explicit_external_contract = (
        build_spec.get("data_source_type") in {"web_scrape", "api"}
        or build_spec.get("source_access_mode") in {"scraping", "api"}
        or bool(build_spec.get("external_sources"))
    )
    explicit_source_reference = any(
        looks_like_url(part)
        for part in [raw_user_message or "", user_prompt or ""]
        if isinstance(part, str) and part.strip()
    )
    guard_decision = None
    guard_usage = None
    guard_error = None
    try:
        guard_decision, guard_usage, guard_error = assess_research_requirement(
            raw_user_message or user_prompt,
            summary=arguments.get("summary") or "",
            build_spec=build_spec,
            task_id=task_id,
        )
    except Exception as exc:
        guard_error = str(exc)
    if token_callback and task_id and guard_usage:
        token_callback(task_id, "Research_Requirement_Guard", guard_usage)

    negative_external_signal = has_negative_external_dependency_signal(
        raw_user_message,
        user_prompt,
        arguments.get("summary") or "",
        build_spec.get("app_goal") or "",
    )
    api_request = looks_like_api_integration_request(combined_text, combined_text) and not negative_external_signal
    runtime_web_request = looks_like_runtime_web_data_request(combined_text, combined_text)
    explicit_api_reference_request = api_request and any(
        marker in combined_text.lower()
        for marker in [
            "swagger",
            "openapi",
            " api ",
            "api ",
            " endpoint",
            "endpoint ",
            "graphql",
            "webhook",
            "sdk",
        ]
    )
    if isinstance(guard_decision, dict):
        if guard_decision.get("decision") == "force_research":
            if not negative_external_signal or explicit_external_contract or explicit_source_reference or runtime_web_request:
                research_query = (
                    guard_decision.get("research_query")
                    or choose_external_research_query(
                        raw_user_message=raw_user_message,
                        user_prompt=user_prompt,
                        summary=arguments.get("summary") or "",
                        build_spec=build_spec,
                    )
                )
                research_reason = (
                    guard_decision.get("reason")
                    or "사용자 요청이 외부 정보 검증을 요구하므로 조사 후 빌드합니다."
                )
                if research_query:
                    return {
                        "tool": "research_then_build",
                        "arguments": {
                            "summary": (arguments.get("summary") or build_spec.get("app_goal") or research_query).strip(),
                            "research_query": research_query,
                            "research_reason": research_reason,
                        },
                    }
        elif guard_decision.get("decision") == "skip_research":
            return normalized_decision

    if negative_external_signal and not (
        explicit_external_contract or explicit_source_reference or runtime_web_request
    ):
        return normalized_decision
    if not (explicit_external_contract or explicit_source_reference or explicit_api_reference_request or runtime_web_request):
        return normalized_decision

    research_query = choose_external_research_query(
        raw_user_message=raw_user_message,
        user_prompt=user_prompt,
        summary=arguments.get("summary") or "",
        build_spec=build_spec,
    )
    if not research_query:
        return normalized_decision

    research_reason = (
        "사용자 요청이 실시간 외부 데이터에 의존하므로 공식 출처와 검증 계약을 먼저 고정한 뒤 빌드합니다."
        if explicit_external_contract or runtime_web_request or explicit_source_reference
        else "사용자 요청이 외부 API 연동에 의존하므로 공식 문서와 접근 제약을 먼저 확인한 뒤 빌드합니다."
    )
    return {
        "tool": "research_then_build",
        "arguments": {
            "summary": (arguments.get("summary") or build_spec.get("app_goal") or research_query).strip(),
            "research_query": research_query,
            "research_reason": research_reason,
        },
    }


def _best_web_data_analysis(analyses: List[Dict[str, Any]]) -> Optional[Dict[str, Any]]:
    viable = [item for item in analyses if isinstance(item, dict) and item.get("status") == "success"]
    if not viable:
        return None
    return sorted(
        viable,
        key=lambda item: (
            len(item.get("sample_records") or []),
            float(item.get("confidence") or 0.0),
            len(item.get("candidate_urls") or []),
        ),
        reverse=True,
    )[0]


def _merge_web_data_contracts(
    existing_contract: Optional[Dict[str, Any]],
    selected_contract: Optional[Dict[str, Any]],
) -> Dict[str, Any]:
    base = dict(existing_contract) if isinstance(existing_contract, dict) else {}
    selected = dict(selected_contract) if isinstance(selected_contract, dict) else {}
    if not selected:
        return base
    if not base:
        merged = dict(selected)
    else:
        merged = dict(base)

    def _collect_strings(*values: Any, max_items: int = 8) -> List[str]:
        collected: List[str] = []
        seen = set()
        for value in values:
            if isinstance(value, list):
                items = value
            else:
                items = [value]
            for item in items:
                if not isinstance(item, str) or not item.strip():
                    continue
                normalized = item.strip()
                if normalized in seen:
                    continue
                seen.add(normalized)
                collected.append(normalized)
                if len(collected) >= max_items:
                    return collected
        return collected

    def _collect_sample_records(*values: Any, max_items: int = 5) -> List[Any]:
        collected: List[Any] = []
        seen = set()
        for value in values:
            if isinstance(value, list):
                items = value
            else:
                items = [value]
            for item in items:
                if isinstance(item, dict):
                    normalized = dict(item)
                    key = json.dumps(normalized, ensure_ascii=False, sort_keys=True)
                elif isinstance(item, str) and item.strip():
                    normalized = item.strip()
                    key = normalized
                else:
                    continue
                if key in seen:
                    continue
                seen.add(key)
                collected.append(normalized)
                if len(collected) >= max_items:
                    return collected
        return collected

    for key in ("source_kind", "primary_url", "parser_strategy", "required_runtime_behavior"):
        if not isinstance(merged.get(key), str) or not merged.get(key, "").strip():
            if isinstance(selected.get(key), str) and selected.get(key, "").strip():
                merged[key] = selected.get(key).strip()

    merged["candidate_urls"] = _collect_strings(
        merged.get("candidate_urls"),
        selected.get("candidate_urls"),
        merged.get("primary_url"),
        selected.get("primary_url"),
        max_items=12,
    )
    merged["sample_records"] = _collect_sample_records(
        merged.get("sample_records"),
        selected.get("sample_records"),
        max_items=5,
    )
    merged["accepted_parser_strategies"] = _collect_strings(
        merged.get("accepted_parser_strategies"),
        merged.get("parser_strategy"),
        selected.get("accepted_parser_strategies"),
        selected.get("parser_strategy"),
        max_items=6,
    )
    merged["accepted_source_kinds"] = _collect_strings(
        merged.get("accepted_source_kinds"),
        merged.get("source_kind"),
        selected.get("accepted_source_kinds"),
        selected.get("source_kind"),
        max_items=6,
    )

    try:
        minimum_sample_records = int(
            merged.get("minimum_sample_records")
            or selected.get("minimum_sample_records")
            or 1
        )
    except (TypeError, ValueError):
        minimum_sample_records = 1
    observed_sample_count = len(merged.get("sample_records") or [])
    if observed_sample_count:
        minimum_sample_records = min(max(1, minimum_sample_records), observed_sample_count)
    merged["minimum_sample_records"] = max(1, minimum_sample_records)
    return merged


def _enrich_build_spec_with_web_data_contract(
    build_spec: Dict[str, Any],
    selected_analysis: Optional[Dict[str, Any]],
) -> Dict[str, Any]:
    if not isinstance(build_spec, dict):
        build_spec = {}
    if not isinstance(selected_analysis, dict):
        return build_spec
    parser_contract = selected_analysis.get("parser_contract")
    if not isinstance(parser_contract, dict):
        return build_spec
    enriched = dict(build_spec)
    enriched["web_data_contract"] = _merge_web_data_contracts(
        enriched.get("web_data_contract"),
        parser_contract,
    )
    source_urls = []
    existing_urls = enriched.get("source_url_candidates")
    if isinstance(existing_urls, list):
        source_urls.extend(existing_urls)
    merged_contract = enriched.get("web_data_contract") if isinstance(enriched.get("web_data_contract"), dict) else {}
    source_urls.extend(merged_contract.get("candidate_urls") or [])
    if merged_contract.get("primary_url"):
        source_urls.append(merged_contract.get("primary_url"))
    deduped_urls = []
    seen_urls = set()
    for url in source_urls:
        if not isinstance(url, str) or not url.strip():
            continue
        value = url.strip()
        if value in seen_urls:
            continue
        seen_urls.add(value)
        deduped_urls.append(value)
        if len(deduped_urls) >= 8:
            break
    enriched["source_url_candidates"] = deduped_urls
    enriched["data_source_type"] = enriched.get("data_source_type") or "web_scrape"
    enriched["source_access_mode"] = enriched.get("source_access_mode") or "scraping"
    requirements = dict(enriched.get("verification_requirements") or {})
    requirements["requires_external_data_verification"] = True
    requirements["source_probe_required"] = True
    requirements["parser_smoke_test_required"] = True
    try:
        requirements["minimum_sample_days"] = max(1, int(requirements.get("minimum_sample_days") or 1))
    except (TypeError, ValueError):
        requirements["minimum_sample_days"] = 1
    requirements["cache_persistence_required"] = True
    enriched["verification_requirements"] = requirements
    return enriched


def _enrich_build_spec_with_selected_source(
    build_spec: Dict[str, Any],
    selected_source: Optional[Dict[str, Any]],
    *,
    runtime_web_data_request: bool = False,
    api_request: bool = False,
) -> Dict[str, Any]:
    normalized = dict(build_spec) if isinstance(build_spec, dict) else {}
    if not isinstance(selected_source, dict):
        return normalize_runtime_build_spec(normalized)

    final_url = ""
    for key in ("final_url", "url"):
        value = selected_source.get(key)
        if isinstance(value, str) and value.strip():
            final_url = value.strip()
            break

    external_sources = [
        item for item in normalized.get("external_sources", [])
        if isinstance(item, dict)
    ]
    if final_url:
        already_present = any(
            isinstance(item.get("url"), str) and item.get("url", "").strip() == final_url
            for item in external_sources
        )
        if not already_present:
            inferred_source_type = "official_page"
            source_type_value = (selected_source.get("source_type") or "").strip().lower()
            if source_type_value in {"api_reference", "developer_portal", "official_docs"}:
                inferred_source_type = "public_api"
            elif source_type_value in {"search_result"}:
                inferred_source_type = "search_result"
            external_sources.append(
                {
                    "title": (selected_source.get("title") or "외부 데이터 출처").strip() if isinstance(selected_source.get("title"), str) else "외부 데이터 출처",
                    "url": final_url,
                    "source_type": inferred_source_type,
                }
            )
    normalized["external_sources"] = external_sources[:5]

    source_candidates: List[str] = []
    for value in normalized.get("source_url_candidates") or []:
        if isinstance(value, str) and value.strip():
            source_candidates.append(value.strip())
    if final_url:
        source_candidates.append(final_url)
    page_result = selected_source.get("page_result") if isinstance(selected_source.get("page_result"), dict) else {}
    for value in [page_result.get("final_url"), page_result.get("url")]:
        if isinstance(value, str) and value.strip():
            source_candidates.append(value.strip())
    normalized["source_url_candidates"] = list(dict.fromkeys(source_candidates))[:8]

    selected_kind = (selected_source.get("source_kind") or "").strip().lower()
    source_type_value = (selected_source.get("source_type") or "").strip().lower()
    if runtime_web_data_request:
        normalized["data_strategy"] = "runtime_web_fetch"
        normalized["data_source_type"] = "web_scrape"
        normalized["source_access_mode"] = "scraping"
    elif api_request or selected_kind in {"api_fetch", "openapi_parse"} or source_type_value in {"api_reference", "developer_portal", "official_docs"}:
        normalized["data_strategy"] = "runtime_api_fetch"
        normalized["data_source_type"] = "api"
        normalized["source_access_mode"] = "api"

    requirements = dict(normalized.get("verification_requirements") or {})
    if normalized.get("source_url_candidates"):
        requirements["requires_external_data_verification"] = True
        requirements["source_probe_required"] = True
        requirements["cache_persistence_required"] = True
        if normalized.get("data_source_type") == "web_scrape":
            requirements["migration_notice_check"] = True
            requirements["parser_smoke_test_required"] = True
    normalized["verification_requirements"] = requirements
    return normalize_runtime_build_spec(normalized)


def _build_api_source_candidate(
    search_item: Dict[str, Any],
    page_result: Dict[str, Any],
    quality: Dict[str, Any],
    source_kind: str,
) -> Dict[str, Any]:
    return {
        "title": (page_result.get("title") or search_item.get("title") or "").strip(),
        "url": (search_item.get("url") or page_result.get("final_url") or "").strip(),
        "final_url": (page_result.get("final_url") or search_item.get("url") or "").strip(),
        "snippet": (search_item.get("snippet") or "")[:400],
        "text_content": (page_result.get("text_content") or "")[:4000],
        "source_type": (search_item.get("source_type") or ("api_reference" if source_kind != "generic_fetch" else "generic_web")),
        "confidence": float(search_item.get("confidence") or 0.0),
        "source_kind": source_kind,
        "quality_passed": bool(quality.get("research_quality_passed")),
        "quality_reason": quality.get("research_quality_reason") or "",
        "used_external_sources": quality.get("used_external_sources", []),
        "detected_api_name": page_result.get("detected_api_name") or "",
        "detected_base_url": page_result.get("detected_base_url") or "",
        "auth_hints": page_result.get("auth_hints") or [],
        "endpoint_hints": page_result.get("endpoint_hints") or [],
        "servers": page_result.get("servers") or [],
        "auth_schemes": page_result.get("auth_schemes") or [],
        "endpoints": page_result.get("endpoints") or [],
        "search_result": {
            "title": search_item.get("title") or page_result.get("title") or "",
            "url": search_item.get("url") or page_result.get("final_url") or "",
            "snippet": search_item.get("snippet") or "",
            "source_type": search_item.get("source_type"),
            "confidence": search_item.get("confidence"),
        },
        "page_result": page_result,
    }


def _compact_research_source(source: Optional[Dict[str, Any]]) -> Dict[str, Any]:
    if not isinstance(source, dict):
        return {}
    page_result = source.get("page_result") if isinstance(source.get("page_result"), dict) else {}
    web_data_analysis = source.get("web_data_analysis") if isinstance(source.get("web_data_analysis"), dict) else {}
    return {
        "title": source.get("title") or page_result.get("title") or "",
        "url": source.get("url") or page_result.get("url") or "",
        "final_url": source.get("final_url") or page_result.get("final_url") or "",
        "source_type": source.get("source_type") or "",
        "source_kind": source.get("source_kind") or "",
        "detected_api_name": source.get("detected_api_name") or page_result.get("detected_api_name") or "",
        "detected_base_url": source.get("detected_base_url") or page_result.get("detected_base_url") or "",
        "servers": source.get("servers") or page_result.get("servers") or [],
        "auth_schemes": source.get("auth_schemes") or page_result.get("auth_schemes") or [],
        "auth_hints": source.get("auth_hints") or page_result.get("auth_hints") or [],
        "endpoint_hints": source.get("endpoint_hints") or page_result.get("endpoint_hints") or [],
        "endpoints": source.get("endpoints") or page_result.get("endpoints") or [],
        "web_data_contract": web_data_analysis.get("parser_contract") or {},
        "sample_records": web_data_analysis.get("sample_records") or [],
        "text_excerpt": (source.get("text_content") or page_result.get("text_content") or "")[:1800],
    }


def _compact_research_context(
    *,
    api_source_strategy: Dict[str, Any],
    selected_source_payload: Optional[Dict[str, Any]],
    supporting_sources: List[Dict[str, Any]],
    fetched_pages: List[Dict[str, Any]],
    web_data_analyses: List[Dict[str, Any]],
    http_probe_result: Optional[Dict[str, Any]],
    quality: Dict[str, Any],
) -> Dict[str, Any]:
    return {
        "api_source_strategy": api_source_strategy or {},
        "selected_source": _compact_research_source(selected_source_payload),
        "supporting_sources": [
            _compact_research_source(item)
            for item in supporting_sources[:5]
            if isinstance(item, dict)
        ],
        "openapi_references": [
            {
                "title": page.get("title") or "",
                "final_url": page.get("final_url") or page.get("url") or "",
                "servers": page.get("servers") or [],
                "auth_schemes": page.get("auth_schemes") or [],
                "endpoints": page.get("endpoints") or [],
            }
            for page in fetched_pages[:3]
            if isinstance(page, dict) and (page.get("servers") or page.get("endpoints") or page.get("auth_schemes"))
        ],
        "web_data_analyses": [
            {
                "status": item.get("status"),
                "source_kind": item.get("source_kind"),
                "final_url": item.get("final_url") or item.get("url") or "",
                "parser_contract": item.get("parser_contract") or {},
                "sample_records": item.get("sample_records") or [],
                "confidence": item.get("confidence"),
                "failure_reason": item.get("failure_reason") or "",
            }
            for item in web_data_analyses[:4]
            if isinstance(item, dict)
        ],
        "http_probe": http_probe_result or {},
        "quality": {
            "research_quality_passed": quality.get("research_quality_passed"),
            "research_quality_reason": quality.get("research_quality_reason") or "",
            "used_external_sources": quality.get("used_external_sources") or [],
        },
    }


def _build_safe_probe_target(selected_source: Optional[Dict[str, Any]]) -> Optional[Dict[str, Any]]:
    source = selected_source or {}
    page_result = source.get("page_result") if isinstance(source.get("page_result"), dict) else {}
    base_url = ""
    for candidate in list(page_result.get("servers") or []) + [
        page_result.get("detected_base_url"),
        source.get("detected_base_url"),
    ]:
        if isinstance(candidate, str) and candidate.strip():
            base_url = candidate.strip().rstrip("/")
            break
    endpoint_path = ""
    endpoints = page_result.get("endpoints") or []
    for endpoint in endpoints:
        if not isinstance(endpoint, dict):
            continue
        methods = [str(method).upper() for method in (endpoint.get("methods") or [])]
        path = endpoint.get("path")
        if "GET" in methods and isinstance(path, str) and path.strip() and "{" not in path:
            endpoint_path = path.strip()
            break
    if not endpoint_path:
        for hint in page_result.get("endpoint_hints") or []:
            if not isinstance(hint, str):
                continue
            match = re.match(r"GET\s+(/[^\\s]+)", hint.strip(), re.I)
            if match and "{" not in match.group(1):
                endpoint_path = match.group(1)
                break
    if not base_url or not endpoint_path:
        return None
    probe_url = f"{base_url}{endpoint_path if endpoint_path.startswith('/') else '/' + endpoint_path}"
    return {"method": "GET", "url": probe_url}


def _source_urls_for_auth_check(source: Dict[str, Any]) -> List[str]:
    page_result = source.get("page_result") if isinstance(source.get("page_result"), dict) else {}
    urls: List[str] = []
    for container in [source, page_result]:
        for key in ["final_url", "url", "detected_base_url", "base_url"]:
            value = container.get(key)
            if isinstance(value, str) and value.strip():
                urls.append(value.strip())
        for value in container.get("servers") or []:
            if isinstance(value, str) and value.strip():
                urls.append(value.strip())
    return list(dict.fromkeys(urls))


def _looks_like_key_required_api_url(url: str) -> bool:
    text = (url or "").lower()
    if not text:
        return False
    try:
        parsed = urlparse(url)
        auth_like_params = {
            "api_key",
            "apikey",
            "app_key",
            "appkey",
            "app_id",
            "appid",
            "access_key",
            "access_token",
            "auth_token",
            "token",
            "subscription-key",
            "subscription_key",
            "client_secret",
        }
        query_keys = {key.lower() for key, _ in parse_qsl(parsed.query, keep_blank_values=True)}
        if query_keys & auth_like_params:
            return True
        if "key" in query_keys and any(marker in text for marker in ["api", "developer", "maps", "weather"]):
            return True
    except Exception:
        pass
    if any(marker in text for marker in [
        "weather.googleapis.com",
        "developers.google.com/maps/documentation/weather",
        "maps.googleapis.com",
        "rapidapi.com",
        "api-football.com",
        "newsapi.org",
        "developer.spotify.com",
        "developer.twitter.com",
        "developer.x.com",
    ]):
        return True
    if "open-meteo.com" in text:
        return False
    return any(marker in text for marker in [
        "api.openweathermap.org",
        "openweathermap.org/api",
        "openweathermap.org/current",
        "meteosource.com",
        "/authentication",
        "/auth/",
        "/api-key",
        "/api_key",
    ])


def _source_has_auth_requirement(source: Optional[Dict[str, Any]]) -> bool:
    if not isinstance(source, dict):
        return False
    page_result = source.get("page_result") if isinstance(source.get("page_result"), dict) else {}
    auth_schemes = source.get("auth_schemes") or page_result.get("auth_schemes") or []
    auth_hints = source.get("auth_hints") or page_result.get("auth_hints") or []
    source_urls = _source_urls_for_auth_check(source)
    key_required_by_url = any(_looks_like_key_required_api_url(url) for url in source_urls)
    text = " ".join(
        [
            json.dumps(source_urls, ensure_ascii=False),
            str(source.get("title") or ""),
            str(page_result.get("title") or ""),
            json.dumps(auth_schemes, ensure_ascii=False),
            json.dumps(auth_hints, ensure_ascii=False),
            str(source.get("snippet") or ""),
            str(page_result.get("text_content") or "")[:1200],
        ]
    ).lower()
    no_auth_markers = [
        "no api key required",
        "no api-key required",
        "no authentication required",
        "without api key",
        "without an api key",
        "does not require an api key",
        "api key is not required",
        "authentication is not required",
        "no auth",
        "인증이 필요하지",
        "인증 없이",
        "키가 필요하지",
        "api 키가 필요하지",
    ]
    concrete_auth_schemes = [
        scheme for scheme in auth_schemes
        if str(scheme or "").strip().lower() not in {"none", "noauth", "no_auth", "public"}
    ]
    if key_required_by_url:
        return True
    if any(marker in text for marker in no_auth_markers) and not concrete_auth_schemes:
        return False
    if auth_schemes or auth_hints:
        return True
    return any(marker in text for marker in [
        "requires an api key",
        "require an api key",
        "requires api key",
        "api key required",
        "get an api key",
        "your api key",
        "api key",
        "apikey",
        "api_key",
        "app_id",
        "appid",
        "subscription key",
        "subscription-key",
        "x-api-key",
        "x-rapidapi-key",
        "key=",
        "x-goog-api-key",
        "authorization",
        "bearer",
        "oauth",
        "credential",
        "credentials",
        "billing",
        "quota project",
        "maps platform",
        "client secret",
        "access token",
        "consumer key",
        "consumer secret",
        "인증",
        "api 키 필요",
        "api 키가 필요",
        "키를 발급",
        "키 발급",
        "토큰",
    ])


def _merge_search_results(*search_results: Dict[str, Any]) -> Dict[str, Any]:
    merged_results: List[Dict[str, Any]] = []
    seen_urls: set[str] = set()
    errors: List[str] = []
    query = ""
    for result in search_results:
        if not isinstance(result, dict):
            continue
        query = query or str(result.get("query") or "")
        if result.get("error"):
            errors.append(str(result.get("error")))
        for item in result.get("results") or []:
            if not isinstance(item, dict):
                continue
            url = str(item.get("url") or "").strip()
            dedupe_key = url or json.dumps(item, sort_keys=True, ensure_ascii=False)
            if dedupe_key in seen_urls:
                continue
            seen_urls.add(dedupe_key)
            merged_results.append(item)
    return {
        "status": "success" if merged_results else "failed",
        "query": query,
        "results": merged_results,
        "error": "; ".join(errors[:3]) if errors and not merged_results else "",
    }


def _candidate_quality_score(candidate: Dict[str, Any]) -> float:
    quality = candidate.get("quality") if isinstance(candidate.get("quality"), dict) else {}
    score = 0.0
    if candidate.get("quality_passed") or quality.get("research_quality_passed"):
        score += 100.0
    confidence = candidate.get("confidence")
    if isinstance(confidence, (int, float)):
        score += float(confidence)
    search_result = candidate.get("search_result") if isinstance(candidate.get("search_result"), dict) else {}
    result_confidence = search_result.get("confidence")
    if isinstance(result_confidence, (int, float)):
        score += float(result_confidence)
    if candidate.get("source_type") in {"official_docs", "api_reference", "developer_portal"}:
        score += 10.0
    if candidate.get("source_kind") in {"openapi_parse", "api_fetch"}:
        score += 5.0
    return score


def _best_public_no_key_api_candidate(candidates: List[Dict[str, Any]]) -> Optional[Dict[str, Any]]:
    eligible = [
        candidate for candidate in candidates
        if isinstance(candidate, dict)
        and not _source_has_auth_requirement(candidate)
        and bool(
            candidate.get("quality_passed")
            or (candidate.get("quality") or {}).get("research_quality_passed")
        )
    ]
    if not eligible:
        return None
    return sorted(eligible, key=_candidate_quality_score, reverse=True)[0]


def _looks_like_weather_no_key_request(*texts: str) -> bool:
    combined = " ".join(str(text or "") for text in texts).lower()
    if not any(marker in combined for marker in ["weather", "날씨", "기온", "온도", "wind", "풍속"]):
        return False
    return any(marker in combined for marker in [
        "free",
        "public",
        "no key",
        "no-key",
        "no api key",
        "without api key",
        "무료",
        "공개",
        "무키",
        "키 없이",
        "api 키 없이",
    ])


def _build_open_meteo_weather_candidate(research_query: str) -> Dict[str, Any]:
    docs_url = "https://open-meteo.com/en/docs"
    base_url = "https://api.open-meteo.com/v1"
    sample_url = (
        "https://api.open-meteo.com/v1/forecast"
        "?latitude=37.5665&longitude=126.9780"
        "&current=temperature_2m,wind_speed_10m"
        "&timezone=Asia%2FSeoul"
    )
    search_item = {
        "title": "Open-Meteo Weather Forecast API official documentation",
        "url": docs_url,
        "snippet": "Open-Meteo weather forecast API. No API key required for non-commercial use.",
        "source_type": "official_docs",
        "confidence": 0.95,
    }
    page_result = {
        "status": "success",
        "title": "Open-Meteo Weather Forecast API official documentation",
        "url": docs_url,
        "final_url": docs_url,
        "detected_api_name": "Open-Meteo Weather Forecast API",
        "detected_base_url": base_url,
        "servers": [base_url],
        "auth_schemes": [],
        "auth_hints": [],
        "endpoint_hints": ["GET /forecast"],
        "endpoints": [
            {
                "path": "/forecast",
                "methods": ["GET"],
                "required_params": ["latitude", "longitude"],
                "optional_params": ["current", "timezone"],
                "response_format": "json",
                "purpose": "현재 날씨와 예보 데이터를 조회합니다.",
            }
        ],
        "text_content": (
            "Open-Meteo Weather Forecast API official documentation. "
            "No API key required. Supports latitude, longitude, current weather variables, and timezone. "
            f"Example: {sample_url}. Query intent: {research_query}"
        ),
    }
    quality = {
        "research_quality_passed": True,
        "research_quality_reason": "deterministic_public_no_key_weather_candidate",
        "used_external_sources": [docs_url],
    }
    candidate = _build_api_source_candidate(search_item, page_result, quality, "api_fetch")
    candidate["selection_reason"] = "무료/무키 날씨 API 요청에 대해 Open-Meteo 공식 API 후보를 보강했습니다."
    candidate["integration_notes"] = {
        "auth": "public_no_key",
        "base_url": base_url,
        "official_docs_url": docs_url,
        "sample_url": sample_url,
    }
    return candidate


def _append_deterministic_public_no_key_candidates(
    candidates: List[Dict[str, Any]],
    research_query: str,
    api_source_strategy: Optional[Dict[str, Any]],
) -> None:
    strategy = api_source_strategy or {}
    if not strategy.get("prefer_public_no_key"):
        return
    if not _looks_like_weather_no_key_request(
        research_query,
        strategy.get("public_api_search_query") or "",
        strategy.get("fallback_api_search_query") or "",
        strategy.get("reason") or "",
    ):
        return
    if any("open-meteo.com" in " ".join(_source_urls_for_auth_check(candidate)).lower() for candidate in candidates):
        return
    candidates.append(_build_open_meteo_weather_candidate(research_query))


PUBLIC_NO_KEY_ENDPOINT_SCHEMA = {
    "type": "object",
    "properties": {
        "path": {"type": "string"},
        "methods": {"type": "array", "items": {"type": "string"}},
        "required_params": {"type": "array", "items": {"type": "string"}},
        "optional_params": {"type": "array", "items": {"type": "string"}},
        "response_format": {"type": "string"},
        "purpose": {"type": "string"},
    },
    "required": [
        "path",
        "methods",
        "required_params",
        "optional_params",
        "response_format",
        "purpose",
    ],
    "additionalProperties": False,
}


PUBLIC_NO_KEY_API_CANDIDATE_TOOL_SCHEMAS = [
    {
        "type": "function",
        "function": {
            "name": "propose_public_no_key_api_candidate",
            "description": "검색 실패 또는 약한 검색 결과 상황에서 모델 지식으로 공식 무키 공개 API 후보를 구조화합니다.",
            "parameters": {
                "type": "object",
                "properties": {
                    "can_use_public_no_key_candidate": {"type": "boolean"},
                    "api_name": {"type": "string"},
                    "official_docs_url": {"type": "string"},
                    "base_url": {"type": "string"},
                    "no_auth_evidence": {"type": "string"},
                    "endpoints": {
                        "type": "array",
                        "items": PUBLIC_NO_KEY_ENDPOINT_SCHEMA,
                    },
                    "implementation_notes": {"type": "string"},
                    "confidence": {"type": "number"},
                    "reason": {"type": "string"},
                },
                "required": [
                    "can_use_public_no_key_candidate",
                    "api_name",
                    "official_docs_url",
                    "base_url",
                    "no_auth_evidence",
                    "endpoints",
                    "implementation_notes",
                    "confidence",
                    "reason",
                ],
                "additionalProperties": False,
            },
        },
    }
]


def validate_public_no_key_api_candidate_payload(payload: Any) -> tuple[bool, Optional[str]]:
    if not isinstance(payload, dict):
        return False, f"public API candidate payload must be an object | raw_payload={repr(payload)}"
    if not isinstance(payload.get("can_use_public_no_key_candidate"), bool):
        return False, f"can_use_public_no_key_candidate must be boolean | raw_payload={json.dumps(payload, ensure_ascii=False)}"
    if not payload.get("can_use_public_no_key_candidate"):
        return True, None

    for key in ["api_name", "official_docs_url", "base_url", "no_auth_evidence", "implementation_notes", "reason"]:
        if not isinstance(payload.get(key), str) or not payload.get(key).strip():
            return False, f"{key} must be a non-empty string when candidate is usable | raw_payload={json.dumps(payload, ensure_ascii=False)}"
    if not looks_like_url(payload.get("official_docs_url") or ""):
        return False, f"official_docs_url must be a URL | raw_payload={json.dumps(payload, ensure_ascii=False)}"
    if not looks_like_url(payload.get("base_url") or ""):
        return False, f"base_url must be a URL | raw_payload={json.dumps(payload, ensure_ascii=False)}"
    confidence = payload.get("confidence")
    if not isinstance(confidence, (int, float)) or float(confidence) < 0.65:
        return False, f"confidence must be >= 0.65 | raw_payload={json.dumps(payload, ensure_ascii=False)}"
    endpoints = payload.get("endpoints")
    if not isinstance(endpoints, list) or not endpoints:
        return False, f"endpoints must be a non-empty list | raw_payload={json.dumps(payload, ensure_ascii=False)}"
    for endpoint in endpoints:
        if not isinstance(endpoint, dict):
            return False, f"endpoint must be object | raw_payload={json.dumps(payload, ensure_ascii=False)}"
        if not isinstance(endpoint.get("path"), str) or not endpoint.get("path", "").startswith("/"):
            return False, f"endpoint.path must start with / | raw_payload={json.dumps(payload, ensure_ascii=False)}"
        methods = endpoint.get("methods")
        if not isinstance(methods, list) or not methods or not any(str(method).upper() == "GET" for method in methods):
            return False, f"at least one GET endpoint is required | raw_payload={json.dumps(payload, ensure_ascii=False)}"
        for key in ["required_params", "optional_params"]:
            if not isinstance(endpoint.get(key), list):
                return False, f"endpoint.{key} must be list | raw_payload={json.dumps(payload, ensure_ascii=False)}"
    return True, None


def _llm_payload_to_public_no_key_candidate(payload: Dict[str, Any]) -> Optional[Dict[str, Any]]:
    if not payload.get("can_use_public_no_key_candidate"):
        return None
    docs_url = (payload.get("official_docs_url") or "").strip()
    base_url = (payload.get("base_url") or "").strip().rstrip("/")
    api_name = (payload.get("api_name") or "").strip()
    endpoints = payload.get("endpoints") if isinstance(payload.get("endpoints"), list) else []
    endpoint_hints = []
    normalized_endpoints = []
    for endpoint in endpoints:
        if not isinstance(endpoint, dict):
            continue
        methods = [str(method).upper() for method in (endpoint.get("methods") or [])]
        path = str(endpoint.get("path") or "").strip()
        if not path:
            continue
        endpoint_hints.append(f"{','.join(methods or ['GET'])} {path}")
        normalized_endpoints.append(
            {
                "path": path,
                "methods": methods or ["GET"],
                "required_params": endpoint.get("required_params") or [],
                "optional_params": endpoint.get("optional_params") or [],
                "response_format": endpoint.get("response_format") or "",
                "purpose": endpoint.get("purpose") or "",
            }
        )

    search_item = {
        "title": f"{api_name} official API documentation",
        "url": docs_url,
        "snippet": payload.get("implementation_notes") or payload.get("reason") or "",
        "source_type": "official_docs",
        "confidence": float(payload.get("confidence") or 0.0),
    }
    page_result = {
        "status": "success",
        "title": f"{api_name} official API documentation",
        "url": docs_url,
        "final_url": docs_url,
        "detected_api_name": api_name,
        "detected_base_url": base_url,
        "servers": [base_url],
        "auth_schemes": [],
        "auth_hints": [],
        "endpoint_hints": endpoint_hints,
        "endpoints": normalized_endpoints,
        "text_content": " ".join(
            [
                f"{api_name} official API documentation.",
                "Public no-key API candidate selected by LLM.",
                f"No-auth evidence: {payload.get('no_auth_evidence') or ''}",
                f"Implementation notes: {payload.get('implementation_notes') or ''}",
            ]
        ),
    }
    quality = {
        "research_quality_passed": True,
        "research_quality_reason": "llm_public_no_key_candidate",
        "used_external_sources": [docs_url],
    }
    candidate = _build_api_source_candidate(search_item, page_result, quality, "llm_public_api_candidate")
    candidate["selection_reason"] = payload.get("reason") or "LLM selected a public no-key API candidate."
    candidate["integration_notes"] = {
        "auth": "public_no_key",
        "base_url": base_url,
        "official_docs_url": docs_url,
        "implementation_notes": payload.get("implementation_notes") or "",
        "no_auth_evidence": payload.get("no_auth_evidence") or "",
    }
    return candidate


def propose_public_no_key_api_candidate_with_llm(
    *,
    task_id: str,
    raw_user_message: str,
    user_prompt: str,
    research_query: str,
    api_source_strategy: Dict[str, Any],
    search_error: str,
) -> tuple[Optional[Dict[str, Any]], Dict[str, int]]:
    if not api_source_strategy.get("prefer_public_no_key"):
        return None, {}

    task = get_task(task_id) or {}
    result = call_agent_with_tools(
        "당신은 앱 생성 서버의 API 출처 판별 에이전트입니다. "
        "서버의 웹 검색이 실패했거나 약한 결과만 있을 때, 특정 도메인별 하드코딩 없이 모델 지식으로 공식 무키 공개 API 후보가 있는지 판단합니다. "
        "후보를 제안하려면 공식 문서 URL, HTTPS base URL, 인증이 필요 없다는 근거, GET 엔드포인트와 필수 파라미터를 구조화할 수 있어야 합니다. "
        "확실하지 않거나 인증/키/OAuth/서버 secret이 필요하면 can_use_public_no_key_candidate=false로 답하세요. "
        "사용자 요청을 충족하지 못하는 API를 억지로 제안하지 마세요.",
        "공식 무키 공개 API 후보를 사용할 수 있는지 판단하세요.",
        context={
            "raw_user_message": raw_user_message,
            "decision_prompt": user_prompt,
            "research_query": research_query,
            "api_source_strategy": api_source_strategy,
            "search_error": search_error,
            "conversation_state": get_conversation_state(task),
            "recent_messages": get_recent_conversation_messages(task_id, limit=12, include_task_logs=False),
            "required_decision": {
                "use_candidate_only_if": [
                    "official_docs_url_known",
                    "https_base_url_known",
                    "no_auth_evidence_known",
                    "at_least_one_get_endpoint_known",
                    "candidate_satisfies_user_goal",
                ],
                "otherwise": "return can_use_public_no_key_candidate=false",
            },
        },
        trace={
            "task_id": task_id,
            "flow_type": "generate_decision",
            "agent_name": "Public_No_Key_API_Candidate",
            "stage": "api_source_fallback",
        },
        tools=PUBLIC_NO_KEY_API_CANDIDATE_TOOL_SCHEMAS,
        validator=validate_public_no_key_api_candidate_payload,
        parsed_output_builder=lambda tool_name, tool_arguments: tool_arguments,
    )
    parsed = result.get("parsed_output")
    usage = result.get("usage") or {}
    candidate = _llm_payload_to_public_no_key_candidate(parsed) if isinstance(parsed, dict) else None
    return candidate, usage


API_SOURCE_STRATEGY_TOOL_SCHEMAS = [
    {
        "type": "function",
        "function": {
            "name": "choose_api_source_strategy",
            "description": "사용자 요구에 맞는 API 문서 탐색 전략을 정합니다. 가능하면 공식 무키 공개 API를 먼저 찾습니다.",
            "parameters": {
                "type": "object",
                "properties": {
                    "prefer_public_no_key": {"type": "boolean"},
                    "public_api_search_query": {"type": "string"},
                    "fallback_api_search_query": {"type": "string"},
                    "reason": {"type": "string"},
                },
                "required": [
                    "prefer_public_no_key",
                    "public_api_search_query",
                    "fallback_api_search_query",
                    "reason",
                ],
                "additionalProperties": False,
            },
        },
    }
]


AUTH_BUILD_SPEC_UPDATES_SCHEMA = {
    "type": "object",
    "properties": {
        "api_key_handling": {"type": "string"},
        "api_auth_strategy": {"type": "string"},
        "requires_api_key_input_screen": {"type": "boolean"},
        "secret_storage_policy": {"type": "string"},
        "api_key_error_handling_required": {"type": "boolean"},
        "prefer_public_no_key_api": {"type": "boolean"},
        "required_permissions_note": {"type": "string"},
        "implementation_note": {"type": "string"},
    },
    "additionalProperties": False,
}


def validate_api_source_strategy_payload(payload: Any) -> tuple[bool, Optional[str]]:
    if not isinstance(payload, dict):
        return False, f"API source strategy payload must be an object | raw_payload={repr(payload)}"
    if not isinstance(payload.get("prefer_public_no_key"), bool):
        return False, f"prefer_public_no_key must be boolean | raw_payload={json.dumps(payload, ensure_ascii=False)}"
    for key in ["public_api_search_query", "fallback_api_search_query", "reason"]:
        if not isinstance(payload.get(key), str):
            return False, f"{key} must be string | raw_payload={json.dumps(payload, ensure_ascii=False)}"
    return True, None


def choose_api_source_strategy_with_llm(
    *,
    task_id: str,
    raw_user_message: str,
    user_prompt: str,
    research_query: str,
    tool_args: Dict[str, Any],
) -> tuple[Dict[str, Any], Dict[str, int]]:
    task = get_task(task_id) or {}
    result = call_agent_with_tools(
        "당신은 앱 생성 서버의 API 출처 탐색 전략가입니다. "
        "사용자 요구가 외부 API나 최신 데이터를 필요로 하면 공식 문서와 안정적인 공개 API를 우선 탐색하세요. "
        "가능하면 API key, OAuth, 서버 secret 없이 사용할 수 있는 공식 무키 공개 API를 먼저 찾으세요. "
        "웹사이트 크롤링은 명시적 공개 API가 없거나 요구를 충족하지 못할 때의 fallback입니다. "
        "유료/키 필요 API만 먼저 고정하지 말고, 무키 공개 API 후보를 확인할 수 있는 검색 질의를 생성하세요. "
        "단, 결제, 계정, 관리자 권한, 민감 데이터처럼 인증이 본질인 기능은 무키 API를 억지로 선택하지 마세요.",
        "API 출처 검색 전략을 결정하세요.",
        context={
            "raw_user_message": raw_user_message,
            "decision_prompt": user_prompt,
            "research_query": research_query,
            "tool_args": tool_args,
            "conversation_state": get_conversation_state(task),
            "recent_messages": get_recent_conversation_messages(task_id, limit=12, include_task_logs=False),
            "policy": {
                "preferred": "official public no-key API when it can satisfy the app goal",
                "fallback": "official API with user-provided in-app key, then structured public web data if appropriate",
            },
        },
        trace={
            "task_id": task_id,
            "flow_type": "generate_decision",
            "agent_name": "API_Source_Strategy",
            "stage": "api_source_strategy",
        },
        tools=API_SOURCE_STRATEGY_TOOL_SCHEMAS,
        validator=validate_api_source_strategy_payload,
        parsed_output_builder=lambda tool_name, tool_arguments: tool_arguments,
    )
    parsed = result.get("parsed_output")
    usage = result.get("usage") or {}
    return parsed if isinstance(parsed, dict) else {}, usage


AUTH_RESOLUTION_TOOL_SCHEMAS = [
    {
        "type": "function",
        "function": {
            "name": "assess_api_auth_resolution",
            "description": "대화 문맥과 빌드 명세를 바탕으로 API 인증 처리 방식이 빌드 가능한 수준으로 확정됐는지 판단합니다.",
            "parameters": {
                "type": "object",
                "properties": {
                    "api_auth_resolved": {"type": "boolean"},
                    "api_key_handling": {
                        "type": "string",
                        "enum": [
                            "user_provided_in_app",
                            "server_proxy_required",
                            "public_no_key",
                            "unknown",
                        ],
                    },
                    "reason": {"type": "string"},
                    "build_spec_updates": AUTH_BUILD_SPEC_UPDATES_SCHEMA,
                    "clarification_question": {"type": "string"},
                },
                "required": [
                    "api_auth_resolved",
                    "api_key_handling",
                    "reason",
                    "build_spec_updates",
                    "clarification_question",
                ],
                "additionalProperties": False,
            },
        },
    }
]


def validate_auth_resolution_payload(payload: Any) -> tuple[bool, Optional[str]]:
    if not isinstance(payload, dict):
        return False, f"Auth resolution payload must be an object | raw_payload={repr(payload)}"
    if not isinstance(payload.get("api_auth_resolved"), bool):
        return False, f"api_auth_resolved must be boolean | raw_payload={json.dumps(payload, ensure_ascii=False)}"
    if payload.get("api_key_handling") not in {
        "user_provided_in_app",
        "server_proxy_required",
        "public_no_key",
        "unknown",
    }:
        return False, f"api_key_handling invalid | raw_payload={json.dumps(payload, ensure_ascii=False)}"
    if not isinstance(payload.get("reason"), str):
        return False, f"reason must be string | raw_payload={json.dumps(payload, ensure_ascii=False)}"
    if not isinstance(payload.get("build_spec_updates"), dict):
        return False, f"build_spec_updates must be object | raw_payload={json.dumps(payload, ensure_ascii=False)}"
    if not isinstance(payload.get("clarification_question"), str):
        return False, f"clarification_question must be string | raw_payload={json.dumps(payload, ensure_ascii=False)}"
    return True, None


def assess_api_auth_resolution_with_llm(
    *,
    task_id: str,
    raw_user_message: str,
    user_prompt: str,
    tool_args: Dict[str, Any],
    selected_source_payload: Optional[Dict[str, Any]],
    quality: Dict[str, Any],
) -> tuple[Dict[str, Any], Dict[str, int]]:
    task = get_task(task_id) or {}
    result = call_agent_with_tools(
        "당신은 앱 생성 서버의 API 인증 정책 라우터입니다. "
        "사용자 대화, 직전 확인 질문, 빌드 명세, API 문서 근거를 보고 인증 처리 방식이 빌드 가능한 수준으로 확정됐는지 판단하세요. "
        "실제 비밀 키를 서버 로그나 앱 코드에 넣는 방식은 확정된 것으로 보지 마세요. "
        "일반적인 API key, appid, token, bearer token 방식은 모바일 앱에서 사용자가 자신의 키를 입력하고 로컬에 저장하는 안전한 기본 전략으로 처리할 수 있습니다. "
        "사용자가 명시적으로 키를 제공하지 않아도, 서비스가 사용자 개인 키 입력을 허용하는 형태라면 api_auth_resolved=true, api_key_handling=user_provided_in_app로 판단하세요. "
        "공개 무키 API도 api_auth_resolved=true입니다. "
        "OAuth client secret, 서버 전용 secret, 관리자 키, 결제/민감 권한 토큰처럼 모바일 앱에 노출하면 안 되는 credential이 핵심이면 api_auth_resolved=false로 두고 clarification_question에 서버 프록시 필요성을 물으세요. "
        "server_proxy_required는 사용자가 이미 서버 프록시 구현에 동의했거나 기존 서버 프록시가 명세에 있을 때만 resolved로 봅니다. "
        "build_spec_updates에는 선택한 인증 전략을 구현하기 위한 화면/저장/오류 처리 요구사항을 넣으세요.",
        "API 인증 처리 방식 확정 여부를 판단하세요.",
        context={
            "raw_user_message": raw_user_message,
            "decision_prompt": user_prompt,
            "tool_args": tool_args,
            "conversation_state": get_conversation_state(task),
            "recent_messages": get_recent_conversation_messages(task_id, limit=12, include_task_logs=False),
            "selected_source": selected_source_payload or {},
            "research_quality": quality or {},
            "default_safe_policy": {
                "ordinary_api_key": "Proceed with an in-app user API key input/settings screen; do not hardcode secrets.",
                "public_no_key": "Proceed without asking.",
                "oauth_or_server_secret": "Ask only when a confidential server-held credential or proxy is required.",
            },
        },
        trace={
            "task_id": task_id,
            "flow_type": "generate_decision",
            "agent_name": "API_Auth_Resolution",
            "stage": "api_auth_resolution",
        },
        tools=AUTH_RESOLUTION_TOOL_SCHEMAS,
        validator=validate_auth_resolution_payload,
        parsed_output_builder=lambda tool_name, tool_arguments: tool_arguments,
    )
    parsed = result.get("parsed_output")
    usage = result.get("usage") or {}
    return parsed if isinstance(parsed, dict) else {}, usage


class TaskStatus:
    PENDING_DECISION = "Pending Decision"
    CLARIFICATION_NEEDED = "Clarification Needed"
    PROCESSING = "Processing"
    REVIEWING = "Reviewing"
    REPAIRING = "Repairing"
    SUCCESS = "Success"
    FAILED = "Failed"
    ERROR = "Error"
    REJECTED = "Rejected"


ALLOWED_TASK_STATUSES = {
    TaskStatus.PENDING_DECISION,
    TaskStatus.CLARIFICATION_NEEDED,
    TaskStatus.PROCESSING,
    TaskStatus.REVIEWING,
    TaskStatus.REPAIRING,
    TaskStatus.SUCCESS,
    TaskStatus.FAILED,
    TaskStatus.ERROR,
    TaskStatus.REJECTED,
}

# ------------------------------------------------
# DATABASE INITIALIZATION
# ------------------------------------------------

def get_conn():
    return sqlite3.connect(
        DB_PATH,
        timeout=30,
        check_same_thread=False
    )

def init_db():
    with get_conn() as conn:
        cursor = conn.cursor()
        cursor.execute("""
        CREATE TABLE IF NOT EXISTS tasks(
            task_id TEXT PRIMARY KEY,
            device_id TEXT,
            status TEXT,
            app_name TEXT,
            log TEXT,
            apk_path TEXT,
            apk_url TEXT,
            project_path TEXT,
            device_info TEXT,
            package_name TEXT,
            attempts INTEGER DEFAULT 0,
            conversation_state TEXT,
            created_at DATETIME DEFAULT (DATETIME('now', 'localtime')),
            updated_at DATETIME DEFAULT (DATETIME('now', 'localtime')),
            initial_user_prompt TEXT,
            final_requirement_summary TEXT,
            generated_app_name TEXT,
            final_app_spec TEXT,
            build_success INTEGER,
            build_attempts INTEGER DEFAULT 0,
            active_flow TEXT,
            user_id TEXT,
            phone_number TEXT,
            interview_consent INTEGER DEFAULT 0
        )
        """)
        conn.commit()

        cursor.execute("PRAGMA table_info(tasks)")
        task_columns = {row[1] for row in cursor.fetchall()}
        for column_name, definition in [
            ("device_id", "TEXT"),
            ("conversation_state", "TEXT"),
            ("created_at", "DATETIME"),
            ("updated_at", "DATETIME"),
            ("initial_user_prompt", "TEXT"),
            ("final_requirement_summary", "TEXT"),
            ("generated_app_name", "TEXT"),
            ("final_app_spec", "TEXT"),
            ("build_success", "INTEGER"),
            ("build_attempts", "INTEGER DEFAULT 0"),
            ("active_flow", "TEXT"),
            ("user_id", "TEXT"),
            ("phone_number", "TEXT"),
            ("interview_consent", "INTEGER DEFAULT 0"),
            ("reference_image_analysis", "TEXT"),
            ("reference_image_fingerprint", "TEXT"),
            ("image_reference_summary", "TEXT"),
            ("image_conflict_note", "TEXT"),
            ("grounding_metadata", "TEXT"),
            ("synthesis_grounding_summary", "TEXT"),
            ("research_used", "INTEGER DEFAULT 0"),
            ("api_reference_used", "INTEGER DEFAULT 0"),
            ("openapi_used", "INTEGER DEFAULT 0"),
            ("image_reference_used", "INTEGER DEFAULT 0"),
            ("http_probe_used", "INTEGER DEFAULT 0"),
            ("selected_source_type", "TEXT"),
            ("ui_contract", "TEXT"),
            ("verification_summary", "TEXT"),
            ("verification_report", "TEXT"),
            ("verification_passed", "INTEGER"),
        ]:
            if column_name not in task_columns:
                try:
                    cursor.execute(f"ALTER TABLE tasks ADD COLUMN {column_name} {definition}")
                    conn.commit()
                    task_columns.add(column_name)
                except sqlite3.OperationalError as exc:
                    if "duplicate column name" not in str(exc).lower():
                        raise
                    task_columns.add(column_name)

        cursor.execute("""
        UPDATE tasks
        SET created_at = COALESCE(created_at, DATETIME('now', 'localtime')),
            updated_at = COALESCE(updated_at, DATETIME('now', 'localtime')),
            build_attempts = COALESCE(build_attempts, 0),
            interview_consent = COALESCE(interview_consent, 0)
        """)
        conn.commit()

        cursor.execute("""
        CREATE TABLE IF NOT EXISTS token_logs(
            task_id TEXT,
            agent_name TEXT,
            prompt_tokens INTEGER,
            completion_tokens INTEGER,
            total_tokens INTEGER,
            timestamp DATETIME DEFAULT (DATETIME('now', 'localtime'))
        )
        """)
        conn.commit()

        cursor.execute("""
        CREATE TABLE IF NOT EXISTS decision_logs(
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            task_id TEXT NOT NULL,
            event_type TEXT NOT NULL,
            decision_type TEXT,
            tool_name TEXT,
            tool_args TEXT,
            raw_user_message TEXT,
            structured_summary TEXT,
            timestamp DATETIME DEFAULT (DATETIME('now', 'localtime'))
        )
        """)
        conn.commit()

        cursor.execute("""
        CREATE TABLE IF NOT EXISTS user_query_logs(
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            task_id TEXT,
            device_id TEXT,
            user_id TEXT,
            phone_number TEXT,
            query_type TEXT NOT NULL,
            endpoint TEXT NOT NULL,
            content TEXT NOT NULL,
            created_at DATETIME DEFAULT (DATETIME('now', 'localtime'))
        )
        """)
        conn.commit()

        cursor.execute("""
        CREATE TABLE IF NOT EXISTS conversation_messages(
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            task_id TEXT NOT NULL,
            role TEXT NOT NULL,
            message_type TEXT NOT NULL,
            endpoint TEXT,
            content TEXT NOT NULL,
            payload TEXT,
            created_at DATETIME DEFAULT (DATETIME('now', 'localtime'))
        )
        """)
        conn.commit()

        cursor.execute("""
        CREATE TABLE IF NOT EXISTS workspace_revisions(
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            task_id TEXT NOT NULL,
            revision_no INTEGER NOT NULL,
            flow_type TEXT NOT NULL,
            project_path TEXT NOT NULL,
            parent_project_path TEXT,
            trigger_message TEXT,
            status TEXT,
            created_at DATETIME DEFAULT (DATETIME('now', 'localtime'))
        )
        """)
        conn.commit()

        cursor.execute("PRAGMA table_info(workspace_revisions)")
        workspace_revision_columns = {row[1] for row in cursor.fetchall()}
        for column_name, definition in [
            ("ui_contract", "TEXT"),
        ]:
            if column_name not in workspace_revision_columns:
                try:
                    cursor.execute(f"ALTER TABLE workspace_revisions ADD COLUMN {column_name} {definition}")
                    conn.commit()
                    workspace_revision_columns.add(column_name)
                except sqlite3.OperationalError as exc:
                    if "duplicate column name" not in str(exc).lower():
                        raise
                    workspace_revision_columns.add(column_name)

        cursor.execute("""
        CREATE TABLE IF NOT EXISTS grounding_events(
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            task_id TEXT NOT NULL,
            event_type TEXT NOT NULL,
            stage TEXT NOT NULL,
            used_web_sources TEXT,
            used_api_sources TEXT,
            used_openapi_sources TEXT,
            used_image_summary TEXT,
            used_http_probe TEXT,
            research_used INTEGER,
            api_reference_used INTEGER,
            openapi_used INTEGER,
            image_reference_used INTEGER,
            http_probe_used INTEGER,
            selected_source_type TEXT,
            synthesis_grounding_summary TEXT,
            grounding_metadata TEXT,
            created_at DATETIME DEFAULT (DATETIME('now', 'localtime'))
        )
        """)
        conn.commit()

        cursor.execute("""
        CREATE TABLE IF NOT EXISTS interaction_events(
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            task_id TEXT,
            event_type TEXT NOT NULL,
            source TEXT,
            action TEXT,
            message_id TEXT,
            message_type TEXT,
            content TEXT,
            payload TEXT,
            device_id TEXT,
            user_id TEXT,
            phone_number TEXT,
            created_at DATETIME DEFAULT (DATETIME('now', 'localtime'))
        )
        """)
        conn.commit()

        cursor.execute("""
        CREATE TABLE IF NOT EXISTS orchestration_events(
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            task_id TEXT NOT NULL,
            event_type TEXT NOT NULL,
            stage TEXT,
            flow_type TEXT,
            status TEXT,
            message TEXT,
            build_attempt INTEGER,
            related_trace_id INTEGER,
            related_event_id INTEGER,
            event_metadata TEXT,
            created_at DATETIME DEFAULT (DATETIME('now', 'localtime'))
        )
        """)
        conn.commit()

        cursor.execute("CREATE INDEX IF NOT EXISTS idx_user_query_logs_task_id_created_at ON user_query_logs(task_id, created_at)")
        cursor.execute("CREATE INDEX IF NOT EXISTS idx_conversation_messages_task_id_id ON conversation_messages(task_id, id)")
        cursor.execute("CREATE INDEX IF NOT EXISTS idx_workspace_revisions_task_id_revision_no ON workspace_revisions(task_id, revision_no)")
        cursor.execute("CREATE INDEX IF NOT EXISTS idx_grounding_events_task_id_created_at ON grounding_events(task_id, created_at)")
        cursor.execute("CREATE INDEX IF NOT EXISTS idx_interaction_events_task_id_created_at ON interaction_events(task_id, created_at)")
        cursor.execute("CREATE INDEX IF NOT EXISTS idx_interaction_events_type_created_at ON interaction_events(event_type, created_at)")
        cursor.execute("CREATE INDEX IF NOT EXISTS idx_orchestration_events_task_id_created_at ON orchestration_events(task_id, created_at)")
        cursor.execute("CREATE INDEX IF NOT EXISTS idx_orchestration_events_task_stage ON orchestration_events(task_id, stage)")
        cursor.execute("DROP VIEW IF EXISTS grounding_agent_trace_view")
        cursor.execute("""
        CREATE VIEW grounding_agent_trace_view AS
        SELECT
            ge.id AS grounding_event_id,
            ge.task_id AS task_id,
            ge.event_type AS event_type,
            ge.stage AS grounding_stage,
            ge.selected_source_type AS selected_source_type,
            ge.research_used AS research_used,
            ge.api_reference_used AS api_reference_used,
            ge.openapi_used AS openapi_used,
            ge.image_reference_used AS image_reference_used,
            ge.http_probe_used AS http_probe_used,
            ge.synthesis_grounding_summary AS synthesis_grounding_summary,
            ge.grounding_metadata AS grounding_metadata,
            ge.created_at AS grounding_created_at,
            atl.id AS agent_trace_id,
            atl.flow_type AS flow_type,
            atl.agent_name AS agent_name,
            atl.stage AS trace_stage,
            atl.tool_name AS tool_name,
            atl.validation_result AS validation_result,
            atl.fallback_used AS fallback_used,
            atl.fallback_reason AS fallback_reason,
            atl.usage_json AS usage_json,
            atl.prompt_tokens AS prompt_tokens,
            atl.completion_tokens AS completion_tokens,
            atl.total_tokens AS total_tokens,
            atl.created_at AS trace_created_at
        FROM grounding_events ge
        LEFT JOIN agent_trace_logs atl
            ON ge.task_id = atl.task_id
            AND (
                ge.stage = atl.stage
                OR (ge.stage = 'generate_init' AND atl.flow_type = 'generate')
                OR (ge.stage = 'refine' AND atl.flow_type = 'refine')
                OR (ge.stage = 'refine_plan' AND atl.flow_type = 'refine')
                OR (ge.stage = 'retry' AND atl.flow_type = 'retry')
                OR (ge.stage = 'research_then_build' AND atl.flow_type IN ('web_research', 'research_build'))
            )
        """)
        conn.commit()

init_db()

def log_token_usage(task_id, agent_name, usage_dict):
    if not usage_dict:
        return
    with get_conn() as conn:
        cursor = conn.cursor()
        cursor.execute("""
        INSERT INTO token_logs (task_id, agent_name, prompt_tokens, completion_tokens, total_tokens)
        VALUES (?, ?, ?, ?, ?)
        """, (
            task_id, 
            agent_name, 
            usage_dict.get("prompt", 0), 
            usage_dict.get("completion", 0), 
            usage_dict.get("total", 0)
        ))
        conn.commit()

# ------------------------------------------------
# DATABASE UTILITIES
# ------------------------------------------------

def was_recent_duplicate_user_query(
    task_id: Optional[str],
    content: str,
    *,
    endpoint: Optional[str] = None,
    query_type: Optional[str] = None,
    window_seconds: int = 3,
) -> bool:
    normalized_task_id = (task_id or "").strip()
    normalized_content = (content or "").strip()
    if not normalized_task_id or not normalized_content:
        return False

    with get_conn() as conn:
        conn.row_factory = sqlite3.Row
        cursor = conn.cursor()
        cursor.execute(
            """
            SELECT content, endpoint, message_type, created_at
            FROM conversation_messages
            WHERE task_id=?
              AND role='user'
            ORDER BY id DESC
            LIMIT 1
            """,
            (normalized_task_id,)
        )
        row = cursor.fetchone()

    if not row:
        return False

    last_content = (row["content"] or "").strip()
    last_created_at = row["created_at"] or ""
    if last_content != normalized_content:
        return False
    normalized_endpoint = (endpoint or "").strip()
    normalized_query_type = (query_type or "").strip()
    if normalized_endpoint and (row["endpoint"] or "").strip() != normalized_endpoint:
        return False
    if normalized_query_type and (row["message_type"] or "").strip() != normalized_query_type:
        return False

    with get_conn() as conn:
        cursor = conn.cursor()
        cursor.execute(
            """
            SELECT
                CASE
                    WHEN ABS((julianday(DATETIME('now', 'localtime')) - julianday(?)) * 86400.0) <= ?
                    THEN 1 ELSE 0
                END
            """,
            (last_created_at, window_seconds),
        )
        result = cursor.fetchone()
    return bool(result and int(result[0] or 0) == 1)

def record_user_query(
    *,
    task_id: Optional[str],
    device_id: Optional[str],
    user_id: Optional[str],
    phone_number: Optional[str],
    query_type: str,
    endpoint: str,
    content: str,
):
    if was_recent_duplicate_user_query(task_id, content, endpoint=endpoint, query_type=query_type):
        logger.info(
            f"[user_query] deduped task_id={task_id or '-'} endpoint={endpoint} query_type={query_type}"
        )
        return

    with get_conn() as conn:
        cursor = conn.cursor()
        cursor.execute(
            """
            INSERT INTO user_query_logs
            (task_id, device_id, user_id, phone_number, query_type, endpoint, content)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """,
            (
                task_id,
                device_id,
                user_id,
                normalize_phone_number(phone_number),
                query_type,
                endpoint,
                content,
            )
        )
        conn.commit()

    if task_id:
        record_conversation_message(
            task_id=task_id,
            role="user",
            message_type=query_type,
            endpoint=endpoint,
            content=content,
            payload={
                "device_id": device_id,
                "user_id": user_id,
                "phone_number": normalize_phone_number(phone_number),
            },
        )


def record_conversation_message(
    *,
    task_id: str,
    role: str,
    message_type: str,
    content: str,
    endpoint: Optional[str] = None,
    payload: Optional[Dict[str, Any]] = None,
):
    if not task_id or not role or not message_type or content is None:
        return
    with get_conn() as conn:
        cursor = conn.cursor()
        cursor.execute(
            """
            INSERT INTO conversation_messages
            (task_id, role, message_type, endpoint, content, payload)
            VALUES (?, ?, ?, ?, ?, ?)
            """,
            (
                task_id,
                role,
                message_type,
                endpoint,
                content,
                json_string(payload),
            )
        )
        conn.commit()


def record_assistant_response(
    *,
    task_id: str,
    message_type: str,
    content: str,
    endpoint: Optional[str] = None,
    payload: Optional[Dict[str, Any]] = None,
):
    record_conversation_message(
        task_id=task_id,
        role="assistant",
        message_type=message_type,
        endpoint=endpoint,
        content=content,
        payload=payload,
    )


def record_interaction_event(
    *,
    task_id: Optional[str],
    event_type: str,
    source: str = "android_host",
    action: Optional[str] = None,
    message_id: Optional[str] = None,
    message_type: Optional[str] = None,
    content: Optional[str] = None,
    payload: Optional[Dict[str, Any]] = None,
    device_id: Optional[str] = None,
    user_id: Optional[str] = None,
    phone_number: Optional[str] = None,
):
    if not event_type:
        return
    with get_conn() as conn:
        cursor = conn.cursor()
        cursor.execute(
            """
            INSERT INTO interaction_events
            (task_id, event_type, source, action, message_id, message_type, content, payload, device_id, user_id, phone_number)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            (
                task_id,
                event_type,
                source,
                action,
                message_id,
                message_type,
                content,
                json_string(payload),
                device_id,
                user_id,
                normalize_phone_number(phone_number),
            )
        )
        conn.commit()


def infer_orchestration_stage(message: str) -> str:
    text = message or ""
    if "앱 설계" in text:
        return "product_plan"
    if "UI 레이아웃" in text:
        return "layout_plan"
    if "화면 기능" in text:
        return "logic_plan"
    if "UI와 기능 통합" in text:
        return "integration_plan"
    if "코드 생성" in text or "코드 수정" in text:
        return "implement"
    if "코드 검토" in text or "검토" in text:
        return "review"
    if "정적 분석" in text:
        return "analyze"
    if "빌드 오류 수정" in text or "오류 수정" in text:
        return "debug"
    if "빌드 실행" in text or "빌드 성공" in text or "빌드 실패" in text:
        return "build"
    if "UI 기준선" in text:
        return "ui_contract"
    if "작업이 성공" in text:
        return "finalize"
    return "task_log"


def infer_orchestration_event_type(message: str) -> str:
    text = message or ""
    if "❌" in text or "실패" in text:
        return "stage_failed"
    if "✅" in text or "통과" in text or "성공" in text:
        return "stage_succeeded"
    if "수정 중" in text or "복구" in text:
        return "stage_repairing"
    return "stage_progress"


def record_orchestration_event(
    *,
    task_id: str,
    event_type: str,
    stage: Optional[str] = None,
    flow_type: Optional[str] = None,
    status: Optional[str] = None,
    message: Optional[str] = None,
    build_attempt: Optional[int] = None,
    related_trace_id: Optional[int] = None,
    related_event_id: Optional[int] = None,
    event_metadata: Optional[Dict[str, Any]] = None,
):
    if not task_id or not event_type:
        return
    if build_attempt is None:
        task = get_task(task_id) or {}
        build_attempt = task.get("build_attempts")
        status = status or task.get("status")
        flow_type = flow_type or task.get("active_flow")
    with get_conn() as conn:
        cursor = conn.cursor()
        cursor.execute(
            """
            INSERT INTO orchestration_events
            (task_id, event_type, stage, flow_type, status, message, build_attempt, related_trace_id, related_event_id, event_metadata)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            (
                task_id,
                event_type,
                stage,
                flow_type,
                status,
                message,
                build_attempt,
                related_trace_id,
                related_event_id,
                json_string(event_metadata),
            )
        )
        conn.commit()
def update_task(task_id, **kwargs):
    with get_conn() as conn:
        cursor = conn.cursor()
        for k, v in kwargs.items():
            if k == "device_info":
                v = json.dumps(v)
            if k == "conversation_state" and isinstance(v, dict):
                v = json.dumps(v, ensure_ascii=False)
            if k == "final_app_spec" and isinstance(v, dict):
                v = json.dumps(v, ensure_ascii=False)
            if k == "reference_image_analysis" and isinstance(v, dict):
                v = json.dumps(v, ensure_ascii=False)
            if k == "grounding_metadata" and isinstance(v, dict):
                v = json.dumps(v, ensure_ascii=False)
            if k == "ui_contract" and isinstance(v, dict):
                v = json.dumps(v, ensure_ascii=False)
            if k == "verification_report" and isinstance(v, dict):
                v = json.dumps(v, ensure_ascii=False)
            if k == "phone_number":
                v = normalize_phone_number(v)
            if k == "interview_consent":
                v = 1 if to_bool(v) else 0
            cursor.execute(
                f"UPDATE tasks SET {k}=? WHERE task_id=?",
                (v, task_id)
            )
        if kwargs:
            cursor.execute(
                "UPDATE tasks SET updated_at=(DATETIME('now', 'localtime')) WHERE task_id=?",
                (task_id,)
            )
        conn.commit()

def append_log(task_id, message):
    task = get_task(task_id)
    if not task:
        return
    old_log = task.get("log") or ""
    # 최신 로그를 상단 혹은 하단에 덧붙여 히스토리 유지
    new_log = old_log + "\n" + f"[{task_id}] {message}"
    update_task(task_id, log=new_log)


def get_reference_image_analysis(task: Dict[str, Any]) -> Dict[str, Any]:
    raw_value = task.get("reference_image_analysis")
    if isinstance(raw_value, dict):
        return raw_value
    if not raw_value:
        return {}
    try:
        parsed = json.loads(raw_value)
        return parsed if isinstance(parsed, dict) else {}
    except Exception:
        return {}


def build_reference_image_fingerprint(
    reference_image_path: Optional[str],
    reference_image_name: Optional[str],
    reference_image_base64: Optional[str],
) -> str:
    if isinstance(reference_image_base64, str):
        normalized_base64 = reference_image_base64.strip()
        if normalized_base64:
            return hashlib.sha256(normalized_base64.encode("utf-8")).hexdigest()

    normalized_path = (reference_image_path or "").strip()
    normalized_name = (reference_image_name or "").strip()
    fingerprint_seed = "|".join([normalized_path, normalized_name]).strip("|")
    if fingerprint_seed:
        return hashlib.sha256(fingerprint_seed.encode("utf-8")).hexdigest()
    return ""


def get_grounding_metadata(task: Dict[str, Any]) -> Dict[str, Any]:
    raw_value = task.get("grounding_metadata")
    if isinstance(raw_value, dict):
        return raw_value
    if not raw_value:
        return {}
    try:
        parsed = json.loads(raw_value)
        return parsed if isinstance(parsed, dict) else {}
    except Exception:
        return {}


def get_ui_contract(task: Dict[str, Any]) -> Dict[str, Any]:
    raw_value = task.get("ui_contract")
    if isinstance(raw_value, dict):
        return raw_value
    if not raw_value:
        return {}
    try:
        parsed = json.loads(raw_value)
        return parsed if isinstance(parsed, dict) else {}
    except Exception:
        return {}


def _unique_strings(*groups: Any) -> List[str]:
    values: List[str] = []
    seen: set[str] = set()
    for group in groups:
        if isinstance(group, str):
            candidates = [group]
        elif isinstance(group, list):
            candidates = group
        else:
            continue
        for candidate in candidates:
            if not isinstance(candidate, str):
                continue
            normalized = candidate.strip()
            if not normalized or normalized in seen:
                continue
            seen.add(normalized)
            values.append(normalized)
    return values


def _collect_urls_from_sources(sources: List[Dict[str, Any]], *, source_types: Optional[set[str]] = None, source_kinds: Optional[set[str]] = None) -> List[str]:
    urls: List[str] = []
    for source in sources:
        if not isinstance(source, dict):
            continue
        if source_types is not None and (source.get("source_type") or "") not in source_types:
            continue
        if source_kinds is not None and (source.get("source_kind") or "") not in source_kinds:
            continue
        for key in ("final_url", "url"):
            value = source.get(key)
            if isinstance(value, str) and value.strip():
                urls.append(value.strip())
                break
    return _unique_strings(urls)


def build_grounding_summary(metadata: Dict[str, Any]) -> str:
    parts: List[str] = []
    used_web_sources = metadata.get("used_web_sources") or []
    used_api_sources = metadata.get("used_api_sources") or []
    used_openapi_sources = metadata.get("used_openapi_sources") or []
    used_image_summary = (metadata.get("used_image_summary") or "").strip()
    used_http_probe = metadata.get("used_http_probe") or {}
    selected_source_type = (metadata.get("selected_source_type") or "").strip()

    if used_web_sources:
        parts.append(f"웹 참고 {len(used_web_sources)}건")
    if used_api_sources:
        label = "API 문서"
        if selected_source_type:
            label += f"({selected_source_type})"
        parts.append(f"{label} {len(used_api_sources)}건")
    if used_openapi_sources:
        parts.append(f"OpenAPI 명세 {len(used_openapi_sources)}건")
    if used_image_summary:
        parts.append("참고 이미지 요약")
    if isinstance(used_http_probe, dict) and used_http_probe.get("final_url"):
        parts.append("공개 GET probe")

    if not parts:
        return "추가 참고 근거 없이 텍스트 요구사항만 사용했어요."
    return "참고 근거: " + ", ".join(parts) + "를 반영했어요."


def build_grounding_metadata(
    *,
    existing_metadata: Optional[Dict[str, Any]] = None,
    flow_type: str = "",
    endpoint: str = "",
    web_sources: Optional[List[str]] = None,
    api_sources: Optional[List[str]] = None,
    openapi_sources: Optional[List[str]] = None,
    image_reference_summary: str = "",
    http_probe: Optional[Dict[str, Any]] = None,
    selected_source_type: str = "",
    research_quality_passed: Optional[bool] = None,
    research_quality_reason: str = "",
) -> Dict[str, Any]:
    existing = existing_metadata or {}
    metadata: Dict[str, Any] = dict(existing)
    metadata["used_web_sources"] = _unique_strings(existing.get("used_web_sources") or [], web_sources or [])
    metadata["used_api_sources"] = _unique_strings(existing.get("used_api_sources") or [], api_sources or [])
    metadata["used_openapi_sources"] = _unique_strings(existing.get("used_openapi_sources") or [], openapi_sources or [])

    current_image_summary = (image_reference_summary or "").strip() or (existing.get("used_image_summary") or "").strip()
    metadata["used_image_summary"] = current_image_summary

    current_probe = http_probe if isinstance(http_probe, dict) and http_probe else (existing.get("used_http_probe") or {})
    metadata["used_http_probe"] = current_probe

    current_source_type = (selected_source_type or "").strip() or (existing.get("selected_source_type") or "").strip()
    metadata["selected_source_type"] = current_source_type
    metadata["last_flow_type"] = (flow_type or "").strip() or (existing.get("last_flow_type") or "").strip()
    metadata["last_endpoint"] = (endpoint or "").strip() or (existing.get("last_endpoint") or "").strip()
    if research_quality_passed is not None:
        metadata["research_quality_passed"] = bool(research_quality_passed)
    elif "research_quality_passed" not in metadata:
        metadata["research_quality_passed"] = False
    if research_quality_reason:
        metadata["research_quality_reason"] = research_quality_reason
    elif "research_quality_reason" not in metadata:
        metadata["research_quality_reason"] = ""

    metadata["research_used"] = bool(metadata["used_web_sources"] or metadata["used_api_sources"] or metadata["used_openapi_sources"])
    metadata["api_reference_used"] = bool(metadata["used_api_sources"])
    metadata["openapi_used"] = bool(metadata["used_openapi_sources"])
    metadata["image_reference_used"] = bool(metadata["used_image_summary"])
    metadata["http_probe_used"] = bool(isinstance(current_probe, dict) and current_probe)
    metadata["synthesis_grounding_summary"] = build_grounding_summary(metadata)
    return metadata


def persist_grounding_metadata(
    task_id: str,
    metadata: Optional[Dict[str, Any]],
    *,
    endpoint: str,
    event_type: str = "grounding_snapshot",
    stage: str = "",
    emit_log_entry: bool = True,
) -> Dict[str, Any]:
    task = get_task(task_id)
    if not task or not isinstance(metadata, dict) or not metadata:
        return {}
    summary = (metadata.get("synthesis_grounding_summary") or "").strip()
    previous_summary = (task.get("synthesis_grounding_summary") or "").strip()
    update_task(
        task_id,
        grounding_metadata=metadata,
        synthesis_grounding_summary=summary,
        research_used=1 if metadata.get("research_used") else 0,
        api_reference_used=1 if metadata.get("api_reference_used") else 0,
        openapi_used=1 if metadata.get("openapi_used") else 0,
        image_reference_used=1 if metadata.get("image_reference_used") else 0,
        http_probe_used=1 if metadata.get("http_probe_used") else 0,
        selected_source_type=(metadata.get("selected_source_type") or "").strip(),
    )
    with get_conn() as conn:
        cursor = conn.cursor()
        cursor.execute(
            """
            INSERT INTO grounding_events
            (
                task_id, event_type, stage, used_web_sources, used_api_sources, used_openapi_sources,
                used_image_summary, used_http_probe, research_used, api_reference_used, openapi_used,
                image_reference_used, http_probe_used, selected_source_type, synthesis_grounding_summary,
                grounding_metadata
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            (
                task_id,
                event_type,
                (stage or endpoint or "unknown").strip() or "unknown",
                json_string(metadata.get("used_web_sources") or []),
                json_string(metadata.get("used_api_sources") or []),
                json_string(metadata.get("used_openapi_sources") or []),
                metadata.get("used_image_summary") or "",
                json_string(metadata.get("used_http_probe") or {}),
                1 if metadata.get("research_used") else 0,
                1 if metadata.get("api_reference_used") else 0,
                1 if metadata.get("openapi_used") else 0,
                1 if metadata.get("image_reference_used") else 0,
                1 if metadata.get("http_probe_used") else 0,
                (metadata.get("selected_source_type") or "").strip(),
                summary,
                json_string(metadata),
            ),
        )
        conn.commit()
    if emit_log_entry and summary and (summary != previous_summary or endpoint == "/retry"):
        emit_task_log(task_id, f"📚 참고 근거 요약\n{summary}")
    return metadata


def fetch_grounding_agent_trace_rows(task_id: Optional[str] = None, *, limit: int = 200) -> List[Dict[str, Any]]:
    query = """
        SELECT *
        FROM grounding_agent_trace_view
        {where_clause}
        ORDER BY grounding_created_at DESC, grounding_event_id DESC, trace_created_at DESC, agent_trace_id DESC
        LIMIT ?
    """
    params: List[Any] = []
    where_clause = ""
    normalized_task_id = (task_id or "").strip()
    if normalized_task_id:
        where_clause = "WHERE task_id = ?"
        params.append(normalized_task_id)
    params.append(limit)

    with get_conn() as conn:
        conn.row_factory = sqlite3.Row
        cursor = conn.cursor()
        cursor.execute(query.format(where_clause=where_clause), params)
        return [dict(row) for row in cursor.fetchall()]


def build_image_reference_summary(analysis: Dict[str, Any]) -> str:
    if not isinstance(analysis, dict):
        return ""
    layout_summary = (analysis.get("layout_summary") or "").strip()
    components = [item for item in (analysis.get("ui_components") or []) if isinstance(item, str) and item.strip()]
    if layout_summary and components:
        component_text = ", ".join(components[:3])
        return f"참고 이미지에서 {layout_summary} 주요 요소는 {component_text}예요."
    if layout_summary:
        return f"참고 이미지에서 {layout_summary}"
    if components:
        return f"참고 이미지의 주요 구성 요소로 {', '.join(components[:3])}를 확인했어요."
    return "참고 이미지의 색감과 구성 요소를 반영할게요."


def persist_visual_context(
    task_id: str,
    analysis: Optional[Dict[str, Any]],
    conflict_note: str = "",
    *,
    endpoint: str,
) -> Dict[str, str]:
    task = get_task(task_id)
    if not task or not isinstance(analysis, dict) or not analysis:
        return {"summary": "", "conflict_note": ""}
    summary = build_image_reference_summary(analysis)
    previous_summary = (task.get("image_reference_summary") or "").strip()
    previous_conflict = (task.get("image_conflict_note") or "").strip()
    update_task(
        task_id,
        reference_image_analysis=analysis,
        image_reference_summary=summary,
        image_conflict_note=conflict_note or "",
    )
    if summary and summary != previous_summary:
        record_assistant_response(
            task_id=task_id,
            message_type="image_reference_summary",
            endpoint=endpoint,
            content=summary,
            payload={"image_reference_summary": summary},
        )
    if conflict_note and conflict_note != previous_conflict:
        record_assistant_response(
            task_id=task_id,
            message_type="image_reference_conflict",
            endpoint=endpoint,
            content=conflict_note,
            payload={"image_conflict_note": conflict_note},
        )
    return {"summary": summary, "conflict_note": conflict_note or ""}


def emit_task_log(task_id: str, message: str):
    append_log(task_id, message)
    record_orchestration_event(
        task_id=task_id,
        event_type=infer_orchestration_event_type(message),
        stage=infer_orchestration_stage(message),
        message=message,
    )
    record_conversation_message(
        task_id=task_id,
        role="system",
        message_type="task_log",
        endpoint="task_log",
        content=message,
    )
    logger.info(f"[{task_id}] {message}")


def sanitize_log_text(value: str) -> str:
    return " ".join((value or "").split())


def extract_log_lines(value: str) -> list[str]:
    return [sanitize_log_text(line) for line in (value or "").splitlines() if line.strip()]


def get_latest_status_message(task: Dict[str, Any], log_lines: list[str]) -> str:
    if log_lines:
        return log_lines[-1]
    app_name = sanitize_log_text(task.get("app_name") or "")
    if app_name:
        return app_name
    return normalize_task_status(task.get("status"))


TRANSIENT_APP_NAME_VALUES = {
    "API 정보 확인 필요",
    "판단 실패",
    "추가 확인 필요",
    "대화 중",
    "확인 필요",
    "거절됨",
    "웹 페이지 읽기 실패",
    "검색 실패",
    "웹 데이터 파싱 실패",
    "외부 정보 품질 부족",
    "검색 해석 실패",
    "외부 데이터 계약 누락",
    "앱 설계 중...",
}


def is_transient_app_name(value: Any) -> bool:
    text = sanitize_log_text(str(value or ""))
    if not text:
        return True
    if text in TRANSIENT_APP_NAME_VALUES or text in ALLOWED_TASK_STATUSES:
        return True
    status_labels = {get_status_display_text(status) for status in ALLOWED_TASK_STATUSES}
    status_labels.update(get_status_display_text(TaskStatus.PROCESSING, mode) for mode in ("generate", "refine", "retry"))
    return text in status_labels


def trim_task_display_title(value: Any, max_chars: int = 48) -> str:
    text = sanitize_log_text(str(value or ""))
    if not text:
        return ""
    if len(text) <= max_chars:
        return text
    return text[: max_chars - 1].rstrip(" ,./-_:;") + "…"


def first_string_from_mapping(mapping: Dict[str, Any], *keys: str) -> str:
    for key in keys:
        value = mapping.get(key)
        if isinstance(value, str) and value.strip():
            return value.strip()
    return ""


def derive_task_display_app_name(task: Dict[str, Any]) -> str:
    generated_name = sanitize_log_text(task.get("generated_app_name") or "")
    if generated_name and not is_transient_app_name(generated_name):
        return trim_task_display_title(generated_name)

    stored_name = sanitize_log_text(task.get("app_name") or "")
    if stored_name and not is_transient_app_name(stored_name):
        return trim_task_display_title(stored_name)

    build_spec = parse_json_object_field(task.get("final_app_spec")) or {}
    spec_title = first_string_from_mapping(
        build_spec,
        "app_name",
        "app_title",
        "title",
        "name",
        "display_name",
        "app_goal",
        "summary",
    )
    if spec_title:
        return trim_task_display_title(spec_title)

    conversation_state = get_conversation_state(task)
    for candidate in (
        task.get("final_requirement_summary"),
        conversation_state.get("latest_summary"),
        task.get("initial_user_prompt"),
        conversation_state.get("initial_user_prompt"),
    ):
        title = trim_task_display_title(candidate)
        if title:
            return title
    return ""


def normalize_phone_number(value: Optional[str]) -> Optional[str]:
    if value is None:
        return None
    normalized = "".join(ch for ch in value if ch.isdigit() or ch == "+").strip()
    return normalized or None


def build_download_url(task: Dict[str, Any]) -> str:
    task_id = (task.get("task_id") or "").strip()
    if not task_id:
        return ""

    query_params: Dict[str, str] = {}
    # Prefer stable identities that survive reinstall over ephemeral device ids.
    for key in ("user_id", "phone_number", "device_id"):
        value = task.get(key)
        if key == "phone_number":
            value = normalize_phone_number(value)
        if isinstance(value, str) and value.strip():
            query_params[key] = value.strip()
            break

    suffix = f"?{urlencode(query_params)}" if query_params else ""
    return f"/download/{task_id}{suffix}"


def normalize_task_status(value: Any) -> str:
    if value in ALLOWED_TASK_STATUSES:
        return value
    return TaskStatus.ERROR


def get_status_display_text(status: Any, progress_mode: Optional[str] = None) -> str:
    normalized = normalize_task_status(status)
    mode = (progress_mode or "").strip().lower()
    if normalized == TaskStatus.PENDING_DECISION:
        return "요청을 검토하고 있어요"
    if normalized == TaskStatus.CLARIFICATION_NEEDED:
        return "추가 정보가 필요해요"
    if normalized == TaskStatus.PROCESSING:
        if mode == "refine":
            return "피드백을 반영하고 있어요"
        if mode == "retry":
            return "요청을 검토하고 있어요"
        return "앱을 생성하고 있어요"
    if normalized == TaskStatus.REVIEWING:
        return "결과를 점검하고 있어요"
    if normalized == TaskStatus.REPAIRING:
        return "오류를 수정하고 있어요"
    if normalized in {TaskStatus.FAILED, TaskStatus.ERROR}:
        return "앱 생성에 실패했어요"
    if normalized == TaskStatus.SUCCESS:
        return "앱 생성이 완료되었어요"
    if normalized == TaskStatus.REJECTED:
        return "요청을 처리할 수 없어요"
    return normalized

def get_task(task_id):
    with get_conn() as conn:
        conn.row_factory = sqlite3.Row
        cursor = conn.cursor()
        cursor.execute(
            "SELECT * FROM tasks WHERE task_id=?",
            (task_id,)
        )
        row = cursor.fetchone()
        return dict(row) if row else None


def infer_project_path_for_task(task: Dict[str, Any]) -> Optional[str]:
    task_id = (task.get("task_id") or "").strip()
    if not task_id or not os.path.isdir(BUILD_ROOT_DIR):
        return None

    candidates = []
    for entry in os.scandir(BUILD_ROOT_DIR):
        if not entry.is_dir():
            continue
        name = entry.name
        if task_id not in name:
            continue
        pubspec_path = os.path.join(entry.path, "pubspec.yaml")
        if not os.path.isfile(pubspec_path):
            continue
        try:
            mtime = entry.stat().st_mtime
        except OSError:
            mtime = 0
        candidates.append((mtime, entry.path))

    if not candidates:
        return None
    candidates.sort(key=lambda item: item[0], reverse=True)
    return candidates[0][1]


def resolve_task_project_path(task: Dict[str, Any], persist: bool = False) -> Optional[str]:
    project_path = task.get("project_path")
    if project_path and os.path.isdir(project_path):
        return project_path

    inferred_path = infer_project_path_for_task(task)
    if inferred_path:
        task["project_path"] = inferred_path
        if persist and task.get("task_id"):
            update_task(task["task_id"], project_path=inferred_path)
        return inferred_path
    return None


def get_workspace_revision_count(task_id: str) -> int:
    with get_conn() as conn:
        cursor = conn.cursor()
        cursor.execute(
            "SELECT COUNT(*) FROM workspace_revisions WHERE task_id=?",
            (task_id,)
        )
        row = cursor.fetchone()
    return int(row[0] or 0) if row else 0


def workspace_revision_exists(task_id: str, project_path: str) -> bool:
    with get_conn() as conn:
        cursor = conn.cursor()
        cursor.execute(
            """
            SELECT 1
            FROM workspace_revisions
            WHERE task_id=? AND project_path=?
            LIMIT 1
            """,
            (task_id, project_path)
        )
        return cursor.fetchone() is not None


def record_workspace_revision(
    *,
    task_id: str,
    project_path: str,
    flow_type: str,
    status: str,
    parent_project_path: Optional[str] = None,
    trigger_message: Optional[str] = None,
    ui_contract: Optional[Dict[str, Any]] = None,
) -> None:
    if not task_id or not project_path:
        return
    if workspace_revision_exists(task_id, project_path):
        if ui_contract:
            update_workspace_revision_ui_contract(project_path, ui_contract)
        return

    revision_no = get_workspace_revision_count(task_id)
    with get_conn() as conn:
        cursor = conn.cursor()
        cursor.execute(
            """
            INSERT INTO workspace_revisions
            (task_id, revision_no, flow_type, project_path, parent_project_path, trigger_message, status, ui_contract)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """,
            (
                task_id,
                revision_no,
                flow_type,
                project_path,
                parent_project_path,
                trigger_message,
                status,
                json_string(ui_contract) if ui_contract else None,
            )
        )
        conn.commit()


def update_workspace_revision_ui_contract(project_path: Optional[str], ui_contract: Optional[Dict[str, Any]]) -> None:
    normalized_path = (project_path or "").strip()
    if not normalized_path or not ui_contract:
        return
    with get_conn() as conn:
        cursor = conn.cursor()
        cursor.execute(
            """
            UPDATE workspace_revisions
            SET ui_contract=?
            WHERE project_path=?
            """,
            (json_string(ui_contract), normalized_path)
        )
        conn.commit()


def update_workspace_revision_status(project_path: Optional[str], status: str) -> None:
    normalized_path = (project_path or "").strip()
    if not normalized_path:
        return
    with get_conn() as conn:
        cursor = conn.cursor()
        cursor.execute(
            """
            UPDATE workspace_revisions
            SET status=?
            WHERE project_path=?
            """,
            (status, normalized_path)
        )
        conn.commit()


def refresh_task_ui_contract(
    *,
    task_id: str,
    project_path: Optional[str],
    flow_type: str,
) -> Dict[str, Any]:
    normalized_path = (project_path or "").strip()
    if not task_id or not normalized_path or not os.path.isdir(normalized_path):
        return {}

    task = get_task(task_id) or {}
    previous_contract = get_ui_contract(task)
    contract, usage, error = extract_ui_contract(
        normalized_path,
        task_id=task_id,
        previous_ui_contract=previous_contract,
        flow_type=flow_type,
    )
    log_token_usage(task_id, "UI_Contract_Extractor", usage)
    if error or not contract:
        emit_task_log(task_id, f"⚠️ UI 기준선 추출 실패: {error or 'unknown'}")
        return previous_contract

    update_task(task_id, ui_contract=contract)
    update_workspace_revision_ui_contract(normalized_path, contract)
    emit_task_log(task_id, "🎨 UI 기준선을 저장했습니다.")
    return contract


def ensure_workspace_revision_baseline(task: Dict[str, Any]) -> None:
    task_id = (task.get("task_id") or "").strip()
    project_path = (task.get("project_path") or "").strip()
    if not task_id or not project_path or not os.path.isdir(project_path):
        return
    record_workspace_revision(
        task_id=task_id,
        project_path=project_path,
        flow_type="baseline",
        status=normalize_task_status(task.get("status")),
        parent_project_path=None,
        trigger_message="baseline workspace",
        ui_contract=get_ui_contract(task),
    )


def build_workspace_clone_path(task_id: str, source_project_path: str, flow_type: str) -> str:
    source_name = os.path.basename(source_project_path.rstrip(os.sep))
    revision_no = get_workspace_revision_count(task_id)
    normalized_flow = (flow_type or "revision").strip().lower()
    normalized_flow = re.sub(r"[^a-z0-9]+", "_", normalized_flow).strip("_") or "revision"
    return os.path.join(
        BUILD_ROOT_DIR,
        f"{source_name}__r{revision_no:02d}_{normalized_flow}"
    )


def workspace_clone_ignore_patterns():
    return shutil.ignore_patterns(*WORKSPACE_CLONE_EXCLUDE_PATTERNS)


def create_workspace_revision_clone(
    *,
    task: Dict[str, Any],
    flow_type: str,
    trigger_message: str,
) -> str:
    task_id = (task.get("task_id") or "").strip()
    source_project_path = resolve_task_project_path(task, persist=True)
    if not task_id or not source_project_path:
        raise RuntimeError("workspace_clone_missing_source")
    if not os.path.isdir(source_project_path):
        raise RuntimeError(f"workspace_clone_invalid_source:{source_project_path}")

    ensure_workspace_revision_baseline(task)
    target_project_path = build_workspace_clone_path(task_id, source_project_path, flow_type)
    if os.path.exists(target_project_path):
        raise RuntimeError(f"workspace_clone_target_exists:{target_project_path}")

    shutil.copytree(
        source_project_path,
        target_project_path,
        ignore=workspace_clone_ignore_patterns(),
    )
    record_workspace_revision(
        task_id=task_id,
        project_path=target_project_path,
        flow_type=flow_type,
        status="Prepared",
        parent_project_path=source_project_path,
        trigger_message=trigger_message,
        ui_contract=get_ui_contract(task),
    )
    update_task(task_id, project_path=target_project_path)
    return target_project_path


def backfill_missing_project_paths():
    with get_conn() as conn:
        conn.row_factory = sqlite3.Row
        cursor = conn.cursor()
        cursor.execute(
            """
            SELECT task_id, project_path
            FROM tasks
            WHERE (project_path IS NULL OR TRIM(project_path) = '')
            """
        )
        rows = [dict(row) for row in cursor.fetchall()]

    for task in rows:
        inferred_path = resolve_task_project_path(task, persist=False)
        if inferred_path:
            update_task(task["task_id"], project_path=inferred_path)


def require_task_id(task_id: Optional[str], endpoint: str) -> str:
    normalized = (task_id or "").strip()
    if not normalized:
        raise HTTPException(
            status_code=400,
            detail="missing_task_id"
        )
    return normalized


def build_status_payload(task: Dict[str, Any]) -> Dict[str, Any]:
    # Official /status contract:
    # task_id, status, app_name, generated_app_name, package_name, apk_url,
    # build_success, build_attempts, conversation_state, log, log_lines,
    # retry_allowed, allowed_next_actions, retry_block_reason
    # Extended convenience fields:
    # latest_log, status_message, full_log
    normalized = dict(task)
    conversation_state = get_conversation_state(normalized)
    raw_log = normalized.get("log") or ""
    log_lines = extract_log_lines(raw_log)
    raw_app_name = normalized.get("app_name") or ""
    display_app_name = derive_task_display_app_name(normalized)
    app_name = raw_app_name if raw_app_name and not is_transient_app_name(raw_app_name) else display_app_name
    generated_app_name = display_app_name or (
        normalized.get("generated_app_name")
        if not is_transient_app_name(normalized.get("generated_app_name"))
        else ""
    )
    current_status = normalize_task_status(normalized.get("status"))
    status_message = get_latest_status_message(normalized, log_lines)
    resolved_project_path = resolve_task_project_path(normalized, persist=False)
    progress_mode = (normalized.get("active_flow") or "").strip()
    status_display_text = get_status_display_text(current_status, progress_mode)

    retry_allowed = current_status in {TaskStatus.FAILED, TaskStatus.ERROR} and bool(resolved_project_path)
    retry_block_reason = None
    if current_status in {TaskStatus.FAILED, TaskStatus.ERROR}:
        if not resolved_project_path:
            retry_block_reason = "missing_project_path"
    else:
        retry_block_reason = "status_not_retryable"

    allowed_next_actions = []
    if current_status in {
        TaskStatus.PENDING_DECISION,
        TaskStatus.CLARIFICATION_NEEDED,
        TaskStatus.REJECTED,
    }:
        allowed_next_actions.append("generate_continue")
    if current_status == TaskStatus.SUCCESS:
        allowed_next_actions.extend(["refine_plan", "refine"])
    if retry_allowed:
        allowed_next_actions.append("retry")

    return {
        "task_id": normalized.get("task_id") or "",
        "status": current_status,
        "app_name": app_name or "",
        "generated_app_name": generated_app_name or "",
        "package_name": normalized.get("package_name") or "",
        "apk_url": build_download_url(normalized) if current_status == TaskStatus.SUCCESS else (normalized.get("apk_url") or ""),
        "build_success": to_bool(normalized.get("build_success")) if normalized.get("build_success") is not None else False,
        "build_attempts": int(normalized.get("build_attempts") or 0),
        "conversation_state": conversation_state,
        "log": status_message,
        "full_log": raw_log,
        "log_lines": log_lines,
        "latest_log": status_message,
        "status_message": status_message,
        "progress_mode": progress_mode,
        "status_display_text": status_display_text,
        "retry_allowed": retry_allowed,
        "allowed_next_actions": allowed_next_actions,
        "retry_block_reason": retry_block_reason,
        "verification_summary": normalized.get("verification_summary") or "",
        "verification_passed": to_bool(normalized.get("verification_passed")) if normalized.get("verification_passed") is not None else None,
    }


def get_conversation_state(task: Dict[str, Any]) -> Dict[str, Any]:
    base_state = {
        "initial_user_prompt": task.get("initial_user_prompt") or "",
        "latest_assistant_questions": [],
        "latest_user_reply": "",
        "latest_summary": "",
        "latest_pending_action": "",
        "latest_pending_summary": "",
        "latest_pending_reason": "",
    }

    raw_state = task.get("conversation_state")
    if not raw_state:
        return base_state
    try:
        parsed = json.loads(raw_state)
        if not isinstance(parsed, dict):
            return base_state
        return {
            "initial_user_prompt": parsed.get("initial_user_prompt") or base_state["initial_user_prompt"],
            "latest_assistant_questions": parsed.get("latest_assistant_questions") or [],
            "latest_user_reply": parsed.get("latest_user_reply") or "",
            "latest_summary": parsed.get("latest_summary") or "",
            "latest_pending_action": parsed.get("latest_pending_action") or "",
            "latest_pending_summary": parsed.get("latest_pending_summary") or "",
            "latest_pending_reason": parsed.get("latest_pending_reason") or "",
        }
    except json.JSONDecodeError:
        return base_state


def update_conversation_state(task_id: str, **kwargs):
    task = get_task(task_id)
    if not task:
        return
    state = get_conversation_state(task)
    for key, value in kwargs.items():
        if value is not None:
            state[key] = value
    update_task(task_id, conversation_state=state)


def json_string(value: Any) -> Optional[str]:
    if value is None:
        return None
    return json.dumps(value, ensure_ascii=False)


def parse_json_object_field(value: Any) -> Optional[Dict[str, Any]]:
    if isinstance(value, dict):
        return value
    if not value or not isinstance(value, str):
        return None
    try:
        parsed = json.loads(value)
    except json.JSONDecodeError:
        return None
    return parsed if isinstance(parsed, dict) else None


backfill_missing_project_paths()


def extract_retry_context_from_log(task_log: str) -> Dict[str, str]:
    raw_log = task_log or ""
    lines = [line.strip() for line in raw_log.splitlines() if line.strip()]
    user_request = ""
    summary = ""

    for line in lines:
        if "User request:" in line:
            user_request = line.split("User request:", 1)[1].strip()
            break

    for index, line in enumerate(lines):
        normalized = line.split("] ", 1)[-1]
        if normalized.startswith("요약:"):
            summary = normalized.split("요약:", 1)[1].strip()
            break
        if normalized == "빌드 요청 수락" and index + 1 < len(lines):
            next_line = lines[index + 1].split("] ", 1)[-1]
            if next_line.startswith("요약:"):
                summary = next_line.split("요약:", 1)[1].strip()
                break

    return {
        "user_request": user_request,
        "summary": summary,
    }


def get_recent_conversation_messages(
    task_id: str,
    *,
    limit: int = 12,
    include_task_logs: bool = False,
) -> List[Dict[str, Any]]:
    if not task_id:
        return []

    where_clause = "WHERE task_id=?"
    params: List[Any] = [task_id]
    if not include_task_logs:
        where_clause += " AND message_type != ?"
        params.append("task_log")
    params.append(limit)

    with get_conn() as conn:
        conn.row_factory = sqlite3.Row
        cursor = conn.cursor()
        cursor.execute(
            f"""
            SELECT id, role, message_type, endpoint, content, payload, created_at
            FROM conversation_messages
            {where_clause}
            ORDER BY id DESC
            LIMIT ?
            """,
            tuple(params)
        )
        rows = [dict(row) for row in cursor.fetchall()]

    rows.reverse()
    normalized: List[Dict[str, Any]] = []
    for row in rows:
        normalized.append(
            {
                "id": row.get("id"),
                "role": row.get("role") or "",
                "message_type": row.get("message_type") or "",
                "endpoint": row.get("endpoint") or "",
                "content": row.get("content") or "",
                "payload": parse_json_object_field(row.get("payload")) or {},
                "created_at": row.get("created_at") or "",
            }
        )
    return normalized


def trim_context_text(value: Any, limit: int = 600) -> str:
    text = str(value or "").strip()
    if len(text) <= limit:
        return text
    return text[: max(limit - 1, 0)].rstrip() + "…"


def get_recent_failure_log_lines(task_log: str, *, limit: int = 8) -> List[str]:
    lines = extract_log_lines(task_log)
    if not lines:
        return []

    failure_keywords = (
        "❌",
        "실패",
        "오류",
        "크래시",
        "복구",
        "재시도",
        "자가 복구",
    )
    matched = [line for line in lines if any(keyword in line for keyword in failure_keywords)]
    if not matched:
        matched = lines
    return matched[-limit:]


def summarize_retry_conversation_context(task_id: str, *, limit: int = 10) -> Dict[str, Any]:
    messages = get_recent_conversation_messages(task_id, limit=limit, include_task_logs=False)
    if not messages:
        return {
            "recent_messages": [],
            "latest_runtime_error": {},
            "latest_user_requests": [],
            "repair_history": [],
        }

    recent_messages: List[Dict[str, Any]] = []
    latest_runtime_report: Dict[str, Any] = {}
    latest_runtime_summary: Dict[str, Any] = {}
    latest_user_requests: List[Dict[str, Any]] = []
    repair_history: List[Dict[str, Any]] = []

    for message in messages:
        payload = message.get("payload") or {}
        message_type = message.get("message_type") or ""
        content = trim_context_text(message.get("content") or "", 400)

        if message_type == "runtime_error_report":
            latest_runtime_report = {
                "package_name": payload.get("package_name") or "",
                "stack_trace": trim_context_text(payload.get("stack_trace") or "", 1200),
                "content": content,
                "created_at": message.get("created_at") or "",
            }
        elif message_type == "runtime_error_summary":
            latest_runtime_summary = {
                "summary": payload.get("summary") or content,
                "assistant_message": content,
                "created_at": message.get("created_at") or "",
            }
        elif message_type in {"runtime_repair_started", "runtime_repair_failed", "runtime_repair_succeeded"}:
            repair_history.append(
                {
                    "message_type": message_type,
                    "content": content,
                    "created_at": message.get("created_at") or "",
                    "payload": payload,
                }
            )

        if message.get("role") == "user":
            latest_user_requests.append(
                {
                    "message_type": message_type,
                    "content": content,
                    "created_at": message.get("created_at") or "",
                }
            )

        recent_messages.append(
            {
                "role": message.get("role") or "",
                "message_type": message_type,
                "content": content,
                "created_at": message.get("created_at") or "",
            }
        )

    latest_runtime_error: Dict[str, Any] = {}
    if latest_runtime_report or latest_runtime_summary:
        latest_runtime_error = {
            "package_name": latest_runtime_report.get("package_name") or "",
            "summary": latest_runtime_summary.get("summary") or "",
            "assistant_message": latest_runtime_summary.get("assistant_message") or "",
            "stack_trace": latest_runtime_report.get("stack_trace") or "",
            "reported_at": latest_runtime_report.get("created_at") or latest_runtime_summary.get("created_at") or "",
        }

    return {
        "recent_messages": recent_messages[-limit:],
        "latest_runtime_error": latest_runtime_error,
        "latest_user_requests": latest_user_requests[-5:],
        "repair_history": repair_history[-5:],
    }


def build_retry_request_context(task: Dict[str, Any]) -> Dict[str, Any]:
    conversation_state = get_conversation_state(task)
    log_fallback = extract_retry_context_from_log(task.get("log") or "")
    final_app_spec = parse_json_object_field(task.get("final_app_spec"))
    recent_context = summarize_retry_conversation_context(task.get("task_id") or "", limit=10)
    failure_log_lines = get_recent_failure_log_lines(task.get("log") or "", limit=8)
    reference_image_analysis = get_reference_image_analysis(task)
    image_reference_summary = (task.get("image_reference_summary") or "").strip()
    image_conflict_note = (task.get("image_conflict_note") or "").strip()
    grounding_metadata = get_grounding_metadata(task)
    synthesis_grounding_summary = (task.get("synthesis_grounding_summary") or "").strip()
    ui_contract = get_ui_contract(task)

    initial_prompt = (
        task.get("initial_user_prompt")
        or conversation_state.get("initial_user_prompt")
        or log_fallback.get("user_request")
        or ""
    )
    requirement_summary = (
        task.get("final_requirement_summary")
        or conversation_state.get("latest_summary")
        or log_fallback.get("summary")
        or ""
    )

    return {
        "task_id": task.get("task_id") or "",
        "generated_app_name": derive_task_display_app_name(task),
        "initial_user_prompt": initial_prompt,
        "requirement_summary": requirement_summary,
        "final_app_spec": final_app_spec or {},
        "ui_contract": ui_contract,
        "retry_origin": "runtime_repair_failure"
        if recent_context.get("latest_runtime_error") or recent_context.get("repair_history")
        else "build_failure",
        "latest_runtime_error": recent_context.get("latest_runtime_error") or {},
        "recent_user_requests": recent_context.get("latest_user_requests") or [],
        "recent_conversation": recent_context.get("recent_messages") or [],
        "repair_history": recent_context.get("repair_history") or [],
        "recent_failure_log_lines": failure_log_lines,
        "reference_image_analysis": reference_image_analysis,
        "image_reference_summary": image_reference_summary,
        "image_conflict_note": image_conflict_note,
        "grounding_metadata": grounding_metadata,
        "synthesis_grounding_summary": synthesis_grounding_summary,
        "context_sources": {
            "initial_user_prompt": "task.initial_user_prompt"
            if task.get("initial_user_prompt")
            else "conversation_state.initial_user_prompt"
            if conversation_state.get("initial_user_prompt")
            else "task.log",
            "requirement_summary": "task.final_requirement_summary"
            if task.get("final_requirement_summary")
            else "conversation_state.latest_summary"
            if conversation_state.get("latest_summary")
            else "task.log",
            "final_app_spec": "task.final_app_spec" if final_app_spec else "missing",
            "ui_contract": "task.ui_contract" if ui_contract else "missing",
            "recent_conversation": "conversation_messages",
            "latest_runtime_error": "conversation_messages.runtime_error_*"
            if recent_context.get("latest_runtime_error")
            else "missing",
            "recent_failure_log_lines": "task.log" if failure_log_lines else "missing",
            "reference_image_analysis": "tasks.reference_image_analysis"
            if reference_image_analysis
            else "missing",
            "image_reference_summary": "tasks.image_reference_summary"
            if image_reference_summary
            else "missing",
            "grounding_metadata": "tasks.grounding_metadata"
            if grounding_metadata
            else "missing",
        },
    }


def build_failure_summary_fallback(failure_stage: Optional[str], failure_type: Optional[str]) -> Dict[str, str]:
    stage = (failure_stage or "").strip().lower()
    failure_kind = (failure_type or "").strip().lower()

    if stage == "analyze":
        stage_text = "코드 검사 단계"
    elif stage == "build":
        stage_text = "APK 빌드 단계"
    else:
        stage_text = "빌드 단계"

    if failure_kind == "syntax":
        cause = "코드 문법 오류"
    elif failure_kind == "import":
        cause = "파일 또는 import 경로 문제"
    elif failure_kind == "type":
        cause = "타입 불일치 오류"
    elif failure_kind == "layout":
        cause = "레이아웃 관련 코드 오류"
    elif failure_kind == "gradle":
        cause = "Android 또는 Gradle 설정 문제"
    else:
        cause = "알 수 없는 빌드 오류"

    summary = f"{stage_text}에서 {cause}가 발생했어요."
    return {
        "summary": summary,
        "assistant_message": f"앱 빌드에 실패했어요. 원인은 {summary} 수정해서 다시 시도할 수 있어요.",
    }


def extract_primary_failure_line(error_log: Any) -> str:
    text = str(error_log or "").strip()
    if not text:
        return ""
    lines = [line.strip() for line in text.splitlines() if line.strip()]
    if not lines:
        return ""
    preferred_keywords = ("error", "exception", "failed", "failure", "undefined", "missing", "refusing", "invalid", "overflow")
    for line in lines:
        lowered = line.lower()
        if any(keyword in lowered for keyword in preferred_keywords):
            return trim_context_text(line, 280)
    return trim_context_text(lines[0], 280)


def build_spec_requires_external_data_verification(build_spec: Dict[str, Any]) -> bool:
    if not isinstance(build_spec, dict):
        return False
    requirements = build_spec.get("verification_requirements") or {}
    return bool(
        requirements.get("requires_external_data_verification")
        or (build_spec.get("data_source_type") or "").strip().lower() in {"web_scrape", "api", "manual"}
        or build_spec.get("source_url_candidates")
        or build_spec.get("external_sources")
    )


def enforce_release_verification_result(task: Dict[str, Any], result: Dict[str, Any]) -> Dict[str, Any]:
    if not isinstance(result, dict) or result.get("status") != "success":
        return result

    verification_report = result.get("verification_report") if isinstance(result.get("verification_report"), dict) else {}
    verification_status = (verification_report.get("status") or result.get("verification_status") or "").strip()
    build_spec = result.get("build_spec") if isinstance(result.get("build_spec"), dict) else {}
    if not build_spec:
        build_spec = parse_json_object_field(task.get("final_app_spec")) or {}

    requires_verification = build_spec_requires_external_data_verification(build_spec)
    if verification_status == "fail" or (requires_verification and verification_status not in {"pass", "not_applicable"}):
        verification_summary = (
            result.get("verification_summary")
            or verification_report.get("summary")
            or "외부 데이터 핵심 기능 검증이 통과되지 않았습니다."
        )
        verification_issues = verification_report.get("issues") if isinstance(verification_report.get("issues"), list) else []
        return {
            **result,
            "status": "failed",
            "error_log": verification_summary + ("\n" + "\n".join(verification_issues) if verification_issues else ""),
            "failure_stage": "verification",
            "failure_type": "external_data_verification",
            "verification_summary": verification_summary,
            "verification_report": verification_report,
        }

    return result


def find_existing_built_apk(project_path: str) -> str:
    if not project_path:
        return ""
    candidates = [
        os.path.join(project_path, "build/app/outputs/flutter-apk/app-debug.apk"),
        os.path.join(project_path, "build/app/outputs/flutter-apk/app-release.apk"),
        os.path.join(project_path, "build/app/outputs/apk/debug/app-debug.apk"),
        os.path.join(project_path, "build/app/outputs/apk/release/app-release.apk"),
    ]
    for candidate in candidates:
        if os.path.isfile(candidate):
            return candidate
    return ""


def reconcile_failed_result_with_verified_artifact(
    task: Dict[str, Any],
    result: Dict[str, Any],
    *,
    callback_log=None,
) -> Dict[str, Any]:
    if not isinstance(result, dict) or result.get("status") == "success":
        return result

    task_id = task.get("task_id") or result.get("task_id") or ""
    current_task = get_task(task_id) if task_id else {}
    task_context = {**task, **(current_task or {})}

    project_path = result.get("project_path") or task_context.get("project_path")
    if not project_path or not os.path.isdir(project_path):
        return result

    existing_apk = find_existing_built_apk(project_path)
    if not existing_apk:
        return result

    package_name = result.get("package_name") or task_context.get("package_name") or ""
    build_spec = result.get("build_spec") if isinstance(result.get("build_spec"), dict) else {}
    if not build_spec:
        build_spec = parse_json_object_field(task_context.get("final_app_spec")) or {}

    try:
        if callback_log:
            callback_log("🧪 실패 처리 전 기존 APK와 프로젝트 상태를 재검증합니다.")

        preflight = run_static_preflight_checks(
            project_path,
            task_id=task_id,
            package_name=package_name,
        )
        if preflight.get("status") != "pass":
            if callback_log:
                issues = preflight.get("issues") if isinstance(preflight.get("issues"), list) else []
                callback_log(f"❌ 재검증 중 preflight 실패: {trim_context_text('; '.join(map(str, issues)), 280)}")
            return result

        analyze_ok, analyze_output = run_flutter_analyze(project_path)
        if not analyze_ok:
            if callback_log:
                callback_log(f"❌ 재검증 중 analyze 실패: {extract_primary_failure_line(analyze_output)}")
            return result

        build_ok, build_res = run_flutter_build(project_path)
        if not build_ok or not isinstance(build_res, str) or not os.path.isfile(build_res):
            if callback_log:
                callback_log(f"❌ 재검증 중 build 실패: {extract_primary_failure_line(str(build_res))}")
            return result

        verification = verify_release_external_data_gate(
            project_path,
            build_spec=build_spec,
            task_id=task_id,
            token_callback=log_token_usage,
            callback_log=callback_log,
        )
        if verification.get("status") not in {"pass", "not_applicable"}:
            if callback_log:
                callback_log(f"❌ 재검증 중 핵심 기능 검증 실패: {verification.get('summary') or '검증 실패'}")
            return result

        if callback_log:
            callback_log("✅ 기존 산출물 재검증 통과: 실패 상태를 성공으로 보정합니다.")

        return {
            **result,
            "status": "success",
            "app_name": result.get("app_name") or task_context.get("generated_app_name") or task_context.get("app_name") or "생성 앱",
            "apk_path": build_res,
            "project_path": project_path,
            "package_name": package_name,
            "build_spec": build_spec,
            "verification_status": verification.get("status"),
            "verification_summary": verification.get("summary") or "재검증 통과",
            "verification_report": verification,
            "reconciled_from_failure": True,
        }
    except Exception as exc:
        if callback_log:
            callback_log(f"⚠️ 기존 산출물 재검증 중 예외: {trim_context_text(str(exc), 280)}")
        return result


FUNCTION_CALLING_FALLBACK_REMOVAL_PLAN = [
    {
        "phase": 1,
        "targets": ["Feedback_Router", "Runtime_Error_Summarizer", "Build_Failure_Summarizer"],
        "removal_condition": "최근 trace에서 fallback_used=0이고 validation_result 누락이 없을 때",
    },
    {
        "phase": 2,
        "targets": ["Generate_Decision", "Research_Build_Synthesizer"],
        "removal_condition": "ask/build/research/answer/confirmation 주요 tool path smoke test가 모두 안정적일 때",
    },
    {
        "phase": 3,
        "targets": ["Product_Planner", "UI_Layout_Designer", "Feature_Logic_Designer", "Integration_Planner", "Refiner_Planner", "Reviewer"],
        "removal_condition": "생성/재명세/retry 회귀 테스트에서 schema normalizer 의존 fallback이 없을 때",
    },
    {
        "phase": 4,
        "targets": ["Engineer", "Debugger", "Refiner_Engineer", "Runtime_Debugger"],
        "removal_condition": "파일 변경 schema 실패율이 충분히 낮고 fallback 없이도 build/analyze 복구율이 유지될 때",
    },
]


def build_function_calling_trace_quality_report(task_id: Optional[str] = None) -> Dict[str, Any]:
    where_clause = ""
    params: List[Any] = []
    if task_id:
        where_clause = "WHERE task_id=?"
        params.append(task_id)

    with get_conn() as conn:
        conn.row_factory = sqlite3.Row
        cursor = conn.cursor()
        cursor.execute(
            f"""
            SELECT
                COUNT(*) AS total_traces,
                SUM(CASE WHEN tool_name IS NULL OR TRIM(tool_name) = '' THEN 1 ELSE 0 END) AS missing_tool_name,
                SUM(CASE WHEN parsed_output IS NULL OR TRIM(parsed_output) = '' THEN 1 ELSE 0 END) AS missing_parsed_output,
                SUM(CASE WHEN validation_result IS NULL OR TRIM(validation_result) = '' THEN 1 ELSE 0 END) AS missing_validation_result,
                SUM(CASE WHEN usage_json IS NULL OR TRIM(usage_json) = '' THEN 1 ELSE 0 END) AS missing_usage_json,
                SUM(CASE WHEN fallback_used = 1 THEN 1 ELSE 0 END) AS fallback_count,
                SUM(CASE WHEN fallback_reason LIKE 'invalid_tool:%' OR validation_result LIKE '%invalid_tool:%' THEN 1 ELSE 0 END) AS invalid_tool_count
            FROM agent_trace_logs
            {where_clause}
            """,
            tuple(params),
        )
        totals = dict(cursor.fetchone() or {})
        cursor.execute(
            f"""
            SELECT
                COALESCE(agent_name, 'Unknown_Agent') AS agent_name,
                COALESCE(stage, '') AS stage,
                COUNT(*) AS total_traces,
                SUM(CASE WHEN fallback_used = 1 THEN 1 ELSE 0 END) AS fallback_count,
                SUM(CASE WHEN validation_result IS NULL OR TRIM(validation_result) = '' THEN 1 ELSE 0 END) AS missing_validation_result,
                SUM(CASE WHEN parsed_output IS NULL OR TRIM(parsed_output) = '' THEN 1 ELSE 0 END) AS missing_parsed_output
            FROM agent_trace_logs
            {where_clause}
            GROUP BY agent_name, stage
            ORDER BY fallback_count DESC, missing_validation_result DESC, total_traces DESC
            LIMIT 50
            """,
            tuple(params),
        )
        by_agent = [dict(row) for row in cursor.fetchall()]

    total = int(totals.get("total_traces") or 0)
    fallback_count = int(totals.get("fallback_count") or 0)
    missing_core_fields = sum(
        int(totals.get(key) or 0)
        for key in ["missing_tool_name", "missing_parsed_output", "missing_validation_result", "missing_usage_json"]
    )
    return {
        "task_id": task_id or "",
        "totals": totals,
        "by_agent_stage": by_agent,
        "fallback_rate": (fallback_count / total) if total else 0.0,
        "trace_quality_ok": total > 0 and fallback_count == 0 and missing_core_fields == 0,
        "fallback_removal_plan": FUNCTION_CALLING_FALLBACK_REMOVAL_PLAN,
    }


def sanitize_reference_image_clarification(
    decision: Dict[str, Any],
    reference_image_analysis: Optional[Dict[str, Any]],
) -> Dict[str, Any]:
    if not reference_image_analysis or not isinstance(decision, dict):
        return decision
    if decision.get("tool") != "ask_clarification":
        return decision

    arguments = decision.get("arguments")
    if not isinstance(arguments, dict):
        return decision

    questions = arguments.get("questions") or []
    if not isinstance(questions, list):
        return decision

    def is_false_missing_image_question(question: Any) -> bool:
        text = str(question or "").strip()
        return "이미지" in text and any(marker in text for marker in ["보이지", "업로드", "첨부", "다시"])

    filtered_questions = [str(q).strip() for q in questions if str(q or "").strip() and not is_false_missing_image_question(q)]
    if not filtered_questions:
        filtered_questions = ["참고 이미지 분석은 반영했습니다. 앱에 꼭 필요한 기능이나 저장 방식이 있으면 알려주세요."]

    missing_fields = arguments.get("missing_fields") or []
    if isinstance(missing_fields, list):
        arguments["missing_fields"] = [
            field for field in missing_fields
            if "image" not in str(field).lower() and "이미지" not in str(field)
        ]
    arguments["questions"] = filtered_questions[:3]
    decision["arguments"] = arguments
    return decision


def log_decision_event(
    task_id: str,
    *,
    event_type: str,
    decision_type: Optional[str],
    tool_name: Optional[str],
    tool_args: Optional[Dict[str, Any]],
    raw_user_message: Optional[str],
    structured_summary: Optional[Dict[str, Any]],
):
    with get_conn() as conn:
        cursor = conn.cursor()
        cursor.execute(
            """
            INSERT INTO decision_logs
            (task_id, event_type, decision_type, tool_name, tool_args, raw_user_message, structured_summary)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """,
            (
                task_id,
                event_type,
                decision_type,
                tool_name,
                json_string(tool_args),
                raw_user_message,
                json_string(structured_summary),
            )
        )
        conn.commit()


def increment_build_attempts(task_id: str):
    with get_conn() as conn:
        cursor = conn.cursor()
        cursor.execute(
            """
            UPDATE tasks
            SET build_attempts = COALESCE(build_attempts, 0) + 1,
                updated_at = DATETIME('now', 'localtime')
            WHERE task_id=?
            """,
            (task_id,)
        )
        conn.commit()

def can_access_task(
    task: Dict[str, Any],
    device_id: Optional[str] = None,
    user_id: Optional[str] = None,
    phone_number: Optional[str] = None,
) -> bool:
    task_device_id = (task.get("device_id") or "").strip()
    normalized_device_id = (device_id or "").strip()
    if task_device_id and normalized_device_id and task_device_id == normalized_device_id:
        return True

    task_user_id = (task.get("user_id") or "").strip()
    normalized_user_id = (user_id or "").strip()
    if task_user_id and normalized_user_id and task_user_id == normalized_user_id:
        return True

    task_phone_number = normalize_phone_number(task.get("phone_number"))
    normalized_phone_number = normalize_phone_number(phone_number)
    if task_phone_number and normalized_phone_number and task_phone_number == normalized_phone_number:
        return True

    return False


def build_access_denied_reason(
    task: Dict[str, Any],
    *,
    device_id: Optional[str] = None,
    user_id: Optional[str] = None,
    phone_number: Optional[str] = None,
) -> str:
    normalized_device_id = (device_id or "").strip()
    normalized_user_id = (user_id or "").strip()
    normalized_phone_number = normalize_phone_number(phone_number)

    if not normalized_device_id and not normalized_user_id and not normalized_phone_number:
        return "identity_missing"

    if (task.get("device_id") or "").strip() and normalized_device_id:
        return "device_id_mismatch"
    if (task.get("user_id") or "").strip() and normalized_user_id:
        return "user_id_mismatch"
    if normalize_phone_number(task.get("phone_number")) and normalized_phone_number:
        return "phone_number_mismatch"
    return "identity_missing_on_task"


def maybe_rebind_task_device(
    task: Dict[str, Any],
    *,
    device_id: Optional[str] = None,
    user_id: Optional[str] = None,
    phone_number: Optional[str] = None,
) -> bool:
    task_id = (task.get("task_id") or "").strip()
    normalized_device_id = (device_id or "").strip()
    if not task_id or not normalized_device_id:
        return False

    current_device_id = (task.get("device_id") or "").strip()
    if current_device_id == normalized_device_id:
        return False

    task_user_id = (task.get("user_id") or "").strip()
    normalized_user_id = (user_id or "").strip()
    task_phone_number = normalize_phone_number(task.get("phone_number"))
    normalized_phone_number = normalize_phone_number(phone_number)
    matched_by_stable_identity = (
        (task_user_id and normalized_user_id and task_user_id == normalized_user_id)
        or (task_phone_number and normalized_phone_number and task_phone_number == normalized_phone_number)
    )
    if not matched_by_stable_identity:
        return False

    with get_conn() as conn:
        cursor = conn.cursor()
        cursor.execute(
            "UPDATE tasks SET device_id=? WHERE task_id=?",
            (normalized_device_id, task_id)
        )
        conn.commit()

    task["device_id"] = normalized_device_id
    return True


def log_device_access(endpoint: str, *, task_id: Optional[str] = None, received_device_id: Optional[str] = None, stored_device_id: Optional[str] = None, reason: str = "ok"):
    print(
        f"[device_access] endpoint={endpoint} task_id={task_id} "
        f"received_device_id={received_device_id} stored_device_id={stored_device_id} reason={reason}"
    )

# ------------------------------------------------
# API DATA MODELS
# ------------------------------------------------

class BuildRequest(BaseModel):
    prompt: str
    device_id: Optional[str] = None
    device_info: Optional[Dict[str, Any]] = None
    user_id: Optional[str] = None
    phone_number: Optional[str] = None
    interview_consent: Optional[bool] = None
    reference_image_path: Optional[str] = None
    reference_image_name: Optional[str] = None
    reference_image_base64: Optional[str] = None

class RefineRequest(BaseModel):
    task_id: str
    feedback: str
    device_id: Optional[str] = None
    user_id: Optional[str] = None
    phone_number: Optional[str] = None
    reference_image_path: Optional[str] = None
    reference_image_name: Optional[str] = None
    reference_image_base64: Optional[str] = None

class RetryRequest(BaseModel):
    task_id: str
    feedback: str
    device_id: Optional[str] = None
    user_id: Optional[str] = None
    phone_number: Optional[str] = None

class CrashReport(BaseModel):
    task_id: str
    package_name: str
    stack_trace: str
    device_id: Optional[str] = None
    user_id: Optional[str] = None
    phone_number: Optional[str] = None

class RuntimeErrorContext(BaseModel):
    package_name: Optional[str] = None
    stack_trace: Optional[str] = None
    summary: Optional[str] = None
    awaiting_user_confirmation: Optional[bool] = None

class FeedbackRouteRequest(BaseModel):
    task_id: str
    user_message: str
    device_id: Optional[str] = None
    user_id: Optional[str] = None
    phone_number: Optional[str] = None
    runtime_error: Optional[RuntimeErrorContext] = None

class RuntimeErrorSummaryRequest(BaseModel):
    task_id: str
    package_name: Optional[str] = None
    stack_trace: str
    device_id: Optional[str] = None
    user_id: Optional[str] = None
    phone_number: Optional[str] = None

class RuntimeErrorReportRequest(BaseModel):
    task_id: str
    package_name: Optional[str] = None
    stack_trace: str
    device_id: Optional[str] = None
    user_id: Optional[str] = None
    phone_number: Optional[str] = None

class ContinueGenerateRequest(BaseModel):
    task_id: str
    user_message: str
    device_id: Optional[str] = None
    user_id: Optional[str] = None
    phone_number: Optional[str] = None

class InteractionEventRequest(BaseModel):
    task_id: Optional[str] = None
    event_type: str
    source: Optional[str] = "android_host"
    action: Optional[str] = None
    message_id: Optional[str] = None
    message_type: Optional[str] = None
    content: Optional[str] = None
    payload: Optional[Dict[str, Any]] = None
    device_id: Optional[str] = None
    user_id: Optional[str] = None
    phone_number: Optional[str] = None


def format_build_input(user_prompt: str, tool_args: Dict[str, Any]) -> str:
    build_payload = {
        "user_request": user_prompt,
        "summary": tool_args.get("summary", ""),
        "build_spec": tool_args.get("build_spec", {})
    }
    if tool_args.get("research_reason"):
        build_payload["research_reason"] = tool_args.get("research_reason", "")
    if tool_args.get("research_query"):
        build_payload["research_query"] = tool_args.get("research_query", "")
    if tool_args.get("research_results"):
        build_payload["research_results"] = tool_args.get("research_results", [])
    if tool_args.get("research_context"):
        build_payload["research_context"] = tool_args.get("research_context", {})
    return json.dumps(build_payload, ensure_ascii=False, indent=2)


def build_decision_prompt(initial_prompt: str, user_message: Optional[str] = None, task_log: str = "") -> str:
    if not user_message:
        return initial_prompt

    parts = [
        f"최초 요청:\n{initial_prompt}"
    ]

    if task_log:
        parts.append(f"현재까지의 대화 로그:\n{task_log}")

    parts.append(f"가장 최근 사용자 응답:\n{user_message}")
    parts.append("기존 대화 문맥을 바탕으로 이 작업의 다음 동작을 결정하세요.")
    return "\n\n".join(parts)


def _detect_image_conflict(user_text: str, image_analysis: Optional[Dict[str, Any]]) -> str:
    if not isinstance(image_analysis, dict):
        return ""
    color_summary = " ".join([
        str(image_analysis.get("color_style_summary") or ""),
        " ".join(image_analysis.get("text_detected") or []),
    ]).lower()
    user_lower = (user_text or "").lower()
    if any(token in user_lower for token in ["dark", "다크", "어두운"]) and any(token in color_summary for token in ["light", "밝", "white", "화이트"]):
        return "사용자 텍스트는 어두운 스타일을 원하지만, 참조 이미지는 밝은 스타일로 보입니다."
    if any(token in user_lower for token in ["light", "라이트", "밝은"]) and any(token in color_summary for token in ["dark", "다크", "black", "블랙", "어두"]):
        return "사용자 텍스트는 밝은 스타일을 원하지만, 참조 이미지는 어두운 스타일로 보입니다."
    return ""


def materialize_reference_image(
    reference_image_path: Optional[str],
    reference_image_name: Optional[str],
    reference_image_base64: Optional[str],
    task_id: Optional[str],
) -> Optional[str]:
    if isinstance(reference_image_path, str) and reference_image_path.strip():
        candidate_path = os.path.abspath(reference_image_path.strip())
        allowed_roots = [
            os.path.abspath(root)
            for root in [tempfile.gettempdir(), BUILD_ROOT_DIR]
            if isinstance(root, str) and root.strip()
        ]
        if (
            os.path.isfile(candidate_path)
            and any(os.path.commonpath([root, candidate_path]) == root for root in allowed_roots)
        ):
            return candidate_path
        return None
    if not isinstance(reference_image_base64, str) or not reference_image_base64.strip():
        return None
    safe_name = os.path.basename((reference_image_name or "reference.png").strip() or "reference.png")
    if "." not in safe_name:
        safe_name += ".png"
    suffix = os.path.splitext(safe_name)[1] or ".png"
    try:
        raw_bytes = base64.b64decode(reference_image_base64, validate=True)
    except Exception:
        return None
    fd, temp_path = tempfile.mkstemp(prefix=f"reference_{task_id or 'task'}_", suffix=suffix)
    with os.fdopen(fd, "wb") as f:
        f.write(raw_bytes)
    return temp_path


def prepare_reference_image_analysis(
    image_path: Optional[str],
    analysis_goal: str,
    task_id: Optional[str],
    reference_image_name: Optional[str] = None,
    reference_image_base64: Optional[str] = None,
) -> Optional[Dict[str, Any]]:
    materialized_path = materialize_reference_image(
        image_path,
        reference_image_name,
        reference_image_base64,
        task_id,
    )
    if not isinstance(materialized_path, str) or not materialized_path.strip():
        return None
    analysis = analyze_reference_image(materialized_path.strip(), analysis_goal, task_id=task_id)
    if analysis.get("status") != "success":
        return None
    return {
        "layout_summary": analysis.get("layout_summary") or "",
        "ui_components": analysis.get("ui_components") or [],
        "text_detected": analysis.get("text_detected") or [],
        "color_style_summary": analysis.get("color_style_summary") or "",
        "interaction_hints": analysis.get("interaction_hints") or [],
    }


def resolve_reference_image_analysis(
    *,
    task: Optional[Dict[str, Any]],
    task_id: Optional[str],
    image_path: Optional[str],
    analysis_goal: str,
    reference_image_name: Optional[str] = None,
    reference_image_base64: Optional[str] = None,
) -> tuple[Optional[Dict[str, Any]], str, bool]:
    fingerprint = build_reference_image_fingerprint(
        image_path,
        reference_image_name,
        reference_image_base64,
    )
    if task and fingerprint:
        existing_fingerprint = (task.get("reference_image_fingerprint") or "").strip()
        existing_analysis = get_reference_image_analysis(task)
        if existing_fingerprint == fingerprint and existing_analysis:
            return existing_analysis, fingerprint, True

    analysis = prepare_reference_image_analysis(
        image_path,
        analysis_goal,
        task_id,
        reference_image_name=reference_image_name,
        reference_image_base64=reference_image_base64,
    )
    return analysis, fingerprint, False


def build_continuation_prompt(conversation_state: Dict[str, Any], task_log: str = "") -> str:
    initial_prompt = conversation_state.get("initial_user_prompt", "")
    latest_questions = conversation_state.get("latest_assistant_questions", [])
    latest_reply = conversation_state.get("latest_user_reply", "")
    latest_summary = conversation_state.get("latest_summary", "")
    latest_pending_action = (conversation_state.get("latest_pending_action") or "").strip()
    latest_pending_summary = (conversation_state.get("latest_pending_summary") or "").strip()
    latest_pending_reason = (conversation_state.get("latest_pending_reason") or "").strip()

    parts = [f"최초 요청:\n{initial_prompt}"]

    if latest_summary:
        parts.append(f"현재 이해 내용:\n{latest_summary}")
    if latest_pending_action:
        parts.append(f"직전 확인 질문의 예정 동작:\n{latest_pending_action}")
    if latest_pending_summary:
        parts.append(f"직전 확인 질문의 작업 요약:\n{latest_pending_summary}")
    if latest_pending_reason:
        parts.append(f"직전 확인 질문의 판단 이유:\n{latest_pending_reason}")
    if latest_questions:
        parts.append(f"가장 최근 확인 질문:\n{json.dumps(latest_questions, ensure_ascii=False)}")
    if latest_reply:
        parts.append(f"가장 최근 사용자 답변:\n{latest_reply}")
    if task_log:
        parts.append(f"현재까지의 대화 로그:\n{task_log}")

    parts.append(
        "기존 대화 문맥을 바탕으로 이 작업의 다음 동작을 결정하세요. "
        "가장 최근 사용자 답변이 '어', '응', '네', '좋아', '그대로 진행해줘'처럼 짧은 긍정/승인 표현이고 "
        "직전 확인 질문의 예정 동작이 있으면, 새 명세가 짧다는 이유로 다시 질문하지 말고 그 예정 동작을 수행하세요."
    )
    return "\n\n".join(parts)


def build_generate_response(
    *,
    task_id: str,
    status: str,
    tool: Optional[str] = None,
    message: str = "",
    summary: str = "",
    questions: Optional[list] = None,
    missing_fields: Optional[list] = None,
    reason: str = "",
    policy_category: str = "",
    image_reference_summary: str = "",
    image_conflict_note: str = "",
) -> Dict[str, Any]:
    return {
        "task_id": task_id,
        "status": normalize_task_status(status),
        "tool": tool or "",
        "message": message or "",
        "summary": summary or "",
        "questions": questions or [],
        "missing_fields": missing_fields or [],
        "reason": reason or "",
        "policy_category": policy_category or "",
        "image_reference_summary": image_reference_summary or "",
        "image_conflict_note": image_conflict_note or "",
    }


def build_api_research_clarification_response(
    *,
    task_id: str,
    reason: str,
    research_query: str,
    user_prompt: str,
    image_reference_summary: str = "",
    image_conflict_note: str = "",
    used_external_sources: Optional[List[str]] = None,
    auth_required: bool = False,
) -> Dict[str, Any]:
    sources = [source for source in (used_external_sources or []) if isinstance(source, str) and source.strip()]
    questions = []
    if reason in {"search_failed", "fetch_failed", "quality_failed", "weak_api_reference"}:
        questions.append(
            "무료 공개 API나 공개 웹페이지 파싱 후보를 찾지 못했습니다. 사용할 공식 데이터/API URL이 있을까요?"
        )
    if auth_required:
        questions.append(
            "무키 공개 API와 웹 크롤링 대안을 먼저 확인했지만 부족했습니다. 이 기능은 사용자가 앱 안에서 API 키를 입력하는 구조로 진행해도 될까요?"
        )
    questions.append(
        "공식 데이터 출처를 제공하기 어렵다면, 외부 연동 없이 수동 입력/오프라인 데이터 기반 앱으로 좁혀서 만들까요?"
    )
    questions = questions[:3]
    summary = (
        f"'{research_query or user_prompt}' 관련 무료 공개 API와 공개 웹페이지 파싱 후보를 확인했지만, "
        "앱에 안정적으로 반영할 수 있는 출처를 확정하지 못했습니다."
    )
    update_task(
        task_id,
        status=TaskStatus.CLARIFICATION_NEEDED,
        app_name="API 정보 확인 필요",
        final_requirement_summary=summary,
        active_flow="",
        build_success=0,
    )
    update_conversation_state(
        task_id,
        latest_summary=summary,
        latest_assistant_questions=questions,
    )
    append_log(
        task_id,
        "🤔 API 정보 확인 필요\n"
        f"사유: {reason}\n"
        f"질의: {research_query}\n"
        f"출처: {json.dumps(sources[:5], ensure_ascii=False)}"
    )
    response = build_generate_response(
        task_id=task_id,
        status=TaskStatus.CLARIFICATION_NEEDED,
        tool="ask_clarification",
        message="api_research_clarification_required",
        summary=summary,
        questions=questions,
        missing_fields=["official_api_reference", "api_auth_strategy"] if auth_required else ["official_api_reference"],
        reason=reason,
        image_reference_summary=image_reference_summary,
        image_conflict_note=image_conflict_note,
    )
    record_assistant_response(
        task_id=task_id,
        message_type="api_research_clarification",
        endpoint="/generate",
        content="\n".join(questions),
        payload={
            **response,
            "research_query": research_query,
            "used_external_sources": sources[:5],
            "auth_required": auth_required,
        },
    )
    return response


def try_public_api_then_web_fallback(
    *,
    task_id: str,
    raw_user_message: str,
    user_prompt: str,
    research_query: str,
    api_source_strategy: Optional[Dict[str, Any]] = None,
    failure_reason: str = "",
) -> Optional[Dict[str, Any]]:
    strategy = dict(api_source_strategy or {})
    strategy["prefer_public_no_key"] = True
    strategy["public_api_search_query"] = (
        strategy.get("public_api_search_query")
        or f"{research_query} free public API no key official documentation"
    )
    strategy["fallback_api_search_query"] = (
        strategy.get("fallback_api_search_query")
        or f"{research_query} public API no authentication"
    )
    strategy["reason"] = (
        strategy.get("reason")
        or "무료 무키 공개 API를 먼저 확인하고, 실패하면 공개 웹페이지 파싱 가능성을 확인합니다."
    )

    append_log(
        task_id,
        "🧭 API fallback 순서 실행\n"
        "1) 무료/무키 공개 API 탐색\n"
        "2) 공개 웹페이지 크롤링 가능성 확인\n"
        "3) 둘 다 실패하면 사용자에게 출처 확인 질문"
        f"\n직전 실패: {failure_reason or 'unknown'}"
    )

    api_search_results = [
        search_api_docs(
            strategy["public_api_search_query"],
            api_name=research_query,
            task_id=task_id,
            max_results=5,
        )
    ]
    fallback_query = strategy.get("fallback_api_search_query") or ""
    if fallback_query and fallback_query != strategy["public_api_search_query"]:
        api_search_results.append(
            search_api_docs(
                fallback_query,
                api_name=research_query,
                task_id=task_id,
                max_results=5,
            )
        )
    api_search_result = _merge_search_results(*api_search_results)
    api_candidates: List[Dict[str, Any]] = []
    fetched_pages: List[Dict[str, Any]] = []
    for item in api_search_result.get("results", [])[:5]:
        candidate_url = item.get("url") or ""
        if not candidate_url:
            continue
        if looks_like_openapi_reference_url(candidate_url):
            page_result = parse_openapi_reference(candidate_url, task_id=task_id)
            source_kind = "openapi_parse"
            if page_result.get("status") != "success":
                page_result = fetch_api_reference(candidate_url, task_id=task_id)
                source_kind = "api_fetch"
        else:
            source_kind = "api_fetch" if looks_like_api_reference_url(candidate_url) else "generic_fetch"
            page_result = (
                fetch_api_reference(candidate_url, task_id=task_id)
                if source_kind == "api_fetch"
                else fetch_webpage(candidate_url, task_id=task_id)
            )
        if page_result.get("status") != "success":
            continue
        fetched_pages.append(page_result)
        quality = evaluate_research_quality(
            research_query,
            [item],
            [page_result],
            direct_fetch=False,
            task_id=task_id,
            build_spec=None,
        )
        api_candidates.append(_build_api_source_candidate(item, page_result, quality, source_kind))

    _append_deterministic_public_no_key_candidates(api_candidates, research_query, strategy)
    public_candidate = _best_public_no_key_api_candidate(api_candidates)
    if public_candidate:
        append_log(
            task_id,
            "✅ 무료/무키 공개 API fallback 선택\n"
            f"- {public_candidate.get('final_url') or public_candidate.get('url')}"
        )
        selected_page = public_candidate.get("page_result") or {}
        selected_result = public_candidate.get("search_result") or {}
        return {
            "mode": "api",
            "selected_source_payload": public_candidate,
            "supporting_sources": [candidate for candidate in api_candidates if candidate is not public_candidate],
            "fetched_pages": [selected_page] if selected_page else fetched_pages[:1],
            "research_result": {
                "status": "success",
                "query": research_query,
                "results": [selected_result] if selected_result else api_search_result.get("results", [])[:1],
                "fallback_used": "public_no_key_api",
            },
            "top_sources": [
                public_candidate.get("final_url")
                or public_candidate.get("url")
            ],
            "web_data_analyses": [],
        }

    llm_candidate, usage = propose_public_no_key_api_candidate_with_llm(
        task_id=task_id,
        raw_user_message=raw_user_message,
        user_prompt=user_prompt,
        research_query=research_query,
        api_source_strategy=strategy,
        search_error=failure_reason or "public_no_key_candidate_not_found",
    )
    log_token_usage(task_id, "Public_No_Key_API_Candidate", usage)
    if llm_candidate:
        append_log(
            task_id,
            "✅ 모델 지식 기반 무료/무키 공개 API fallback 선택\n"
            f"- {llm_candidate.get('final_url') or llm_candidate.get('url')}"
        )
        selected_page = llm_candidate.get("page_result") or {}
        selected_result = llm_candidate.get("search_result") or {}
        return {
            "mode": "api",
            "selected_source_payload": llm_candidate,
            "supporting_sources": api_candidates[:4],
            "fetched_pages": [selected_page] if selected_page else [],
            "research_result": {
                "status": "success",
                "query": research_query,
                "results": [selected_result] if selected_result else [],
                "fallback_used": "llm_public_no_key_api_candidate",
            },
            "top_sources": [llm_candidate.get("final_url") or llm_candidate.get("url")],
            "web_data_analyses": [],
        }

    web_queries = [
        f"{research_query} official data page",
        f"{research_query} public data webpage",
        user_prompt,
    ]
    web_results = [
        search_web_reference(query, task_id=task_id, max_results=5)
        for query in web_queries
        if isinstance(query, str) and query.strip()
    ]
    web_search_result = _merge_search_results(*web_results)
    web_top_sources = [
        item.get("url")
        for item in web_search_result.get("results", [])
        if isinstance(item, dict) and item.get("url")
    ]
    web_fetched_pages: List[Dict[str, Any]] = []
    web_data_analyses: List[Dict[str, Any]] = []
    seen_urls = set()
    for url in web_top_sources[:5]:
        if not url or url in seen_urls:
            continue
        seen_urls.add(url)
        page = fetch_webpage(url, task_id=task_id, max_chars=16000)
        if page.get("status") != "success":
            continue
        web_fetched_pages.append(page)
        analysis = analyze_web_data_source(
            page.get("final_url") or url,
            user_goal=f"{user_prompt}\n{research_query}",
            page_result=page,
            task_id=task_id,
        )
        web_data_analyses.append(analysis)

    selected_web_data_analysis = _best_web_data_analysis(web_data_analyses)
    if selected_web_data_analysis and selected_web_data_analysis.get("sample_records"):
        selected_url = selected_web_data_analysis.get("final_url") or selected_web_data_analysis.get("url") or ""
        selected_page = next(
            (
                page for page in web_fetched_pages
                if (page.get("final_url") or page.get("url") or "") == selected_url
            ),
            web_fetched_pages[0] if web_fetched_pages else {},
        )
        selected_source = {
            "title": selected_web_data_analysis.get("title") or selected_page.get("title") or "공개 웹 데이터 출처",
            "url": selected_url,
            "final_url": selected_url,
            "source_type": "generic_web",
            "source_kind": "web_data_analyze",
            "web_data_analysis": selected_web_data_analysis,
            "page_result": selected_page,
            "search_result": {
                "title": selected_web_data_analysis.get("title") or selected_page.get("title") or "공개 웹 데이터 출처",
                "url": selected_url,
                "snippet": json.dumps((selected_web_data_analysis.get("sample_records") or [])[:2], ensure_ascii=False)[:300],
                "source_type": "generic_web",
            },
        }
        append_log(
            task_id,
            "✅ 공개 웹페이지 크롤링 fallback 선택\n"
            f"- {selected_url}\n"
            f"- samples={len(selected_web_data_analysis.get('sample_records') or [])}"
        )
        return {
            "mode": "web_scrape",
            "selected_source_payload": selected_source,
            "supporting_sources": [],
            "fetched_pages": web_fetched_pages,
            "research_result": {
                "status": "success",
                "query": research_query,
                "results": [selected_source["search_result"]],
                "fallback_used": "web_scrape",
            },
            "top_sources": web_top_sources,
            "web_data_analyses": web_data_analyses,
        }

    append_log(
        task_id,
        "⚠️ API fallback 실패\n"
        "- 무료/무키 공개 API 후보 없음\n"
        "- 공개 웹페이지 크롤링 샘플 추출 실패"
    )
    return None


def build_task_summary_payload(task: Dict[str, Any]) -> Dict[str, Any]:
    conversation_state = get_conversation_state(task)
    progress_mode = (task.get("active_flow") or "").strip()
    current_status = normalize_task_status(task.get("status"))
    display_app_name = derive_task_display_app_name(task)
    return {
        "task_id": task.get("task_id") or "",
        "status": current_status,
        "status_display_text": get_status_display_text(task.get("status"), progress_mode),
        "generated_app_name": display_app_name,
        "package_name": task.get("package_name") or "",
        "initial_user_prompt": task.get("initial_user_prompt") or conversation_state.get("initial_user_prompt") or "",
        "apk_url": build_download_url(task) if current_status == TaskStatus.SUCCESS else (task.get("apk_url") or ""),
        "build_success": to_bool(task.get("build_success")) if task.get("build_success") is not None else False,
        "created_at": task.get("created_at") or "",
        "updated_at": task.get("updated_at") or "",
        "conversation_state": conversation_state,
    }


def build_feedback_route_context(
    task: Dict[str, Any],
    runtime_error: Optional[RuntimeErrorContext] = None,
) -> Dict[str, Any]:
    status_payload = build_status_payload(task)
    conversation_state = get_conversation_state(task)
    recent_context = summarize_retry_conversation_context(task.get("task_id") or "", limit=10)
    failure_log_lines = get_recent_failure_log_lines(task.get("log") or "", limit=8)
    return {
        "task_id": task.get("task_id") or "",
        "status": status_payload.get("status") or TaskStatus.ERROR,
        "allowed_next_actions": status_payload.get("allowed_next_actions") or [],
        "retry_allowed": bool(status_payload.get("retry_allowed")),
        "retry_block_reason": status_payload.get("retry_block_reason") or "",
        "initial_user_prompt": task.get("initial_user_prompt") or conversation_state.get("initial_user_prompt") or "",
        "latest_summary": conversation_state.get("latest_summary") or task.get("final_requirement_summary") or "",
        "latest_assistant_questions": conversation_state.get("latest_assistant_questions") or [],
        "latest_user_reply": conversation_state.get("latest_user_reply") or "",
        "final_requirement_summary": task.get("final_requirement_summary") or "",
        "final_app_spec": parse_json_object_field(task.get("final_app_spec")) or {},
        "ui_contract": get_ui_contract(task),
        "recent_conversation": recent_context.get("recent_messages") or [],
        "latest_runtime_error": recent_context.get("latest_runtime_error") or {},
        "repair_history": recent_context.get("repair_history") or [],
        "recent_failure_log_lines": failure_log_lines,
        "pending_runtime_error": {
            "package_name": (runtime_error.package_name or "").strip() if runtime_error else "",
            "summary": (runtime_error.summary or "").strip() if runtime_error else "",
            "stack_trace": (runtime_error.stack_trace or "").strip() if runtime_error else "",
            "awaiting_user_confirmation": bool(runtime_error.awaiting_user_confirmation) if runtime_error else False,
        } if runtime_error else None,
        "active_flow": (task.get("active_flow") or "").strip(),
    }


def build_feedback_route_response(
    *,
    task_id: str,
    action: str,
    target_endpoint: str,
    current_status: str,
    assistant_message: str,
    reason: str = "",
) -> Dict[str, Any]:
    return {
        "task_id": task_id,
        "action": action,
        "target_endpoint": target_endpoint,
        "current_status": normalize_task_status(current_status),
        "assistant_message": assistant_message or "",
        "reason": reason or "",
    }


def route_generate_decision(
    *,
    task_id: str,
    user_prompt: str,
    raw_user_message: str,
    device_info: Optional[Dict[str, Any]],
    background_tasks: BackgroundTasks,
    reference_image_analysis: Optional[Dict[str, Any]] = None,
    image_conflict_note: str = "",
    image_reference_summary: str = "",
):
    decision, usage, error = decide_generate_action(
        user_prompt,
        device_context=device_info,
        task_id=task_id,
        reference_image_analysis=reference_image_analysis,
        image_reference_summary=image_reference_summary,
        image_conflict_note=image_conflict_note,
    )
    log_token_usage(task_id, "Generate_Decision", usage)

    if error or not decision:
        update_task(
            task_id,
            status=TaskStatus.ERROR,
            app_name="판단 실패"
        )
        append_log(task_id, f"🚨 빌드 전 판단 단계 실패: {error or '알 수 없는 오류'}")
        response = build_generate_response(
            task_id=task_id,
            status=TaskStatus.ERROR,
            message=error or "빌드 전 판단 단계에 실패했습니다.",
            image_reference_summary=image_reference_summary,
            image_conflict_note=image_conflict_note,
        )
        record_assistant_response(
            task_id=task_id,
            message_type="generate_decision_error",
            endpoint="/generate",
            content=response["message"],
            payload=response,
        )
        return response

    decision = sanitize_reference_image_clarification(decision, reference_image_analysis)
    decision = maybe_force_external_research_decision(
        decision,
        user_prompt=user_prompt,
        raw_user_message=raw_user_message,
        task_id=task_id,
        token_callback=log_token_usage,
    )
    tool_name = decision["tool"]
    tool_args = decision["arguments"]
    summary_text = tool_args.get("summary") or ""
    structured_summary = {
        "summary": summary_text,
        "missing_fields": tool_args.get("missing_fields"),
        "policy_category": tool_args.get("policy_category"),
        "message": tool_args.get("message"),
        "assistant_message": tool_args.get("assistant_message"),
        "question": tool_args.get("question"),
        "assumed_action": tool_args.get("assumed_action"),
        "build_spec": tool_args.get("build_spec"),
    }
    log_decision_event(
        task_id,
        event_type="decision_made",
        decision_type=tool_name,
        tool_name=tool_name,
        tool_args=tool_args,
        raw_user_message=raw_user_message,
        structured_summary=structured_summary,
    )

    if tool_name == "ask_clarification":
        summary = summary_text
        questions = tool_args["questions"]
        update_task(
            task_id,
            status=TaskStatus.CLARIFICATION_NEEDED,
            app_name="추가 확인 필요",
            final_requirement_summary=summary
        )
        update_conversation_state(
            task_id,
            latest_summary=summary,
            latest_assistant_questions=questions,
            latest_pending_action="",
            latest_pending_summary="",
            latest_pending_reason="",
        )
        append_log(task_id, f"🤔 추가 확인 필요\n요약: {summary}\n질문: {json.dumps(questions, ensure_ascii=False)}")
        response = build_generate_response(
            task_id=task_id,
            status=TaskStatus.CLARIFICATION_NEEDED,
            tool=tool_name,
            message="clarification_required",
            summary=summary,
            questions=questions,
            missing_fields=tool_args.get("missing_fields", []),
            image_reference_summary=image_reference_summary,
            image_conflict_note=image_conflict_note,
        )
        record_assistant_response(
            task_id=task_id,
            message_type="clarification_question",
            endpoint="/generate",
            content="\n".join(questions),
            payload=response,
        )
        return response

    if tool_name == "answer_question":
        assistant_message = (tool_args.get("assistant_message") or "").strip()
        update_task(
            task_id,
            status=TaskStatus.PENDING_DECISION,
            app_name="대화 중",
            active_flow="",
        )
        update_conversation_state(
            task_id,
            latest_pending_action="",
            latest_pending_summary="",
            latest_pending_reason="",
        )
        append_log(task_id, "💬 질문에 답변했습니다.")
        response = build_generate_response(
            task_id=task_id,
            status=TaskStatus.PENDING_DECISION,
            tool=tool_name,
            message=assistant_message,
            summary=summary_text,
            reason=tool_args.get("reason") or "",
            image_reference_summary=image_reference_summary,
            image_conflict_note=image_conflict_note,
        )
        record_assistant_response(
            task_id=task_id,
            message_type="assistant_answer",
            endpoint="/generate",
            content=assistant_message,
            payload=response,
        )
        return response

    if tool_name == "ask_confirmation":
        question = (tool_args.get("question") or "").strip()
        summary = (tool_args.get("summary") or summary_text or question).strip()
        update_task(
            task_id,
            status=TaskStatus.CLARIFICATION_NEEDED,
            app_name="확인 필요",
            final_requirement_summary=summary,
            active_flow="",
        )
        update_conversation_state(
            task_id,
            latest_summary=summary,
            latest_assistant_questions=[question],
            latest_pending_action=tool_args.get("assumed_action") or "",
            latest_pending_summary=summary,
            latest_pending_reason=tool_args.get("reason") or "",
        )
        append_log(task_id, f"🤔 실행 전 확인 필요\n질문: {question}")
        response = build_generate_response(
            task_id=task_id,
            status=TaskStatus.CLARIFICATION_NEEDED,
            tool=tool_name,
            message=question,
            summary=summary,
            questions=[question],
            reason=tool_args.get("reason") or "",
            image_reference_summary=image_reference_summary,
            image_conflict_note=image_conflict_note,
        )
        response["assumed_action"] = tool_args.get("assumed_action") or ""
        record_assistant_response(
            task_id=task_id,
            message_type="confirmation_question",
            endpoint="/generate",
            content=question,
            payload=response,
        )
        return response

    if tool_name == "reject_request":
        update_task(
            task_id,
            status=TaskStatus.REJECTED,
            app_name="거절됨",
            final_requirement_summary=tool_args["message"],
            build_success=0
        )
        update_conversation_state(
            task_id,
            latest_summary=tool_args["message"],
            latest_pending_action="",
            latest_pending_summary="",
            latest_pending_reason="",
        )
        append_log(task_id, f"⛔ 요청 거절\n사유: {tool_args['reason']}\n안내: {tool_args['message']}")
        response = build_generate_response(
            task_id=task_id,
            status=TaskStatus.REJECTED,
            tool=tool_name,
            message=tool_args["message"],
            summary=tool_args["message"],
            reason=tool_args["reason"],
            policy_category=tool_args["policy_category"],
            image_reference_summary=image_reference_summary,
            image_conflict_note=image_conflict_note,
        )
        record_assistant_response(
            task_id=task_id,
            message_type="request_rejected",
            endpoint="/generate",
            content=tool_args["message"],
            payload=response,
        )
        return response

    if tool_name == "research_then_build":
        research_query = (tool_args.get("research_query") or "").strip()
        research_reason = (tool_args.get("research_reason") or "").strip()
        direct_fetch = looks_like_url(research_query)
        api_reference_fetch = direct_fetch and looks_like_api_reference_url(research_query)
        openapi_reference_fetch = direct_fetch and looks_like_openapi_reference_url(research_query)
        api_search = (not direct_fetch) and looks_like_api_integration_request(user_prompt, research_query)
        api_source_strategy: Dict[str, Any] = {}
        intent_build_spec = normalize_runtime_build_spec(tool_args.get("build_spec") or {})
        intent_source_constraints, source_constraint_usage, source_constraint_error = extract_source_selection_constraints_with_llm(
            raw_user_message or user_prompt,
            task_id=task_id,
            fallback_existing=(
                intent_build_spec.get("source_selection_constraints")
                if isinstance(intent_build_spec.get("source_selection_constraints"), dict)
                else {}
            ),
        )
        log_token_usage(task_id, "Source_Constraint_Extractor", source_constraint_usage)
        if source_constraint_error:
            append_log(task_id, f"⚠️ 출처 제약 추출 fallback 사용: {source_constraint_error}")
        intent_build_spec["source_selection_constraints"] = intent_source_constraints
        tool_args["build_spec"] = intent_build_spec
        if api_search:
            api_source_strategy, api_source_strategy_usage = choose_api_source_strategy_with_llm(
                task_id=task_id,
                raw_user_message=raw_user_message,
                user_prompt=user_prompt,
                research_query=research_query,
                tool_args=tool_args,
            )
            log_token_usage(task_id, "API_Source_Strategy", api_source_strategy_usage)
        top_sources: List[str] = []
        append_log(task_id, f"🔎 외부 정보 탐색 시작\n질의: {research_query}\n사유: {research_reason}")

        fetched_pages: List[Dict[str, Any]] = []
        selected_source_payload: Optional[Dict[str, Any]] = None
        supporting_sources: List[Dict[str, Any]] = []
        web_data_analyses: List[Dict[str, Any]] = []
        fallback_mode = ""

        def apply_public_api_or_web_fallback(reason: str) -> bool:
            nonlocal research_result, top_sources, fetched_pages, selected_source_payload
            nonlocal supporting_sources, web_data_analyses, fallback_mode
            fallback = try_public_api_then_web_fallback(
                task_id=task_id,
                raw_user_message=raw_user_message,
                user_prompt=user_prompt,
                research_query=research_query,
                api_source_strategy=api_source_strategy,
                failure_reason=reason,
            )
            if not fallback:
                return False
            research_result = fallback.get("research_result") or {
                "status": "success",
                "query": research_query,
                "results": [],
            }
            top_sources = [
                url for url in (fallback.get("top_sources") or [])
                if isinstance(url, str) and url.strip()
            ]
            fetched_pages = [
                page for page in (fallback.get("fetched_pages") or [])
                if isinstance(page, dict)
            ]
            selected_source_payload = fallback.get("selected_source_payload")
            supporting_sources = [
                source for source in (fallback.get("supporting_sources") or [])
                if isinstance(source, dict)
            ]
            web_data_analyses = [
                analysis for analysis in (fallback.get("web_data_analyses") or [])
                if isinstance(analysis, dict)
            ]
            fallback_mode = fallback.get("mode") or ""
            return bool(selected_source_payload)

        if direct_fetch:
            if openapi_reference_fetch:
                page_result = parse_openapi_reference(research_query, task_id=task_id)
                if page_result.get("status") != "success":
                    page_result = fetch_api_reference(research_query, task_id=task_id)
            else:
                page_result = (
                    fetch_api_reference(research_query, task_id=task_id)
                    if api_reference_fetch
                    else fetch_webpage(research_query, task_id=task_id)
                )
            if page_result.get("status") != "success":
                fetch_error = page_result.get("error") or "웹 페이지를 읽지 못했습니다."
                append_log(task_id, f"🚨 웹 페이지 읽기 실패: {fetch_error}")
                fallback_applied = apply_public_api_or_web_fallback("fetch_failed")
                if fallback_applied:
                    append_log(task_id, "🔁 직접 URL 실패 후 fallback 출처로 계속 진행합니다.")
                elif api_reference_fetch or openapi_reference_fetch or looks_like_api_integration_request(user_prompt, research_query):
                    return build_api_research_clarification_response(
                        task_id=task_id,
                        reason="fetch_failed",
                        research_query=research_query,
                        user_prompt=user_prompt,
                        image_reference_summary=image_reference_summary,
                        image_conflict_note=image_conflict_note,
                        used_external_sources=[research_query],
                    )
                elif not fallback_applied:
                    update_task(
                        task_id,
                        status=TaskStatus.ERROR,
                        app_name="웹 페이지 읽기 실패",
                        build_success=0,
                    )
                    response = build_generate_response(
                        task_id=task_id,
                        status=TaskStatus.ERROR,
                        tool=tool_name,
                        message=f"웹 페이지 읽기 단계에 실패했습니다. {fetch_error}",
                        image_reference_summary=image_reference_summary,
                        image_conflict_note=image_conflict_note,
                    )
                    record_assistant_response(task_id=task_id, message_type="web_research_error", endpoint="/generate", content=response["message"], payload={**response, "research_query": research_query})
                    return response
            if page_result.get("status") == "success":
                fetched_pages.append(page_result)
                research_result = {
                    "status": "success",
                    "query": research_query,
                    "results": [{
                        "title": page_result.get("title") or research_query,
                        "url": page_result.get("final_url") or research_query,
                        "snippet": (page_result.get("text_content") or "")[:300],
                    }],
                }
                top_sources = [page_result.get("final_url") or research_query]
                append_log(
                    task_id,
                    f"{'🧩 OpenAPI 명세 확보' if openapi_reference_fetch else ('🧾 API 레퍼런스 확보' if api_reference_fetch else '📄 웹 페이지 확보')}\n- {page_result.get('final_url') or research_query}"
                )
                selected_source_payload = {
                    "title": page_result.get("title") or research_query,
                    "url": page_result.get("final_url") or research_query,
                    "final_url": page_result.get("final_url") or research_query,
                    "source_type": "api_reference" if (api_reference_fetch or openapi_reference_fetch) else "generic_web",
                    "source_kind": "openapi_parse" if openapi_reference_fetch else ("api_fetch" if api_reference_fetch else "generic_fetch"),
                    "page_result": page_result,
                    "search_result": research_result["results"][0],
                }
        else:
            if api_search:
                api_search_results: List[Dict[str, Any]] = []
                public_api_query = (api_source_strategy.get("public_api_search_query") or "").strip()
                fallback_api_query = (api_source_strategy.get("fallback_api_search_query") or "").strip()
                if api_source_strategy.get("prefer_public_no_key") and public_api_query:
                    append_log(
                        task_id,
                        "🔓 무키 공개 API 우선 탐색\n"
                        f"- query={public_api_query}\n"
                        f"- reason={api_source_strategy.get('reason') or 'public_no_key_preferred'}"
                    )
                    api_search_results.append(
                        search_api_docs(public_api_query, api_name=research_query, task_id=task_id, max_results=5)
                    )
                if fallback_api_query and fallback_api_query != public_api_query:
                    api_search_results.append(
                        search_api_docs(fallback_api_query, api_name=research_query, task_id=task_id, max_results=5)
                    )
                if not api_search_results:
                    api_search_results.append(
                        search_api_docs(research_query, api_name=research_query, task_id=task_id, max_results=5)
                    )
                research_result = _merge_search_results(*api_search_results)
            else:
                research_result = search_web_reference(research_query, task_id=task_id, max_results=5)
            if research_result.get("status") != "success" or not research_result.get("results"):
                search_error = research_result.get("error") or "검색 결과를 찾지 못했습니다."
                append_log(task_id, f"🚨 웹 검색 실패: {search_error}")
                if api_search:
                    selected_source_payload, public_api_candidate_usage = propose_public_no_key_api_candidate_with_llm(
                        task_id=task_id,
                        raw_user_message=raw_user_message,
                        user_prompt=user_prompt,
                        research_query=research_query,
                        api_source_strategy=api_source_strategy,
                        search_error=search_error,
                    )
                    log_token_usage(task_id, "Public_No_Key_API_Candidate", public_api_candidate_usage)
                    if selected_source_payload:
                        selected_page = selected_source_payload.get("page_result") or {}
                        selected_result = selected_source_payload.get("search_result") or {}
                        fetched_pages = [selected_page] if selected_page else []
                        top_sources = [
                            selected_source_payload.get("final_url")
                            or selected_source_payload.get("url")
                        ]
                        research_result = {
                            "status": "success",
                            "query": research_query,
                            "results": [selected_result] if selected_result else [],
                            "fallback_used": "llm_public_no_key_api_candidate",
                        }
                        append_log(
                            task_id,
                            "🧭 검색 실패 fallback 적용\n"
                            f"- source={selected_source_payload.get('title')}\n"
                            f"- url={selected_source_payload.get('final_url') or selected_source_payload.get('url')}\n"
                            "- auth=public_no_key"
                        )
                    else:
                        fallback_applied = apply_public_api_or_web_fallback("search_failed")
                        if not fallback_applied:
                            return build_api_research_clarification_response(
                                task_id=task_id,
                                reason="search_failed",
                                research_query=research_query,
                                user_prompt=user_prompt,
                                image_reference_summary=image_reference_summary,
                                image_conflict_note=image_conflict_note,
                            )
                if not selected_source_payload:
                    update_task(
                        task_id,
                        status=TaskStatus.ERROR,
                        app_name="검색 실패",
                        build_success=0,
                    )
                    response = build_generate_response(
                        task_id=task_id,
                        status=TaskStatus.ERROR,
                        tool=tool_name,
                        message=f"웹 검색 단계에 실패했습니다. {search_error}",
                        image_reference_summary=image_reference_summary,
                        image_conflict_note=image_conflict_note,
                    )
                    record_assistant_response(
                        task_id=task_id,
                        message_type="web_research_error",
                        endpoint="/generate",
                        content=response["message"],
                        payload={
                            **response,
                            "research_query": research_query,
                        },
                    )
                    return response

            top_sources = [item.get("url") for item in research_result.get("results", []) if item.get("url")]
            append_log(
                task_id,
                f"{'🧾 API 문서 검색 결과 확보' if api_search else '🌐 웹 검색 결과 확보'}\n"
                + "\n".join([f"- {url}" for url in top_sources[:3]])
            )
            if api_search and top_sources and not selected_source_payload:
                api_candidates: List[Dict[str, Any]] = []
                fetch_limit = min(3, len(research_result.get("results", [])))
                for item in research_result.get("results", [])[:fetch_limit]:
                    candidate_url = item.get("url") or ""
                    if not candidate_url:
                        continue
                    source_kind = "generic_fetch"
                    if looks_like_openapi_reference_url(candidate_url):
                        page_result = parse_openapi_reference(candidate_url, task_id=task_id)
                        source_kind = "openapi_parse"
                        if page_result.get("status") != "success":
                            page_result = fetch_api_reference(candidate_url, task_id=task_id)
                            source_kind = "api_fetch"
                    else:
                        source_kind = "api_fetch" if looks_like_api_reference_url(candidate_url) else "generic_fetch"
                        page_result = (
                            fetch_api_reference(candidate_url, task_id=task_id)
                            if source_kind == "api_fetch"
                            else fetch_webpage(candidate_url, task_id=task_id)
                        )
                    if page_result.get("status") != "success":
                        continue
                    candidate_quality = evaluate_research_quality(
                        research_query,
                        [item],
                        [page_result],
                        direct_fetch=False,
                        task_id=task_id,
                        build_spec=tool_args.get("build_spec") or {},
                    )
                    api_candidates.append(
                        _build_api_source_candidate(item, page_result, candidate_quality, source_kind)
                    )

                _append_deterministic_public_no_key_candidates(
                    api_candidates,
                    research_query,
                    api_source_strategy,
                )
                public_no_key_candidate = _best_public_no_key_api_candidate(api_candidates)
                if api_source_strategy.get("prefer_public_no_key") and public_no_key_candidate:
                    selected_source_payload = public_no_key_candidate
                    supporting_sources = [
                        candidate for candidate in api_candidates
                        if candidate is not public_no_key_candidate
                    ]
                    selection = {
                        "selection_reason": "공식 문서 품질을 통과했고 인증 요구가 감지되지 않아 무키 공개 API 후보를 우선 선택했습니다.",
                        "confidence": _candidate_quality_score(public_no_key_candidate),
                    }
                else:
                    selection = select_best_api_source(
                        research_query,
                        api_candidates,
                        task_id=task_id,
                        build_spec=tool_args.get("build_spec") or {},
                    )
                    selected_source_payload = selection.get("selected_source")
                    supporting_sources = [
                        item for item in selection.get("rejected_sources", [])
                        if isinstance(item, dict)
                    ]
                if selected_source_payload:
                    selected_page = selected_source_payload.get("page_result") or {}
                    selected_result = selected_source_payload.get("search_result") or {}
                    fetched_pages = [selected_page] if selected_page else []
                    research_result = {
                        **research_result,
                        "results": [selected_result] if selected_result else research_result.get("results", [])[:1],
                    }
                    append_log(
                        task_id,
                        f"{'🔓 무키 공개 API 소스 선택' if public_no_key_candidate is selected_source_payload else '🎯 대표 API 소스 선택'}\n"
                        f"- {selected_source_payload.get('final_url') or selected_source_payload.get('url')}\n"
                        f"- 사유: {selection.get('selection_reason')}\n"
                        f"- 신뢰도: {selection.get('confidence')}"
                    )
                else:
                    selected_source_payload, public_api_candidate_usage = propose_public_no_key_api_candidate_with_llm(
                        task_id=task_id,
                        raw_user_message=raw_user_message,
                        user_prompt=user_prompt,
                        research_query=research_query,
                        api_source_strategy=api_source_strategy,
                        search_error="quality_passing_source_not_found",
                    )
                    log_token_usage(task_id, "Public_No_Key_API_Candidate", public_api_candidate_usage)
                    if selected_source_payload:
                        selected_page = selected_source_payload.get("page_result") or {}
                        selected_result = selected_source_payload.get("search_result") or {}
                        fetched_pages = [selected_page] if selected_page else []
                        research_result = {
                            **research_result,
                            "results": [selected_result] if selected_result else research_result.get("results", [])[:1],
                            "fallback_used": "llm_public_no_key_api_candidate",
                        }
                        append_log(
                            task_id,
                            "🧭 API 소스 선택 fallback 적용\n"
                            f"- source={selected_source_payload.get('title')}\n"
                            f"- url={selected_source_payload.get('final_url') or selected_source_payload.get('url')}\n"
                            "- auth=public_no_key"
                        )
                    else:
                        fetched_pages = []
            elif top_sources:
                if looks_like_openapi_reference_url(top_sources[0]):
                    page_result = parse_openapi_reference(top_sources[0], task_id=task_id)
                    if page_result.get("status") != "success":
                        page_result = fetch_api_reference(top_sources[0], task_id=task_id)
                else:
                    page_result = (
                        fetch_api_reference(top_sources[0], task_id=task_id)
                        if api_search or looks_like_api_reference_url(top_sources[0])
                        else fetch_webpage(top_sources[0], task_id=task_id)
                    )
                if page_result.get("status") == "success":
                    fetched_pages.append(page_result)
                    selected_source_payload = {
                        "title": page_result.get("title") or top_sources[0],
                        "url": top_sources[0],
                        "final_url": page_result.get("final_url") or top_sources[0],
                        "source_type": research_result.get("results", [{}])[0].get("source_type") or ("api_reference" if (api_search or looks_like_api_reference_url(top_sources[0])) else "generic_web"),
                        "source_kind": "openapi_parse" if looks_like_openapi_reference_url(top_sources[0]) else ("api_fetch" if (api_search or looks_like_api_reference_url(top_sources[0])) else "generic_fetch"),
                        "page_result": page_result,
                        "search_result": research_result.get("results", [{}])[0],
                    }
                    append_log(
                        task_id,
                        f"{'🧩 대표 OpenAPI 명세 확인' if looks_like_openapi_reference_url(top_sources[0]) else ('🧾 대표 API 레퍼런스 확인' if (api_search or looks_like_api_reference_url(top_sources[0])) else '📄 대표 웹 페이지 확인')}\n- {page_result.get('final_url') or top_sources[0]}"
                    )

        runtime_web_data_request = (
            (looks_like_runtime_web_data_request(user_prompt, research_query) and not api_search)
            or fallback_mode == "web_scrape"
        )
        if runtime_web_data_request:
            analysis_pages: List[Dict[str, Any]] = list(fetched_pages)
            fetched_page_urls = {
                (page.get("final_url") or "").strip()
                for page in analysis_pages
                if isinstance(page, dict)
            }
            for extra_url in top_sources[:3]:
                if not extra_url or extra_url in fetched_page_urls:
                    continue
                extra_page = fetch_webpage(extra_url, task_id=task_id, max_chars=16000)
                if extra_page.get("status") == "success":
                    fetched_pages.append(extra_page)
                    analysis_pages.append(extra_page)
                    fetched_page_urls.add((extra_page.get("final_url") or extra_url).strip())

            for page in analysis_pages[:4]:
                source_url = page.get("final_url") or page.get("url") or research_query
                analysis = analyze_web_data_source(
                    source_url,
                    user_goal=f"{user_prompt}\n{research_query}",
                    page_result=page,
                    task_id=task_id,
                )
                web_data_analyses.append(analysis)

            selected_web_data_analysis = _best_web_data_analysis(web_data_analyses)
            if selected_web_data_analysis:
                append_log(
                    task_id,
                    "🧬 웹 데이터 구조 분석\n"
                    f"- source={selected_web_data_analysis.get('source_kind')}\n"
                    f"- samples={len(selected_web_data_analysis.get('sample_records') or [])}\n"
                    f"- url={selected_web_data_analysis.get('final_url') or selected_web_data_analysis.get('url')}"
                )
                if selected_web_data_analysis.get("sample_records"):
                    selected_source_payload = {
                        "title": selected_web_data_analysis.get("title") or "웹 데이터 출처",
                        "url": selected_web_data_analysis.get("url") or selected_web_data_analysis.get("final_url") or "",
                        "final_url": selected_web_data_analysis.get("final_url") or selected_web_data_analysis.get("url") or "",
                        "source_type": "generic_web",
                        "source_kind": "web_data_analyze",
                        "web_data_analysis": selected_web_data_analysis,
                        "page_result": next(
                            (
                                page for page in fetched_pages
                                if (page.get("final_url") or "") == (selected_web_data_analysis.get("final_url") or "")
                            ),
                            fetched_pages[0] if fetched_pages else {},
                        ),
                        "search_result": {
                            "title": selected_web_data_analysis.get("title") or "웹 데이터 출처",
                            "url": selected_web_data_analysis.get("final_url") or selected_web_data_analysis.get("url") or "",
                            "snippet": json.dumps((selected_web_data_analysis.get("sample_records") or [])[:2], ensure_ascii=False)[:300],
                            "source_type": "generic_web",
                        },
                    }
            if not selected_web_data_analysis or not selected_web_data_analysis.get("sample_records"):
                reason_text = (
                    (selected_web_data_analysis or {}).get("failure_reason")
                    or "web_data_sample_missing"
                )
                update_task(
                    task_id,
                    status=TaskStatus.ERROR,
                    app_name="웹 데이터 파싱 실패",
                    build_success=0,
                )
                response = build_generate_response(
                    task_id=task_id,
                    status=TaskStatus.ERROR,
                    tool=tool_name,
                    message=(
                        "웹페이지는 확인했지만 앱에서 사용할 샘플 데이터를 서버가 추출하지 못했습니다. "
                        f"실제 데이터가 iframe, JavaScript, 별도 API 뒤에 있을 수 있습니다. ({reason_text})"
                    ),
                    image_reference_summary=image_reference_summary,
                    image_conflict_note=image_conflict_note,
                )
                record_assistant_response(
                    task_id=task_id,
                    message_type="web_data_parse_error",
                    endpoint="/generate",
                    content=response["message"],
                    payload={
                        **response,
                        "research_query": research_query,
                        "web_data_analyses": web_data_analyses,
                    },
                )
                return response

        quality = evaluate_research_quality(
            research_query,
            research_result.get("results", []),
            fetched_pages,
            direct_fetch=direct_fetch,
            task_id=task_id,
            build_spec=tool_args.get("build_spec") or {},
        )
        http_probe_result: Optional[Dict[str, Any]] = None
        if quality.get("research_quality_passed") and selected_source_payload:
            probe_target = _build_safe_probe_target(selected_source_payload)
            if probe_target:
                http_probe_result = test_http_request(
                    probe_target["method"],
                    probe_target["url"],
                    headers={},
                    query_params={},
                    allowed_source=selected_source_payload,
                    task_id=task_id,
                )
                if http_probe_result.get("success"):
                    append_log(
                        task_id,
                        "🔬 공개 API GET 확인 성공\n"
                        f"- {http_probe_result.get('final_url')}\n"
                        f"- status={http_probe_result.get('status_code')}\n"
                        f"- type={http_probe_result.get('response_content_type') or 'unknown'}"
                    )
                elif http_probe_result.get("blocked_reason"):
                    append_log(
                        task_id,
                        "🛡 HTTP probe 차단 또는 실패\n"
                        f"- {probe_target['url']}\n"
                        f"- reason={http_probe_result.get('blocked_reason')}"
                    )
        persist_grounding_metadata(
            task_id,
            build_grounding_metadata(
                existing_metadata=get_grounding_metadata(get_task(task_id) or {}),
                flow_type="generate",
                endpoint="/generate",
                web_sources=_collect_urls_from_sources(
                    [item for item in [selected_source_payload, *supporting_sources] if isinstance(item, dict)],
                    source_types={"generic_web"},
                ),
                api_sources=_collect_urls_from_sources(
                    [item for item in [selected_source_payload, *supporting_sources] if isinstance(item, dict)],
                    source_types={"official_docs", "api_reference", "developer_portal"},
                ),
                openapi_sources=_collect_urls_from_sources(
                    [item for item in [selected_source_payload, *supporting_sources] if isinstance(item, dict)],
                    source_kinds={"openapi_parse"},
                ),
                image_reference_summary=image_reference_summary,
                http_probe=http_probe_result,
                selected_source_type=(selected_source_payload or {}).get("source_type") or "",
                research_quality_passed=quality.get("research_quality_passed"),
                research_quality_reason=quality.get("research_quality_reason") or "",
            ),
            endpoint="/generate",
            event_type="generate_grounding_researched",
            stage="research_then_build",
        )
        append_log(
            task_id,
            f"🧪 외부 정보 품질 {'통과' if quality.get('research_quality_passed') else '실패'}\n"
            f"사유: {quality.get('research_quality_reason')}\n"
            f"출처: {json.dumps(quality.get('used_external_sources', []), ensure_ascii=False)}"
        )
        if not quality.get("research_quality_passed"):
            if api_search or api_reference_fetch or openapi_reference_fetch:
                fallback_applied = apply_public_api_or_web_fallback("quality_failed")
                if fallback_applied:
                    runtime_web_data_request = fallback_mode == "web_scrape"
                    quality = evaluate_research_quality(
                        research_query,
                        research_result.get("results", []),
                        fetched_pages,
                        direct_fetch=False,
                        task_id=task_id,
                        build_spec=tool_args.get("build_spec") or {},
                    )
                    append_log(
                        task_id,
                        f"🧪 fallback 외부 정보 품질 {'통과' if quality.get('research_quality_passed') else '실패'}\n"
                        f"사유: {quality.get('research_quality_reason')}"
                    )
                if not quality.get("research_quality_passed"):
                    return build_api_research_clarification_response(
                        task_id=task_id,
                        reason="quality_failed",
                        research_query=research_query,
                        user_prompt=user_prompt,
                        image_reference_summary=image_reference_summary,
                        image_conflict_note=image_conflict_note,
                        used_external_sources=quality.get("used_external_sources", []),
                    )
            if not quality.get("research_quality_passed"):
                update_task(
                    task_id,
                    status=TaskStatus.ERROR,
                    app_name="외부 정보 품질 부족",
                    build_success=0,
                )
                reason_text = quality.get("research_quality_reason") or "research_quality_failed"
                response = build_generate_response(
                    task_id=task_id,
                    status=TaskStatus.ERROR,
                    tool=tool_name,
                    message=f"외부 참고 정보의 관련성이나 본문 품질이 충분하지 않아 앱 설계를 진행하지 않았습니다. ({reason_text})",
                    image_reference_summary=image_reference_summary,
                    image_conflict_note=image_conflict_note,
                )
                record_assistant_response(
                    task_id=task_id,
                    message_type="web_research_quality_error",
                    endpoint="/generate",
                    content=response["message"],
                    payload={
                        **response,
                        "research_quality_passed": quality.get("research_quality_passed"),
                        "research_quality_reason": quality.get("research_quality_reason"),
                        "used_external_sources": quality.get("used_external_sources", []),
                    },
                )
                return response

        auth_required = _source_has_auth_requirement(selected_source_payload)
        auth_resolution: Dict[str, Any] = {}
        if auth_required:
            auth_resolution, auth_resolution_usage = assess_api_auth_resolution_with_llm(
                task_id=task_id,
                raw_user_message=raw_user_message,
                user_prompt=user_prompt,
                tool_args=tool_args,
                selected_source_payload=selected_source_payload,
                quality=quality,
            )
            log_token_usage(task_id, "API_Auth_Resolution", auth_resolution_usage)

        auth_handling = (auth_resolution.get("api_key_handling") or "").strip()
        auth_needs_clarification = (
            auth_required
            and (
                not auth_resolution.get("api_auth_resolved")
                or auth_handling in {"server_proxy_required", "unknown"}
            )
        )
        if (api_search or api_reference_fetch or openapi_reference_fetch) and auth_needs_clarification:
            fallback_applied = apply_public_api_or_web_fallback("auth_required")
            if fallback_applied:
                runtime_web_data_request = fallback_mode == "web_scrape"
                auth_required = _source_has_auth_requirement(selected_source_payload)
                auth_resolution = {}
                quality = evaluate_research_quality(
                    research_query,
                    research_result.get("results", []),
                    fetched_pages,
                    direct_fetch=False,
                    task_id=task_id,
                    build_spec=tool_args.get("build_spec") or {},
                )
                append_log(
                    task_id,
                    f"🔁 인증 필요 소스 대신 fallback 출처 사용\n"
                    f"- mode={fallback_mode or 'unknown'}\n"
                    f"- auth_required={auth_required}"
                )
                if not quality.get("research_quality_passed"):
                    return build_api_research_clarification_response(
                        task_id=task_id,
                        reason="quality_failed",
                        research_query=research_query,
                        user_prompt=user_prompt,
                        image_reference_summary=image_reference_summary,
                        image_conflict_note=image_conflict_note,
                        used_external_sources=quality.get("used_external_sources", []),
                        auth_required=auth_required,
                    )
            if auth_required:
                return build_api_research_clarification_response(
                    task_id=task_id,
                    reason="auth_required",
                    research_query=research_query,
                    user_prompt=user_prompt,
                    image_reference_summary=image_reference_summary,
                    image_conflict_note=image_conflict_note,
                    used_external_sources=quality.get("used_external_sources", []),
                    auth_required=True,
                )
        if auth_required and auth_resolution.get("api_auth_resolved"):
            current_build_spec = normalize_runtime_build_spec(tool_args.get("build_spec") or {})
            auth_updates = auth_resolution.get("build_spec_updates")
            if isinstance(auth_updates, dict):
                current_build_spec.update(auth_updates)
            current_build_spec["api_key_handling"] = (
                current_build_spec.get("api_key_handling")
                or auth_resolution.get("api_key_handling")
                or "unknown"
            )
            current_build_spec["api_auth_strategy"] = (
                current_build_spec.get("api_auth_strategy")
                or auth_resolution.get("api_key_handling")
                or "unknown"
            )
            if current_build_spec.get("api_key_handling") == "user_provided_in_app":
                current_build_spec["requires_api_key_input_screen"] = True
                current_build_spec["secret_storage_policy"] = "store_user_provided_key_locally_never_hardcode"
                current_build_spec["api_key_error_handling_required"] = True
            tool_args["build_spec"] = current_build_spec

        researched_build, research_usage, research_error = synthesize_researched_build(
            user_prompt,
            {
                "decision_summary": tool_args.get("summary") or "",
                "research_query": research_query,
                "research_reason": research_reason,
                "api_source_strategy": api_source_strategy,
                "results": research_result.get("results", []),
                "fetched_pages": fetched_pages,
                "openapi_references": [
                    page for page in fetched_pages
                    if isinstance(page, dict) and (
                        page.get("servers") or page.get("endpoints") or page.get("auth_schemes")
                    )
                ],
                "research_quality_passed": quality.get("research_quality_passed"),
                "research_quality_reason": quality.get("research_quality_reason"),
                "used_external_sources": quality.get("used_external_sources", []),
                "selected_source": selected_source_payload,
                "supporting_sources": supporting_sources[:5],
                "web_data_analyses": web_data_analyses,
                "selected_web_data_analysis": _best_web_data_analysis(web_data_analyses),
                "http_probe": http_probe_result,
            },
            device_context=device_info,
            task_id=task_id,
        )
        log_token_usage(task_id, "Research_Build_Synthesizer", research_usage)

        if research_error or not researched_build:
            update_task(
                task_id,
                status=TaskStatus.ERROR,
                app_name="검색 해석 실패",
                build_success=0,
            )
            append_log(task_id, f"🚨 검색 결과 해석 실패: {research_error or '알 수 없는 오류'}")
            response = build_generate_response(
                task_id=task_id,
                status=TaskStatus.ERROR,
                tool=tool_name,
                message=research_error or "검색 결과를 앱 설계 정보로 변환하지 못했습니다.",
                image_reference_summary=image_reference_summary,
                image_conflict_note=image_conflict_note,
            )
            record_assistant_response(
                task_id=task_id,
                message_type="research_build_error",
                endpoint="/generate",
                content=response["message"],
                payload=response,
            )
            return response

        enriched_build_spec = _enrich_build_spec_with_selected_source(
            _enrich_build_spec_with_web_data_contract(
                researched_build.get("build_spec") or {},
                _best_web_data_analysis(web_data_analyses),
            ),
            selected_source_payload,
            runtime_web_data_request=runtime_web_data_request,
            api_request=bool(api_search or api_reference_fetch or openapi_reference_fetch),
        )
        enriched_build_spec["source_selection_constraints"] = intent_source_constraints
        tool_args = {
            "summary": researched_build.get("summary") or tool_args.get("summary") or "",
            "build_spec": enriched_build_spec,
            "research_query": research_query,
            "research_reason": research_reason,
            "research_results": research_result.get("results", []),
            "research_context": _compact_research_context(
                api_source_strategy=api_source_strategy,
                selected_source_payload=selected_source_payload,
                supporting_sources=supporting_sources,
                fetched_pages=fetched_pages,
                web_data_analyses=web_data_analyses,
                http_probe_result=http_probe_result,
                quality=quality,
            ),
        }
        normalized_build_spec = normalize_runtime_build_spec(tool_args.get("build_spec") or {})
        if auth_required and auth_resolution.get("api_auth_resolved"):
            auth_updates = auth_resolution.get("build_spec_updates")
            if isinstance(auth_updates, dict):
                normalized_build_spec.update(auth_updates)
            normalized_build_spec["api_key_handling"] = (
                normalized_build_spec.get("api_key_handling")
                or auth_resolution.get("api_key_handling")
                or "unknown"
            )
            normalized_build_spec["api_auth_strategy"] = (
                normalized_build_spec.get("api_auth_strategy")
                or auth_resolution.get("api_key_handling")
                or "unknown"
            )
            if normalized_build_spec.get("api_key_handling") == "user_provided_in_app":
                normalized_build_spec["requires_api_key_input_screen"] = True
                normalized_build_spec["secret_storage_policy"] = "store_user_provided_key_locally_never_hardcode"
                normalized_build_spec["api_key_error_handling_required"] = True
        if (
            (api_search or api_reference_fetch or openapi_reference_fetch)
            and selected_source_payload
            and not _source_has_auth_requirement(selected_source_payload)
        ):
            normalized_build_spec["api_key_handling"] = normalized_build_spec.get("api_key_handling") or "public_no_key"
            normalized_build_spec["api_auth_strategy"] = normalized_build_spec.get("api_auth_strategy") or "public_no_key"
            normalized_build_spec["prefer_public_no_key_api"] = True
        tool_args["build_spec"] = normalized_build_spec
        if (runtime_web_data_request or api_search or api_reference_fetch or openapi_reference_fetch) and not normalized_build_spec.get("source_url_candidates"):
            update_task(
                task_id,
                status=TaskStatus.ERROR,
                app_name="외부 데이터 계약 누락",
                build_success=0,
            )
            response = build_generate_response(
                task_id=task_id,
                status=TaskStatus.ERROR,
                tool=tool_name,
                message="외부 데이터 출처를 확인했지만 빌드 명세에 공식 소스 URL과 검증 계약을 고정하지 못해 생성을 중단했습니다.",
                image_reference_summary=image_reference_summary,
                image_conflict_note=image_conflict_note,
            )
            record_assistant_response(
                task_id=task_id,
                message_type="external_source_contract_error",
                endpoint="/generate",
                content=response["message"],
                payload={
                    **response,
                    "research_query": research_query,
                    "selected_source": selected_source_payload,
                    "build_spec": normalized_build_spec,
                },
            )
            return response
        summary_text = tool_args.get("summary") or summary_text
        structured_summary["summary"] = summary_text
        structured_summary["build_spec"] = tool_args.get("build_spec")
        structured_summary["research_query"] = research_query
        structured_summary["research_results_count"] = len(research_result.get("results", []))
        log_decision_event(
            task_id,
            event_type="web_research_completed",
            decision_type=tool_name,
            tool_name="web_research",
            tool_args={
                "research_query": research_query,
                "research_reason": research_reason,
                "top_sources": top_sources[:5],
            },
            raw_user_message=raw_user_message,
            structured_summary=structured_summary,
        )

    build_prompt = format_build_input(user_prompt, tool_args)
    update_task(
        task_id,
        status=TaskStatus.PROCESSING,
        app_name="앱 설계 중...",
        final_requirement_summary=summary_text,
        final_app_spec=tool_args.get("build_spec", {}),
        active_flow="generate",
        build_success=None
    )
    update_conversation_state(
        task_id,
        latest_summary=summary_text,
        latest_pending_action="",
        latest_pending_summary="",
        latest_pending_reason="",
    )
    append_log(task_id, f"빌드 요청 수락\n요약: {summary_text}")
    background_tasks.add_task(
        vibe_worker,
        task_id,
        build_prompt,
        device_info,
        False,
        None,
        False,
        None,
        None,
        reference_image_analysis,
        image_conflict_note,
    )
    response = build_generate_response(
        task_id=task_id,
        status=TaskStatus.PROCESSING,
        tool=tool_name,
        message="build_started",
        summary=summary_text,
        image_reference_summary=image_reference_summary,
        image_conflict_note=image_conflict_note,
    )
    record_assistant_response(
        task_id=task_id,
        message_type="build_started",
        endpoint="/generate",
        content=summary_text or "빌드를 시작했습니다.",
        payload=response,
    )
    return response

# ------------------------------------------------
# API ENDPOINTS
# ------------------------------------------------

@app.post("/generate")
async def generate(req: BuildRequest, background_tasks: BackgroundTasks):

    u = uuid.uuid4().hex[:6]
    ts = int(time.time()) % 1000000

    task_id = f"{ts}_{u}"

    log_device_access(
        "/generate",
        task_id=task_id,
        received_device_id=req.device_id,
        stored_device_id=req.device_id,
        reason="task_create"
    )

    
    with get_conn() as conn:
        cursor = conn.cursor()
        cursor.execute(
            """
            INSERT INTO tasks
            (
                task_id, device_id, status, app_name, log, device_info, conversation_state,
                created_at, updated_at, initial_user_prompt, build_attempts, user_id, phone_number, interview_consent
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, DATETIME('now', 'localtime'), DATETIME('now', 'localtime'), ?, 0, ?, ?, ?)
            """,
            (
                task_id,
                req.device_id,
                TaskStatus.PENDING_DECISION,
                "판단 대기 중",
                "스마트폰 앱 2.0 서버에서 요청을 받았습니다.",
                json.dumps(req.device_info),
                json.dumps({
                    "initial_user_prompt": req.prompt,
                    "latest_assistant_questions": [],
                    "latest_user_reply": "",
                    "latest_summary": ""
                }, ensure_ascii=False),
                req.prompt,
                req.user_id,
                normalize_phone_number(req.phone_number),
                1 if to_bool(req.interview_consent) else 0,
            )
        )
        conn.commit()

    reference_image_analysis, reference_image_fingerprint, reused_reference_image_analysis = resolve_reference_image_analysis(
        task=get_task(task_id) or {},
        task_id=task_id,
        image_path=req.reference_image_path,
        analysis_goal=f"사용자 요청 '{req.prompt}'에 맞는 모바일 앱 UI 참조 구조를 요약하세요.",
        reference_image_name=req.reference_image_name,
        reference_image_base64=req.reference_image_base64,
    )
    image_conflict_note = _detect_image_conflict(req.prompt, reference_image_analysis)
    visual_context = persist_visual_context(
        task_id,
        reference_image_analysis,
        image_conflict_note,
        endpoint="/generate",
    )
    if reference_image_fingerprint:
        update_task(task_id, reference_image_fingerprint=reference_image_fingerprint)
    persist_grounding_metadata(
        task_id,
        build_grounding_metadata(
            existing_metadata=get_grounding_metadata(get_task(task_id) or {}),
            flow_type="generate",
            endpoint="/generate",
            image_reference_summary=visual_context.get("summary") or "",
        ),
        endpoint="/generate",
        event_type="generate_grounding_initialized",
        stage="generate_init",
        emit_log_entry=False,
    )

    record_user_query(
        task_id=task_id,
        device_id=req.device_id,
        user_id=req.user_id,
        phone_number=req.phone_number,
        query_type="generate",
        endpoint="/generate",
        content=req.prompt,
    )
    append_log(task_id, f"👤 User request: {req.prompt}")
    if reference_image_analysis:
        if reused_reference_image_analysis:
            append_log(task_id, f"🖼️ 기존 참조 이미지 분석 재사용\n레이아웃: {reference_image_analysis.get('layout_summary')}")
        else:
            append_log(task_id, f"🖼️ 참조 이미지 분석 완료\n레이아웃: {reference_image_analysis.get('layout_summary')}")
        if image_conflict_note:
            append_log(task_id, f"⚠️ 텍스트/이미지 충돌 감지\n사유: {image_conflict_note}")
    return route_generate_decision(
        task_id=task_id,
        user_prompt=req.prompt,
        raw_user_message=req.prompt,
        device_info=req.device_info,
        background_tasks=background_tasks,
        reference_image_analysis=reference_image_analysis,
        image_conflict_note=image_conflict_note,
        image_reference_summary=visual_context.get("summary") or "",
    )


@app.post("/generate/continue")
async def generate_continue(req: ContinueGenerateRequest, background_tasks: BackgroundTasks):
    task_id = require_task_id(req.task_id, "/generate/continue")
    log_device_access(
        "/generate/continue",
        task_id=task_id,
        received_device_id=req.device_id,
        reason="lookup_start"
    )
    task = get_task(task_id)
    if not task:
        log_device_access(
            "/generate/continue",
            task_id=task_id,
            received_device_id=req.device_id,
            reason="task_not_found"
        )
        raise HTTPException(status_code=404, detail="task_not_found")
    if not can_access_task(
        task,
        device_id=req.device_id,
        user_id=req.user_id,
        phone_number=req.phone_number,
    ):
        stored_device_id = task.get("device_id")
        reason = build_access_denied_reason(
            task,
            device_id=req.device_id,
            user_id=req.user_id,
            phone_number=req.phone_number,
        )
        log_device_access(
            "/generate/continue",
            task_id=task_id,
            received_device_id=req.device_id,
            stored_device_id=stored_device_id,
            reason=reason
        )
        raise HTTPException(status_code=404, detail=reason)

    log_device_access(
        "/generate/continue",
        task_id=task_id,
        received_device_id=req.device_id,
        stored_device_id=task.get("device_id"),
        reason="access_granted"
    )
    maybe_rebind_task_device(
        task,
        device_id=req.device_id,
        user_id=req.user_id,
        phone_number=req.phone_number,
    )
    update_task(
        task_id,
        user_id=req.user_id or task.get("user_id"),
        phone_number=req.phone_number or task.get("phone_number"),
    )

    if task["status"] not in {
        TaskStatus.CLARIFICATION_NEEDED,
        TaskStatus.PENDING_DECISION,
        TaskStatus.REJECTED,
    }:
        raise HTTPException(status_code=409, detail="status_not_continuable")

    device_info = json.loads(task["device_info"]) if task.get("device_info") else None
    existing_log = task.get("log") or ""
    conversation_state = get_conversation_state(task)

    update_conversation_state(task_id, latest_user_reply=req.user_message)
    update_task(task_id, active_flow="generate")
    record_user_query(
        task_id=task_id,
        device_id=req.device_id,
        user_id=req.user_id or task.get("user_id"),
        phone_number=req.phone_number or task.get("phone_number"),
        query_type="continue_generate",
        endpoint="/generate/continue",
        content=req.user_message,
    )
    append_log(task_id, f"👤 Clarification reply: {req.user_message}")
    conversation_state["latest_user_reply"] = req.user_message
    followup_prompt = build_continuation_prompt(conversation_state, existing_log)

    return route_generate_decision(
        task_id=task_id,
        user_prompt=followup_prompt,
        raw_user_message=req.user_message,
        device_info=device_info,
        background_tasks=background_tasks,
        reference_image_analysis=get_reference_image_analysis(task),
        image_conflict_note=(task.get("image_conflict_note") or "").strip(),
        image_reference_summary=(task.get("image_reference_summary") or "").strip(),
    )


@app.get("/tasks")
async def list_tasks(device_id: Optional[str] = None, user_id: Optional[str] = None, phone_number: Optional[str] = None):
    normalized_device_id = (device_id or "").strip()
    normalized_user_id = (user_id or "").strip()
    normalized_phone_number = normalize_phone_number(phone_number)
    log_device_access("/tasks", received_device_id=device_id, stored_device_id=normalized_phone_number or normalized_user_id or normalized_device_id, reason="list_start")
    with get_conn() as conn:
        conn.row_factory = sqlite3.Row
        cursor = conn.cursor()
        if normalized_phone_number:
            cursor.execute(
                """
                SELECT
                    task_id,
                    device_id,
                    created_at,
                    updated_at,
                    generated_app_name,
                    app_name,
                    status,
                    initial_user_prompt,
                    conversation_state,
                    package_name,
                    build_success,
                    apk_url
                FROM tasks
                WHERE phone_number = ?
                ORDER BY
                    CASE WHEN updated_at IS NULL THEN created_at ELSE updated_at END DESC,
                    created_at DESC
                """,
                (normalized_phone_number,)
            )
        elif normalized_user_id:
            cursor.execute(
                """
                SELECT
                    task_id,
                    device_id,
                    created_at,
                    updated_at,
                    generated_app_name,
                    app_name,
                    status,
                    initial_user_prompt,
                    conversation_state,
                    package_name,
                    build_success,
                    apk_url
                FROM tasks
                WHERE user_id = ?
                ORDER BY
                    CASE WHEN updated_at IS NULL THEN created_at ELSE updated_at END DESC,
                    created_at DESC
                """,
                (normalized_user_id,)
            )
        elif normalized_device_id:
            cursor.execute(
                """
                SELECT
                    task_id,
                    device_id,
                    created_at,
                    updated_at,
                    generated_app_name,
                    app_name,
                    status,
                    initial_user_prompt,
                    conversation_state,
                    package_name,
                    build_success,
                    apk_url
                FROM tasks
                WHERE device_id = ?
                ORDER BY
                    CASE WHEN updated_at IS NULL THEN created_at ELSE updated_at END DESC,
                    created_at DESC
                """,
                (normalized_device_id,)
            )
        else:
            rows = []
            log_device_access("/tasks", received_device_id=device_id, stored_device_id=normalized_phone_number or normalized_user_id or normalized_device_id, reason="identity_missing")
            return {"tasks": rows}
        rows = cursor.fetchall()

    log_device_access("/tasks", received_device_id=device_id, stored_device_id=normalized_phone_number or normalized_user_id or normalized_device_id, reason=f"list_result_count_{len(rows)}")

    items = []
    for row in rows:
        task_payload = dict(row)
        maybe_rebind_task_device(
            task_payload,
            device_id=normalized_device_id,
            user_id=normalized_user_id,
            phone_number=normalized_phone_number,
        )
        items.append(build_task_summary_payload(task_payload))

    return {"tasks": items}


@app.get("/diagnostics/function-calling")
async def function_calling_diagnostics(task_id: Optional[str] = None):
    return build_function_calling_trace_quality_report(task_id=task_id)


@app.post("/refine")
async def refine(req: RefineRequest, background_tasks: BackgroundTasks):
    task_id = require_task_id(req.task_id, "/refine")
    log_device_access("/refine", task_id=task_id, received_device_id=req.device_id, reason="lookup_start")
    task = get_task(task_id)
    if not task:
        log_device_access("/refine", task_id=task_id, received_device_id=req.device_id, reason="task_not_found")
        raise HTTPException(status_code=404, detail="task_not_found")
    if not can_access_task(
        task,
        device_id=req.device_id,
        user_id=req.user_id,
        phone_number=req.phone_number,
    ):
        log_device_access(
            "/refine",
            task_id=task_id,
            received_device_id=req.device_id,
            stored_device_id=task.get("device_id"),
            reason=build_access_denied_reason(
                task,
                device_id=req.device_id,
                user_id=req.user_id,
                phone_number=req.phone_number,
            )
        )
        raise HTTPException(
            status_code=404,
            detail=build_access_denied_reason(
                task,
                device_id=req.device_id,
                user_id=req.user_id,
                phone_number=req.phone_number,
            )
        )

    if task.get("status") != TaskStatus.SUCCESS:
        emit_task_log(task_id, f"⛔ /refine 거부: 현재 상태={task.get('status')}")
        raise HTTPException(status_code=409, detail="status_not_refinable")
    project_path = resolve_task_project_path(task, persist=True)
    if not project_path:
        raise HTTPException(status_code=409, detail="missing_project_path")
    try:
        project_path = create_workspace_revision_clone(
            task=task,
            flow_type="refine",
            trigger_message=req.feedback,
        )
    except Exception as e:
        emit_task_log(task_id, f"❌ 리파인 작업용 프로젝트 복제 실패: {str(e)}")
        raise HTTPException(status_code=500, detail="workspace_clone_failed")

    log_device_access(
        "/refine",
        task_id=task_id,
        received_device_id=req.device_id,
        stored_device_id=task.get("device_id"),
        reason="access_granted"
    )
    maybe_rebind_task_device(
        task,
        device_id=req.device_id,
        user_id=req.user_id,
        phone_number=req.phone_number,
    )
    update_task(
        task_id,
        user_id=req.user_id or task.get("user_id"),
        phone_number=req.phone_number or task.get("phone_number"),
    )
    update_task(task_id, status=TaskStatus.PROCESSING, active_flow="refine")
    reference_image_analysis, reference_image_fingerprint, reused_reference_image_analysis = resolve_reference_image_analysis(
        task=task,
        task_id=task_id,
        image_path=req.reference_image_path,
        analysis_goal=f"사용자 리파인 요청 '{req.feedback}'에 맞는 기존 앱 UI 참조 구조를 요약하세요.",
        reference_image_name=req.reference_image_name,
        reference_image_base64=req.reference_image_base64,
    )
    image_conflict_note = _detect_image_conflict(req.feedback, reference_image_analysis)
    visual_context = persist_visual_context(
        task_id,
        reference_image_analysis,
        image_conflict_note,
        endpoint="/refine",
    )
    if reference_image_fingerprint:
        update_task(task_id, reference_image_fingerprint=reference_image_fingerprint)
    persist_grounding_metadata(
        task_id,
        build_grounding_metadata(
            existing_metadata=get_grounding_metadata(task),
            flow_type="refine",
            endpoint="/refine",
            image_reference_summary=visual_context.get("summary") or "",
        ),
        endpoint="/refine",
        event_type="refine_grounding_snapshot",
        stage="refine",
    )
    record_user_query(
        task_id=task_id,
        device_id=req.device_id,
        user_id=req.user_id or task.get("user_id"),
        phone_number=req.phone_number or task.get("phone_number"),
        query_type="refine",
        endpoint="/refine",
        content=req.feedback,
    )
    emit_task_log(task_id, f"🛠️ 리파인 요청 수신: {req.feedback}")
    if reference_image_analysis:
        if reused_reference_image_analysis:
            emit_task_log(task_id, f"🖼️ 기존 참조 이미지 분석 재사용\n레이아웃: {reference_image_analysis.get('layout_summary')}")
        else:
            emit_task_log(task_id, f"🖼️ 참조 이미지 분석 완료\n레이아웃: {reference_image_analysis.get('layout_summary')}")
        if image_conflict_note:
            emit_task_log(task_id, f"⚠️ 텍스트/이미지 충돌 감지\n사유: {image_conflict_note}")
    emit_task_log(task_id, "🧾 리파인 요청이 대기열에 등록되었습니다.")
    record_assistant_response(
        task_id=task_id,
        message_type="refine_accepted",
        endpoint="/refine",
        content="기존 앱 구조를 유지하면서 리파인 작업을 시작합니다.",
        payload={
            "task_id": task_id,
            "status": TaskStatus.PROCESSING,
        },
    )
    
    background_tasks.add_task(
        vibe_worker,
        task_id,
        req.feedback,
        None,
        True,
        project_path,
        False,
        None,
        None,
        reference_image_analysis,
        image_conflict_note,
    )
    return {
        "task_id": task_id,
        "status": TaskStatus.PROCESSING,
        "image_reference_summary": visual_context.get("summary") or "",
        "image_conflict_note": visual_context.get("conflict_note") or "",
    }


@app.post("/feedback/route")
async def route_feedback(req: FeedbackRouteRequest):
    task_id = require_task_id(req.task_id, "/feedback/route")
    task = get_task(task_id)
    if not task:
        raise HTTPException(status_code=404, detail="task_not_found")
    if not can_access_task(
        task,
        device_id=req.device_id,
        user_id=req.user_id,
        phone_number=req.phone_number,
    ):
        raise HTTPException(
            status_code=404,
            detail=build_access_denied_reason(
                task,
                device_id=req.device_id,
                user_id=req.user_id,
                phone_number=req.phone_number,
            )
        )

    maybe_rebind_task_device(
        task,
        device_id=req.device_id,
        user_id=req.user_id,
        phone_number=req.phone_number,
    )
    update_task(
        task_id,
        user_id=req.user_id or task.get("user_id"),
        phone_number=req.phone_number or task.get("phone_number"),
    )
    record_user_query(
        task_id=task_id,
        device_id=req.device_id,
        user_id=req.user_id or task.get("user_id"),
        phone_number=req.phone_number or task.get("phone_number"),
        query_type="feedback_route",
        endpoint="/feedback/route",
        content=req.user_message,
    )

    route_context = build_feedback_route_context(task, req.runtime_error)
    decision, usage, error = decide_feedback_action(
        req.user_message,
        task_context=route_context,
        task_id=task_id,
    )
    log_token_usage(task_id, "Feedback_Route_Decision", usage)

    current_status = normalize_task_status(task.get("status"))
    fallback_message = "요청 의도를 정확히 판단하지 못했어요. 조금 더 구체적으로 말씀해 주세요."
    if error or not decision:
        response = build_feedback_route_response(
            task_id=task_id,
            action="no_action",
            target_endpoint="",
            current_status=current_status,
            assistant_message=fallback_message,
            reason=error or "feedback_route_decision_failed",
        )
        record_assistant_response(
            task_id=task_id,
            message_type="feedback_route",
            endpoint="/feedback/route",
            content=response["assistant_message"],
            payload=response,
        )
        return response

    action = decision.get("action") or "no_action"
    assistant_message = (decision.get("assistant_message") or "").strip() or fallback_message
    reason = (decision.get("reason") or "").strip()
    target_endpoint = ""
    pending_runtime_error = req.runtime_error and (req.runtime_error.stack_trace or req.runtime_error.package_name)
    status_payload = build_status_payload(task)

    if action == "answer_question":
        target_endpoint = ""
    elif action == "ask_confirmation":
        target_endpoint = ""
    elif action == "repair_runtime":
        if pending_runtime_error:
            target_endpoint = "/crash"
        else:
            action = "no_action"
            assistant_message = "해결할 실행 오류 정보가 아직 없어요. 오류를 다시 감지한 뒤 시도해 주세요."
            reason = reason or "missing_runtime_error_context"
    elif action == "refine":
        if current_status == TaskStatus.SUCCESS:
            target_endpoint = "/refine"
        else:
            action = "no_action"
            assistant_message = "지금 상태에서는 수정 요청 대신 현재 작업을 먼저 마무리해야 해요."
            reason = reason or "status_not_refinable"
    elif action == "retry":
        if status_payload.get("retry_allowed"):
            target_endpoint = "/retry"
        else:
            action = "no_action"
            assistant_message = "지금 상태에서는 재시도를 바로 시작할 수 없어요."
            reason = reason or (status_payload.get("retry_block_reason") or "status_not_retryable")
    elif action == "continue_generate":
        if current_status in {TaskStatus.CLARIFICATION_NEEDED, TaskStatus.PENDING_DECISION}:
            target_endpoint = "/generate/continue"
        else:
            action = "no_action"
            assistant_message = "지금은 추가 답변을 이어받는 단계가 아니에요."
            reason = reason or "status_not_clarifying"

    response = build_feedback_route_response(
        task_id=task_id,
        action=action,
        target_endpoint=target_endpoint,
        current_status=current_status,
        assistant_message=assistant_message,
        reason=reason,
    )
    log_decision_event(
        task_id,
        event_type="feedback_routed",
        decision_type=action,
        tool_name=action,
        tool_args={
            "target_endpoint": target_endpoint,
            "current_status": current_status,
            "assistant_message": assistant_message,
            "reason": reason,
        },
        raw_user_message=req.user_message,
        structured_summary={
            "assistant_message": assistant_message,
            "reason": reason,
            "pending_runtime_error": bool(pending_runtime_error),
        },
    )
    record_assistant_response(
        task_id=task_id,
        message_type="assistant_answer" if action == "answer_question" else ("confirmation_question" if action == "ask_confirmation" else "feedback_route"),
        endpoint="/feedback/route",
        content=assistant_message,
        payload=response,
    )
    return response


@app.post("/interaction/event")
async def interaction_event(req: InteractionEventRequest):
    record_interaction_event(
        task_id=req.task_id,
        event_type=req.event_type,
        source=req.source or "android_host",
        action=req.action,
        message_id=req.message_id,
        message_type=req.message_type,
        content=req.content,
        payload=req.payload,
        device_id=req.device_id,
        user_id=req.user_id,
        phone_number=req.phone_number,
    )
    if req.task_id:
        record_orchestration_event(
            task_id=req.task_id,
            event_type=f"interaction:{req.event_type}",
            stage="user_interaction",
            message=req.content,
            event_metadata={
                "source": req.source or "android_host",
                "action": req.action,
                "message_id": req.message_id,
                "message_type": req.message_type,
                "payload": req.payload or {},
            },
        )
    return {"status": "ok", "task_id": req.task_id}


@app.post("/runtime/error/summary")
async def runtime_error_summary(req: RuntimeErrorSummaryRequest):
    task_id = require_task_id(req.task_id, "/runtime/error/summary")
    task = get_task(task_id)
    if not task:
        raise HTTPException(status_code=404, detail="task_not_found")
    if not can_access_task(
        task,
        device_id=req.device_id,
        user_id=req.user_id,
        phone_number=req.phone_number,
    ):
        raise HTTPException(
            status_code=404,
            detail=build_access_denied_reason(
                task,
                device_id=req.device_id,
                user_id=req.user_id,
                phone_number=req.phone_number,
            )
        )

    maybe_rebind_task_device(
        task,
        device_id=req.device_id,
        user_id=req.user_id,
        phone_number=req.phone_number,
    )
    task_context = build_feedback_route_context(
        task,
        RuntimeErrorContext(
            package_name=req.package_name,
            stack_trace=req.stack_trace,
            summary="",
            awaiting_user_confirmation=True,
        ),
    )
    decision, usage, error = summarize_runtime_error(
        req.stack_trace,
        task_context=task_context,
        task_id=task_id,
    )
    log_token_usage(task_id, "Runtime_Error_Summary", usage)

    if error or not decision:
        fallback_summary = "실행 중 오류"
        return {
            "task_id": task_id,
            "summary": fallback_summary,
            "assistant_message": f"오류가 감지되었어요. 감지된 오류는 {fallback_summary}입니다. 해결해드릴까요?",
        }

    response = {
        "task_id": task_id,
        "summary": (decision.get("summary") or "").strip(),
        "assistant_message": (decision.get("assistant_message") or "").strip(),
    }
    record_assistant_response(
        task_id=task_id,
        message_type="runtime_error_summary",
        endpoint="/runtime/error/summary",
        content=response["assistant_message"],
        payload=response,
    )
    return response


@app.post("/runtime/error/report")
async def runtime_error_report(req: RuntimeErrorReportRequest):
    task_id = require_task_id(req.task_id, "/runtime/error/report")
    task = get_task(task_id)
    if not task:
        raise HTTPException(status_code=404, detail="task_not_found")
    if not can_access_task(
        task,
        device_id=req.device_id,
        user_id=req.user_id,
        phone_number=req.phone_number,
    ):
        raise HTTPException(
            status_code=404,
            detail=build_access_denied_reason(
                task,
                device_id=req.device_id,
                user_id=req.user_id,
                phone_number=req.phone_number,
            )
        )

    maybe_rebind_task_device(
        task,
        device_id=req.device_id,
        user_id=req.user_id,
        phone_number=req.phone_number,
    )
    normalized_phone_number = normalize_phone_number(req.phone_number or task.get("phone_number"))
    update_task(
        task_id,
        user_id=req.user_id or task.get("user_id"),
        phone_number=normalized_phone_number,
    )
    record_conversation_message(
        task_id=task_id,
        role="system",
        message_type="runtime_error_detected",
        endpoint="/runtime/error/report",
        content=f"런타임 오류 감지: {trim_context_text(req.stack_trace.splitlines()[0] if req.stack_trace else 'stack_trace_missing', 200)}",
        payload={
            "package_name": req.package_name,
            "stack_trace": req.stack_trace,
            "device_id": req.device_id,
            "user_id": req.user_id or task.get("user_id"),
            "phone_number": normalized_phone_number,
        },
    )
    return {
        "status": "runtime_error_recorded",
        "task_id": task_id,
    }


@app.post("/refine/plan")
async def refine_plan(req: RefineRequest):
    task_id = require_task_id(req.task_id, "/refine/plan")
    log_device_access("/refine/plan", task_id=task_id, received_device_id=req.device_id, reason="lookup_start")
    task = get_task(task_id)
    if not task:
        log_device_access("/refine/plan", task_id=task_id, received_device_id=req.device_id, reason="task_not_found")
        raise HTTPException(status_code=404, detail="task_not_found")
    if not can_access_task(
        task,
        device_id=req.device_id,
        user_id=req.user_id,
        phone_number=req.phone_number,
    ):
        log_device_access(
            "/refine/plan",
            task_id=task_id,
            received_device_id=req.device_id,
            stored_device_id=task.get("device_id"),
            reason=build_access_denied_reason(
                task,
                device_id=req.device_id,
                user_id=req.user_id,
                phone_number=req.phone_number,
            )
        )
        raise HTTPException(
            status_code=404,
            detail=build_access_denied_reason(
                task,
                device_id=req.device_id,
                user_id=req.user_id,
                phone_number=req.phone_number,
            )
        )

    if task.get("status") != TaskStatus.SUCCESS:
        emit_task_log(task_id, f"⛔ /refine/plan 거부: 현재 상태={task.get('status')}")
        raise HTTPException(status_code=409, detail="status_not_refinable")
    project_path = resolve_task_project_path(task, persist=True)
    if not project_path:
        raise HTTPException(status_code=409, detail="missing_project_path")
    log_device_access(
        "/refine/plan",
        task_id=task_id,
        received_device_id=req.device_id,
        stored_device_id=task.get("device_id"),
        reason="access_granted"
    )
    maybe_rebind_task_device(
        task,
        device_id=req.device_id,
        user_id=req.user_id,
        phone_number=req.phone_number,
    )
    update_task(
        task_id,
        user_id=req.user_id or task.get("user_id"),
        phone_number=req.phone_number or task.get("phone_number"),
    )
    record_user_query(
        task_id=task_id,
        device_id=req.device_id,
        user_id=req.user_id or task.get("user_id"),
        phone_number=req.phone_number or task.get("phone_number"),
        query_type="refine_plan",
        endpoint="/refine/plan",
        content=req.feedback,
    )
    reference_image_analysis, reference_image_fingerprint, reused_reference_image_analysis = resolve_reference_image_analysis(
        task=task,
        task_id=task_id,
        image_path=req.reference_image_path,
        analysis_goal=f"사용자 리파인 요청 '{req.feedback}'에 맞는 기존 앱 UI 참조 구조를 요약하세요.",
        reference_image_name=req.reference_image_name,
        reference_image_base64=req.reference_image_base64,
    )
    image_conflict_note = _detect_image_conflict(req.feedback, reference_image_analysis)
    visual_context = persist_visual_context(
        task_id,
        reference_image_analysis,
        image_conflict_note,
        endpoint="/refine/plan",
    )
    if reference_image_fingerprint:
        update_task(task_id, reference_image_fingerprint=reference_image_fingerprint)
    persist_grounding_metadata(
        task_id,
        build_grounding_metadata(
            existing_metadata=get_grounding_metadata(task),
            flow_type="refine",
            endpoint="/refine/plan",
            image_reference_summary=visual_context.get("summary") or "",
        ),
        endpoint="/refine/plan",
        event_type="refine_plan_grounding_snapshot",
        stage="refine_plan",
    )

    preview = create_refinement_plan_preview(
        project_path,
        req.feedback,
        task_id=task_id,
        token_callback=log_token_usage,
        reference_image_analysis=reference_image_analysis,
        image_conflict_note=image_conflict_note,
        ui_contract=get_ui_contract(task),
    )
    if preview["status"] != "success":
        raise HTTPException(status_code=500, detail=preview.get("error_log", "리파인 계획 생성에 실패했습니다."))

    response = {
        "status": "ready",
        "task_id": task_id,
        "assistant_message": preview["assistant_message"],
        "summary": preview["summary"],
        "image_reference_summary": visual_context.get("summary") or "",
        "image_conflict_note": visual_context.get("conflict_note") or "",
    }
    record_assistant_response(
        task_id=task_id,
        message_type="refine_plan",
        endpoint="/refine/plan",
        content=preview["assistant_message"],
        payload=response,
    )
    return response


@app.post("/retry")
async def retry(req: RetryRequest, background_tasks: BackgroundTasks):
    task_id = require_task_id(req.task_id, "/retry")
    task = get_task(task_id)
    if not task:
        raise HTTPException(status_code=404, detail="task_not_found")
    if not can_access_task(
        task,
        device_id=req.device_id,
        user_id=req.user_id,
        phone_number=req.phone_number,
    ):
        raise HTTPException(
            status_code=404,
            detail=build_access_denied_reason(
                task,
                device_id=req.device_id,
                user_id=req.user_id,
                phone_number=req.phone_number,
            )
        )

    maybe_rebind_task_device(
        task,
        device_id=req.device_id,
        user_id=req.user_id,
        phone_number=req.phone_number,
    )
    if task.get("status") not in {TaskStatus.FAILED, TaskStatus.ERROR}:
        emit_task_log(task_id, f"⛔ /retry 거부: 현재 상태={task.get('status')}")
        raise HTTPException(status_code=409, detail="status_not_retryable")

    project_path = resolve_task_project_path(task, persist=True)
    if not project_path:
        raise HTTPException(status_code=409, detail="missing_project_path")
    try:
        project_path = create_workspace_revision_clone(
            task=task,
            flow_type="retry",
            trigger_message=req.feedback,
        )
    except Exception as e:
        emit_task_log(task_id, f"❌ 재시도 작업용 프로젝트 복제 실패: {str(e)}")
        raise HTTPException(status_code=500, detail="workspace_clone_failed")

    update_task(
        task_id,
        user_id=req.user_id or task.get("user_id"),
        phone_number=req.phone_number or task.get("phone_number"),
    )
    update_task(task_id, status=TaskStatus.PROCESSING, active_flow="retry")
    record_user_query(
        task_id=task_id,
        device_id=req.device_id,
        user_id=req.user_id or task.get("user_id"),
        phone_number=req.phone_number or task.get("phone_number"),
        query_type="retry",
        endpoint="/retry",
        content=req.feedback,
    )
    emit_task_log(task_id, f"👤 재시도 요청 수신: {req.feedback}")
    record_assistant_response(
        task_id=task_id,
        message_type="retry_accepted",
        endpoint="/retry",
        content="기존 작업 기준으로 복구 재시도를 시작합니다.",
        payload={
            "task_id": task_id,
            "status": TaskStatus.PROCESSING,
        },
    )

    retry_context = build_retry_request_context(task)
    image_reference_summary = (retry_context.get("image_reference_summary") or "").strip()
    image_conflict_note = (retry_context.get("image_conflict_note") or "").strip()
    if image_reference_summary:
        emit_task_log(task_id, f"🖼️ 기존 참고 이미지 문맥 재사용\n요약: {image_reference_summary}")
    persist_grounding_metadata(
        task_id,
        build_grounding_metadata(
            existing_metadata=get_grounding_metadata(task),
            flow_type="retry",
            endpoint="/retry",
            image_reference_summary=image_reference_summary,
        ),
        endpoint="/retry",
        event_type="retry_grounding_reused",
        stage="retry",
    )

    background_tasks.add_task(
        vibe_worker,
        task_id,
        req.feedback,
        None,
        False,
        project_path,
        True,
        task.get("package_name"),
        retry_context
    )
    return {
        "task_id": task_id,
        "status": TaskStatus.PROCESSING,
        "image_reference_summary": image_reference_summary,
        "image_conflict_note": image_conflict_note,
    }

@app.post("/crash")
async def report_crash(report: CrashReport, background_tasks: BackgroundTasks):
    task_id = require_task_id(report.task_id, "/crash")
    task = get_task(task_id)
    if not task:
        logger.warning(
            f"[crash] lookup_failed task_id={task_id} package_name={report.package_name}"
        )
        raise HTTPException(status_code=404, detail="task_not_found")
    if not can_access_task(
        task,
        device_id=report.device_id,
        user_id=report.user_id,
        phone_number=report.phone_number,
    ):
        raise HTTPException(
            status_code=404,
            detail=build_access_denied_reason(
                task,
                device_id=report.device_id,
                user_id=report.user_id,
                phone_number=report.phone_number,
            )
        )

    maybe_rebind_task_device(
        task,
        device_id=report.device_id,
        user_id=report.user_id,
        phone_number=report.phone_number,
    )
    logger.info(
        f"[crash] resolved canonical_task_id={task_id} "
        f"lookup_source=task_id package_name={report.package_name}"
    )
    project_path = resolve_task_project_path(task, persist=True)
    if not project_path:
        raise HTTPException(status_code=409, detail="missing_project_path")
    try:
        cloned_project_path = create_workspace_revision_clone(
            task=task,
            flow_type="repair",
            trigger_message=trim_context_text(report.stack_trace.splitlines()[0] if report.stack_trace else "runtime crash", 240),
        )
        task["project_path"] = cloned_project_path
    except Exception as e:
        append_log(task_id, f"❌ 런타임 복구용 프로젝트 복제 실패: {str(e)}")
        raise HTTPException(status_code=500, detail="workspace_clone_failed")
    update_task(
        task_id,
        user_id=report.user_id or task.get("user_id"),
        phone_number=report.phone_number or task.get("phone_number"),
    )
    update_task(task_id, status=TaskStatus.REPAIRING, active_flow="repair")
    append_log(
        task_id,
        f"🚨 모바일 기기에서 런타임 크래시가 감지되었습니다. "
        f"(canonical_task_id={task_id}, lookup_source=task_id)"
    )
    record_conversation_message(
        task_id=task_id,
        role="system",
        message_type="runtime_error_report",
        endpoint="/crash",
        content=f"런타임 오류 보고: {trim_context_text(report.stack_trace.splitlines()[0] if report.stack_trace else 'stack_trace_missing', 200)}",
        payload={
            "package_name": report.package_name,
            "stack_trace": report.stack_trace,
            "device_id": report.device_id,
            "user_id": report.user_id or task.get("user_id"),
            "phone_number": normalize_phone_number(report.phone_number or task.get("phone_number")),
        },
    )
    record_assistant_response(
        task_id=task_id,
        message_type="runtime_repair_started",
        endpoint="/crash",
        content="감지된 런타임 오류를 기준으로 복구 빌드를 시작합니다.",
        payload={
            "task_id": task_id,
            "status": TaskStatus.REPAIRING,
            "package_name": report.package_name,
        },
    )
    
    # 자가 치유 로직 백그라운드 실행
    background_tasks.add_task(
        auto_fix_runtime_error,
        task_id,
        report.stack_trace
    )
    return {
        "status": "self_healing_initiated",
        "task_id": task_id,
        "lookup_source": "task_id",
    }

@app.get("/status/{task_id}")
async def status(
    task_id: str,
    device_id: Optional[str] = None,
    user_id: Optional[str] = None,
    phone_number: Optional[str] = None,
):
    log_device_access("/status", task_id=task_id, received_device_id=device_id, reason="lookup_start")
    task = get_task(task_id)
    if not task:
        log_device_access("/status", task_id=task_id, received_device_id=device_id, reason="task_not_found")
        raise HTTPException(status_code=404, detail="task_not_found")
    if not can_access_task(
        task,
        device_id=device_id,
        user_id=user_id,
        phone_number=phone_number,
    ):
        stored_device_id = task.get("device_id")
        reason = build_access_denied_reason(
            task,
            device_id=device_id,
            user_id=user_id,
            phone_number=phone_number,
        )
        log_device_access(
            "/status",
            task_id=task_id,
            received_device_id=device_id,
            stored_device_id=stored_device_id,
            reason=reason
        )
        raise HTTPException(status_code=404, detail=reason)

    log_device_access(
        "/status",
        task_id=task_id,
        received_device_id=device_id,
        stored_device_id=task.get("device_id"),
        reason="access_granted"
    )
    maybe_rebind_task_device(
        task,
        device_id=device_id,
        user_id=user_id,
        phone_number=phone_number,
    )

    return build_status_payload(task)

@app.get("/download/{task_id}")
async def download(
    task_id: str,
    device_id: Optional[str] = None,
    user_id: Optional[str] = None,
    phone_number: Optional[str] = None,
):
    log_device_access("/download", task_id=task_id, received_device_id=device_id, reason="lookup_start")
    task = get_task(task_id)
    if not task:
        log_device_access("/download", task_id=task_id, received_device_id=device_id, reason="task_not_found")
        raise HTTPException(status_code=404, detail="task_not_found")
    if not can_access_task(
        task,
        device_id=device_id,
        user_id=user_id,
        phone_number=phone_number,
    ):
        stored_device_id = task.get("device_id")
        reason = build_access_denied_reason(
            task,
            device_id=device_id,
            user_id=user_id,
            phone_number=phone_number,
        )
        log_device_access(
            "/download",
            task_id=task_id,
            received_device_id=device_id,
            stored_device_id=stored_device_id,
            reason=reason
        )
        raise HTTPException(status_code=404, detail=reason)
    if task["status"] != TaskStatus.SUCCESS:
        log_device_access(
            "/download",
            task_id=task_id,
            received_device_id=device_id,
            stored_device_id=task.get("device_id"),
            reason="apk_not_ready"
        )
        raise HTTPException(status_code=404, detail="APK not ready")

    log_device_access(
        "/download",
        task_id=task_id,
        received_device_id=device_id,
        stored_device_id=task.get("device_id"),
        reason="access_granted"
    )
    maybe_rebind_task_device(
        task,
        device_id=device_id,
        user_id=user_id,
        phone_number=phone_number,
    )

    apk_path = task["apk_path"]
    if not os.path.exists(apk_path):
        log_device_access(
            "/download",
            task_id=task_id,
            received_device_id=device_id,
            stored_device_id=task.get("device_id"),
            reason="apk_file_missing"
        )
        raise HTTPException(status_code=404, detail="APK file missing on server")

    return FileResponse(
        apk_path,
        filename=f"{task['app_name']}.apk"
    )

# ------------------------------------------------
# WORKER LOGIC
# ------------------------------------------------

def vibe_worker(task_id, prompt, device_info, is_refine=False, project_path=None, is_retry=False, package_name=None, retry_context=None, reference_image_analysis=None, image_conflict_note=""):
    def callback_log(msg):
        emit_task_log(task_id, msg)

    flow_type = "generate"
    if is_retry:
        flow_type = "retry"
    elif is_refine:
        flow_type = "refine"

    try:
        task_before_work = get_task(task_id) or {}
        ui_contract = get_ui_contract(task_before_work)
        if is_retry:
            update_task(task_id, status=TaskStatus.REPAIRING, active_flow="retry")
            callback_log("🔁 실패한 빌드 복구 루프 시작")
            result = retry_failed_vibe_app(
                project_path,
                prompt,
                package_name=package_name,
                request_context=retry_context,
                callback_log=callback_log,
                token_callback=log_token_usage,
                task_id=task_id,
                ui_contract=ui_contract,
            )
        elif is_refine:
            update_task(task_id, status=TaskStatus.REVIEWING, active_flow="refine")
            callback_log("🔄 멀티 에이전트 리파인 루프 시작")
            result = refine_vibe_app(
                project_path,
                prompt,
                callback_log=callback_log,
                token_callback=log_token_usage, # 🌟 추가
                task_id=task_id,
                reference_image_analysis=reference_image_analysis,
                image_conflict_note=image_conflict_note,
                ui_contract=ui_contract,
            )
        else:
            increment_build_attempts(task_id)
            callback_log("🏗️ 멀티 에이전트 생성 루프 시작")
            result = run_vibe_factory(
                task_id,
                prompt,
                device_context=device_info,
                callback_log=callback_log,
                token_callback=log_token_usage,
                reference_image_analysis=reference_image_analysis,
                image_conflict_note=image_conflict_note,
                ui_contract=ui_contract,
            )

        result = enforce_release_verification_result(task_before_work, result)
        if isinstance(result, dict) and result.get("status") != "success":
            result = reconcile_failed_result_with_verified_artifact(
                task_before_work,
                result,
                callback_log=callback_log,
            )

        if result["status"] == "success":
            result_project_path = result.get("project_path") or project_path
            verification_report = result.get("verification_report") if isinstance(result.get("verification_report"), dict) else {}
            verification_status = (verification_report.get("status") or result.get("verification_status") or "").strip()
            verification_passed = verification_status in {"pass", "not_applicable"} if verification_status else None
            if result_project_path:
                record_workspace_revision(
                    task_id=task_id,
                    project_path=result_project_path,
                    flow_type=flow_type,
                    status=TaskStatus.SUCCESS,
                    parent_project_path=None,
                    trigger_message=prompt,
                )
                update_workspace_revision_status(result_project_path, TaskStatus.SUCCESS)
                ui_contract = refresh_task_ui_contract(
                    task_id=task_id,
                    project_path=result_project_path,
                    flow_type=flow_type,
                )
            update_task(
                task_id,
                status=TaskStatus.SUCCESS,
                app_name=result.get("app_name", get_task(task_id).get("app_name", "생성 앱")),
                generated_app_name=result.get("app_name", get_task(task_id).get("generated_app_name") or get_task(task_id).get("app_name", "생성 앱")),
                apk_path=result["apk_path"],
                apk_url=build_download_url(get_task(task_id) or {"task_id": task_id}),
                project_path=result["project_path"],
                package_name=result.get("package_name") or package_name,
                active_flow="",
                build_success=1,
                verification_summary=result.get("verification_summary") or "",
                verification_report=verification_report,
                verification_passed=verification_passed,
                ui_contract=ui_contract if ui_contract else get_ui_contract(get_task(task_id) or {}),
            )
            emit_task_log(task_id, "✅ 작업이 성공적으로 완료되었습니다.")
        else:
            error_log = result.get("error_log", "에이전트 단계에서 실행에 실패했습니다.")
            failure_stage = result.get("failure_stage") or "build"
            failure_type = result.get("failure_type") or "unknown"
            persisted_project_path = result.get("project_path") or project_path or get_task(task_id).get("project_path")
            persisted_package_name = result.get("package_name") or package_name or get_task(task_id).get("package_name")
            if persisted_project_path:
                record_workspace_revision(
                    task_id=task_id,
                    project_path=persisted_project_path,
                    flow_type=flow_type,
                    status=TaskStatus.FAILED,
                    parent_project_path=None,
                    trigger_message=prompt,
                )
                update_workspace_revision_status(persisted_project_path, TaskStatus.FAILED)
            update_task(
                task_id,
                build_success=0,
                project_path=persisted_project_path,
                package_name=persisted_package_name,
                verification_summary=result.get("verification_summary") or "",
                verification_report=result.get("verification_report") or {},
                verification_passed=0 if result.get("verification_report") else None,
            )
            record_conversation_message(
                task_id=task_id,
                role="system",
                message_type="build_failure_report",
                endpoint=f"/{flow_type}",
                content=f"빌드 실패 원문 로그 ({failure_stage}/{failure_type})",
                payload={
                    "failure_stage": failure_stage,
                    "failure_type": failure_type,
                    "error_log": error_log,
                    "project_path": persisted_project_path,
                    "package_name": persisted_package_name,
                },
            )
            task_context = build_retry_request_context(get_task(task_id) or {})
            summary_decision, usage, summary_error = summarize_build_failure(
                error_log,
                failure_stage=failure_stage,
                failure_type=failure_type,
                task_context=task_context,
                task_id=task_id,
            )
            log_token_usage(task_id, "Build_Failure_Summarizer", usage)
            summary_payload = summary_decision if summary_decision and not summary_error else build_failure_summary_fallback(failure_stage, failure_type)
            primary_failure_line = extract_primary_failure_line(error_log)
            emit_task_log(task_id, f"❌ 실패 ({failure_stage}/{failure_type})")
            if primary_failure_line:
                emit_task_log(task_id, f"🔎 핵심 로그: {primary_failure_line}")
            record_assistant_response(
                task_id=task_id,
                message_type="build_failure_summary",
                endpoint=f"/{flow_type}",
                content=summary_payload["assistant_message"],
                payload={
                    "summary": summary_payload["summary"],
                    "assistant_message": summary_payload["assistant_message"],
                    "failure_stage": failure_stage,
                    "failure_type": failure_type,
                },
            )
            emit_task_log(task_id, f"🧾 원인 요약: {summary_payload['summary']}")
            update_task(
                task_id,
                status=TaskStatus.FAILED,
                build_success=0,
                project_path=persisted_project_path,
                package_name=persisted_package_name,
                active_flow=""
            )

    except Exception as e:
        traceback.print_exc()
        fallback_task = get_task(task_id) or {}
        persisted_project_path = project_path or fallback_task.get("project_path")
        if persisted_project_path:
            record_workspace_revision(
                task_id=task_id,
                project_path=persisted_project_path,
                flow_type=flow_type,
                status=TaskStatus.ERROR,
                parent_project_path=None,
                trigger_message=prompt,
            )
            update_workspace_revision_status(persisted_project_path, TaskStatus.ERROR)
        update_task(
            task_id,
            status=TaskStatus.ERROR,
            build_success=0,
            project_path=persisted_project_path,
            package_name=package_name or fallback_task.get("package_name"),
            active_flow=""
        )
        error_text = trim_context_text(str(e), 280)
        emit_task_log(task_id, f"🚨 치명적인 시스템 오류: {error_text}")
        record_assistant_response(
            task_id=task_id,
            message_type="fatal_worker_error",
            endpoint=f"/{flow_type}",
            content=f"작업 중 서버 내부 오류가 발생했습니다. {error_text}",
            payload={
                "task_id": task_id,
                "status": TaskStatus.ERROR,
                "flow_type": flow_type,
                "error": str(e),
                "project_path": persisted_project_path,
            },
        )

# ------------------------------------------------
# SELF-HEALING (RUNTIME AUTO FIX)
# ------------------------------------------------

def auto_fix_runtime_error(task_id, stack_trace):
    task = get_task(task_id)
    if not task:
        return

    project_path = task["project_path"]
    package_name = task["package_name"]
    update_workspace_revision_status(project_path, TaskStatus.REPAIRING)
    update_task(task_id, status=TaskStatus.REPAIRING, active_flow="repair")
    
    emit_task_log(task_id, "🧠 디버거 에이전트가 스택 트레이스를 분석 중입니다.")

    build_spec = parse_json_object_field(task.get("final_app_spec")) or {}
    repair_error_log = stack_trace
    last_failure_payload: Dict[str, Any] = {
        "failure_stage": "runtime",
        "failure_type": "runtime_crash",
        "error_log": stack_trace,
    }

    for attempt in range(1, 4):
        emit_task_log(task_id, f"🛠 런타임 자가 복구 시도 중 ({attempt}/3)")
        snapshot = get_current_project_snapshot(project_path)
        debug_context = {
            "error_log": repair_error_log,
            "original_stack_trace": stack_trace,
            "code_snapshot": snapshot,
            "package_name": package_name,
            "previous_failure": last_failure_payload,
            "ui_contract": get_ui_contract(get_task(task_id) or task),
        }

        fix_result = call_agent_with_tools(
            DEBUGGER_SYSTEM,
            "런타임 크래시 또는 복구 빌드 실패를 분석하고 최소 패치로 앱 안정성을 복구하세요.",
            context=debug_context,
            trace={"task_id": task_id, "flow_type": "repair", "agent_name": "Runtime_Debugger", "stage": f"runtime_fix_attempt_{attempt}"},
            tools=FILE_CHANGE_TOOL_SCHEMAS,
            validator=validate_file_change_payload,
            parsed_output_builder=lambda tool_name, tool_arguments: normalize_file_change_payload(tool_arguments),
            fallback_parser=legacy_agent_response_detailed,
        )
        fix_response = fix_result.get("parsed_output")
        usage = fix_result.get("usage")
        if usage:
            log_token_usage(task_id, f"Runtime_Debugger_Attempt_{attempt}", usage)

        if not fix_response or "files" not in fix_response:
            repair_error_log = "디버거가 유효한 수정 패치를 생성하지 못했습니다."
            last_failure_payload = {
                "failure_stage": "runtime_fix",
                "failure_type": "invalid_patch",
                "error_log": repair_error_log,
            }
            emit_task_log(task_id, f"❌ {repair_error_log}")
            continue

        ok, touched_paths, apply_error = apply_project_files_safely(project_path, fix_response["files"])
        if not ok:
            repair_error_log = apply_error
            last_failure_payload = {
                "failure_stage": "runtime_fix",
                "failure_type": "invalid_patch",
                "error_log": trim_context_text(apply_error, 2000),
            }
            emit_task_log(task_id, f"❌ 런타임 복구 패치 적용 실패: {trim_context_text(apply_error, 280)}")
            continue
        emit_task_log(task_id, f"🔧 런타임 복구 패치 적용: {json.dumps(touched_paths, ensure_ascii=False)}")

        success, build_res = run_flutter_build(project_path)
        if not success:
            repair_error_log = build_res
            last_failure_payload = {
                "failure_stage": "build",
                "failure_type": classify_failure_type(build_res),
                "error_log": trim_context_text(build_res, 2000),
            }
            emit_task_log(task_id, f"❌ 복구 빌드 실패 ({last_failure_payload['failure_type']})")
            continue

        verification = verify_release_external_data_gate(
            project_path,
            build_spec=build_spec,
            task_id=task_id,
            token_callback=log_token_usage,
            callback_log=lambda msg: emit_task_log(task_id, msg),
        )
        if verification.get("status") not in {"pass", "not_applicable"}:
            verification_summary = verification.get("summary") or "외부 데이터 핵심 기능 검증에 실패했습니다."
            verification_issues = verification.get("issues") if isinstance(verification.get("issues"), list) else []
            repair_error_log = verification_summary + ("\n" + "\n".join(verification_issues) if verification_issues else "")
            last_failure_payload = {
                "failure_stage": "verification",
                "failure_type": "external_data_verification",
                "error_log": repair_error_log,
                "verification_report": verification,
            }
            emit_task_log(task_id, f"❌ 복구 후 핵심 기능 검증 실패: {verification_summary}")
            continue

        ui_contract = refresh_task_ui_contract(
            task_id=task_id,
            project_path=project_path,
            flow_type="repair",
        )
        update_workspace_revision_status(project_path, TaskStatus.SUCCESS)
        update_task(
            task_id,
            status=TaskStatus.SUCCESS,
            apk_path=build_res,
            apk_url=build_download_url(get_task(task_id) or {"task_id": task_id}),
            active_flow="",
            build_success=1,
            verification_summary=verification.get("summary") or "",
            verification_report=verification,
            verification_passed=1 if verification.get("status") in {"pass", "not_applicable"} else None,
            ui_contract=ui_contract if ui_contract else get_ui_contract(get_task(task_id) or {}),
        )
        emit_task_log(task_id, "✅ 자가 복구에 성공했습니다. 새 APK가 준비되었습니다.")
        emit_task_log(task_id, "🎊 앱 복구 및 재빌드가 성공적으로 완료되었습니다.")
        record_assistant_response(
            task_id=task_id,
            message_type="runtime_repair_succeeded",
            endpoint="/crash",
            content="런타임 오류 수정과 검증 빌드가 모두 성공했습니다.",
            payload={
                "task_id": task_id,
                "status": TaskStatus.SUCCESS,
                "apk_url": build_download_url(get_task(task_id) or {"task_id": task_id}),
                "repair_attempts": attempt,
            },
        )
        return

    update_workspace_revision_status(project_path, TaskStatus.FAILED)
    update_task(
        task_id,
        status=TaskStatus.FAILED,
        build_success=0,
        active_flow="",
        verification_summary=(last_failure_payload.get("verification_report") or {}).get("summary", ""),
        verification_report=last_failure_payload.get("verification_report") or {},
        verification_passed=0 if last_failure_payload.get("verification_report") else None,
    )
    emit_task_log(task_id, "재빌드 중 자가 복구에 실패했습니다.")
    record_assistant_response(
        task_id=task_id,
        message_type="runtime_repair_failed",
        endpoint="/crash",
        content="런타임 오류 수정 패치를 반복 적용했지만 검증 빌드 또는 핵심 기능 검증이 실패했습니다.",
        payload={
            "task_id": task_id,
            "status": TaskStatus.FAILED,
            "stack_trace": stack_trace,
            **last_failure_payload,
        },
    )

# ------------------------------------------------
# SERVER RUNNER
# ------------------------------------------------

if __name__ == "__main__":
    # 외부 접속 허용을 위해 0.0.0.0으로 호스트 설정
    uvicorn.run(
        app,
        host="192.168.0.33", # 실제 서버 IP로 변경
        port=8000
    )
