import json
import sqlite3
from pathlib import Path
from typing import Any, Optional

from fastapi import APIRouter, Header, HTTPException, Request
from fastapi.responses import FileResponse


ASSET_ROOT = Path(__file__).resolve().parent / "admin_dashboard"


def register_admin_dashboard_routes(app: Any, require_admin_token: Any) -> None:
    router = APIRouter(prefix="/admin/dashboard")

    @router.get("")
    def dashboard_page() -> FileResponse:
        return FileResponse(ASSET_ROOT / "index.html")

    @router.get("/")
    def dashboard_page_slash() -> FileResponse:
        return FileResponse(ASSET_ROOT / "index.html")

    @router.get("/task/{task_id}")
    def dashboard_task_page(task_id: str) -> FileResponse:
        return FileResponse(ASSET_ROOT / "task.html")

    @router.get("/assets/{asset_name}")
    def dashboard_asset(asset_name: str) -> FileResponse:
        if asset_name not in {"styles.css", "app.js", "task.js"}:
            raise HTTPException(status_code=404, detail="asset not found")
        return FileResponse(ASSET_ROOT / asset_name)

    @router.get("/data")
    def dashboard_data(
        request: Request,
        x_admin_token: Optional[str] = Header(default=None),
    ) -> dict[str, Any]:
        settings = request.app.state.settings
        db = request.app.state.db
        require_admin_token(settings, x_admin_token)
        return build_dashboard_payload(db)

    @router.get("/tasks/{task_id}")
    def dashboard_task_detail(
        task_id: str,
        request: Request,
        x_admin_token: Optional[str] = Header(default=None),
    ) -> dict[str, Any]:
        settings = request.app.state.settings
        db = request.app.state.db
        require_admin_token(settings, x_admin_token)
        return build_task_detail_payload(db, task_id)

    app.include_router(router)


def build_dashboard_payload(db: Any) -> dict[str, Any]:
    with db.connect() as connection:
        connection.row_factory = sqlite3.Row
        return {
            "overview": overview_payload(connection),
            "status_counts": query_all(
                connection,
                """
                SELECT status, COUNT(*) AS count
                FROM tasks
                GROUP BY status
                ORDER BY count DESC, status ASC
                """,
            ),
            "users": query_all(
                connection,
                """
                SELECT
                    COALESCE(NULLIF(phone_number, ''), NULLIF(user_id, ''), device_id, 'unknown') AS identity,
                    COUNT(*) AS task_count,
                    SUM(CASE WHEN status = 'Success' THEN 1 ELSE 0 END) AS success_count,
                    SUM(CASE WHEN status IN ('Failed', 'Error') THEN 1 ELSE 0 END) AS failed_count,
                    SUM(COALESCE(total_tokens, 0)) AS total_tokens,
                    MAX(updated_at) AS last_seen_at
                FROM tasks
                GROUP BY identity
                ORDER BY task_count DESC, last_seen_at DESC
                LIMIT 50
                """,
            ),
            "recent_tasks": recent_tasks_payload(connection),
            "event_counts": query_all(
                connection,
                """
                SELECT event_type, COUNT(*) AS count
                FROM task_events
                GROUP BY event_type
                ORDER BY count DESC, event_type ASC
                LIMIT 24
                """,
            ),
            "token_timeline": query_all(
                connection,
                """
                SELECT
                    substr(created_at, 1, 10) AS day,
                    COUNT(*) AS task_count,
                    SUM(COALESCE(total_tokens, 0)) AS total_tokens
                FROM tasks
                GROUP BY day
                ORDER BY day DESC
                LIMIT 14
                """,
            ),
            "top_token_tasks": query_all(
                connection,
                """
                SELECT
                    t.task_id,
                    COALESCE(NULLIF(t.app_name, ''), '이름 없음') AS app_name,
                    COALESCE(NULLIF(t.phone_number, ''), NULLIF(t.user_id, ''), t.device_id, 'unknown') AS identity,
                    t.status,
                    COALESCE(t.total_tokens, SUM(COALESCE(u.total_tokens, 0)), 0) AS total_tokens,
                    t.updated_at
                FROM tasks t
                LEFT JOIN task_usage_records u ON u.task_id = t.task_id
                GROUP BY t.task_id
                ORDER BY total_tokens DESC
                LIMIT 20
                """,
            ),
            "runtime_errors": runtime_errors_payload(connection),
            "app_ai_overview": app_ai_overview_payload(connection),
            "app_ai_usage_by_app": app_ai_usage_by_app_payload(connection),
            "app_llm_usage": query_all(
                connection,
                """
                SELECT
                    task_id,
                    package_name,
                    status,
                    input_tokens,
                    output_tokens,
                    total_tokens,
                    created_at
                FROM app_llm_usage
                ORDER BY created_at DESC
                LIMIT 30
                """,
            ),
        }


