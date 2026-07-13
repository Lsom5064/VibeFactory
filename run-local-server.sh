#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SERVER_DIR="$ROOT_DIR/flutter_apk_server"
VENV_DIR="$SERVER_DIR/.venv"
PYTHON_BIN="${PYTHON_BIN:-python3}"
HOST="${HOST:-0.0.0.0}"
PORT="${PORT:-8000}"

detect_local_ip() {
  local ip
  ip="$(ipconfig getifaddr en0 2>/dev/null || true)"
  if [[ -n "$ip" ]]; then
    printf '%s\n' "$ip"
    return
  fi
  if command -v ifconfig >/dev/null 2>&1; then
    ifconfig | awk '/inet / && $2 != "127.0.0.1" {print $2; exit}'
  fi
}

detect_codex_user() {
  local auth_file="$HOME/.codex/auth.json"
  if [[ ! -f "$auth_file" ]]; then
    printf 'not logged in\n'
    return
  fi
  "$PYTHON_BIN" - "$auth_file" <<'PY' 2>/dev/null || printf 'unknown\n'
import base64
import json
import sys

path = sys.argv[1]
with open(path, "r", encoding="utf-8") as handle:
    auth = json.load(handle)
tokens = auth.get("tokens") if isinstance(auth.get("tokens"), dict) else {}
payload = {}
id_token = tokens.get("id_token")
if isinstance(id_token, str) and id_token.count(".") >= 2:
    body = id_token.split(".")[1]
    body += "=" * (-len(body) % 4)
    try:
        payload = json.loads(base64.urlsafe_b64decode(body.encode("utf-8")))
    except Exception:
        payload = {}
for key in ("email", "name", "preferred_username"):
    value = payload.get(key)
    if isinstance(value, str) and value.strip():
        print(value.strip())
        raise SystemExit
account_id = tokens.get("account_id")
if isinstance(account_id, str) and account_id.strip():
    print(account_id.strip())
    raise SystemExit
auth_mode = auth.get("auth_mode")
print(auth_mode if isinstance(auth_mode, str) and auth_mode.strip() else "unknown")
PY
}

prompt_reasoning_effort() {
  local current="$1"
  local selected
  while true; do
    printf "Codex reasoning [default/medium/high/xhigh] (default: %s): " "$current" >&2
    IFS= read -r selected
    selected="${selected:-$current}"
    selected="$(printf '%s' "$selected" | tr '[:upper:]' '[:lower:]')"
    case "$selected" in
      default|none|minimal|low|medium|high|xhigh)
        printf '%s\n' "$selected"
        return
        ;;
      *)
        echo "Choose one of: default, medium, high, xhigh." >&2
        ;;
    esac
  done
}

prompt_fast_mode() {
  local current="$1"
  local default_label="on"
  local selected
  if [[ "$current" == "0" || "$current" == "false" ]]; then
    default_label="off"
  fi
  while true; do
    printf "Codex fast mode [on/off] (default: %s): " "$default_label" >&2
    IFS= read -r selected
    selected="${selected:-$default_label}"
    selected="$(printf '%s' "$selected" | tr '[:upper:]' '[:lower:]')"
    case "$selected" in
      on|yes|y|true|1)
        printf '1\n'
        return
        ;;
      off|no|n|false|0)
        printf '0\n'
        return
        ;;
      *)
        echo "Choose on or off." >&2
        ;;
    esac
  done
}

LOCAL_IP="${LOCAL_SERVER_IP:-$(detect_local_ip)}"
if [[ -z "$LOCAL_IP" ]]; then
  LOCAL_IP="127.0.0.1"
fi

DEFAULT_FLUTTER_COMMAND="$HOME/Desktop/flutter/bin/flutter"
if [[ ! -x "$DEFAULT_FLUTTER_COMMAND" ]]; then
  DEFAULT_FLUTTER_COMMAND="$(command -v flutter || printf 'flutter')"
fi

