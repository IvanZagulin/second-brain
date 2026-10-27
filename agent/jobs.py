"""Execution of agent_jobs: dispatch by kind, retries with backoff, recurrence, budget pauses, and
the follow-up runner (a small tool-using loop for jobs the agent scheduled for itself)."""

from __future__ import annotations

import asyncio
import calendar
import json
import logging
import re
import uuid
from datetime import datetime, timedelta
from decimal import Decimal
from typing import Any

from sqlalchemy import select, update

from agent import archive, extraction, initiative, reports, rules
from agent.chat import system_prompt
from agent.context import recent_digest
from agent.tools import ToolCtx, execute, tool_definitions
from app.core.db import session_scope
from app.core.errors import ApiError, BudgetExceeded
from app.core.timeutil import local_now, parse_hhmm, tz, utcnow
from app.models import AgentJob, AgentJobRun, User
from app.services.finance.recurring import detect_subscriptions, refresh_tax_calendar
from app.services.imports.pipeline import parse_import
from app.services.llm.client import call
from app.services.notifications import create_notification
from app.services.settings import get_all_settings

log = logging.getLogger(__name__)


# ------------------------------------------------------------------ recurrence

def next_occurrence(rule: str, after: datetime, tz_name: str | None) -> datetime:
    """Grammar: every:15m | every:2h | daily@HH:MM | weekly:1,3@HH:MM | monthly:5@HH:MM (local time)."""
    zone = tz(tz_name)
    local = after.astimezone(zone)
    m = re.fullmatch(r"every:(\d+)([mh])", rule)
    if m:
        n = int(m.group(1))
        return after + (timedelta(minutes=n) if m.group(2) == "m" else timedelta(hours=n))
    kind, _, at = rule.partition("@")
    t = parse_hhmm(at or "09:00")
    base = local.replace(hour=t.hour, minute=t.minute, second=0, microsecond=0)
    if kind == "daily":
        return (base if base > local else base + timedelta(days=1)).astimezone(after.tzinfo)
    if kind.startswith("weekly:"):
        days = sorted(int(d) for d in kind.split(":", 1)[1].split(",") if d.strip())
        for add in range(0, 8):
            cand = base + timedelta(days=add)
            if cand.isoweekday() in days and cand > local:
                return cand.astimezone(after.tzinfo)
    if kind.startswith("monthly:"):
        day = int(kind.split(":", 1)[1])
        y, mo = local.year, local.month
        for _ in range(3):
            d = min(day, calendar.monthrange(y, mo)[1])
            cand = base.replace(year=y, month=mo, day=d)
            if cand > local:
                return cand.astimezone(after.tzinfo)
            y, mo = (y + 1, 1) if mo == 12 else (y, mo + 1)
    return after + timedelta(days=1)


# ------------------------------------------------------------------ system jobs

def system_job_specs(settings: dict[str, Any]) -> list[dict[str, Any]]:
    ini = settings["initiative"]
    specs = [
        {"key": "sys:daily_checks", "kind": "daily_checks", "goal": "Проверки сроков и напоминания", "rule": "every:15m",
         "priority": 2},
        {"key": "sys:nightly", "kind": "nightly", "goal": "Ночное обслуживание", "rule": "daily@03:30", "priority": 0},
    ]
    if ini.get("morning_brief", True):
        specs.append({"key": "sys:morning", "kind": "morning_brief", "goal": "Утреннее сообщение",
                      "rule": f"daily@{ini.get('morning_time', '07:40')}", "priority": 1})
    if ini.get("evening_checkin", True):
        specs.append({"key": "sys:evening", "kind": "evening_checkin", "goal": "Вечерний чек-ин",
                      "rule": f"daily@{ini.get('evening_time', '21:30')}", "priority": 0})
    specs.append({"key": "sys:weekly", "kind": "weekly_report", "goal": "Недельный отчёт",
                  "rule": f"weekly:{ini.get('weekly_report_day', 7)}@{ini.get('weekly_report_time', '19:00')}",
                  "priority": 2})
    return specs


