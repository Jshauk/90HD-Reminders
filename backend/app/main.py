import os
import os
from datetime import datetime, timedelta
from pathlib import Path

from fastapi import FastAPI, Depends, HTTPException, Request, Query
from fastapi.responses import HTMLResponse, JSONResponse
from fastapi.templating import Jinja2Templates
from sqlalchemy.orm import Session

from app.database import get_db, init_db
from app.models import Reminder, Settings, Confirmation, Device
from app.schemas import (
    ReminderCreate,
    ReminderUpdate,
    ReminderResponse,
    SettingsResponse,
    SettingsUpdate,
    ConfirmationCreate,
    ConfirmationResponse,
    SyncResponse,
)

# Tombstones are retained this long so devices that have been offline still see
# deletions on their next sync. A device offline longer than this does a full
# resync (client-side concern).
TOMBSTONE_RETENTION_DAYS = 30

# --- Optional bearer-token auth -------------------------------------------
# Off by default (intended for private networks / Tailscale). When AUTH_ENABLED
# is true, every /api/* request must carry `Authorization: Bearer <AUTH_TOKEN>`.
# Fail closed: enabled-without-a-token refuses to start.
AUTH_ENABLED = os.environ.get("AUTH_ENABLED", "false").strip().lower() == "true"
AUTH_TOKEN = os.environ.get("AUTH_TOKEN", "").strip()
if AUTH_ENABLED and not AUTH_TOKEN:
    raise RuntimeError("AUTH_ENABLED=true but AUTH_TOKEN is empty — refusing to start.")

app = FastAPI(title="90HD", version="1.0.0")


@app.middleware("http")
async def bearer_auth(request: Request, call_next):
    # Only guard the API; the web UI and health check stay open. Health is also
    # open so clients can discover whether auth is required.
    path = request.url.path
    if AUTH_ENABLED and path.startswith("/api/") and path != "/api/health":
        expected = f"Bearer {AUTH_TOKEN}"
        if request.headers.get("authorization", "") != expected:
            return JSONResponse({"detail": "unauthorized"}, status_code=401)
    return await call_next(request)

# Templates
BASE_DIR = Path(__file__).resolve().parent
templates = Jinja2Templates(directory=str(BASE_DIR / "templates"))


@app.on_event("startup")
def on_startup():
    init_db()


# --- Web UI ---

@app.get("/", response_class=HTMLResponse)
def web_ui(request: Request):
    return templates.TemplateResponse("index.html", {"request": request})


# --- Health Check ---

@app.get("/api/health")
def health_check():
    # Report whether auth is required so clients can prompt for a token.
    return {"status": "ok", "app": "90HD", "auth": "enabled" if AUTH_ENABLED else "disabled"}


# --- Reminders CRUD ---

@app.get("/api/reminders", response_model=list[ReminderResponse])
def list_reminders(db: Session = Depends(get_db)):
    # Normal listing (web UI) excludes soft-deleted rows.
    return (
        db.query(Reminder)
        .filter(Reminder.deleted == False)  # noqa: E712
        .order_by(Reminder.created_at.desc())
        .all()
    )


@app.post("/api/reminders", response_model=ReminderResponse, status_code=201)
def create_reminder(data: ReminderCreate, db: Session = Depends(get_db)):
    payload = data.model_dump()
    supplied_id = payload.pop("id", None)

    # If the client supplied an id that already exists, treat this as an upsert:
    # apply the fields to the existing row (last-write-wins) instead of erroring.
    if supplied_id:
        existing = db.query(Reminder).filter(Reminder.id == supplied_id).first()
        if existing:
            for key, value in payload.items():
                setattr(existing, key, value)
            # A create/upsert of a live reminder revives a previously deleted row.
            existing.deleted = False
            existing.deleted_at = None
            existing.updated_at = datetime.utcnow()
            db.commit()
            db.refresh(existing)
            return existing

    reminder = Reminder(**payload)
    if supplied_id:
        reminder.id = supplied_id
    db.add(reminder)
    db.commit()
    db.refresh(reminder)
    return reminder


@app.get("/api/reminders/{reminder_id}", response_model=ReminderResponse)
def get_reminder(reminder_id: str, db: Session = Depends(get_db)):
    reminder = db.query(Reminder).filter(Reminder.id == reminder_id).first()
    if not reminder:
        raise HTTPException(status_code=404, detail="Reminder not found")
    return reminder


@app.put("/api/reminders/{reminder_id}", response_model=ReminderResponse)
def update_reminder(reminder_id: str, data: ReminderUpdate, db: Session = Depends(get_db)):
    reminder = db.query(Reminder).filter(Reminder.id == reminder_id).first()
    if not reminder:
        raise HTTPException(status_code=404, detail="Reminder not found")

    update_data = data.model_dump(exclude_unset=True)
    for key, value in update_data.items():
        setattr(reminder, key, value)

    reminder.updated_at = datetime.utcnow()
    db.commit()
    db.refresh(reminder)
    return reminder


@app.delete("/api/reminders/{reminder_id}", status_code=204)
def delete_reminder(reminder_id: str, db: Session = Depends(get_db)):
    reminder = db.query(Reminder).filter(Reminder.id == reminder_id).first()
    if not reminder:
        raise HTTPException(status_code=404, detail="Reminder not found")
    # Soft delete (tombstone) so the deletion can propagate through sync.
    now = datetime.utcnow()
    reminder.deleted = True
    reminder.deleted_at = now
    reminder.updated_at = now
    db.commit()


