from datetime import datetime
from typing import Optional
from pydantic import BaseModel, Field


# --- Reminder Schemas ---

class ReminderCreate(BaseModel):
    id: Optional[str] = None  # client may supply a UUID (offline-created); else server generates
    text: str = Field(..., min_length=1, max_length=500)
    frequency_value: int = Field(..., ge=1)
    frequency_unit: str = Field(..., pattern="^(minutes|hours|days|weeks|months)$")
    start_time: str = Field(..., pattern=r"^\d{2}:\d{2}$")
    start_date: Optional[str] = Field(None, pattern=r"^\d{4}-\d{2}-\d{2}$")
    end_time: Optional[str] = Field(None, pattern=r"^\d{2}:\d{2}$")
    end_date: Optional[str] = Field(None, pattern=r"^\d{4}-\d{2}-\d{2}$")
    active_hours_start: Optional[str] = Field(None, pattern=r"^\d{2}:\d{2}$")
    active_hours_end: Optional[str] = Field(None, pattern=r"^\d{2}:\d{2}$")
    requires_confirmation: bool = False
    active: bool = True


class ReminderUpdate(BaseModel):
    text: Optional[str] = Field(None, min_length=1, max_length=500)
    frequency_value: Optional[int] = Field(None, ge=1)
    frequency_unit: Optional[str] = Field(None, pattern="^(minutes|hours|days|weeks|months)$")
    start_time: Optional[str] = Field(None, pattern=r"^\d{2}:\d{2}$")
    start_date: Optional[str] = Field(None, pattern=r"^\d{4}-\d{2}-\d{2}$")
    end_time: Optional[str] = Field(None, pattern=r"^\d{2}:\d{2}$")
    end_date: Optional[str] = Field(None, pattern=r"^\d{4}-\d{2}-\d{2}$")
    active_hours_start: Optional[str] = Field(None, pattern=r"^\d{2}:\d{2}$")
    active_hours_end: Optional[str] = Field(None, pattern=r"^\d{2}:\d{2}$")
    requires_confirmation: Optional[bool] = None
    active: Optional[bool] = None


class ReminderResponse(BaseModel):
    id: str
    text: str
    frequency_value: int
    frequency_unit: str
    start_time: str
    start_date: Optional[str]
    end_time: Optional[str]
    end_date: Optional[str]
    active_hours_start: Optional[str]
    active_hours_end: Optional[str]
    requires_confirmation: bool
    active: bool
    deleted: bool
    deleted_at: Optional[datetime]
    created_at: datetime
    updated_at: datetime

    model_config = {"from_attributes": True}


# --- Settings Schemas ---

class SettingsResponse(BaseModel):
    id: int
    default_active_hours_start: str
    default_active_hours_end: str
    time_format: str

    model_config = {"from_attributes": True}


class SettingsUpdate(BaseModel):
    default_active_hours_start: Optional[str] = Field(None, pattern=r"^\d{2}:\d{2}$")
    default_active_hours_end: Optional[str] = Field(None, pattern=r"^\d{2}:\d{2}$")
    time_format: Optional[str] = Field(None, pattern="^(12h|24h)$")


# --- Confirmation Schemas ---

class ConfirmationCreate(BaseModel):
    reminder_id: str
    instance_time: datetime


class ConfirmationResponse(BaseModel):
    id: int
    reminder_id: str
    confirmed_at: datetime
    instance_time: datetime

    model_config = {"from_attributes": True}


# --- Sync Schema ---

class SyncResponse(BaseModel):
    reminders: list[ReminderResponse]
    settings: SettingsResponse
    server_time: datetime  # authoritative clock; client stores as its next `since`
