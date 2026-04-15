# AGENTS.md - Server / Orchestrator

## Scope

This directory contains the backend control plane for the app-generation system.
Its job is to run the service, not to act as the generated app itself.

Primary code in scope:
- `server.py`
- `vibe_factory.py`
- future chat/session orchestration modules
- future policy / function-calling / build orchestration modules

This server must do two different jobs cleanly:
1. product-runtime work through the general API
2. developer code changes in this repo through Codex

Do not mix those responsibilities.

---

## Ground truth from the current codebase

The current server already:
- stores task state in SQLite `tasks` and token usage in `token_logs`
- exposes `/generate`, `/refine`, `/crash`, `/status/{task_id}`, `/download/{task_id}`
- launches background worker flows for generation, refinement, and runtime self-healing
- uses structured multi-agent roles in `vibe_factory.py`
- depends on strict JSON-only agent outputs

Preserve those ideas unless a change explicitly replaces them with a better, coordinated design.

---

## Core architecture rule

This repo is edited by Codex, but the running service logic must remain controlled by the normal API layer.

That means:
- Codex may modify backend code in this repo when asked by the developer
- production request understanding must be handled by the normal API flow
- user clarification, policy checking, build planning, build review, and build repair decisions must be made by the server's normal model/API orchestration
- Codex must not become the runtime brain of the service

Codex is a development tool for this codebase.
The general API is the runtime brain for user-facing behavior.

---

## Main product behavior

The host app is moving to a chat-first flow.
The server must support a conversational pre-build stage before generation starts.

Expected lifecycle:
1. receive a user message from the host app
2. determine whether the request is clear enough to proceed
3. if unclear, ask focused clarification questions
4. if unsafe or disallowed, reject or narrow the request
5. once clear enough, create a structured build spec
6. run the build/review/debug loop against a BaseProject-derived workspace
7. expose progress, logs, and final artifact state back to the host app

Do not assume every first user message should trigger an immediate build.

---

## Non-negotiable runtime boundaries

### General API owns runtime request understanding
The normal API layer must own:
- user intent interpretation
- ambiguity detection
- clarification questions
- refusal / narrowing for unsafe requests
- build spec creation
- function-calling decisions
- review pass/fail decisions
- runtime workflow state transitions

### Codex owns developer-requested code changes in this repo
Codex may be used to:
- refactor server modules
- add session/chat endpoints
- add function-calling support
- improve DB code
- improve task orchestration
- add tests and tooling

Codex should not be inserted as the production request interpreter for end-user app creation.

---

## API and persistence rules

The current server persists state in SQLite.
Preserve task and token tracking unless migration is deliberate and coordinated.

Current task fields include at least:
- `task_id`
- `status`
- `app_name`
- `log`
- `apk_path`
- `apk_url`
- `project_path`
- `device_info`
- `package_name`
- `attempts`

Token logging is also part of the current design.
Do not remove token logging hooks casually.

If you change schema or payloads:
- keep backward compatibility where possible
- write migrations deliberately
- update host-app assumptions together

---

## Endpoint rules

Current public endpoints include:
- `POST /generate`
- `POST /refine`
- `POST /crash`
- `GET /status/{task_id}`
- `GET /download/{task_id}`

These should continue to work unless an intentional API redesign is underway.
If chat-first endpoints are added, do not casually break the existing ones.

If new endpoints are introduced, prefer a shape such as:
- `POST /chat/start`
- `POST /chat/message`
- `POST /build/confirm`
- `POST /build/refine`

But preserve existing flows during transition.

---

## Function-calling rule

The running service must manage build orchestration through structured function calling or tool calling.
Do not rely on free-form prose-only planning for executable actions.

Runtime build orchestration should use explicit tools/functions for actions such as:
- analyze user request
- ask clarifying question
- reject request
- create build spec
- prepare workspace
- read project snapshot
- generate files
- review generated output
- run flutter pub get
- run flutter analyze
- run flutter build
- apply minimal fix
- finalize artifact

When output is intended to trigger server actions, it must be machine-readable.

---

## Multi-agent rules

The current design already distinguishes multiple roles such as Planner, Engineer, Reviewer, Debugger, and Refiner.
Preserve this separation of concerns even if the internal implementation changes.

Rules:
- Planner creates structure, not raw file patches
- Engineer produces implementation artifacts
- Reviewer validates hard constraints and approval criteria
- Debugger makes the smallest viable fix
- Refiner updates an existing app surgically

Do not collapse everything into one vague prompt unless that redesign is intentional and verified.

---

## Strict structured-output rules

The current code depends on strict JSON-only outputs from agent steps.
This must remain true for runtime orchestration.

