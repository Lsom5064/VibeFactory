#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DB_PATH="${DB_PATH:-$ROOT_DIR/flutter_apk_server/tasks.db}"
WORKSPACES_ROOT="${WORKSPACES_ROOT:-$ROOT_DIR/flutter_apk_server/workspaces}"
INDEX_ROOT="${INDEX_ROOT:-$ROOT_DIR/aws/downloads/workspace_archive_index}"

NAS_HOST="${NAS_HOST:-192.168.0.15}"
NAS_PORT="${NAS_PORT:-22}"
NAS_USER="${NAS_USER:-hailab}"
NAS_SSH_KEY="${NAS_SSH_KEY:-$HOME/.ssh/id_rsa}"
NAS_ROOT="${NAS_ROOT:-/volume1/vibefactory-archive/local-workspaces}"
NAS_RSYNC_PATH="${NAS_RSYNC_PATH:-/usr/bin/rsync}"

RUN_ID="${RUN_ID:-$(date -u +%Y%m%dT%H%M%SZ)}"
RUN_DIR="$INDEX_ROOT/$RUN_ID"
TARGETS_FILE="$RUN_DIR/targets.tsv"
RESULTS_FILE="$RUN_DIR/results.tsv"
LOCK_DIR="$INDEX_ROOT/.archive.lock"

SSH_OPTIONS=(
  -i "$NAS_SSH_KEY"
  -o IdentitiesOnly=yes
  -o BatchMode=yes
  -o StrictHostKeyChecking=yes
  -p "$NAS_PORT"
)
SSH_TRANSPORT="ssh -i $NAS_SSH_KEY -o IdentitiesOnly=yes -o BatchMode=yes -o StrictHostKeyChecking=yes -p $NAS_PORT"

EXCLUDES=(
  --exclude=build/
  --exclude=.tooling/
  --exclude=.gradle/
  --exclude=.dart_tool/
  --exclude=.kotlin/
  --exclude=__pycache__/
)

fail() {
  echo "ERROR: $*" >&2
  exit 1
}

require_command() {
  command -v "$1" >/dev/null 2>&1 || fail "Required command not found: $1"
}

remote() {
  ssh "${SSH_OPTIONS[@]}" "$NAS_USER@$NAS_HOST" "$@"
}

is_safe_remote_root() {
  [[ "$NAS_ROOT" =~ ^/volume[0-9]+/[A-Za-z0-9._/-]+$ ]]
}

