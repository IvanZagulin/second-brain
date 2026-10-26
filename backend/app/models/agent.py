from __future__ import annotations

import uuid
from datetime import datetime
from decimal import Decimal
from typing import Any

from sqlalchemy import BigInteger, ForeignKey, Index, Numeric, UniqueConstraint, func
from sqlalchemy.orm import Mapped, mapped_column

from app.models.base import Base, OwnedMixin, SyncMixin, TimestampMixin, UUIDPkMixin


class AgentEvent(OwnedMixin, Base):
    """Something happened (money arrived, workout missed …). Processed by agent.rules."""

    __tablename__ = "agent_events"
    __table_args__ = (
        Index("ix_agent_events_pending", "status", "occurred_at"),
        UniqueConstraint("owner_id", "dedupe_key"),
    )

    id: Mapped[int] = mapped_column(BigInteger, primary_key=True, autoincrement=True)
    type: Mapped[str]
    payload: Mapped[dict[str, Any]] = mapped_column(default=dict)
    dedupe_key: Mapped[str | None]
    occurred_at: Mapped[datetime] = mapped_column(server_default=func.now())
    status: Mapped[str] = mapped_column(default="pending")  # pending | processed | skipped | failed
    processed_at: Mapped[datetime | None]
    result: Mapped[dict[str, Any]] = mapped_column(default=dict)


class AgentJob(UUIDPkMixin, OwnedMixin, SyncMixin, TimestampMixin, Base):
    """A scheduled unit of autonomous work, created by the system, the agent itself or the user."""

    __tablename__ = "agent_jobs"
    __sync__ = True
    __sync_sort__ = "next_run_at"
    __table_args__ = (
        Index("ix_agent_jobs_due", "status", "next_run_at"),
        UniqueConstraint("owner_id", "dedupe_key"),
    )

    kind: Mapped[str]  # followup | initiative | extract_conversation | import_analyze | weekly_report | …
    goal: Mapped[str]
    reason: Mapped[str | None]
    payload: Mapped[dict[str, Any]] = mapped_column(default=dict)
    next_run_at: Mapped[datetime]
    priority: Mapped[int] = mapped_column(default=1)  # 0 low · 1 normal · 2 high · 3 critical
    tools: Mapped[list[Any]] = mapped_column(default=list)  # allowed tool names for agent-run jobs
    cost_limit_usd: Mapped[Decimal] = mapped_column(Numeric(10, 4), default=Decimal("0.05"))
    status: Mapped[str] = mapped_column(default="scheduled")  # scheduled | queued | running | done | failed | cancelled | paused
    recurrence: Mapped[str | None]  # cron-lite: "daily@21:30", "weekly:7@19:00", "every:15m"
    attempts: Mapped[int] = mapped_column(default=0)
    max_attempts: Mapped[int] = mapped_column(default=3)
    dedupe_key: Mapped[str | None]
    last_run_at: Mapped[datetime | None]
    result: Mapped[dict[str, Any]] = mapped_column(default=dict)
    created_by: Mapped[str] = mapped_column(default="system")  # system | agent | user


class AgentJobRun(OwnedMixin, Base):
    __tablename__ = "agent_job_runs"

    id: Mapped[int] = mapped_column(BigInteger, primary_key=True, autoincrement=True)
    job_id: Mapped[uuid.UUID] = mapped_column(ForeignKey("agent_jobs.id", ondelete="CASCADE"), index=True)
    started_at: Mapped[datetime] = mapped_column(server_default=func.now())
    finished_at: Mapped[datetime | None]
    status: Mapped[str] = mapped_column(default="running")
    cost_usd: Mapped[Decimal] = mapped_column(Numeric(12, 6), default=Decimal(0))
    output: Mapped[dict[str, Any]] = mapped_column(default=dict)
    error: Mapped[str | None]


