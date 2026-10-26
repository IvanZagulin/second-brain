from __future__ import annotations

import uuid
from datetime import datetime, timedelta
from typing import Any

from fastapi import APIRouter, Depends, Query
from pydantic import BaseModel
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from agent.persona import DEFAULT_PERSONA
from agent.tools import LEVELS, REGISTRY, ToolCtx, effective_level
from app.core.db import get_db
from app.core.deps import Auth, get_auth
from app.core.errors import ApiError, NotFound
from app.core.timeutil import utcnow
from app.models import (
    AgentAction, AgentEvent, AgentJob, AgentJobRun, AgentProfile, Exercise, FinancialGoal, Memory, PlanExercise,
    PlanRevision, Task, Transaction, WorkoutPlan,
)
from app.services.audit import audit
from app.services.crud import schema_for, serialize
from app.services.settings import get_setting, set_setting

router = APIRouter(prefix="/api/v1/agent", tags=["agent"])

ROLLBACK_TABLES = {"memories": Memory, "transactions": Transaction, "financial_goals": FinancialGoal, "tasks": Task}


class ProfileIn(BaseModel):
    persona: str | None = None
    rules: list[str] | None = None
    style: dict[str, Any] | None = None
    reset_to_default: bool = False


@router.get("/profile")
async def get_profile(auth: Auth = Depends(get_auth), db: AsyncSession = Depends(get_db)):
    p = (await db.execute(select(AgentProfile).where(AgentProfile.owner_id == auth.owner_id,
                                                     AgentProfile.is_active.is_(True)))).scalars().first()
    if p is None:
        return {"persona": DEFAULT_PERSONA, "rules": [], "style": {}, "version": 0, "default_persona": DEFAULT_PERSONA}
    return serialize(p) | {"default_persona": DEFAULT_PERSONA}


@router.put("/profile")
async def put_profile(body: ProfileIn, auth: Auth = Depends(get_auth), db: AsyncSession = Depends(get_db)):
    p = (await db.execute(select(AgentProfile).where(AgentProfile.owner_id == auth.owner_id,
                                                     AgentProfile.is_active.is_(True)))).scalars().first()
    if p is None:
        p = AgentProfile(owner_id=auth.owner_id, persona=DEFAULT_PERSONA, rules=[], style={}, version=0)
        db.add(p)
    if body.reset_to_default:
        p.persona = DEFAULT_PERSONA
    if body.persona is not None:
        p.persona = body.persona.strip() or DEFAULT_PERSONA
    if body.rules is not None:
        p.rules = [r.strip() for r in body.rules if r.strip()]
    if body.style is not None:
        p.style = body.style
    p.version += 1
    await db.flush()
    await audit(db, auth.owner_id, auth.actor, "edit_agent_profile", "agent_profiles", p.id)
    return serialize(p)


@router.get("/tools")
async def tools(auth: Auth = Depends(get_auth), db: AsyncSession = Depends(get_db)):
    out = []
    for name in sorted(REGISTRY):
        spec = REGISTRY[name]
        level, enabled = await effective_level(db, auth.owner_id, spec)
        out.append({"name": name, "description": spec.description, "domain": spec.domain, "level": level,
                    "default_level": spec.level, "min_level": spec.min_level, "enabled": enabled,
                    "writes": spec.writes})
    return {"items": out}


class ToolPermIn(BaseModel):
    level: str | None = None
    enabled: bool | None = None


@router.put("/tools/{name}")
async def set_tool(name: str, body: ToolPermIn, auth: Auth = Depends(get_auth), db: AsyncSession = Depends(get_db)):
    spec = REGISTRY.get(name)
    if spec is None:
        raise NotFound("Инструмент не найден")
    perms = await get_setting(db, auth.owner_id, "tool_permissions") or {}
    cur = dict(perms.get(name) or {})
    if body.level is not None:
        if body.level not in LEVELS:
            raise ApiError("Уровень должен быть A, B или C")
        if LEVELS[body.level] < LEVELS[spec.min_level]:
            raise ApiError(f"Для этого инструмента минимальный уровень — {spec.min_level}", code="level_too_low")
        cur["level"] = body.level
    if body.enabled is not None:
        cur["enabled"] = body.enabled
    perms[name] = cur
    await set_setting(db, auth.owner_id, "tool_permissions", perms, merge=False)
    await audit(db, auth.owner_id, auth.actor, "change_tool_permission", "tool", name, cur)
    return {"name": name, **cur}


