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

list_codex_models() {
  local cache_file="${CODEX_MODELS_CACHE_FILE:-$HOME/.codex/models_cache.json}"
  if [[ -f "$cache_file" ]]; then
    "$PYTHON_BIN" - "$cache_file" <<'PY' 2>/dev/null && return
import json
import sys

with open(sys.argv[1], "r", encoding="utf-8") as handle:
    payload = json.load(handle)

seen = set()
for item in payload.get("models", []):
    if not isinstance(item, dict) or item.get("visibility") != "list":
        continue
    slug = str(item.get("slug") or "").strip()
    if not slug or slug in seen:
        continue
    seen.add(slug)
    label = str(item.get("display_name") or item.get("name") or slug).strip()
    print(f"{slug}\t{label}")
PY
  fi

  cat <<'EOF'
gpt-5.6-sol	GPT-5.6-Sol
gpt-5.6-terra	GPT-5.6-Terra
gpt-5.6-luna	GPT-5.6-Luna
gpt-5.5	GPT-5.5
gpt-5.4	GPT-5.4
gpt-5.4-mini	GPT-5.4-Mini
gpt-5.3-codex-spark	GPT-5.3-Codex-Spark
EOF
}

prompt_codex_model() {
  local current="$1"
  local selected custom_model slug label index custom_index
  local -a model_slugs=()
  local -a model_labels=()

  while IFS=$'\t' read -r slug label; do
    [[ -n "$slug" ]] || continue
    model_slugs+=("$slug")
    model_labels+=("${label:-$slug}")
  done < <(list_codex_models)

  if [[ ${#model_slugs[@]} -eq 0 ]]; then
    model_slugs+=("$current")
    model_labels+=("$current")
  fi

  custom_index=$((${#model_slugs[@]} + 1))
  while true; do
    printf 'Codex model (current: %s):\n' "$current" >&2
    for ((index = 0; index < ${#model_slugs[@]}; index++)); do
      if [[ "${model_slugs[$index]}" == "$current" ]]; then
        printf '  %d) %s [%s] (default)\n' "$((index + 1))" "${model_labels[$index]}" "${model_slugs[$index]}" >&2
      else
        printf '  %d) %s [%s]\n' "$((index + 1))" "${model_labels[$index]}" "${model_slugs[$index]}" >&2
      fi
    done
    printf '  %d) Enter another model ID\n' "$custom_index" >&2
    printf 'Choose a number or press Enter for %s: ' "$current" >&2
    IFS= read -r selected

    if [[ -z "$selected" ]]; then
      printf '%s\n' "$current"
      return
    fi
    if [[ "$selected" =~ ^[0-9]+$ ]]; then
      if ((selected >= 1 && selected <= ${#model_slugs[@]})); then
        printf '%s\n' "${model_slugs[$((selected - 1))]}"
        return
      fi
      if ((selected == custom_index)); then
        printf 'Model ID: ' >&2
        IFS= read -r custom_model
        if [[ "$custom_model" =~ ^[A-Za-z0-9._:-]+$ ]]; then
          printf '%s\n' "$custom_model"
          return
        fi
        echo 'Model ID may contain only letters, numbers, dot, underscore, colon, and hyphen.' >&2
        continue
      fi
    elif [[ "$selected" =~ ^[A-Za-z0-9._:-]+$ ]]; then
      printf '%s\n' "$selected"
      return
    fi
    printf 'Choose 1-%d, press Enter, or enter a valid model ID.\n' "$custom_index" >&2
  done
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

SIGNING_ENV_FILE="${GENERATED_APP_SIGNING_ENV_FILE:-$HOME/.vibefactory/signing/generated-app-signing.env}"
if [[ -f "$SIGNING_ENV_FILE" ]]; then
  # shellcheck disable=SC1090
  source "$SIGNING_ENV_FILE"
fi

export BASE_PROJECT_PATH="${BASE_PROJECT_PATH:-$ROOT_DIR/BaseProject}"
export WORKSPACES_ROOT="${WORKSPACES_ROOT:-$SERVER_DIR/native_workspaces}"
export DB_PATH="${DB_PATH:-$SERVER_DIR/native_tasks.db}"
export APP_DATA_DB_PATH="${APP_DATA_DB_PATH:-$SERVER_DIR/native_app_data.db}"
export BUILD_CACHE_ROOT="${BUILD_CACHE_ROOT:-$SERVER_DIR/.native_tooling}"
export SERVER_BASE_URL="${SERVER_BASE_URL:-http://$LOCAL_IP:$PORT}"
export MOCK_CODEX="${MOCK_CODEX:-0}"
export INTENT_AGENT_ENABLED="${INTENT_AGENT_ENABLED:-1}"
export CODEX_DANGEROUS_BYPASS="${CODEX_DANGEROUS_BYPASS:-0}"
export CODEX_SANDBOX_MODE="${CODEX_SANDBOX_MODE:-workspace-write}"
export CODEX_MODEL="${CODEX_MODEL:-gpt-5.6-sol}"
export CODEX_REASONING_EFFORT="${CODEX_REASONING_EFFORT:-default}"
export CODEX_SERVICE_TIER="${CODEX_SERVICE_TIER:-default}"
export CODEX_FAST_MODE="${CODEX_FAST_MODE:-0}"
export CODEX_TIMEOUT_SECONDS="${CODEX_TIMEOUT_SECONDS:-1800}"
export MAX_CONCURRENT_CODEX_RUNS="${MAX_CONCURRENT_CODEX_RUNS:-1}"
export APP_RUNTIME_DAILY_REQUEST_LIMIT="${APP_RUNTIME_DAILY_REQUEST_LIMIT:-100}"
export APP_RUNTIME_DAILY_TOKEN_LIMIT="${APP_RUNTIME_DAILY_TOKEN_LIMIT:-50000}"
export APP_RUNTIME_MAX_OUTPUT_TOKENS="${APP_RUNTIME_MAX_OUTPUT_TOKENS:-0}"
export APP_RUNTIME_OPENAI_API_KEY="${APP_RUNTIME_OPENAI_API_KEY:-${OPENAI_API_KEY:-}}"

if [[ -t 0 && "${LOCAL_SERVER_PROMPTS:-1}" != "0" ]]; then
  echo "Codex account: $(detect_codex_user)"
  CODEX_MODEL="$(prompt_codex_model "$CODEX_MODEL")"
  export CODEX_MODEL
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

if [[ ! -x "$VENV_DIR/bin/uvicorn" ]] || ! "$VENV_DIR/bin/python" -c "import PIL" >/dev/null 2>&1; then
  "$PYTHON_BIN" -m venv "$VENV_DIR"
  "$VENV_DIR/bin/pip" install -r "$SERVER_DIR/requirements.txt"
fi

if [[ "$MOCK_CODEX" != "1" && "$INTENT_AGENT_ENABLED" == "1" && -z "${OPENAI_API_KEY:-}" ]]; then
  echo "Warning: OPENAI_API_KEY is not set. Intent/spec clarification may fail."
fi

echo "Starting VibeFactory local server"
echo "  SERVER_BASE_URL=$SERVER_BASE_URL"
echo "  DB_PATH=$DB_PATH"
echo "  APP_DATA_DB_PATH=$APP_DATA_DB_PATH"
echo "  WORKSPACES_ROOT=$WORKSPACES_ROOT"
echo "  BUILD_CACHE_ROOT=$BUILD_CACHE_ROOT"
echo "  GENERATED_APP_KEYSTORE_PATH=${GENERATED_APP_KEYSTORE_PATH:-not configured}"
echo "  CODEX_MODEL=$CODEX_MODEL"
echo "  CODEX_REASONING_EFFORT=$CODEX_REASONING_EFFORT"
echo "  CODEX_FAST_MODE=$CODEX_FAST_MODE"
echo "  CODEX_SERVICE_TIER=$CODEX_SERVICE_TIER"
echo "  CODEX_SANDBOX_MODE=$CODEX_SANDBOX_MODE"
echo "  CODEX_DANGEROUS_BYPASS=$CODEX_DANGEROUS_BYPASS"

cd "$SERVER_DIR"
exec "$VENV_DIR/bin/uvicorn" server:app --host "$HOST" --port "$PORT"
