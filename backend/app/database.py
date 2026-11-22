import os
from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker, DeclarativeBase

DATABASE_URL = os.getenv("DATABASE_URL", "sqlite:///data/90hd.db")

# SQLite needs check_same_thread=False for FastAPI's async usage
connect_args = {"check_same_thread": False} if "sqlite" in DATABASE_URL else {}

engine = create_engine(DATABASE_URL, connect_args=connect_args)
SessionLocal = sessionmaker(autocommit=False, autoflush=False, bind=engine)


class Base(DeclarativeBase):
    pass


def get_db():
    db = SessionLocal()
    try:
        yield db
    finally:
        db.close()


def init_db():
    """Create all tables and seed default settings if needed."""
    from app.models import Reminder, Settings, Confirmation  # noqa: F401

    Base.metadata.create_all(bind=engine)

    # Seed default settings if not present
    db = SessionLocal()
    try:
        from app.models import Settings as SettingsModel
        existing = db.query(SettingsModel).first()
        if not existing:
            default_settings = SettingsModel(
                default_active_hours_start="08:00",
                default_active_hours_end="22:00",
                time_format="12h",
            )
            db.add(default_settings)
            db.commit()
    finally:
        db.close()
