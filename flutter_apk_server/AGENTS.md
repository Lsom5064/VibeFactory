# Project Instructions

- Keep this repository focused on a minimal FastAPI server for Flutter Android APK generation tasks.
- Do not copy an older multi-agent server architecture into this project.
- Prefer small, auditable changes over framework-heavy abstractions.
- Treat `.codex_result/task_result.json` as the task contract, but always validate it server-side.
- Keep workspace writes inside `workspaces/` only.
- Support `MOCK_CODEX=1` so local development and tests do not require a real Codex CLI or Flutter toolchain.
- Avoid logging secrets, API keys, or authentication material.
