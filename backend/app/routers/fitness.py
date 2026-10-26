from __future__ import annotations

import uuid
from datetime import datetime, timedelta
from decimal import Decimal
from typing import Any

from fastapi import APIRouter, Depends, Query
from pydantic import BaseModel, Field
from sqlalchemy import func, select
from sqlalchemy.dialects.postgresql import insert
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.db import get_db
from app.core.deps import Auth, get_auth
from app.core.errors import NotFound
from app.core.timeutil import utcnow
from app.models import (
    BodyMetric, Exercise, HealthRecord, Meal, PlanExercise, PlanRevision, ProgressPhoto, Workout, WorkoutPlan, WorkoutSet,
)
from app.routers.agent import restore_plan
from app.services.crud import crud_router, serialize

router = APIRouter(prefix="/api/v1/workouts", tags=["fitness"])


@router.get("/progress/{exercise_id}")
async def progress(exercise_id: uuid.UUID, auth: Auth = Depends(get_auth), db: AsyncSession = Depends(get_db)):
    ex = await db.get(Exercise, exercise_id)
    if ex is None or ex.owner_id != auth.owner_id:
        raise NotFound("Упражнение не найдено")
    rows = (await db.execute(select(WorkoutSet, Workout.started_at).join(Workout, Workout.id == WorkoutSet.workout_id)
                             .where(WorkoutSet.exercise_id == exercise_id, WorkoutSet.deleted_at.is_(None),
                                    WorkoutSet.is_warmup.is_(False), Workout.deleted_at.is_(None))
                             .order_by(Workout.started_at))).all()
    sessions: dict[uuid.UUID, dict[str, Any]] = {}
    for s, started in rows:
        w = float(s.weight or 0)
        e1rm = w * (1 + (s.reps or 0) / 30)
        d = sessions.setdefault(s.workout_id, {"date": started.date().isoformat(), "top_weight": 0.0, "volume": 0.0,
                                               "e1rm": 0.0, "sets": 0})
        d["top_weight"] = max(d["top_weight"], w)
        d["volume"] += w * (s.reps or 0)
        d["e1rm"] = max(d["e1rm"], round(e1rm, 1))
        d["sets"] += 1
    return {"exercise": serialize(ex), "sessions": list(sessions.values())}


@router.get("/stats")
async def stats(days: int = Query(56, le=365), auth: Auth = Depends(get_auth), db: AsyncSession = Depends(get_db)):
    since = utcnow() - timedelta(days=days)
    workouts = (await db.execute(select(Workout).where(Workout.owner_id == auth.owner_id, Workout.deleted_at.is_(None),
                                                       Workout.started_at >= since).order_by(Workout.started_at))).scalars().all()
    by_group = (await db.execute(
        select(Exercise.muscle_group, func.count(), func.coalesce(func.sum(WorkoutSet.weight * WorkoutSet.reps), 0))
        .join(WorkoutSet, WorkoutSet.exercise_id == Exercise.id).join(Workout, Workout.id == WorkoutSet.workout_id)
        .where(Workout.owner_id == auth.owner_id, Workout.started_at >= since, WorkoutSet.deleted_at.is_(None),
               WorkoutSet.is_warmup.is_(False))
        .group_by(Exercise.muscle_group))).all()
    return {"workouts": [serialize(w) for w in workouts],
            "by_muscle_group": [{"group": g, "sets": n, "volume": float(v or 0)} for g, n, v in by_group]}


@router.get("/plans/{plan_id}/revisions")
async def revisions(plan_id: uuid.UUID, auth: Auth = Depends(get_auth), db: AsyncSession = Depends(get_db)):
    rows = (await db.execute(select(PlanRevision).where(PlanRevision.plan_id == plan_id,
                                                        PlanRevision.owner_id == auth.owner_id)
                             .order_by(PlanRevision.created_at.desc()))).scalars().all()
    return {"items": [serialize(r) for r in rows]}


