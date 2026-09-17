"""Opt-in real Codex/Gradle check. Creates only an isolated task/database.

Run from the repository root with the usual server signing/SDK environment:
python -m server.tests.lint_recovery_live_check --output-root /tmp/vf-lint-check-unique
"""
import argparse
import json
from dataclasses import replace
from pathlib import Path

from server.server import (
    AppDataDatabase, CodexTaskRunner, Database, build_task_workspace,
    load_settings, utc_now_iso, write_result_json,
)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--output-root", required=True)
    args = parser.parse_args()
    root = Path(args.output_root).resolve()
    original = load_settings()
    if root == original.workspaces_root or original.workspaces_root in root.parents:
        raise SystemExit("Use an isolated output directory outside production workspaces.")
    root.mkdir(parents=True, exist_ok=False)
    settings = replace(original, workspaces_root=root / "workspaces", db_path=root / "tasks.db",
                       app_data_db_path=root / "app_data.db", mock_codex=False,
                       lint_recovery_max_attempts=2, lint_recovery_timeout_seconds=600)
    database = Database(settings.db_path)
    database.init_db()
    AppDataDatabase(settings.app_data_db_path).init_db()
    task = dict(task_id="isolated-lint-recovery-check", user_id="isolated-verification",
                device_id="isolated-verification", status="Running", message="검증 중",
                prompt="제목에 자동 복구 확인, 본문에 검증 완료를 보여 주는 화면을 유지해 줘. 화면 여백과 도움말도 유지해 줘.",
                app_name="자동 복구 확인", package_name="kr.ac.kangwon.hai.generated.lintrecoverycheck",
                created_at=utc_now_iso(), updated_at=utc_now_iso())
    database.create_task(task)
    workspace, project = build_task_workspace(settings, task)
    database.update_task(task["task_id"], workspace_path=str(workspace), project_path=str(project))
    layout = project / "app/src/main/res/layout/activity_main.xml"
    # A real Android resource-linking error that the final lint gate must detect.
    layout.write_text(layout.read_text().replace("@string/template_title", "@string/recovery_title")
                      .replace("@string/template_body", "@string/recovery_body"), encoding="utf-8")
    result_path = workspace / ".codex_result/task_result.json"
    write_result_json(result_path, {"status": "success", "task_id": task["task_id"]})
    runner = CodexTaskRunner(settings, database)
    runner.attempt_server_side_build(task["task_id"], workspace, result_path, 0)
    result = json.loads(result_path.read_text())
    summary = {"status": result.get("status"), "lint_recovery": result.get("lint_recovery"),
               "error_stage": result.get("error_stage"),
               "apk_exists": (project / "app/build/outputs/apk/release/app-release.apk").is_file()}
    (root / "verification.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(summary, ensure_ascii=False), flush=True)
    if result.get("status") != "success" or result.get("lint_recovery", {}).get("stop_reason") != "recovered":
        raise SystemExit(1)


if __name__ == "__main__":
    main()
