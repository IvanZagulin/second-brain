from __future__ import annotations

import uuid
from datetime import datetime
from decimal import Decimal
from typing import Any

from sqlalchemy import ForeignKey, Index, UniqueConstraint, func
from sqlalchemy.orm import Mapped, mapped_column

from app.models.base import Base, OwnedMixin, SyncMixin, TimestampMixin, UUIDPkMixin


class Exercise(UUIDPkMixin, OwnedMixin, SyncMixin, TimestampMixin, Base):
    __tablename__ = "exercises"
    __sync__ = True
    __sync_push__ = True
    __sync_sort__ = "created_at"

    name: Mapped[str]
    muscle_group: Mapped[str]  # back | biceps | shoulders | chest | triceps | legs | core | cardio | other
    secondary_groups: Mapped[list[Any]] = mapped_column(default=list)
    equipment: Mapped[str | None]
    is_bodyweight: Mapped[bool] = mapped_column(default=False)
    notes: Mapped[str | None]
    archived: Mapped[bool] = mapped_column(default=False)


class WorkoutPlan(UUIDPkMixin, OwnedMixin, SyncMixin, TimestampMixin, Base):
    __tablename__ = "workout_plans"
    __sync__ = True
    __sync_push__ = True
    __sync_sort__ = "created_at"

    name: Mapped[str]
    weekday: Mapped[int | None]  # 1 = Monday … 7 = Sunday
    muscle_groups: Mapped[list[Any]] = mapped_column(default=list)
    planned_minutes: Mapped[int | None]
    notes: Mapped[str | None]
    is_active: Mapped[bool] = mapped_column(default=True)
    sort: Mapped[int] = mapped_column(default=0)


class PlanExercise(UUIDPkMixin, OwnedMixin, SyncMixin, TimestampMixin, Base):
    __tablename__ = "plan_exercises"
    __sync__ = True
    __sync_push__ = True
    __sync_parent__ = "plan_id"
    __sync_sort__ = "created_at"

    plan_id: Mapped[uuid.UUID] = mapped_column(ForeignKey("workout_plans.id", ondelete="CASCADE"), index=True)
    exercise_id: Mapped[uuid.UUID] = mapped_column(ForeignKey("exercises.id", ondelete="CASCADE"))
    position: Mapped[int] = mapped_column(default=0)
    target_sets: Mapped[int | None]
    target_reps: Mapped[str | None]  # "8-12"
    target_weight: Mapped[Decimal | None]
    rest_seconds: Mapped[int | None]
    notes: Mapped[str | None]


class PlanRevision(UUIDPkMixin, OwnedMixin, Base):
    """Snapshot of a plan before every change, so agent edits can be rolled back."""

    __tablename__ = "plan_revisions"

    plan_id: Mapped[uuid.UUID] = mapped_column(ForeignKey("workout_plans.id", ondelete="CASCADE"), index=True)
    snapshot: Mapped[dict[str, Any]]
    reason: Mapped[str | None]
    author: Mapped[str] = mapped_column(default="user")  # user | agent
    action_id: Mapped[uuid.UUID | None]
    created_at: Mapped[datetime] = mapped_column(server_default=func.now())


class Workout(UUIDPkMixin, OwnedMixin, SyncMixin, TimestampMixin, Base):
    __tablename__ = "workouts"
    __sync__ = True
    __sync_push__ = True
    __sync_sort__ = "started_at"

    plan_id: Mapped[uuid.UUID | None] = mapped_column(ForeignKey("workout_plans.id", ondelete="SET NULL"))
    started_at: Mapped[datetime]
    ended_at: Mapped[datetime | None]
    status: Mapped[str] = mapped_column(default="in_progress")  # in_progress | done | skipped | cancelled
    feeling: Mapped[int | None]  # 1..10
    pain_note: Mapped[str | None]
    notes: Mapped[str | None]
    skip_reason: Mapped[str | None]
    analysis: Mapped[str | None]
    analysis_at: Mapped[datetime | None]


class WorkoutSet(UUIDPkMixin, OwnedMixin, SyncMixin, TimestampMixin, Base):
    __tablename__ = "workout_sets"
    __sync__ = True
    __sync_push__ = True
    __sync_parent__ = "workout_id"
    __sync_sort__ = "done_at"
    __table_args__ = (Index("ix_workout_sets_exercise", "owner_id", "exercise_id", "done_at"),)

    workout_id: Mapped[uuid.UUID] = mapped_column(ForeignKey("workouts.id", ondelete="CASCADE"), index=True)
    exercise_id: Mapped[uuid.UUID] = mapped_column(ForeignKey("exercises.id", ondelete="CASCADE"))
    set_no: Mapped[int] = mapped_column(default=1)
    reps: Mapped[int | None]
    weight: Mapped[Decimal | None]
    rpe: Mapped[Decimal | None]
    rest_seconds: Mapped[int | None]
    is_warmup: Mapped[bool] = mapped_column(default=False)
    note: Mapped[str | None]
    done_at: Mapped[datetime | None]


class BodyMetric(UUIDPkMixin, OwnedMixin, SyncMixin, TimestampMixin, Base):
    __tablename__ = "body_metrics"
    __sync__ = True
    __sync_push__ = True
    __sync_sort__ = "measured_at"

    measured_at: Mapped[datetime]
    kind: Mapped[str]  # weight | waist | chest | arm | hips | thigh | neck | body_fat | custom
    value: Mapped[Decimal]
    unit: Mapped[str] = mapped_column(default="kg")
    note: Mapped[str | None]


class ProgressPhoto(UUIDPkMixin, OwnedMixin, SyncMixin, TimestampMixin, Base):
    """Metadata only: photos stay in the phone's private storage unless uploaded on purpose."""

    __tablename__ = "progress_photos"
    __sync__ = True
    __sync_push__ = True
    __sync_sort__ = "taken_at"

    taken_at: Mapped[datetime]
    pose: Mapped[str | None]  # front | side | back
    local_path: Mapped[str | None]
    attachment_id: Mapped[uuid.UUID | None]
    weight: Mapped[Decimal | None]
    note: Mapped[str | None]


class HealthRecord(UUIDPkMixin, OwnedMixin, Base):
    __tablename__ = "health_records"
    __table_args__ = (UniqueConstraint("owner_id", "kind", "start_at", "source"),)

    kind: Mapped[str]  # steps | sleep | heart_rate | resting_hr | active_minutes | calories | weight
    start_at: Mapped[datetime]
    end_at: Mapped[datetime | None]
    value: Mapped[Decimal]
    unit: Mapped[str | None]
    source: Mapped[str] = mapped_column(default="health_connect")
    data: Mapped[dict[str, Any]] = mapped_column(default=dict)


class Meal(UUIDPkMixin, OwnedMixin, SyncMixin, TimestampMixin, Base):
    __tablename__ = "meals"
    __sync__ = True
    __sync_push__ = True
    __sync_sort__ = "eaten_at"

    eaten_at: Mapped[datetime]
    description: Mapped[str | None]
    attachment_id: Mapped[uuid.UUID | None]
    kcal: Mapped[int | None]
    protein_g: Mapped[Decimal | None]
    fat_g: Mapped[Decimal | None]
    carbs_g: Mapped[Decimal | None]
    error_pct: Mapped[int | None]  # estimate uncertainty, ±%
    estimated_by: Mapped[str | None]  # user | llm
    note: Mapped[str | None]
