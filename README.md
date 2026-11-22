# 90HD - Persistent Reminder System

A local-first Android reminder app with persistent, escalating notifications — and an
**optional** self-hosted Python backend for backup and cross-device sync.

The app works fully standalone: reminders live on your phone, fire offline, and require
no account or server. If you want backup/sync (a privacy-friendly, self-hosted option),
you can run the included Docker backend on your own machine and connect the app to it.

## Architecture

```
┌──────────────────────────────┐
│  Android Device (standalone) │
│  90HD App (Kotlin/Compose)   │
│  Room (source of truth)      │
│  AlarmManager (scheduling)   │
│  Notifications + Escalation  │
└──────────────────────────────┘
              ▲
              │  optional REST sync
              ▼
┌─────────────────────────┐
│  Your Server (optional) │
│  FastAPI Backend        │
│  SQLite Database        │
│  Web UI (port 8090)     │
│  Docker Container       │
└─────────────────────────┘
```

## Features

- **Local-first** — Reminders are stored on the phone and work with no server and no network
- **Android App** — Native notification system with persistent reminders
- **Recurring Reminders** — Every N hours/days/weeks/months
- **Optional start/end dates** — Schedule when a reminder begins and stops
- **Active Hours** — Hourly reminders only fire during configured hours (global default + per-reminder override)
- **Timezone-Aware** — Times are local; if you travel, notifications adjust to your new timezone
- **Escalation** — Opt-in per reminder: if you don't confirm within 5 minutes, re-notifies every 1 minute (up to 10 times)
- **Optional self-hosted server** — Run your own backend for backup and a web UI; nothing is sent to any third party

## Backend Setup (Optional)

The backend is only needed if you want backup/sync or the web UI. The app is fully
functional without it.

### Prerequisites
- Docker and Docker Compose installed on your server

### Deploy

```bash
ssh your-server
cd ~
git clone <repo-url> 90HD  # or just copy the backend/ directory
cd 90HD/backend
mkdir -p data
docker compose up -d
```

The backend will be available at `http://your-server:8090`

### Verify

```bash
curl http://localhost:8090/api/health
# {"status":"ok","app":"90HD"}
```

### Configuration

- **Port**: Change in `docker-compose.yml` (default: 8090)
- **Database**: SQLite file stored at `./data/90hd.db` (persisted via Docker volume)
- **Default active hours**: 08:00 - 22:00 (configurable via Settings in the UI)

## API Endpoints

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/health` | Health check |
| GET | `/api/reminders` | List all reminders |
| POST | `/api/reminders` | Create a reminder |
| GET | `/api/reminders/{id}` | Get a specific reminder |
| PUT | `/api/reminders/{id}` | Update a reminder |
| DELETE | `/api/reminders/{id}` | Delete a reminder |
| GET | `/api/settings` | Get global settings |
| PUT | `/api/settings` | Update global settings |
| POST | `/api/confirmations` | Log a confirmation |
| GET | `/api/sync` | Get all reminders + settings (for app sync) |

### Create Reminder Example

```json
POST /api/reminders
{
    "text": "Take medication",
    "frequency_value": 1,
    "frequency_unit": "days",
    "start_time": "08:00",
    "end_time": null,
    "active_hours_start": null,
    "active_hours_end": null,
    "active": true
}
```

## Android App

### Build

1. Open `android/` in Android Studio
2. Sync Gradle
3. Build → Make Project (or `./gradlew assembleDebug`)
4. Install on device

### First Launch

The app opens straight to your reminder list — no setup, no account, no server required.
Just tap **+** to create a reminder.

To optionally connect a self-hosted server for backup/sync, go to **Settings → Connect to
server** and enter its address (e.g., `192.168.1.10:8090`). The app validates the
connection by hitting `/api/health`.

### How Notifications Work

1. Reminders are stored locally in the phone's Room database (the source of truth)
2. `NotificationScheduler` calculates next fire time in **device's local timezone**
3. `AlarmManager` fires exact alarm at the scheduled time
4. Notification appears — with a **Confirm** button if the reminder has confirmation enabled
5. If confirmation is required and not confirmed within 5 minutes → re-notifies every 1 minute (up to 10 times)
6. Tapping Confirm → cancels escalation and dismisses the notification
7. Next scheduled occurrence fires normally regardless of confirmation

### Timezone Handling

All times are stored as naive local times (e.g., "08:00"). The app interprets them in the device's current timezone. If you travel:
- "08:00" reminder fires at 8:00 AM **in your new timezone**
- A `TIMEZONE_CHANGED` broadcast receiver reschedules all alarms automatically

### Permissions Required

- `INTERNET` — Server communication
- `POST_NOTIFICATIONS` — Show notifications (Android 13+ requires runtime permission)
- `SCHEDULE_EXACT_ALARM` / `USE_EXACT_ALARM` — Precise scheduling
- `RECEIVE_BOOT_COMPLETED` — Reschedule after device restart
- `WAKE_LOCK` — Ensure notifications fire on time
- `VIBRATE` — Notification vibration

## Project Structure

```
90HD/
├── backend/
│   ├── app/
│   │   ├── main.py          # FastAPI application
│   │   ├── models.py        # SQLAlchemy models
│   │   ├── schemas.py       # Pydantic schemas
│   │   ├── database.py      # DB setup and session
│   │   └── templates/
│   │       └── index.html   # Web UI
│   ├── mcp/                 # Pebble Index MCP bridge (optional)
│   │   ├── server.py        # SSE MCP server, proxies to the backend API
│   │   ├── Dockerfile
│   │   └── requirements.txt
│   ├── Dockerfile
│   ├── docker-compose.yml
│   └── requirements.txt
└── android/
    └── app/src/main/java/com/ninetyhd/app/
        ├── MainActivity.kt
        ├── NinetyHDApp.kt
        ├── data/
        │   ├── local/          # Room database
        │   ├── remote/         # Retrofit API client
        │   ├── repository/     # Data layer
        │   └── sync/           # WorkManager sync
        ├── notifications/      # Scheduling logic
        ├── receivers/          # Broadcast receivers
        └── ui/                 # Compose screens