def overview_payload(connection: sqlite3.Connection) -> dict[str, Any]:
    row = query_one(
        connection,
        """
        SELECT
            COUNT(*) AS total_tasks,
            SUM(CASE WHEN status = 'Success' THEN 1 ELSE 0 END) AS success_tasks,
            SUM(CASE WHEN status IN ('Failed', 'Error') THEN 1 ELSE 0 END) AS failed_tasks,
            SUM(CASE WHEN status IN ('Queued', 'Running') THEN 1 ELSE 0 END) AS running_tasks,
            SUM(CASE WHEN status = 'Pending Decision' THEN 1 ELSE 0 END) AS pending_decision_tasks,
            COUNT(DISTINCT COALESCE(NULLIF(phone_number, ''), NULLIF(user_id, ''), device_id)) AS user_count,
            SUM(COALESCE(total_tokens, 0)) AS total_tokens,
            AVG(
                CASE
                    WHEN status IN ('Success', 'Failed', 'Error')
                    THEN (julianday(updated_at) - julianday(created_at)) * 86400
                    ELSE NULL
                END
            ) AS avg_terminal_duration_seconds
        FROM tasks
        """,
    )
    events = query_one(connection, "SELECT COUNT(*) AS event_count FROM task_events")
    usage = query_one(
        connection,
        """
        SELECT
            COUNT(*) AS usage_record_count,
            SUM(COALESCE(total_tokens, 0)) AS recorded_total_tokens
        FROM task_usage_records
        """,
    )
    return {
        **row,
        "event_count": events.get("event_count", 0),
        "usage_record_count": usage.get("usage_record_count", 0),
        "recorded_total_tokens": usage.get("recorded_total_tokens", 0),
    }


def recent_tasks_payload(connection: sqlite3.Connection) -> list[dict[str, Any]]:
    rows = query_all(
        connection,
        """
        SELECT
            task_id,
            COALESCE(NULLIF(phone_number, ''), NULLIF(user_id, ''), device_id, 'unknown') AS identity,
            status,
            message,
            prompt,
            app_name,
            package_name,
            apk_url,
            input_tokens,
            cached_input_tokens,
            output_tokens,
            reasoning_output_tokens,
            total_tokens,
            created_at,
            updated_at,
            CAST((julianday(updated_at) - julianday(created_at)) * 86400 AS INTEGER) AS duration_seconds
        FROM tasks
        ORDER BY created_at DESC
        LIMIT 80
        """,
    )
    for row in rows:
        row["prompt_preview"] = compact_text(str(row.get("prompt") or ""), 120)
        row["message_preview"] = compact_text(str(row.get("message") or ""), 90)
    return rows


def runtime_errors_payload(connection: sqlite3.Connection) -> list[dict[str, Any]]:
    rows = query_all(
        connection,
        """
        SELECT
            e.task_id,
            t.app_name,
            COALESCE(NULLIF(t.phone_number, ''), NULLIF(t.user_id, ''), t.device_id, 'unknown') AS identity,
            e.message_text,
            e.payload_json,
            e.created_at
        FROM task_events e
        LEFT JOIN tasks t ON t.task_id = e.task_id
        WHERE e.event_type = 'runtime_error_detected'
        ORDER BY e.created_at DESC
        LIMIT 30
        """,
    )
    for row in rows:
        payload = parse_json_object(row.pop("payload_json", None))
        row["package_name"] = payload.get("package_name", "")
        row["error_message"] = payload.get("error_message", "")
        row["report_kind"] = payload.get("report_kind", "")
    return rows


def app_ai_overview_payload(connection: sqlite3.Connection) -> dict[str, Any]:
    row = query_one(
        connection,
        """
        SELECT
            COUNT(*) AS configured_app_count,
            SUM(CASE WHEN enabled = 1 THEN 1 ELSE 0 END) AS enabled_app_count
        FROM app_llm_configs
        """,
    )
    usage = query_one(
        connection,
        """
        SELECT
            COUNT(*) AS total_request_count,
            SUM(COALESCE(total_tokens, 0)) AS total_tokens,
            SUM(CASE WHEN created_at >= date('now') THEN 1 ELSE 0 END) AS today_request_count,
            SUM(CASE WHEN created_at >= date('now') THEN COALESCE(total_tokens, 0) ELSE 0 END) AS today_tokens,
            SUM(CASE WHEN status LIKE '%limit%' OR status LIKE 'http_429%' THEN 1 ELSE 0 END) AS limit_error_count
        FROM app_llm_usage
        """,
    )
    return {**row, **usage}