@router.post("/plans/{plan_id}/revisions/{revision_id}/restore")
async def restore(plan_id: uuid.UUID, revision_id: uuid.UUID, auth: Auth = Depends(get_auth),
                  db: AsyncSession = Depends(get_db)):
    plan = await db.get(WorkoutPlan, plan_id)
    if plan is None or plan.owner_id != auth.owner_id:
        raise NotFound("Программа не найдена")
    items = (await db.execute(select(PlanExercise).where(PlanExercise.plan_id == plan_id,
                                                         PlanExercise.deleted_at.is_(None)))).scalars().all()
    db.add(PlanRevision(owner_id=auth.owner_id, plan_id=plan_id, author="user", reason="перед откатом",
                        snapshot={"plan": serialize(plan), "items": [serialize(i) for i in items]}))
    await restore_plan(db, auth.owner_id, revision_id)
    return {"ok": True}


class HealthRecordIn(BaseModel):
    kind: str
    start_at: datetime
    end_at: datetime | None = None
    value: float
    unit: str | None = None
    source: str = "health_connect"
    data: dict[str, Any] = Field(default_factory=dict)


class HealthBatch(BaseModel):
    items: list[HealthRecordIn] = Field(max_length=2000)


health_router = APIRouter(prefix="/api/v1/health", tags=["fitness"])


@health_router.post("/records")
async def health_records(body: HealthBatch, auth: Auth = Depends(get_auth), db: AsyncSession = Depends(get_db)):
    if not body.items:
        return {"stored": 0}
    values = [{"id": uuid.uuid4(), "owner_id": auth.owner_id, **i.model_dump(), "value": Decimal(str(i.value))}
              for i in body.items]
    stmt = insert(HealthRecord).values(values)
    stmt = stmt.on_conflict_do_update(index_elements=["owner_id", "kind", "start_at", "source"],
                                      set_={"value": stmt.excluded.value, "end_at": stmt.excluded.end_at,
                                            "data": stmt.excluded.data})
    await db.execute(stmt)
    return {"stored": len(values)}


@health_router.get("/summary")
async def health_summary(days: int = Query(14, le=365), auth: Auth = Depends(get_auth), db: AsyncSession = Depends(get_db)):
    since = utcnow() - timedelta(days=days)
    rows = (await db.execute(select(HealthRecord.kind, func.date(HealthRecord.start_at), func.sum(HealthRecord.value),
                                    func.avg(HealthRecord.value))
                             .where(HealthRecord.owner_id == auth.owner_id, HealthRecord.start_at >= since)
                             .group_by(HealthRecord.kind, func.date(HealthRecord.start_at))
                             .order_by(func.date(HealthRecord.start_at)))).all()
    out: dict[str, list] = {}
    for kind, day, total, avg in rows:
        out.setdefault(kind, []).append({"date": day.isoformat(), "sum": float(total or 0), "avg": float(avg or 0)})
    return out


class MealEstimateIn(BaseModel):
    description: str | None = None
    attachment_id: uuid.UUID | None = None
    estimate: bool = True


@health_router.post("/meals/estimate")
async def meal_estimate(body: MealEstimateIn, auth: Auth = Depends(get_auth), db: AsyncSession = Depends(get_db)):
    """Save a meal; with an estimate, Claude evaluates calories and macros (with an error margin)."""
    from agent.estimates import estimate_meal
    await db.commit()
    meal = await estimate_meal(auth.owner_id, body.description, body.attachment_id, body.estimate)
    return serialize(meal)


crud_routers = [
    crud_router(Exercise, "/api/v1/workouts/exercises", "fitness", order_by="name"),
    crud_router(WorkoutPlan, "/api/v1/workouts/plans", "fitness", order_by="weekday"),
    crud_router(PlanExercise, "/api/v1/workouts/plan-exercises", "fitness", order_by="position"),
    crud_router(Workout, "/api/v1/workouts/sessions", "fitness", order_by="started_at"),
    crud_router(WorkoutSet, "/api/v1/workouts/sets", "fitness", order_by="done_at"),
    crud_router(BodyMetric, "/api/v1/health/metrics", "fitness", order_by="measured_at"),
    crud_router(ProgressPhoto, "/api/v1/health/photos", "fitness", order_by="taken_at"),
    crud_router(Meal, "/api/v1/health/meals", "fitness", order_by="eaten_at"),
]
