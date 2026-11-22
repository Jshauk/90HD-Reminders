"""
90HD MCP server for the Pebble Index.

A thin bridge: the Index's cloud agent connects over SSE with a bearer token and
calls our tools; each tool proxies to the 90HD backend REST API over the internal
Docker network. The server never touches SQLite directly — it reuses the backend's
validation and sync-aware upsert.

Transport: SSE (the Index only supports SSE or Streamable HTTP; this build uses SSE).
Auth: a static bearer token checked in middleware (the Index sends it as the
Authorization header). MCP-over-SSE has no standardized auth, so we enforce it
ourselves at the HTTP layer.
"""

import os
from datetime import datetime, timedelta
from zoneinfo import ZoneInfo

import httpx
from mcp.server.fastmcp import FastMCP
from mcp.server.sse import SseServerTransport
from starlette.applications import Starlette
from starlette.middleware.base import BaseHTTPMiddleware
from starlette.requests import Request
from starlette.responses import JSONResponse, PlainTextResponse
from starlette.routing import Mount, Route

# --- Config (from environment) ---
BACKEND_URL = os.environ.get("BACKEND_URL", "http://ninety-hd:8000")
# Bearer token the Index must present. Required — fail closed if missing.
AUTH_TOKEN = os.environ.get("MCP_AUTH_TOKEN", "").strip()
# Fallback timezone used only until the phone has reported its own on sync.
FALLBACK_TZ = os.environ.get("TZ", "America/Indiana/Indianapolis")


async def _resolve_tz(client: httpx.AsyncClient) -> ZoneInfo:
    """The phone's current timezone (reported on sync), falling back to the
    configured default if the phone hasn't synced a timezone yet. This makes
    relative times resolve in the user's actual zone and self-correct on travel.
    """
    try:
        resp = await client.get(f"{BACKEND_URL}/api/device-timezone", timeout=10)
        if resp.status_code == 200:
            tz = resp.json().get("timezone")
            if tz:
                return ZoneInfo(tz)
    except Exception:
        pass
    return ZoneInfo(FALLBACK_TZ)


mcp = FastMCP("90HD")


# --------------------------------------------------------------------------
# Tools — each proxies to the backend REST API.
# --------------------------------------------------------------------------

@mcp.tool()
async def create_reminder(
    text: str,
    start_in_minutes: int | None = None,
    start_date: str | None = None,
    start_time: str | None = None,
) -> str:
    """Create a persistent reminder in 90HD.

    Use this whenever the user asks to be reminded of something. Reminders are
    deliberately insistent: they repeat every 1 minute from the start, and keep
    notifying until the user confirms or deletes them in the app. Intended for
    important reminders.

    Choosing when it starts (pick ONE approach):
    - RELATIVE time ("in 5 minutes", "in 2 hours", "in half an hour"): set
      `start_in_minutes` to the total number of minutes from now (e.g. "in 2
      hours" -> 120, "in 90 minutes" -> 90). Leave start_date/start_time unset.
    - ABSOLUTE time ("at 3pm", "tomorrow at 9am", "on March 3 at noon"): set
      `start_date` (YYYY-MM-DD) and/or `start_time` (24-hour HH:MM). If only a
      time is given, assume today (or the next day if that time already passed).
    - If the user gives NO time at all, leave all three unset — it starts now.

    Args:
        text: What to be reminded about, e.g. "call the dentist".
        start_in_minutes: Minutes from now until the first notification.
        start_date: Absolute start date, ISO YYYY-MM-DD.
        start_time: Absolute start time, 24-hour HH:MM.
    """
    if not text or not text.strip():
        return "Could not create the reminder: no text was provided."

    async with httpx.AsyncClient(timeout=15) as client:
        tz = await _resolve_tz(client)
        now = datetime.now(tz)

        if start_in_minutes is not None:
            # Relative: resolve now + offset in the phone's local time.
            when = now + timedelta(minutes=max(0, start_in_minutes))
            resolved_date = when.date().isoformat()
            resolved_time = when.strftime("%H:%M")
        else:
            # Absolute (or defaults). Default date = today, default time = now.
            resolved_date = start_date or now.date().isoformat()
            resolved_time = start_time or now.strftime("%H:%M")
            # If only a time was given and it already passed today, roll to tomorrow.
            if start_time and not start_date:
                try:
                    hh, mm = (int(x) for x in start_time.split(":"))
                    candidate = now.replace(hour=hh, minute=mm, second=0, microsecond=0)
                    if candidate < now:
                        resolved_date = (now + timedelta(days=1)).date().isoformat()
                except Exception:
                    pass

        payload = {
            "text": text.strip(),
            "frequency_value": 1,
            "frequency_unit": "minutes",
            "start_time": resolved_time,
            "start_date": resolved_date,
            "requires_confirmation": True,
        }
        resp = await client.post(f"{BACKEND_URL}/api/reminders", json=payload)

    if resp.status_code in (200, 201):
        return (
            f'Reminder set: "{text.strip()}", starting {resolved_date} at '
            f"{resolved_time}. It repeats every minute until you confirm or delete it."
        )
    return f"Failed to create the reminder (server returned {resp.status_code})."


