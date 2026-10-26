"""Monthly Claude API budget: reservations before every call, usage records after it, warnings at
50/80/95 %, background pause and model degradation near the cap, hard stop at 100 %.

The cap here is a second line of defence; the spend limit in Claude Console is the first."""

from __future__ import annotations

import calendar
import logging
import uuid
from datetime import timedelta
from decimal import Decimal
from typing import Any

from sqlalchemy import delete, func, select, text
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.db import session_scope
from app.core.errors import BudgetExceeded
from app.core.logging import log_event
from app.core.timeutil import utcnow
from app.models import ApiReservation, ApiUsage
from app.services.llm.pricing import cost_usd
from app.services.settings import get_setting

log = logging.getLogger(__name__)

INTERACTIVE_PURPOSES = {"chat", "analysis"}
RESERVATION_TTL = timedelta(minutes=20)


def _month_start():
    now = utcnow()
    return now.replace(day=1, hour=0, minute=0, second=0, microsecond=0)


async def month_spent(db: AsyncSession, owner_id: uuid.UUID) -> Decimal:
    value = (await db.execute(
        select(func.coalesce(func.sum(ApiUsage.cost_usd), 0))
        .where(ApiUsage.owner_id == owner_id, ApiUsage.created_at >= _month_start())
    )).scalar_one()
    return Decimal(value)


async def active_reserved(db: AsyncSession, owner_id: uuid.UUID) -> Decimal:
    value = (await db.execute(
        select(func.coalesce(func.sum(ApiReservation.amount_usd), 0))
        .where(ApiReservation.owner_id == owner_id, ApiReservation.expires_at > utcnow())
    )).scalar_one()
    return Decimal(value)


async def budget_state(db: AsyncSession, owner_id: uuid.UUID) -> dict[str, Any]:
    cfg = await get_setting(db, owner_id, "api_budget")
    limit = Decimal(str(cfg["monthly_limit_usd"]))
    spent = await month_spent(db, owner_id)
    reserved = await active_reserved(db, owner_id)
    ratio = float((spent + reserved) / limit) if limit > 0 else 1.0
    return {"limit": limit, "spent": spent, "reserved": reserved, "ratio": ratio, "config": cfg}


async def usage_summary(db: AsyncSession, owner_id: uuid.UUID) -> dict[str, Any]:
    state = await budget_state(db, owner_id)
    start = _month_start()
    now = utcnow()
    by_model = (await db.execute(
        select(ApiUsage.model, func.sum(ApiUsage.cost_usd), func.count(),
               func.sum(ApiUsage.input_tokens), func.sum(ApiUsage.output_tokens),
               func.sum(ApiUsage.cache_read_tokens))
        .where(ApiUsage.owner_id == owner_id, ApiUsage.created_at >= start)
        .group_by(ApiUsage.model)
    )).all()
    by_purpose = (await db.execute(
        select(ApiUsage.purpose, func.sum(ApiUsage.cost_usd), func.count())
        .where(ApiUsage.owner_id == owner_id, ApiUsage.created_at >= start)
        .group_by(ApiUsage.purpose)
    )).all()
    days_in_month = calendar.monthrange(now.year, now.month)[1]
    elapsed_days = max((now - start).total_seconds() / 86400, 0.5)
    forecast = state["spent"] / Decimal(str(elapsed_days)) * days_in_month
    background_cost = sum((Decimal(r[1] or 0) for r in by_purpose if r[0] not in INTERACTIVE_PURPOSES), Decimal(0))
    return {
        "month": start.strftime("%Y-%m"),
        "limit_usd": float(state["limit"]),
        "spent_usd": float(state["spent"]),
        "reserved_usd": float(state["reserved"]),
        "ratio": round(state["ratio"], 4),
        "forecast_usd": float(round(forecast, 4)),
        "background_usd": float(background_cost),
        "by_model": [
            {"model": m, "cost_usd": float(c or 0), "requests": n, "input_tokens": int(i or 0),
             "output_tokens": int(o or 0), "cache_read_tokens": int(r or 0)}
            for m, c, n, i, o, r in by_model
        ],
        "by_purpose": [{"purpose": p, "cost_usd": float(c or 0), "requests": n} for p, c, n in by_purpose],
        "blocked": state["spent"] >= state["limit"],
    }


def choose_model(models: dict, efforts: dict, budget_cfg: dict, purpose: str, ratio: float,
                 model: str | None = None, effort: str | None = None) -> tuple[str, str]:
    """Pick model/effort for a purpose, degrading to cheaper models close to the cap."""
    chosen = model or models.get(purpose) or models.get("background") or "claude-haiku-5-5"
    chosen_effort = effort or efforts.get(purpose) or "low"
    if budget_cfg.get("degrade", True):
        if purpose == "analysis" and ratio >= budget_cfg.get("analysis_downgrade_ratio", 0.8):
            chosen = models.get("chat", "claude-sonnet-5-5")
        if purpose in ("chat", "analysis") and ratio >= budget_cfg.get("chat_downgrade_ratio", 0.95):
            chosen = models.get("background", "claude-haiku-5-5")
            chosen_effort = "low"
    return chosen, chosen_effort


