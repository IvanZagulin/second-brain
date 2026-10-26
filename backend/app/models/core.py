from __future__ import annotations

import uuid
from datetime import datetime
from typing import Any

from sqlalchemy import BigInteger, Index, String, UniqueConstraint, func
from sqlalchemy.dialects.postgresql import JSONB
from sqlalchemy.orm import Mapped, mapped_column

from app.models.base import Base, OwnedMixin, SyncMixin, TimestampMixin, UUIDPkMixin


class User(UUIDPkMixin, TimestampMixin, Base):
    __tablename__ = "users"

    name: Mapped[str] = mapped_column(default="")
    timezone: Mapped[str] = mapped_column(default="Europe/Moscow")
    locale: Mapped[str] = mapped_column(default="ru-RU")
    # Daily schedule, body parameters, income plan etc. Edited from the app.
    profile: Mapped[dict[str, Any]] = mapped_column(default=dict)


class Device(UUIDPkMixin, OwnedMixin, TimestampMixin, Base):
    __tablename__ = "devices"

    name: Mapped[str] = mapped_column(default="")
    platform: Mapped[str] = mapped_column(default="android")
    model: Mapped[str | None]
    app_version: Mapped[str | None]
    token_hash: Mapped[str] = mapped_column(String(64), unique=True)
    fcm_token: Mapped[str | None]
    last_seen_at: Mapped[datetime | None]
    revoked_at: Mapped[datetime | None]


class PairingCode(UUIDPkMixin, OwnedMixin, Base):
    __tablename__ = "pairing_codes"

    code_hash: Mapped[str] = mapped_column(String(64), unique=True)
    expires_at: Mapped[datetime]
    used_at: Mapped[datetime | None]
    note: Mapped[str | None]
    created_at: Mapped[datetime] = mapped_column(server_default=func.now())


class AppSetting(OwnedMixin, Base):
    __tablename__ = "app_settings"
    __table_args__ = (UniqueConstraint("owner_id", "key"),)

    id: Mapped[int] = mapped_column(BigInteger, primary_key=True, autoincrement=True)
    key: Mapped[str]
    value: Mapped[Any] = mapped_column(JSONB)
    updated_at: Mapped[datetime] = mapped_column(server_default=func.now(), onupdate=func.now())


class AgentProfile(UUIDPkMixin, OwnedMixin, TimestampMixin, Base):
    """The agent's character. Only one is active; edits create a new version number."""

    __tablename__ = "agent_profiles"

    name: Mapped[str] = mapped_column(default="Основной")
    persona: Mapped[str]
    rules: Mapped[list[Any]] = mapped_column(default=list)
    style: Mapped[dict[str, Any]] = mapped_column(default=dict)
    is_active: Mapped[bool] = mapped_column(default=True)
    version: Mapped[int] = mapped_column(default=1)


class Attachment(UUIDPkMixin, OwnedMixin, Base):
    __tablename__ = "attachments"

    kind: Mapped[str]  # image | pdf | audio | document | file
    mime: Mapped[str]
    file_name: Mapped[str | None]
    path: Mapped[str]
    size: Mapped[int] = mapped_column(BigInteger)
    sha256: Mapped[str] = mapped_column(String(64), index=True)
    meta: Mapped[dict[str, Any]] = mapped_column(default=dict)
    created_at: Mapped[datetime] = mapped_column(server_default=func.now())


class AuditLog(OwnedMixin, Base):
    __tablename__ = "audit_log"
    __table_args__ = (Index("ix_audit_log_owner_at", "owner_id", "at"),)

    id: Mapped[int] = mapped_column(BigInteger, primary_key=True, autoincrement=True)
    at: Mapped[datetime] = mapped_column(server_default=func.now())
    actor: Mapped[str]            # device:<id> | agent | system | cli
    action: Mapped[str]           # read_sensitive | update | delete | confirm | ...
    entity: Mapped[str | None]
    entity_id: Mapped[str | None]
    details: Mapped[dict[str, Any]] = mapped_column(default=dict)
    ip: Mapped[str | None]


class SyncEvent(OwnedMixin, Base):
    __tablename__ = "sync_events"

    id: Mapped[int] = mapped_column(BigInteger, primary_key=True, autoincrement=True)
    device_id: Mapped[uuid.UUID | None]
    direction: Mapped[str]  # push | pull
    entity: Mapped[str | None]
    entity_id: Mapped[str | None]
    op: Mapped[str | None]
    status: Mapped[str]
    details: Mapped[dict[str, Any]] = mapped_column(default=dict)
    created_at: Mapped[datetime] = mapped_column(server_default=func.now())


class Note(UUIDPkMixin, OwnedMixin, SyncMixin, TimestampMixin, Base):
    """Quick notes written on the phone (works offline); the agent reads them as memory input."""

    __tablename__ = "notes"
    __sync__ = True
    __sync_push__ = True
    __sync_sort__ = "created_at"

    text: Mapped[str]
    tags: Mapped[list[Any]] = mapped_column(default=list)
    processed_at: Mapped[datetime | None]
