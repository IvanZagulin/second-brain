"""Tasks, notifications, sync, API usage and the aggregated home screen."""

from __future__ import annotations

import uuid
from datetime import datetime, timedelta

from fastapi import APIRouter, Depends, Query
from pydantic import BaseModel, Field
from sqlalchemy import func, select, update
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.db import get_db
from app.core.deps import Auth, get_auth
from app.core.errors import NotFound
from app.core.timeutil import local_day_bounds, local_now, utcnow
from app.models import (
    ApiUsage, Conversation, Experiment, Habit, HabitLog, LifeEvent, Message, MoodEntry, Note, Notification, Task,
    Workout, WorkoutPlan,
)
from app.services import sync as syncsvc
from app.services.crud import crud_router, serialize
from app.services.finance.engine import finance_summary
from app.services.llm.budget import usage_summary
from app.services.settings import get_setting

# ---------------------------------------------------------------- notifications

notifications_router = APIRouter(prefix="/api/v1/notifications", tags=["notifications"])


@notifications_router.get("")
async def list_notifications(since: datetime | None = None, unread_only: bool = False,
                             limit: int = Query(50, le=200), auth: Auth = Depends(get_auth),
                             db: AsyncSession = Depends(get_db)):
    q = select(Notification).where(Notification.owner_id == auth.owner_id, Notification.deleted_at.is_(None))
    if since:
        q = q.where(Notification.created_at > since)
    if unread_only:
        q = q.where(Notification.read_at.is_(None))
    rows = (await db.execute(q.order_by(Notification.created_at.desc()).limit(limit))).scalars().all()
    return {"items": [serialize(n) for n in rows]}


@notifications_router.post("/{nid}/read")
async def read(nid: uuid.UUID, auth: Auth = Depends(get_auth), db: AsyncSession = Depends(get_db)):
    n = await db.get(Notification, nid)
    if n is None or n.owner_id != auth.owner_id:
        raise NotFound("Уведомление не найдено")
    n.read_at = n.read_at or utcnow()
    n.version += 1
    return {"ok": True}


@notifications_router.post("/read-all")
async def read_all(auth: Auth = Depends(get_auth), db: AsyncSession = Depends(get_db)):
    await db.execute(update(Notification).where(Notification.owner_id == auth.owner_id, Notification.read_at.is_(None))
                     .values(read_at=utcnow(), version=Notification.version + 1))
    return {"ok": True}


class SnoozeIn(BaseModel):
    minutes: int = Field(60, ge=5, le=60 * 24 * 7)


@notifications_router.post("/{nid}/snooze")
async def snooze(nid: uuid.UUID, body: SnoozeIn, auth: Auth = Depends(get_auth), db: AsyncSession = Depends(get_db)):
    n = await db.get(Notification, nid)
    if n is None or n.owner_id != auth.owner_id:
        raise NotFound("Уведомление не найдено")
    n.snoozed_until = utcnow() + timedelta(minutes=body.minutes)
    n.version += 1
    data = dict(n.data or {})
    if data.get("task_id"):
        t = await db.get(Task, uuid.UUID(data["task_id"]))
        if t is not None:
            t.remind_at = n.snoozed_until
            t.version += 1
    return {"snoozed_until": n.snoozed_until.isoformat()}


# ---------------------------------------------------------------- sync

sync_router = APIRouter(prefix="/api/v1/sync", tags=["sync"])


class PushIn(BaseModel):
    changes: list[syncsvc.Change] = Field(max_length=1000)


@sync_router.post("/push")
async def push(body: PushIn, auth: Auth = Depends(get_auth), db: AsyncSession = Depends(get_db)):
    return {"results": await syncsvc.push(db, auth.owner_id, auth.device.id, body.changes)}


@sync_router.get("/pull")
async def pull(cursor: int = 0, limit: int = Query(500, le=2000), entities: str | None = None,
               auth: Auth = Depends(get_auth), db: AsyncSession = Depends(get_db)):
    return await syncsvc.pull(db, auth.owner_id, cursor, limit, entities.split(",") if entities else None)


# ---------------------------------------------------------------- usage

usage_router = APIRouter(prefix="/api/v1/usage", tags=["usage"])


@usage_router.get("/summary")
async def usage(auth: Auth = Depends(get_auth), db: AsyncSession = Depends(get_db)):
    return await usage_summary(db, auth.owner_id)


@usage_router.get("/daily")
async def usage_daily(days: int = Query(30, le=366), auth: Auth = Depends(get_auth), db: AsyncSession = Depends(get_db)):
    since = utcnow() - timedelta(days=days)
    rows = (await db.execute(select(func.date(ApiUsage.created_at), ApiUsage.purpose, func.sum(ApiUsage.cost_usd),
                                    func.count())
                             .where(ApiUsage.owner_id == auth.owner_id, ApiUsage.created_at >= since)
                             .group_by(func.date(ApiUsage.created_at), ApiUsage.purpose)
                             .order_by(func.date(ApiUsage.created_at)))).all()
    return {"items": [{"date": d.isoformat(), "purpose": p, "cost_usd": float(c or 0), "requests": n} for d, p, c, n in rows]}