@router.get("/actions")
async def actions(status: str | None = None, limit: int = Query(100, le=500), offset: int = 0,
                  auth: Auth = Depends(get_auth), db: AsyncSession = Depends(get_db)):
    q = select(AgentAction).where(AgentAction.owner_id == auth.owner_id)
    if status:
        q = q.where(AgentAction.status == status)
    rows = (await db.execute(q.order_by(AgentAction.created_at.desc()).limit(limit).offset(offset))).scalars().all()
    return {"items": [serialize(a) for a in rows]}


async def _action(db: AsyncSession, auth: Auth, action_id: uuid.UUID) -> AgentAction:
    a = await db.get(AgentAction, action_id)
    if a is None or a.owner_id != auth.owner_id:
        raise NotFound("Действие не найдено")
    return a


@router.post("/actions/{action_id}/confirm")
async def confirm(action_id: uuid.UUID, auth: Auth = Depends(get_auth), db: AsyncSession = Depends(get_db)):
    a = await _action(db, auth, action_id)
    if a.status != "pending_confirmation":
        raise ApiError("Действие уже обработано", code="already_decided")
    spec = REGISTRY.get(a.tool)
    if spec is None or spec.confirm is None:
        raise ApiError("Это действие нельзя выполнить", code="not_confirmable")
    user = auth.user
    ctx = ToolCtx(db=db, owner_id=auth.owner_id, initiator="user", conversation_id=a.conversation_id,
                  tz_name=user.timezone)
    result = await spec.confirm(ctx, spec.schema.model_validate(a.params))
    a.status, a.result, a.decided_at = "done", result, utcnow()
    a.version += 1
    await audit(db, auth.owner_id, auth.actor, "confirm_action", "agent_actions", a.id, {"tool": a.tool})
    return serialize(a)


@router.post("/actions/{action_id}/reject")
async def reject(action_id: uuid.UUID, auth: Auth = Depends(get_auth), db: AsyncSession = Depends(get_db)):
    a = await _action(db, auth, action_id)
    if a.status == "pending_confirmation":
        a.status, a.decided_at = "rejected", utcnow()
        a.version += 1
    return serialize(a)


@router.post("/actions/{action_id}/rollback")
async def rollback(action_id: uuid.UUID, auth: Auth = Depends(get_auth), db: AsyncSession = Depends(get_db)):
    a = await _action(db, auth, action_id)
    rb = a.rollback or {}
    if a.status != "done" or not rb:
        raise ApiError("Это действие нельзя откатить", code="not_reversible")
    if "table" in rb:
        model = ROLLBACK_TABLES[rb["table"]]
        obj = await db.get(model, uuid.UUID(rb["id"]))
        if obj is None:
            raise NotFound("Запись уже удалена")
        values = schema_for(model, "patch").model_validate(rb["values"]).model_dump(exclude_unset=True)
        for k, v in values.items():
            setattr(obj, k, v)
        obj.version += 1
    elif "setting" in rb:
        await set_setting(db, auth.owner_id, rb["setting"], rb["values"])
    elif "plan_revision_id" in rb:
        await restore_plan(db, auth.owner_id, uuid.UUID(rb["plan_revision_id"]))
    a.status, a.decided_at = "rolled_back", utcnow()
    a.version += 1
    await audit(db, auth.owner_id, auth.actor, "rollback_action", "agent_actions", a.id, {"tool": a.tool})
    return serialize(a)


