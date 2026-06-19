#!/usr/bin/env bash
set -euo pipefail

APP_USER="${APP_USER:-ubuntu}"
APP_ROOT="${APP_ROOT:-/opt/vibefactory}"
DATA_ROOT="${DATA_ROOT:-/srv/vibefactory}"
PUBLIC_BASE_URL="${PUBLIC_BASE_URL:-http://15.165.191.202}"

echo "[bootstrap] installing base packages"
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
  xz-utils \
  zip

echo "[bootstrap] preparing directories"
sudo mkdir -p "$APP_ROOT" "$DATA_ROOT/workspaces" "$DATA_ROOT/profiles"
sudo chown -R "$APP_USER:$APP_USER" "$APP_ROOT" "$DATA_ROOT"

echo "[bootstrap] installing Flutter if needed"
if [ ! -d /opt/flutter ]; then
  sudo git clone --depth 1 -b stable https://github.com/flutter/flutter.git /opt/flutter
fi
sudo chown -R "$APP_USER:$APP_USER" /opt/flutter

if ! grep -q '/opt/flutter/bin' "/home/$APP_USER/.profile" 2>/dev/null; then
  echo 'export PATH="/opt/flutter/bin:$PATH"' | sudo tee -a "/home/$APP_USER/.profile" >/dev/null
fi

echo "[bootstrap] installing Codex CLI if needed"
if ! command -v codex >/dev/null 2>&1; then
  sudo npm install -g @openai/codex
fi

echo "[bootstrap] optional Android SDK command-line tools"
ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-/opt/android-sdk}"
sudo mkdir -p "$ANDROID_SDK_ROOT/cmdline-tools"
sudo chown -R "$APP_USER:$APP_USER" "$ANDROID_SDK_ROOT"

if [ -n "${ANDROID_CMDLINE_TOOLS_ZIP_URL:-}" ] && [ ! -d "$ANDROID_SDK_ROOT/cmdline-tools/latest" ]; then
  tmp_zip="/tmp/android-commandline-tools.zip"
  curl -L "$ANDROID_CMDLINE_TOOLS_ZIP_URL" -o "$tmp_zip"
  tmp_dir="$(mktemp -d)"
  unzip -q "$tmp_zip" -d "$tmp_dir"
  mkdir -p "$ANDROID_SDK_ROOT/cmdline-tools/latest"
  mv "$tmp_dir/cmdline-tools/"* "$ANDROID_SDK_ROOT/cmdline-tools/latest/"
fi

SDKMANAGER="$ANDROID_SDK_ROOT/cmdline-tools/latest/bin/sdkmanager"
if [ -x "$SDKMANAGER" ]; then
  yes | "$SDKMANAGER" --sdk_root="$ANDROID_SDK_ROOT" --licenses >/dev/null || true
  "$SDKMANAGER" --sdk_root="$ANDROID_SDK_ROOT" \
    "platform-tools" \
    "platforms;android-35" \
    "build-tools;35.0.0"
fi

echo "[bootstrap] installing Python server dependencies"
cd "$APP_ROOT/flutter_apk_server"
python3 -m venv .venv
. .venv/bin/activate
python -m pip install --upgrade pip
python -m pip install -r requirements.txt

echo "[bootstrap] creating env file if missing"
if [ ! -f /etc/vibefactory-server.env ]; then
  sudo cp "$APP_ROOT/aws/vibefactory-server.env.example" /etc/vibefactory-server.env
  sudo sed -i "s#^SERVER_BASE_URL=.*#SERVER_BASE_URL=$PUBLIC_BASE_URL#" /etc/vibefactory-server.env
fi

echo "[bootstrap] installing systemd and nginx config"
sudo cp "$APP_ROOT/aws/vibefactory-server.service" /etc/systemd/system/vibefactory-server.service
sudo cp "$APP_ROOT/aws/nginx-vibefactory.conf" /etc/nginx/sites-available/vibefactory
sudo ln -sf /etc/nginx/sites-available/vibefactory /etc/nginx/sites-enabled/vibefactory

sudo nginx -t
sudo systemctl daemon-reload
sudo systemctl enable vibefactory-server
sudo systemctl restart vibefactory-server
sudo systemctl reload nginx

echo "[bootstrap] done"
echo "Health check: curl $PUBLIC_BASE_URL/health"
