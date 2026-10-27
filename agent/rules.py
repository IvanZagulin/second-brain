"""Event rules and periodic checks: what the agent does when something happens. Deterministic
reminders (payments due, task reminders) go out directly; anything that benefits from judgement goes
through agent.initiative.consider."""

from __future__ import annotations

import logging
import uuid
from datetime import date, datetime, timedelta
from typing import Any

from sqlalchemy import func, select

from agent.initiative import consider
from app.core.db import session_scope
from app.core.money import fmt_rub
from app.core.timeutil import local_now, parse_hhmm, utcnow
from app.models import (
    AgentEvent, AgentJob, BodyMetric, BudgetCategory, Debt, Subscription, Task, Transaction, User, Workout,
)
from app.services.finance.engine import _advance, finance_summary, obligations_until
from app.services.notifications import create_notification
from app.services.queue import enqueue
from app.services.settings import get_all_settings, get_setting

log = logging.getLogger(__name__)


async def process_events(limit: int = 50) -> int:
    async with session_scope() as db:
        rows = (await db.execute(select(AgentEvent).where(AgentEvent.status == "pending")
                                 .order_by(AgentEvent.id).limit(limit).with_for_update(skip_locked=True))).scalars().all()
        batch = [(e.id, e.owner_id, e.type, dict(e.payload or {})) for e in rows]
        for e in rows:
            e.status = "processing"
    for event_id, owner_id, etype, payload in batch:
        handler = HANDLERS.get(etype)
        status, result = "skipped", {}
        if handler is not None:
            try:
                result = await handler(owner_id, payload) or {}
                status = "processed"
            except Exception as exc:
                log.exception("event %s failed", etype)
                status, result = "failed", {"error": type(exc).__name__}
        async with session_scope() as db:
            ev = await db.get(AgentEvent, event_id)
            ev.status, ev.processed_at, ev.result = status, utcnow(), result
    return len(batch)


async def on_transaction(owner_id: uuid.UUID, p: dict[str, Any]) -> dict:
    async with session_scope() as db:
        tx = await db.get(Transaction, uuid.UUID(p["transaction_id"]))
        if tx is None or tx.deleted_at is not None or tx.status in ("excluded", "possible_duplicate"):
            return {"skip": "gone"}
        fin = await get_setting(db, owner_id, "finance")
        cat = await db.get(BudgetCategory, tx.category_id) if tx.category_id else None
        summary = await finance_summary(db, owner_id)
        if tx.category_id is None and tx.direction == "out":
            await enqueue("run_system_task", "categorize_pending", str(owner_id), job_id=f"categorize:{owner_id}",
                          defer_seconds=120)
        month_cat_total = 0
        if cat is not None:
            month_start = local_now(None).replace(day=1, hour=0, minute=0, second=0, microsecond=0)
            month_cat_total = int((await db.execute(select(func.coalesce(func.sum(Transaction.amount_minor), 0)).where(
                Transaction.owner_id == owner_id, Transaction.category_id == cat.id, Transaction.deleted_at.is_(None),
                Transaction.direction == "out", Transaction.occurred_at >= month_start))).scalar_one() or 0)
    amount = fmt_rub(tx.amount_minor)
    base = (f"Свободно сейчас {fmt_rub(summary['free_minor'])}, бюджет на сегодня {fmt_rub(summary['daily_budget_minor'])}, "
            f"потрачено сегодня {fmt_rub(summary['today_spent_minor'])}, до поступления {summary['days_until_income']} дн."
            + (" Остатки оценочные." if summary["estimated"] else ""))
    results = {}
    still = {"transaction_id": str(tx.id)}  # re-checked if the message waits for the morning
    if tx.direction == "in" and tx.flow_type == "income" and tx.amount_minor >= 500_000:
        results["income"] = await consider(owner_id, f"Поступление {amount} ({tx.merchant or tx.description or 'без описания'})",
                                           base + " Подскажи, как распределить: обязательства, цели, резерв.",
                                           priority=1, dedupe_key=f"income:{tx.id}", validate=still)
    if tx.direction == "out" and tx.flow_type == "expense":
        if cat is not None and cat.is_impulse:
            results["impulse"] = await consider(
                owner_id, f"Трата в категории «{cat.name}»: {amount} ({tx.merchant or '—'})",
                f"{base} За месяц в этой категории уже {fmt_rub(month_cat_total)}.", priority=1,
                dedupe_key=f"impulse:{tx.id}", validate=still)
        elif tx.amount_minor >= int(fin.get("large_purchase_minor", 300_000)):
            results["large"] = await consider(owner_id, f"Крупная покупка {amount} ({tx.merchant or tx.description or '—'})",
                                              base, priority=1, dedupe_key=f"large:{tx.id}", validate=still)
        # No daily budget means no balances yet: "overspent" against zero is noise, not news.
        if summary["overspent_today"] and summary["daily_budget_minor"] > 0:
            results["overspent"] = await consider(
                owner_id, "Превышен бюджет на сегодня",
                f"{base} Последняя трата: {amount} {tx.merchant or ''}.", priority=2,
                dedupe_key=f"overspent:{summary['date']}",
                validate={**still, "overspent_date": str(summary["date"])})
    return {k: v.get("sent") for k, v in results.items()}