async def ensure_system_jobs() -> None:
    async with session_scope() as db:
        users = (await db.execute(select(User))).scalars().all()
        for user in users:
            settings = await get_all_settings(db, user.id)
            specs = system_job_specs(settings)
            wanted = {s["key"] for s in specs}
            existing = {j.dedupe_key: j for j in (await db.execute(select(AgentJob).where(
                AgentJob.owner_id == user.id, AgentJob.dedupe_key.like("sys:%")))).scalars()}
            for spec in specs:
                job = existing.get(spec["key"])
                if job is None:
                    db.add(AgentJob(owner_id=user.id, kind=spec["kind"], goal=spec["goal"], recurrence=spec["rule"],
                                    next_run_at=next_occurrence(spec["rule"], utcnow(), user.timezone),
                                    priority=spec["priority"], dedupe_key=spec["key"], created_by="system",
                                    max_attempts=2))
                elif job.recurrence != spec["rule"] or job.status in ("cancelled", "failed", "done"):
                    job.recurrence = spec["rule"]
                    job.status = "scheduled"
                    job.next_run_at = next_occurrence(spec["rule"], utcnow(), user.timezone)
            for key, job in existing.items():
                if key not in wanted and job.status == "scheduled":
                    job.status = "cancelled"


# ------------------------------------------------------------------ follow-up runner

async def run_followup(owner_id: uuid.UUID, job: dict[str, Any]) -> dict[str, Any]:
    async with session_scope() as db:
        system = await system_prompt(db, owner_id)
        user = await db.get(User, owner_id)
        tz_name = user.timezone if user else "Europe/Moscow"
        now = local_now(tz_name)
        digest = await recent_digest(db, owner_id, now, since=utcnow() - timedelta(hours=20))
    system += ("\n\n## Фоновое задание\nСейчас ты работаешь в фоне, владелец не в чате. Выполни задание с помощью "
               "инструментов. Писать владельцу — только через message_user и только если это действительно полезно. "
               "В конце коротко опиши итог.")
    tools = tool_definitions(job.get("tools") or None, include_job_only=True)
    messages: list[dict[str, Any]] = [{"role": "user", "content": (
        f"<context>Сейчас {now:%A %d.%m.%Y %H:%M}.\n{digest or ''}</context>\nЗадание: {job['goal']}\n"
        f"Почему запланировано: {job.get('reason') or '—'}")}]
    limit = Decimal(str(job.get("cost_limit_usd") or "0.05"))
    spent = Decimal(0)
    final_text = ""
    for _ in range(6):
        res = await call(owner_id, "background", system=system, messages=messages, tools=tools, max_tokens=4000,
                         job_id=uuid.UUID(job["id"]), priority=job.get("priority", 1))
        spent += res.cost_usd
        messages.append({"role": "assistant", "content": res.content})
        final_text = res.text
        tool_uses = [b for b in res.content if b.get("type") == "tool_use"]
        if not tool_uses or res.stop_reason != "tool_use":
            break
        results = []
        async with session_scope() as db:
            ctx = ToolCtx(db=db, owner_id=owner_id, initiator="job", job_id=uuid.UUID(job["id"]), tz_name=tz_name)
            for tu in tool_uses:
                payload, status = await execute(ctx, tu["name"], tu.get("input"), tu["id"])
                block = {"type": "tool_result", "tool_use_id": tu["id"],
                         "content": json.dumps(payload, ensure_ascii=False, default=str)[:30000]}
                if status in ("error", "denied"):
                    block["is_error"] = True
                results.append(block)
        messages.append({"role": "user", "content": results})
        if spent >= limit:
            break
    return {"summary": final_text[:2000], "cost_usd": float(spent)}


# ------------------------------------------------------------------ dispatcher

async def _nightly(owner_id: uuid.UUID, job: dict) -> dict:
    out: dict[str, Any] = {}
    async with session_scope() as db:
        subs = await detect_subscriptions(db, owner_id)
        for s in subs[:5]:
            await create_notification(db, owner_id, title="Похоже на подписку",
                                      body=f"{s['name']}: ~{s['amount_minor'] // 100} ₽, {s['period']}. Добавить в подписки?",
                                      category="finance", priority="low", silent=True,
                                      data={"subscription_suggestion": s}, dedupe_key=f"subsugg:{s['merchant']}")
        await refresh_tax_calendar(db, owner_id)
        out["subscription_suggestions"] = len(subs)
    for name, fn in (("categorized", reports.categorize_pending), ("profile", reports.refresh_profile_summary)):
        try:
            out[name] = await fn(owner_id)
        except ApiError as exc:
            out[name] = {"error": exc.code}
    return out