```

## Pebble Index Integration (Optional)

If you own a [Pebble Index](https://repebble.com) smart ring, you can create reminders by
voice. The repo includes an MCP server (`backend/mcp/`) that the Index's cloud agent
connects to; each voice request becomes a reminder in your 90HD backend.

By default, a reminder created from the ring is **insistent**: it repeats every minute
from its start until you confirm or delete it in the app — intended for important
reminders. You can say things like:

- "remind me to take out the trash" (starts now)
- "remind me in 20 minutes to flip the laundry"
- "remind me at 3pm to call the bank"

### How it works

```
Ring (double-click + speak)
  → Pebble cloud agent (parses the request, fills tool args)
  → your MCP server (SSE + bearer token, exposed via HTTPS)
  → 90HD backend REST API
  → reminder created; phone picks it up on next sync
```

The MCP server is a thin bridge — it never touches the database directly, it just calls
the backend API over the internal Docker network. Relative times ("in 20 minutes") are
resolved in the phone's current timezone, which the app reports to the server on each
sync (so it self-corrects when you travel).

### Setup

1. **Run the MCP service.** It's a second service in `docker-compose.yml`. Set a strong
   bearer token in a `.env` file next to the compose file:
   ```
   MCP_AUTH_TOKEN=<a long random string>
   ```
   Then `docker compose up -d --build`. The MCP server listens on port 8091.

2. **Expose it over HTTPS.** The Index's cloud agent reaches your server over the public
   internet, so the MCP endpoint needs a public HTTPS URL. Put it behind a reverse proxy
   (nginx Proxy Manager, Caddy, etc.) or a tunnel (Cloudflare Tunnel, Tailscale Funnel)
   pointing at `http://<server>:8091`. Because SSE is a long-lived stream, disable
   response buffering on the proxy, e.g. for nginx:
   ```
   proxy_buffering off;
   proxy_cache off;
   proxy_read_timeout 3600s;
   ```

3. **Register it in the Pebble app** → Index → Settings → MCP & Tool Settings → MCP
   Servers → Add:
   - **Name**: `90hd` (no spaces — known Pebble bug)
   - **URL**: `https://<your-mcp-domain>/sse`
   - **Transport**: SSE
   - **Authorization**: `Bearer <your MCP_AUTH_TOKEN>`
   - **Groups**: a sandbox group

4. **Assign the sandbox group** to a gesture (e.g. "Double click and hold") in the Index
   tab settings. Double-click, speak, done.

## Self-Hosting Notes

- The backend ships with **optional bearer-token auth**, off by default (`AUTH_ENABLED`).
  On a trusted private network (LAN, VPN, Tailscale) you can leave it off. If you expose
  the backend itself to the internet, enable it (`AUTH_ENABLED=true` + `AUTH_TOKEN`). The
  MCP bridge always requires its own bearer token.
- Single-user system — each person runs their own instance
- No cloud dependencies — fully self-contained, no Firebase/Google services needed
- Data stays on your device (and, if you connect one, your own server) — the SQLite file
  is the entire backend database