async def on_workout_finished(owner_id: uuid.UUID, p: dict[str, Any]) -> dict:
    async with session_scope() as db:
        db.add(AgentJob(owner_id=owner_id, kind="workout_analysis", goal="Разбор тренировки",
                        payload={"workout_id": p["workout_id"]}, next_run_at=utcnow(), priority=2,
                        dedupe_key=f"workout_analysis:{p['workout_id']}"))
    return {"scheduled": True}


async def on_weight(owner_id: uuid.UUID, p: dict[str, Any]) -> dict:
    async with session_scope() as db:
        rows = (await db.execute(select(BodyMetric).where(
            BodyMetric.owner_id == owner_id, BodyMetric.kind == "weight", BodyMetric.deleted_at.is_(None),
            BodyMetric.measured_at >= utcnow() - timedelta(days=21)).order_by(BodyMetric.measured_at))).scalars().all()
        fit = await get_setting(db, owner_id, "fitness")
    if len(rows) < 2:
        return {}
    delta = float(rows[-1].value) - float(rows[0].value)
    if abs(delta) < 1.0:
        return {}
    target = fit.get("target_weight")
    return await consider(owner_id, f"Вес изменился на {delta:+.1f} кг за {(rows[-1].measured_at - rows[0].measured_at).days} дн.",
                          f"Сейчас {float(rows[-1].value):g} кг" + (f", цель {target} кг." if target else "."),
                          priority=0, dedupe_key=f"weight:{rows[-1].id}")


HANDLERS = {
    "transaction.created": on_transaction,
    "workout.finished": on_workout_finished,
    "weight.changed": on_weight,
}


