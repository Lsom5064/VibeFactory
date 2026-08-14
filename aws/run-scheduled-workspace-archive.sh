#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ARCHIVE_SCRIPT="$ROOT_DIR/aws/archive-local-workspaces-to-nas.sh"
DB_PATH="${DB_PATH:-$ROOT_DIR/flutter_apk_server/tasks.db}"
WORKSPACES_ROOT="${WORKSPACES_ROOT:-$ROOT_DIR/flutter_apk_server/workspaces}"
STATE_ROOT="$ROOT_DIR/aws/downloads/workspace_archive_index"
START_AT="${ARCHIVE_START_AT:-2026-08-12 22:05:00}"
LOG_FILE="$STATE_ROOT/scheduled-20260812-2205.log"
MARKER_FILE="$STATE_ROOT/scheduled-20260812-2205.result"

mkdir -p "$STATE_ROOT"

target_epoch="$(date -j -f '%Y-%m-%d %H:%M:%S' "$START_AT" '+%s')"
now_epoch="$(date '+%s')"
if (( now_epoch < target_epoch )); then
  sleep "$((target_epoch - now_epoch))"
fi

{
  echo "[$(date '+%Y-%m-%d %H:%M:%S %Z')] Scheduled workspace archive started."

  for attempt in 1 2 3; do
    run_id="scheduled_$(date -u '+%Y%m%dT%H%M%SZ')_attempt${attempt}"
    echo "[$(date '+%Y-%m-%d %H:%M:%S %Z')] Attempt $attempt: $run_id"
    RUN_ID="$run_id" "$ARCHIVE_SCRIPT" || true

    remaining="$(sqlite3 "$DB_PATH" "
      SELECT count(*)
      FROM tasks
      WHERE datetime(updated_at) <= datetime('now', '-2 days')
        AND lower(coalesce(status, '')) NOT IN (
          'queued', 'running', 'pending decision', 'pending_decision'
        )
        AND workspace_path IS NOT NULL;
    ")"

    physical_remaining=0
    while IFS= read -r workspace_dir; do
      [[ -n "$workspace_dir" && -d "$workspace_dir" ]] || continue
      physical_remaining=$((physical_remaining + 1))
    done < <(sqlite3 -noheader "$DB_PATH" "
      SELECT workspace_path
      FROM tasks
      WHERE datetime(updated_at) <= datetime('now', '-2 days')
        AND lower(coalesce(status, '')) NOT IN (
          'queued', 'running', 'pending decision', 'pending_decision'
        )
        AND workspace_path IS NOT NULL;
    ")

    echo "[$(date '+%Y-%m-%d %H:%M:%S %Z')] DB candidates: $remaining; physical remaining: $physical_remaining"
    if (( physical_remaining == 0 )); then
      printf 'completed\t%s\n' "$(date '+%Y-%m-%d %H:%M:%S %Z')" > "$MARKER_FILE"
      echo "Scheduled workspace archive completed."
      exit 0
    fi

    sleep 60
  done

  printf 'incomplete\t%s\n' "$(date '+%Y-%m-%d %H:%M:%S %Z')" > "$MARKER_FILE"
  echo "Scheduled workspace archive stopped with remaining workspaces."
  exit 1
} >> "$LOG_FILE" 2>&1