def app_ai_usage_by_app_payload(connection: sqlite3.Connection) -> list[dict[str, Any]]:
    rows = query_all(
        connection,
        """
        SELECT
            c.task_id,
            COALESCE(NULLIF(t.app_name, ''), '이름 없음') AS app_name,
            COALESCE(NULLIF(t.phone_number, ''), NULLIF(t.user_id, ''), t.device_id, 'unknown') AS identity,
            COALESCE(NULLIF(t.package_name, ''), u.package_name, '') AS package_name,
            c.enabled,
            c.model,
            c.daily_request_limit,
            c.daily_token_limit,
            COUNT(u.usage_id) AS total_request_count,
            SUM(COALESCE(u.total_tokens, 0)) AS total_tokens,
            SUM(CASE WHEN u.created_at >= date('now') THEN 1 ELSE 0 END) AS today_request_count,
            SUM(CASE WHEN u.created_at >= date('now') THEN COALESCE(u.total_tokens, 0) ELSE 0 END) AS today_tokens,
            SUM(CASE WHEN u.status LIKE '%limit%' OR u.status LIKE 'http_429%' THEN 1 ELSE 0 END) AS limit_error_count,
            MAX(u.created_at) AS last_used_at
        FROM app_llm_configs c
        LEFT JOIN tasks t ON t.task_id = c.task_id
        LEFT JOIN app_llm_usage u ON u.task_id = c.task_id
        GROUP BY c.task_id
        ORDER BY today_tokens DESC, total_tokens DESC, last_used_at DESC
        LIMIT 80
        """,
    )
    for row in rows:
        request_limit = int(row.get("daily_request_limit") or 0)
        token_limit = int(row.get("daily_token_limit") or 0)
        today_requests = int(row.get("today_request_count") or 0)
        today_tokens = int(row.get("today_tokens") or 0)
        row["request_used_percent"] = percent(today_requests, request_limit)
        row["token_used_percent"] = percent(today_tokens, token_limit)
        row["risk_level"] = app_ai_risk_level(
            enabled=bool(row.get("enabled")),
            request_percent=row["request_used_percent"],
            token_percent=row["token_used_percent"],
            limit_error_count=int(row.get("limit_error_count") or 0),
        )
    return rows


def percent(value: int, total: int) -> int:
    if total <= 0:
        return 0
    return max(0, min(100, round((value / total) * 100)))


def app_ai_risk_level(
    *,
    enabled: bool,
    request_percent: int,
    token_percent: int,
    limit_error_count: int,
) -> str:
    if not enabled:
        return "disabled"
    if limit_error_count > 0 or request_percent >= 95 or token_percent >= 95:
        return "critical"
    if request_percent >= 80 or token_percent >= 80:
        return "watch"
    return "normal"


def build_task_detail_payload(db: Any, task_id: str) -> dict[str, Any]:
    with db.connect() as connection:
        connection.row_factory = sqlite3.Row
        task = query_one(
            connection,
            """
            SELECT
                task_id,
                COALESCE(NULLIF(phone_number, ''), NULLIF(user_id, ''), device_id, 'unknown') AS identity,
                status,
                message,
                prompt,
                app_name,
                package_name,
                apk_url,
                input_tokens,
                cached_input_tokens,
                output_tokens,
                reasoning_output_tokens,
                total_tokens,
                codex_result_json,
                created_at,
                updated_at,
                CAST((julianday(updated_at) - julianday(created_at)) * 86400 AS INTEGER) AS duration_seconds
            FROM tasks
            WHERE task_id = ?
            """,
            (task_id,),
        )
        if not task:
            raise HTTPException(status_code=404, detail="task not found")

        events = task_conversation_events(connection, task_id)
        usage_records = query_all(
            connection,
            """
            SELECT
                source,
                model,
                input_tokens,
                cached_input_tokens,
                output_tokens,
                cached_output_tokens,
                reasoning_output_tokens,
                total_tokens,
                status,
                created_at
            FROM task_usage_records
            WHERE task_id = ?
            ORDER BY rowid ASC
            """,
            (task_id,),
        )
        snapshots = query_all(
            connection,
            """
            SELECT revision_label, source, workspace_path, project_path, created_at
            FROM task_project_snapshots
            WHERE task_id = ?
            ORDER BY rowid ASC
            """,
            (task_id,),
        )

        task["prompt_preview"] = compact_text(str(task.get("prompt") or ""), 240)
        task["codex_result"] = parse_json_object(task.pop("codex_result_json", None))
        return {
            "task": task,
            "events": events,
            "usage_records": usage_records,
            "snapshots": snapshots,
        }