async def daily_checks(owner_id: uuid.UUID) -> dict[str, int]:
    """Runs every 15 minutes; every notification has a dedupe key so nothing repeats."""
    sent = {"payments": 0, "reminders": 0, "overdue": 0, "missed_workout": 0, "subscriptions": 0}
    async with session_scope() as db:
        user = await db.get(User, owner_id)
        tz_name = user.timezone if user else None
        now_local = local_now(tz_name)
        today = now_local.date()
        settings = await get_all_settings(db, owner_id)
        fx = settings["finance"].get("fx_rates") or {"USD": 95.0}

        # Upcoming obligations: 3 days ahead and on the day.
        for o in await obligations_until(db, owner_id, today, today + timedelta(days=3), fx):
            days = (o.due - today).days
            if days not in (0, 1, 3):
                continue
            when = "сегодня" if days == 0 else "завтра" if days == 1 else f"через {days} дня"
            n = await create_notification(
                db, owner_id, title=f"Платёж {when}", body=f"{o.title}: {fmt_rub(o.amount_minor)}"
                + (" (сумма примерная)" if o.estimated else ""), category="finance",
                priority="high" if days == 0 else "normal", dedupe_key=f"due:{o.kind}:{o.ref_id}:{o.due}:{days}")
            sent["payments"] += int(n is not None)

        # Credit holidays ending soon.
        for d in (await db.execute(select(Debt).where(Debt.owner_id == owner_id, Debt.deleted_at.is_(None),
                                                      Debt.holiday_until.is_not(None), Debt.status != "closed"))).scalars():
            left = (d.holiday_until - today).days
            if left in (30, 14, 3):
                await create_notification(db, owner_id, title="Заканчиваются кредитные каникулы",
                                          body=f"{d.creditor}: каникулы до {d.holiday_until:%d.%m.%Y} (через {left} дн.). "
                                               f"Остаток {fmt_rub(d.balance_minor)}. Проверь условия и график платежей.",
                                          category="finance", priority="high", dedupe_key=f"holiday:{d.id}:{left}")

        # Subscription dates roll forward once passed.
        for s in (await db.execute(select(Subscription).where(Subscription.owner_id == owner_id,
                                                              Subscription.deleted_at.is_(None),
                                                              Subscription.is_active.is_(True),
                                                              Subscription.next_charge_date < today))).scalars():
            guard = 0
            while s.next_charge_date < today and guard < 24:
                s.next_charge_date = _advance(s.next_charge_date, s.period, s.period_days)
                guard += 1
            s.version += 1
            sent["subscriptions"] += 1

        # Task reminders and overdue tasks.
        now = utcnow()
        tasks = (await db.execute(select(Task).where(Task.owner_id == owner_id, Task.deleted_at.is_(None),
                                                     Task.status == "open"))).scalars().all()
        procrastinated = []
        for t in tasks:
            if t.remind_at and t.remind_at <= now:
                n = await create_notification(db, owner_id, title="Напоминание", body=t.title, category="task",
                                              data={"task_id": str(t.id)}, dedupe_key=f"remind:{t.id}:{t.remind_at:%Y%m%d%H%M}")
                sent["reminders"] += int(n is not None)
            if t.due_at and t.due_at < now - timedelta(hours=1):
                if t.postponed_count >= 3 or t.commitment == "firm":
                    procrastinated.append(t)
                else:
                    n = await create_notification(db, owner_id, title="Просрочено", body=t.title, category="task",
                                                  data={"task_id": str(t.id)}, dedupe_key=f"overdue:{t.id}:{t.due_at:%Y%m%d}")
                    sent["overdue"] += int(n is not None)

        # Missed training.
        fit = settings["fitness"]
        missed = False
        if now_local.isoweekday() in (fit.get("training_days") or []) and \
                now_local.time() >= parse_hhmm(fit.get("missed_check_time"), "22:30"):
            day_start = now_local.replace(hour=0, minute=0, second=0, microsecond=0)
            had = (await db.execute(select(func.count()).select_from(Workout).where(
                Workout.owner_id == owner_id, Workout.deleted_at.is_(None), Workout.started_at >= day_start))).scalar_one()
            missed = not had

    if missed:
        r = await consider(owner_id, "Сегодня день тренировки, но тренировка не записана",
                           "Спроси, была ли тренировка (может, забыл записать) и если нет — почему.",
                           priority=1, dedupe_key=f"missed_workout:{today}")
        sent["missed_workout"] = int(r.get("sent", False))
    for t in procrastinated[:2]:
        await consider(owner_id, f"Просрочено обещание: «{t.title}»",
                       f"Срок был {t.due_at:%d.%m %H:%M}, переносилось {t.postponed_count} раз, тип: {t.commitment}. "
                       "Потребуй объяснений и предложи один маленький конкретный шаг.",
                       priority=1, dedupe_key=f"procrastination:{t.id}:{t.due_at:%Y%m%d}")
    return sent