# ---------------------------------------------------------------- home

home_router = APIRouter(prefix="/api/v1/home", tags=["home"])


@home_router.get("")
async def home(auth: Auth = Depends(get_auth), db: AsyncSession = Depends(get_db)):
    user = auth.user
    now = local_now(user.timezone)
    hour = now.hour
    greeting = "Доброй ночи" if hour < 5 else "Доброе утро" if hour < 12 else "Добрый день" if hour < 18 else "Добрый вечер"
    name = user.name.split()[0] if user.name else ""
    agent_conv = (await db.execute(select(Conversation).where(Conversation.owner_id == auth.owner_id,
                                                              Conversation.kind == "agent"))).scalars().first()
    observation = None
    if agent_conv:
        m = (await db.execute(select(Message).where(Message.conversation_id == agent_conv.id, Message.role == "assistant",
                                                    Message.deleted_at.is_(None))
                              .order_by(Message.created_at.desc()).limit(1))).scalar_one_or_none()
        if m:
            observation = {"text": m.text, "at": m.created_at.isoformat(), "conversation_id": str(agent_conv.id)}
    try:
        fin = await finance_summary(db, auth.owner_id)
        finance = {k: fin[k] for k in ("free_minor", "daily_budget_minor", "today_left_minor", "today_spent_minor",
                                       "days_until_income", "next_income_date", "estimated", "debts_total_minor",
                                       "overspent_today", "savings_mode")}
        finance["obligations"] = fin["upcoming_30d"][:4]
    except Exception:
        finance = None
    fit = await get_setting(db, auth.owner_id, "fitness")
    days = fit.get("training_days") or []
    next_workout = None
    for add in range(0, 8):
        d = now + timedelta(days=add)
        if d.isoweekday() in days:
            plan = (await db.execute(select(WorkoutPlan).where(WorkoutPlan.owner_id == auth.owner_id,
                                                               WorkoutPlan.weekday == d.isoweekday(),
                                                               WorkoutPlan.deleted_at.is_(None)))).scalars().first()
            next_workout = {"date": d.date().isoformat(), "in_days": add, "plan_id": str(plan.id) if plan else None,
                            "plan_name": plan.name if plan else None, "time": fit.get("training_time")}
            break
    _, day_end = local_day_bounds(now.date(), user.timezone)
    tasks = (await db.execute(select(Task).where(Task.owner_id == auth.owner_id, Task.status == "open",
                                                 Task.deleted_at.is_(None),
                                                 (Task.due_at.is_(None)) | (Task.due_at < day_end))
                              .order_by(Task.due_at.asc().nulls_last(), Task.priority.desc()).limit(5))).scalars().all()
    mood = (await db.execute(select(MoodEntry).where(MoodEntry.owner_id == auth.owner_id, MoodEntry.deleted_at.is_(None))
                             .order_by(MoodEntry.occurred_at.desc()).limit(1))).scalar_one_or_none()
    events = (await db.execute(select(LifeEvent).where(LifeEvent.owner_id == auth.owner_id, LifeEvent.deleted_at.is_(None))
                               .order_by(LifeEvent.occurred_at.desc().nulls_last()).limit(3))).scalars().all()
    last_workout = (await db.execute(select(Workout).where(Workout.owner_id == auth.owner_id, Workout.status == "done",
                                                           Workout.deleted_at.is_(None))
                                     .order_by(Workout.started_at.desc()).limit(1))).scalar_one_or_none()
    usage = await usage_summary(db, auth.owner_id)
    unread = (await db.execute(select(func.count()).select_from(Notification).where(
        Notification.owner_id == auth.owner_id, Notification.read_at.is_(None)))).scalar_one()
    return {
        "greeting": f"{greeting}{', ' + name if name else ''}",
        "now": now.isoformat(),
        "observation": observation,
        "finance": finance,
        "next_workout": next_workout,
        "last_workout": serialize(last_workout) if last_workout else None,
        "tasks": [serialize(t) for t in tasks],
        "mood": serialize(mood) if mood else None,
        "events": [serialize(e) for e in events],
        "api_budget": {"spent_usd": usage["spent_usd"], "limit_usd": usage["limit_usd"], "ratio": usage["ratio"]},
        "unread_notifications": unread,
    }


experiments_router = APIRouter(prefix="/api/v1/experiments", tags=["tasks"])


@experiments_router.post("/{exp_id}/evaluate")
async def evaluate(exp_id: uuid.UUID, auth: Auth = Depends(get_auth), db: AsyncSession = Depends(get_db)):
    from agent.estimates import evaluate_experiment
    await db.commit()
    return serialize(await evaluate_experiment(auth.owner_id, exp_id))


crud_routers = [
    experiments_router,
    crud_router(Task, "/api/v1/tasks", "tasks", order_by="due_at"),
    crud_router(Habit, "/api/v1/habits", "tasks", order_by="created_at"),
    crud_router(HabitLog, "/api/v1/habit-logs", "tasks", order_by="day"),
    crud_router(Experiment, "/api/v1/experiments", "tasks", order_by="start_date"),
    crud_router(Note, "/api/v1/notes", "tasks", order_by="created_at"),
]
