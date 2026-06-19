# AGENTS.md

## Project Overview

This root is a multi-project workspace for an AI app-generation system.

- `flutter_apk_server/`: FastAPI backend that accepts app-generation requests, tracks tasks, stores logs and task state, and builds Flutter APKs.
- `vibefactory/`: Android host app that sends requests to the server, shows task progress, polls build status, displays notifications, and receives runtime error reports from generated apps.
- `BaseProject/`: Flutter template project used as the starting point for generated apps.

## File Safety Rules

- Do not delete files or folders unless the user explicitly asks for deletion.
- Do not remove runtime data such as `flutter_apk_server/tasks.db`, `flutter_apk_server/workspaces/`, or `flutter_apk_server/profiles/` unless the user explicitly asks.
- Do not use destructive Git commands such as `git reset --hard`, `git checkout --`, or history-rewriting operations unless the user explicitly asks.
- If a cleanup looks useful, propose it first instead of doing it directly.

## Codex Working Guidance

- Inspect the existing code before making assumptions about architecture or behavior.
- Treat `flutter_apk_server`, `vibefactory`, and `BaseProject` as one connected system. Changes in one project may require matching changes in another.
- Preserve existing user data and local runtime artifacts unless the task explicitly requires changing them.
- Prefer targeted searches with `rg` and avoid broad workspace-wide scans unless necessary.
- When editing, make the smallest change that fully solves the problem and verify it when practical.
- If Git state and filesystem contents appear inconsistent, prioritize protecting the filesystem contents and inspect carefully before switching branches or rewriting anything.