async def reserve(owner_id: uuid.UUID, purpose: str, amount: Decimal, priority: int = 1) -> uuid.UUID:
    """Hold ``amount`` against the monthly cap, atomically across processes."""
    async with session_scope() as db:
        await db.execute(text("SELECT pg_advisory_xact_lock(:k)"), {"k": 7_270_001})
        await db.execute(delete(ApiReservation).where(ApiReservation.expires_at <= utcnow()))
        state = await budget_state(db, owner_id)
        limit, used = state["limit"], state["spent"] + state["reserved"]
        if used + amount > limit:
            raise BudgetExceeded(
                f"Месячный лимит Claude API (${limit}) исчерпан. Новые платные запросы остановлены до "
                f"следующего месяца или увеличения лимита в настройках.",
                details={"spent": float(state["spent"]), "limit": float(limit)},
            )
        pause_ratio = Decimal(str(state["config"].get("background_pause_ratio", 0.8)))
        if purpose not in INTERACTIVE_PURPOSES and priority < 2 and used >= limit * pause_ratio:
            raise BudgetExceeded("Фоновые задачи приостановлены: израсходовано больше "
                                 f"{int(pause_ratio * 100)}% месячного лимита API", code="api_budget_background_paused")
        reservation = ApiReservation(owner_id=owner_id, amount_usd=amount, purpose=purpose,
                                     expires_at=utcnow() + RESERVATION_TTL)
        db.add(reservation)
        await db.flush()
        return reservation.id


def usage_cost(model: str, usage: Any) -> tuple[Decimal, dict[str, int]]:
    """Cost of a response, pricing each fallback iteration at the model that actually ran."""
    totals = {
        "input_tokens": int(getattr(usage, "input_tokens", 0) or 0),
        "output_tokens": int(getattr(usage, "output_tokens", 0) or 0),
        "cache_read_tokens": int(getattr(usage, "cache_read_input_tokens", 0) or 0),
        "cache_write_tokens": int(getattr(usage, "cache_creation_input_tokens", 0) or 0),
    }
    iterations = getattr(usage, "iterations", None) or []
    if iterations:
        total = Decimal(0)
        for it in iterations:
            total += cost_usd(getattr(it, "model", None) or model,
                              int(getattr(it, "input_tokens", 0) or 0), int(getattr(it, "output_tokens", 0) or 0),
                              int(getattr(it, "cache_read_input_tokens", 0) or 0),
                              int(getattr(it, "cache_creation_input_tokens", 0) or 0))
        return total, totals
    return cost_usd(model, totals["input_tokens"], totals["output_tokens"],
                    totals["cache_read_tokens"], totals["cache_write_tokens"]), totals


async def settle(owner_id: uuid.UUID, reservation_id: uuid.UUID | None, *, model: str, purpose: str,
                 usage: Any = None, job_id: uuid.UUID | None = None, conversation_id: uuid.UUID | None = None,
                 request_id: str | None = None, ok: bool = True, meta: dict | None = None) -> Decimal:
    cost, totals = usage_cost(model, usage) if usage is not None else (Decimal(0), {
        "input_tokens": 0, "output_tokens": 0, "cache_read_tokens": 0, "cache_write_tokens": 0})
    async with session_scope() as db:
        before = await budget_state(db, owner_id)
        if reservation_id is not None:
            await db.execute(delete(ApiReservation).where(ApiReservation.id == reservation_id))
        db.add(ApiUsage(owner_id=owner_id, model=model, purpose=purpose, cost_usd=cost, job_id=job_id,
                        conversation_id=conversation_id, request_id=request_id, ok=ok, meta=meta or {}, **totals))
        await db.flush()
        after = await budget_state(db, owner_id)
        await _maybe_warn(db, owner_id, before, after)
    log_event(log, "llm_usage", model=model, purpose=purpose, cost_usd=float(cost), **totals)
    return cost


async def _maybe_warn(db: AsyncSession, owner_id: uuid.UUID, before: dict, after: dict) -> None:
    from app.services.notifications import create_notification  # local import: avoid cycle

    limit = after["limit"]
    if limit <= 0:
        return
    b = float(before["spent"] / limit)
    a = float(after["spent"] / limit)
    month = _month_start().strftime("%Y-%m")
    for t in sorted(after["config"].get("warn_thresholds", [0.5, 0.8, 0.95]) + [1.0]):
        if b < t <= a:
            pct = int(t * 100)
            body = (f"Израсходовано ${after['spent']:.2f} из ${limit} ({pct}%)."
                    + (" Новые платные запросы остановлены." if t >= 1.0 else
                       " Фоновые задачи с низким приоритетом будут приостановлены." if t >= 0.8 else ""))
            await create_notification(db, owner_id, title=f"Бюджет Claude API: {pct}%", body=body,
                                      category="budget", priority="high" if t >= 0.8 else "normal",
                                      dedupe_key=f"api_budget:{month}:{pct}")