HANDLERS = {
    "followup": run_followup,
    "initiative": initiative.run_deferred,
    "extract_conversation": lambda o, j: extraction.extract_conversation(o, uuid.UUID(j["payload"]["conversation_id"])),
    "title_conversation": lambda o, j: reports.title_conversation(o, uuid.UUID(j["payload"]["conversation_id"])),
    "workout_analysis": lambda o, j: reports.workout_analysis(o, uuid.UUID(j["payload"]["workout_id"])),
    "purchase_analysis": lambda o, j: reports.analyze_purchase(o, uuid.UUID(j["payload"]["item_id"])),
    "weekly_report": lambda o, j: reports.weekly_report(o),
    "morning_brief": lambda o, j: reports.morning_brief(o),
    "evening_checkin": lambda o, j: reports.evening_checkin(o),
    "daily_checks": lambda o, j: rules.daily_checks(o),
    "nightly": _nightly,
    "import_parse": lambda o, j: parse_import(o, uuid.UUID(j["payload"]["import_job_id"])),
    "import_analyze": lambda o, j: archive.analyze_import(o, uuid.UUID(j["payload"]["import_job_id"])),
    "categorize_pending": lambda o, j: reports.categorize_pending(o),
    "profile_summary": lambda o, j: reports.refresh_profile_summary(o),
}


def _snapshot(job: AgentJob) -> dict[str, Any]:
    return {"id": str(job.id), "kind": job.kind, "goal": job.goal, "reason": job.reason, "payload": dict(job.payload or {}),
            "priority": job.priority, "tools": list(job.tools or []), "cost_limit_usd": str(job.cost_limit_usd)}


async def _heartbeat(job_id: uuid.UUID, every: int = 300) -> None:
    """Long jobs (an archive analysis runs for hours) touch their row so the scheduler's stuck-job recovery
    (35 minutes without an update) does not start a second copy alongside."""
    while True:
        await asyncio.sleep(every)
        async with session_scope() as db:
            await db.execute(update(AgentJob).where(AgentJob.id == job_id, AgentJob.status == "running")
                             .values(updated_at=utcnow()))


async def run_job(job_id: uuid.UUID) -> dict[str, Any]:
    async with session_scope() as db:
        job = (await db.execute(select(AgentJob).where(AgentJob.id == job_id).with_for_update(skip_locked=True))
               ).scalar_one_or_none()
        if job is None or job.status not in ("queued", "scheduled"):
            return {"skip": True}
        job.status, job.attempts, job.last_run_at = "running", job.attempts + 1, utcnow()
        run = AgentJobRun(owner_id=job.owner_id, job_id=job.id)
        db.add(run)
        await db.flush()
        run_id, owner_id, snap = run.id, job.owner_id, _snapshot(job)
        user = await db.get(User, owner_id)
        tz_name = user.timezone if user else None
    handler = HANDLERS.get(snap["kind"])
    status, result, error = "done", {}, None
    retry_at: datetime | None = None
    try:
        if handler is None:
            raise RuntimeError(f"unknown job kind {snap['kind']}")
        beat = asyncio.create_task(_heartbeat(job_id))
        try:
            result = await handler(owner_id, snap) or {}
        finally:
            beat.cancel()
    except BudgetExceeded as exc:
        status, error = "budget", exc.message
        retry_at = utcnow() + timedelta(hours=6)
    except Exception as exc:
        log.exception("job %s (%s) failed", job_id, snap["kind"])
        status, error = "error", f"{type(exc).__name__}: {str(exc)[:300]}"
    async with session_scope() as db:
        job = await db.get(AgentJob, job_id)
        run = await db.get(AgentJobRun, run_id)
        run.finished_at, run.error = utcnow(), error
        run.output = result if isinstance(result, dict) else {"result": str(result)}
        run.status = "done" if status == "done" else "failed"
        if isinstance(result, dict) and "cost_usd" in result:
            run.cost_usd = Decimal(str(result["cost_usd"]))
        job.result = run.output
        if status == "done" and isinstance(result, dict) and result.get("continue") and job.status != "cancelled":
            # A long job finished one slice (see agent.archive.SLICE_SECONDS): run the next one right away.
            job.status, job.attempts, job.next_run_at = "scheduled", 0, utcnow()
        elif status == "done" or (status == "error" and job.attempts >= job.max_attempts) or job.status == "cancelled":
            if job.recurrence and job.status != "cancelled":
                job.status, job.attempts = "scheduled", 0
                job.next_run_at = next_occurrence(job.recurrence, utcnow(), tz_name)
            elif job.status != "cancelled":
                job.status = "done" if status == "done" else "failed"
        elif status == "budget":
            job.status, job.next_run_at = "scheduled", retry_at
            job.attempts = max(job.attempts - 1, 0)
        else:
            job.status = "scheduled"
            job.next_run_at = utcnow() + timedelta(minutes=2 ** job.attempts)
        job.version += 1
    return {"status": status, "error": error}