# --- Settings ---

@app.get("/api/settings", response_model=SettingsResponse)
def get_settings(db: Session = Depends(get_db)):
    settings = db.query(Settings).first()
    if not settings:
        raise HTTPException(status_code=404, detail="Settings not found")
    return settings


@app.get("/api/device-timezone")
def device_timezone(db: Session = Depends(get_db)):
    """The phone's most recently reported timezone (single-device setup).

    Used by the MCP/Index bridge to resolve relative times in the user's zone.
    Returns null if no device has reported one yet.
    """
    device = (
        db.query(Device)
        .filter(Device.timezone.isnot(None))
        .order_by(Device.last_sync_at.desc().nullslast())
        .first()
    )
    return {"timezone": device.timezone if device else None}


@app.put("/api/settings", response_model=SettingsResponse)
def update_settings(data: SettingsUpdate, db: Session = Depends(get_db)):
    settings = db.query(Settings).first()
    if not settings:
        raise HTTPException(status_code=404, detail="Settings not found")

    update_data = data.model_dump(exclude_unset=True)
    for key, value in update_data.items():
        setattr(settings, key, value)

    db.commit()
    db.refresh(settings)
    return settings


# --- Confirmations ---

@app.post("/api/confirmations", response_model=ConfirmationResponse, status_code=201)
def create_confirmation(data: ConfirmationCreate, db: Session = Depends(get_db)):
    # Verify the reminder exists
    reminder = db.query(Reminder).filter(Reminder.id == data.reminder_id).first()
    if not reminder:
        raise HTTPException(status_code=404, detail="Reminder not found")

    confirmation = Confirmation(**data.model_dump())
    db.add(confirmation)
    db.commit()
    db.refresh(confirmation)
    return confirmation


# --- Sync ---

def _purge_old_tombstones(db: Session, safe_cutoff: datetime | None) -> None:
    """Hard-delete tombstoned reminders that are safe to remove.

    A tombstone is safe to purge when it was deleted before `safe_cutoff` — the
    oldest checkpoint every device had *already reached on a previous sync* — so
    all devices have pulled the deletion. Independently, tombstones older than
    the hard retention window are always purged so a long-offline device can't
    pin rows forever.
    """
    now = datetime.utcnow()
    retention_cutoff = now - timedelta(days=TOMBSTONE_RETENTION_DAYS)

    if safe_cutoff is not None:
        db.query(Reminder).filter(
            Reminder.deleted == True,  # noqa: E712
            Reminder.deleted_at < safe_cutoff,
        ).delete(synchronize_session=False)

    db.query(Reminder).filter(
        Reminder.deleted == True,  # noqa: E712
        Reminder.deleted_at < retention_cutoff,
    ).delete(synchronize_session=False)
    db.commit()


@app.get("/api/sync", response_model=SyncResponse)
def sync(
    db: Session = Depends(get_db),
    since: datetime | None = Query(None, description="Return reminders updated at/after this server timestamp"),
    device_id: str | None = Query(None, description="Registering device's id; its last_sync_at is updated"),
    tz: str | None = Query(None, description="The device's current IANA timezone, e.g. America/Chicago"),
):
    server_time = datetime.utcnow()

    query = db.query(Reminder)
    if since is not None:
        # Delta sync: everything changed since the client last synced, INCLUDING
        # tombstones so deletions propagate.
        query = query.filter(Reminder.updated_at >= since)
    else:
        # Full sync: only live reminders (a fresh/long-offline client).
        query = query.filter(Reminder.deleted == False)  # noqa: E712
    reminders = query.order_by(Reminder.created_at.desc()).all()

    settings = db.query(Settings).first()

    # Register / update the device's sync checkpoint.
    if device_id:
        # The device checkpoint (last_sync_at) represents "this device has seen
        # all tombstones up to this time". Only a DELTA sync (since provided)
        # actually delivers tombstones, so only a delta advances the checkpoint.
        # A full sync (no since) is a bootstrap of live data and must NOT advance
        # it, otherwise we could purge tombstones the device never received.
        all_devices = db.query(Device).all()
        current = next((d for d in all_devices if d.device_id == device_id), None)

        # Safe purge cutoff = oldest checkpoint across devices BEFORE this update.
        # Skip device-based purge if any device has no checkpoint yet.
        others_have_checkpoints = all(d.last_sync_at is not None for d in all_devices)
        safe_cutoff = (
            min(d.last_sync_at for d in all_devices)
            if all_devices and others_have_checkpoints and current is not None
            else None
        )

        if current is None:
            # New device. On a delta sync record the checkpoint; on a bootstrap
            # full sync, leave it null until the first delta so we don't skip
            # tombstones created between now and that first delta.
            db.add(Device(
                device_id=device_id,
                last_sync_at=(server_time if since is not None else None),
                timezone=tz,
            ))
        else:
            if since is not None:
                current.last_sync_at = server_time
            # Timezone updates on every sync (full or delta) so it self-corrects
            # as the phone travels.
            if tz:
                current.timezone = tz
        db.commit()

        _purge_old_tombstones(db, safe_cutoff)

    return SyncResponse(reminders=reminders, settings=settings, server_time=server_time)
