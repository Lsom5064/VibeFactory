"""
Codex CLI bridge for the Vibe Factory server.

Intended location:
    /Users/hai/Desktop/buildingAppwithLLMs_app/Server/codex_runner.py

This module is designed for non-interactive server automation using `codex exec`.
"""

from __future__ import annotations

import json
import os
import shutil
import subprocess
from pathlib import Path
from typing import Any, Dict, Optional


class CodexCLIError(RuntimeError):
    pass


def _which_codex() -> str:
    codex_bin = shutil.which("codex")
    if not codex_bin:
        raise CodexCLIError(
            "Codex CLI was not found on PATH. Install it first, then retry."
        )
    return codex_bin


def run_codex_exec(
    *,
    workspace: str | Path,
    prompt: str,
    schema_path: str | Path | None = None,
    profile: Optional[str] = None,
    cwd_env: Optional[Dict[str, str]] = None,
    timeout_seconds: int = 1800,
) -> Dict[str, Any]:
    """
    Run Codex in non-interactive mode inside the given workspace.

    Returns a dictionary with:
    - ok: bool
    - stdout: str
    - stderr: str
    - parsed: dict | list | None
    - returncode: int
    """
    codex_bin = _which_codex()
    workspace = str(Path(workspace).resolve())

    cmd = [codex_bin, "exec", "--json", prompt]
    if schema_path:
        cmd.extend(["--output-schema", str(Path(schema_path).resolve())])
    if profile:
        cmd.extend(["--profile", profile])

    env = os.environ.copy()
    if cwd_env:
        env.update(cwd_env)

    proc = subprocess.run(
        cmd,
        cwd=workspace,
        env=env,
        capture_output=True,
        text=True,
        timeout=timeout_seconds,
    )

    stdout = proc.stdout or ""
    stderr = proc.stderr or ""
    parsed = None

    if stdout.strip():
        try:
            parsed = json.loads(stdout)
        except json.JSONDecodeError:
            parsed = None

    return {
        "ok": proc.returncode == 0,
        "stdout": stdout,
        "stderr": stderr,
        "parsed": parsed,
        "returncode": proc.returncode,
        "command": cmd,
        "workspace": workspace,
    }


def run_codex_build_fix_loop(
    *,
    workspace: str | Path,
    build_error: str,
    app_goal: str,
    extra_constraints: Optional[list[str]] = None,
    profile: Optional[str] = None,
    timeout_seconds: int = 1800,
) -> Dict[str, Any]:
    """
    Ask Codex to make a minimal build-fix patch inside the workspace.
    """
    constraints = extra_constraints or []

    prompt = f"""
You are repairing a Flutter workspace.

Goal:
{app_goal}

Current build failure:
{build_error}

Constraints:
- Work only inside the current repository
- Make the smallest coherent fix
- Preserve CrashHandler initialization
- Do not overwrite lib/crash_handler.dart
- Preserve Material 3 and layout safety
- Prefer Flutter SDK APIs
- Do not add unnecessary dependencies

When done:
- ensure the repository is left with your file edits on disk
- print strict JSON to stdout in the format:
{{
  "status": "patched" | "no_change" | "failed",
  "summary": "short summary",
  "files_touched": ["relative/path1", "relative/path2"]
}}
""".strip()

    if constraints:
        prompt += "\nAdditional constraints:\n" + "\n".join(f"- {x}" for x in constraints)

    return run_codex_exec(
        workspace=workspace,
        prompt=prompt,
        schema_path=None,
        profile=profile,
        timeout_seconds=timeout_seconds,
    )