export BASE_PROJECT_PATH="${BASE_PROJECT_PATH:-$ROOT_DIR/BaseProject}"
export WORKSPACES_ROOT="${WORKSPACES_ROOT:-$SERVER_DIR/workspaces}"
export DB_PATH="${DB_PATH:-$SERVER_DIR/tasks.db}"
export SERVER_BASE_URL="${SERVER_BASE_URL:-http://$LOCAL_IP:$PORT}"
export FLUTTER_COMMAND="${FLUTTER_COMMAND:-$DEFAULT_FLUTTER_COMMAND}"
export MOCK_CODEX="${MOCK_CODEX:-0}"
export INTENT_AGENT_ENABLED="${INTENT_AGENT_ENABLED:-1}"
export CODEX_DANGEROUS_BYPASS="${CODEX_DANGEROUS_BYPASS:-1}"
export CODEX_MODEL="${CODEX_MODEL:-gpt-5.4}"
export CODEX_REASONING_EFFORT="${CODEX_REASONING_EFFORT:-default}"
export CODEX_SERVICE_TIER="${CODEX_SERVICE_TIER:-default}"
export CODEX_FAST_MODE="${CODEX_FAST_MODE:-0}"
export CODEX_TIMEOUT_SECONDS="${CODEX_TIMEOUT_SECONDS:-1800}"
export MAX_CONCURRENT_CODEX_RUNS="${MAX_CONCURRENT_CODEX_RUNS:-1}"
export APP_RUNTIME_DAILY_REQUEST_LIMIT="${APP_RUNTIME_DAILY_REQUEST_LIMIT:-100}"
export APP_RUNTIME_DAILY_TOKEN_LIMIT="${APP_RUNTIME_DAILY_TOKEN_LIMIT:-50000}"
export APP_RUNTIME_MAX_OUTPUT_TOKENS="${APP_RUNTIME_MAX_OUTPUT_TOKENS:-0}"

if [[ -t 0 && "${LOCAL_SERVER_PROMPTS:-1}" != "0" ]]; then
  echo "Codex account: $(detect_codex_user)"
  CODEX_REASONING_EFFORT="$(prompt_reasoning_effort "$CODEX_REASONING_EFFORT")"
  export CODEX_REASONING_EFFORT
  CODEX_FAST_MODE="$(prompt_fast_mode "$CODEX_FAST_MODE")"
  export CODEX_FAST_MODE
fi

case "$(printf '%s' "$CODEX_FAST_MODE" | tr '[:upper:]' '[:lower:]')" in
  1|true|yes|y|on)
    CODEX_FAST_MODE=1
    CODEX_SERVICE_TIER=priority
    ;;
  *)
    CODEX_FAST_MODE=0
    CODEX_SERVICE_TIER=default
    ;;
esac
export CODEX_FAST_MODE
export CODEX_SERVICE_TIER

if [[ ! -x "$VENV_DIR/bin/uvicorn" ]]; then
  "$PYTHON_BIN" -m venv "$VENV_DIR"
  "$VENV_DIR/bin/pip" install -r "$SERVER_DIR/requirements.txt"
fi

if [[ "$MOCK_CODEX" != "1" && "$INTENT_AGENT_ENABLED" == "1" && -z "${OPENAI_API_KEY:-}" ]]; then
  echo "Warning: OPENAI_API_KEY is not set. Intent/spec clarification may fail."
fi

echo "Starting VibeFactory local server"
echo "  SERVER_BASE_URL=$SERVER_BASE_URL"
echo "  DB_PATH=$DB_PATH"
echo "  WORKSPACES_ROOT=$WORKSPACES_ROOT"
echo "  FLUTTER_COMMAND=$FLUTTER_COMMAND"
echo "  CODEX_MODEL=$CODEX_MODEL"
echo "  CODEX_REASONING_EFFORT=$CODEX_REASONING_EFFORT"
echo "  CODEX_FAST_MODE=$CODEX_FAST_MODE"
echo "  CODEX_SERVICE_TIER=$CODEX_SERVICE_TIER"

cd "$SERVER_DIR"
exec "$VENV_DIR/bin/uvicorn" server:app --host "$HOST" --port "$PORT"