class AgentAction(UUIDPkMixin, OwnedMixin, SyncMixin, TimestampMixin, Base):
    """Journal of every tool call the agent made, with its permission level and undo data."""

    __tablename__ = "agent_actions"
    __sync__ = True
    __sync_sort__ = "created_at"
    __table_args__ = (UniqueConstraint("owner_id", "idempotency_key"),)

    tool: Mapped[str]
    level: Mapped[str]  # A | B | C
    title: Mapped[str | None]
    params: Mapped[dict[str, Any]] = mapped_column(default=dict)
    status: Mapped[str] = mapped_column(default="done")  # done | pending_confirmation | rejected | failed | rolled_back | expired
    result: Mapped[dict[str, Any]] = mapped_column(default=dict)
    rollback: Mapped[dict[str, Any] | None]
    initiator: Mapped[str] = mapped_column(default="chat")  # chat | job | event | user
    conversation_id: Mapped[uuid.UUID | None]
    job_id: Mapped[uuid.UUID | None]
    idempotency_key: Mapped[str | None]
    decided_at: Mapped[datetime | None]
    error: Mapped[str | None]


class Notification(UUIDPkMixin, OwnedMixin, SyncMixin, Base):
    __tablename__ = "notifications"
    __sync__ = True
    __sync_sort__ = "created_at"
    __table_args__ = (UniqueConstraint("owner_id", "dedupe_key"),)

    title: Mapped[str]
    body: Mapped[str]
    category: Mapped[str] = mapped_column(default="agent")  # agent | finance | workout | task | budget | system | confirm
    priority: Mapped[str] = mapped_column(default="normal")  # low | normal | high
    silent: Mapped[bool] = mapped_column(default=False)
    sensitive: Mapped[bool] = mapped_column(default=False)
    conversation_id: Mapped[uuid.UUID | None]
    message_id: Mapped[uuid.UUID | None]
    action_id: Mapped[uuid.UUID | None]
    data: Mapped[dict[str, Any]] = mapped_column(default=dict)
    dedupe_key: Mapped[str | None]
    created_at: Mapped[datetime] = mapped_column(server_default=func.now())
    push_status: Mapped[str] = mapped_column(default="pending")  # pending | sent | failed | skipped
    delivered_at: Mapped[datetime | None]
    read_at: Mapped[datetime | None]
    snoozed_until: Mapped[datetime | None]


class ApiUsage(OwnedMixin, Base):
    __tablename__ = "api_usage"
    __table_args__ = (Index("ix_api_usage_owner_created", "owner_id", "created_at"),)

    id: Mapped[int] = mapped_column(BigInteger, primary_key=True, autoincrement=True)
    created_at: Mapped[datetime] = mapped_column(server_default=func.now())
    model: Mapped[str]
    purpose: Mapped[str]  # chat | analysis | background | import | vision | extraction
    input_tokens: Mapped[int] = mapped_column(default=0)
    output_tokens: Mapped[int] = mapped_column(default=0)
    cache_read_tokens: Mapped[int] = mapped_column(default=0)
    cache_write_tokens: Mapped[int] = mapped_column(default=0)
    cost_usd: Mapped[Decimal] = mapped_column(Numeric(12, 6), default=Decimal(0))
    job_id: Mapped[uuid.UUID | None]
    conversation_id: Mapped[uuid.UUID | None]
    request_id: Mapped[str | None]
    ok: Mapped[bool] = mapped_column(default=True)
    meta: Mapped[dict[str, Any]] = mapped_column(default=dict)


class ApiReservation(OwnedMixin, Base):
    """Estimated cost held before a call starts, so parallel jobs can't overshoot the monthly cap."""

    __tablename__ = "api_reservations"

    id: Mapped[uuid.UUID] = mapped_column(primary_key=True, default=uuid.uuid4)
    amount_usd: Mapped[Decimal] = mapped_column(Numeric(12, 6))
    purpose: Mapped[str]
    created_at: Mapped[datetime] = mapped_column(server_default=func.now())
    expires_at: Mapped[datetime]
