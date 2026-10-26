from __future__ import annotations

import uuid
from datetime import date, datetime
from decimal import Decimal
from typing import Any

from sqlalchemy import ForeignKey, UniqueConstraint
from sqlalchemy.orm import Mapped, mapped_column

from app.models.base import Base, OwnedMixin, SyncMixin, TimestampMixin, UUIDPkMixin


class Task(UUIDPkMixin, OwnedMixin, SyncMixin, TimestampMixin, Base):
    __tablename__ = "tasks"
    __sync__ = True
    __sync_push__ = True
    __sync_parent__ = "parent_id"
    __sync_sort__ = "due_at"

    parent_id: Mapped[uuid.UUID | None] = mapped_column(ForeignKey("tasks.id", ondelete="CASCADE"), index=True)
    title: Mapped[str]
    notes: Mapped[str | None]
    due_at: Mapped[datetime | None]
    has_time: Mapped[bool] = mapped_column(default=False)
    priority: Mapped[int] = mapped_column(default=1)  # 0 low … 3 urgent
    status: Mapped[str] = mapped_column(default="open")  # proposed | open | done | snoozed | cancelled
    # daily | weekdays | weekly:2,4 | monthly:5 | every:3d — evaluated by app.services.tasks
    repeat_rule: Mapped[str | None]
    remind_at: Mapped[datetime | None]
    completed_at: Mapped[datetime | None]
    postponed_count: Mapped[int] = mapped_column(default=0)
    # firm = an explicit promise, tentative = "надо бы", idea = a passing thought
    commitment: Mapped[str] = mapped_column(default="firm")
    source: Mapped[str] = mapped_column(default="user")  # user | agent | chat | import
    source_ref: Mapped[dict[str, Any]] = mapped_column(default=dict)
    goal_ref: Mapped[str | None]
    history: Mapped[list[Any]] = mapped_column(default=list)


class Habit(UUIDPkMixin, OwnedMixin, SyncMixin, TimestampMixin, Base):
    __tablename__ = "habits"
    __sync__ = True
    __sync_push__ = True
    __sync_sort__ = "created_at"

    name: Mapped[str]
    kind: Mapped[str] = mapped_column(default="good")  # good (do it) | bad (avoid it)
    schedule: Mapped[str] = mapped_column(default="daily")  # same grammar as Task.repeat_rule
    target_value: Mapped[Decimal | None]
    unit: Mapped[str | None]
    is_active: Mapped[bool] = mapped_column(default=True)
    notes: Mapped[str | None]


class HabitLog(UUIDPkMixin, OwnedMixin, SyncMixin, TimestampMixin, Base):
    __tablename__ = "habit_logs"
    __sync__ = True
    __sync_push__ = True
    __sync_parent__ = "habit_id"
    __sync_sort__ = "day"
    __table_args__ = (UniqueConstraint("habit_id", "day"),)

    habit_id: Mapped[uuid.UUID] = mapped_column(ForeignKey("habits.id", ondelete="CASCADE"), index=True)
    day: Mapped[date]
    value: Mapped[Decimal] = mapped_column(default=Decimal(1))
    note: Mapped[str | None]


class Experiment(UUIDPkMixin, OwnedMixin, SyncMixin, TimestampMixin, Base):
    __tablename__ = "experiments"
    __sync__ = True
    __sync_push__ = True
    __sync_sort__ = "start_date"

    title: Mapped[str]
    hypothesis: Mapped[str]
    metrics: Mapped[list[Any]] = mapped_column(default=list)  # [{"key": "games_spend", "source": "finance:category:games"}]
    start_date: Mapped[date]
    end_date: Mapped[date]
    status: Mapped[str] = mapped_column(default="active")  # planned | active | finished | abandoned
    baseline: Mapped[dict[str, Any]] = mapped_column(default=dict)
    results: Mapped[dict[str, Any]] = mapped_column(default=dict)
    conclusion: Mapped[str | None]
