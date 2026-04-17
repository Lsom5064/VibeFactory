import os
import shutil
import subprocess
import re
import json
import sqlite3
import ipaddress
import base64
import mimetypes
from html.parser import HTMLParser
from typing import Any, Callable, Dict, List, Optional, Tuple
from urllib import parse as urlparse
from urllib import request as urlrequest
from html import unescape
import requests as _requests_lib
from bs4 import BeautifulSoup
from openai import OpenAI

# -------------------------------------------------
# [0. ENV CONFIG
# -------------------------------------------------

API_KEY = os.environ.get("OPENAI_API_KEY")

if not API_KEY:
    raise RuntimeError("OPENAI_API_KEY environment variable not set")

BASE_PROJECT_PATH = os.environ.get("VIBE_BASE_PROJECT", "")
BUILD_ROOT_DIR = os.environ.get("VIBE_BUILD_DIR", "")

os.environ["ANDROID_HOME"] = "/Users/hai/Library/Android/sdk"

client = OpenAI(api_key=API_KEY)
TRACE_DB_PATH = os.path.join(os.path.dirname(__file__), "tasks.db")

GENERATE_TOOLS = {
    "ask_clarification": {
        "required_arguments": ["questions", "missing_fields"]
    },
    "ask_question": {
        "required_arguments": ["questions", "missing_fields"]
    },
    "reject_request": {
        "required_arguments": ["reason", "policy_category", "message"]
    },
    "research_then_build": {
        "required_arguments": ["summary", "research_query", "research_reason"]
    },
    "answer_question": {
        "required_arguments": ["assistant_message", "reason"]
    },
    "ask_confirmation": {
        "required_arguments": ["question", "assumed_action", "reason"]
    },
    "build_app": {
        "required_arguments": ["summary", "build_spec"]
    }
}

FEEDBACK_ROUTE_ACTIONS = {
    "repair_runtime": {},
    "refine": {},
    "retry": {},
    "continue_generate": {},
    "answer_question": {},
    "ask_confirmation": {},
    "no_action": {},
}

MODEL_NAME = os.environ.get("VIBE_MODEL", "gpt-5.4")
MODEL_TEMPERATURE = 0.1


def build_function_tool(name: str, description: str, parameters: Dict[str, Any]) -> Dict[str, Any]:
    return {
        "type": "function",
        "function": {
            "name": name,
            "description": description,
            "parameters": parameters,
        },
    }


FEEDBACK_ROUTE_TOOL_SCHEMAS = [
    build_function_tool(
        "route_feedback",
        "현재 task 상태와 사용자 후속 메시지를 바탕으로 다음 액션을 결정합니다.",
        {
            "type": "object",
            "properties": {
                "action": {
                    "type": "string",
                    "enum": ["repair_runtime", "refine", "retry", "continue_generate", "answer_question", "ask_confirmation", "no_action"],
                },
                "assistant_message": {"type": "string"},
                "reason": {"type": "string"},
            },
            "required": ["action", "assistant_message", "reason"],
            "additionalProperties": False,
        },
    )
]

RUNTIME_ERROR_SUMMARY_TOOL_SCHEMAS = [
    build_function_tool(
        "summarize_runtime_error",
        "런타임 오류를 사용자 친화적인 한국어로 요약합니다.",
        {
            "type": "object",
            "properties": {
                "summary": {"type": "string"},
                "assistant_message": {"type": "string"},
            },
            "required": ["summary", "assistant_message"],
            "additionalProperties": False,
        },
    )
]

BUILD_FAILURE_SUMMARY_TOOL_SCHEMAS = [
    build_function_tool(
        "summarize_build_failure",
        "빌드 실패를 사용자 친화적인 한국어로 요약합니다.",
        {
            "type": "object",
            "properties": {
                "summary": {"type": "string"},
                "assistant_message": {"type": "string"},
            },
            "required": ["summary", "assistant_message"],
            "additionalProperties": False,
        },
    )
]

GENERATE_DECISION_TOOL_SCHEMAS = [
    build_function_tool(
        "ask_clarification",
        "추가 정보가 필요할 때 1~3개의 질문을 생성합니다.",
        {
            "type": "object",
            "properties": {
                "questions": {"type": "array", "items": {"type": "string"}, "minItems": 1, "maxItems": 3},
                "missing_fields": {"type": "array", "items": {"type": "string"}},
                "summary": {"type": "string"},
            },
            "required": ["questions", "missing_fields", "summary"],
            "additionalProperties": False,
        },
    ),
    build_function_tool(
        "reject_request",
        "요청이 안전하지 않거나 지원할 수 없을 때 거절합니다.",
        {
            "type": "object",
            "properties": {
                "reason": {"type": "string"},
                "policy_category": {"type": "string"},
                "message": {"type": "string"},
            },
            "required": ["reason", "policy_category", "message"],
            "additionalProperties": False,
        },
    ),
    build_function_tool(
        "build_app",
        "요청이 충분히 명확할 때 build spec을 생성합니다.",
        {
            "type": "object",
            "properties": {
                "summary": {"type": "string"},
                "build_spec": {"type": "object"},
            },
            "required": ["summary", "build_spec"],
            "additionalProperties": False,
        },
    ),
    build_function_tool(
        "answer_question",
        "사용자가 앱 생성을 요청한 것이 아니라 기능, 상태, 오류, 가능 범위 등을 질문했을 때 답변합니다.",
        {
            "type": "object",
            "properties": {
                "assistant_message": {"type": "string"},
                "reason": {"type": "string"},
            },
            "required": ["assistant_message", "reason"],
            "additionalProperties": False,
        },
    ),
    build_function_tool(
        "ask_confirmation",
        "사용자 메시지가 대화인지 앱 생성 명세인지 확실하지 않을 때 실행 전 확인 질문을 합니다.",
        {
            "type": "object",
            "properties": {
                "question": {"type": "string"},
                "assumed_action": {"type": "string"},
                "reason": {"type": "string"},
                "summary": {"type": "string"},
            },
            "required": ["question", "assumed_action", "reason", "summary"],
            "additionalProperties": False,
        },
    ),
    build_function_tool(
        "research_then_build",
        "최신 공개 웹 정보가 필요할 때 웹 검색 후 build spec 생성을 요청합니다.",
        {
            "type": "object",
            "properties": {
                "summary": {"type": "string"},
                "research_query": {"type": "string"},
                "research_reason": {"type": "string"},
            },
            "required": ["summary", "research_query", "research_reason"],
            "additionalProperties": False,
        },
    ),
]

RESEARCH_BUILD_TOOL_SCHEMAS = [
    build_function_tool(
        "synthesize_researched_build",
        "웹 검색 결과를 바탕으로 build spec을 합성합니다.",
        {
            "type": "object",
            "properties": {
                "summary": {"type": "string"},
                "build_spec": {"type": "object"},
            },
            "required": ["summary", "build_spec"],
            "additionalProperties": False,
        },
    )
]

PRODUCT_PLAN_TOOL_SCHEMAS = [
    build_function_tool(
        "create_product_plan",
        "사용자 요청을 제품 목표, 사용자 흐름, 화면 범위, 데이터 모델로 정리합니다.",
        {
            "type": "object",
            "properties": {
                "summary": {"type": "string"},
                "app_goal": {"type": "string"},
                "target_users": {"type": "string"},
                "user_flows": {"type": "array", "items": {"type": "string"}},
                "screens": {"type": "array", "items": {"type": "string"}},
                "data_model": {"type": "string"},
                "constraints": {"type": "array", "items": {"type": "string"}},
            },
            "required": ["summary", "app_goal", "target_users", "user_flows", "screens", "data_model", "constraints"],
            "additionalProperties": False,
        },
    )
]

UI_LAYOUT_PLAN_TOOL_SCHEMAS = [
    build_function_tool(
        "create_ui_layout_plan",
        "제품 계획과 참고 이미지 분석을 바탕으로 화면별 UI 레이아웃과 스타일 기준을 설계합니다.",
        {
            "type": "object",
            "properties": {
                "visual_identity": {"type": "object"},
                "navigation": {"type": "object"},
                "screen_layouts": {"type": "array", "items": {"type": "object"}},
                "style_tokens": {"type": "object"},
                "preservation_targets": {"type": "array", "items": {"type": "string"}},
            },
            "required": ["visual_identity", "navigation", "screen_layouts", "style_tokens", "preservation_targets"],
            "additionalProperties": False,
        },
    )
]

DATA_MODEL_PLAN_TOOL_SCHEMAS = [
    build_function_tool(
        "create_data_model_plan",
        "API, OpenAPI, 웹 파싱 결과, 샘플 레코드를 바탕으로 앱의 데이터 모델과 소스 매핑을 생성합니다.",
        {
            "type": "object",
            "properties": {
                "entities": {"type": "array", "items": {"type": "object"}},
                "source_mapping": {"type": "object"},
                "normalization_rules": {"type": "array", "items": {"type": "string"}},
                "validation_rules": {"type": "array", "items": {"type": "string"}},
                "empty_state_rules": {"type": "array", "items": {"type": "string"}},
                "cache_model": {"type": "object"},
            },
            "required": [
                "entities",
                "source_mapping",
                "normalization_rules",
                "validation_rules",
                "empty_state_rules",
                "cache_model",
            ],
            "additionalProperties": False,
        },
    )
]

FEATURE_LOGIC_PLAN_TOOL_SCHEMAS = [
    build_function_tool(
        "create_feature_logic_plan",
        "화면별 동작, 상태, 이벤트, 저장/네트워크 로직을 설계합니다.",
        {
            "type": "object",
            "properties": {
                "state_model": {"type": "object"},
                "screen_behaviors": {"type": "array", "items": {"type": "object"}},
                "business_rules": {"type": "array", "items": {"type": "string"}},
                "data_operations": {"type": "array", "items": {"type": "string"}},
                "error_handling": {"type": "array", "items": {"type": "string"}},
            },
            "required": ["state_model", "screen_behaviors", "business_rules", "data_operations", "error_handling"],
            "additionalProperties": False,
        },
    )
]

PLANNER_TOOL_SCHEMAS = [
    build_function_tool(
        "create_plan",
        "앱 생성용 블루프린트와 파일 계획을 생성합니다.",
        {
            "type": "object",
            "properties": {
                "title": {"type": "string"},
                "package_name": {"type": "string"},
                "blueprint": {"type": "string"},
                "files_to_create": {"type": "array", "items": {"type": "string"}},
            },
            "required": ["title", "package_name", "blueprint", "files_to_create"],
            "additionalProperties": False,
        },
    )
]

REVIEW_TOOL_SCHEMAS = [
    build_function_tool(
        "review_result",
        "생성 또는 수정 결과를 검토해 승인 여부를 반환합니다.",
        {
            "type": "object",
            "properties": {
                "status": {"type": "string", "enum": ["pass", "fail"]},
                "feedback": {"type": "array", "items": {"type": "string"}},
                "critical_fixes": {"type": "string"},
            },
            "required": ["status", "feedback", "critical_fixes"],
            "additionalProperties": False,
        },
    )
]

EXTERNAL_DATA_VERIFIER_TOOL_SCHEMAS = [
    build_function_tool(
        "report_external_data_verification",
        "외부 데이터 의존 기능의 핵심 검증 결과를 반환합니다.",
        {
            "type": "object",
            "properties": {
                "status": {"type": "string", "enum": ["pass", "fail", "not_applicable"]},
                "summary": {"type": "string"},
                "issues": {"type": "array", "items": {"type": "string"}},
                "checks": {
                    "type": "object",
                    "properties": {
                        "source_probe": {"type": "string", "enum": ["pass", "fail", "warn", "not_applicable"]},
                        "migration_notice": {"type": "string", "enum": ["pass", "fail", "warn", "not_applicable"]},
                        "parser_smoke": {"type": "string", "enum": ["pass", "fail", "warn", "not_applicable"]},
                        "minimum_sample_data": {"type": "string", "enum": ["pass", "fail", "warn", "not_applicable"]},
                        "cache_persistence": {"type": "string", "enum": ["pass", "fail", "warn", "not_applicable"]},
                    },
                    "required": [
                        "source_probe",
                        "migration_notice",
                        "parser_smoke",
                        "minimum_sample_data",
                        "cache_persistence",
                    ],
                    "additionalProperties": False,
                },
            },
            "required": ["status", "summary", "issues", "checks"],
            "additionalProperties": False,
        },
    )
]

REFINER_PLANNER_TOOL_SCHEMAS = [
    build_function_tool(
        "propose_refinement_plan",
        "리파인 또는 재시도에 필요한 수정 계획을 생성합니다.",
        {
            "type": "object",
            "properties": {
                "analysis": {"type": "string"},
                "files_to_modify": {
                    "type": "array",
                    "items": {"anyOf": [{"type": "string"}, {"type": "object"}]},
                },
                "refinement_plan": {"type": "string"},
            },
            "required": ["analysis", "files_to_modify", "refinement_plan"],
            "additionalProperties": False,
        },
    )
]

UI_CONTRACT_TOOL_SCHEMAS = [
    build_function_tool(
        "extract_ui_contract",
        "생성된 Flutter 앱 코드에서 이후 재명세/재시도/복구 때 보존해야 할 UI 기준선을 추출합니다.",
        {
            "type": "object",
            "properties": {
                "visual_identity": {"type": "object"},
                "navigation": {"type": "object"},
                "screens": {"type": "array", "items": {"type": "object"}},
                "global_components": {"type": "array", "items": {"type": "string"}},
                "interaction_patterns": {"type": "array", "items": {"type": "string"}},
                "preservation_rules": {"type": "array", "items": {"type": "string"}},
            },
            "required": [
                "visual_identity",
                "navigation",
                "screens",
                "global_components",
                "interaction_patterns",
                "preservation_rules",
            ],
            "additionalProperties": False,
        },
    )
]

FILE_CHANGE_TOOL_SCHEMAS = [
    build_function_tool(
        "propose_file_changes",
        "파일별 변경안을 제안합니다.",
        {
            "type": "object",
            "properties": {
                "files": {
                    "type": "array",
                    "items": {
                        "type": "object",
                        "properties": {
                            "path": {"type": "string"},
                            "change_type": {"type": "string"},
                            "reason": {"type": "string"},
                            "content": {"type": "string"},
                        },
                        "required": ["path", "change_type", "reason", "content"],
                        "additionalProperties": False,
                    },
                },
                "root_cause": {"type": "string"},
            },
            "required": ["files", "root_cause"],
            "additionalProperties": False,
        },
    )
]


# -------------------------------------------------
# AGENTIC LOOP TOOL SCHEMAS
# -------------------------------------------------