Rules:
- preserve strict JSON contracts where downstream parsing expects JSON
- validate model output before applying side effects
- do not inject explanatory prose into machine-parsed fields
- fail clearly if schema validation fails
- prefer schemas and typed parsing to brittle ad hoc parsing

---

## Chat clarification rules

Before starting a build, the server must decide whether clarification is needed.

Clarify when key requirements are missing or ambiguous, including:
- main app purpose
- target user flow
- required screens
- persistence model
- auth/account needs
- online vs offline behavior
- payment handling
- admin/moderation expectations
- security-sensitive capabilities
- sensor/device integrations

Ask the minimum number of high-value questions needed to proceed safely.
Do not bombard the user with unnecessary questions.

Preferred behavior:
- summarize current understanding
- ask 1 to 3 focused follow-ups
- offer concrete options where useful
- only start build preparation once requirements are sufficiently concrete

---

## Safety and policy rules

Do not build or assist runtime planning for apps that facilitate:
- credential theft
- spyware or covert monitoring
- secret recording or tracking
- unauthorized access
- stealth persistence or privilege abuse
- exfiltration of private data without informed consent

If a request is partially legitimate but includes unsafe behavior:
- reject the unsafe part
- narrow to a safe alternative
- proceed only if the remaining request is explicit and safe

For sensitive but potentially legitimate features, require transparency and user consent.

---

## Build/workspace rules

Generated apps must be created from `BaseProject` into isolated workspaces.
Do not build directly inside the template.

Rules:
- never write outside the active workspace
- preserve path safety
- avoid path traversal risks
- keep per-task isolation
- verify before reporting success
- prefer small coherent edits over destructive rewrites

Preferred runtime loop:
1. prepare workspace from BaseProject
2. apply generated files or refinements
3. run `flutter pub get`
4. run `flutter analyze`
5. run `flutter build`
6. if failure, invoke the debugger/fix path through the normal API loop
7. retry with bounded attempts
8. only mark success after actual verification

---

## Runtime self-healing rules

The server already accepts crash reports and triggers a self-healing path.
Preserve that capability.

Rules:
- runtime fixes must be minimal and surgical
- use task state to identify the affected project
- do not lose `project_path` / `package_name` linkage
- do not claim recovery until rebuild verification succeeds
- log root cause and repair attempts clearly

Important consistency rule:
- helper signatures must match across `server.py` and `vibe_factory.py`
- do not reintroduce mismatches like passing extra arguments to `get_current_project_snapshot`

The task row, not the snapshot function signature, is the source of truth for project identity.

---

## Logging and status rules

The host app reads human-visible status and log text.
Keep logs readable and statuses explicit.

Preserve semantics for fields such as:
- `task_id`
- `status`
- `log`
- `apk_url`
- `package_name`

Recommended statuses include:
- `Clarifying`
- `ReadyToBuild`
- `Processing`
- `Reviewing`
- `Repairing`
- `Success`
- `Failed`
- `Error`
- `Rejected`

If you add statuses, make them user-facing and unambiguous.

---

## Host-app compatibility rules

The Android host app currently:
- sends a prompt and device info
- polls task status
- downloads APKs from `apk_url`
- sends refinement feedback using `task_id`
- reports runtime crashes with `task_id`, `package_name`, and `stack_trace`

Do not casually rename or remove those fields.
If chat-first flows are introduced, keep compatibility in mind while migrating the app.

---

## Code quality rules

- keep request handlers thin
- keep background work in worker/orchestrator modules
- prefer explicit helper functions over monolithic prompt blobs
- preserve or improve error transparency
- validate model outputs before side effects
- preserve task persistence around major state transitions
- keep logs useful for both the user and debugging

Do not silently swallow orchestration failures.

---

## Completion checklist

Before finishing changes in this repo, verify:
1. runtime request understanding still belongs to the normal API layer
2. Codex is not being turned into the runtime decision-maker
3. chat clarification is supported for ambiguous requests
4. unsafe requests are rejected or narrowed safely
5. function/tool calling governs build actions
6. structured JSON contracts remain machine-readable
7. task and token persistence still work
8. self-healing still maps crashes back to the correct project
9. endpoint compatibility with the host app remains intact or is deliberately migrated
10. success is only returned after real verification

---

## Do not do

- do not let Codex become the production request-understanding layer
- do not start builds immediately when clarification is required
- do not break `/generate`, `/refine`, `/crash`, `/status/{task_id}`, `/download/{task_id}` casually
- do not remove token logging without deliberate replacement
- do not hide build failures behind success states
- do not write outside the intended workspace
- do not reintroduce helper signature mismatches