@mcp.tool()
async def list_reminders() -> str:
    """List the user's current active reminders in 90HD."""
    async with httpx.AsyncClient(timeout=15) as client:
        resp = await client.get(f"{BACKEND_URL}/api/reminders")
    if resp.status_code != 200:
        return f"Failed to fetch reminders (server returned {resp.status_code})."
    reminders = resp.json()
    active = [r for r in reminders if r.get("active", True)]
    if not active:
        return "You have no active reminders."
    lines = [
        f'- {r["text"]} (every {r["frequency_value"]} {r["frequency_unit"]})'
        for r in active
    ]
    return "Your reminders:\n" + "\n".join(lines)


@mcp.tool()
async def delete_reminder(text: str) -> str:
    """Delete a reminder by matching its text.

    Finds the first active reminder whose text contains the given phrase and
    deletes it. Use this when the user says they're done with or want to cancel
    a reminder.
    """
    async with httpx.AsyncClient(timeout=15) as client:
        resp = await client.get(f"{BACKEND_URL}/api/reminders")
        if resp.status_code != 200:
            return f"Failed to look up reminders (server returned {resp.status_code})."
        reminders = resp.json()
        needle = text.strip().lower()
        match = next(
            (r for r in reminders if needle in r.get("text", "").lower() and not r.get("deleted")),
            None,
        )
        if match is None:
            return f'No active reminder matching "{text}" was found.'
        del_resp = await client.delete(f"{BACKEND_URL}/api/reminders/{match['id']}")
    if del_resp.status_code in (200, 204):
        return f'Deleted reminder: "{match["text"]}".'
    return f"Failed to delete the reminder (server returned {del_resp.status_code})."


# --------------------------------------------------------------------------
# Bearer-token auth middleware.
# --------------------------------------------------------------------------

class BearerAuthMiddleware(BaseHTTPMiddleware):
    async def dispatch(self, request: Request, call_next):
        # Health check is open so the tunnel/host can probe liveness.
        if request.url.path == "/health":
            return await call_next(request)

        auth = request.headers.get("authorization", "")
        expected = f"Bearer {AUTH_TOKEN}"
        if not AUTH_TOKEN or auth != expected:
            return JSONResponse({"error": "unauthorized"}, status_code=401)
        return await call_next(request)


# --------------------------------------------------------------------------
# SSE transport wiring (mount /sse and /messages).
# --------------------------------------------------------------------------

def build_app() -> Starlette:
    if not AUTH_TOKEN:
        # Fail closed: refuse to start without a token, so we never expose an
        # open MCP server to the internet by accident.
        raise RuntimeError("MCP_AUTH_TOKEN is not set — refusing to start.")

    transport = SseServerTransport("/messages/")

    async def handle_sse(request: Request):
        async with transport.connect_sse(
            request.scope, request.receive, request._send
        ) as streams:
            await mcp._mcp_server.run(
                streams[0],
                streams[1],
                mcp._mcp_server.create_initialization_options(),
            )

    async def health(_request: Request):
        return PlainTextResponse("ok")

    routes = [
        Route("/health", endpoint=health),
        Route("/sse", endpoint=handle_sse),
        Mount("/messages/", app=transport.handle_post_message),
    ]
    app = Starlette(routes=routes)
    app.add_middleware(BearerAuthMiddleware)
    return app


app = build_app()
