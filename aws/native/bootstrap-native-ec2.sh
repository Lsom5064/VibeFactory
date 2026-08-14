#!/usr/bin/env bash
set -euo pipefail

APP_USER="${APP_USER:-ubuntu}"
APP_ROOT="${APP_ROOT:-/opt/vibefactory-native}"
DATA_ROOT="${DATA_ROOT:-/srv/vibefactory-native}"
ENV_ROOT="${ENV_ROOT:-/etc/vibefactory-native}"
ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-/opt/android-sdk}"
PUBLIC_BASE_URL="${PUBLIC_BASE_URL:-http://127.0.0.1:8081}"

echo "[bootstrap] installing native Android build dependencies"
sudo apt update
sudo apt install -y \
  ca-certificates \
  curl \
  git \
  nginx \
  nodejs \
  npm \
  openjdk-17-jdk \
  python3-pip \
  python3-venv \
  rsync \
  unzip \
  zip

echo "[bootstrap] preparing isolated native service directories"
sudo mkdir -p \
  "$APP_ROOT" \
  "$DATA_ROOT/native_workspaces" \
  "$DATA_ROOT/.native_tooling" \
  "$ENV_ROOT/secrets"
sudo chown -R "$APP_USER:$APP_USER" "$APP_ROOT" "$DATA_ROOT"
sudo chown root:"$APP_USER" "$ENV_ROOT" "$ENV_ROOT/secrets"
sudo chmod 750 "$ENV_ROOT" "$ENV_ROOT/secrets"

echo "[bootstrap] checking Android SDK"
SDKMANAGER=""
for candidate in \
  "$ANDROID_SDK_ROOT/cmdline-tools/latest/bin/sdkmanager" \
  "$ANDROID_SDK_ROOT/cmdline-tools/latest-2/bin/sdkmanager"; do
  if [[ -x "$candidate" ]]; then
    SDKMANAGER="$candidate"
    break
  fi
done
if [[ -z "$SDKMANAGER" ]]; then
  echo "Android sdkmanager is missing under $ANDROID_SDK_ROOT/cmdline-tools." >&2
  echo "Install Android command-line tools before continuing." >&2
  exit 1
fi
yes | "$SDKMANAGER" --sdk_root="$ANDROID_SDK_ROOT" --licenses >/dev/null || true
"$SDKMANAGER" --sdk_root="$ANDROID_SDK_ROOT" \
  "platform-tools" \
  "platforms;android-36" \
  "build-tools;36.0.0"

if ! command -v codex >/dev/null 2>&1; then
  sudo npm install -g @openai/codex
fi

echo "[bootstrap] installing Python dependencies"
cd "$APP_ROOT/flutter_apk_server"
python3 -m venv .venv
.venv/bin/python -m pip install --upgrade pip
.venv/bin/python -m pip install -r requirements.txt

if [[ ! -f "$ENV_ROOT/server.env" ]]; then
  sudo cp "$APP_ROOT/aws/native/vibefactory-native-server.env.example" "$ENV_ROOT/server.env"
  sudo sed -i "s#^SERVER_BASE_URL=.*#SERVER_BASE_URL=$PUBLIC_BASE_URL#" "$ENV_ROOT/server.env"
  sudo chmod 600 "$ENV_ROOT/server.env"
  echo "Created $ENV_ROOT/server.env. Add secrets before starting the service."
fi

sudo cp \
  "$APP_ROOT/aws/native/vibefactory-native-server.service" \
  /etc/systemd/system/vibefactory-native-server.service
sudo cp \
  "$APP_ROOT/aws/native/nginx-vibefactory-native-canary.conf" \
  /etc/nginx/sites-available/vibefactory-native-canary
sudo ln -sf \
  /etc/nginx/sites-available/vibefactory-native-canary \
  /etc/nginx/sites-enabled/vibefactory-native-canary
sudo nginx -t
sudo systemctl daemon-reload

echo "[bootstrap] prepared but not started"
echo "1. Install the signing keystore at $ENV_ROOT/secrets/generated-app.jks"
echo "   sudo chown root:$APP_USER '$ENV_ROOT/secrets/generated-app.jks' && sudo chmod 640 '$ENV_ROOT/secrets/generated-app.jks'"
echo "2. Fill secret values in $ENV_ROOT/server.env"
echo "3. Run server tests and then enable vibefactory-native-server"
