import uuid
from datetime import datetime
from sqlalchemy import Column, Integer, String, Boolean, DateTime, ForeignKey
from app.database import Base


def generate_uuid() -> str:
    return str(uuid.uuid4())


class Reminder(Base):
    __tablename__ = "reminders"

    # UUID string primary key. Clients (phone/web) may supply their own id on
    # create so that reminders created offline get a globally-unique id. If none
    # is supplied, one is generated server-side.
    id = Column(String, primary_key=True, index=True, default=generate_uuid)
    text = Column(String, nullable=False)
    frequency_value = Column(Integer, nullable=False)
    frequency_unit = Column(String, nullable=False)  # hours, days, weeks, months
    start_time = Column(String, nullable=False)  # naive time "HH:MM"
    start_date = Column(String, nullable=True)  # naive date "YYYY-MM-DD" or null (means today/ASAP)
    end_time = Column(String, nullable=True)  # naive time "HH:MM" or null
    end_date = Column(String, nullable=True)  # naive date "YYYY-MM-DD" or null (means never)
    active_hours_start = Column(String, nullable=True)  # override, naive "HH:MM"
    active_hours_end = Column(String, nullable=True)  # override, naive "HH:MM"
    requires_confirmation = Column(Boolean, default=False)  # if True, escalation enabled
    active = Column(Boolean, default=True)
    # Tombstone: soft-delete so deletions propagate through sync. Rows are kept
    # (filtered out of normal listings) until cleaned up after all devices sync.
    deleted = Column(Boolean, default=False, nullable=False)
    deleted_at = Column(DateTime, nullable=True)
    created_at = Column(DateTime, default=datetime.utcnow)
    updated_at = Column(DateTime, default=datetime.utcnow, onupdate=datetime.utcnow)


class Device(Base):
    __tablename__ = "devices"

    # A device registers itself so the server can track how far each has synced
    # (last_sync_at). Tombstones older than the oldest device's last_sync_at are
    # safe to purge. device_id is a client-generated UUID.
    device_id = Column(String, primary_key=True, index=True)
    name = Column(String, nullable=True)
    last_sync_at = Column(DateTime, nullable=True)
    # The phone's current IANA timezone (e.g. "America/Chicago"), reported on
    # each sync. Used by the MCP/Index bridge to resolve relative times ("in 5
    # minutes") in the user's actual zone, which self-corrects as they travel.
    timezone = Column(String, nullable=True)


class Settings(Base):
    __tablename__ = "settings"

    id = Column(Integer, primary_key=True, index=True)
    default_active_hours_start = Column(String, nullable=False, default="08:00")
    default_active_hours_end = Column(String, nullable=False, default="22:00")
    time_format = Column(String, nullable=False, default="12h")  # "12h" or "24h"


class Confirmation(Base):
    __tablename__ = "confirmations"

    id = Column(Integer, primary_key=True, index=True)
    reminder_id = Column(String, ForeignKey("reminders.id"), nullable=False)
    confirmed_at = Column(DateTime, default=datetime.utcnow)
    instance_time = Column(DateTime, nullable=False)
