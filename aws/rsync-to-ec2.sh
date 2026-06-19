#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SSH_KEY="${SSH_KEY:-$ROOT_DIR/aws/vibeFactory.pem}"
SSH_HOST="${SSH_HOST:-ubuntu@15.165.191.202}"
REMOTE_ROOT="${REMOTE_ROOT:-/opt/vibefactory}"
REMOTE_DATA_ROOT="${REMOTE_DATA_ROOT:-/srv/vibefactory}"

if [ ! -f "$SSH_KEY" ]; then
  echo "SSH key not found: $SSH_KEY" >&2
  exit 1
fi

ssh -i "$SSH_KEY" "$SSH_HOST" \
  "sudo mkdir -p '$REMOTE_ROOT' '$REMOTE_DATA_ROOT/workspaces' '$REMOTE_DATA_ROOT/profiles' && sudo chown -R ubuntu:ubuntu '$REMOTE_ROOT' '$REMOTE_DATA_ROOT'"

rsync -az --progress \
  -e "ssh -i $SSH_KEY" \
  --exclude '.git/' \
  --exclude '.DS_Store' \
  --exclude 'aws/*.pem' \
  --exclude 'aws/*.key' \
  --exclude 'aws/*.env' \
  --exclude 'flutter_apk_server/tasks.db' \
  --exclude 'flutter_apk_server/workspaces/' \
  --exclude 'flutter_apk_server/profiles/' \
  --exclude 'flutter_apk_server/.venv/' \
  --exclude '**/.dart_tool/' \
  --exclude '**/.gradle/' \
  --exclude '**/build/' \
  --exclude '**/local.properties' \
  "$ROOT_DIR/" "$SSH_HOST:$REMOTE_ROOT/"

echo "Copied project to $SSH_HOST:$REMOTE_ROOT"