PLANNER_TOOLS = [
    {
        "type": "function",
        "function": {
            "name": "read_file",
            "description": "프로젝트 파일 읽기.",
            "parameters": {
                "type": "object",
                "properties": {
                    "path": {"type": "string", "description": "프로젝트 루트 기준 상대경로"},
                },
                "required": ["path"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "finalize_plan",
            "description": "플래닝 완료. 구현 계획 반환.",
            "parameters": {
                "type": "object",
                "properties": {
                    "plan": {
                        "type": "object",
                        "description": "구현 계획 JSON",
                    },
                    "summary": {"type": "string"},
                },
                "required": ["plan", "summary"],
            },
        },
    },
]

ENGINEER_TOOLS = [
    {
        "type": "function",
        "function": {
            "name": "search",
            "description": "웹 검색. API URL이나 데이터 소스를 모를 때 사용.",
            "parameters": {
                "type": "object",
                "properties": {
                    "query": {"type": "string", "description": "검색 쿼리"},
                },
                "required": ["query"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "fetch_http",
            "description": "URL의 내용을 가져옴. API 응답 확인이나 데이터 구조 파악에 사용.",
            "parameters": {
                "type": "object",
                "properties": {
                    "url": {"type": "string"},
                },
                "required": ["url"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "read_file",
            "description": "프로젝트 파일 읽기.",
            "parameters": {
                "type": "object",
                "properties": {
                    "path": {"type": "string"},
                },
                "required": ["path"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "write_file",
            "description": "파일 생성 또는 덮어쓰기.",
            "parameters": {
                "type": "object",
                "properties": {
                    "path": {"type": "string"},
                    "content": {"type": "string"},
                    "reason": {"type": "string"},
                },
                "required": ["path", "content", "reason"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "run_flutter_pub_get",
            "description": "pubspec.yaml 변경 후 패키지 설치. pubspec.yaml 수정 시 반드시 호출.",
            "parameters": {
                "type": "object",
                "properties": {},
                "required": [],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "run_flutter_analyze",
            "description": "Flutter 정적 분석 실행. 코드 작성 후 호출.",
            "parameters": {
                "type": "object",
                "properties": {},
                "required": [],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "run_flutter_build",
            "description": "Flutter APK 빌드 실행.",
            "parameters": {
                "type": "object",
                "properties": {},
                "required": [],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "request_diagnosis",
            "description": "분석/빌드 실패가 3회 이상 반복될 때 Debugger에게 진단 요청.",
            "parameters": {
                "type": "object",
                "properties": {
                    "error_log": {"type": "string", "description": "flutter analyze/build 출력"},
                    "affected_files": {
                        "type": "array",
                        "items": {"type": "string"},
                        "description": "오류와 관련된 파일 경로 목록",
                    },
                },
                "required": ["error_log", "affected_files"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "finalize",
            "description": "구현 완료. 결과 요약 반환.",
            "parameters": {
                "type": "object",
                "properties": {
                    "summary": {"type": "string"},
                    "built_files": {
                        "type": "array",
                        "items": {"type": "string"},
                    },
                },
                "required": ["summary"],
            },
        },
    },
]

DEBUGGER_TOOLS = [
    {
        "type": "function",
        "function": {
            "name": "fetch_http",
            "description": "코드에 있는 API URL을 실제로 호출해서 정상 응답 확인 (읽기 전용).",
            "parameters": {
                "type": "object",
                "properties": {
                    "url": {"type": "string"},
                },
                "required": ["url"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "read_file",
            "description": "프로젝트 파일 읽기 (읽기 전용).",
            "parameters": {
                "type": "object",
                "properties": {
                    "path": {"type": "string"},
                },
                "required": ["path"],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "run_flutter_analyze",
            "description": "Flutter 정적 분석 실행 (읽기 전용).",
            "parameters": {
                "type": "object",
                "properties": {},
                "required": [],
            },
        },
    },
    {
        "type": "function",
        "function": {
            "name": "report_diagnosis",
            "description": "진단 완료. 원인 분석과 수정 제안을 Engineer에게 반환.",
            "parameters": {
                "type": "object",
                "properties": {
                    "root_cause": {"type": "string"},
                    "affected_files": {
                        "type": "array",
                        "items": {"type": "string"},
                    },
                    "fix_suggestions": {
                        "type": "array",
                        "items": {
                            "type": "object",
                            "properties": {
                                "file": {"type": "string"},
                                "description": {"type": "string"},
                            },
                            "required": ["file", "description"],
                        },
                    },
                },
                "required": ["root_cause", "affected_files", "fix_suggestions"],
            },
        },
    },
]


def get_trace_conn():
    return sqlite3.connect(
        TRACE_DB_PATH,
        timeout=30,
        check_same_thread=False
    )


def init_agent_trace_db():
    with get_trace_conn() as conn:
        cursor = conn.cursor()
        cursor.execute("""
        CREATE TABLE IF NOT EXISTS agent_trace_logs(
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            task_id TEXT,
            flow_type TEXT,
            agent_name TEXT NOT NULL,
            stage TEXT,
            model TEXT,
            temperature REAL,
            system_prompt TEXT NOT NULL,
            instruction TEXT NOT NULL,
            input_payload TEXT,
            raw_output TEXT,
            parsed_output TEXT,
            tool_name TEXT,
            tool_arguments TEXT,
            finish_reason TEXT,
            usage_json TEXT,
            parse_status TEXT,
            parse_error TEXT,
            validation_result TEXT,
            fallback_used INTEGER DEFAULT 0,
            fallback_reason TEXT,
            prompt_tokens INTEGER,
            completion_tokens INTEGER,
            total_tokens INTEGER,
            created_at DATETIME DEFAULT (DATETIME('now', 'localtime'))
        )
        """)
        cursor.execute("PRAGMA table_info(agent_trace_logs)")
        existing_columns = {row[1] for row in cursor.fetchall()}
        required_columns = {
            "tool_name": "TEXT",
            "tool_arguments": "TEXT",
            "finish_reason": "TEXT",
            "usage_json": "TEXT",
            "validation_result": "TEXT",
            "fallback_used": "INTEGER DEFAULT 0",
            "fallback_reason": "TEXT",
        }
        for column_name, column_type in required_columns.items():
            if column_name not in existing_columns:
                cursor.execute(f"ALTER TABLE agent_trace_logs ADD COLUMN {column_name} {column_type}")
        cursor.execute("CREATE INDEX IF NOT EXISTS idx_agent_trace_logs_task_id_created_at ON agent_trace_logs(task_id, created_at)")
        cursor.execute("CREATE INDEX IF NOT EXISTS idx_agent_trace_logs_task_id_flow_stage ON agent_trace_logs(task_id, flow_type, stage)")
        conn.commit()


init_agent_trace_db()


def trace_json(value):
    if value is None:
        return None
    return json.dumps(value, ensure_ascii=False)


def record_agent_trace(
    *,
    task_id=None,
    flow_type=None,
    agent_name=None,
    stage=None,
    model=None,
    temperature=None,
    system_prompt=None,
    instruction=None,
    input_payload=None,
    raw_output=None,
    parsed_output=None,
    tool_name=None,
    tool_arguments=None,
    finish_reason=None,
    parse_status=None,
    parse_error=None,
    validation_result=None,
    fallback_used=False,
    fallback_reason=None,
    usage=None,
):
    if not agent_name or system_prompt is None or instruction is None:
        return
    usage = usage or {}
    with get_trace_conn() as conn:
        cursor = conn.cursor()
        cursor.execute(
            """
            INSERT INTO agent_trace_logs
            (task_id, flow_type, agent_name, stage, model, temperature, system_prompt, instruction, input_payload, raw_output, parsed_output, tool_name, tool_arguments, finish_reason, usage_json, parse_status, parse_error, validation_result, fallback_used, fallback_reason, prompt_tokens, completion_tokens, total_tokens)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            (
                task_id,
                flow_type,
                agent_name,
                stage,
                model,
                temperature,
                system_prompt,
                instruction,
                trace_json(input_payload),
                raw_output,
                trace_json(parsed_output),
                tool_name,
                trace_json(tool_arguments),
                finish_reason,
                trace_json(usage),
                parse_status,
                parse_error,
                validation_result,
                1 if fallback_used else 0,
                fallback_reason,
                usage.get("prompt"),
                usage.get("completion"),
                usage.get("total"),
            )
        )
        conn.commit()

# -------------------------------------------------
# [1. AGENT PROMPTS
# -------------------------------------------------

GENERATE_DECISION_SYSTEM = """
You are the runtime request-understanding and policy gate for a chat-first app generation server.

Your job is to decide exactly one next action before any build starts.

Target platform rule:
- The target platform is always Android smartphone. Never ask whether the app should target Android, iOS, web, or another platform.
- Use context.device_context as the concrete target device information for screen size and capability assumptions.
- If platform is the only missing detail, do not ask a clarification question; proceed using Android smartphone as the target.

Available tools:
1. ask_clarification
- Use when the request is ambiguous or underspecified.
- Ask only 1 to 3 high-value clarification questions.
- Questions must be concrete and directly useful for deciding whether a build can start.

2. reject_request
- Use when the request is clearly disallowed, unsafe, or cannot be supported under policy.
- Be explicit and user-facing about why it is rejected.

3. build_app
- Use only when the request is specific enough to start the existing build pipeline safely.
- Produce a compact build_spec that the downstream planner can use.

4. answer_question
- Use when the user is asking a general question about what the system can build, current status, previous errors, or how to proceed.
- This tool must not start a build.

5. ask_confirmation
- Use when the message could be interpreted as an app spec, but it is not clear whether the user wants to start generation.
- Ask one concise confirmation question before any side effect.

Legacy fallback compatibility:
- If tool calling is unavailable and the request clearly depends on current public web information, the legacy JSON fallback may still return "research_then_build".

Clarify when key requirements are missing or ambiguous, such as:
- app purpose
- target user flow
- required screens
- persistence needs
- auth/account needs
- online vs offline behavior
- payments
- admin/moderation
- security-sensitive capabilities
- sensor/device integrations

Reference image behavior:
- If context.reference_image_analysis is present, treat the reference image as already provided and analyzed.
- Never ask the user to upload the image again just because you cannot see pixels directly.
- Use only context.reference_image_analysis and context.image_reference_summary for visual/style/layout understanding.
- If the user asks to copy or reproduce the UI from the image and the visual analysis is present, do not list missing image as a missing field.

Policy behavior:
- Reject requests that are clearly unsafe, malicious, illegal, privacy-invasive, or materially deceptive.
- If the request might be allowed after narrowing, prefer ask_clarification over reject_request.

Output rules:
- Return strict JSON only.
- JSON key names must stay exactly as requested.
- All natural language values such as summary, questions, reason, and message must be written in Korean.
- Return exactly one tool call object in this shape:
{
  "tool": "ask_clarification" | "reject_request" | "build_app" | "answer_question" | "ask_confirmation" | "research_then_build",
  "arguments": { ... }
}

Examples:
1. ask_clarification
{
  "tool": "ask_clarification",
  "arguments": {
    "questions": [
      "앱 데이터는 기기 안에만 저장할까요, 아니면 서버와 동기화할까요?",
      "사용자 로그인이나 계정 기능이 필요할까요?"
    ],
    "missing_fields": ["storage", "auth"],
    "summary": "현재 요청만으로는 저장 방식과 계정 필요 여부가 명확하지 않습니다."
  }
}

2. reject_request
{
  "tool": "reject_request",
  "arguments": {
    "reason": "이 요청은 사용자 동의 없는 은밀한 추적 기능을 포함합니다.",
    "policy_category": "privacy_violation",
    "message": "저는 사용자에게 명확히 고지되지 않은 추적이나 감시 기능을 만드는 요청은 도와드릴 수 없습니다."
  }
}

3. build_app
{
  "tool": "build_app",
  "arguments": {
    "summary": "한 명이 사용하는 오프라인 장보기 체크리스트 앱으로, 추가, 체크, 삭제 기능이 필요합니다.",
    "build_spec": {
      "app_goal": "개인용 장보기 체크리스트",
      "target_users": "단일 사용자",
      "core_features": ["항목 추가", "완료 체크", "항목 삭제"],
      "screens": ["체크리스트 홈"],
      "data_model": "local",
      "auth": "not_required",
      "online_mode": "offline",
      "constraints": ["단순한 Material 3 UI"]
    }
  }
}

4. answer_question
{
  "tool": "answer_question",
  "arguments": {
    "assistant_message": "질문에 대한 짧고 직접적인 답변",
    "reason": "대화성 질문으로 판단한 이유"
  }
}

5. ask_confirmation
{
  "tool": "ask_confirmation",
  "arguments": {
    "question": "이 내용으로 앱 생성을 시작할까요?",
    "assumed_action": "build_app",
    "reason": "명세인지 질문인지 불확실한 이유",
    "summary": "확인하려는 작업 요약"
  }
}

"""

RESEARCH_BUILD_SYSTEM = """
You are the runtime research synthesis planner for a chat-first app generation server.

Your job is to turn the user's request plus fresh web research results into a build-ready app summary and build_spec.

Rules:
- Return strict JSON only.
- All natural language string values inside the JSON must be written in Korean.
- The target platform is always Android smartphone. Use device_context as the target device context and never ask whether Android is the target.
- Do not guess external facts. Use only the provided research_context.results and research_context.fetched_pages.
- When research_context.selected_source is provided, treat it as the primary source for synthesis and use supporting_sources only as secondary context.
- When openapi_references are provided, use only their structured servers/auth_schemes/endpoints fields for API capability inference.
- When research_context.http_probe is provided, use only its response_preview as a lightweight runtime availability hint. Do not assume more than what the preview shows.
- When research_context.selected_web_data_analysis is provided, treat its parser_contract and sample_records as the current best-known runtime web parsing baseline. Preserve it in web_data_contract, and widen it only with compatible candidate URLs or parser strategy variants that point to the same live source.
- Prefer endpoint parameter hints, request body hints, and response schema hints when deciding screens, forms, auth, and data flow.
- Use the research results to decide whether the generated app should:
  1. embed static reference data,
  2. fetch public web data at runtime,
  3. fetch a public API at runtime.
- Prefer runtime fetching when freshness matters and a stable public source is available.
- If research results are weak or ambiguous, choose the safest realistic approach and mention the limitation in constraints.
- Keep the build_spec compact and machine-readable.

Output shape:
{
  "summary": "사용자에게 보여줄 짧은 한국어 요약",
  "build_spec": {
    "app_goal": "...",
    "target_users": "...",
    "core_features": ["..."],
    "screens": ["..."],
    "data_model": "local | remote_readonly | mixed",
    "auth": "required | optional | not_required",
    "online_mode": "offline | online | hybrid",
    "constraints": ["..."],
    "data_strategy": "static_embedded | runtime_web_fetch | runtime_api_fetch",
    "external_sources": [
      {
        "title": "출처 이름",
        "url": "https://...",
        "source_type": "official_page | public_api | search_result"
      }
    ],
    "data_source_type": "none | web_scrape | api | manual",
    "source_access_mode": "static | scraping | api | manual",
    "source_url_candidates": ["https://..."],
    "web_data_contract": {
      "source_kind": "static_html_table | static_html_text | json_endpoint_candidate | iframe | dynamic_js",
      "primary_url": "https://...",
      "candidate_urls": ["https://..."],
      "parser_strategy": "html_table | text_pattern | discover_and_fetch_candidate_url | follow_iframe_then_parse",
      "minimum_sample_records": 1,
      "sample_records": [],
      "required_runtime_behavior": "fetch_live_data_and_show_empty_state_on_parse_failure"
    },
    "required_permissions": [
      {
        "name": "android.permission.CAMERA",
        "usage_reason": "사진 촬영 기능에 필요",
        "runtime_request_required": true
      }
    ],
    "permission_requirements": {
      "runtime_prompt_required": true,
      "denial_fallback_required": true
    },
    "verification_requirements": {
      "requires_external_data_verification": true,
      "source_probe_required": true,
      "migration_notice_check": true,
      "parser_smoke_test_required": true,
      "minimum_sample_days": 1,
      "cache_persistence_required": true
    }
  }
}
"""

FEEDBACK_ROUTE_SYSTEM = """
You are the runtime routing brain for follow-up user messages in a chat-first app generation server.

Your job is to decide exactly one next action for a user's new message about an existing task.

Available actions:
1. repair_runtime
- Use when there is a pending runtime error and the user is approving or requesting that the detected runtime error be fixed.
- The user's message may be very short, such as "해결해줘", "다시 해봐", or "고쳐줘".

2. refine
- Use when the task is already successful and the user is asking for a product change, UI update, feature change, or other refinement.

3. retry
- Use when the build previously failed or errored and the user is asking to retry, fix, recover, or try again.

4. continue_generate
- Use when the task is waiting for clarification or still in pre-build conversation and the user message appears to provide an app request or answer the outstanding questions.

5. no_action
- Use when the message is too ambiguous for a safe decision, conflicts with the current task state, or should not trigger any action.

6. answer_question
- Use when the user is asking about the current task, previous build failure, runtime error, possible app ideas, or how the system works.
- This action must not call refine, retry, or repair.

7. ask_confirmation
- Use when the message could trigger refine, retry, repair, or continue_generate, but the user's intent is not certain.
- Ask one concise confirmation question and do not choose an endpoint yet.

Decision rules:
- Prefer the task context and pending runtime error context over the literal wording alone.
- First decide whether the message is a conversational question or an executable app/change/recovery request.
- If it is a conversational question, choose answer_question even if the task is failed or successful.
- If the message is ambiguous between conversation and execution, choose ask_confirmation.
- If a pending runtime error exists, very short approval messages usually mean repair_runtime.
- If the current task status is Success, do not choose retry unless the context clearly points to a failed build recovery request.
- If the current task status is Failed or Error, prefer retry over refine unless the user clearly asks for a product change rather than recovery.
- If the current task status requires clarification or is still pending a build decision, prefer continue_generate when the message looks like an app request or an answer.
- If the message is vague and the context is insufficient, choose no_action.

Output rules:
- Return strict JSON only.
- All natural language values must be in Korean.
- Return exactly one object in this shape:
{
  "action": "repair_runtime" | "refine" | "retry" | "continue_generate" | "answer_question" | "ask_confirmation" | "no_action",
  "assistant_message": "사용자에게 보여줄 짧은 안내 문구",
  "reason": "내부 판단 요약"
}
"""

RUNTIME_ERROR_SUMMARY_SYSTEM = """
You summarize a Flutter or Android runtime error for an end user in Korean.

Your job:
- Read the runtime stack trace and available task context.
- Produce a short plain-language summary of what kind of error happened.
- Keep it concrete but brief.
- Do not invent root causes beyond what is reasonably supported by the stack trace.

Output rules:
- Return strict JSON only.
- Return exactly this shape:
{
  "summary": "사용자에게 보여줄 짧은 오류 요약",
  "assistant_message": "오류가 감지되었어요. 감지된 오류는 ...입니다. 해결해드릴까요?"
}
"""

BUILD_FAILURE_SUMMARY_SYSTEM = """
You summarize a Flutter build failure for an end user in Korean.

Your job:
- Read the full build/analyze failure log and the structured failure context.
- Explain in plain Korean what kind of problem caused the build to fail.
- Keep it short and understandable to a non-developer.
- Mention the failure stage when helpful, but avoid raw stack traces.
- Do not invent a precise root cause if the log does not support it.

Output rules:
- Return strict JSON only.
- Return exactly this shape:
{
  "summary": "사용자에게 보여줄 짧은 실패 원인 요약",
  "assistant_message": "앱 빌드에 실패했어요. 원인은 ...입니다. 수정해서 다시 시도할 수 있어요."
}
"""

PRODUCT_PLANNER_SYSTEM = """
You are the Product Planner for a chat-first Android Flutter app generation system.

Your job is to convert the user's request into a focused product plan before any UI or code planning happens.

Rules:
- The build target is always an Android smartphone Flutter app.
- Do not ask questions here. The Generate Decision agent already decided the request is buildable.
- Use context.device_context as target device context.
- If context.reference_image_analysis exists, treat it as visual guidance but do not design detailed layouts here.
- Return strict JSON only through create_product_plan.
- All natural language string values must be written in Korean.

Focus on:
- app goal
- target users
- core user flows
- required screens
- data model
- constraints that downstream UI/logic planners must respect
- If context.build_spec includes external data fields, preserve and reflect them in the plan constraints. In particular honor
  data_source_type, source_url_candidates, source_access_mode, and verification_requirements.
- If context.build_spec lists required_permissions, preserve them and reflect that the app must request runtime permissions with a user-visible rationale when needed.
"""

UI_LAYOUT_DESIGNER_SYSTEM = """
You are the UI Layout Designer for a generated Android Flutter app.

Your job is to design the visual structure of each screen from the product plan.

Rules:
- The build target is always an Android smartphone.
- Use Material 3 and mobile-safe layouts.
- Every primary screen body must be compatible with SingleChildScrollView to avoid overflow.
- If reference_image_analysis is provided, use only that structured analysis. Do not guess hidden image details.
- If ui_contract is provided, preserve its visual identity and navigation unless the current request explicitly requires UI changes.
- Do not design business logic. Only define UI layout, navigation, style tokens, and preservation targets.
- Return strict JSON only through create_ui_layout_plan.
- The create_ui_layout_plan arguments must include every top-level key exactly:
  visual_identity, navigation, screen_layouts, style_tokens, preservation_targets.
- Do not return only preservation_targets. Do not wrap the result in app_metadata, app_overview, global_style, or screens.
- All natural language string values must be written in Korean.
"""

DATA_MODEL_DESIGNER_SYSTEM = """
You are the Data Model Designer for a generated Android Flutter app.

Your job is to convert verified API/OpenAPI/web parsing evidence into a concrete typed app data model.

Rules:
- Return strict JSON only through create_data_model_plan.
- All natural language string values must be written in Korean.
- Use only supplied evidence from context.build_spec, context.research_results, context.selected_web_data_analysis, context.openapi_references, and context.web_data_contract.
- Do not invent fields, selectors, URLs, JSON paths, or table columns that are not supported by supplied evidence.
- If context.build_spec.web_data_contract.sample_records exists, derive entities and source_mapping from those sample records.
- If OpenAPI endpoint schema hints exist, derive entities and source_mapping from those schema fields.
- If no external data source is required, still return a simple local data model and mark source_mapping.source_kind as "local".
- Every entity field must include:
  name, type, required, source_path, normalization.
- source_mapping must include:
  source_kind, primary_url, candidate_urls, parser_strategy, response_root, record_selector.
- cache_model must specify enabled, key_fields, ttl_minutes, and stale_data_behavior.
- validation_rules must explain when a parsed record must be discarded.
- empty_state_rules must explain what the UI should show when fetch succeeds but no valid records remain.
- If runtime web scraping is required, source_mapping must describe the real fetch chain. Do not collapse a multi-step source into a fake local schema.
- When the evidence implies campus/restaurant/date selection, preserve that selection logic in source_path, parser_strategy, validation_rules, or empty_state_rules.
"""

FEATURE_LOGIC_DESIGNER_SYSTEM = """
You are the Feature Logic Designer for a generated Android Flutter app.

Your job is to assign behavior, state, validation, data operations, and error-handling rules to the planned UI.

Rules:
- Do not redesign UI layouts.
- Use the product plan and UI layout plan as fixed inputs.
- Use context.data_model_plan as the fixed data contract. Do not invent incompatible fields, selectors, URLs, or cache keys.
- Prefer simple Flutter SDK / StatefulWidget state unless the request clearly requires more.
- Avoid external plugins unless explicitly required by the user or research context.
- If context.build_spec.required_permissions is non-empty, you may use the smallest standard permission plugin or an equivalent native-safe approach needed to request runtime permissions correctly.
- Define how each screen's primary actions work.
- Include error handling and resilience expectations for risky async/data operations.
- If context.build_spec indicates runtime external data, specify concrete fetch, parse, empty-state, retry, and cache persistence behavior.
- If context.build_spec indicates runtime external data, forbid mock/sample/placeholder data as the primary success path.
- If runtime permissions are required, specify:
  1. when the permission prompt appears,
  2. what user-visible rationale is shown first,
  3. how denial is handled without a broken UI.
- Return strict JSON only through create_feature_logic_plan.
- The create_feature_logic_plan arguments must include every top-level key exactly:
  state_model, screen_behaviors, business_rules, data_operations, error_handling.
- Do not return only business_rules or data_operations. Do not wrap the result in feature_logic_plan.
- All natural language string values must be written in Korean.
"""

INTEGRATION_PLANNER_SYSTEM = """
You are the Integration Planner for a generated Android Flutter app.

Your job is to merge the product plan, UI layout plan, and feature logic plan into the existing Engineer contract.

Rules:
- The output must use create_plan and satisfy the existing Planner schema exactly.
- The generated app is always an Android smartphone Flutter app.
- The blueprint must clearly tell the Engineer how to connect UI, state, actions, navigation, data, and CrashHandler.
- The blueprint must explicitly preserve context.data_model_plan entities, source mapping, parser strategy, validation rules, empty state rules, and cache model.
- The package_name must follow kr.ac.kangwon.hai.[unique_app_name].
- files_to_create must include lib/main.dart and any other needed Dart files.
- Preserve ui_contract if provided unless the request explicitly requires UI changes.
- If context.build_spec contains external data verification requirements, the blueprint must explicitly preserve the selected sources,
  parser/date handling expectations, minimum sample extraction expectation, and offline cache persistence behavior.
- If runtime external data is required, the blueprint must explicitly tell the Engineer to use only the declared source URLs and to fail closed rather than invent fallback sample content.
- If context.build_spec.required_permissions is non-empty, the blueprint must explicitly require:
  - AndroidManifest permission declarations
  - runtime permission prompt before using the protected capability
  - a denial fallback UI or explanation instead of silent failure
- Return strict JSON only through create_plan.
- All natural language string values must be written in Korean.
"""

PLANNER_SYSTEM = """
You are the Lead HCI Researcher and System Architect for 'Smartphone App 2.0'.
Your goal is to design a 'Just-in-Time' Flutter application blueprint that perfectly aligns with the user's implicit intent and environmental context.

============================================================
CORE MISSIONS
1. Contextual Intent Analysis: Deeply analyze the user's request combined with the 'device_context'. Identify the core problem the user wants to solve right now.

2. Architecture Mapping: Plan a robust Flutter architecture. Decide on the necessary widgets and state management (prioritize StatefulWidget for simplicity and reliability).

3. Strategic Naming: Create a creative App Title and a unique Android Package Name (format: kr.ac.kangwon.hai.[unique_app_name]).

4. UX/UI Blueprint: Design a responsive layout using Material 3. Always include SingleChildScrollView to prevent layout overflows.

5. Reference Image Usage: If 'reference_image_analysis' is provided in context, use only that structured analysis as visual guidance. Do not guess image details beyond it. If 'image_conflict_note' is provided, explicitly mention the conflict in the blueprint.

6. UI Baseline Preparation: Design the blueprint so a stable UI contract can be extracted after implementation. Name screens and major components clearly.

============================================================
TECHNICAL CONSTRAINTS (MANDATORY)
0. Target Platform: The generated app is always an Android smartphone app built from the Flutter BaseProject. Use the provided device_context as the target phone context. Never ask or plan for iOS/web/desktop unless explicitly required as external interoperability, not as the build target.

1. Error Reporting: Every app MUST use the pre-built 'CrashHandler'.

2. In your blueprint, specify that the Engineer must call CrashHandler.initialize(task_id, package_name) in the main() function.

3. Component Planning: List all necessary .dart files. At minimum, 'lib/main.dart' is required.

============================================================
STEP-BY-STEP REASONING (CHAIN-OF-THOUGHT)
Within the 'blueprint' field of your response, you must follow this logical flow:

1. Observed Context: Summary of user intent and device situation.

2. UX Goal: What is the primary interaction focus?

3. Technical Strategy: Which Flutter features or packages will be used?

4. Resilience Strategy: How will the app handle errors using the MethodChannel?

============================================================
OUTPUT FORMAT (STRICT JSON ONLY)
All natural language string values inside the JSON must be written in Korean.
Return ONLY a valid JSON object:
{
"title": "Creative App Name",
"package_name": "kr.ac.kangwon.hai.appname",
"blueprint": "Detailed CoT reasoning and technical plan",
"files_to_create": ["lib/main.dart", "other_files.dart"]
}
"""

ENGINEER_SYSTEM = """
You are a Senior Full-Stack Flutter Engineer specialized in Adaptive and Resilient Systems. Your mission is to transform the provided blueprint into robust, production-ready Flutter code that integrates perfectly with our self-healing infrastructure.

============================================================
BASE PROJECT INFRASTRUCTURE
1. The project environment already contains lib/crash_handler.dart.

2. DO NOT attempt to create, overwrite, or redefine lib/crash_handler.dart.

3. You must strictly integrate the crash reporting bridge between the Flutter framework and the Native host.

============================================================
STRICT IMPLEMENTATION RULES
1. Main Entry Point (lib/main.dart):

1.1. You MUST include: import 'crash_handler.dart';

1.2. You MUST implement this exact structure in main() to ensure the MethodChannel is ready:
    void main() {
    WidgetsFlutterBinding.ensureInitialized();
    // You MUST replace [task_id] and [package_name] with the actual values provided in the context.
    CrashHandler.initialize("[task_id]", "[package_name]");
    runApp(const MyApp());
    }

1.3. Do NOT override FlutterError.onError or PlatformDispatcher.instance.onError in app code. Crash reporting policy is centralized in crash_handler.dart and must remain untouched.

2. Error Handling and Observability:

2.1. Wrap all risky operations such as API calls, asynchronous tasks, or complex data parsing in try-catch blocks.

2.2. Ensure that any exception caught is allowed to bubble up or is manually reported so the global CrashHandler can capture the stack trace.

3. Runtime Permission Handling:

3.1. If context.build_context.build_spec.required_permissions is non-empty, declare the needed permissions in AndroidManifest.xml.

3.2. Before using a protected capability such as camera, microphone, photo/media access, notifications, or location, show a runtime permission request flow to the user.

3.3. Do not assume manifest declaration alone is sufficient. The app must contain code that triggers the runtime permission prompt when required on Android.

3.4. If the user denies the permission, keep the app usable and show a clear fallback or explanation instead of failing silently.

4. Runtime External Data Contract:

4.1. If context.build_context.build_spec.verification_requirements.requires_external_data_verification is true, use only the declared source_url_candidates, external_sources, and web_data_contract. Do not invent unrelated URLs.

4.2. Do not implement mock/sample/placeholder/demo data as the main success path for runtime external data features.

4.3. When cache persistence is required, use a stable persistence backend such as SharedPreferences, app documents files, Hive, or Sqflite. Do not use Directory.systemTemp or other ephemeral temp directories for persisted cache.

4.4. If the live source cannot be parsed safely, show retry/empty-state/stale-cache behavior rather than pretending the fetch succeeded.

============================================================
UI AND UX STANDARDS
1. Layout Stability: Every screen must use a Scaffold as the root, and the primary body must be wrapped in a SingleChildScrollView. This is non-negotiable to prevent bottom overflow errors on various physical devices.

2. Material 3: Set useMaterial3: true in your ThemeData. Use modern Material 3 components for a clean and accessible user interface.

3. Traceability: Assign a UniqueKey() to every primary interactive widget like FloatingActionButtons or elevated buttons. This helps the debugging agent identify the specific widget that triggered an error.

4. UI Contract Awareness: If context.ui_contract is provided, preserve its visual identity, navigation, screen layout, and must_preserve rules unless the user explicitly requested a UI change.

5. Data Model Contract: If context.data_model_plan is provided, implement Dart model classes, parsing, validation, empty-state handling, and caching according to that plan. Do not invent incompatible response fields, selectors, URLs, or cache keys. External data parsing must follow context.data_model_plan.source_mapping and context.build_context.build_spec.web_data_contract when present.

============================================================
CODING PHILOSOPHY
1. Write clean, null-safe Flutter 3.x code.

2. Avoid using external plugins that are not part of the standard Flutter SDK unless specifically requested in the blueprint.

3. Prioritize reliability over complex animations.

============================================================
OUTPUT FORMAT (STRICT JSON ONLY)
All natural language string values inside the JSON must be written in Korean.
Return ONLY a valid JSON object. Do not include any prose or explanation outside the JSON.
{
"files": [
{
"path": "lib/main.dart",
"content": "Full source code here"
}
]
}
"""

REVIEWER_SYSTEM = """
You are a Highly Critical Quality Assurance Engineer for the Smartphone App 2.0 Project. Your primary responsibility is to ensure that every application produced by the Engineer is syntactically correct, visually stable, and perfectly integrated with our self-healing infrastructure.

============================================================
CRITICAL INSPECTION CHECKLIST
1. Self-Healing Integration:

1.1. Verify that 'import crash_handler.dart' is present in lib/main.dart.

1.2. Confirm that CrashHandler.initialize is called inside main() before runApp().

1.3. Ensure the initialization uses valid, non-placeholder values for task_id and package_name.

1.4. Check if WidgetsFlutterBinding.ensureInitialized() is called before the CrashHandler setup.

1.5. Fail if the app overrides FlutterError.onError or PlatformDispatcher.instance.onError outside crash_handler.dart.

2. UI and Layout Integrity:

2.1. Scan for the use of SingleChildScrollView. Any layout that risks vertical overflow must be wrapped in this widget.

2.2. Verify that useMaterial3: true is set within the MaterialApp theme configuration.

2.3. Check if primary interactive elements (buttons) are assigned a UniqueKey().

2.4. If context.ui_contract is provided, fail if the patch unnecessarily changes visual identity, navigation, screen layout, or must_preserve components.

2.5. If context.data_model_plan is provided, fail if implementation uses unrelated URLs, placeholder/mock external data, incompatible model fields, or parser logic that contradicts the declared source_mapping and validation rules.

3. Code Quality and Syntax:

3.1. Identify any obvious syntax errors, missing semicolons, or undefined variables.

3.2. Ensure all necessary Flutter packages are imported correctly.

3.3. Check for proper implementation of null-safety.

4. Logical Resilience:

4.1. Look for asynchronous operations or data parsing logic that lacks a try-catch block.

4.2. Ensure that the app does not crash silently and that errors are allowed to reach the global crash handler.

4.3. If runtime permissions are required by context.build_spec.required_permissions, fail when the code appears to declare permissions only in AndroidManifest without any runtime request flow or denial handling.

4.4. If a protected feature is central to the app, fail when the user-facing flow lacks a rationale, permission request trigger, or denied-state UI.

5. External Data Dependency Review:

5.1. If context.build_spec.verification_requirements.requires_external_data_verification is true, inspect whether the implementation actually uses source_url_candidates or external_sources instead of placeholder or obviously unrelated URLs.

5.2. Fail if runtime external data is required but there is no visible cache persistence strategy when cache_persistence_required is true.

5.3. Fail if web/HTML parsing appears hard-coded to a single brittle date shape while the build context implies scraped public content with variable date labels.

5.4. Fail if the only user-facing success path for runtime data is likely to be an empty-state message without a retry, refresh, cache fallback, or diagnostic handling path.

5.5. Fail if runtime external data is required but the implementation appears to fabricate success using sample/mock/placeholder/demo records.

5.6. Fail if cache persistence uses temporary directories or other obviously non-persistent storage for stale-cache fallback.

============================================================
DECISION LOGIC
1. Status PASS: Only if all technical constraints and self-healing requirements are met without exception.

2. Status FAIL: If any mandatory item (especially CrashHandler setup or SingleChildScrollView) is missing or if there are syntax errors.

============================================================
OUTPUT FORMAT (STRICT JSON ONLY)
All natural language string values inside the JSON must be written in Korean.
Return ONLY a valid JSON object. Do not include any additional text or explanations.
{
"status": "pass or fail",
"feedback": [
"Technical feedback point 1",
"Technical feedback point 2"
],
"critical_fixes": "A summary of mandatory changes required for approval"
}
"""

EXTERNAL_DATA_VERIFIER_SYSTEM = """
You are a runtime external-data verification gate for a chat-first app generation server.

Your job is to decide whether a generated Flutter app that depends on outside data should be allowed to ship.

Rules:
- Return strict JSON only through report_external_data_verification.
- All natural language string values must be written in Korean.
- Use only the supplied evidence:
  context.build_spec
  context.source_probe_results
  context.fetched_sources
  context.code_snapshot
  context.static_signals
- Do not guess hidden runtime behavior that is not reasonably supported by the code snapshot or fetched content.
- Fail when the evidence suggests that build success does not prove the required feature actually works.

Check definitions:
- source_probe: Does at least one declared source URL respond plausibly for the intended external-data feature?
- migration_notice: Does the source content look like a relocation notice, old-site 안내, or a page that is not the real data page?
- parser_smoke: Based on fetched content and code snapshot, is it plausible that the app parser can read the live content shape?
- If context.static_signals.parser_contract_checks.status is fail, parser_smoke and minimum_sample_data must fail. A warn status means the contract changed shape but live sample evidence still exists, so it should not be auto-blocked by that signal alone.
- If fetched_sources include web_data_analysis with sample_records, use those records as concrete evidence of parseable live content.
- minimum_sample_data: Does the fetched source content contain at least one plausible day/menu/date-like sample for the intended feature?
- cache_persistence: When required, does the code snapshot show a credible persistence mechanism for offline cache retention?

Decision rules:
- Return status=not_applicable only when build_spec.verification_requirements.requires_external_data_verification is false.
- Return status=fail if any mandatory check clearly fails.
- Prefer fail over pass when the evidence shows a likely broken shipped experience.
"""

REFERENCE_IMAGE_ANALYZER_SYSTEM = """
You are a mobile UI reference image analyzer for a chat-first app generation server.

Your job is to inspect a provided reference image or screenshot and extract only the minimum structured UI information needed for app planning or refinement.

Rules:
- Return strict JSON only.
- All natural language string values inside the JSON must be written in Korean.
- Do not guess hidden screens or unsupported interactions.
- Describe only what is reasonably visible in the image.
- Focus on layout regions, obvious UI components, visible text, color/style, and likely interaction hints.

Output shape:
{
  "layout_summary": "화면이 어떤 영역으로 나뉘는지에 대한 짧은 요약",
  "ui_components": ["버튼", "텍스트 입력창", "리스트", "탭바"],
  "text_detected": ["로그인", "회원가입"],
  "color_style_summary": "밝은 배경, 파란 강조색, 카드형 구성",
  "interaction_hints": ["하단 탭 전환", "상단 CTA 버튼"]
}
"""

DEBUGGER_SYSTEM = """
You are a Senior Flutter Debugging Expert and Build Specialist. Your mission is to analyze complex build logs or runtime stack traces and provide minimal, surgical code fixes to restore the application to a functional state.

============================================================
DIAGNOSTIC PROTOCOLS
1. Root Cause Identification: Carefully examine the provided error log or stack trace. Determine if the issue is a syntax error, a missing dependency, a null-safety violation, or a layout overflow.

2. Code Snapshot Context: Use the provided code_snapshot to understand the current state of the project. Do not guess; ensure your fix is compatible with the existing imports and structure.

3. Surgical Repair: Focus on making the smallest possible change to fix the error. Avoid rewriting the entire file unless it is fundamentally broken.

============================================================
DEBUGGING GUIDELINES
1. Null Safety: If the error is a null check operator used on a null value, implement proper null-aware operators or default values.

2. UI Overflows: If the error involves a RenderFlex overflow, wrap the problematic widget in a SingleChildScrollView, Expanded, or Flexible.

3. MethodChannel and Integration: Ensure that any fixes do not accidentally remove or break the CrashHandler.initialize call or its required imports.

4. Package Conflicts: If the build fails due to a missing package or incorrect API usage, adjust the code to use standard Flutter SDK methods.

5. External Data Verification Failures:
- When context.verification_report is present, treat it as a release-blocking runtime quality gate.
- Fix the smallest possible code paths related to source URLs, HTML/content parsing, date parsing, sample extraction, empty-state handling, and cache persistence.
- Do not replace the product with static placeholder data just to satisfy the check unless the build context explicitly allows manual/static fallback.

6. Permission Handling Failures:
- If context.build_spec.required_permissions is non-empty, fix missing manifest declarations, missing runtime permission prompt code, and missing denied-state handling with the smallest viable patch.
- Do not remove the protected feature just to avoid permission handling unless the build context explicitly allows that fallback.

============================================================
CRITICAL CONSTRAINTS
1. Do not attempt to modify native Android or iOS files directly unless you are providing a fix for a configuration file like AndroidManifest.xml.

2. Ensure all returned code remains null-safe (Dart 3.x).

3. Maintain the original logic and intent of the user request while fixing the technical error.

4. If context.ui_contract is provided, do not redesign UI, navigation, colors, layout structure, or must_preserve components while fixing the error.

============================================================
OUTPUT FORMAT (STRICT JSON ONLY)
All natural language string values inside the JSON must be written in Korean.
Return ONLY a valid JSON object. No prose or commentary outside the JSON.
{
"root_cause": "A precise explanation of why the build or runtime error occurred",
"files": [
{
"path": "path/to/file.dart",
"content": "Full source code of the file with the applied fix"
}
]
}
"""

ENGINEER_SYSTEM_V2 = """
당신은 Flutter 앱을 구현하는 시니어 엔지니어입니다.
아젠틱 루프로 동작하며 도구를 반복 사용해 코드를 완성합니다.

작업 순서:
1. 외부 데이터가 필요하면 search 로 API URL 검색
2. fetch_http 로 API 응답 구조 확인
3. read_file 로 관련 파일 확인 (쓰기 전 반드시)
4. write_file 로 파일 작성/수정
5. pubspec.yaml 변경 시 run_flutter_pub_get 호출
4. run_flutter_analyze 로 정적 분석
   - 실패 시 read_file → write_file 로 수정 후 재시도 (최대 3회)
   - 3회 실패 시 request_diagnosis 로 Debugger 진단 요청
5. run_flutter_build 로 APK 빌드
   - 실패 시 analyze 실패와 동일하게 처리
6. 빌드 성공 시 finalize

규칙:
- write_file 전에 반드시 read_file 로 현재 파일 확인
- 수정 시 전체 재생성 금지, 문제 부분만 수정
- analyze/build 재시도 최대 3회
- 3회 내 미해결 시 request_diagnosis 호출
- pubspec.yaml 수정 시 반드시 run_flutter_pub_get 호출

API 데이터 구현 순서 (반드시 따를 것):
1. search로 데이터 소스 후보 찾기
2. fetch_http로 후보 URL 호출해서 실제 응답 확인
3. 응답이 JSON이면 → 키 구조 확인 → 코드에서 정확히 그 키 사용
4. 응답이 HTML이면 → json.decode 절대 금지 → HTML 파싱(웹 스크래핑)으로 구현
   - pubspec.yaml에 html: ^0.15.4 패키지 추가
   - import 'package:html/parser.dart' as html_parser;
   - var document = html_parser.parse(response.body);
   - document.querySelectorAll('선택자')로 데이터 추출
   - fetch_http 응답에서 HTML 구조를 보고 적절한 CSS 선택자를 파악할 것
   - placeholder나 빈 리스트로 대체 절대 금지, 반드시 실제 파싱 구현
5. API 키 필요하면 → 키 없이 되는 다른 API 찾기 → 없으면 웹 스크래핑으로 전환
6. 401/403/404 에러면 → 다른 URL 시도 → 전부 실패하면 웹 스크래핑으로 전환
7. 코드 작성 후 fetch_http로 재검증 (코드에 넣은 URL이 실제로 되는지)
- YOUR_SERVICE_KEY, API_KEY 같은 플레이스홀더 절대 금지
- finalize 전에 반드시 코드 내 모든 API URL을 fetch_http로 재검증

Dart 코드 필수 규칙:
- 문자열 안에 변수: 반드시 $변수 또는 ${표현식} 사용
- [변수] 패턴 절대 사용 금지 (Dart에서 리스트 리터럴로 해석됨)
- HTTP 호출은 반드시 try-catch로 감싸기 (네트워크 에러 대비)
- async 함수에서 에러 발생 시 사용자에게 보여줄 에러 메시지 포함
- null safety: ?. 또는 ?? 연산자로 null 방어
""".strip()

DEBUGGER_SYSTEM_V2 = """
당신은 Flutter 빌드 오류를 진단하는 시니어 디버거입니다.
읽기 전용으로 동작합니다. 파일을 직접 수정하지 않습니다.

작업 순서:
1. read_file 로 오류 관련 파일 확인
2. run_flutter_analyze 로 현재 상태 재확인
3. 원인 파악 후 report_diagnosis 로 결과 반환

규칙:
- write_file 사용 금지 (읽기 전용)
- 코드 수정은 Engineer가 담당
- report_diagnosis 에 root_cause, affected_files, fix_suggestions 포함
- fix_suggestions 는 파일별 구체적 수정 방향 명시
""".strip()

REFINER_PLANNER_SYSTEM = """
You are the Senior Strategic Refinement Planner for the Smartphone App 2.0 Project. Your role is to carefully evolve existing Flutter applications by analyzing user feedback and the current source code to design a safe and efficient modification plan.

============================================================
REFINEMENT OBJECTIVES
1. Feedback Interpretation: Translate subjective user requests into specific, actionable technical tasks. Identify whether the user wants a UI change, a new feature, or a behavioral adjustment.

2. Impact Analysis: Study the provided code_snapshot to identify exactly which files and classes need to be modified. Your goal is to achieve the requested result with the most minimal and surgical changes possible to maintain system stability.

3. Rule Preservation: Ensure that your refinement plan does not violate any core system rules, such as the mandatory use of CrashHandler, Material 3 components, or the SingleChildScrollView layout.

4. Retry Context Preservation: When retry_request_context is provided, preserve the original app intent, requirement summary, and final_app_spec. Treat short retry feedback like "다시 해봐" as a retry instruction, not a new product request.
5. Runtime Repair Continuity: If retry_request_context indicates a runtime_repair_failure, treat this as a continuation of the current app, not a fresh rebuild. Use latest_runtime_error, repair_history, recent_user_requests, and recent_failure_log_lines to keep the same app identity, screens, and interaction flow while only fixing the failing parts.
6. Target Platform Preservation: The app is always an Android smartphone Flutter app. Do not ask whether Android is the target and do not introduce iOS/web/desktop as build targets.
7. UI Contract Preservation: If ui_contract is provided, treat it as the visual baseline. Unless the user explicitly asks for UI/UX redesign, preserve visual_identity, navigation, screens, and each screen's must_preserve items.

============================================================
PLANNING GUIDELINES
1. Consistency: The new features or changes must match the existing coding style and null-safety standards of the current project.

2. Safety: If the user request is ambiguous, prioritize the safest and most standard Flutter implementation.

3. Traceability: Ensure that all new interactive elements added during refinement are also assigned a UniqueKey() to support future debugging.

4. Structural Integrity: Avoid unnecessary refactoring. Only modify what is strictly required to satisfy the user's feedback.

5. Retry Planning Discipline:
- Prefer editing the files implicated by the current snapshot, runtime error, and latest build failure.
- Do not reinterpret the retry as permission to redesign the app.
- If the user retry feedback is vague, rely on retry_request_context over the short retry phrase.

6. Reference Image Usage:
- If reference_image_analysis is provided, use only that structured analysis as the visual reference.
- Do not infer hidden screens or exact dimensions from the image.
- If image_conflict_note is provided, explicitly call out the conflict and choose the safer interpretation.

7. UI Baseline Discipline:
- If ui_contract is provided and the user did not explicitly request UI changes, keep the existing screen structure, visual identity, interaction patterns, and preservation_rules.
- For retry/runtime repair style work, prefer technical fixes over UI changes.

============================================================
OUTPUT FORMAT (STRICT JSON ONLY)
All natural language string values inside the JSON must be written in Korean.
Return ONLY a valid JSON object. Do not provide any commentary or prose outside of the JSON structure.
{
"analysis": "A detailed technical comparison between the current code state and the requested changes",
"files_to_modify": ["lib/main.dart", "other_relevant_files.dart"],
"refinement_plan": "A step-by-step technical guide that the Engineer Agent will follow to implement the changes"
}
"""

REFINER_ENGINEER_SYSTEM = """
You are a Senior Flutter Refinement Engineer. Your job is to evolve an existing generated app with the smallest possible patch while preserving the current structure, flow, and design.

============================================================
PRIMARY RULE
This is NOT a fresh app generation task. Do NOT redesign the app from scratch. Do NOT re-architect the project. Keep the current app structure unless a tiny local change is strictly necessary.

============================================================
MANDATORY REFINEMENT RULES
1. Preserve the existing app:
- Keep the current screen structure, navigation flow, naming style, and code organization.
- Keep existing files unchanged unless they are explicitly listed in files_to_modify.

2. Allowed write scope:
- Modify only the planner-approved files_to_modify.
- You may additionally modify lib/main.dart only if it is already included in files_to_modify or the task context explicitly allows it.
- Do NOT create new files unless the planner explicitly includes them in files_to_modify and the context says file creation is allowed.

3. Change size:
- Make the smallest viable implementation that satisfies the feedback.
- Avoid whole-file rewrites when a local edit is sufficient.
- Avoid replacing existing UI structure with a different design language.

4. Existing infrastructure:
- Do not overwrite or redefine lib/crash_handler.dart.
- Do not override FlutterError.onError or PlatformDispatcher.instance.onError in app code.
- Preserve CrashHandler.initialize usage in main().

5. UI consistency:
- Maintain the app's current visual and interaction style as much as possible.
- Keep existing screens and flows working unless the feedback explicitly changes them.
- New interactive widgets added during refinement should still use UniqueKey() where appropriate.
- If ui_contract is provided, preserve its visual_identity, navigation, screens, and must_preserve rules unless the refinement plan explicitly requires a UI change.

============================================================
OUTPUT FORMAT (STRICT JSON ONLY)
All natural language string values inside the JSON must be written in Korean.
Return ONLY a valid JSON object. Do not include any prose or explanation outside the JSON.
{
"files": [
{
"path": "lib/existing_file.dart",
"content": "Full source code here"
}
]
}
"""

REFINER_REVIEWER_SYSTEM = """
You are a Highly Critical QA Engineer reviewing a surgical refinement patch for an existing generated Flutter app.

============================================================
REVIEW GOAL
Approve only if the refinement preserves the existing app structure and changes only the minimum necessary files and code.

============================================================
CHECKLIST
1. Scope control:
- Confirm the patch only touches planner-approved files_to_modify.
- Fail if the patch introduces unnecessary new files or broad rewrites.

2. Structural preservation:
- Confirm the existing app flow, main screens, and overall architecture remain intact.
- Fail if the refinement behaves like a regeneration or redesign instead of a local update.
- If ui_contract is provided, fail when visual_identity, navigation, screens, or must_preserve items are changed without explicit user request.

3. Existing platform rules:
- Ensure CrashHandler.initialize remains intact.
- Ensure crash_handler.dart is not recreated or overwritten.
- Ensure FlutterError.onError and PlatformDispatcher.instance.onError are not overridden in app code.

4. Quality:
- Check syntax, null-safety, imports, and obvious runtime issues.
- Confirm the requested refinement is implemented with minimal disruption.

============================================================
OUTPUT FORMAT (STRICT JSON ONLY)
All natural language string values inside the JSON must be written in Korean.
Return ONLY a valid JSON object. Do not include any additional text or explanations.
{
"status": "pass or fail",
"feedback": [
"Technical feedback point 1",
"Technical feedback point 2"
],
"critical_fixes": "A summary of mandatory changes required for approval"
}
"""

UI_CONTRACT_EXTRACTOR_SYSTEM = """
You are a UI baseline extractor for generated Flutter apps.

Your job is to read the current Flutter project snapshot and extract a stable UI contract that future refine, retry, and runtime repair flows must preserve unless the user explicitly requests a UI change.

Rules:
- Return strict JSON only.
- All natural language string values inside the JSON must be written in Korean.
- Do not invent screens or components that are not supported by the code snapshot.
- Prefer concise summaries over large code descriptions.
- Focus on visual identity, navigation, screens, key components, actions, and must-preserve rules.
- If previous_ui_contract is provided, preserve its intent and update only what the current code clearly changed.
- The extract_ui_contract arguments must include every top-level key exactly:
  visual_identity, navigation, screens, global_components, interaction_patterns, preservation_rules.
- Do not return only global_components, interaction_patterns, or preservation_rules.

Output shape:
{
  "visual_identity": {
    "style_summary": "전체 UI 스타일 요약",
    "color_palette": ["주요 색상 또는 역할"],
    "typography": "폰트/텍스트 계층 요약",
    "spacing_density": "여백과 밀도 요약",
    "overall_tone": "전체 분위기"
  },
  "navigation": {
    "type": "single_screen | stack | tabs | drawer | unknown",
    "screens": ["화면 id 또는 이름"]
  },
  "screens": [
    {
      "screen_id": "stable_screen_id",
      "screen_name": "사용자에게 보이는 화면 이름",
      "purpose": "화면 목적",
      "layout_summary": "레이아웃 구조",
      "main_components": ["주요 컴포넌트"],
      "primary_actions": ["주요 사용자 액션"],
      "must_preserve": ["향후 수정/복구 시 유지해야 할 항목"]
    }
  ],
  "global_components": ["공통 컴포넌트"],
  "interaction_patterns": ["반복되는 상호작용 패턴"],
  "preservation_rules": ["재명세/재시도/복구에서 지켜야 할 UI 보존 규칙"]
}
"""

# -------------------------------------------------
# [2. UTILITIES]
# -------------------------------------------------

def sanitize_package(pkg):

    pkg = pkg.lower()

    pkg = re.sub(r'[^a-z0-9\.]', '', pkg)

    if not pkg.startswith("kr.ac.kangwon.hai"):
        pkg = "kr.ac.kangwon.hai.generated"

    return pkg

def safe_join(base, rel):
    full = os.path.abspath(os.path.join(base, rel))
    if not full.startswith(os.path.abspath(base)):
        raise ValueError("Unsafe path detected")
    return full


def extract_usage_dict(response) -> Dict[str, int]:
    usage = getattr(response, "usage", None)
    if not usage:
        return {}
    return {
        "prompt": getattr(usage, "prompt_tokens", 0) or 0,
        "completion": getattr(usage, "completion_tokens", 0) or 0,
        "total": getattr(usage, "total_tokens", 0) or 0,
    }


def build_tool_raw_output(message, tool_calls) -> str:
    normalized_tool_calls = []
    for tool_call in tool_calls or []:
        function = getattr(tool_call, "function", None)
        normalized_tool_calls.append(
            {
                "id": getattr(tool_call, "id", None),
                "type": getattr(tool_call, "type", None),
                "name": getattr(function, "name", None),
                "arguments": getattr(function, "arguments", None),
            }
        )
    return json.dumps(
        {
            "content": getattr(message, "content", None),
            "tool_calls": normalized_tool_calls,
        },
        ensure_ascii=False,
    )


def get_llm_json(system_prompt, user_prompt, retry_count=2):
    for i in range(retry_count):
        try:
            model_name = MODEL_NAME
            temperature = MODEL_TEMPERATURE
            response = client.chat.completions.create(
                model=model_name,
                response_format={"type": "json_object"},
                messages=[
                    {"role": "system", "content": system_prompt + " \nIMPORTANT: Respond only in valid JSON format."},
                    {"role": "user", "content": user_prompt}
                ],
                temperature=temperature
            )
            
            # 토큰 사용량 추출
            usage = extract_usage_dict(response)
            
            raw_content = response.choices[0].message.content
            clean_json = re.sub(r"```json|```", "", raw_content).strip()
            parsed = json.loads(clean_json)

            return parsed, usage, raw_content, None, model_name, temperature

        except Exception as e:
            if i == retry_count - 1:
                return None, None, locals().get("raw_content"), str(e), locals().get("model_name", MODEL_NAME), locals().get("temperature", MODEL_TEMPERATURE)
    return None, None, None, "llm_json_failed", MODEL_NAME, MODEL_TEMPERATURE


def get_llm_tool_call(system_prompt, user_prompt, tools, retry_count=2):
    for i in range(retry_count):
        try:
            model_name = MODEL_NAME
            temperature = MODEL_TEMPERATURE
            response = client.chat.completions.create(
                model=model_name,
                messages=[
                    {"role": "system", "content": system_prompt},
                    {"role": "user", "content": user_prompt},
                ],
                tools=tools,
                tool_choice="required",
                temperature=temperature,
            )

            usage = extract_usage_dict(response)
            choice = response.choices[0]
            message = choice.message
            tool_calls = getattr(message, "tool_calls", None) or []
            raw_output = build_tool_raw_output(message, tool_calls)
            finish_reason = getattr(choice, "finish_reason", None)

            if not tool_calls:
                return None, None, usage, raw_output, finish_reason, "no_tool_call", model_name, temperature

            tool_call = tool_calls[0]
            function = getattr(tool_call, "function", None)
            tool_name = getattr(function, "name", None)
            arguments_raw = getattr(function, "arguments", None) or "{}"
            arguments = json.loads(arguments_raw)
            if not isinstance(arguments, dict):
                return None, None, usage, raw_output, finish_reason, "tool_arguments_not_object", model_name, temperature

            return tool_name, arguments, usage, raw_output, finish_reason, None, model_name, temperature
        except Exception as e:
            if i == retry_count - 1:
                return None, None, {}, locals().get("raw_output"), locals().get("finish_reason"), str(e), locals().get("model_name", MODEL_NAME), locals().get("temperature", MODEL_TEMPERATURE)
    return None, None, {}, None, None, "llm_tool_call_failed", MODEL_NAME, MODEL_TEMPERATURE


def legacy_agent_response_detailed(system, user, context=None):
    original_user = user
    if context:
        user = f"Context:\n{json.dumps(context, ensure_ascii=False)}\n\nTask:\n{user}"
    parsed, usage, raw_output, parse_error, model_name, temperature = get_llm_json(system, user)
    return {
        "parsed_output": parsed,
        "usage": usage,
        "raw_output": raw_output,
        "error": parse_error,
        "model": model_name,
        "temperature": temperature,
        "instruction": original_user,
    }


def normalized_legacy_agent_response(system, user, context=None, normalizer=None):
    result = legacy_agent_response_detailed(system, user, context)
    if callable(normalizer):
        result["parsed_output"] = normalizer(result.get("parsed_output"))
    return result


def _encode_local_image_as_data_url(image_path: str) -> str:
    mime_type, _ = mimetypes.guess_type(image_path)
    mime_type = mime_type or "image/png"
    with open(image_path, "rb") as f:
        encoded = base64.b64encode(f.read()).decode("ascii")
    return f"data:{mime_type};base64,{encoded}"


def _validate_reference_image_payload(payload):
    if not isinstance(payload, dict):
        return False, "image_analysis_payload_not_object"
    required_string_fields = ["layout_summary", "color_style_summary"]
    required_list_fields = ["ui_components", "text_detected", "interaction_hints"]
    for field in required_string_fields:
        if not isinstance(payload.get(field), str):
            return False, f"{field}_must_be_string"
    for field in required_list_fields:
        if not isinstance(payload.get(field), list):
            return False, f"{field}_must_be_list"
    return True, None


def analyze_reference_image(image_path, analysis_goal, task_id=None):
    if not isinstance(image_path, str) or not image_path.strip():
        result = {
            "layout_summary": "",
            "ui_components": [],
            "text_detected": [],
            "color_style_summary": "",
            "interaction_hints": [],
        }
        record_agent_trace(
            task_id=task_id,
            flow_type="image_analysis",
            agent_name="Reference_Image_Analyzer",
            stage="analyze",
            model=MODEL_NAME,
            temperature=0.0,
            system_prompt=REFERENCE_IMAGE_ANALYZER_SYSTEM,
            instruction=analysis_goal,
            input_payload={"image_path": image_path, "analysis_goal": analysis_goal},
            raw_output=None,
            parsed_output=result,
            tool_name="analyze_reference_image",
            tool_arguments={"image_path": image_path, "analysis_goal": analysis_goal},
            finish_reason="error",
            parse_status="error",
            parse_error="invalid_image_path",
            validation_result="failed",
            fallback_used=False,
            fallback_reason=None,
            usage={},
        )
        return {"status": "failed", "error": "invalid_image_path", **result}

    if not os.path.exists(image_path):
        result = {
            "layout_summary": "",
            "ui_components": [],
            "text_detected": [],
            "color_style_summary": "",
            "interaction_hints": [],
        }
        record_agent_trace(
            task_id=task_id,
            flow_type="image_analysis",
            agent_name="Reference_Image_Analyzer",
            stage="analyze",
            model=MODEL_NAME,
            temperature=0.0,
            system_prompt=REFERENCE_IMAGE_ANALYZER_SYSTEM,
            instruction=analysis_goal,
            input_payload={"image_path": image_path, "analysis_goal": analysis_goal},
            raw_output=None,
            parsed_output=result,
            tool_name="analyze_reference_image",
            tool_arguments={"image_path": image_path, "analysis_goal": analysis_goal},
            finish_reason="error",
            parse_status="error",
            parse_error="image_not_found",
            validation_result="failed",
            fallback_used=False,
            fallback_reason=None,
            usage={},
        )
        return {"status": "failed", "error": "image_not_found", **result}

    model_name = MODEL_NAME
    temperature = 0.0
    raw_output = None
    usage = {}
    finish_reason = None
    try:
        data_url = _encode_local_image_as_data_url(image_path)
        response = client.chat.completions.create(
            model=model_name,
            response_format={"type": "json_object"},
            messages=[
                {"role": "system", "content": REFERENCE_IMAGE_ANALYZER_SYSTEM},
                {
                    "role": "user",
                    "content": [
                        {"type": "text", "text": f"analysis_goal: {analysis_goal}"},
                        {"type": "image_url", "image_url": {"url": data_url}},
                    ],
                },
            ],
            temperature=temperature,
        )
        usage = extract_usage_dict(response)
        finish_reason = getattr(response.choices[0], "finish_reason", None)
        raw_output = response.choices[0].message.content
        clean_json = re.sub(r"```json|```", "", raw_output or "").strip()
        parsed = json.loads(clean_json)
        is_valid, validation_error = _validate_reference_image_payload(parsed)
        if not is_valid:
            record_agent_trace(
                task_id=task_id,
                flow_type="image_analysis",
                agent_name="Reference_Image_Analyzer",
                stage="analyze",
                model=model_name,
                temperature=temperature,
                system_prompt=REFERENCE_IMAGE_ANALYZER_SYSTEM,
                instruction=analysis_goal,
                input_payload={"image_path": image_path, "analysis_goal": analysis_goal},
                raw_output=raw_output,
                parsed_output=parsed,
                tool_name="analyze_reference_image",
                tool_arguments={"image_path": image_path, "analysis_goal": analysis_goal},
                finish_reason=finish_reason,
                parse_status="validation_failed",
                parse_error=validation_error,
                validation_result=f"failed:{validation_error}",
                fallback_used=False,
                fallback_reason=None,
                usage=usage,
            )
            return {"status": "failed", "error": validation_error}
        record_agent_trace(
            task_id=task_id,
            flow_type="image_analysis",
            agent_name="Reference_Image_Analyzer",
            stage="analyze",
            model=model_name,
            temperature=temperature,
            system_prompt=REFERENCE_IMAGE_ANALYZER_SYSTEM,
            instruction=analysis_goal,
            input_payload={"image_path": image_path, "analysis_goal": analysis_goal},
            raw_output=raw_output,
            parsed_output=parsed,
            tool_name="analyze_reference_image",
            tool_arguments={"image_path": image_path, "analysis_goal": analysis_goal},
            finish_reason=finish_reason or "completed",
            parse_status="success",
            parse_error=None,
            validation_result="success",
            fallback_used=False,
            fallback_reason=None,
            usage=usage,
        )
        return {"status": "success", **parsed, "usage": usage}
    except Exception as e:
        record_agent_trace(
            task_id=task_id,
            flow_type="image_analysis",
            agent_name="Reference_Image_Analyzer",
            stage="analyze",
            model=model_name,
            temperature=temperature,
            system_prompt=REFERENCE_IMAGE_ANALYZER_SYSTEM,
            instruction=analysis_goal,
            input_payload={"image_path": image_path, "analysis_goal": analysis_goal},
            raw_output=raw_output,
            parsed_output=None,
            tool_name="analyze_reference_image",
            tool_arguments={"image_path": image_path, "analysis_goal": analysis_goal},
            finish_reason=finish_reason or "error",
            parse_status="error",
            parse_error=str(e),
            validation_result="failed",
            fallback_used=False,
            fallback_reason=None,
            usage=usage,
        )
        return {"status": "failed", "error": str(e)}


def call_agent_with_tools(
    system: str,
    user: str,
    *,
    context: Optional[Dict[str, Any]] = None,
    trace: Optional[Dict[str, Any]] = None,
    tools: Optional[List[Dict[str, Any]]] = None,
    validator: Optional[Callable[[Any], Tuple[bool, Optional[str]]]] = None,
    parsed_output_builder: Optional[Callable[[str, Dict[str, Any]], Any]] = None,
    fallback_parser: Optional[Callable[[str, str, Optional[Dict[str, Any]]], Dict[str, Any]]] = None,
) -> Dict[str, Any]:
    original_user = user
    if context:
        user = f"Context:\n{json.dumps(context, ensure_ascii=False)}\n\nTask:\n{user}"

    trace = trace or {}
    parsed_output_builder = parsed_output_builder or (lambda tool_name, tool_arguments: {"tool": tool_name, "arguments": tool_arguments})
    fallback_parser = fallback_parser or legacy_agent_response_detailed
    allowed_tool_names = {
        function_name
        for tool in (tools or [])
        for function_name in [((tool or {}).get("function") or {}).get("name")]
        if isinstance(function_name, str) and function_name.strip()
    }

    tool_name, tool_arguments, usage, raw_output, finish_reason, tool_error, model_name, temperature = get_llm_tool_call(system, user, tools or [])

    parsed_output = None
    validation_result = "not_run"
    fallback_used = False
    fallback_reason = None
    parse_status = "success"
    parse_error = tool_error
    final_usage = usage
    final_model = model_name
    final_temperature = temperature
    final_raw_output = raw_output

    if not tool_error and tool_name and isinstance(tool_arguments, dict):
        if allowed_tool_names and tool_name not in allowed_tool_names:
            validation_result = f"failed:invalid_tool:{tool_name}"
            parse_status = "validation_failed"
            parse_error = f"invalid_tool:{tool_name}"
        else:
            parsed_output = parsed_output_builder(tool_name, tool_arguments)
            if validator:
                is_valid, validation_error = validator(parsed_output)
                if is_valid:
                    validation_result = "success"
                else:
                    validation_result = f"failed:{validation_error}"
                    parse_status = "validation_failed"
                    parse_error = validation_error
            else:
                validation_result = "success"
    else:
        parse_status = "tool_call_failed"

    if validation_result != "success":
        fallback_used = True
        fallback_reason = parse_error or "tool_call_failed"
        fallback_result = fallback_parser(system, original_user, context)
        fallback_parsed = fallback_result.get("parsed_output")
        fallback_usage = fallback_result.get("usage") or {}
        fallback_error = fallback_result.get("error")
        fallback_raw_output = fallback_result.get("raw_output")
        fallback_model = fallback_result.get("model")
        fallback_temperature = fallback_result.get("temperature")
        if validator and fallback_parsed is not None:
            fallback_valid, fallback_validation_error = validator(fallback_parsed)
        else:
            fallback_valid, fallback_validation_error = (fallback_parsed is not None, None)

        final_usage = {
            "prompt": (usage or {}).get("prompt", 0) + (fallback_usage or {}).get("prompt", 0),
            "completion": (usage or {}).get("completion", 0) + (fallback_usage or {}).get("completion", 0),
            "total": (usage or {}).get("total", 0) + (fallback_usage or {}).get("total", 0),
        }
        final_model = fallback_model or final_model
        final_temperature = fallback_temperature if fallback_temperature is not None else final_temperature
        final_raw_output = json.dumps(
            {
                "tool_call_raw": raw_output,
                "fallback_raw": fallback_raw_output,
            },
            ensure_ascii=False,
        )
        if fallback_valid:
            parsed_output = fallback_parsed
            if isinstance(fallback_parsed, dict) and isinstance(fallback_parsed.get("tool"), str) and isinstance(fallback_parsed.get("arguments"), dict):
                tool_name = fallback_parsed.get("tool")
                tool_arguments = fallback_parsed.get("arguments")
            else:
                tool_name = tool_name or "fallback_json"
                tool_arguments = tool_arguments or {}
            validation_result = "fallback_success"
            parse_status = "fallback_success"
            parse_error = None
        else:
            parsed_output = fallback_parsed
            validation_result = f"fallback_failed:{fallback_validation_error or fallback_error or 'unknown'}"
            parse_status = "fallback_failed"
            parse_error = fallback_validation_error or fallback_error or parse_error

    record_agent_trace(
        task_id=trace.get("task_id"),
        flow_type=trace.get("flow_type"),
        agent_name=trace.get("agent_name") or "Unknown_Agent",
        stage=trace.get("stage"),
        model=final_model,
        temperature=final_temperature,
        system_prompt=system,
        instruction=original_user,
        input_payload=context,
        raw_output=final_raw_output,
        parsed_output=parsed_output,
        tool_name=tool_name,
        tool_arguments=tool_arguments,
        finish_reason=finish_reason,
        parse_status=parse_status,
        parse_error=parse_error,
        validation_result=validation_result,
        fallback_used=fallback_used,
        fallback_reason=fallback_reason,
        usage=final_usage,
    )

    return {
        "tool_name": tool_name,
        "tool_arguments": tool_arguments,
        "parsed_output": parsed_output,
        "raw_response": final_raw_output,
        "finish_reason": finish_reason,
        "usage": final_usage,
        "validation_result": validation_result,
        "fallback_used": fallback_used,
        "fallback_reason": fallback_reason,
        "error": parse_error,
        "model": final_model,
        "temperature": final_temperature,
    }


def get_agent_response(system, user, context=None, trace=None):
    legacy = legacy_agent_response_detailed(system, user, context)
    trace = trace or {}
    record_agent_trace(
        task_id=trace.get("task_id"),
        flow_type=trace.get("flow_type"),
        agent_name=trace.get("agent_name") or "Unknown_Agent",
        stage=trace.get("stage"),
        model=legacy.get("model"),
        temperature=legacy.get("temperature"),
        system_prompt=system,
        instruction=legacy.get("instruction") or user,
        input_payload=context,
        raw_output=legacy.get("raw_output"),
        parsed_output=legacy.get("parsed_output"),
        parse_status="success" if legacy.get("parsed_output") is not None else "parse_failed",
        parse_error=legacy.get("error"),
        validation_result="legacy_json",
        fallback_used=False,
        fallback_reason=None,
        usage=legacy.get("usage"),
    )
    return legacy.get("parsed_output"), legacy.get("usage")


def normalize_generate_tool_call_payload(payload):
    if not isinstance(payload, dict):
        return payload
    normalized = dict(payload)
    tool_name = normalized.get("tool")
    arguments = normalized.get("arguments")
    if tool_name == "ask_question":
        normalized["tool"] = "ask_clarification"
        tool_name = "ask_clarification"
    if not isinstance(arguments, dict):
        return normalized
    arguments = dict(arguments)

    if tool_name == "ask_clarification" and "questions" in arguments and "missing_fields" not in arguments:
        arguments["missing_fields"] = []

    if tool_name == "build_app":
        summary = (arguments.get("summary") or normalized.get("summary") or "").strip()
        build_spec = arguments.get("build_spec")
        if (not isinstance(build_spec, dict) or not build_spec) and summary:
            arguments["build_spec"] = {
                "app_goal": summary,
                "target_platform": "Android smartphone",
                "target_users": "단일 사용자",
                "core_features": [],
                "screens": [],
                "data_model": "local_or_request_defined",
                "auth": "not_required_unless_requested",
                "online_mode": "offline_unless_requested",
                "constraints": [
                    "사용자 원문 요청과 summary를 기준으로 downstream planner가 상세 요구사항을 보강합니다.",
                    "안드로이드 스마트폰용 Flutter 앱으로 생성합니다.",
                ],
            }

    normalized["arguments"] = arguments
    return normalized


def _normalize_string_list(values, max_items=5):
    normalized = []
    seen = set()
    for item in values or []:
        if not isinstance(item, str):
            continue
        value = item.strip()
        if not value or value in seen:
            continue
        seen.add(value)
        normalized.append(value)
        if len(normalized) >= max_items:
            break
    return normalized


def normalize_runtime_build_spec(build_spec):
    if not isinstance(build_spec, dict):
        return {}

    normalized = dict(build_spec)
    external_sources = [item for item in normalized.get("external_sources", []) if isinstance(item, dict)]
    required_permissions = [item for item in normalized.get("required_permissions", []) if isinstance(item, dict)]
    existing_candidates = normalized.get("source_url_candidates")
    web_data_contract = normalized.get("web_data_contract") if isinstance(normalized.get("web_data_contract"), dict) else {}
    source_url_candidates = []
    if isinstance(existing_candidates, list):
        source_url_candidates.extend(existing_candidates)
    for source in external_sources:
        url = source.get("url")
        if isinstance(url, str):
            source_url_candidates.append(url)
    for key in ("primary_url",):
        value = web_data_contract.get(key)
        if isinstance(value, str):
            source_url_candidates.append(value)
    candidate_contract_urls = web_data_contract.get("candidate_urls")
    if isinstance(candidate_contract_urls, list):
        source_url_candidates.extend(candidate_contract_urls)
    source_url_candidates = _normalize_string_list(source_url_candidates, max_items=8)

    data_source_type = (normalized.get("data_source_type") or "").strip().lower()
    data_strategy = (normalized.get("data_strategy") or "").strip().lower()
    source_access_mode = (normalized.get("source_access_mode") or "").strip().lower()

    if data_source_type not in {"none", "web_scrape", "api", "manual"}:
        if data_strategy == "runtime_api_fetch":
            data_source_type = "api"
        elif data_strategy == "runtime_web_fetch":
            data_source_type = "web_scrape"
        elif source_url_candidates:
            data_source_type = "web_scrape"
        else:
            data_source_type = "none"

    if source_access_mode not in {"static", "scraping", "api", "manual"}:
        source_access_mode = {
            "api": "api",
            "web_scrape": "scraping",
            "manual": "manual",
            "none": "static",
        }.get(data_source_type, "static")

    existing_requirements = normalized.get("verification_requirements")
    verification_requirements = dict(existing_requirements) if isinstance(existing_requirements, dict) else {}
    existing_permission_requirements = normalized.get("permission_requirements")
    permission_requirements = dict(existing_permission_requirements) if isinstance(existing_permission_requirements, dict) else {}
    requires_external_data_verification = bool(
        verification_requirements.get("requires_external_data_verification")
    ) or data_source_type in {"web_scrape", "api", "manual"} or bool(source_url_candidates)

    verification_requirements["requires_external_data_verification"] = requires_external_data_verification
    verification_requirements["source_probe_required"] = bool(
        verification_requirements.get("source_probe_required")
    ) or data_source_type in {"web_scrape", "api"}
    verification_requirements["migration_notice_check"] = bool(
        verification_requirements.get("migration_notice_check")
    ) or data_source_type == "web_scrape"
    verification_requirements["parser_smoke_test_required"] = bool(
        verification_requirements.get("parser_smoke_test_required")
    ) or data_source_type == "web_scrape"
    verification_requirements["minimum_sample_days"] = max(
        1,
        int(verification_requirements.get("minimum_sample_days") or 1),
    )
    verification_requirements["cache_persistence_required"] = bool(
        verification_requirements.get("cache_persistence_required")
    ) or requires_external_data_verification

    normalized_permissions = []
    seen_permissions = set()
    for item in required_permissions:
        name = item.get("name")
        if not isinstance(name, str) or not name.strip():
            continue
        normalized_name = name.strip()
        if normalized_name in seen_permissions:
            continue
        seen_permissions.add(normalized_name)
        usage_reason = item.get("usage_reason")
        normalized_permissions.append(
            {
                "name": normalized_name,
                "usage_reason": usage_reason.strip() if isinstance(usage_reason, str) and usage_reason.strip() else "권한이 필요한 기능 사용",
                "runtime_request_required": bool(item.get("runtime_request_required", True)),
            }
        )

    permission_requirements["runtime_prompt_required"] = bool(
        permission_requirements.get("runtime_prompt_required")
    ) or any(item.get("runtime_request_required") for item in normalized_permissions)
    permission_requirements["denial_fallback_required"] = bool(
        permission_requirements.get("denial_fallback_required")
    ) or bool(normalized_permissions)

    normalized["external_sources"] = external_sources
    normalized["source_url_candidates"] = source_url_candidates
    normalized["data_source_type"] = data_source_type
    normalized["source_access_mode"] = source_access_mode
    normalized["required_permissions"] = normalized_permissions
    normalized["permission_requirements"] = permission_requirements
    normalized["verification_requirements"] = verification_requirements
    return normalized


def extract_build_request_context(user_request):
    raw_text = user_request if isinstance(user_request, str) else ""
    parsed_payload = {}
    if isinstance(user_request, str):
        try:
            candidate = json.loads(user_request)
            if isinstance(candidate, dict):
                parsed_payload = candidate
        except json.JSONDecodeError:
            parsed_payload = {}

    prompt_text = (parsed_payload.get("user_request") if isinstance(parsed_payload.get("user_request"), str) else raw_text).strip()
    summary = (parsed_payload.get("summary") if isinstance(parsed_payload.get("summary"), str) else "").strip()
    build_spec = normalize_runtime_build_spec(parsed_payload.get("build_spec"))
    return {
        "raw_request": raw_text,
        "user_request": prompt_text or raw_text,
        "summary": summary,
        "build_spec": build_spec,
        "research_query": parsed_payload.get("research_query") if isinstance(parsed_payload.get("research_query"), str) else "",
        "research_reason": parsed_payload.get("research_reason") if isinstance(parsed_payload.get("research_reason"), str) else "",
        "research_results": parsed_payload.get("research_results") if isinstance(parsed_payload.get("research_results"), list) else [],
    }


def validate_generate_tool_call(payload):
    payload = normalize_generate_tool_call_payload(payload)
    if not isinstance(payload, dict):
        return False, f"Tool call payload must be an object | raw_payload={repr(payload)}"

    tool_name = payload.get("tool")
    arguments = payload.get("arguments")

    if tool_name not in GENERATE_TOOLS:
        return False, f"Unknown tool: {tool_name} | raw_payload={json.dumps(payload, ensure_ascii=False)}"

    if not isinstance(arguments, dict):
        return False, f"Tool arguments must be an object | tool={tool_name} | raw_payload={json.dumps(payload, ensure_ascii=False)}"

    for key in GENERATE_TOOLS[tool_name]["required_arguments"]:
        if key not in arguments:
            return False, f"Missing required argument: {key} | tool={tool_name} | raw_payload={json.dumps(payload, ensure_ascii=False)}"

    if tool_name == "ask_clarification":
        questions = arguments.get("questions")
        missing_fields = arguments.get("missing_fields")
        summary = arguments.get("summary")
        if not isinstance(questions, list) or not questions or len(questions) > 3:
            return False, f"ask_clarification.questions must be a list with 1 to 3 items | tool={tool_name} | raw_payload={json.dumps(payload, ensure_ascii=False)}"
        if not all(isinstance(item, str) and item.strip() for item in questions):
            return False, f"ask_clarification.questions must contain non-empty strings | tool={tool_name} | raw_payload={json.dumps(payload, ensure_ascii=False)}"
        if not isinstance(missing_fields, list):
            return False, f"ask_clarification.missing_fields must be a list | tool={tool_name} | raw_payload={json.dumps(payload, ensure_ascii=False)}"
        if summary is not None and (not isinstance(summary, str) or not summary.strip()):
            return False, f"ask_clarification.summary must be a non-empty string when present | tool={tool_name} | raw_payload={json.dumps(payload, ensure_ascii=False)}"

    if tool_name == "reject_request":
        for key in ["reason", "policy_category", "message"]:
            if not isinstance(arguments.get(key), str) or not arguments[key].strip():
                return False, f"reject_request.{key} must be a non-empty string | tool={tool_name} | raw_payload={json.dumps(payload, ensure_ascii=False)}"

    if tool_name == "build_app":
        if not isinstance(arguments.get("summary"), str) or not arguments["summary"].strip():
            return False, f"build_app.summary must be a non-empty string | tool={tool_name} | raw_payload={json.dumps(payload, ensure_ascii=False)}"
        if not isinstance(arguments.get("build_spec"), dict) or not arguments["build_spec"]:
            return False, f"build_app.build_spec must be a non-empty object | tool={tool_name} | raw_payload={json.dumps(payload, ensure_ascii=False)}"

    if tool_name == "research_then_build":
        if not isinstance(arguments.get("summary"), str) or not arguments["summary"].strip():
            return False, f"research_then_build.summary must be a non-empty string | tool={tool_name} | raw_payload={json.dumps(payload, ensure_ascii=False)}"
        if not isinstance(arguments.get("research_query"), str) or not arguments["research_query"].strip():
            return False, f"research_then_build.research_query must be a non-empty string | tool={tool_name} | raw_payload={json.dumps(payload, ensure_ascii=False)}"
        if not isinstance(arguments.get("research_reason"), str) or not arguments["research_reason"].strip():
            return False, f"research_then_build.research_reason must be a non-empty string | tool={tool_name} | raw_payload={json.dumps(payload, ensure_ascii=False)}"

    if tool_name == "answer_question":
        if not isinstance(arguments.get("assistant_message"), str) or not arguments["assistant_message"].strip():
            return False, f"answer_question.assistant_message must be a non-empty string | tool={tool_name} | raw_payload={json.dumps(payload, ensure_ascii=False)}"
        if not isinstance(arguments.get("reason"), str) or not arguments["reason"].strip():
            return False, f"answer_question.reason must be a non-empty string | tool={tool_name} | raw_payload={json.dumps(payload, ensure_ascii=False)}"

    if tool_name == "ask_confirmation":
        if not isinstance(arguments.get("question"), str) or not arguments["question"].strip():
            return False, f"ask_confirmation.question must be a non-empty string | tool={tool_name} | raw_payload={json.dumps(payload, ensure_ascii=False)}"
        if not isinstance(arguments.get("assumed_action"), str) or not arguments["assumed_action"].strip():
            return False, f"ask_confirmation.assumed_action must be a non-empty string | tool={tool_name} | raw_payload={json.dumps(payload, ensure_ascii=False)}"
        if not isinstance(arguments.get("reason"), str) or not arguments["reason"].strip():
            return False, f"ask_confirmation.reason must be a non-empty string | tool={tool_name} | raw_payload={json.dumps(payload, ensure_ascii=False)}"
        if "summary" in arguments and (not isinstance(arguments.get("summary"), str) or not arguments["summary"].strip()):
            return False, f"ask_confirmation.summary must be a non-empty string when present | tool={tool_name} | raw_payload={json.dumps(payload, ensure_ascii=False)}"

    return True, None


def validate_plan_payload(payload):
    if not isinstance(payload, dict):
        return False, f"Plan payload must be an object | raw_payload={repr(payload)}"
    if not isinstance(payload.get("title"), str) or not payload.get("title", "").strip():
        return False, f"title must be a non-empty string | raw_payload={json.dumps(payload, ensure_ascii=False)}"
    if not isinstance(payload.get("package_name"), str) or not payload.get("package_name", "").strip():
        return False, f"package_name must be a non-empty string | raw_payload={json.dumps(payload, ensure_ascii=False)}"
    if not isinstance(payload.get("blueprint"), str) or not payload.get("blueprint", "").strip():
        return False, f"blueprint must be a non-empty string | raw_payload={json.dumps(payload, ensure_ascii=False)}"
    files_to_create = payload.get("files_to_create")
    if not isinstance(files_to_create, list) or not files_to_create or not all(isinstance(item, str) and item.strip() for item in files_to_create):
        return False, f"files_to_create must be a non-empty string array | raw_payload={json.dumps(payload, ensure_ascii=False)}"
    return True, None


def validate_review_payload(payload):
    if not isinstance(payload, dict):
        return False, f"Review payload must be an object | raw_payload={repr(payload)}"
    status = payload.get("status")
    if status not in {"pass", "fail"}:
        return False, f"status must be pass or fail | raw_payload={json.dumps(payload, ensure_ascii=False)}"
    feedback = payload.get("feedback")
    if not isinstance(feedback, list):
        return False, f"feedback must be a list | raw_payload={json.dumps(payload, ensure_ascii=False)}"
    if not isinstance(payload.get("critical_fixes"), str):
        return False, f"critical_fixes must be a string | raw_payload={json.dumps(payload, ensure_ascii=False)}"
    return True, None


def validate_external_data_verification_payload(payload):
    if not isinstance(payload, dict):
        return False, f"External data verification payload must be an object | raw_payload={repr(payload)}"
    if payload.get("status") not in {"pass", "fail", "not_applicable"}:
        return False, f"status must be pass, fail, or not_applicable | raw_payload={json.dumps(payload, ensure_ascii=False)}"
    if not isinstance(payload.get("summary"), str) or not payload.get("summary", "").strip():
        return False, f"summary must be a non-empty string | raw_payload={json.dumps(payload, ensure_ascii=False)}"
    if not isinstance(payload.get("issues"), list):
        return False, f"issues must be a list | raw_payload={json.dumps(payload, ensure_ascii=False)}"
    checks = payload.get("checks")
    required_keys = ["source_probe", "migration_notice", "parser_smoke", "minimum_sample_data", "cache_persistence"]
    if not isinstance(checks, dict):
        return False, f"checks must be an object | raw_payload={json.dumps(payload, ensure_ascii=False)}"
    for key in required_keys:
        if checks.get(key) not in {"pass", "fail", "warn", "not_applicable"}:
            return False, f"checks.{key} must be pass/fail/warn/not_applicable | raw_payload={json.dumps(payload, ensure_ascii=False)}"
    return True, None


def validate_refinement_plan_payload(payload):
    if not isinstance(payload, dict):
        return False, f"Refinement plan payload must be an object | raw_payload={repr(payload)}"
    if not isinstance(payload.get("analysis"), str) or not payload.get("analysis", "").strip():
        return False, f"analysis must be a non-empty string | raw_payload={json.dumps(payload, ensure_ascii=False)}"
    if not isinstance(payload.get("refinement_plan"), str) or not payload.get("refinement_plan", "").strip():
        return False, f"refinement_plan must be a non-empty string | raw_payload={json.dumps(payload, ensure_ascii=False)}"
    if not isinstance(payload.get("files_to_modify"), list) or not payload.get("files_to_modify"):
        return False, f"files_to_modify must be a non-empty list | raw_payload={json.dumps(payload, ensure_ascii=False)}"
    return True, None


def validate_file_change_payload(payload):
    if not isinstance(payload, dict):
        return False, f"File change payload must be an object | raw_payload={repr(payload)}"
    files = payload.get("files")
    if not isinstance(files, list) or not files:
        return False, f"files must be a non-empty list | raw_payload={json.dumps(payload, ensure_ascii=False)}"
    for item in files:
        if not isinstance(item, dict):
            return False, f"files items must be objects | raw_payload={json.dumps(payload, ensure_ascii=False)}"
        if not isinstance(item.get("path"), str) or not item.get("path", "").strip():
            return False, f"files.path must be a non-empty string | raw_payload={json.dumps(payload, ensure_ascii=False)}"
        if not isinstance(item.get("content"), str):
            return False, f"files.content must be a string | raw_payload={json.dumps(payload, ensure_ascii=False)}"
        if "change_type" in item and (not isinstance(item.get("change_type"), str) or not item.get("change_type", "").strip()):
            return False, f"files.change_type must be a non-empty string when present | raw_payload={json.dumps(payload, ensure_ascii=False)}"
        if "reason" in item and (not isinstance(item.get("reason"), str) or not item.get("reason", "").strip()):
            return False, f"files.reason must be a non-empty string when present | raw_payload={json.dumps(payload, ensure_ascii=False)}"
    return True, None


def normalize_file_change_payload(payload):
    files = []
    for item in payload.get("files", []) if isinstance(payload, dict) else []:
        if not isinstance(item, dict):
            continue
        path = item.get("path")
        content = item.get("content")
        if not isinstance(path, str) or not path.strip():
            continue
        if not isinstance(content, str):
            continue
        files.append({"path": path, "content": content})
    normalized = {"files": files}
    if isinstance(payload, dict) and isinstance(payload.get("root_cause"), str):
        normalized["root_cause"] = payload.get("root_cause")
    return normalized


def validate_research_build_payload(payload):
    if not isinstance(payload, dict):
        return False, f"Research build payload must be an object | raw_payload={repr(payload)}"
    summary = payload.get("summary")
    build_spec = payload.get("build_spec")
    if not isinstance(summary, str) or not summary.strip():
        return False, f"summary must be a non-empty string | raw_payload={json.dumps(payload, ensure_ascii=False)}"
    if not isinstance(build_spec, dict) or not build_spec:
        return False, f"build_spec must be a non-empty object | raw_payload={json.dumps(payload, ensure_ascii=False)}"
    return True, None


def validate_product_plan_payload(payload):
    if not isinstance(payload, dict):
        return False, f"Product plan payload must be an object | raw_payload={repr(payload)}"
    for key in ["summary", "app_goal", "target_users", "data_model"]:
        if not isinstance(payload.get(key), str) or not payload.get(key, "").strip():
            return False, f"{key} must be a non-empty string | raw_payload={json.dumps(payload, ensure_ascii=False)}"
    for key in ["user_flows", "screens", "constraints"]:
        if not isinstance(payload.get(key), list) or not payload.get(key):
            return False, f"{key} must be a non-empty list | raw_payload={json.dumps(payload, ensure_ascii=False)}"
    return True, None


def _non_empty_dict(value):
    return isinstance(value, dict) and bool(value)


def _as_list(value):
    if value is None:
        return []
    if isinstance(value, list):
        return value
    return [value]


def normalize_ui_layout_plan_payload(payload):
    if not isinstance(payload, dict):
        return payload
    normalized = dict(payload)
    app_overview = normalized.get("app_overview") if isinstance(normalized.get("app_overview"), dict) else {}
    if not app_overview:
        app_overview = normalized.get("app_metadata") if isinstance(normalized.get("app_metadata"), dict) else {}
    global_style = normalized.get("global_style") if isinstance(normalized.get("global_style"), dict) else {}

    if not _non_empty_dict(normalized.get("visual_identity")):
        visual_identity = {}
        for key in ["app_name", "design_goal", "platform", "design_system"]:
            if app_overview.get(key):
                visual_identity[key] = app_overview.get(key)
        if global_style.get("color_tokens"):
            visual_identity["color_palette"] = global_style.get("color_tokens")
        if global_style.get("typography"):
            visual_identity["typography"] = global_style.get("typography")
        if global_style.get("spacing"):
            visual_identity["spacing_density"] = global_style.get("spacing")
        if not visual_identity and normalized.get("preservation_targets"):
            visual_identity["style_summary"] = " / ".join(
                str(item) for item in _as_list(normalized.get("preservation_targets"))[:4]
            )
            visual_identity["design_system"] = "Material 3"
            visual_identity["platform"] = "Android smartphone"
        normalized["visual_identity"] = visual_identity

    if not _non_empty_dict(normalized.get("navigation")):
        navigation_pattern = app_overview.get("navigation_pattern") or "단일 화면"
        normalized["navigation"] = {
            "type": navigation_pattern,
            "routes": [{"route": "/", "screen_id": "home"}],
        }

    if not normalized.get("screen_layouts") and isinstance(normalized.get("screens"), list):
        normalized["screen_layouts"] = normalized.get("screens")
    if not normalized.get("screen_layouts") and normalized.get("preservation_targets"):
        normalized["screen_layouts"] = [
            {
                "screen_id": "main",
                "screen_name": "메인 화면",
                "layout_summary": "사용자 요청과 제품 계획을 바탕으로 구성되는 단일 안드로이드 스마트폰 화면입니다.",
                "must_preserve": _as_list(normalized.get("preservation_targets")),
            }
        ]

    if not _non_empty_dict(normalized.get("style_tokens")):
        normalized["style_tokens"] = global_style or {
            key: normalized.get(key)
            for key in ["color_tokens", "typography", "spacing", "shape", "motion"]
            if normalized.get(key)
        }
        if not normalized["style_tokens"]:
            normalized["style_tokens"] = {
                "design_system": "Material 3",
                "layout": "mobile_safe_single_column",
                "scroll": "SingleChildScrollView compatible",
            }

    if "preservation_targets" not in normalized:
        normalized["preservation_targets"] = _as_list(
            normalized.get("layout_preservation_targets") or normalized.get("preservation_rules")
        )
    elif isinstance(normalized.get("preservation_targets"), dict):
        targets = normalized.get("preservation_targets") or {}
        flattened_targets = []
        for key in ["must_keep", "must_avoid", "rules", "targets"]:
            flattened_targets.extend(_as_list(targets.get(key)))
        normalized["preservation_targets"] = [str(item) for item in flattened_targets if item]

    return normalized


def validate_ui_layout_plan_payload(payload):
    payload = normalize_ui_layout_plan_payload(payload)
    if not isinstance(payload, dict):
        return False, f"UI layout plan payload must be an object | raw_payload={repr(payload)}"
    for key in ["visual_identity", "navigation", "style_tokens"]:
        if not isinstance(payload.get(key), dict) or not payload.get(key):
            return False, f"{key} must be a non-empty object | raw_payload={json.dumps(payload, ensure_ascii=False)}"
    if not isinstance(payload.get("screen_layouts"), list) or not payload.get("screen_layouts"):
        return False, f"screen_layouts must be a non-empty list | raw_payload={json.dumps(payload, ensure_ascii=False)}"
    if not isinstance(payload.get("preservation_targets"), list):
        return False, f"preservation_targets must be a list | raw_payload={json.dumps(payload, ensure_ascii=False)}"
    return True, None


def normalize_data_model_plan_payload(payload):
    if not isinstance(payload, dict):
        return payload
    if isinstance(payload.get("data_model_plan"), dict):
        normalized = dict(payload.get("data_model_plan"))
    else:
        normalized = dict(payload)

    if not isinstance(normalized.get("entities"), list):
        entity = normalized.get("entity") if isinstance(normalized.get("entity"), dict) else {}
        normalized["entities"] = [entity] if entity else []

    cleaned_entities = []
    for entity in normalized.get("entities") or []:
        if not isinstance(entity, dict):
            continue
        name = str(entity.get("name") or entity.get("entity_name") or "AppRecord").strip()
        fields = entity.get("fields")
        if not isinstance(fields, list):
            fields = []
        cleaned_fields = []
        for field in fields:
            if not isinstance(field, dict):
                continue
            field_name = str(field.get("name") or "").strip()
            if not field_name:
                continue
            cleaned_fields.append({
                "name": field_name,
                "type": str(field.get("type") or "String").strip() or "String",
                "required": bool(field.get("required", False)),
                "source_path": str(field.get("source_path") or field.get("json_path") or field.get("selector") or "").strip(),
                "normalization": str(field.get("normalization") or "").strip(),
            })
        cleaned_entities.append({
            "name": name,
            "fields": cleaned_fields,
            "description": str(entity.get("description") or "").strip(),
        })
    normalized["entities"] = cleaned_entities

    source_mapping = normalized.get("source_mapping")
    if not isinstance(source_mapping, dict):
        source_mapping = {}
    candidate_urls = source_mapping.get("candidate_urls")
    if not isinstance(candidate_urls, list):
        candidate_urls = _as_list(candidate_urls)
    source_mapping["source_kind"] = str(source_mapping.get("source_kind") or "local").strip() or "local"
    source_mapping["primary_url"] = str(source_mapping.get("primary_url") or "").strip()
    source_mapping["candidate_urls"] = [str(item).strip() for item in candidate_urls if str(item).strip()][:8]
    source_mapping["parser_strategy"] = str(source_mapping.get("parser_strategy") or "local").strip() or "local"
    source_mapping["response_root"] = str(source_mapping.get("response_root") or "").strip()
    source_mapping["record_selector"] = str(source_mapping.get("record_selector") or "").strip()
    normalized["source_mapping"] = source_mapping

    for key in ["normalization_rules", "validation_rules", "empty_state_rules"]:
        normalized[key] = [str(item).strip() for item in _as_list(normalized.get(key)) if str(item).strip()]

    cache_model = normalized.get("cache_model")
    if not isinstance(cache_model, dict):
        cache_model = {}
    key_fields = cache_model.get("key_fields")
    if not isinstance(key_fields, list):
        key_fields = _as_list(key_fields)
    try:
        ttl_minutes = int(cache_model.get("ttl_minutes") or 0)
    except (TypeError, ValueError):
        ttl_minutes = 0
    normalized["cache_model"] = {
        "enabled": bool(cache_model.get("enabled", False)),
        "key_fields": [str(item).strip() for item in key_fields if str(item).strip()],
        "ttl_minutes": max(0, ttl_minutes),
        "stale_data_behavior": str(cache_model.get("stale_data_behavior") or "").strip(),
    }
    return normalized


def validate_data_model_plan_payload(payload):
    payload = normalize_data_model_plan_payload(payload)
    if not isinstance(payload, dict):
        return False, f"Data model plan payload must be an object | raw_payload={repr(payload)}"
    if not isinstance(payload.get("entities"), list) or not payload.get("entities"):
        return False, f"entities must be a non-empty list | raw_payload={json.dumps(payload, ensure_ascii=False)}"
    for entity in payload.get("entities"):
        if not isinstance(entity, dict) or not isinstance(entity.get("name"), str) or not entity.get("name", "").strip():
            return False, f"entities items must have a non-empty name | raw_payload={json.dumps(payload, ensure_ascii=False)}"
        fields = entity.get("fields")
        if not isinstance(fields, list) or not fields:
            return False, f"each entity must have non-empty fields | raw_payload={json.dumps(payload, ensure_ascii=False)}"
        for field in fields:
            if not isinstance(field, dict) or not isinstance(field.get("name"), str) or not field.get("name", "").strip():
                return False, f"entity fields must have non-empty names | raw_payload={json.dumps(payload, ensure_ascii=False)}"
            if not isinstance(field.get("type"), str) or not field.get("type", "").strip():
                return False, f"entity fields must have non-empty type | raw_payload={json.dumps(payload, ensure_ascii=False)}"
            if "required" not in field:
                return False, f"entity fields must include required flag | raw_payload={json.dumps(payload, ensure_ascii=False)}"
            if "source_path" not in field or "normalization" not in field:
                return False, f"entity fields must include source_path and normalization | raw_payload={json.dumps(payload, ensure_ascii=False)}"
    if not isinstance(payload.get("source_mapping"), dict) or not payload.get("source_mapping"):
        return False, f"source_mapping must be a non-empty object | raw_payload={json.dumps(payload, ensure_ascii=False)}"
    for key in ["normalization_rules", "validation_rules", "empty_state_rules"]:
        if not isinstance(payload.get(key), list):
            return False, f"{key} must be a list | raw_payload={json.dumps(payload, ensure_ascii=False)}"
    if not isinstance(payload.get("cache_model"), dict):
        return False, f"cache_model must be an object | raw_payload={json.dumps(payload, ensure_ascii=False)}"
    return True, None


def normalize_feature_logic_plan_payload(payload):
    if not isinstance(payload, dict):
        return payload
    if isinstance(payload.get("feature_logic_plan"), dict):
        normalized = dict(payload.get("feature_logic_plan"))
    else:
        normalized = dict(payload)

    state_model = normalized.get("state_model")
    if not isinstance(state_model, dict):
        state_model = {}
        for key in ["state_management", "data_model", "time_logic", "lifecycle_and_resilience"]:
            if normalized.get(key):
                state_model[key] = normalized.get(key)
        if not state_model and normalized.get("business_rules"):
            state_model["rules_summary"] = normalized.get("business_rules")
        normalized["state_model"] = state_model

    if not normalized.get("screen_behaviors"):
        if isinstance(normalized.get("screens"), list):
            normalized["screen_behaviors"] = normalized.get("screens")
        elif isinstance(normalized.get("actions"), list):
            normalized["screen_behaviors"] = [{"screen_id": "home", "actions": normalized.get("actions")}]
        elif normalized.get("business_rules"):
            normalized["screen_behaviors"] = [
                {
                    "screen_id": "main",
                    "behavior_summary": _as_list(normalized.get("business_rules")),
                }
            ]
        else:
            normalized["screen_behaviors"] = []

    if not isinstance(normalized.get("business_rules"), list):
        rules = normalized.get("business_rules")
        if isinstance(rules, dict):
            normalized["business_rules"] = [json.dumps(rules, ensure_ascii=False)]
        else:
            normalized["business_rules"] = _as_list(rules)

    if not isinstance(normalized.get("data_operations"), list):
        operations = normalized.get("data_operations")
        if operations is None and isinstance(normalized.get("data_model"), dict):
            operations = normalized.get("data_model").get("fields") or normalized.get("data_model")
        normalized["data_operations"] = _as_list(operations)

    if not isinstance(normalized.get("error_handling"), list):
        error_handling = normalized.get("error_handling")
        if isinstance(error_handling, dict):
            cases = error_handling.get("cases")
            normalized["error_handling"] = cases if isinstance(cases, list) else [json.dumps(error_handling, ensure_ascii=False)]
        else:
            normalized["error_handling"] = _as_list(error_handling)

    return normalized


def validate_feature_logic_plan_payload(payload):
    payload = normalize_feature_logic_plan_payload(payload)
    if not isinstance(payload, dict):
        return False, f"Feature logic plan payload must be an object | raw_payload={repr(payload)}"
    if not isinstance(payload.get("state_model"), dict):
        return False, f"state_model must be an object | raw_payload={json.dumps(payload, ensure_ascii=False)}"
    for key in ["screen_behaviors", "business_rules", "data_operations", "error_handling"]:
        if not isinstance(payload.get(key), list):
            return False, f"{key} must be a list | raw_payload={json.dumps(payload, ensure_ascii=False)}"
    if not payload.get("screen_behaviors"):
        return False, f"screen_behaviors must be non-empty | raw_payload={json.dumps(payload, ensure_ascii=False)}"
    return True, None


def normalize_ui_contract_payload(payload):
    if not isinstance(payload, dict):
        return payload
    normalized = dict(payload)
    screens = normalized.get("screens") if isinstance(normalized.get("screens"), list) else []
    if not screens and (
        normalized.get("global_components") or normalized.get("interaction_patterns") or normalized.get("preservation_rules")
    ):
        screens = [
            {
                "screen_id": "main",
                "screen_name": "메인 화면",
                "purpose": "현재 코드에서 확인된 주요 UI를 제공하는 화면입니다.",
                "layout_summary": "전역 컴포넌트와 보존 규칙을 기준으로 추출한 기본 화면 구조입니다.",
                "main_components": _as_list(normalized.get("global_components")),
                "primary_actions": _as_list(normalized.get("interaction_patterns")),
                "must_preserve": _as_list(normalized.get("preservation_rules")),
            }
        ]
        normalized["screens"] = screens

    if not _non_empty_dict(normalized.get("visual_identity")):
        normalized["visual_identity"] = {
            "style_summary": "현재 코드에서 추출한 UI 구성 요소와 보존 규칙을 기준으로 한 시각 기준선입니다.",
            "color_palette": [],
            "typography": "",
            "spacing_density": "",
            "overall_tone": "",
        }
        components = normalized.get("global_components")
        if isinstance(components, list) and components:
            normalized["visual_identity"]["style_summary"] = ", ".join(str(item) for item in components[:5])

    if not _non_empty_dict(normalized.get("navigation")):
        screen_names = []
        for screen in screens:
            if isinstance(screen, dict):
                screen_names.append(screen.get("screen_id") or screen.get("screen_name") or "screen")
        normalized["navigation"] = {
            "type": "single_screen" if len(screen_names) <= 1 else "stack",
            "screens": screen_names or ["unknown"],
        }

    for key in ["global_components", "interaction_patterns", "preservation_rules"]:
        if not isinstance(normalized.get(key), list):
            normalized[key] = _as_list(normalized.get(key))

    return normalized


def validate_ui_contract_payload(payload):
    payload = normalize_ui_contract_payload(payload)
    if not isinstance(payload, dict):
        return False, f"UI contract payload must be an object | raw_payload={repr(payload)}"
    visual_identity = payload.get("visual_identity")
    navigation = payload.get("navigation")
    screens = payload.get("screens")
    if not isinstance(visual_identity, dict) or not visual_identity:
        return False, f"visual_identity must be a non-empty object | raw_payload={json.dumps(payload, ensure_ascii=False)}"
    if not isinstance(navigation, dict) or not navigation:
        return False, f"navigation must be a non-empty object | raw_payload={json.dumps(payload, ensure_ascii=False)}"
    if not isinstance(screens, list) or not screens:
        return False, f"screens must be a non-empty list | raw_payload={json.dumps(payload, ensure_ascii=False)}"
    for key in ["global_components", "interaction_patterns", "preservation_rules"]:
        if not isinstance(payload.get(key), list):
            return False, f"{key} must be a list | raw_payload={json.dumps(payload, ensure_ascii=False)}"
    return True, None


def validate_feedback_route_call(payload):
    if not isinstance(payload, dict):
        return False, f"Feedback route payload must be an object | raw_payload={repr(payload)}"

    action = payload.get("action")
    assistant_message = payload.get("assistant_message")
    reason = payload.get("reason")

    if action not in FEEDBACK_ROUTE_ACTIONS:
        return False, f"Unknown feedback route action: {action} | raw_payload={json.dumps(payload, ensure_ascii=False)}"
    if not isinstance(assistant_message, str) or not assistant_message.strip():
        return False, f"assistant_message must be a non-empty string | raw_payload={json.dumps(payload, ensure_ascii=False)}"
    if reason is not None and (not isinstance(reason, str) or not reason.strip()):
        return False, f"reason must be a non-empty string when present | raw_payload={json.dumps(payload, ensure_ascii=False)}"

    return True, None


def validate_runtime_error_summary(payload):
    if not isinstance(payload, dict):
        return False, f"Runtime error summary payload must be an object | raw_payload={repr(payload)}"
    summary = payload.get("summary")
    assistant_message = payload.get("assistant_message")
    if not isinstance(summary, str) or not summary.strip():
        return False, f"summary must be a non-empty string | raw_payload={json.dumps(payload, ensure_ascii=False)}"
    if not isinstance(assistant_message, str) or not assistant_message.strip():
        return False, f"assistant_message must be a non-empty string | raw_payload={json.dumps(payload, ensure_ascii=False)}"
    return True, None


def validate_build_failure_summary(payload):
    if not isinstance(payload, dict):
        return False, f"Build failure summary payload must be an object | raw_payload={repr(payload)}"
    summary = payload.get("summary")
    assistant_message = payload.get("assistant_message")
    if not isinstance(summary, str) or not summary.strip():
        return False, f"summary must be a non-empty string | raw_payload={json.dumps(payload, ensure_ascii=False)}"
    if not isinstance(assistant_message, str) or not assistant_message.strip():
        return False, f"assistant_message must be a non-empty string | raw_payload={json.dumps(payload, ensure_ascii=False)}"
    return True, None


def decide_generate_action(
    user_request,
    device_context=None,
    task_id=None,
    reference_image_analysis=None,
    image_reference_summary="",
    image_conflict_note="",
):
    result = call_agent_with_tools(
        GENERATE_DECISION_SYSTEM,
        user_request,
        context={
            "task_id": task_id,
            "device_context": device_context,
            "reference_image_analysis": reference_image_analysis or {},
            "image_reference_summary": image_reference_summary or "",
            "image_conflict_note": image_conflict_note or "",
        },
        trace={"task_id": task_id, "flow_type": "generate_decision", "agent_name": "Generate_Decision", "stage": "route"},
        tools=GENERATE_DECISION_TOOL_SCHEMAS,
        validator=validate_generate_tool_call,
        parsed_output_builder=lambda tool_name, tool_arguments: normalize_generate_tool_call_payload(
            {"tool": tool_name, "arguments": tool_arguments}
        ),
        fallback_parser=lambda system, user, context: normalized_legacy_agent_response(
            system, user, context, normalize_generate_tool_call_payload
        ),
    )
    decision = result.get("parsed_output")
    usage = result.get("usage")
    error = result.get("error")

    if not decision:
        return None, usage, error or "Generate decision agent failed"

    print(f"[generate_decision] raw_payload={json.dumps(decision, ensure_ascii=False)}")

    return decision, usage, None


def decide_feedback_action(user_message, task_context=None, task_id=None):
    result = call_agent_with_tools(
        FEEDBACK_ROUTE_SYSTEM,
        user_message,
        context={
            "task_id": task_id,
            "task_context": task_context or {},
        },
        trace={"task_id": task_id, "flow_type": "feedback_route", "agent_name": "Feedback_Router", "stage": "route"},
        tools=FEEDBACK_ROUTE_TOOL_SCHEMAS,
        validator=validate_feedback_route_call,
        parsed_output_builder=lambda tool_name, tool_arguments: tool_arguments,
        fallback_parser=legacy_agent_response_detailed,
    )
    decision = result.get("parsed_output")
    usage = result.get("usage")
    error = result.get("error")

    if not decision:
        return None, usage, error or "Feedback route decision agent failed"

    print(f"[feedback_route] raw_payload={json.dumps(decision, ensure_ascii=False)}")

    return decision, usage, None


def summarize_runtime_error(stack_trace, task_context=None, task_id=None):
    result = call_agent_with_tools(
        RUNTIME_ERROR_SUMMARY_SYSTEM,
        stack_trace,
        context={
            "task_id": task_id,
            "task_context": task_context or {},
        },
        trace={"task_id": task_id, "flow_type": "runtime_error_summary", "agent_name": "Runtime_Error_Summarizer", "stage": "summarize"},
        tools=RUNTIME_ERROR_SUMMARY_TOOL_SCHEMAS,
        validator=validate_runtime_error_summary,
        parsed_output_builder=lambda tool_name, tool_arguments: tool_arguments,
        fallback_parser=legacy_agent_response_detailed,
    )
    decision = result.get("parsed_output")
    usage = result.get("usage")
    error = result.get("error")

    if not decision:
        return None, usage, error or "Runtime error summary agent failed"

    print(f"[runtime_error_summary] raw_payload={json.dumps(decision, ensure_ascii=False)}")

    return decision, usage, None


def summarize_build_failure(error_log, failure_stage=None, failure_type=None, task_context=None, task_id=None):
    result = call_agent_with_tools(
        BUILD_FAILURE_SUMMARY_SYSTEM,
        error_log,
        context={
            "task_id": task_id,
            "failure_stage": failure_stage,
            "failure_type": failure_type,
            "task_context": task_context or {},
        },
        trace={"task_id": task_id, "flow_type": "build_failure_summary", "agent_name": "Build_Failure_Summarizer", "stage": "summarize"},
        tools=BUILD_FAILURE_SUMMARY_TOOL_SCHEMAS,
        validator=validate_build_failure_summary,
        parsed_output_builder=lambda tool_name, tool_arguments: tool_arguments,
        fallback_parser=legacy_agent_response_detailed,
    )
    decision = result.get("parsed_output")
    usage = result.get("usage")
    error = result.get("error")

    if not decision:
        return None, usage, error or "Build failure summary agent failed"

    print(f"[build_failure_summary] raw_payload={json.dumps(decision, ensure_ascii=False)}")

    return decision, usage, None


def _strip_html_tags(text):
    return re.sub(r"<[^>]+>", " ", text or "").strip()


def _normalize_search_url(raw_url):
    url = unescape(raw_url or "").strip()
    if not url:
        return ""
    if url.startswith("//"):
        url = "https:" + url
    if "duckduckgo.com/l/?" in url:
        parsed = urlparse.urlparse(url)
        target = urlparse.parse_qs(parsed.query).get("uddg", [""])[0]
        if target:
            return urlparse.unquote(target)
    return url


def _collapse_text(text):
    return " ".join((text or "").split())


def _extract_html_title(html):
    match = re.search(r"<title[^>]*>(.*?)</title>", html or "", re.I | re.S)
    if not match:
        return ""
    return _collapse_text(_strip_html_tags(unescape(match.group(1) or "")))


def _download_web_document(url):
    request = urlrequest.Request(
        url,
        headers={"User-Agent": "Mozilla/5.0 VibeFactory/1.0"}
    )
    with urlrequest.urlopen(request, timeout=15) as response:
        final_url = response.geturl()
        html = response.read().decode("utf-8", errors="ignore")
    title = _extract_html_title(html)
    text_content = _collapse_text(_strip_html_tags(unescape(html)))
    return final_url, html, title, text_content


def _absolute_web_url(base_url, candidate):
    value = unescape(candidate or "").strip()
    if not value:
        return ""
    if value.startswith("//"):
        parsed_base = urlparse.urlparse(base_url or "")
        return f"{parsed_base.scheme or 'https'}:{value}"
    return urlparse.urljoin(base_url or "", value)


def _looks_like_data_url(url):
    lowered = (url or "").lower()
    markers = [
        "api",
        "json",
        "ajax",
        "meal",
        "menu",
        "food",
        "diet",
        "cafeteria",
        "restaurant",
        "schedule",
        "calendar",
        "식단",
        "메뉴",
        "학식",
        "급식",
    ]
    return any(marker in lowered for marker in markers)


class _WebStructureParser(HTMLParser):
    def __init__(self, base_url):
        super().__init__(convert_charrefs=True)
        self.base_url = base_url
        self.links = []
        self.iframes = []
        self.scripts = []
        self.json_ld = []
        self.tables = []
        self.forms = []
        self.meta = []
        self._current_table = None
        self._current_row = None
        self._current_cell = None
        self._current_script = None
        self._current_json_ld = False
        self._current_form = None
        self._in_title = False

    def handle_starttag(self, tag, attrs):
        attrs_dict = {k.lower(): (v or "") for k, v in attrs if k}
        tag = (tag or "").lower()
        if tag == "a":
            href = _absolute_web_url(self.base_url, attrs_dict.get("href"))
            if href and len(self.links) < 80:
                self.links.append({
                    "url": href,
                    "text": "",
                    "title": attrs_dict.get("title", ""),
                })
        elif tag == "iframe":
            src = _absolute_web_url(self.base_url, attrs_dict.get("src"))
            if src and len(self.iframes) < 20:
                self.iframes.append({"url": src, "title": attrs_dict.get("title", "")})
        elif tag == "script":
            src = _absolute_web_url(self.base_url, attrs_dict.get("src"))
            script_type = attrs_dict.get("type", "")
            if src and len(self.scripts) < 40:
                self.scripts.append({"src": src, "type": script_type, "inline_preview": ""})
            self._current_script = {"src": src, "type": script_type, "text": ""}
            self._current_json_ld = script_type.lower() == "application/ld+json"
        elif tag == "table":
            self._current_table = {"headers": [], "rows": []}
        elif tag == "tr" and self._current_table is not None:
            self._current_row = []
        elif tag in {"td", "th"} and self._current_row is not None:
            self._current_cell = {"tag": tag, "text": ""}
        elif tag == "form":
            action = _absolute_web_url(self.base_url, attrs_dict.get("action"))
            self._current_form = {
                "action": action,
                "method": (attrs_dict.get("method") or "GET").upper(),
                "inputs": [],
            }
        elif tag in {"input", "select", "textarea"} and self._current_form is not None:
            name = attrs_dict.get("name") or attrs_dict.get("id") or ""
            if name and len(self._current_form["inputs"]) < 20:
                self._current_form["inputs"].append(name)
        elif tag == "meta":
            name = attrs_dict.get("name") or attrs_dict.get("property") or attrs_dict.get("http-equiv") or ""
            content = attrs_dict.get("content") or ""
            if (name or content) and len(self.meta) < 40:
                self.meta.append({"name": name, "content": content[:300]})

    def handle_endtag(self, tag):
        tag = (tag or "").lower()
        if tag == "script" and self._current_script is not None:
            text = self._current_script.get("text", "").strip()
            if self._current_json_ld and text and len(self.json_ld) < 10:
                self.json_ld.append(text[:4000])
            elif text and len(self.scripts) < 40:
                self.scripts.append({
                    "src": self._current_script.get("src", ""),
                    "type": self._current_script.get("type", ""),
                    "inline_preview": text[:1500],
                })
            self._current_script = None
            self._current_json_ld = False
        elif tag in {"td", "th"} and self._current_cell is not None and self._current_row is not None:
            self._current_row.append({
                "tag": self._current_cell.get("tag", "td"),
                "text": _collapse_text(self._current_cell.get("text", "")),
            })
            self._current_cell = None
        elif tag == "tr" and self._current_table is not None and self._current_row is not None:
            cells = [cell for cell in self._current_row if cell.get("text")]
            if cells:
                if all(cell.get("tag") == "th" for cell in cells) and not self._current_table["headers"]:
                    self._current_table["headers"] = [cell.get("text", "") for cell in cells][:12]
                elif len(self._current_table["rows"]) < 20:
                    self._current_table["rows"].append([cell.get("text", "") for cell in cells][:12])
            self._current_row = None
        elif tag == "table" and self._current_table is not None:
            if self._current_table["rows"] and len(self.tables) < 8:
                self.tables.append(self._current_table)
            self._current_table = None
        elif tag == "form" and self._current_form is not None:
            if len(self.forms) < 10:
                self.forms.append(self._current_form)
            self._current_form = None

    def handle_data(self, data):
        if self._current_cell is not None:
            self._current_cell["text"] += " " + (data or "")
        if self._current_script is not None:
            self._current_script["text"] += data or ""
        if self.links and data and not self.links[-1].get("text"):
            text = _collapse_text(data)
            if text:
                self.links[-1]["text"] = text[:200]


def _extract_inline_endpoint_candidates(text, base_url, max_items=20):
    candidates = []
    seen = set()
    patterns = [
        r"https?://[A-Za-z0-9가-힣._~:/?#\[\]@!$&'()*+,;=%-]+",
        r"['\"]((?:/|\.{1,2}/)[A-Za-z0-9가-힣._~:/?#\[\]@!$&()*+,;=%-]+)['\"]",
    ]
    for pattern in patterns:
        for match in re.findall(pattern, text or "", re.I):
            raw = match[0] if isinstance(match, tuple) else match
            candidate = _absolute_web_url(base_url, raw).rstrip(".,;")
            if not candidate or candidate in seen:
                continue
            if _looks_like_data_url(candidate):
                seen.add(candidate)
                candidates.append(candidate)
            if len(candidates) >= max_items:
                return candidates
    return candidates


def _extract_web_structure(html, base_url):
    parser = _WebStructureParser(base_url)
    try:
        parser.feed(html or "")
    except Exception:
        pass
    script_text = "\n".join(
        item.get("inline_preview", "") for item in parser.scripts if isinstance(item, dict)
    )
    link_candidates = [
        item.get("url", "") for item in (parser.links + parser.iframes)
        if _looks_like_data_url(item.get("url", ""))
    ][:20]
    script_candidates = [
        item.get("src", "") for item in parser.scripts
        if _looks_like_data_url(item.get("src", ""))
    ][:20]
    inline_candidates = _extract_inline_endpoint_candidates(script_text, base_url, max_items=20)
    candidate_urls = _normalize_string_list(link_candidates + script_candidates + inline_candidates, max_items=30)
    return {
        "tables": parser.tables[:8],
        "links": parser.links[:40],
        "iframes": parser.iframes[:20],
        "scripts": parser.scripts[:25],
        "forms": parser.forms[:10],
        "meta": parser.meta[:20],
        "json_ld": parser.json_ld[:5],
        "candidate_data_urls": candidate_urls,
        "signals": {
            "table_count": len(parser.tables),
            "iframe_count": len(parser.iframes),
            "script_count": len(parser.scripts),
            "form_count": len(parser.forms),
            "candidate_data_url_count": len(candidate_urls),
        },
    }


def _table_sample_records(tables, max_records=5):
    records = []
    for table_index, table in enumerate(tables or []):
        headers = [str(item) for item in (table.get("headers") or [])]
        for row in table.get("rows") or []:
            values = [str(item) for item in row if str(item).strip()]
            if not values:
                continue
            if headers and len(headers) == len(values):
                record = {headers[index]: values[index] for index in range(len(values))}
            else:
                record = {"columns": values}
            record["_source"] = f"table[{table_index}]"
            records.append(record)
            if len(records) >= max_records:
                return records
    return records


def _line_sample_records(text_content, max_records=5):
    lines = [_collapse_text(line) for line in re.split(r"[\n\r]+| {2,}", text_content or "")]
    records = []
    date_pattern = re.compile(r"(20\d{2}[./-]\d{1,2}[./-]\d{1,2}|\d{1,2}[./-]\d{1,2}(?:\([^)]+\))?|\d{1,2}월\s*\d{1,2}일)")
    food_markers = ["식단", "메뉴", "조식", "중식", "석식", "백반", "한식", "라면", "밥", "국", "반찬", "샐러드"]
    for line in lines:
        if len(line) < 8:
            continue
        if date_pattern.search(line) or any(marker in line for marker in food_markers):
            records.append({"text": line[:500], "_source": "text"})
        if len(records) >= max_records:
            break
    return records


def analyze_web_data_source(url, user_goal="", page_result=None, task_id=None):
    page = page_result if isinstance(page_result, dict) else {}
    if not page:
        page = fetch_webpage(url, task_id=task_id, max_chars=20000)
    if page.get("status") != "success":
        return {
            "status": "failed",
            "url": url,
            "source_kind": "unusable",
            "confidence": 0.0,
            "sample_records": [],
            "candidate_urls": [],
            "failure_reason": page.get("error") or "fetch_failed",
        }

    final_url = page.get("final_url") or url
    structure = page.get("web_structure")
    if not isinstance(structure, dict):
        structure = _extract_web_structure(page.get("raw_html") or "", final_url)
    tables = structure.get("tables") or []
    candidate_urls = _normalize_string_list(
        [final_url] + (structure.get("candidate_data_urls") or []) + [
            item.get("url", "") for item in structure.get("iframes") or [] if isinstance(item, dict)
        ],
        max_items=12,
    )
    table_records = _table_sample_records(tables)
    text_records = _line_sample_records(page.get("text_content") or "")
    sample_records = table_records or text_records

    source_kind = "unusable"
    parser_strategy = "none"
    confidence = 0.0
    failure_reason = ""
    if table_records:
        source_kind = "static_html_table"
        parser_strategy = "html_table"
        confidence = 0.85
    elif text_records:
        source_kind = "static_html_text"
        parser_strategy = "text_pattern"
        confidence = 0.65
    elif structure.get("candidate_data_urls"):
        source_kind = "json_endpoint_candidate"
        parser_strategy = "discover_and_fetch_candidate_url"
        confidence = 0.55
        failure_reason = "candidate_endpoint_requires_followup_probe"
    elif structure.get("iframes"):
        source_kind = "iframe"
        parser_strategy = "follow_iframe_then_parse"
        confidence = 0.45
        failure_reason = "iframe_requires_followup_fetch"
    elif structure.get("scripts"):
        source_kind = "dynamic_js"
        parser_strategy = "dynamic_or_script_backed"
        confidence = 0.25
        failure_reason = "static_fetch_has_no_sample_records"
    else:
        failure_reason = "no_structured_or_sample_data_found"

    parser_contract = {
        "source_kind": source_kind,
        "accepted_source_kinds": [source_kind] if source_kind != "unusable" else [],
        "primary_url": final_url,
        "candidate_urls": candidate_urls,
        "parser_strategy": parser_strategy,
        "accepted_parser_strategies": [parser_strategy] if parser_strategy != "none" else [],
        "minimum_sample_records": 1,
        "sample_records": sample_records[:5],
        "required_runtime_behavior": "fetch_live_data_and_show_empty_state_on_parse_failure",
    }
    analysis = {
        "status": "success" if source_kind != "unusable" else "failed",
        "url": url,
        "final_url": final_url,
        "title": page.get("title") or "",
        "source_kind": source_kind,
        "confidence": confidence,
        "candidate_urls": candidate_urls,
        "sample_records": sample_records[:5],
        "parser_contract": parser_contract,
        "structure_signals": structure.get("signals") or {},
        "failure_reason": failure_reason,
    }
    record_agent_trace(
        task_id=task_id,
        flow_type="web_research",
        agent_name="Web_Tool",
        stage="web_data_analyze",
        model="web_tool",
        temperature=0.0,
        system_prompt="analyze_web_data_source",
        instruction=user_goal or url,
        input_payload={"url": url, "user_goal": user_goal},
        raw_output=json.dumps({"structure_signals": analysis.get("structure_signals")}, ensure_ascii=False),
        parsed_output=analysis,
        tool_name="analyze_web_data_source",
        tool_arguments={"url": url, "user_goal": user_goal},
        finish_reason="completed",
        parse_status="success" if analysis["status"] == "success" else "failed",
        parse_error=None if analysis["status"] == "success" else failure_reason,
        validation_result="success" if analysis["status"] == "success" else "failed",
        fallback_used=False,
        fallback_reason=None,
        usage={},
    )
    return analysis


def _normalize_auth_scheme_name(name):
    lowered = (name or "").lower()
    if lowered in {"apikey", "api_key"}:
        return "apiKey"
    if lowered in {"http", "bearer"}:
        return "bearer"
    if lowered == "oauth2":
        return "oauth2"
    if lowered == "basic":
        return "basic"
    return name or ""


def _extract_openapi_auth_schemes(spec):
    schemes = []
    components = spec.get("components") if isinstance(spec, dict) else {}
    security_schemes = components.get("securitySchemes") if isinstance(components, dict) else {}
    for _, scheme in (security_schemes or {}).items():
        if not isinstance(scheme, dict):
            continue
        scheme_type = _normalize_auth_scheme_name(str(scheme.get("type") or ""))
        scheme_name = _normalize_auth_scheme_name(str(scheme.get("scheme") or ""))
        if scheme_type == "http" and scheme_name:
            scheme_type = scheme_name
        if scheme_type and scheme_type not in schemes:
            schemes.append(scheme_type)
    return schemes


def _resolve_openapi_schema(schema, spec, depth=0):
    if not isinstance(schema, dict) or depth > 2:
        return {}
    if "$ref" in schema and isinstance(schema.get("$ref"), str):
        ref = schema.get("$ref")
        if ref.startswith("#/"):
            target = spec
            for part in ref[2:].split("/"):
                if not isinstance(target, dict):
                    return {}
                target = target.get(part)
            if isinstance(target, dict):
                return _resolve_openapi_schema(target, spec, depth + 1)
        return {}
    return schema


def _openapi_type_hint(schema, spec):
    resolved = _resolve_openapi_schema(schema, spec)
    if not isinstance(resolved, dict):
        return ""
    schema_type = resolved.get("type")
    if schema_type == "array":
        items = _resolve_openapi_schema(resolved.get("items"), spec)
        item_type = items.get("type") if isinstance(items, dict) else ""
        return f"array[{item_type or 'object'}]"
    if isinstance(schema_type, str) and schema_type:
        return schema_type
    if resolved.get("properties"):
        return "object"
    if resolved.get("enum"):
        return "enum"
    return ""


def _extract_schema_field_names(schema, spec, max_items=12):
    resolved = _resolve_openapi_schema(schema, spec)
    if not isinstance(resolved, dict):
        return []
    properties = resolved.get("properties")
    if isinstance(properties, dict):
        names = [name for name in properties.keys() if isinstance(name, str)]
        return names[:max_items]
    if resolved.get("type") == "array":
        items = _resolve_openapi_schema(resolved.get("items"), spec)
        if isinstance(items, dict):
            item_props = items.get("properties")
            if isinstance(item_props, dict):
                names = [name for name in item_props.keys() if isinstance(name, str)]
                return names[:max_items]
    return []


def _extract_operation_parameters(operation, path_item, spec, max_items=10):
    combined = []
    seen = set()
    for candidate in (path_item.get("parameters"), operation.get("parameters")):
        if not isinstance(candidate, list):
            continue
        for param in candidate:
            resolved = _resolve_openapi_schema(param, spec)
            if not isinstance(resolved, dict):
                continue
            name = resolved.get("name")
            location = resolved.get("in")
            if not isinstance(name, str) or not isinstance(location, str):
                continue
            key = (name, location)
            if key in seen:
                continue
            seen.add(key)
            combined.append({
                "name": name,
                "in": location,
                "required": bool(resolved.get("required")),
                "type_hint": _openapi_type_hint(resolved.get("schema"), spec),
            })
            if len(combined) >= max_items:
                return combined
    return combined


def _extract_request_body_hints(operation, spec):
    request_body = _resolve_openapi_schema(operation.get("requestBody"), spec)
    if not isinstance(request_body, dict):
        return False, []
    content = request_body.get("content")
    if not isinstance(content, dict):
        return bool(request_body.get("required")), []
    for _, media in content.items():
        if not isinstance(media, dict):
            continue
        fields = _extract_schema_field_names(media.get("schema"), spec)
        return bool(request_body.get("required")), fields
    return bool(request_body.get("required")), []


def _extract_response_schema_hint(operation, spec):
    responses = operation.get("responses")
    if not isinstance(responses, dict):
        return []
    preferred_codes = ["200", "201", "default"]
    ordered_codes = preferred_codes + [code for code in responses.keys() if code not in preferred_codes]
    for code in ordered_codes:
        response = _resolve_openapi_schema(responses.get(code), spec)
        if not isinstance(response, dict):
            continue
        content = response.get("content")
        if not isinstance(content, dict):
            continue
        for _, media in content.items():
            if not isinstance(media, dict):
                continue
            fields = _extract_schema_field_names(media.get("schema"), spec)
            if fields:
                return fields
    return []


def _extract_openapi_endpoints(spec, max_items=20):
    endpoints = []
    paths = spec.get("paths") if isinstance(spec, dict) else {}
    for path, path_item in (paths or {}).items():
        if not isinstance(path, str) or not isinstance(path_item, dict):
            continue
        methods = []
        operation_ids = []
        tags = []
        parameters = []
        request_body_required = False
        request_body_schema_hint = []
        response_schema_hint = []
        for method, operation in path_item.items():
            upper = str(method).upper()
            if upper not in {"GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS"}:
                continue
            methods.append(upper)
            if not isinstance(operation, dict):
                continue
            op_id = operation.get("operationId")
            if isinstance(op_id, str) and op_id.strip():
                operation_ids.append(op_id.strip())
            op_tags = operation.get("tags")
            if isinstance(op_tags, list):
                for tag in op_tags:
                    if isinstance(tag, str) and tag.strip() and tag.strip() not in tags:
                        tags.append(tag.strip())
            if not parameters:
                parameters = _extract_operation_parameters(operation, path_item, spec)
            if not request_body_schema_hint and not request_body_required:
                request_body_required, request_body_schema_hint = _extract_request_body_hints(operation, spec)
            if not response_schema_hint:
                response_schema_hint = _extract_response_schema_hint(operation, spec)
        if methods:
            endpoints.append({
                "path": path,
                "methods": sorted(set(methods)),
                "operation_ids": operation_ids[:10],
                "tags": tags[:10],
                "parameters": parameters[:10],
                "request_body_required": request_body_required,
                "request_body_schema_hint": request_body_schema_hint[:12],
                "response_schema_hint": response_schema_hint[:12],
            })
        if len(endpoints) >= max_items:
            break
    return endpoints


def parse_openapi_reference(url, task_id=None):
    request = urlrequest.Request(
        url,
        headers={"User-Agent": "Mozilla/5.0 VibeFactory/1.0", "Accept": "application/json, text/plain, */*"}
    )
    try:
        with urlrequest.urlopen(request, timeout=15) as response:
            final_url = response.geturl()
            raw_body = response.read().decode("utf-8", errors="ignore")
        spec = json.loads(raw_body)
    except Exception as e:
        record_agent_trace(
            task_id=task_id,
            flow_type="web_research",
            agent_name="Web_Tool",
            stage="openapi_parse",
            model="web_tool",
            temperature=0.0,
            system_prompt="parse_openapi_reference",
            instruction=url,
            input_payload={"url": url},
            raw_output=None,
            parsed_output=None,
            tool_name="parse_openapi_reference",
            tool_arguments={"url": url},
            finish_reason="error",
            parse_status="error",
            parse_error=str(e),
            validation_result="failed",
            fallback_used=False,
            fallback_reason=None,
            usage={},
        )
        return {"status": "failed", "url": url, "error": str(e)}

    info = spec.get("info") if isinstance(spec, dict) else {}
    title = ""
    if isinstance(info, dict):
        title = _collapse_text(str(info.get("title") or ""))
    servers = []
    for item in spec.get("servers", []) if isinstance(spec, dict) else []:
        if isinstance(item, dict) and isinstance(item.get("url"), str) and item.get("url").strip():
            servers.append(item.get("url").strip())
    auth_schemes = _extract_openapi_auth_schemes(spec)
    endpoints = _extract_openapi_endpoints(spec)
    result = {
        "title": title,
        "final_url": final_url,
        "detected_api_name": title or _extract_api_name(title, raw_body[:500], final_url),
        "servers": servers[:10],
        "auth_schemes": auth_schemes[:10],
        "endpoints": endpoints,
    }
    record_agent_trace(
        task_id=task_id,
        flow_type="web_research",
        agent_name="Web_Tool",
        stage="openapi_parse",
        model="web_tool",
        temperature=0.0,
        system_prompt="parse_openapi_reference",
        instruction=url,
        input_payload={"url": url},
        raw_output=raw_body[:12000],
        parsed_output=result,
        tool_name="parse_openapi_reference",
        tool_arguments={"url": url},
        finish_reason="completed",
        parse_status="success",
        parse_error=None,
        validation_result="success",
        fallback_used=False,
        fallback_reason=None,
        usage={},
    )
    return {"status": "success", **result}


def fetch_webpage(url, task_id=None, max_chars=12000):
    try:
        final_url, html, title, text_content = _download_web_document(url)
    except Exception as e:
        record_agent_trace(
            task_id=task_id,
            flow_type="web_research",
            agent_name="Web_Tool",
            stage="fetch",
            model="web_tool",
            temperature=0.0,
            system_prompt="fetch_webpage",
            instruction=url,
            input_payload={"url": url},
            raw_output=None,
            parsed_output=None,
            tool_name="fetch_webpage",
            tool_arguments={"url": url},
            finish_reason="error",
            parse_status="error",
            parse_error=str(e),
            validation_result="failed",
            fallback_used=False,
            fallback_reason=None,
            usage={},
        )
        return {"status": "failed", "url": url, "error": str(e)}

    result = {
        "title": title,
        "final_url": final_url,
        "text_content": text_content[:max_chars],
        "web_structure": _extract_web_structure(html, final_url),
    }
    record_agent_trace(
        task_id=task_id,
        flow_type="web_research",
        agent_name="Web_Tool",
        stage="fetch",
        model="web_tool",
        temperature=0.0,
        system_prompt="fetch_webpage",
        instruction=url,
        input_payload={"url": url},
        raw_output=html[:12000],
        parsed_output=result,
        tool_name="fetch_webpage",
        tool_arguments={"url": url},
        finish_reason="completed",
        parse_status="success",
        parse_error=None,
        validation_result="success",
        fallback_used=False,
        fallback_reason=None,
        usage={},
    )
    return {"status": "success", **result}


def _extract_api_name(title, text_content, final_url):
    candidates = [
        title,
        text_content[:300],
        final_url,
    ]
    for candidate in candidates:
        if not isinstance(candidate, str):
            continue
        match = re.search(r"([A-Za-z0-9._ -]{2,40})\s+(API|OpenAPI|Swagger)", candidate, re.I)
        if match:
            return _collapse_text(match.group(1) + " " + match.group(2))
    host = (urlparse.urlparse(final_url or "").netloc or "").split(":")[0]
    return host or ""


def _extract_base_url(text_content, final_url):
    candidates = re.findall(r"https?://[A-Za-z0-9._:/\\-]+", text_content or "")
    for candidate in candidates:
        if "/api" in candidate.lower() or "/v1" in candidate.lower() or "/v2" in candidate.lower():
            return candidate.rstrip('.,)')
    return ""


def _extract_auth_hints(text_content):
    lowered = (text_content or "").lower()
    hints = []
    if "bearer" in lowered or "authorization" in lowered:
        hints.append("bearer_or_authorization_header")
    if "api key" in lowered or "x-api-key" in lowered:
        hints.append("api_key")
    if "oauth" in lowered or "oauth2" in lowered:
        hints.append("oauth")
    if "basic auth" in lowered:
        hints.append("basic_auth")
    return hints


def _extract_endpoint_hints(text_content):
    endpoints = []
    seen = set()
    for method, path in re.findall(r"\b(GET|POST|PUT|DELETE|PATCH)\s+(/[A-Za-z0-9._/{}/-]+)", text_content or "", re.I):
        normalized = f"{method.upper()} {path}"
        if normalized not in seen:
            endpoints.append(normalized)
            seen.add(normalized)
        if len(endpoints) >= 10:
            break
    return endpoints


def fetch_api_reference(url, task_id=None, max_chars=12000):
    try:
        final_url, html, title, text_content = _download_web_document(url)
    except Exception as e:
        record_agent_trace(
            task_id=task_id,
            flow_type="web_research",
            agent_name="Web_Tool",
            stage="api_fetch",
            model="web_tool",
            temperature=0.0,
            system_prompt="fetch_api_reference",
            instruction=url,
            input_payload={"url": url},
            raw_output=None,
            parsed_output=None,
            tool_name="fetch_api_reference",
            tool_arguments={"url": url},
            finish_reason="error",
            parse_status="error",
            parse_error=str(e),
            validation_result="failed",
            fallback_used=False,
            fallback_reason=None,
            usage={},
        )
        return {"status": "failed", "url": url, "error": str(e)}

    result = {
        "title": title,
        "final_url": final_url,
        "text_content": text_content[:max_chars],
        "detected_api_name": _extract_api_name(title, text_content, final_url),
        "detected_base_url": _extract_base_url(text_content, final_url),
        "auth_hints": _extract_auth_hints(text_content),
        "endpoint_hints": _extract_endpoint_hints(text_content),
    }
    record_agent_trace(
        task_id=task_id,
        flow_type="web_research",
        agent_name="Web_Tool",
        stage="api_fetch",
        model="web_tool",
        temperature=0.0,
        system_prompt="fetch_api_reference",
        instruction=url,
        input_payload={"url": url},
        raw_output=html[:12000],
        parsed_output=result,
        tool_name="fetch_api_reference",
        tool_arguments={"url": url},
        finish_reason="completed",
        parse_status="success",
        parse_error=None,
        validation_result="success",
        fallback_used=False,
        fallback_reason=None,
        usage={},
    )
    return {"status": "success", **result}


def search_web_reference(query, task_id=None, max_results=5):
    search_url = "https://html.duckduckgo.com/html/?q=" + urlparse.quote(query)
    request = urlrequest.Request(
        search_url,
        headers={"User-Agent": "Mozilla/5.0 VibeFactory/1.0"}
    )

    try:
        with urlrequest.urlopen(request, timeout=15) as response:
            html = response.read().decode("utf-8", errors="ignore")
    except Exception as e:
        record_agent_trace(
            task_id=task_id,
            flow_type="web_research",
            agent_name="Web_Tool",
            stage="search",
            model="web_tool",
            temperature=0.0,
            system_prompt="search_web_reference",
            instruction=query,
            input_payload={"query": query, "max_results": max_results},
            raw_output=None,
            parsed_output=None,
            tool_name="search_web_reference",
            tool_arguments={"query": query},
            finish_reason="error",
            parse_status="error",
            parse_error=str(e),
            validation_result="failed",
            fallback_used=False,
            fallback_reason=None,
            usage={},
        )
        return {"status": "failed", "error": str(e), "query": query, "results": []}

    results = []
    pattern = re.compile(
        r'<a[^>]*class="result__a"[^>]*href="(?P<url>[^"]+)"[^>]*>(?P<title>.*?)</a>.*?(?:<a[^>]*class="result__snippet"[^>]*>(?P<snippet_a>.*?)</a>|<span[^>]*class="result__snippet"[^>]*>(?P<snippet_span>.*?)</span>)?',
        re.S,
    )
    for match in pattern.finditer(html):
        url = _normalize_search_url(match.group("url"))
        title = _strip_html_tags(unescape(match.group("title") or ""))
        snippet = _strip_html_tags(unescape(match.group("snippet_a") or match.group("snippet_span") or ""))
        if not url or not title:
            continue
        results.append(
            {
                "title": title,
                "url": url,
                "snippet": " ".join(snippet.split()),
            }
        )
        if len(results) >= max_results:
            break

    record_agent_trace(
        task_id=task_id,
        flow_type="web_research",
        agent_name="Web_Tool",
        stage="search",
        model="web_tool",
        temperature=0.0,
        system_prompt="search_web_reference",
        instruction=query,
        input_payload={"query": query, "max_results": max_results},
        raw_output=html[:12000],
        parsed_output={"results": results},
        tool_name="search_web_reference",
        tool_arguments={"query": query},
        finish_reason="completed",
        parse_status="success" if results else "no_results",
        parse_error=None if results else "no_search_results",
        validation_result="success" if results else "failed",
        fallback_used=False,
        fallback_reason=None,
        usage={},
    )
    return {"status": "success" if results else "failed", "query": query, "results": results}


def _score_api_doc_result(query, api_name, title, url, snippet):
    text = " ".join([title or "", url or "", snippet or ""]).lower()
    score = 0.0
    source_type = "generic_web"

    strong_markers = ["openapi", "swagger", "redoc", "api-docs", "reference"]
    medium_markers = ["developers", "developer", "docs", "api", "postman"]
    if any(marker in text for marker in strong_markers):
        score += 0.55
    elif any(marker in text for marker in medium_markers):
        score += 0.35

    host = (urlparse.urlparse(url or "").netloc or "").lower()
    if any(marker in host for marker in ["developers.", ".developers", "docs.", ".docs", "developer."]):
        score += 0.25
        source_type = "developer_portal"
    if any(marker in text for marker in ["openapi", "swagger", "redoc", "postman"]):
        source_type = "api_reference"
    elif source_type != "developer_portal" and any(marker in text for marker in ["developers", "docs", "reference"]):
        source_type = "official_docs"

    overlap = max(
        _count_token_overlap(query, text),
        _count_token_overlap(api_name or "", text),
    )
    if overlap >= 2:
        score += 0.2
    elif overlap >= 1:
        score += 0.1

    if _looks_low_trust_host(url):
        score -= 0.35
        source_type = "generic_web"

    score = max(0.0, min(score, 0.99))
    return source_type, round(score, 2)


def search_api_docs(query, api_name=None, task_id=None, max_results=5):
    effective_query = " ".join(
        part for part in [query, api_name, "API docs developers reference swagger openapi"] if isinstance(part, str) and part.strip()
    )
    search_result = search_web_reference(effective_query, task_id=task_id, max_results=max_results * 3)
    if search_result.get("status") != "success":
        record_agent_trace(
            task_id=task_id,
            flow_type="web_research",
            agent_name="Web_Tool",
            stage="api_search",
            model="web_tool",
            temperature=0.0,
            system_prompt="search_api_docs",
            instruction=query,
            input_payload={"query": query, "api_name": api_name, "max_results": max_results},
            raw_output=None,
            parsed_output=None,
            tool_name="search_api_docs",
            tool_arguments={"query": query, "api_name": api_name},
            finish_reason="error",
            parse_status="error",
            parse_error=search_result.get("error") or "search_failed",
            validation_result="failed",
            fallback_used=False,
            fallback_reason=None,
            usage={},
        )
        return {"status": "failed", "query": query, "results": [], "error": search_result.get("error")}

    ranked = []
    for item in search_result.get("results", []):
        source_type, confidence = _score_api_doc_result(
            query,
            api_name,
            item.get("title") or "",
            item.get("url") or "",
            item.get("snippet") or "",
        )
        ranked.append({
            **item,
            "source_type": source_type,
            "confidence": confidence,
        })

    ranked.sort(key=lambda item: item.get("confidence", 0.0), reverse=True)
    results = ranked[:max_results]
    record_agent_trace(
        task_id=task_id,
        flow_type="web_research",
        agent_name="Web_Tool",
        stage="api_search",
        model="web_tool",
        temperature=0.0,
        system_prompt="search_api_docs",
        instruction=query,
        input_payload={"query": query, "api_name": api_name, "max_results": max_results},
        raw_output=json.dumps(search_result.get("results", []), ensure_ascii=False)[:12000],
        parsed_output={"results": results},
        tool_name="search_api_docs",
        tool_arguments={"query": query, "api_name": api_name},
        finish_reason="completed",
        parse_status="success" if results else "no_results",
        parse_error=None if results else "no_search_results",
        validation_result="success" if results else "failed",
        fallback_used=False,
        fallback_reason=None,
        usage={},
    )
    return {"status": "success" if results else "failed", "query": query, "results": results}


def perform_web_research(query, task_id=None, max_results=5):
    return search_web_reference(query, task_id=task_id, max_results=max_results)


def _extract_research_tokens(text):
    return {
        token
        for token in re.findall(r"[A-Za-z0-9]{2,}|[가-힣]{2,}", text or "")
        if token.strip()
    }


def _count_token_overlap(query, text):
    query_tokens = _extract_research_tokens(query)
    if not query_tokens:
        return 0
    text_tokens = _extract_research_tokens(text)
    return len(query_tokens & text_tokens)


def _looks_low_trust_host(url):
    host = (urlparse.urlparse(url or "").netloc or "").lower()
    return any(marker in host for marker in ["blog", "tistory", "medium", "velog", "wordpress", "brunch"])


def _is_private_or_local_host(hostname):
    host = (hostname or "").strip().lower()
    if not host:
        return True
    if host in {"localhost", "127.0.0.1", "::1"} or host.endswith(".local"):
        return True
    try:
        ip = ipaddress.ip_address(host)
        return ip.is_private or ip.is_loopback or ip.is_link_local or ip.is_reserved or ip.is_multicast
    except ValueError:
        pass
    if re.match(r"^\d+\.\d+\.\d+\.\d+$", host):
        return True
    return host.startswith("10.") or host.startswith("192.168.") or host.startswith("172.")


def _coerce_safe_query_params(query_params):
    safe = {}
    if not isinstance(query_params, dict):
        return safe
    for key, value in query_params.items():
        if not isinstance(key, str) or not key.strip():
            continue
        if isinstance(value, (str, int, float, bool)):
            safe[key.strip()] = str(value)
    return safe


def test_http_request(method, url, headers=None, query_params=None, *, allowed_source=None, task_id=None):
    method_upper = (method or "").strip().upper()
    parsed = urlparse.urlparse(url or "")
    hostname = (parsed.hostname or "").strip().lower()
    blocked_reason = None

    if method_upper != "GET":
        blocked_reason = "method_not_allowed"
    elif parsed.scheme not in {"http", "https"}:
        blocked_reason = "unsupported_scheme"
    elif _is_private_or_local_host(hostname):
        blocked_reason = "private_or_local_address"

    allowed_hosts = set()
    allowed_urls = set()
    if isinstance(allowed_source, dict):
        for candidate in [
            allowed_source.get("final_url"),
            allowed_source.get("url"),
            allowed_source.get("detected_base_url"),
        ] + list(allowed_source.get("servers") or []):
            if not isinstance(candidate, str) or not candidate.strip():
                continue
            allowed_urls.add(candidate.strip())
            try:
                candidate_host = (urlparse.urlparse(candidate).hostname or "").strip().lower()
                if candidate_host:
                    allowed_hosts.add(candidate_host)
            except Exception:
                continue
    if not blocked_reason and allowed_hosts and hostname not in allowed_hosts:
        blocked_reason = "url_not_related_to_selected_source"

    safe_headers = {}
    for key, value in (headers or {}).items() if isinstance(headers, dict) else []:
        if not isinstance(key, str) or not isinstance(value, str):
            continue
        normalized_key = key.strip().lower()
        if normalized_key in {"accept", "user-agent"}:
            safe_headers[key.strip()] = value.strip()[:200]
        elif any(secret_key in normalized_key for secret_key in ["authorization", "token", "secret", "cookie", "api-key", "x-api-key"]):
            blocked_reason = blocked_reason or "unsafe_header_blocked"

    safe_query_params = _coerce_safe_query_params(query_params)

    if blocked_reason:
        parsed_output = {
            "final_url": url,
            "status_code": None,
            "response_preview": "",
            "response_content_type": "",
            "success": False,
            "blocked_reason": blocked_reason,
        }
        record_agent_trace(
            task_id=task_id,
            flow_type="web_research",
            agent_name="Web_Tool",
            stage="http_probe",
            model="web_tool",
            temperature=0.0,
            system_prompt="test_http_request",
            instruction=url,
            input_payload={"method": method_upper, "headers": headers or {}, "query_params": query_params or {}},
            raw_output=None,
            parsed_output=parsed_output,
            tool_name="test_http_request",
            tool_arguments={"method": method_upper, "url": url, "headers": safe_headers, "query_params": safe_query_params},
            finish_reason="blocked",
            parse_status="blocked",
            parse_error=blocked_reason,
            validation_result="failed",
            fallback_used=False,
            fallback_reason=None,
            usage={},
        )
        return parsed_output

    final_url = url
    if safe_query_params:
        query_string = urlparse.urlencode(safe_query_params)
        separator = "&" if parsed.query else "?"
        final_url = f"{url}{separator}{query_string}"
    request = urlrequest.Request(
        final_url,
        headers={"User-Agent": "Mozilla/5.0 VibeFactory/1.0", "Accept": "application/json, text/plain, */*"} | safe_headers,
        method="GET",
    )
    try:
        with urlrequest.urlopen(request, timeout=10) as response:
            response_bytes = response.read(4096)
            response_text = response_bytes.decode("utf-8", errors="ignore")
            content_type = response.headers.get("Content-Type", "")
            status_code = getattr(response, "status", None) or response.getcode()
            result = {
                "final_url": response.geturl(),
                "status_code": status_code,
                "response_preview": _collapse_text(response_text)[:600],
                "response_content_type": content_type,
                "success": 200 <= int(status_code or 0) < 300,
                "blocked_reason": None,
            }
            record_agent_trace(
                task_id=task_id,
                flow_type="web_research",
                agent_name="Web_Tool",
                stage="http_probe",
                model="web_tool",
                temperature=0.0,
                system_prompt="test_http_request",
                instruction=final_url,
                input_payload={"method": method_upper, "headers": safe_headers, "query_params": safe_query_params},
                raw_output=response_text[:12000],
                parsed_output=result,
                tool_name="test_http_request",
                tool_arguments={"method": method_upper, "url": url, "headers": safe_headers, "query_params": safe_query_params},
                finish_reason="completed",
                parse_status="success" if result["success"] else "failed",
                parse_error=None if result["success"] else f"http_status_{status_code}",
                validation_result="success" if result["success"] else "failed",
                fallback_used=False,
                fallback_reason=None,
                usage={},
            )
            return result
    except Exception as e:
        result = {
            "final_url": final_url,
            "status_code": None,
            "response_preview": "",
            "response_content_type": "",
            "success": False,
            "blocked_reason": str(e),
        }
        record_agent_trace(
            task_id=task_id,
            flow_type="web_research",
            agent_name="Web_Tool",
            stage="http_probe",
            model="web_tool",
            temperature=0.0,
            system_prompt="test_http_request",
            instruction=final_url,
            input_payload={"method": method_upper, "headers": safe_headers, "query_params": safe_query_params},
            raw_output=None,
            parsed_output=result,
            tool_name="test_http_request",
            tool_arguments={"method": method_upper, "url": url, "headers": safe_headers, "query_params": safe_query_params},
            finish_reason="error",
            parse_status="error",
            parse_error=str(e),
            validation_result="failed",
            fallback_used=False,
            fallback_reason=None,
            usage={},
        )
        return result


def select_best_api_source(query, candidates, task_id=None):
    candidates = [candidate for candidate in (candidates or []) if isinstance(candidate, dict)]
    scored_candidates = []
    rejected_sources = []

    for index, candidate in enumerate(candidates):
        title = _collapse_text(candidate.get("title") or "")
        url = candidate.get("final_url") or candidate.get("url") or ""
        snippet = _collapse_text(candidate.get("snippet") or "")
        text_content = _collapse_text(candidate.get("text_content") or "")
        source_type = (candidate.get("source_type") or "generic_web").strip() or "generic_web"
        source_kind = (candidate.get("source_kind") or "").strip()
        quality_passed = bool(candidate.get("quality_passed"))
        quality_reason = (candidate.get("quality_reason") or "").strip() or "quality_unknown"
        search_confidence = float(candidate.get("confidence") or 0.0)
        endpoints = candidate.get("endpoints") or []
        auth_schemes = candidate.get("auth_schemes") or []
        servers = candidate.get("servers") or []
        endpoint_hints = candidate.get("endpoint_hints") or []
        auth_hints = candidate.get("auth_hints") or []
        structured_endpoint_count = sum(1 for endpoint in endpoints if isinstance(endpoint, dict))
        has_structured_openapi = bool(servers or auth_schemes or endpoints)

        if not quality_passed:
            rejected_sources.append({
                "title": title,
                "url": url,
                "reason": f"quality_gate_failed:{quality_reason}",
            })
            continue

        score = 0.0
        if source_type == "official_docs":
            score += 0.45
        elif source_type == "api_reference":
            score += 0.42
        elif source_type == "developer_portal":
            score += 0.35
        else:
            score += 0.08

        if source_kind == "openapi_parse":
            score += 0.35
        elif source_kind == "api_fetch":
            score += 0.18

        if has_structured_openapi:
            score += 0.2
        if structured_endpoint_count >= 3:
            score += 0.15
        elif structured_endpoint_count >= 1:
            score += 0.08

        if auth_schemes or auth_hints:
            score += 0.05
        if servers:
            score += 0.04
        if endpoint_hints:
            score += 0.03

        overlap = max(
            _count_token_overlap(query, " ".join([title, snippet, url])),
            _count_token_overlap(query, text_content[:1200]),
        )
        if overlap >= 3:
            score += 0.18
        elif overlap >= 2:
            score += 0.12
        elif overlap >= 1:
            score += 0.06

        score += min(search_confidence, 0.99) * 0.15

        if _looks_low_trust_host(url):
            score -= 0.35
        if len(text_content) < 120 and not has_structured_openapi:
            score -= 0.15
        if source_kind == "openapi_parse" and not structured_endpoint_count:
            score -= 0.2

        score = max(0.0, min(score, 0.99))
        scored_candidates.append({
            **candidate,
            "score": round(score, 3),
            "selection_index": index,
        })

    scored_candidates.sort(
        key=lambda item: (
            item.get("score", 0.0),
            1 if item.get("source_kind") == "openapi_parse" else 0,
            float(item.get("confidence") or 0.0),
        ),
        reverse=True,
    )

    selected_source = scored_candidates[0] if scored_candidates else None
    if selected_source:
        selection_reason = (
            f"{selected_source.get('source_type') or 'source'} 우선, "
            f"{selected_source.get('source_kind') or 'candidate'} 구조, "
            f"quality={selected_source.get('quality_reason') or 'unknown'}"
        )
        confidence = round(float(selected_source.get("score") or 0.0), 2)
        for candidate in scored_candidates[1:]:
            rejected_sources.append({
                "title": _collapse_text(candidate.get("title") or ""),
                "url": candidate.get("final_url") or candidate.get("url") or "",
                "reason": f"lower_score:{candidate.get('score', 0.0)}",
            })
    else:
        selection_reason = "quality_passing_source_not_found"
        confidence = 0.0

    parsed_output = {
        "selected_source": selected_source,
        "rejected_sources": rejected_sources[:10],
        "selection_reason": selection_reason,
        "confidence": confidence,
    }
    record_agent_trace(
        task_id=task_id,
        flow_type="web_research",
        agent_name="API_Source_Selector",
        stage="source_select",
        model="rule_based",
        temperature=0.0,
        system_prompt="select_best_api_source",
        instruction=query,
        input_payload={"candidate_count": len(candidates)},
        raw_output=json.dumps(parsed_output, ensure_ascii=False)[:12000],
        parsed_output=parsed_output,
        tool_name="select_best_api_source",
        tool_arguments={"query": query},
        finish_reason="completed",
        parse_status="success" if selected_source else "failed",
        parse_error=None if selected_source else "no_selectable_source",
        validation_result="success" if selected_source else "failed",
        fallback_used=False,
        fallback_reason=None,
        usage={},
    )
    return parsed_output


def evaluate_research_quality(research_query, results, fetched_pages, *, direct_fetch=False, task_id=None):
    results = results or []
    fetched_pages = fetched_pages or []
    used_external_sources = []
    for page in fetched_pages:
        final_url = (page or {}).get("final_url")
        if isinstance(final_url, str) and final_url.strip():
            used_external_sources.append(final_url.strip())
    for item in results:
        item_url = (item or {}).get("url")
        if isinstance(item_url, str) and item_url.strip() and item_url.strip() not in used_external_sources:
            used_external_sources.append(item_url.strip())

    passed = False
    reason = "unknown"

    if not results:
        reason = "results_empty"
    elif not fetched_pages:
        reason = "representative_fetch_missing"
    elif direct_fetch:
        page = fetched_pages[0] or {}
        text_content = _collapse_text(page.get("text_content") or "")
        title = _collapse_text(page.get("title") or "")
        endpoint_hints = page.get("endpoint_hints") or []
        auth_hints = page.get("auth_hints") or []
        detected_base_url = page.get("detected_base_url") or ""
        openapi_servers = page.get("servers") or []
        openapi_endpoints = page.get("endpoints") or []
        openapi_auth = page.get("auth_schemes") or []
        openapi_has_structure = any(
            endpoint.get("parameters") or endpoint.get("request_body_schema_hint") or endpoint.get("response_schema_hint")
            for endpoint in openapi_endpoints if isinstance(endpoint, dict)
        )
        has_structured_api_reference = bool(openapi_servers or openapi_endpoints or openapi_auth)
        if len(text_content) < 80 and not (endpoint_hints or auth_hints or detected_base_url or has_structured_api_reference):
            reason = "direct_fetch_content_too_short"
        elif not title and len(text_content) < 160 and not (endpoint_hints or auth_hints or has_structured_api_reference):
            reason = "direct_fetch_content_too_weak"
        elif has_structured_api_reference and not openapi_endpoints:
            reason = "openapi_reference_too_sparse"
        else:
            passed = True
            reason = (
                "openapi_reference_structured"
                if has_structured_api_reference and openapi_has_structure
                else ("openapi_reference_ok" if has_structured_api_reference else ("direct_fetch_ok" if not (endpoint_hints or auth_hints or detected_base_url) else "api_reference_ok"))
            )
    else:
        top_result = results[0] or {}
        page = fetched_pages[0] or {}
        result_text = " ".join([
            top_result.get("title") or "",
            top_result.get("snippet") or "",
            top_result.get("url") or "",
        ])
        page_text = " ".join([
            page.get("title") or "",
            page.get("text_content") or "",
            page.get("final_url") or "",
        ])
        overlap = max(
            _count_token_overlap(research_query, result_text),
            _count_token_overlap(research_query, page_text),
        )
        endpoint_hints = page.get("endpoint_hints") or []
        auth_hints = page.get("auth_hints") or []
        detected_base_url = page.get("detected_base_url") or ""
        openapi_servers = page.get("servers") or []
        openapi_endpoints = page.get("endpoints") or []
        openapi_auth = page.get("auth_schemes") or []
        openapi_has_structure = any(
            endpoint.get("parameters") or endpoint.get("request_body_schema_hint") or endpoint.get("response_schema_hint")
            for endpoint in openapi_endpoints if isinstance(endpoint, dict)
        )
        has_structured_api_reference = bool(openapi_servers or openapi_endpoints or openapi_auth)
        if len(_collapse_text(page.get("text_content") or "")) < 80 and not (endpoint_hints or auth_hints or detected_base_url or has_structured_api_reference):
            reason = "representative_fetch_content_too_short"
        elif has_structured_api_reference and not openapi_endpoints:
            reason = "openapi_reference_too_sparse"
        elif endpoint_hints or auth_hints or detected_base_url or has_structured_api_reference:
            passed = True
            reason = (
                "openapi_reference_structured"
                if has_structured_api_reference and openapi_has_structure
                else ("openapi_reference_relevant" if has_structured_api_reference else "api_reference_relevant")
            )
        elif overlap >= 2:
            passed = True
            reason = "search_result_relevant"
        elif overlap >= 1 and not _looks_low_trust_host(top_result.get("url") or page.get("final_url") or ""):
            passed = True
            reason = "search_result_weak_but_acceptable"
        else:
            reason = "search_result_irrelevant_or_weak"

    parsed_output = {
        "research_quality_passed": passed,
        "research_quality_reason": reason,
        "used_external_sources": used_external_sources[:5],
        "results_count": len(results),
        "fetched_pages_count": len(fetched_pages),
        "direct_fetch": direct_fetch,
    }
    record_agent_trace(
        task_id=task_id,
        flow_type="web_research",
        agent_name="Research_Quality_Gate",
        stage="quality_gate",
        model="rule_based",
        temperature=0.0,
        system_prompt="evaluate_research_quality",
        instruction=research_query,
        input_payload={
            "results": results[:3],
            "fetched_pages": fetched_pages[:1],
            "direct_fetch": direct_fetch,
        },
        raw_output=json.dumps(parsed_output, ensure_ascii=False),
        parsed_output=parsed_output,
        tool_name="evaluate_research_quality",
        tool_arguments={
            "research_query": research_query,
            "direct_fetch": direct_fetch,
        },
        finish_reason="completed",
        parse_status="success" if passed else "failed",
        parse_error=None if passed else reason,
        validation_result="success" if passed else "failed",
        fallback_used=False,
        fallback_reason=None,
        usage={},
    )
    return parsed_output


def synthesize_researched_build(user_request, research_context, device_context=None, task_id=None):
    result = call_agent_with_tools(
        RESEARCH_BUILD_SYSTEM,
        user_request,
        context={
            "task_id": task_id,
            "device_context": device_context or {},
            "research_context": research_context or {},
        },
        trace={"task_id": task_id, "flow_type": "research_build", "agent_name": "Research_Build_Synthesizer", "stage": "synthesize"},
        tools=RESEARCH_BUILD_TOOL_SCHEMAS,
        validator=validate_research_build_payload,
        parsed_output_builder=lambda tool_name, tool_arguments: tool_arguments,
        fallback_parser=legacy_agent_response_detailed,
    )
    decision = result.get("parsed_output")
    usage = result.get("usage")
    error = result.get("error")

    if not decision:
        return None, usage, error or "Research build synthesis agent failed"

    return decision, usage, None


def extract_ui_contract(project_path, task_id=None, previous_ui_contract=None, flow_type="generate"):
    snapshot = get_current_project_snapshot(project_path)
    if not snapshot:
        return None, {}, "ui_contract_snapshot_empty"

    result = call_agent_with_tools(
        UI_CONTRACT_EXTRACTOR_SYSTEM,
        "현재 Flutter 앱 코드에서 이후 재명세/재시도/복구 때 보존해야 할 UI 기준선을 추출하세요.",
        context={
            "task_id": task_id,
            "flow_type": flow_type,
            "previous_ui_contract": previous_ui_contract or {},
            "code_snapshot": snapshot,
        },
        trace={"task_id": task_id, "flow_type": flow_type, "agent_name": "UI_Contract_Extractor", "stage": "extract"},
        tools=UI_CONTRACT_TOOL_SCHEMAS,
        validator=validate_ui_contract_payload,
        parsed_output_builder=lambda tool_name, tool_arguments: normalize_ui_contract_payload(tool_arguments),
        fallback_parser=lambda system, user, context: normalized_legacy_agent_response(
            system, user, context, normalize_ui_contract_payload
        ),
    )
    contract = result.get("parsed_output")
    usage = result.get("usage")
    error = result.get("error")

    if not contract:
        return None, usage, error or "UI contract extraction failed"

    return contract, usage, None


def update_android_label(project_path, title):

    manifest = os.path.join(
        project_path,
        "android/app/src/main/AndroidManifest.xml"
    )

    if not os.path.exists(manifest):
        return

    with open(manifest) as f:
        c = f.read()

    c = re.sub(r'android:label="[^"]+"', f'android:label="{title}"', c)

    with open(manifest, "w") as f:
        f.write(c)


def update_package_name(project_path, new_pkg):
    gradle_path_groovy = os.path.join(project_path, "android/app/build.gradle")
    gradle_path_kts = os.path.join(project_path, "android/app/build.gradle.kts")

    gradle_path = gradle_path_kts if os.path.exists(gradle_path_kts) else gradle_path_groovy
    old_pkg = "kr.ac.kangwon.hai.baseproject" 

    if os.path.exists(gradle_path):
        with open(gradle_path, "r", encoding="utf-8") as f:
            content = f.read()
            
        match = re.search(r'namespace\s*=?\s*["\']([^"\']+)["\']', content)
        if match:
            old_pkg = match.group(1)
            
        content = re.sub(r'(namespace\s*=?\s*)["\'][^"\']+["\']', f'\\1"{new_pkg}"', content)
        content = re.sub(r'(applicationId\s*=?\s*)["\'][^"\']+["\']', f'\\1"{new_pkg}"', content)
        
        with open(gradle_path, "w", encoding="utf-8") as f:
            f.write(content)

    manifest_path = os.path.join(project_path, "android/app/src/main/AndroidManifest.xml")
    if os.path.exists(manifest_path):
        with open(manifest_path, "r", encoding="utf-8") as f:
            content = f.read()
            
        content = re.sub(r'(package\s*=\s*)["\'][^"\']+["\']', f'\\1"{new_pkg}"', content)
        if 'package=' not in content:
            content = content.replace('<manifest', f'<manifest package="{new_pkg}"')
            
        with open(manifest_path, "w", encoding="utf-8") as f:
            f.write(content)

    kotlin_root = os.path.join(project_path, "android/app/src/main/kotlin")
    old_dir = os.path.join(kotlin_root, old_pkg.replace(".", "/"))
    new_dir = os.path.join(kotlin_root, new_pkg.replace(".", "/"))
    old_file = os.path.join(old_dir, "MainActivity.kt")
    new_file = os.path.join(new_dir, "MainActivity.kt")

    if os.path.exists(old_file):
        with open(old_file, "r", encoding="utf-8") as f:
            kt_text = f.read()
            
        kt_text = re.sub(r'^package\s+[a-zA-Z0-9._]+', f'package {new_pkg}', kt_text, flags=re.MULTILINE)
        
        os.makedirs(new_dir, exist_ok=True)
        with open(new_file, "w", encoding="utf-8") as f:
            f.write(kt_text)
            
        if old_dir != new_dir:
            try:
                os.remove(old_file)
                curr = old_dir
                while curr != kotlin_root:
                    if not os.listdir(curr):
                        os.rmdir(curr)
                        curr = os.path.dirname(curr)
                    else:
                        break
            except Exception:
                pass


def update_pubspec_name(project_path, new_name):
    pubspec_path = os.path.join(project_path, "pubspec.yaml")
    if os.path.exists(pubspec_path):
        with open(pubspec_path, "r", encoding="utf-8") as f:
            content = f.read()
        clean_name = re.sub(r'[^a-z0-9_]', '_', new_name.lower())
        clean_name = re.sub(r'^_+|_+$', '', clean_name)

    if clean_name and clean_name[0].isdigit():
        clean_name = 'app_' + clean_name
        
    content = re.sub(r'^name:\s+.*', f'name: {clean_name}', content, flags=re.MULTILINE)
    
    with open(pubspec_path, "w", encoding="utf-8") as f:
        f.write(content)

def update_dart_imports(project_path, old_project_name, new_project_name):
    lib_path = os.path.join(project_path, "lib")
    if not os.path.exists(lib_path):
        return

    old_name = re.sub(r'[^a-z0-9_]', '_', old_project_name.lower())
    old_name = re.sub(r'^_+|_+$', '', old_name)

    new_name = re.sub(r'[^a-z0-9_]', '_', new_project_name.lower())
    new_name = re.sub(r'^_+|_+$', '', new_name)
    if new_name and new_name[0].isdigit():
        new_name = 'app_' + new_name
        
    target = f"package:{old_name}/"
    replacement = f"package:{new_name}/"

    for root, _, files in os.walk(lib_path):
        for file in files:
            if file.endswith(".dart"):
                fp = os.path.join(root, file)
                with open(fp, "r", encoding="utf-8") as f:
                    content = f.read()
                    
                if target in content:
                    content = content.replace(target, replacement)
                    with open(fp, "w", encoding="utf-8") as f:
                        f.write(content)

def save_project_files(project_path, files):

    if not files:
        return

    for f in files:

        path = f.get("path")
        content = f.get("content")

        if not path:
            continue

        if path.endswith(".dart") and not path.startswith("lib/"):
            path = os.path.join("lib", path)

        full = safe_join(project_path, path)

        os.makedirs(os.path.dirname(full), exist_ok=True)

        with open(full, "w", encoding="utf-8") as fp:
            fp.write(content)

def normalize_relevant_files(files):
    out = []
    seen = set()
    for item in files or []:
        if isinstance(item, dict):
            path = item.get("path")
        elif isinstance(item, str):
            path = item
        else:
            path = None

        if not path or not isinstance(path, str):
            continue

        normalized = path if path.startswith("lib/") or path.startswith("android/") or path == "pubspec.yaml" else os.path.join("lib", path)
        if normalized not in seen:
            seen.add(normalized)
            out.append(normalized)
    return out


def merge_unique_paths(paths):
    merged = []
    seen = set()
    for path in paths:
        if not path or path in seen:
            continue
        seen.add(path)
        merged.append(path)
    return merged


def build_refinement_relevant_files(files_to_modify):
    allowed = normalize_relevant_files(files_to_modify)
    base_files = ["lib/main.dart", "pubspec.yaml"]
    return merge_unique_paths(allowed + base_files)


def _compact_korean_text(text, max_len=180):
    cleaned = " ".join((text or "").split())
    if len(cleaned) <= max_len:
        return cleaned
    trimmed = cleaned[:max_len].rstrip()
    sentence_end = max(trimmed.rfind("."), trimmed.rfind("!"), trimmed.rfind("?"))
    if sentence_end >= max_len // 2:
        trimmed = trimmed[: sentence_end + 1].rstrip()
    else:
        for token in [" 입니다", " 합니다", "해요", "돼요", "됩니다", "입니다", "함", "요"]:
            idx = trimmed.rfind(token)
            if idx >= max_len // 2:
                trimmed = trimmed[: idx + len(token)].rstrip()
                break
        if trimmed and trimmed[-1] not in ".!?":
            trimmed += "."
    return trimmed


def build_refinement_user_summary(plan, files_to_modify):
    normalized_files = normalize_relevant_files(files_to_modify)
    file_count = len(normalized_files) if normalized_files else 1
    primary_file = normalized_files[0] if normalized_files else "lib/main.dart"
    analysis = " ".join((plan.get("analysis", "") or "").split())
    refinement_plan = " ".join((plan.get("refinement_plan", "") or "").split())

    summary = f"기존 구조를 유지하면서 필요한 부분만 수정하겠습니다. {primary_file}"
    if file_count > 1:
        summary += f" 외 {file_count - 1}개 파일을 검토해 반영할게요."
    else:
        summary += " 중심으로 반영할게요."

    if analysis:
        summary += f" 핵심 기준은 {analysis}"
    if refinement_plan:
        summary += f" 수정 방향은 {refinement_plan}"

    return summary.strip()


def _extract_refinement_keep_text(plan):
    analysis = _compact_korean_text(plan.get("analysis", ""), max_len=100)
    if analysis:
        return ["기존 화면 구조와 흐름을 유지합니다.", analysis]
    return ["기존 화면 구조와 흐름을 유지합니다.", "필요한 부분만 부분 수정합니다."]


def _extract_refinement_change_text(plan):
    refinement_plan = _compact_korean_text(plan.get("refinement_plan", ""), max_len=120)
    if refinement_plan:
        return [refinement_plan]
    return ["요청한 기능과 화면 요소만 국소적으로 수정합니다."]


def create_refinement_plan_preview(project_path, feedback, task_id=None, token_callback=None, reference_image_analysis=None, image_conflict_note="", ui_contract=None):
    if not project_path or not isinstance(project_path, str):
        return {
            "status": "failed",
            "error_log": "리파인에는 유효한 project_path가 필요합니다.",
            "project_path": project_path,
        }

    if not os.path.isdir(project_path):
        return {
            "status": "failed",
            "error_log": f"유효하지 않은 project_path입니다: {project_path}",
            "project_path": project_path,
        }

    metadata = {}
    meta_path = os.path.join(project_path, "vibe.json")
    if os.path.exists(meta_path):
        try:
            with open(meta_path, encoding="utf-8") as f:
                loaded = json.load(f)
            if isinstance(loaded, dict):
                metadata = loaded
        except Exception:
            metadata = {}

    pubspec_name = None
    pubspec_path = os.path.join(project_path, "pubspec.yaml")
    if os.path.exists(pubspec_path):
        try:
            with open(pubspec_path, "r", encoding="utf-8") as f:
                for line in f:
                    stripped = line.strip()
                    if stripped.startswith("name:"):
                        pubspec_name = stripped.split(":", 1)[1].strip().strip("'\"")
                        break
        except Exception:
            pubspec_name = None

    pkg = metadata.get("package_name") or pubspec_name or ""
    app_name = metadata.get("app_name") or os.path.basename(project_path).rsplit("_", 1)[0]
    planner_snapshot = get_current_project_snapshot(project_path)

    plan_result = call_agent_with_tools(
        REFINER_PLANNER_SYSTEM,
        feedback,
        context={
            "snapshot": planner_snapshot,
            "package_name": pkg,
            "app_name": app_name,
            "reference_image_analysis": reference_image_analysis or {},
            "image_conflict_note": image_conflict_note or "",
            "ui_contract": ui_contract or {},
        },
        trace={"task_id": task_id, "flow_type": "refine", "agent_name": "Refiner_Planner", "stage": "preview_plan"},
        tools=REFINER_PLANNER_TOOL_SCHEMAS,
        validator=validate_refinement_plan_payload,
        parsed_output_builder=lambda tool_name, tool_arguments: tool_arguments,
        fallback_parser=legacy_agent_response_detailed,
    )
    plan = plan_result.get("parsed_output")
    usage = plan_result.get("usage")
    if token_callback and task_id and usage:
        token_callback(task_id, "Refiner_Planner", usage)

    if not plan:
        return {
            "status": "failed",
            "error_log": "리파인 계획 수립에 실패했습니다.",
            "project_path": project_path,
            "package_name": pkg,
        }

    files_to_modify = normalize_relevant_files(plan.get("files_to_modify"))
    if not files_to_modify:
        files_to_modify = ["lib/main.dart"]

    return {
        "status": "success",
        "project_path": project_path,
        "package_name": pkg,
        "app_name": app_name,
        "plan": plan,
        "files_to_modify": files_to_modify,
        "assistant_message": build_refinement_user_summary(plan, files_to_modify),
        "summary": {
            "keep": _extract_refinement_keep_text(plan),
            "change": _extract_refinement_change_text(plan),
            "files_to_modify": files_to_modify,
        },
    }


def ensure_crash_handler_identity(project_path, task_id, package_name):
    main_dart_path = os.path.join(project_path, "lib", "main.dart")
    if not os.path.exists(main_dart_path):
        return

    with open(main_dart_path, "r", encoding="utf-8") as f:
        content = f.read()

    updated = re.sub(
        r'CrashHandler\.initialize\(\s*"[^"]*"\s*,\s*"[^"]*"\s*\);',
        f'CrashHandler.initialize("{task_id}", "{package_name}");',
        content,
    )

    if updated != content:
        with open(main_dart_path, "w", encoding="utf-8") as f:
            f.write(updated)


def filter_refinement_files(files, allowed_files, callback_log=None):
    allowed_set = set(normalize_relevant_files(allowed_files))
    if "lib/main.dart" not in allowed_set:
        allowed_set.add("lib/main.dart")

    filtered = []
    blocked = []

    for item in files or []:
        raw_path = item.get("path") if isinstance(item, dict) else None
        if not raw_path or not isinstance(raw_path, str):
            continue

        normalized = raw_path
        if raw_path.endswith(".dart") and not raw_path.startswith("lib/") and not raw_path.startswith("android/") and raw_path != "pubspec.yaml":
            normalized = os.path.join("lib", raw_path)

        if normalized in allowed_set:
            copied = dict(item)
            copied["path"] = normalized
            filtered.append(copied)
        else:
            blocked.append(normalized)

    if blocked and callback_log:
        callback_log(f"⛔ 리파인 범위 밖 파일 수정 차단: {json.dumps(blocked, ensure_ascii=False)}")

    return filtered, blocked


def get_recent_lib_files(project_path, limit=8):
    lib_root = os.path.join(project_path, "lib")
    if not os.path.isdir(lib_root):
        return []

    collected = []
    for root, _, files in os.walk(lib_root):
        for file in files:
            if file.endswith(".dart"):
                path = os.path.join(root, file)
                rel = os.path.relpath(path, project_path)
                try:
                    collected.append((os.path.getmtime(path), rel))
                except OSError:
                    continue

    collected.sort(reverse=True)
    return [rel for _, rel in collected[:limit]]


def get_current_project_snapshot(project_path, relevant_files=None):
    if not project_path or not isinstance(project_path, str) or not os.path.isdir(project_path):
        return ""

    files = [
        "lib/main.dart",
        "pubspec.yaml",
        "android/app/src/main/AndroidManifest.xml",
        "android/app/build.gradle"
    ]

    prioritized = normalize_relevant_files(relevant_files)
    fallback = get_recent_lib_files(project_path)
    ordered_files = []
    seen = set()

    for path in prioritized + files + fallback:
        if path not in seen:
            seen.add(path)
            ordered_files.append(path)

    out = []

    for f in ordered_files:

        try:

            full = safe_join(project_path, f)

            if os.path.exists(full):

                with open(full) as fp:
                    out.append(f"[{f}]\n{fp.read()}")

        except Exception:
            pass

    return "\n\n".join(out)


def prepare_debugger_context(project_path, error_log, stage, relevant_files=None, callback_log=None, ui_contract=None):
    normalized_files = []
    snapshot = ""

    try:
        normalized_files = normalize_relevant_files(relevant_files)
        snapshot = get_current_project_snapshot(project_path, normalized_files)
    except Exception as exc:
        if callback_log:
            callback_log(f"⚠️ Snapshot fallback used for debugger context: {exc}")
        normalized_files = []
        snapshot = get_current_project_snapshot(project_path, [])

    return {
        "stage": stage,
        "failure_type": classify_failure_type(error_log),
        "error_log": error_log,
        "code_snapshot": snapshot,
        "relevant_files": normalized_files,
        "ui_contract": ui_contract or {},
    }


def classify_failure_type(error_log):
    text = (error_log or "").lower()
    if any(token in text for token in ["expected ';'", "unexpected token", "expected ')'", "expected '}'", "unterminated"]):
        return "syntax"
    if "uri doesn't exist" in text or "target of uri doesn't exist" in text or "not found:" in text:
        return "import"
    if any(token in text for token in ["isn't a type", "can't be assigned", "the argument type", "the return type", "undefined class", "undefined method"]):
        return "type"
    if "renderflex overflow" in text or "overflowed by" in text:
        return "layout"
    if any(token in text for token in ["gradle", "manifest", "kotlin", "aapt", "execution failed for task"]):
        return "gradle"
    return "unknown"


def has_blocking_flutter_analyze_issue(output):
    text = output or ""
    for line in text.splitlines():
        stripped = line.strip().lower()
        if not stripped:
            continue
        if re.search(r"\berror\s+•", stripped):
            return True
        if stripped.startswith("error •"):
            return True
    return False


def run_flutter_pub_get(project_path: str) -> tuple[bool, str]:
    result = subprocess.run(
        ["flutter", "pub", "get"],
        cwd=project_path,
        capture_output=True,
        text=True,
        timeout=120,
    )
    ok = result.returncode == 0
    return ok, result.stdout + result.stderr


def run_flutter_analyze(project_path):
    proc = subprocess.Popen(
        ["flutter", "analyze"],
        cwd=project_path,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True
    )

    stdout, stderr = proc.communicate()
    output = stderr + stdout
    if proc.returncode != 0:
        if not has_blocking_flutter_analyze_issue(output):
            return True, output
        return False, output
    return True, output


# -------------------------------------------------
# BUILD
# -------------------------------------------------

def run_flutter_build(project_path):

    subprocess.run(
        ["./gradlew", "--stop"],
        cwd=os.path.join(project_path, "android")
    )

    subprocess.run(
        ["flutter", "clean"], 
        cwd=project_path
    )
    
    subprocess.run(
        ["flutter", "pub", "get"],
        cwd=project_path
    )

    env = os.environ.copy()
    env["ORG_GRADLE_PROJECT_org.gradle.daemon"] = "false"

    proc = subprocess.Popen(
        ["flutter", "build", "apk", "--debug"],
        cwd=project_path,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
        env=env
    )

    stdout, stderr = proc.communicate()

    apk = os.path.join(
        project_path,
        "build/app/outputs/flutter-apk/app-debug.apk"
    )

    if proc.returncode != 0:
        return False, stderr + stdout

    return True, apk


# -------------------------------------------------
# AGENTIC LOOP ENGINE
# -------------------------------------------------

def execute_tool(
    tool_name: str,
    args: dict,
    project_path: str,
    task_id: str,
    pkg: str = "",
    ensure_crash_fn=None,
    callback_log=None,
    token_callback=None,
    _depth: int = 0,
) -> dict:
    if tool_name == "search":
        query = args.get("query", "")
        try:
            headers = {"User-Agent": "Mozilla/5.0"}
            resp = _requests_lib.get(
                "https://html.duckduckgo.com/html/",
                params={"q": query},
                headers=headers,
                timeout=10,
            )
            soup = BeautifulSoup(resp.text, "html.parser")
            results = []
            for a in soup.select(".result__a")[:5]:
                href = a.get("href", "")
                match = re.search(r"uddg=([^&]+)", href)
                if match:
                    from urllib.parse import unquote
                    url = unquote(match.group(1))
                else:
                    url = href
                results.append({"title": a.get_text(strip=True), "url": url})
            return {"status": "ok", "results": results}
        except Exception as e:
            return {"status": "error", "message": str(e)}

    elif tool_name == "fetch_http":
        url = args.get("url", "")
        try:
            parsed = urlparse.urlparse(url)
            host = parsed.hostname or ""
            try:
                if ipaddress.ip_address(host).is_private:
                    return {"status": "error", "message": "private IP 접근 차단"}
            except ValueError:
                if host in ("localhost", "metadata.google.internal", "169.254.169.254"):
                    return {"status": "error", "message": "private URL 접근 차단"}
            headers = {"User-Agent": "Mozilla/5.0"}
            resp = _requests_lib.get(url, headers=headers, timeout=15)
            soup = BeautifulSoup(resp.text, "html.parser")
            for tag in soup(["script", "style"]):
                tag.decompose()
            text = soup.get_text(separator="\n", strip=True)
            if len(text) > 8000:
                text = text[:8000] + "\n...(truncated)"
            return {"status": "ok", "url": url, "content": text}
        except Exception as e:
            return {"status": "error", "message": str(e)}

    elif tool_name == "read_file":
        path = args.get("path", "").lstrip("/")
        full = safe_join(project_path, path)
        if not os.path.exists(full):
            return {"status": "error", "message": f"파일 없음: {path}"}
        with open(full, "r", encoding="utf-8") as f:
            content = f.read()
        return {"status": "ok", "path": path, "content": content}

    elif tool_name == "write_file":
        path = args.get("path", "")
        content = args.get("content", "")
        reason = args.get("reason", "")
        save_project_files(project_path, [{"path": path, "content": content, "change_type": "modify", "reason": reason}])
        if ensure_crash_fn:
            ensure_crash_fn()
        return {"status": "ok", "path": path}

    elif tool_name == "run_flutter_pub_get":
        pubspec_path = os.path.join(project_path, "pubspec.yaml")
        pubspec_mtime = os.path.getmtime(pubspec_path) if os.path.exists(pubspec_path) else 0
        lock_path = os.path.join(project_path, "pubspec.lock")
        lock_mtime = os.path.getmtime(lock_path) if os.path.exists(lock_path) else 0
        if lock_mtime > 0 and lock_mtime >= pubspec_mtime:
            return {"status": "ok", "output": "pubspec.yaml 변경 없음, pub get 스킵"}
        ok, output = run_flutter_pub_get(project_path)
        return {"status": "ok" if ok else "error", "output": output[:3000]}

    elif tool_name == "run_flutter_analyze":
        ok, output = run_flutter_analyze(project_path)
        return {"status": "pass" if ok else "fail", "output": output[:3000]}

    elif tool_name == "run_flutter_build":
        ok, res = run_flutter_build(project_path)
        return {"status": "pass" if ok else "fail", "output": res[:3000] if isinstance(res, str) else res}

    elif tool_name == "request_diagnosis":
        if _depth >= 1:
            return {"status": "error", "message": "재귀 깊이 초과: Debugger 내에서 request_diagnosis 호출 불가"}
        error_log = args.get("error_log", "")
        affected_files = args.get("affected_files", [])
        diag_request = (
            f"다음 Flutter 오류를 진단하세요.\n\n"
            f"error_log:\n{error_log}\n\n"
            f"affected_files: {json.dumps(affected_files, ensure_ascii=False)}"
        )
        diag_result = run_agentic_loop(
            task_id=task_id,
            user_request=diag_request,
            tools=DEBUGGER_TOOLS,
            system=DEBUGGER_SYSTEM_V2,
            project_path=project_path,
            callback_log=callback_log,
            token_callback=token_callback,
            max_turns=10,
            pkg=pkg,
            ensure_crash_fn=ensure_crash_fn,
            _depth=_depth + 1,
        )
        return {"status": "ok", "diagnosis": diag_result.get("result", diag_result)}

    elif tool_name == "finalize":
        # P0: finalize 전 코드 자동 검증 (도구 레벨 강제)
        warnings = []
        lib_path = os.path.join(project_path, "lib")
        if os.path.exists(lib_path):
            for root, _, fnames in os.walk(lib_path):
                for fname in fnames:
                    if not fname.endswith(".dart"):
                        continue
                    fpath = os.path.join(root, fname)
                    try:
                        with open(fpath, "r", encoding="utf-8") as f:
                            code = f.read()
                    except Exception:
                        continue

                    # 1. 플레이스홀더 체크
                    urls = re.findall(r"https?://[^\s'\"<>]+", code)
                    clean_urls = []
                    for u in urls:
                        u = u.rstrip("');},")
                        if any(p in u for p in ["YOUR_", "API_KEY", "SERVICE_KEY", "PLACEHOLDER"]):
                            warnings.append(f"{fname}: 플레이스홀더 URL: {u[:80]}")
                        elif "$" not in u and "{" not in u:
                            clean_urls.append(u)

                    # 2. [변수] 패턴 체크
                    bad_interp = re.findall(r"'[^']*\[(?:id|index|item|key|name)\][^']*'", code)
                    if bad_interp:
                        warnings.append(f"{fname}: 문자열 보간 오류: {bad_interp[:3]}")

                    # 3. placeholder/TODO 텍스트 체크
                    for pattern in ["placeholder", "TODO: 실제", "// Replace with"]:
                        if pattern.lower() in code.lower():
                            warnings.append(f"{fname}: 미구현 코드 발견: '{pattern}'")
                            break

                    # 4. URL 실제 fetch 검증 (최대 3개)
                    for u in clean_urls[:3]:
                        try:
                            parsed = urlparse.urlparse(u)
                            host = parsed.hostname or ""
                            try:
                                if ipaddress.ip_address(host).is_private:
                                    continue
                            except ValueError:
                                if host in ("localhost", "metadata.google.internal"):
                                    continue
                            resp = _requests_lib.get(u, headers={"User-Agent": "Mozilla/5.0"}, timeout=10)
                            if resp.status_code >= 400:
                                warnings.append(f"{fname}: URL {resp.status_code} 에러: {u[:80]}")
                            else:
                                content_type = resp.headers.get("content-type", "")
                                is_html = "text/html" in content_type
                                uses_json_decode = "json.decode" in code or "jsonDecode" in code
                                if is_html and uses_json_decode:
                                    warnings.append(f"{fname}: HTML 응답인데 json.decode 사용 중: {u[:60]}")
                        except Exception:
                            pass

        if warnings:
            return {
                "status": "error",
                "message": "finalize 전 코드 검증 실패. 아래 문제를 수정하고 다시 finalize하세요.",
                "warnings": warnings,
            }
        return {"status": "done", **args}

    elif tool_name in ("finalize_plan", "report_diagnosis"):
        return {"status": "done", **args}

    else:
        return {"status": "error", "message": f"unknown tool: {tool_name}"}


def _truncate_old_messages(messages: list, max_content_len: int = 2000):
    """오래된 tool result의 content를 절단해서 토큰 폭발 방지."""
    if len(messages) < 10:
        return
    for msg in messages[:-6]:
        if msg.get("role") == "tool" and isinstance(msg.get("content"), str):
            if len(msg["content"]) > max_content_len:
                msg["content"] = msg["content"][:max_content_len] + "\n...(truncated)"


def run_agentic_loop(
    task_id: str,
    user_request: str,
    tools: list,
    system: str,
    project_path: str,
    callback_log=None,
    token_callback=None,
    max_turns: int = 50,
    pkg: str = "",
    ensure_crash_fn=None,
    _depth: int = 0,
) -> dict:
    if _depth > 1:
        return {"status": "error", "message": "최대 재귀 깊이 초과 (depth > 1)"}

    messages = [{"role": "user", "content": user_request}]
    analyze_fail_count = 0
    diagnosis_count = 0

    for turn in range(max_turns):
        _truncate_old_messages(messages)

        try:
            response = client.chat.completions.create(
                model=MODEL_NAME,
                messages=[{"role": "system", "content": system}] + messages,
                tools=tools,
                tool_choice="auto",
                temperature=MODEL_TEMPERATURE,
            )
        except Exception as e:
            return {"status": "error", "message": str(e)}

        usage = extract_usage_dict(response)
        if token_callback:
            token_callback(task_id, f"AgenticLoop_turn{turn + 1}", usage)

        assistant_msg = response.choices[0].message
        tool_calls = getattr(assistant_msg, "tool_calls", None) or []

        msg = {"role": "assistant", "content": assistant_msg.content or ""}
        if tool_calls:
            msg["tool_calls"] = [
                {
                    "id": tc.id,
                    "type": "function",
                    "function": {"name": tc.function.name, "arguments": tc.function.arguments},
                }
                for tc in tool_calls
            ]
        messages.append(msg)

        if not tool_calls:
            return {"status": "success", "message": assistant_msg.content}

        for tc in tool_calls:
            tool_name = tc.function.name
            try:
                args = json.loads(tc.function.arguments)
            except Exception:
                args = {}

            if callback_log:
                callback_log(f"  🔧 {tool_name}")

            # P1-1: analyze 실패 카운터
            if tool_name == "run_flutter_analyze":
                result = execute_tool(
                    tool_name, args, project_path, task_id,
                    pkg=pkg, ensure_crash_fn=ensure_crash_fn,
                    callback_log=callback_log, token_callback=token_callback,
                    _depth=_depth,
                )
                if result.get("status") == "fail":
                    analyze_fail_count += 1
                    if analyze_fail_count >= 3:
                        result["_force_hint"] = "analyze가 3회 연속 실패했습니다. 반드시 request_diagnosis를 호출하세요."
                else:
                    analyze_fail_count = 0
            elif tool_name == "request_diagnosis":
                diagnosis_count += 1
                if diagnosis_count > 3:
                    result = {"status": "error", "message": "request_diagnosis 3회 초과. 문제되는 기능을 빼고 빌드하거나, 현재 상태로 finalize하세요."}
                else:
                    result = execute_tool(
                        tool_name, args, project_path, task_id,
                        pkg=pkg, ensure_crash_fn=ensure_crash_fn,
                        callback_log=callback_log, token_callback=token_callback,
                        _depth=_depth,
                    )
            else:
                result = execute_tool(
                    tool_name, args, project_path, task_id,
                    pkg=pkg, ensure_crash_fn=ensure_crash_fn,
                    callback_log=callback_log, token_callback=token_callback,
                    _depth=_depth,
                )

            messages.append({
                "role": "tool",
                "tool_call_id": tc.id,
                "content": json.dumps(result, ensure_ascii=False),
            })

            if tool_name in ("finalize", "finalize_plan", "report_diagnosis"):
                return {"status": "success", "result": result, "tool": tool_name}

    return {"status": "max_turns_exceeded"}


# -------------------------------------------------
# MAIN ENGINE
# -------------------------------------------------

def build_specialized_generation_plan(
    task_id,
    user_request,
    device_context=None,
    callback_log=None,
    token_callback=None,
    reference_image_analysis=None,
    image_conflict_note="",
    ui_contract=None,
):
    build_context = extract_build_request_context(user_request)
    build_context, build_context_error = validate_external_data_build_context(build_context)
    if build_context_error:
        return {"status": "failed", "error_log": build_context_error}
    build_spec = build_context.get("build_spec") or {}
    planning_prompt = build_context.get("summary") or build_context.get("user_request") or user_request

    product_result = call_agent_with_tools(
        PRODUCT_PLANNER_SYSTEM,
        planning_prompt,
        context={
            "device_context": device_context,
            "task_id": task_id,
            "build_summary": build_context.get("summary") or "",
            "build_spec": build_spec,
            "research_query": build_context.get("research_query") or "",
            "research_reason": build_context.get("research_reason") or "",
            "research_results": build_context.get("research_results") or [],
            "reference_image_analysis": reference_image_analysis or {},
            "image_conflict_note": image_conflict_note or "",
            "ui_contract": ui_contract or {},
        },
        trace={"task_id": task_id, "flow_type": "generate", "agent_name": "Product_Planner", "stage": "product_plan"},
        tools=PRODUCT_PLAN_TOOL_SCHEMAS,
        validator=validate_product_plan_payload,
        parsed_output_builder=lambda tool_name, tool_arguments: tool_arguments,
        fallback_parser=legacy_agent_response_detailed,
    )
    product_plan = product_result.get("parsed_output")
    usage = product_result.get("usage")
    if token_callback and product_plan and usage:
        token_callback(task_id, "Product_Planner", usage)

    if not product_plan:
        return {"status": "failed", "error_log": "제품 계획 단계에 실패했습니다."}

    if callback_log:
        callback_log("🎨 UI 레이아웃 설계 중")

    ui_result = call_agent_with_tools(
        UI_LAYOUT_DESIGNER_SYSTEM,
        "제품 계획을 바탕으로 화면별 UI 레이아웃과 스타일 기준을 설계하세요.",
        context={
            "device_context": device_context,
            "task_id": task_id,
            "product_plan": product_plan,
            "build_summary": build_context.get("summary") or "",
            "build_spec": build_spec,
            "reference_image_analysis": reference_image_analysis or {},
            "image_conflict_note": image_conflict_note or "",
            "ui_contract": ui_contract or {},
        },
        trace={"task_id": task_id, "flow_type": "generate", "agent_name": "UI_Layout_Designer", "stage": "layout_plan"},
        tools=UI_LAYOUT_PLAN_TOOL_SCHEMAS,
        validator=validate_ui_layout_plan_payload,
        parsed_output_builder=lambda tool_name, tool_arguments: normalize_ui_layout_plan_payload(tool_arguments),
        fallback_parser=lambda system, user, context: normalized_legacy_agent_response(
            system, user, context, normalize_ui_layout_plan_payload
        ),
    )
    ui_layout_plan = ui_result.get("parsed_output")
    usage = ui_result.get("usage")
    if token_callback and ui_layout_plan and usage:
        token_callback(task_id, "UI_Layout_Designer", usage)

    if not ui_layout_plan:
        return {"status": "failed", "error_log": "UI 레이아웃 설계 단계에 실패했습니다."}

    if callback_log:
        callback_log("🗂️ 데이터 모델 설계 중")

    data_result = call_agent_with_tools(
        DATA_MODEL_DESIGNER_SYSTEM,
        "제품 계획, 외부 데이터 근거, 샘플 레코드를 바탕으로 앱 데이터 모델과 소스 매핑을 설계하세요.",
        context={
            "device_context": device_context,
            "task_id": task_id,
            "product_plan": product_plan,
            "ui_layout_plan": ui_layout_plan,
            "build_summary": build_context.get("summary") or "",
            "build_spec": build_spec,
            "research_query": build_context.get("research_query") or "",
            "research_reason": build_context.get("research_reason") or "",
            "research_results": build_context.get("research_results") or [],
            "web_data_contract": build_spec.get("web_data_contract") if isinstance(build_spec, dict) else {},
            "selected_web_data_analysis": (build_spec.get("web_data_contract") or {}) if isinstance(build_spec, dict) else {},
            "external_sources": build_spec.get("external_sources") if isinstance(build_spec, dict) else [],
            "source_url_candidates": build_spec.get("source_url_candidates") if isinstance(build_spec, dict) else [],
            "ui_contract": ui_contract or {},
        },
        trace={"task_id": task_id, "flow_type": "generate", "agent_name": "Data_Model_Designer", "stage": "data_model_plan"},
        tools=DATA_MODEL_PLAN_TOOL_SCHEMAS,
        validator=validate_data_model_plan_payload,
        parsed_output_builder=lambda tool_name, tool_arguments: normalize_data_model_plan_payload(tool_arguments),
        fallback_parser=lambda system, user, context: normalized_legacy_agent_response(
            system, user, context, normalize_data_model_plan_payload
        ),
    )
    data_model_plan = data_result.get("parsed_output")
    usage = data_result.get("usage")
    if token_callback and data_model_plan and usage:
        token_callback(task_id, "Data_Model_Designer", usage)

    if not data_model_plan:
        return {"status": "failed", "error_log": "데이터 모델 설계 단계에 실패했습니다."}

    if callback_log:
        callback_log("🧩 화면 기능 설계 중")

    logic_result = call_agent_with_tools(
        FEATURE_LOGIC_DESIGNER_SYSTEM,
        "제품 계획, UI 레이아웃 계획, 데이터 모델 계획을 바탕으로 화면별 기능, 상태, 이벤트, 데이터 로직을 설계하세요.",
        context={
            "device_context": device_context,
            "task_id": task_id,
            "product_plan": product_plan,
            "ui_layout_plan": ui_layout_plan,
            "data_model_plan": data_model_plan,
            "build_summary": build_context.get("summary") or "",
            "build_spec": build_spec,
            "ui_contract": ui_contract or {},
        },
        trace={"task_id": task_id, "flow_type": "generate", "agent_name": "Feature_Logic_Designer", "stage": "logic_plan"},
        tools=FEATURE_LOGIC_PLAN_TOOL_SCHEMAS,
        validator=validate_feature_logic_plan_payload,
        parsed_output_builder=lambda tool_name, tool_arguments: normalize_feature_logic_plan_payload(tool_arguments),
        fallback_parser=lambda system, user, context: normalized_legacy_agent_response(
            system, user, context, normalize_feature_logic_plan_payload
        ),
    )
    feature_logic_plan = logic_result.get("parsed_output")
    usage = logic_result.get("usage")
    if token_callback and feature_logic_plan and usage:
        token_callback(task_id, "Feature_Logic_Designer", usage)

    if not feature_logic_plan:
        return {"status": "failed", "error_log": "기능 로직 설계 단계에 실패했습니다."}

    if callback_log:
        callback_log("🔗 UI와 기능 통합 계획 중")

    plan_result = call_agent_with_tools(
        INTEGRATION_PLANNER_SYSTEM,
        "제품 계획, UI 레이아웃 계획, 데이터 모델 계획, 기능 로직 계획을 통합해 Engineer가 구현할 최종 앱 블루프린트를 생성하세요.",
        context={
            "device_context": device_context,
            "task_id": task_id,
            "product_plan": product_plan,
            "ui_layout_plan": ui_layout_plan,
            "data_model_plan": data_model_plan,
            "feature_logic_plan": feature_logic_plan,
            "build_summary": build_context.get("summary") or "",
            "build_spec": build_spec,
            "research_query": build_context.get("research_query") or "",
            "research_reason": build_context.get("research_reason") or "",
            "reference_image_analysis": reference_image_analysis or {},
            "image_conflict_note": image_conflict_note or "",
            "ui_contract": ui_contract or {},
        },
        trace={"task_id": task_id, "flow_type": "generate", "agent_name": "Integration_Planner", "stage": "integration_plan"},
        tools=PLANNER_TOOL_SCHEMAS,
        validator=validate_plan_payload,
        parsed_output_builder=lambda tool_name, tool_arguments: tool_arguments,
        fallback_parser=legacy_agent_response_detailed,
    )
    plan = plan_result.get("parsed_output")
    usage = plan_result.get("usage")
    if token_callback and plan and usage:
        token_callback(task_id, "Integration_Planner", usage)

    if not plan:
        return {"status": "failed", "error_log": "통합 계획 단계에 실패했습니다."}
    plan["product_plan"] = product_plan
    plan["ui_layout_plan"] = ui_layout_plan
    plan["data_model_plan"] = data_model_plan
    plan["feature_logic_plan"] = feature_logic_plan
    plan["build_context"] = build_context
    plan["ui_contract"] = ui_contract or {}
    plan["status"] = "success"
    return plan


def _looks_like_external_data_app(build_spec):
    if not isinstance(build_spec, dict):
        return False
    requirements = build_spec.get("verification_requirements") or {}
    return bool(
        requirements.get("requires_external_data_verification")
        or (build_spec.get("data_source_type") or "").strip().lower() in {"web_scrape", "api", "manual"}
        or bool(build_spec.get("source_url_candidates"))
        or bool(build_spec.get("external_sources"))
    )


def _has_actionable_web_data_contract(build_spec):
    if not isinstance(build_spec, dict):
        return False
    contract = build_spec.get("web_data_contract")
    if not isinstance(contract, dict):
        return False
    parser_strategy = str(contract.get("parser_strategy") or "").strip().lower()
    candidate_urls = _normalize_string_list(contract.get("candidate_urls") or [], max_items=8)
    primary_url = str(contract.get("primary_url") or "").strip()
    return parser_strategy not in {"", "none", "local"} and bool(primary_url or candidate_urls)


def validate_external_data_build_context(build_context):
    context = dict(build_context) if isinstance(build_context, dict) else {}
    build_spec = normalize_runtime_build_spec(context.get("build_spec"))
    context["build_spec"] = build_spec
    if not _looks_like_external_data_app(build_spec):
        return context, None

    if not build_spec.get("source_url_candidates"):
        return context, "외부 데이터 앱으로 분류되었지만 build_spec에 공식 소스 URL이 없습니다."

    if (build_spec.get("data_source_type") or "").strip().lower() == "web_scrape" and not _has_actionable_web_data_contract(build_spec):
        return context, "웹 파싱 앱으로 분류되었지만 build_spec.web_data_contract가 비어 있거나 실행 가능한 파서 전략이 없습니다."

    return context, None


def _extract_date_tokens(text):
    candidates = []
    seen = set()
    patterns = [
        r"20\d{2}[./-]\d{1,2}[./-]\d{1,2}",
        r"\d{1,2}[./-]\d{1,2}\([^)]+\)",
        r"\d{1,2}[./-]\d{1,2}(?:\([^)]+\))?",
        r"\d{1,2}월\s*\d{1,2}일",
    ]
    for pattern in patterns:
        for match in re.findall(pattern, text or "", re.I):
            token = " ".join(str(match).split())
            if token and token not in seen:
                seen.add(token)
                candidates.append(token)
            if len(candidates) >= 8:
                return candidates
    return candidates


def _contains_migration_notice(text):
    lowered = (text or "").lower()
    markers = [
        "이전",
        "새 홈페이지",
        "새 사이트",
        "공지",
        "안내",
        "moved",
        "migrat",
        "redirect",
        "relocat",
        "new site",
        "old site",
    ]
    return any(marker in lowered for marker in markers)


def _detect_cache_persistence_signals(code_snapshot):
    lowered = (code_snapshot or "").lower()
    markers = [
        "sharedpreferences",
        "shared_preferences",
        "hive",
        "sqflite",
        "hydrated",
        "path_provider",
        "file(",
        "writetofile",
        "readfromfile",
        "jsonencode(",
        "jsondecode(",
    ]
    hits = [marker for marker in markers if marker in lowered]
    return {
        "present": bool(hits),
        "markers": hits[:8],
    }


def _detect_mock_success_signals(code_snapshot):
    lowered = (code_snapshot or "").lower()
    markers = [
        "sampleitems",
        "mock",
        "placeholder",
        "dummy",
        "demodata",
        "future<void>.delayed",
        "sourcemetadata: '공식 웹페이지 소스 미확정",
        'sourcemetadata: "공식 웹페이지 소스 미확정',
        "로컬 파서 구조 사용",
    ]
    hits = [marker for marker in markers if marker in lowered]
    return {
        "present": bool(hits),
        "markers": hits[:8],
    }


def _detect_ephemeral_cache_patterns(code_snapshot):
    lowered = (code_snapshot or "").lower()
    markers = [
        "directory.systemtemp.createtempsync",
        "directory.systemtemp",
        "systemtemp",
        "createtempsync(",
    ]
    hits = [marker for marker in markers if marker in lowered]
    return {
        "present": bool(hits),
        "markers": hits[:8],
    }


def _load_project_build_spec(project_path):
    if not project_path:
        return {}
    vibe_path = os.path.join(project_path, "vibe.json")
    try:
        with open(vibe_path, "r", encoding="utf-8") as f:
            payload = json.load(f)
        build_spec = payload.get("build_spec")
        return build_spec if isinstance(build_spec, dict) else {}
    except Exception:
        return {}


def _extract_http_urls_from_text(text, max_items=12):
    urls = []
    seen = set()
    for match in re.finditer(r"https?://[^\s'\"<>)\]]+", text or ""):
        url = match.group(0).rstrip(".,;")
        if url and url not in seen:
            seen.add(url)
            urls.append(url)
        if len(urls) >= max_items:
            break
    return urls


def _normalize_url_identity(url):
    parsed = urlparse.urlparse(url or "")
    host = (parsed.netloc or "").lower()
    if host.startswith("www."):
        host = host[4:]
    path = (parsed.path or "/").rstrip("/") or "/"
    query = parsed.query or ""
    return f"{host}{path}?{query}" if query else f"{host}{path}"


def _same_source_family(left, right):
    left_parsed = urlparse.urlparse(left or "")
    right_parsed = urlparse.urlparse(right or "")
    left_host = (left_parsed.netloc or "").lower().removeprefix("www.")
    right_host = (right_parsed.netloc or "").lower().removeprefix("www.")
    if not left_host or left_host != right_host:
        return False
    left_path = (left_parsed.path or "").strip("/")
    right_path = (right_parsed.path or "").strip("/")
    if not left_path or not right_path:
        return True
    return left_path.split("/")[:3] == right_path.split("/")[:3]


def _deterministic_external_data_checks(build_spec, code_snapshot, task_id=None):
    normalized_build_spec = normalize_runtime_build_spec(build_spec)
    declared_urls = _normalize_string_list(normalized_build_spec.get("source_url_candidates") or [], max_items=5)
    code_urls = _extract_http_urls_from_text(code_snapshot)
    cache_signals = _detect_cache_persistence_signals(code_snapshot)
    mock_signals = _detect_mock_success_signals(code_snapshot)
    ephemeral_cache = _detect_ephemeral_cache_patterns(code_snapshot)
    code_url_probes = [
        {"url": url, **test_http_request("GET", url, allowed_source={"url": url}, task_id=task_id)}
        for url in code_urls[:5]
    ]

    issues = []
    if declared_urls:
        declared_identities = {_normalize_url_identity(url) for url in declared_urls}
        code_identities = {_normalize_url_identity(url) for url in code_urls}
        has_exact_declared_source = bool(declared_identities.intersection(code_identities))
        has_same_source_family = any(
            _same_source_family(code_url, declared_url)
            for code_url in code_urls
            for declared_url in declared_urls
        )
        if not has_exact_declared_source and not has_same_source_family:
            issues.append("구현 코드에서 build_spec의 공식 외부 데이터 출처 URL을 사용하지 않습니다.")
    elif _looks_like_external_data_app(normalized_build_spec):
        issues.append("외부 데이터 앱인데 구현 코드와 build_spec 어디에서도 공식 소스 URL을 확인하지 못했습니다.")

    if _looks_like_external_data_app(normalized_build_spec) and not code_urls:
        issues.append("외부 데이터 앱인데 구현 코드에서 실제 네트워크 출처 URL을 찾지 못했습니다.")

    if code_urls and not any(bool(item.get("success")) for item in code_url_probes):
        issues.append("구현 코드에 포함된 외부 데이터 URL이 모두 HTTP probe에 실패했습니다.")

    if _looks_like_external_data_app(normalized_build_spec) and mock_signals.get("present"):
        issues.append("외부 데이터 핵심 기능의 성공 경로에 샘플/목업 데이터로 보이는 구현 흔적이 있습니다.")

    requirements = normalized_build_spec.get("verification_requirements") or {}
    if requirements.get("cache_persistence_required"):
        if not cache_signals.get("present"):
            issues.append("외부 데이터 앱인데 캐시 영속 저장 구현 흔적을 찾지 못했습니다.")
        if ephemeral_cache.get("present"):
            issues.append("캐시 저장이 임시 디렉터리에 의존해 앱 재실행 후 유지되지 않을 가능성이 큽니다.")

    return {
        "status": "fail" if issues else "pass",
        "issues": issues,
        "code_urls": code_urls,
        "code_url_probes": code_url_probes,
        "mock_signals": mock_signals,
        "cache_signals": cache_signals,
        "ephemeral_cache": ephemeral_cache,
    }


def _deterministic_parser_contract_checks(build_spec, fetched_sources):
    normalized_build_spec = normalize_runtime_build_spec(build_spec)
    contract = normalized_build_spec.get("web_data_contract")
    if not isinstance(contract, dict):
        return {"status": "not_applicable", "issues": [], "warnings": [], "sample_records": []}

    try:
        required_samples = max(1, int(contract.get("minimum_sample_records") or 1))
    except (TypeError, ValueError):
        required_samples = 1
    accepted_strategy_values = contract.get("accepted_parser_strategies")
    if not isinstance(accepted_strategy_values, list):
        accepted_strategy_values = [accepted_strategy_values] if accepted_strategy_values else []
    accepted_strategies = set()
    for value in [contract.get("parser_strategy"), *accepted_strategy_values]:
        if isinstance(value, str) and value.strip():
            accepted_strategies.add(value.strip())

    source_kind = (contract.get("source_kind") or "").strip()
    compatible_strategy_map = {
        "static_html_table": {"html_table", "text_pattern"},
        "static_html_text": {"text_pattern"},
        "json_endpoint_candidate": {"discover_and_fetch_candidate_url"},
        "iframe": {"follow_iframe_then_parse", "discover_and_fetch_candidate_url"},
        "dynamic_js": {"discover_and_fetch_candidate_url", "follow_iframe_then_parse"},
    }
    accepted_strategies.update(compatible_strategy_map.get(source_kind, set()))

    candidate_contract_urls = contract.get("candidate_urls")
    if not isinstance(candidate_contract_urls, list):
        candidate_contract_urls = [candidate_contract_urls] if candidate_contract_urls else []
    contract_exact_urls = set()
    contract_path_urls = set()
    for value in [contract.get("primary_url"), *candidate_contract_urls]:
        if isinstance(value, str) and value.strip():
            normalized_value = value.strip()
            parsed = urlparse.urlparse(normalized_value)
            exact_key = (
                (parsed.scheme or "").lower(),
                (parsed.netloc or "").lower(),
                parsed.path.rstrip("/"),
                tuple(sorted(urlparse.parse_qsl(parsed.query, keep_blank_values=True))),
            )
            path_key = (
                (parsed.scheme or "").lower(),
                (parsed.netloc or "").lower(),
                parsed.path.rstrip("/"),
            )
            contract_exact_urls.add(exact_key)
            if not parsed.query:
                contract_path_urls.add(path_key)

    def _matches_contract_url(source_url):
        if not contract_exact_urls and not contract_path_urls:
            return True
        if not isinstance(source_url, str) or not source_url.strip():
            return False
        parsed = urlparse.urlparse(source_url.strip())
        exact_key = (
            (parsed.scheme or "").lower(),
            (parsed.netloc or "").lower(),
            parsed.path.rstrip("/"),
            tuple(sorted(urlparse.parse_qsl(parsed.query, keep_blank_values=True))),
        )
        path_key = (
            (parsed.scheme or "").lower(),
            (parsed.netloc or "").lower(),
            parsed.path.rstrip("/"),
        )
        return exact_key in contract_exact_urls or path_key in contract_path_urls

    collected_samples = []
    matched_samples = []
    issues = []
    warnings = []
    mismatched_strategies = set()
    for source in fetched_sources or []:
        analysis = source.get("web_data_analysis") if isinstance(source, dict) else {}
        if not isinstance(analysis, dict):
            continue
        samples = analysis.get("sample_records") if isinstance(analysis.get("sample_records"), list) else []
        collected_samples.extend(samples)
        source_url = source.get("final_url") or source.get("url") or analysis.get("final_url") or analysis.get("url")
        if _matches_contract_url(source_url):
            matched_samples.extend(samples)
            observed_strategy = (analysis.get("parser_contract", {}).get("parser_strategy") or "").strip()
            if observed_strategy and accepted_strategies and observed_strategy not in accepted_strategies:
                mismatched_strategies.add(observed_strategy)

    if len(matched_samples) < required_samples:
        issues.append(
            f"web_data_contract 기준 최소 샘플 {required_samples}건을 검증 시점에 추출하지 못했습니다."
        )
    elif mismatched_strategies:
        warnings.append(
            "검증 시점의 파서 전략 표기가 빌드 명세와 다르지만, 같은 출처에서 최소 샘플 추출은 확인되었습니다."
        )

    return {
        "status": "fail" if issues else ("warn" if warnings else "pass"),
        "issues": issues,
        "warnings": warnings,
        "sample_records": matched_samples[:5] or collected_samples[:5],
    }


def collect_external_data_verification_inputs(build_spec, task_id=None):
    normalized_build_spec = normalize_runtime_build_spec(build_spec)
    urls = _normalize_string_list(normalized_build_spec.get("source_url_candidates") or [], max_items=3)
    probe_results = []
    fetched_sources = []

    for url in urls:
        probe = test_http_request("GET", url, allowed_source={"url": url}, task_id=task_id)
        probe_results.append({"url": url, **probe})
        fetched = fetch_webpage(url, task_id=task_id, max_chars=16000)
        if fetched.get("status") == "success":
            text_content = fetched.get("text_content") or ""
            web_data_analysis = analyze_web_data_source(
                url,
                user_goal=json.dumps(normalized_build_spec, ensure_ascii=False)[:1000],
                page_result=fetched,
                task_id=task_id,
            )
            fetched_sources.append(
                {
                    "url": url,
                    "final_url": fetched.get("final_url") or url,
                    "title": fetched.get("title") or "",
                    "text_content": text_content,
                    "web_structure": fetched.get("web_structure") or {},
                    "web_data_analysis": web_data_analysis,
                    "signals": {
                        "contains_migration_notice": _contains_migration_notice(text_content),
                        "date_tokens": _extract_date_tokens(text_content),
                    },
                }
            )
        else:
            fetched_sources.append(
                {
                    "url": url,
                    "final_url": url,
                    "title": "",
                    "text_content": "",
                    "signals": {
                        "contains_migration_notice": False,
                        "date_tokens": [],
                    },
                    "error": fetched.get("error") or "fetch_failed",
                }
            )

    has_probe_success = any(bool(item.get("success")) for item in probe_results)
    has_migration_notice = any(
        bool((item.get("signals") or {}).get("contains_migration_notice"))
        for item in fetched_sources
    )
    total_date_tokens = []
    for item in fetched_sources:
        total_date_tokens.extend((item.get("signals") or {}).get("date_tokens") or [])
    parser_contract_checks = _deterministic_parser_contract_checks(normalized_build_spec, fetched_sources)

    return {
        "build_spec": normalized_build_spec,
        "source_probe_results": probe_results,
        "fetched_sources": fetched_sources,
        "static_signals": {
            "source_candidate_count": len(urls),
            "has_successful_source_probe": has_probe_success,
            "has_migration_notice": has_migration_notice,
            "sample_date_tokens": _normalize_string_list(total_date_tokens, max_items=8),
            "parser_contract_checks": parser_contract_checks,
        },
    }


def verify_external_data_dependencies(project_path, build_spec, task_id=None, token_callback=None):
    normalized_build_spec = normalize_runtime_build_spec(build_spec)
    if not _looks_like_external_data_app(normalized_build_spec):
        return {
            "status": "not_applicable",
            "summary": "외부 데이터 핵심 검증이 필요한 앱으로 분류되지 않았습니다.",
            "issues": [],
            "checks": {
                "source_probe": "not_applicable",
                "migration_notice": "not_applicable",
                "parser_smoke": "not_applicable",
                "minimum_sample_data": "not_applicable",
                "cache_persistence": "not_applicable",
            },
            "evidence": {
                "build_spec": normalized_build_spec,
                "source_probe_results": [],
                "fetched_sources": [],
                "static_signals": {},
            },
        }

    evidence = collect_external_data_verification_inputs(normalized_build_spec, task_id=task_id)
    code_snapshot = get_current_project_snapshot(project_path)
    evidence["code_snapshot"] = code_snapshot
    evidence["static_signals"]["cache_signals"] = _detect_cache_persistence_signals(code_snapshot)
    deterministic_checks = _deterministic_external_data_checks(
        normalized_build_spec,
        code_snapshot,
        task_id=task_id,
    )
    evidence["static_signals"]["implementation_source_checks"] = deterministic_checks

    result = call_agent_with_tools(
        EXTERNAL_DATA_VERIFIER_SYSTEM,
        "외부 데이터 의존 기능이 실제로 동작할 가능성이 충분한지 검증하세요.",
        context=evidence,
        trace={"task_id": task_id, "flow_type": "generate", "agent_name": "External_Data_Verifier", "stage": "external_data_review"},
        tools=EXTERNAL_DATA_VERIFIER_TOOL_SCHEMAS,
        validator=validate_external_data_verification_payload,
        parsed_output_builder=lambda tool_name, tool_arguments: tool_arguments,
        fallback_parser=legacy_agent_response_detailed,
    )
    verification = result.get("parsed_output")
    usage = result.get("usage")
    if token_callback and verification and usage:
        token_callback(task_id, "External_Data_Verifier", usage)

    if not verification:
        verification = {
            "status": "fail",
            "summary": "외부 데이터 검증 결과를 해석하지 못했습니다.",
            "issues": ["검증 응답 파싱 실패"],
            "checks": {
                "source_probe": "warn",
                "migration_notice": "warn",
                "parser_smoke": "fail",
                "minimum_sample_data": "warn",
                "cache_persistence": "warn",
            },
        }

    if deterministic_checks.get("status") == "fail":
        deterministic_issues = deterministic_checks.get("issues") or []
        existing_issues = verification.get("issues") if isinstance(verification.get("issues"), list) else []
        verification["status"] = "fail"
        verification["issues"] = existing_issues + [
            issue for issue in deterministic_issues if issue not in existing_issues
        ]
        summary = (verification.get("summary") or "").strip()
        deterministic_summary = "구현 코드의 외부 데이터 출처가 검증 요구사항과 일치하지 않습니다."
        verification["summary"] = f"{summary} {deterministic_summary}".strip() if summary else deterministic_summary
        checks = verification.get("checks") if isinstance(verification.get("checks"), dict) else {}
        checks["source_probe"] = "fail"
        verification["checks"] = checks

    parser_contract_checks = (evidence.get("static_signals") or {}).get("parser_contract_checks") or {}
    if isinstance(parser_contract_checks, dict) and parser_contract_checks.get("status") == "fail":
        parser_issues = parser_contract_checks.get("issues") or []
        existing_issues = verification.get("issues") if isinstance(verification.get("issues"), list) else []
        verification["status"] = "fail"
        verification["issues"] = existing_issues + [
            issue for issue in parser_issues if issue not in existing_issues
        ]
        summary = (verification.get("summary") or "").strip()
        parser_summary = "서버 smoke test에서 실제 웹 데이터 샘플을 추출하지 못했습니다."
        verification["summary"] = f"{summary} {parser_summary}".strip() if summary else parser_summary
        checks = verification.get("checks") if isinstance(verification.get("checks"), dict) else {}
        checks["parser_smoke"] = "fail"
        checks["minimum_sample_data"] = "fail"
        verification["checks"] = checks

    verification["evidence"] = {
        "build_spec": evidence.get("build_spec") or normalized_build_spec,
        "source_probe_results": evidence.get("source_probe_results") or [],
        "fetched_sources": evidence.get("fetched_sources") or [],
        "static_signals": evidence.get("static_signals") or {},
    }
    return verification


def verify_release_external_data_gate(
    project_path,
    build_spec=None,
    task_id=None,
    token_callback=None,
    callback_log=None,
):
    resolved_build_spec = build_spec if isinstance(build_spec, dict) else {}
    if not resolved_build_spec:
        resolved_build_spec = _load_project_build_spec(project_path)

    if not _looks_like_external_data_app(resolved_build_spec):
        return {
            "status": "not_applicable",
            "summary": "외부 데이터 핵심 검증이 필요한 앱으로 분류되지 않았습니다.",
            "issues": [],
            "checks": {
                "source_probe": "not_applicable",
                "migration_notice": "not_applicable",
                "parser_smoke": "not_applicable",
                "minimum_sample_data": "not_applicable",
                "cache_persistence": "not_applicable",
            },
        }

    if callback_log:
        callback_log("🔎 핵심 기능 검증 중")
    verification = verify_external_data_dependencies(
        project_path,
        resolved_build_spec,
        task_id=task_id,
        token_callback=token_callback,
    )
    if verification.get("status") in {"pass", "not_applicable"}:
        if callback_log:
            callback_log("✅ 핵심 기능 검증 통과")
    elif callback_log:
        callback_log(f"❌ 핵심 기능 검증 실패: {verification.get('summary') or '외부 데이터 검증 실패'}")
    return verification


def build_external_data_verification_failure_result(
    verification,
    project_path,
    package_name=None,
    app_name=None,
):
    verification_summary = verification.get("summary") or "외부 데이터 핵심 기능 검증에 실패했습니다."
    verification_issues = verification.get("issues") or []
    return {
        "status": "failed",
        "error_log": verification_summary + ("\n" + "\n".join(verification_issues) if verification_issues else ""),
        "failure_stage": "verification",
        "failure_type": "external_data_verification",
        "project_path": project_path,
        "package_name": package_name,
        "app_name": app_name,
        "verification_summary": verification_summary,
        "verification_status": verification.get("status") or "fail",
        "verification_report": verification,
    }


def apply_external_data_verification_fix(
    project_path,
    verification,
    current_files=None,
    task_id=None,
    token_callback=None,
    callback_log=None,
    ui_contract=None,
    package_name=None,
):
    verification_summary = verification.get("summary") or "외부 데이터 핵심 기능 검증에 실패했습니다."
    relevant_files = current_files or get_recent_lib_files(project_path)
    debug_context = prepare_debugger_context(
        project_path,
        verification_summary,
        "verification",
        relevant_files=relevant_files,
        callback_log=callback_log,
        ui_contract=ui_contract,
    )
    debug_context["verification_report"] = verification
    debug_context["build_spec"] = _load_project_build_spec(project_path)

    fix_result = call_agent_with_tools(
        DEBUGGER_SYSTEM,
        "외부 데이터 의존 기능 검증 실패를 수정하세요. 실제 출처 URL, HTTP 응답, DOM 구조, 파서 로직을 함께 맞추세요.",
        context=debug_context,
        trace={"task_id": task_id, "flow_type": "verification_repair", "agent_name": "Debugger", "stage": "external_data_verification_fix"},
        tools=FILE_CHANGE_TOOL_SCHEMAS,
        validator=validate_file_change_payload,
        parsed_output_builder=lambda tool_name, tool_arguments: normalize_file_change_payload(tool_arguments),
        fallback_parser=legacy_agent_response_detailed,
    )
    fix = fix_result.get("parsed_output")
    usage = fix_result.get("usage")
    if token_callback and task_id and usage:
        token_callback(task_id, "Debugger_External_Data_Verification", usage)

    if not fix or "files" not in fix:
        if callback_log:
            callback_log("⚠️ 외부 데이터 검증 실패 수정 패치가 생성되지 않았습니다.")
        return []

    files = fix.get("files") or []
    save_project_files(project_path, files)
    if package_name:
        ensure_crash_handler_identity(project_path, task_id, package_name)
    touched_paths = [f.get("path") for f in files if f.get("path")]
    if callback_log and touched_paths:
        callback_log(f"🩹 외부 데이터 검증 수정 적용 파일: {json.dumps(touched_paths, ensure_ascii=False)}")
    return touched_paths


def run_vibe_factory(task_id, user_request, device_context=None, callback_log=None, token_callback=None, reference_image_analysis=None, image_conflict_note="", ui_contract=None):

    if callback_log:
        callback_log("🧠 앱 설계 중")

    build_context = extract_build_request_context(user_request)
    plan = build_specialized_generation_plan(
        task_id,
        user_request,
        device_context=device_context,
        callback_log=callback_log,
        token_callback=token_callback,
        reference_image_analysis=reference_image_analysis,
        image_conflict_note=image_conflict_note,
        ui_contract=ui_contract,
    )

    if plan.get("status") != "success":
        return plan
    plan.pop("status", None)

    title = plan.get("title", "VibeApp")
    
    base_pkg = plan.get("package_name", "kr.ac.kangwon.hai.generated")
    base_pkg = sanitize_package(base_pkg)
    safe_task_id = re.sub(r'[^a-z0-9_]', '', task_id.lower())
    pkg = f"{base_pkg}.t{safe_task_id}"


    if callback_log:
        callback_log(f"📦 패키지 이름 설정됨: {pkg}")


    clean_title = re.sub(r'\W+', '', title)
    folder = f"{clean_title}_{task_id}"

    project = os.path.join(BUILD_ROOT_DIR, folder)

    shutil.copytree(
        BASE_PROJECT_PATH,
        project,
        ignore=shutil.ignore_patterns(
            'build',
            '.gradle',
            '.dart_tool',
            '.idea',
            '.flutter-plugins',
            '.flutter-plugins-dependencies',
            '*.iml',
        )
    )

    update_android_label(project, title)
    update_package_name(project, pkg)
    update_pubspec_name(project, title)
    update_dart_imports(project, "baseproject", title)
    ensure_crash_handler_identity(project, task_id, pkg)

    ensure_crash_fn = lambda: ensure_crash_handler_identity(project, task_id, pkg)
    current_files = []

    # ENGINEER AGENTIC LOOP
    eng_user_request = (
        f"다음 설계안을 Flutter 앱으로 구현하세요.\n\n"
        f"설계안:\n{json.dumps(plan, ensure_ascii=False)}"
    )

    analyze_ok = False
    failure_type = "engineer_loop_failed"
    analyze_res = ""

    for attempt in range(2):
        if callback_log:
            callback_log(f"✍️ 코드 생성 중 (시도 {attempt+1})")

        if attempt > 0 and plan.get("feedback"):
            eng_user_request += f"\n\n검토 피드백:\n{json.dumps(plan['feedback'], ensure_ascii=False)}"

        eng_loop_result = run_agentic_loop(
            task_id=task_id,
            user_request=eng_user_request,
            tools=ENGINEER_TOOLS,
            system=ENGINEER_SYSTEM_V2,
            project_path=project,
            callback_log=callback_log,
            token_callback=token_callback,
            pkg=pkg,
            ensure_crash_fn=ensure_crash_fn,
        )

        if eng_loop_result.get("status") == "max_turns_exceeded":
            if callback_log:
                callback_log("⚠️ Engineer 루프 최대 턴 초과")

        # Check analyze result from loop or run fresh
        if callback_log:
            callback_log("🧪 정적 분석 확인 중")
        analyze_ok, analyze_res = run_flutter_analyze(project)

        if analyze_ok:
            if callback_log:
                callback_log("✅ 정적 분석 통과")
        else:
            failure_type = classify_failure_type(analyze_res)
            if callback_log:
                callback_log(f"❌ 정적 분석 실패 ({failure_type})")

        if callback_log:
            callback_log("🧐 코드 검토 중")

        # Read current files for Reviewer context
        current_dart_files = []
        lib_path = os.path.join(project, "lib")
        if os.path.exists(lib_path):
            for root, _, fnames in os.walk(lib_path):
                for fname in fnames:
                    if fname.endswith(".dart"):
                        rel = os.path.relpath(os.path.join(root, fname), project)
                        try:
                            with open(os.path.join(root, fname), "r", encoding="utf-8") as f:
                                current_dart_files.append({"path": rel, "content": f.read()})
                        except Exception:
                            pass

        review_result = call_agent_with_tools(
            REVIEWER_SYSTEM,
            "이 코드를 검토하세요.",
            context={
                "implementation": {"files": current_dart_files},
                "build_spec": build_context.get("build_spec") or {},
                "ui_contract": ui_contract or {},
                "analyze_result": {"ok": analyze_ok, "output": analyze_res[:2000]},
            },
            trace={"task_id": task_id, "flow_type": "generate", "agent_name": "Reviewer", "stage": "review"},
            tools=REVIEW_TOOL_SCHEMAS,
            validator=validate_review_payload,
            parsed_output_builder=lambda tool_name, tool_arguments: tool_arguments,
            fallback_parser=legacy_agent_response_detailed,
        )
        review = review_result.get("parsed_output")
        usage = review_result.get("usage")
        if token_callback and review and usage:
            token_callback(task_id, "Reviewer", usage)

        if review and review.get("status") == "pass" and analyze_ok:
            break

        if review:
            plan["feedback"] = review.get("feedback")

    if not analyze_ok:
        return {
            "status": "failed",
            "error_log": analyze_res,
            "failure_stage": "analyze",
            "failure_type": failure_type,
            "project_path": project,
            "package_name": pkg,
            "app_name": title,
        }

    # BUILD LOOP

    last_verification = None
    for i in range(3):

        if callback_log:
            callback_log("🏗️ 빌드 실행 중")

        ok, res = run_flutter_build(project)

        if ok:
            if callback_log:
                callback_log("✅ 빌드 성공")

            if callback_log:
                callback_log("🔎 핵심 기능 검증 중")

            verification = verify_external_data_dependencies(
                project,
                build_context.get("build_spec") or {},
                task_id=task_id,
                token_callback=token_callback,
            )
            last_verification = verification

            if verification.get("status") in {"pass", "not_applicable"}:
                if callback_log:
                    callback_log("✅ 핵심 기능 검증 통과")

                meta = {
                    "task_id": task_id,
                    "package_name": pkg,
                    "app_name": title,
                    "build_spec": build_context.get("build_spec") or {},
                    "verification_summary": verification.get("summary") or "",
                    "verification_status": verification.get("status") or "not_applicable",
                }

                with open(os.path.join(project, "vibe.json"), "w") as f:
                    json.dump(meta, f)

                return {
                    "status": "success",
                    "app_name": title,
                    "apk_path": res,
                    "project_path": project,
                    "package_name": pkg,
                    "verification_summary": verification.get("summary") or "",
                    "verification_report": verification,
                }

            verification_summary = verification.get("summary") or "외부 데이터 핵심 기능 검증에 실패했습니다."
            verification_issues = verification.get("issues") or []
            meta = {
                "task_id": task_id,
                "package_name": pkg,
                "app_name": title,
                "build_spec": build_context.get("build_spec") or {},
                "verification_summary": verification_summary,
                "verification_status": verification.get("status") or "fail",
            }

            with open(os.path.join(project, "vibe.json"), "w") as f:
                json.dump(meta, f)

            if callback_log:
                callback_log(f"❌ 핵심 기능 검증 실패: {verification_summary}")

            if i == 2:
                return {
                    "status": "failed",
                    "error_log": verification_summary + ("\n" + "\n".join(verification_issues) if verification_issues else ""),
                    "failure_stage": "verification",
                    "failure_type": "external_data_verification",
                    "project_path": project,
                    "package_name": pkg,
                    "app_name": title,
                    "verification_summary": verification_summary,
                    "verification_report": verification,
                }

            if callback_log:
                callback_log(f"🛠 검증 실패 원인 수정 중 ({i+1}/3)")

            debug_context = prepare_debugger_context(
                project,
                verification_summary,
                "verification",
                relevant_files=current_files,
                callback_log=callback_log,
                ui_contract=ui_contract,
            )
            debug_context["verification_report"] = verification
            debug_context["build_spec"] = build_context.get("build_spec") or {}
            fix_result = call_agent_with_tools(
                DEBUGGER_SYSTEM,
                "외부 데이터 의존 기능 검증 실패를 수정하세요.",
                context=debug_context,
                trace={"task_id": task_id, "flow_type": "generate", "agent_name": "Debugger", "stage": "verification_fix"},
                tools=FILE_CHANGE_TOOL_SCHEMAS,
                validator=validate_file_change_payload,
                parsed_output_builder=lambda tool_name, tool_arguments: normalize_file_change_payload(tool_arguments),
                fallback_parser=legacy_agent_response_detailed,
            )
            fix = fix_result.get("parsed_output")
            usage = fix_result.get("usage")
            if token_callback and fix and usage:
                token_callback(task_id, "Debugger_Verification", usage)
            if fix and "files" in fix:
                save_project_files(project, fix["files"])
                ensure_crash_handler_identity(project, task_id, pkg)
                current_files += [f.get("path") for f in fix["files"] if f.get("path")]
            continue

        failure_type = classify_failure_type(res)
        if callback_log:
            callback_log(f"❌ 빌드 실패 ({failure_type})")

        if callback_log:
            callback_log(f"🛠 빌드 오류 수정 중 ({i+1}/3)")

        debug_context = prepare_debugger_context(
            project,
            res,
            "build",
            relevant_files=current_files,
            callback_log=callback_log,
            ui_contract=ui_contract,
        )

        fix_result = call_agent_with_tools(
            DEBUGGER_SYSTEM,
            "빌드 오류를 수정하세요.",
            context=debug_context,
            trace={"task_id": task_id, "flow_type": "generate", "agent_name": "Debugger", "stage": "build_fix"},
            tools=FILE_CHANGE_TOOL_SCHEMAS,
            validator=validate_file_change_payload,
            parsed_output_builder=lambda tool_name, tool_arguments: normalize_file_change_payload(tool_arguments),
            fallback_parser=legacy_agent_response_detailed,
        )
        fix = fix_result.get("parsed_output")
        usage = fix_result.get("usage")
        if token_callback and fix and usage:
            token_callback(task_id, "Debugger", usage)  

        if fix and "files" in fix:

            save_project_files(project, fix["files"])
            ensure_crash_handler_identity(project, task_id, pkg)

            current_files += [f.get("path") for f in fix["files"] if f.get("path")]

    return {
        "status": "failed",
        "error_log": res,
        "failure_stage": "build",
        "failure_type": classify_failure_type(res),
        "project_path": project,
        "package_name": pkg,
        "app_name": title,
        "verification_summary": (last_verification or {}).get("summary") or "",
        "verification_report": last_verification,
    }


# -------------------------------------------------
# REFINEMENT
# -------------------------------------------------

def refine_vibe_app(project_path, feedback, task_id=None, callback_log=None, token_callback=None, reference_image_analysis=None, image_conflict_note="", ui_contract=None):
    if callback_log:
        callback_log("🔄 리파인 계획 수립 중")

    preview = create_refinement_plan_preview(
        project_path,
        feedback,
        task_id=task_id,
        token_callback=token_callback,
        reference_image_analysis=reference_image_analysis,
        image_conflict_note=image_conflict_note,
        ui_contract=ui_contract,
    )
    if preview["status"] != "success":
        if callback_log:
            callback_log(f"❌ 리파인 차단: {preview['error_log']}")
        return preview

    pkg = preview.get("package_name")
    app_name = preview.get("app_name")
    plan = preview.get("plan") or {}
    files_to_modify = preview.get("files_to_modify") or ["lib/main.dart"]

    relevant_files = build_refinement_relevant_files(files_to_modify)
    refinement_snapshot = get_current_project_snapshot(project_path, relevant_files)
    user_summary = preview.get("assistant_message") or build_refinement_user_summary(plan, files_to_modify)

    if callback_log:
        callback_log(user_summary)

    if callback_log:
        callback_log(f"🧩 리파인 대상 파일: {json.dumps(files_to_modify, ensure_ascii=False)}")

    current_files = list(files_to_modify)

    for attempt in range(2):
        if callback_log:
            callback_log(f"✍️ 리파인 코드 수정 중 (시도 {attempt+1})")

        eng_result = call_agent_with_tools(
            REFINER_ENGINEER_SYSTEM,
            "기존 앱을 유지하면서 리파인 계획을 구현하세요.",
            context={
                "feedback": feedback,
                "package_name": pkg,
                "app_name": app_name,
                "files_to_modify": files_to_modify,
                "file_creation_allowed": False,
                "refinement_plan": plan.get("refinement_plan"),
                "analysis": plan.get("analysis"),
                "relevant_files": relevant_files,
                "code_snapshot": refinement_snapshot,
                "ui_contract": ui_contract or {},
            },
            trace={"task_id": task_id, "flow_type": "refine", "agent_name": "Refiner_Engineer", "stage": "implement"},
            tools=FILE_CHANGE_TOOL_SCHEMAS,
            validator=validate_file_change_payload,
            parsed_output_builder=lambda tool_name, tool_arguments: normalize_file_change_payload(tool_arguments),
            fallback_parser=legacy_agent_response_detailed,
        )
        eng = eng_result.get("parsed_output")
        usage = eng_result.get("usage")
        if token_callback and task_id and usage:
            token_callback(task_id, "Refiner_Engineer", usage)

        if not eng:
            continue

        filtered_files, blocked_files = filter_refinement_files(
            eng.get("files", []),
            files_to_modify,
            callback_log=callback_log
        )
        if not filtered_files:
            if callback_log:
                callback_log("⚠️ 적용 가능한 리파인 수정 파일이 없습니다.")
            continue

        touched_paths = [f.get("path") for f in filtered_files if f.get("path")]
        if callback_log:
            callback_log(f"📝 실제 적용 리파인 파일: {json.dumps(touched_paths, ensure_ascii=False)}")

        save_project_files(project_path, filtered_files)
        ensure_crash_handler_identity(project_path, task_id, pkg)
        current_files = merge_unique_paths(current_files + touched_paths)
        refinement_snapshot = get_current_project_snapshot(project_path, build_refinement_relevant_files(current_files))

        review_result = call_agent_with_tools(
            REFINER_REVIEWER_SYSTEM,
            "리파인 결과를 검토하세요.",
            context={
                "feedback": feedback,
                "files_to_modify": files_to_modify,
                "blocked_files": blocked_files,
                "applied_files": touched_paths,
                "code_snapshot": refinement_snapshot,
                "ui_contract": ui_contract or {},
            },
            trace={"task_id": task_id, "flow_type": "refine", "agent_name": "Refiner_Reviewer", "stage": "review"},
            tools=REVIEW_TOOL_SCHEMAS,
            validator=validate_review_payload,
            parsed_output_builder=lambda tool_name, tool_arguments: tool_arguments,
            fallback_parser=legacy_agent_response_detailed,
        )
        review = review_result.get("parsed_output")
        usage = review_result.get("usage")
        if token_callback and task_id and usage:
            token_callback(task_id, "Refiner_Reviewer", usage)

        if review and review.get("status") == "pass":
            break

    for i in range(3):
        ok, res = run_flutter_build(project_path)
        if ok:
            verification = verify_release_external_data_gate(
                project_path,
                task_id=task_id,
                token_callback=token_callback,
                callback_log=callback_log,
            )
            if verification.get("status") not in {"pass", "not_applicable"}:
                if i == 2:
                    return build_external_data_verification_failure_result(
                        verification,
                        project_path,
                        package_name=pkg,
                        app_name=app_name,
                    )
                if callback_log:
                    callback_log(f"🛠 외부 데이터 검증 실패 원인 수정 중 ({i+1}/3)")
                touched_verification_paths = apply_external_data_verification_fix(
                    project_path,
                    verification,
                    current_files=build_refinement_relevant_files(current_files),
                    task_id=task_id,
                    token_callback=token_callback,
                    callback_log=callback_log,
                    ui_contract=ui_contract,
                    package_name=pkg,
                )
                current_files = merge_unique_paths(current_files + touched_verification_paths)
                continue
            return {
                "status": "success",
                "apk_path": res,
                "project_path": project_path,
                "package_name": pkg,
                "verification_summary": verification.get("summary") or "",
                "verification_status": verification.get("status") or "not_applicable",
                "verification_report": verification,
            }

        fix_result = call_agent_with_tools(
            DEBUGGER_SYSTEM,
            "리파인 후 빌드 오류를 수정하세요.",
            context=prepare_debugger_context(
                project_path,
                res,
                "refine_build",
                relevant_files=build_refinement_relevant_files(current_files),
                callback_log=callback_log,
                ui_contract=ui_contract,
            ),
            trace={"task_id": task_id, "flow_type": "refine", "agent_name": "Refiner_Debugger", "stage": "build_fix"},
            tools=FILE_CHANGE_TOOL_SCHEMAS,
            validator=validate_file_change_payload,
            parsed_output_builder=lambda tool_name, tool_arguments: normalize_file_change_payload(tool_arguments),
            fallback_parser=legacy_agent_response_detailed,
        )
        fix = fix_result.get("parsed_output")
        usage = fix_result.get("usage")
        if token_callback and task_id and usage:
            token_callback(task_id, "Refiner_Debugger", usage)
        
        if fix and "files" in fix:
            filtered_fix_files, _ = filter_refinement_files(
                fix["files"],
                current_files,
                callback_log=callback_log
            )
            if filtered_fix_files:
                touched_fix_paths = [f.get("path") for f in filtered_fix_files if f.get("path")]
                if callback_log:
                    callback_log(f"🩹 리파인 빌드 수정 적용 파일: {json.dumps(touched_fix_paths, ensure_ascii=False)}")
                save_project_files(project_path, filtered_fix_files)
                ensure_crash_handler_identity(project_path, task_id, pkg)
                current_files = merge_unique_paths(current_files + touched_fix_paths)

    return {"status": "failed", "error_log": res, "project_path": project_path, "package_name": pkg}


def retry_failed_vibe_app(project_path, feedback, package_name=None, task_id=None, callback_log=None, token_callback=None, request_context=None, ui_contract=None):
    if not project_path or not isinstance(project_path, str):
        if callback_log:
            callback_log("❌ 재시도 차단: project_path가 없습니다.")
        return {"status": "failed", "error_log": "재시도에는 유효한 project_path가 필요합니다.", "project_path": project_path}

    if not os.path.isdir(project_path):
        if callback_log:
            callback_log(f"❌ 재시도 차단: 유효하지 않은 project_path {project_path}")
        return {"status": "failed", "error_log": f"유효하지 않은 project_path입니다: {project_path}", "project_path": project_path}

    current_files = get_recent_lib_files(project_path)
    snapshot = get_current_project_snapshot(project_path, current_files)

    if callback_log:
        callback_log("🔁 실패한 빌드 복구 계획 수립 중")

    plan_result = call_agent_with_tools(
        REFINER_PLANNER_SYSTEM,
        feedback,
        context={
            "snapshot": snapshot,
            "retry_request_context": request_context or {},
            "user_retry_feedback": feedback,
            "ui_contract": ui_contract or {},
        },
        trace={"task_id": task_id, "flow_type": "retry", "agent_name": "Retry_Planner", "stage": "plan"},
        tools=REFINER_PLANNER_TOOL_SCHEMAS,
        validator=validate_refinement_plan_payload,
        parsed_output_builder=lambda tool_name, tool_arguments: tool_arguments,
        fallback_parser=legacy_agent_response_detailed,
    )
    plan = plan_result.get("parsed_output")
    usage = plan_result.get("usage")
    if token_callback and task_id and usage:
        token_callback(task_id, "Retry_Planner", usage)

    if not plan:
        return {"status": "failed", "error_log": "재시도 계획 수립에 실패했습니다.", "project_path": project_path, "package_name": package_name}
    plan["ui_contract"] = ui_contract or {}

    for attempt in range(2):
        if callback_log:
            callback_log(f"✍️ 실패한 빌드 재시도 코드 수정 중 (시도 {attempt+1})")

        eng_result = call_agent_with_tools(
            ENGINEER_SYSTEM,
            "실패한 빌드 복구 계획을 구현하세요.",
            context=plan,
            trace={"task_id": task_id, "flow_type": "retry", "agent_name": "Retry_Engineer", "stage": "implement"},
            tools=FILE_CHANGE_TOOL_SCHEMAS,
            validator=validate_file_change_payload,
            parsed_output_builder=lambda tool_name, tool_arguments: normalize_file_change_payload(tool_arguments),
            fallback_parser=legacy_agent_response_detailed,
        )
        eng = eng_result.get("parsed_output")
        usage = eng_result.get("usage")
        if token_callback and task_id and usage:
            token_callback(task_id, "Retry_Engineer", usage)

        if not eng:
            continue

        save_project_files(project_path, eng.get("files", []))

        if callback_log:
            callback_log("🧐 복구 패치 검토 중")

        review_result = call_agent_with_tools(
            REVIEWER_SYSTEM,
            "실패한 빌드 복구 패치를 검토하세요.",
            context={
                "implementation": eng,
                "ui_contract": ui_contract or {},
            },
            trace={"task_id": task_id, "flow_type": "retry", "agent_name": "Retry_Reviewer", "stage": "review"},
            tools=REVIEW_TOOL_SCHEMAS,
            validator=validate_review_payload,
            parsed_output_builder=lambda tool_name, tool_arguments: tool_arguments,
            fallback_parser=legacy_agent_response_detailed,
        )
        review = review_result.get("parsed_output")
        usage = review_result.get("usage")
        if token_callback and task_id and usage:
            token_callback(task_id, "Retry_Reviewer", usage)

        if review and review.get("status") == "pass":
            break

    if callback_log:
        callback_log("🧪 정적 분석 실행 중")

    analyze_ok, analyze_res = run_flutter_analyze(project_path)
    if analyze_ok:
        if callback_log:
            callback_log("✅ 정적 분석 통과")
    else:
        failure_type = classify_failure_type(analyze_res)
        if callback_log:
            callback_log(f"❌ 정적 분석 실패 ({failure_type})")

        for i in range(2):
            if callback_log:
                callback_log(f"🛠 정적 분석 오류 수정 중 ({i+1}/2)")

            debug_context = prepare_debugger_context(
                project_path,
                analyze_res,
                "analyze",
                relevant_files=current_files,
                callback_log=callback_log,
                ui_contract=ui_contract,
            )

            fix_result = call_agent_with_tools(
                DEBUGGER_SYSTEM,
                "실패한 빌드 복구 중 정적 분석 오류를 수정하세요.",
                context=debug_context,
                trace={"task_id": task_id, "flow_type": "retry", "agent_name": "Retry_Debugger", "stage": "analyze_fix"},
                tools=FILE_CHANGE_TOOL_SCHEMAS,
                validator=validate_file_change_payload,
                parsed_output_builder=lambda tool_name, tool_arguments: normalize_file_change_payload(tool_arguments),
                fallback_parser=legacy_agent_response_detailed,
            )
            fix = fix_result.get("parsed_output")
            usage = fix_result.get("usage")
            if token_callback and task_id and usage:
                token_callback(task_id, "Retry_Debugger_Analyze", usage)

            if fix and "files" in fix:
                save_project_files(project_path, fix["files"])
                current_files += [f.get("path") for f in fix["files"] if f.get("path")]

            if callback_log:
                callback_log("🧪 정적 분석 실행 중")
            analyze_ok, analyze_res = run_flutter_analyze(project_path)
            if analyze_ok:
                if callback_log:
                    callback_log("✅ 정적 분석 통과")
                break
            failure_type = classify_failure_type(analyze_res)
            if callback_log:
                callback_log(f"❌ 정적 분석 실패 ({failure_type})")

        if not analyze_ok:
            return {"status": "failed", "error_log": analyze_res, "failure_stage": "analyze", "failure_type": failure_type, "project_path": project_path, "package_name": package_name}

    for i in range(3):
        if callback_log:
            callback_log("🏗️ 빌드 실행 중")
        ok, res = run_flutter_build(project_path)
        if ok:
            if callback_log:
                callback_log("✅ 빌드 성공")
            request_build_spec = (request_context or {}).get("final_app_spec") if isinstance(request_context, dict) else {}
            verification = verify_release_external_data_gate(
                project_path,
                build_spec=request_build_spec if isinstance(request_build_spec, dict) else {},
                task_id=task_id,
                token_callback=token_callback,
                callback_log=callback_log,
            )
            if verification.get("status") not in {"pass", "not_applicable"}:
                if i == 2:
                    return build_external_data_verification_failure_result(
                        verification,
                        project_path,
                        package_name=package_name,
                    )
                if callback_log:
                    callback_log(f"🛠 외부 데이터 검증 실패 원인 수정 중 ({i+1}/3)")
                touched_verification_paths = apply_external_data_verification_fix(
                    project_path,
                    verification,
                    current_files=current_files,
                    task_id=task_id,
                    token_callback=token_callback,
                    callback_log=callback_log,
                    ui_contract=ui_contract,
                    package_name=package_name,
                )
                current_files = merge_unique_paths(current_files + touched_verification_paths)
                continue
            return {
                "status": "success",
                "apk_path": res,
                "project_path": project_path,
                "package_name": package_name,
                "verification_summary": verification.get("summary") or "",
                "verification_status": verification.get("status") or "not_applicable",
                "verification_report": verification,
            }

        failure_type = classify_failure_type(res)
        if callback_log:
            callback_log(f"❌ 빌드 실패 ({failure_type})")

        if callback_log:
            callback_log(f"🛠 재시도 빌드 오류 수정 중 ({i+1}/3)")

        debug_context = prepare_debugger_context(
            project_path,
            res,
            "build",
            relevant_files=current_files,
            callback_log=callback_log,
            ui_contract=ui_contract,
        )

        fix_result = call_agent_with_tools(
            DEBUGGER_SYSTEM,
            "재시도 빌드 오류를 수정하세요.",
            context=debug_context,
            trace={"task_id": task_id, "flow_type": "retry", "agent_name": "Retry_Debugger", "stage": "build_fix"},
            tools=FILE_CHANGE_TOOL_SCHEMAS,
            validator=validate_file_change_payload,
            parsed_output_builder=lambda tool_name, tool_arguments: normalize_file_change_payload(tool_arguments),
            fallback_parser=legacy_agent_response_detailed,
        )
        fix = fix_result.get("parsed_output")
        usage = fix_result.get("usage")
        if token_callback and task_id and usage:
            token_callback(task_id, "Retry_Debugger", usage)

        if fix and "files" in fix:
            save_project_files(project_path, fix["files"])
            current_files += [f.get("path") for f in fix["files"] if f.get("path")]

    return {"status": "failed", "error_log": res, "failure_stage": "build", "failure_type": classify_failure_type(res), "project_path": project_path, "package_name": package_name}