write_targets() {
  sqlite3 -tabs "$DB_PATH" "
    SELECT task_id, status, updated_at, workspace_path
    FROM tasks
    WHERE datetime(updated_at) <= datetime('now', '-2 days')
      AND lower(coalesce(status, '')) NOT IN (
        'queued', 'running', 'pending decision', 'pending_decision'
      )
    ORDER BY datetime(updated_at), task_id;
  " > "$RUN_DIR/eligible.tsv"

  : > "$TARGETS_FILE"
  : > "$RUN_DIR/skipped.tsv"

  while IFS=$'\t' read -r task_id task_status updated_at workspace_dir; do
    if [[ -z "$workspace_dir" || ! -d "$workspace_dir" ]]; then
      discovered_count="$(
        find "$WORKSPACES_ROOT" \
          -mindepth 2 -maxdepth 2 \
          -type d -name "task_$task_id" -print | wc -l | tr -d ' '
      )"
      if [[ "$discovered_count" == "1" ]]; then
        workspace_dir="$(
          find "$WORKSPACES_ROOT" \
            -mindepth 2 -maxdepth 2 \
            -type d -name "task_$task_id" -print
        )"
      elif [[ "$discovered_count" -gt "1" ]]; then
        printf '%s\t%s\t%s\t%s\tambiguous-physical-path\n' \
          "$task_id" "$task_status" "$updated_at" "$workspace_dir" \
          >> "$RUN_DIR/skipped.tsv"
        continue
      fi
    fi

    if [[ -z "$workspace_dir" || ! -d "$workspace_dir" ]]; then
      printf '%s\t%s\t%s\t%s\tmissing\n' \
        "$task_id" "$task_status" "$updated_at" "$workspace_dir" \
        >> "$RUN_DIR/skipped.tsv"
      continue
    fi

    case "$workspace_dir" in
      "$WORKSPACES_ROOT"/*/task_"$task_id")
        printf '%s\t%s\t%s\t%s\n' \
          "$task_id" "$task_status" "$updated_at" "$workspace_dir" \
          >> "$TARGETS_FILE"
        ;;
      *)
        printf '%s\t%s\t%s\t%s\tunsafe-path\n' \
          "$task_id" "$task_status" "$updated_at" "$workspace_dir" \
          >> "$RUN_DIR/skipped.tsv"
        ;;
    esac
  done < "$RUN_DIR/eligible.tsv"
}

write_file_inventory() {
  local workspace_dir="$1"
  local metadata_dir="$2"
  local apk_path apk_hash canonical_path

  (
    cd "$workspace_dir"
    find . \
      -type d \( \
        -name build -o \
        -name .tooling -o \
        -name .gradle -o \
        -name .dart_tool -o \
        -name .kotlin -o \
        -name __pycache__ \
      \) -prune -o -type f -print
  ) | LC_ALL=C sort -u > "$metadata_dir/NON_CACHE_FILES.txt"

  (
    cd "$workspace_dir"
    find . -type f -name '*.apk' -print | LC_ALL=C sort -u
  ) > "$metadata_dir/APK_FILES.txt"

  cat "$metadata_dir/NON_CACHE_FILES.txt" "$metadata_dir/APK_FILES.txt" \
    | LC_ALL=C sort -u > "$metadata_dir/FILES.txt"

  : > "$metadata_dir/APK_CANONICALS.tsv"
  : > "$metadata_dir/APK_TRANSFER_FILES.txt"
  : > "$metadata_dir/APK_ALIASES.tsv"

  while IFS= read -r apk_path; do
    [[ -n "$apk_path" ]] || continue
    apk_hash="$(cd "$workspace_dir" && shasum -a 256 "$apk_path" | cut -d ' ' -f1)"
    canonical_path="$(
      awk -F '\t' -v hash="$apk_hash" '$1 == hash { print $2; exit }' \
        "$metadata_dir/APK_CANONICALS.tsv"
    )"

    if [[ -n "$canonical_path" ]]; then
      printf '%s\t%s\n' "$apk_path" "$canonical_path" \
        >> "$metadata_dir/APK_ALIASES.tsv"
      continue
    fi

    printf '%s\t%s\n' "$apk_hash" "$apk_path" \
      >> "$metadata_dir/APK_CANONICALS.tsv"
    if ! grep -Fqx "$apk_path" "$metadata_dir/NON_CACHE_FILES.txt"; then
      printf '%s\n' "$apk_path" >> "$metadata_dir/APK_TRANSFER_FILES.txt"
    fi
  done < "$metadata_dir/APK_FILES.txt"

  (
    cd "$workspace_dir"
    while IFS= read -r relative_path; do
      [[ -f "$relative_path" ]] || continue
      shasum -a 256 "$relative_path"
    done < "$metadata_dir/FILES.txt"
  ) > "$metadata_dir/SHA256SUMS"

  cat > "$metadata_dir/RECREATE_APK_LINKS.sh" <<'EOF'
#!/bin/sh
set -eu
cd "$(dirname "$0")/../workspace"
while IFS="$(printf '\t')" read -r alias_path canonical_path; do
  [ -n "$alias_path" ] || continue
  mkdir -p "$(dirname "$alias_path")"
  rm -f "$alias_path"
  ln "$canonical_path" "$alias_path"
done < ../_archive_meta/APK_ALIASES.tsv
EOF
}

write_task_metadata() {
  local task_id="$1"
  local task_status="$2"
  local updated_at="$3"
  local workspace_dir="$4"
  local owner_dir="$5"
  local metadata_dir="$6"
  local file_count archive_bytes apk_count unique_apk_count duplicate_apk_count

  file_count="$(wc -l < "$metadata_dir/FILES.txt" | tr -d ' ')"
  archive_bytes="$(
    cd "$workspace_dir"
    while IFS= read -r relative_path; do
      [[ -f "$relative_path" ]] || continue
      stat -f '%z' "$relative_path"
    done < "$metadata_dir/FILES.txt" | awk '{ total += $1 } END { print total + 0 }'
  )"
  apk_count="$(wc -l < "$metadata_dir/APK_FILES.txt" | tr -d ' ')"
  unique_apk_count="$(wc -l < "$metadata_dir/APK_CANONICALS.tsv" | tr -d ' ')"
  duplicate_apk_count="$(wc -l < "$metadata_dir/APK_ALIASES.tsv" | tr -d ' ')"

  sqlite3 -json "$DB_PATH" \
    "SELECT * FROM tasks WHERE task_id = '$task_id';" \
    > "$metadata_dir/task_db_row.json"

  jq -n \
    --arg schema_version "1" \
    --arg archived_at "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
    --arg task_id "$task_id" \
    --arg status "$task_status" \
    --arg updated_at "$updated_at" \
    --arg owner_dir "$owner_dir" \
    --arg original_workspace_path "$workspace_dir" \
    --arg nas_path "$NAS_ROOT/$owner_dir/task_$task_id" \
    --argjson file_count "$file_count" \
    --argjson archive_bytes "$archive_bytes" \
    --argjson apk_count "$apk_count" \
    --argjson unique_apk_count "$unique_apk_count" \
    --argjson duplicate_apk_count "$duplicate_apk_count" \
    '{
      schema_version: $schema_version,
      archived_at: $archived_at,
      task_id: $task_id,
      status: $status,
      updated_at: $updated_at,
      owner_dir: $owner_dir,
      original_workspace_path: $original_workspace_path,
      nas_path: $nas_path,
      file_count: $file_count,
      archive_bytes: $archive_bytes,
      apk_count: $apk_count,
      unique_apk_count: $unique_apk_count,
      duplicate_apk_count: $duplicate_apk_count,
      excluded_directory_names: [
        "build", ".tooling", ".gradle", ".dart_tool", ".kotlin", "__pycache__"
      ],
      apk_policy: "All APK paths are retained. Byte-identical APKs are stored as NAS hard links."
    }' > "$metadata_dir/archive_manifest.json"

  cat > "$metadata_dir/RESTORE.txt" <<EOF
Restore this workspace from the NAS to its original location:

mkdir -p '$(dirname "$workspace_dir")'
rsync -a --progress \\
  --rsync-path='$NAS_RSYNC_PATH' \\
  -e '$SSH_TRANSPORT' \\
  '$NAS_USER@$NAS_HOST:$NAS_ROOT/$owner_dir/task_$task_id/workspace/' \\
  '$workspace_dir/'

The Flutter/Gradle caches were intentionally excluded. Recreate them with:
flutter pub get
flutter build apk
EOF
}

still_safe_to_delete() {
  local task_id="$1"
  local expected_updated_at="$2"
  local eligible

  eligible="$(sqlite3 "$DB_PATH" "
    SELECT count(*)
    FROM tasks
    WHERE task_id = '$task_id'
      AND updated_at = '$expected_updated_at'
      AND datetime(updated_at) <= datetime('now', '-2 days')
      AND lower(coalesce(status, '')) NOT IN (
        'queued', 'running', 'pending decision', 'pending_decision'
      );
  ")"
  [[ "$eligible" == "1" ]]
}

archive_task() {
  local task_id="$1"
  local task_status="$2"
  local updated_at="$3"
  local workspace_dir="$4"
  local owner_dir task_index_dir remote_incoming remote_final
  local remote_checksum local_checksum

  owner_dir="$(basename "$(dirname "$workspace_dir")")"
  [[ "$owner_dir" =~ ^[A-Za-z0-9._-]+$ ]] || return 11
  [[ "$task_id" =~ ^[0-9a-fA-F]{32}$ ]] || return 12

  task_index_dir="$RUN_DIR/tasks/$task_id"
  remote_incoming="$NAS_ROOT/.incoming/$owner_dir/task_$task_id"
  remote_final="$NAS_ROOT/$owner_dir/task_$task_id"
  mkdir -p "$task_index_dir"

  echo "[$task_id] Building cache-excluded inventory..."
  write_file_inventory "$workspace_dir" "$task_index_dir"
  write_task_metadata \
    "$task_id" "$task_status" "$updated_at" "$workspace_dir" \
    "$owner_dir" "$task_index_dir"

  if remote "test -e '$remote_final'"; then
    echo "[$task_id] Final NAS path already exists; verifying it instead of overwriting."
    remote_checksum="$(remote "sha256sum '$remote_final/_archive_meta/SHA256SUMS' | cut -d ' ' -f1")"
    local_checksum="$(shasum -a 256 "$task_index_dir/SHA256SUMS" | cut -d ' ' -f1)"
    [[ "$remote_checksum" == "$local_checksum" ]] || return 21
    remote "if test -s '$remote_final/_archive_meta/SHA256SUMS'; then cd '$remote_final/workspace' && sha256sum -c '../_archive_meta/SHA256SUMS' >/dev/null; else test -z \"\$(find '$remote_final/workspace' -type f -print -quit)\"; fi"
  else
    remote "mkdir -p '$remote_incoming/workspace' '$remote_incoming/_archive_meta'"

    echo "[$task_id] Uploading source and non-cache artifacts..."
    rsync -a --partial --progress --delete \
      --rsync-path="$NAS_RSYNC_PATH" \
      "${EXCLUDES[@]}" \
      -e "$SSH_TRANSPORT" \
      "$workspace_dir/" \
      "$NAS_USER@$NAS_HOST:$remote_incoming/workspace/"

    echo "[$task_id] Restoring APK artifacts below excluded build directories..."
    rsync -a --partial --progress --relative \
      --files-from="$task_index_dir/APK_TRANSFER_FILES.txt" \
      --rsync-path="$NAS_RSYNC_PATH" \
      -e "$SSH_TRANSPORT" \
      "$workspace_dir/" \
      "$NAS_USER@$NAS_HOST:$remote_incoming/workspace/"

    rsync -a --progress --delete \
      --rsync-path="$NAS_RSYNC_PATH" \
      -e "$SSH_TRANSPORT" \
      "$task_index_dir/" \
      "$NAS_USER@$NAS_HOST:$remote_incoming/_archive_meta/"

    remote "sh '$remote_incoming/_archive_meta/RECREATE_APK_LINKS.sh'"

    echo "[$task_id] Verifying every retained regular file on the NAS..."
    remote "if test -s '$remote_incoming/_archive_meta/SHA256SUMS'; then cd '$remote_incoming/workspace' && sha256sum -c '../_archive_meta/SHA256SUMS' >/dev/null; else test -z \"\$(find '$remote_incoming/workspace' -type f -print -quit)\"; fi"
    remote "touch '$remote_incoming/_archive_meta/VERIFIED' && mkdir -p '$(dirname "$remote_final")' && mv '$remote_incoming' '$remote_final'"
  fi

  still_safe_to_delete "$task_id" "$updated_at" || return 31
  [[ -d "$workspace_dir" ]] || return 32
  case "$workspace_dir" in
    "$WORKSPACES_ROOT"/*/task_"$task_id") ;;
    *) return 33 ;;
  esac

  echo "[$task_id] NAS verification passed; deleting the local workspace."
  rm -rf -- "$workspace_dir"
  rmdir "$(dirname "$workspace_dir")" 2>/dev/null || true
  return 0
}

