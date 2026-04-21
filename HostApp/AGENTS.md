# AGENTS.md - Android Host App / vibefactory

## Scope

This directory contains the Android host application, not the generated Flutter app.
It is the operator UI that talks to the backend service and manages generated APK delivery and crash feedback.

Primary responsibilities of this app:
- present a chat-first interface to the user
- send user messages and device context to the backend
- display clarification questions, policy rejections, build progress, and results
- keep track of the active task/session state
- poll or subscribe to build status
- download and install generated APKs
- receive crash reports from generated apps and send them back to the backend

Do not confuse this app with the generated BaseProject-derived app.

---

## Ground truth from the current codebase

The current app already:
- talks to the backend using Retrofit
- stores the current `task_id`
- polls `/status/{task_id}`
- downloads an APK from `apk_url`
- exposes refinement UI
- receives a crash broadcast carrying `task_id`, `package_name`, and `stack_trace`
- forwards runtime crashes back to the server

The next evolution is to turn this into a real chat-style host app.

---

## Core product rule

This app is the conversation shell for the service.
It should not try to make backend policy or build decisions locally.

The backend general API owns:
- request understanding
- clarification decisions
- policy refusals
- build planning
- build/review/debug decisions

This Android app owns:
- rendering the conversation cleanly
- collecting user input
- maintaining session/task state in the UI
- showing progress and results
- routing crash reports back to the server

Do not move runtime product logic from the backend into the Android client.

---

## Chat-first UX rules

The app must evolve from a one-shot form into a chat interface.
That means the user should be able to:
- type an initial app idea
- receive follow-up questions from the server
- answer clarification questions in sequence
- see rejections or constraints when a request is unsafe or infeasible
- explicitly confirm when the build is ready to start
- see build progress in the same conversation timeline
- send refinement requests as follow-up chat turns

Preferred message types:
- user message
- assistant clarification question
- assistant rejection / constraint message
- assistant summary / confirmation
- system build status update
- refinement result update
- crash recovery update

The UI should read like a conversation, not like a collection of separate admin panels.

---

## Backward-compatibility rules

The current backend still uses task-based endpoints and status polling.
The app must continue to support the current server contract during migration.

Current important fields and expectations include:
- `task_id`
- `status`
- `log`
- `apk_url`
- `package_name`

Do not break compatibility casually while converting the UI to chat.
If a new chat/session protocol is introduced, migrate gracefully.

---

## State management rules

The app needs clear separation between:
- chat/session state
- active build/task state
- latest downloadable artifact state
- crash recovery state

At minimum, preserve and evolve tracking for:
- current session id if chat sessions are added
- current task id once a build starts
- latest APK URL
- latest package name if returned
- pending clarification status
- pending refinement status

Do not rely on ad hoc global UI state scattered across unrelated views.

---

## Network/API rules

The client should remain a thin transport and presentation layer.

Rules:
- keep backend API models explicit and typed
- prefer separate DTOs for chat, build, refine, status, and crash-report flows
- keep network errors visible to the user in human-readable form
- do not fake success when the server request failed
- handle connection loss gracefully during status polling

If the backend adds chat endpoints, update Retrofit models cleanly instead of overloading unrelated calls.

---

## Build-status UX rules

The app should present build progress as conversational system updates.

Examples of server states the UI should handle well:
- clarifying
- ready to build
- processing
- reviewing
- repairing
- success
- failed
- error
- rejected

Do not force users to infer what is happening from raw technical text alone.
Show concise labels, but also preserve detailed logs when available.

---

## APK download/install rules

The current host app downloads an APK from the server and triggers installation.
Preserve that capability.

Rules:
- only show install/download actions when an APK is actually available
- keep unknown-sources permission flow clear
- provide useful failure messages for download errors
- keep the latest APK URL tied to the correct task/result

Do not let stale APK links from older tasks overwrite the active result incorrectly.

---

## Crash-reporting rules

The generated apps send runtime crash information to this host app, which forwards it to the backend.
Preserve that bridge.

Rules:
- keep the crash broadcast receiver working
- preserve collection of `task_id`, `package_name`, and `stack_trace`
- make the crash UI visible but not disruptive
- let the user understand that a repair flow has started
- resume status watching after the repair request is sent

Do not remove crash forwarding unless the backend protocol changes intentionally.

---

## UI architecture rules

Prefer maintainable Android UI structure.
If refactoring, move toward a cleaner architecture, but do it incrementally.

Preferred direction:
- separate view models / state holders from raw activity glue
- keep network calls out of sprawling UI code where practical
- make message rendering components reusable
- keep polling and side effects lifecycle-aware

Do not do a giant rewrite unless explicitly requested.
Small coherent refactors are preferred.

---

## Security and privacy rules

This app collects device info and may handle crash traces.
Treat that data carefully.

Rules:
- send only what the backend actually needs
- be transparent in the UI when device info is relevant
- do not store sensitive crash or device data longer than necessary without a good reason
- do not add hidden tracking or background monitoring behavior

This app must remain a transparent user-facing controller.

---

## Developer-tooling rule

This repo may be modified by Codex during development.
That is appropriate for code changes in this app.

Codex may help with:
- converting the UI to a chat experience
- refactoring Retrofit/API layers
- improving state handling
- cleaning up `MainActivity`
- extracting reusable components
- improving crash/result UX

But the runtime product decisions still belong to the backend.
Do not hardcode backend policy logic into the app.

---

## Completion checklist

Before finishing Android host-app changes, verify:
1. the app still acts as the host/controller, not the generated app
2. chat-first UX is preserved or improved
3. backend clarification questions can be displayed clearly
4. rejections and constraints can be shown naturally in the chat timeline
5. task/session state is tracked coherently
6. status polling or equivalent progress updates still work
7. APK download/install still works
8. crash report forwarding still works with `task_id`, `package_name`, and `stack_trace`
9. backend policy/build decisions are not duplicated locally
10. network failures remain visible and understandable

---

## Do not do

- do not move backend request-understanding logic into the Android app
- do not hardcode unsafe-policy decisions locally as the primary source of truth
- do not confuse the host app with the generated Flutter app
- do not break `task_id` / `apk_url` / crash-report flows casually
- do not replace clear conversational UX with opaque status-only UI
- do not do broad rewrites when a surgical refactor will do