async def restore_plan(db: AsyncSession, owner_id: uuid.UUID, revision_id: uuid.UUID) -> None:
    rev = await db.get(PlanRevision, revision_id)
    if rev is None or rev.owner_id != owner_id:
        raise NotFound("Версия программы не найдена")
    plan = await db.get(WorkoutPlan, rev.plan_id)
    snap = rev.snapshot
    for k, v in schema_for(WorkoutPlan, "patch").model_validate(snap["plan"]).model_dump(exclude_unset=True).items():
        setattr(plan, k, v)
    plan.version += 1
    keep = {uuid.UUID(i["id"]) for i in snap["items"]}
    current = (await db.execute(select(PlanExercise).where(PlanExercise.plan_id == plan.id))).scalars().all()
    for item in current:
        if item.id not in keep and item.deleted_at is None:
            item.deleted_at = utcnow()
            item.version += 1
    patch = schema_for(PlanExercise, "patch")
    for raw in snap["items"]:
        item = await db.get(PlanExercise, uuid.UUID(raw["id"]))
        values = patch.model_validate(raw).model_dump(exclude_unset=True)
        if item is None:
            db.add(PlanExercise(id=uuid.UUID(raw["id"]), owner_id=owner_id, **values))
        else:
            for k, v in values.items():
                setattr(item, k, v)
            item.deleted_at = None
            item.version += 1


@router.get("/jobs")
async def list_jobs(status: str | None = None, auth: Auth = Depends(get_auth), db: AsyncSession = Depends(get_db)):
    q = select(AgentJob).where(AgentJob.owner_id == auth.owner_id)
    if status:
        q = q.where(AgentJob.status == status)
    rows = (await db.execute(q.order_by(AgentJob.next_run_at.desc()).limit(200))).scalars().all()
    return {"items": [serialize(j) for j in rows]}


@router.get("/jobs/{job_id}/runs")
async def job_runs(job_id: uuid.UUID, auth: Auth = Depends(get_auth), db: AsyncSession = Depends(get_db)):
    rows = (await db.execute(select(AgentJobRun).where(AgentJobRun.job_id == job_id,
                                                       AgentJobRun.owner_id == auth.owner_id)
                             .order_by(AgentJobRun.started_at.desc()).limit(50))).scalars().all()
    return {"items": [serialize(r) for r in rows]}


class JobPatch(BaseModel):
    status: str | None = None  # scheduled | paused | cancelled
    next_run_at: datetime | None = None


@router.patch("/jobs/{job_id}")
async def patch_job(job_id: uuid.UUID, body: JobPatch, auth: Auth = Depends(get_auth), db: AsyncSession = Depends(get_db)):
    j = await db.get(AgentJob, job_id)
    if j is None or j.owner_id != auth.owner_id:
        raise NotFound("Задание не найдено")
    if body.status in ("scheduled", "paused", "cancelled"):
        j.status = body.status
    if body.next_run_at:
        j.next_run_at = body.next_run_at
    j.version += 1
    return serialize(j)


@router.post("/jobs/{job_id}/run")
async def run_now(job_id: uuid.UUID, auth: Auth = Depends(get_auth), db: AsyncSession = Depends(get_db)):
    j = await db.get(AgentJob, job_id)
    if j is None or j.owner_id != auth.owner_id:
        raise NotFound("Задание не найдено")
    j.status, j.next_run_at = "scheduled", utcnow()
    return serialize(j)


@router.get("/events")
async def events(limit: int = Query(100, le=500), auth: Auth = Depends(get_auth), db: AsyncSession = Depends(get_db)):
    rows = (await db.execute(select(AgentEvent).where(AgentEvent.owner_id == auth.owner_id)
                             .order_by(AgentEvent.id.desc()).limit(limit))).scalars().all()
    return {"items": [{"id": e.id, "type": e.type, "payload": e.payload, "status": e.status, "result": e.result,
                       "occurred_at": e.occurred_at.isoformat()} for e in rows]}


class PauseIn(BaseModel):
    hours: float | None = None  # None → resume


@router.post("/initiative/pause")
async def pause(body: PauseIn, auth: Auth = Depends(get_auth), db: AsyncSession = Depends(get_db)):
    until = (utcnow() + timedelta(hours=body.hours)).isoformat() if body.hours else None
    value = await set_setting(db, auth.owner_id, "initiative", {"paused_until": until})
    return {"paused_until": value.get("paused_until")}