main() {
  require_command sqlite3
  require_command jq
  require_command rsync
  require_command ssh
  require_command shasum
  require_command find

  [[ -f "$DB_PATH" ]] || fail "Database not found: $DB_PATH"
  [[ -d "$WORKSPACES_ROOT" ]] || fail "Workspace root not found: $WORKSPACES_ROOT"
  [[ -f "$NAS_SSH_KEY" ]] || fail "NAS SSH key not found: $NAS_SSH_KEY"
  is_safe_remote_root || fail "Unsafe NAS_ROOT: $NAS_ROOT"

  mkdir -p "$INDEX_ROOT"
  if ! mkdir "$LOCK_DIR" 2>/dev/null; then
    fail "Another workspace archive process is already running: $LOCK_DIR"
  fi
  trap 'rmdir "$LOCK_DIR" 2>/dev/null || true' EXIT

  mkdir -p "$RUN_DIR/tasks"
  : > "$RESULTS_FILE"
  write_targets

  echo "Run: $RUN_ID"
  echo "Targets: $(wc -l < "$TARGETS_FILE" | tr -d ' ')"
  echo "Skipped before upload: $(wc -l < "$RUN_DIR/skipped.tsv" | tr -d ' ')"
  remote "mkdir -p '$NAS_ROOT/.incoming' && test -x '$NAS_RSYNC_PATH' && command -v sha256sum >/dev/null && df -h '$(dirname "$NAS_ROOT")'"

  while IFS=$'\t' read -r task_id task_status updated_at workspace_dir <&3; do
    set +e
    (
      set -Eeuo pipefail
      archive_task "$task_id" "$task_status" "$updated_at" "$workspace_dir"
    )
    result=$?
    set -e

    if (( result >= 128 )); then
      echo "Archive interrupted by signal ($result); leaving the current Task in .incoming." >&2
      exit "$result"
    fi

    if [[ "$result" == "0" ]]; then
      printf '%s\tarchived-and-deleted\t%s\t%s\n' \
        "$task_id" "$updated_at" "$workspace_dir" | tee -a "$RESULTS_FILE"
    else
      printf '%s\tfailed-%s\t%s\t%s\n' \
        "$task_id" "$result" "$updated_at" "$workspace_dir" | tee -a "$RESULTS_FILE" >&2
    fi
  done 3< "$TARGETS_FILE"

  echo
  echo "Archive run finished."
  echo "Results: $RESULTS_FILE"
  echo "Local workspace size now:"
  du -sh "$WORKSPACES_ROOT"
}

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
  main "$@"
fi
