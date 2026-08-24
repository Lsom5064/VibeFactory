# Project Instructions

- Keep this repository focused on a minimal FastAPI server for native Android APK generation tasks.
- Do not copy an older multi-agent server architecture into this project.
- Prefer small, auditable changes over framework-heavy abstractions.
- Treat `.codex_result/task_result.json` as the task contract, but always validate it server-side.
- Keep generated project writes inside the configured `WORKSPACES_ROOT`
  (`native_workspaces/` by default).
- Support `MOCK_CODEX=1` so local development and tests do not require a real Codex CLI, Android SDK, or signing key.
- Avoid logging secrets, API keys, or authentication material.
