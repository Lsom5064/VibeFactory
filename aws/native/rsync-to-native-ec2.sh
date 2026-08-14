#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SSH_KEY="${SSH_KEY:-$ROOT_DIR/aws/vibeFactory.pem}"
SSH_HOST="${SSH_HOST:?Set SSH_HOST, for example ubuntu@203.0.113.10}"
REMOTE_ROOT="${REMOTE_ROOT:-/opt/vibefactory-native}"
REMOTE_DATA_ROOT="${REMOTE_DATA_ROOT:-/srv/vibefactory-native}"

if [[ ! -f "$SSH_KEY" ]]; then
  echo "SSH key not found: $SSH_KEY" >&2
  exit 1
fi

ssh -i "$SSH_KEY" "$SSH_HOST" \
  "sudo mkdir -p '$REMOTE_ROOT' '$REMOTE_DATA_ROOT/native_workspaces' '$REMOTE_DATA_ROOT/.native_tooling' && sudo chown -R ubuntu:ubuntu '$REMOTE_ROOT' '$REMOTE_DATA_ROOT'"

rsync -az --progress \
  -e "ssh -i $SSH_KEY" \
  --exclude '.git/' \
  --exclude '.DS_Store' \
  --exclude '.mypy_cache/' \
  --exclude '.pytest_cache/' \
  --exclude 'debug_workspaces/' \
  --exclude 'exports/' \
  --exclude 'aws/downloads/' \
  --exclude 'aws/*.pem' \
  --exclude 'aws/*.key' \
  --exclude 'aws/*.env' \
  --exclude 'flutter_apk_server/*.db*' \
  --exclude 'flutter_apk_server/workspaces/' \
  --exclude 'flutter_apk_server/native_workspaces/' \
  --exclude 'flutter_apk_server/profiles/' \
  --exclude 'flutter_apk_server/.venv/' \
  --exclude 'flutter_apk_server/.tooling/' \
  --exclude '**/__pycache__/' \
  --exclude '**/.dart_tool/' \
  --exclude '**/.gradle/' \
  --exclude '**/.native_tooling/' \
  --exclude '**/build/' \
  --exclude '**/local.properties' \
  "$ROOT_DIR/" "$SSH_HOST:$REMOTE_ROOT/"

echo "Copied Native Android service source to $SSH_HOST:$REMOTE_ROOT"
