# AWS Deployment Notes

This folder contains helper files for deploying the local FastAPI APK builder to
the EC2 instance at `15.165.191.202`.

The server should be brought up in two phases:

1. Start in `MOCK_CODEX=1` to verify SSH, file copy, Python, systemd, Nginx,
   SQLite, and public HTTP routing.
2. Install and authenticate Flutter, Android SDK, and Codex, then switch to
   `MOCK_CODEX=0` for real APK generation.

## 0. Local prerequisites

From the workspace root:

```bash
chmod 400 aws/vibeFactory.pem
ssh -i aws/vibeFactory.pem ubuntu@15.165.191.202
```

The EC2 security group should allow:

- SSH `22` from your IP only.
- HTTP `80` from the devices that will use the host app.
- Do not expose `8000`; Nginx proxies to local uvicorn on `127.0.0.1:8000`.

## 1. Copy the project to EC2

From the workspace root:

```bash
./aws/rsync-to-ec2.sh
```

This copies source files to `/opt/vibefactory` and intentionally excludes local
runtime data such as `tasks.db`, `workspaces/`, `profiles/`, build outputs, and
private keys.

## 2. Bootstrap the EC2 instance

Run:

```bash
ssh -i aws/vibeFactory.pem ubuntu@15.165.191.202 \
  'bash /opt/vibefactory/aws/bootstrap-ec2.sh'
```

This installs base packages, creates `/srv/vibefactory`, creates the Python
virtualenv, installs FastAPI dependencies, installs systemd/Nginx files, and
starts the service in mock mode.

## 3. Verify mock deployment

```bash
curl http://15.165.191.202/health
curl -sS -X POST http://15.165.191.202/generate \
  -H 'Content-Type: application/json' \
  -d '{"device_id":"aws-smoke-device","prompt":"make a simple todo app"}'
```

Check logs if needed:

```bash
ssh -i aws/vibeFactory.pem ubuntu@15.165.191.202 \
  'sudo systemctl status vibefactory-server --no-pager && sudo journalctl -u vibefactory-server -n 80 --no-pager'
```

## 4. Enable real builds

On the EC2 instance:

```bash
sudo nano /etc/vibefactory-server.env
```

Change at least:

```bash
MOCK_CODEX=0
INTENT_AGENT_ENABLED=1
OPENAI_API_KEY=sk-...
APP_RUNTIME_OPENAI_API_KEY=sk-...
```

Then install/verify the real toolchain:

```bash
/opt/flutter/bin/flutter doctor -v
codex --version
codex login
```

The systemd service runs as `ubuntu`, so Codex auth must exist for the `ubuntu`
user, usually under `/home/ubuntu/.codex/auth.json`.

Restart:

```bash
sudo systemctl restart vibefactory-server
sudo journalctl -u vibefactory-server -n 80 --no-pager
```

## 5. Point the Android host app at AWS

Update:

```text
vibefactory/app/src/main/java/kr/ac/kangwon/hai/vibefactory/HostAppConfig.kt
```

Use:

```kotlin
const val BASE_URL = "http://15.165.191.202"
```

For production, prefer a domain plus HTTPS and update both:

- Android `HostAppConfig.BASE_URL`
- `/etc/vibefactory-server.env` `SERVER_BASE_URL`

## Operational notes

- `tasks.db` and `workspaces/` live under `/srv/vibefactory`; back up this data
  with EBS snapshots.
- Keep `MAX_CONCURRENT_CODEX_RUNS=1` until the instance size and memory pressure
  are measured.
- Long build requests require long proxy timeouts. The included Nginx config uses
  one hour.
- This deployment does not add public API authentication. Add request auth/rate
  limits before exposing it broadly.