def task_conversation_events(connection: sqlite3.Connection, task_id: str) -> list[dict[str, Any]]:
    rows = query_all(
        connection,
        """
        SELECT actor, event_type, message_text, payload_json, created_at
        FROM task_events
        WHERE task_id = ?
        ORDER BY rowid ASC
        """,
        (task_id,),
    )
    events: list[dict[str, Any]] = []
    seen: set[tuple[str, str, str]] = set()
    for row in rows:
        payload = parse_json_object(row.pop("payload_json", None))
        content = resolve_event_content(row, payload)
        if not content:
            continue
        kind = classify_event(row["actor"], row["event_type"])
        dedupe_key = (str(row["created_at"]), kind, content)
        if dedupe_key in seen:
            continue
        seen.add(dedupe_key)
        events.append(
            {
                "actor": row["actor"],
                "event_type": row["event_type"],
                "kind": kind,
                "content": content,
                "detail": resolve_event_detail(row, payload),
                "created_at": row["created_at"],
            }
        )
    return events


def classify_event(actor: str, event_type: str) -> str:
    if actor == "user" or event_type in {"user_message", "user_interaction"}:
        return "user"
    if event_type == "runtime_error_detected":
        return "error"
    if event_type.startswith("build_stage") or event_type in {"task_status", "task_succeeded", "task_failed"}:
        return "status"
    if actor == "assistant" or event_type in {"assistant_message", "agent_raw_output"}:
        return "assistant"
    return "system"


def resolve_event_content(row: dict[str, Any], payload: dict[str, Any]) -> str:
    event_type = str(row.get("event_type") or "")
    message_text = str(row.get("message_text") or "").strip()
    if event_type == "agent_raw_output":
        raw_output = str(payload.get("raw_output_text") or message_text)
        parsed = parse_json_object(raw_output)
        return compact_text(
            str(parsed.get("message") or parsed.get("summary") or parsed.get("effective_user_prompt") or raw_output),
            1200,
        )
    if event_type == "assistant_message":
        return compact_text(str(payload.get("message") or message_text), 1200)
    if event_type == "task_status":
        return compact_text(str(payload.get("message") or message_text or payload.get("status") or ""), 800)
    if event_type == "runtime_error_detected":
        return compact_text(str(payload.get("summary") or message_text or payload.get("error_message") or ""), 1000)
    return compact_text(message_text, 1200)


def resolve_event_detail(row: dict[str, Any], payload: dict[str, Any]) -> str:
    parts = [str(row.get("event_type") or "")]
    for key in ("status", "tool", "mode", "request_scope", "stage", "phase", "package_name", "model"):
        value = payload.get(key)
        if value not in (None, ""):
            parts.append(f"{key}={value}")
    return " · ".join(parts)


def query_one(connection: sqlite3.Connection, sql: str, params: tuple[Any, ...] = ()) -> dict[str, Any]:
    row = connection.execute(sql, params).fetchone()
    return normalize_row(row) if row else {}


def query_all(connection: sqlite3.Connection, sql: str, params: tuple[Any, ...] = ()) -> list[dict[str, Any]]:
    return [normalize_row(row) for row in connection.execute(sql, params).fetchall()]


def normalize_row(row: sqlite3.Row) -> dict[str, Any]:
    return {key: normalize_value(row[key]) for key in row.keys()}


def normalize_value(value: Any) -> Any:
    if value is None:
        return 0
    return value


def parse_json_object(value: Any) -> dict[str, Any]:
    if not isinstance(value, str) or not value.strip():
        return {}
    try:
        payload = json.loads(value)
    except json.JSONDecodeError:
        return {}
    return payload if isinstance(payload, dict) else {}


def compact_text(value: str, limit: int) -> str:
    text = " ".join(value.split())
    if len(text) <= limit:
        return text
    return f"{text[: max(0, limit - 1)]}…"
