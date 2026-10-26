"""Side effects of writes that arrive through REST or offline sync. Imported once at startup by the
API and the worker so the registry is populated in both."""

from __future__ import annotations

import uuid
from datetime import timedelta
from typing import Any

from sqlalchemy.ext.asyncio import AsyncSession

from app.core.timeutil import utcnow
from app.models import AgentJob, BodyMetric, Note, Task, Transaction, WishlistItem, Workout
from app.services.crud import on_write
from app.services.events import emit
from app.services.finance.categorize import apply_rules, learn_correction, normalize_merchant
from app.services.memory import upsert_memory


@on_write("transactions")
async def _transaction(db: AsyncSession, owner_id: uuid.UUID, tx: Transaction, op: str, changes: dict[str, Any]) -> None:
    if op == "create":
        tx.merchant_norm = normalize_merchant(tx.merchant or tx.description)
        if tx.category_id is None:
            await apply_rules(db, tx)
        else:
            tx.category_source = tx.category_source or "user"
        from app.services.finance.matching import _cash_counterpart, _pair_internal
        await _pair_internal(db, tx)
        await _cash_counterpart(db, tx)
        await db.flush()
        await emit(db, owner_id, "transaction.created",
                   {"transaction_id": str(tx.id), "amount_minor": tx.amount_minor, "direction": tx.direction,
                    "flow_type": tx.flow_type, "merchant": tx.merchant}, dedupe_key=f"tx:{tx.id}")
    elif op == "update":
        if "merchant" in changes:
            tx.merchant_norm = normalize_merchant(tx.merchant)
        if "category_id" in changes and changes["category_id"]:
            tx.category_source, tx.category_confidence = "user", 1.0
            if tx.status in ("new", "needs_review"):
                tx.status = "confirmed"
            await learn_correction(db, tx, category_id=tx.category_id, scope=changes.get("scope"),
                                   flow_type=changes.get("flow_type"))


@on_write("workouts")
async def _workout(db: AsyncSession, owner_id: uuid.UUID, w: Workout, op: str, changes: dict[str, Any]) -> None:
    if op in ("create", "update") and w.status == "done" and ("status" in changes or op == "create"):
        if w.ended_at is None:
            w.ended_at = utcnow()
        await emit(db, owner_id, "workout.finished", {"workout_id": str(w.id)}, dedupe_key=f"workout_done:{w.id}")


@on_write("body_metrics")
async def _metric(db: AsyncSession, owner_id: uuid.UUID, m: BodyMetric, op: str, changes: dict[str, Any]) -> None:
    if op == "create" and m.kind == "weight":
        await emit(db, owner_id, "weight.changed", {"metric_id": str(m.id), "value": float(m.value)},
                   dedupe_key=f"weight:{m.id}")


def _next_due(task: Task):
    rule = task.repeat_rule or ""
    base = task.due_at or utcnow()
    if rule == "daily":
        return base + timedelta(days=1)
    if rule == "weekdays":
        nxt = base + timedelta(days=1)
        while nxt.isoweekday() > 5:
            nxt += timedelta(days=1)
        return nxt
    if rule.startswith("weekly:"):
        days = sorted(int(d) for d in rule.split(":", 1)[1].split(",") if d.strip().isdigit())
        for add in range(1, 8):
            cand = base + timedelta(days=add)
            if cand.isoweekday() in days:
                return cand
    if rule.startswith("monthly:"):
        return base + timedelta(days=30)
    if rule.startswith("every:") and rule.endswith("d"):
        return base + timedelta(days=int(rule[6:-1] or 1))
    return None


@on_write("tasks")
async def _task(db: AsyncSession, owner_id: uuid.UUID, t: Task, op: str, changes: dict[str, Any]) -> None:
    if changes.get("status") == "done":
        t.completed_at = t.completed_at or utcnow()
        nxt = _next_due(t) if t.repeat_rule else None
        if nxt is not None:
            db.add(Task(owner_id=owner_id, title=t.title, notes=t.notes, due_at=nxt, has_time=t.has_time,
                        priority=t.priority, repeat_rule=t.repeat_rule, commitment=t.commitment, source=t.source,
                        remind_at=(nxt - (t.due_at - t.remind_at)) if t.remind_at and t.due_at else None))


@on_write("wishlist_items")
async def _wish(db: AsyncSession, owner_id: uuid.UUID, item: WishlistItem, op: str, changes: dict[str, Any]) -> None:
    if op == "create" and not item.analysis:
        db.add(AgentJob(owner_id=owner_id, kind="purchase_analysis", goal=f"Разбор покупки «{item.title}»",
                        payload={"item_id": str(item.id)}, next_run_at=utcnow(), priority=2,
                        dedupe_key=f"purchase:{item.id}"))


@on_write("debt_payments")
async def _debt_payment(db: AsyncSession, owner_id: uuid.UUID, p, op: str, changes: dict[str, Any]) -> None:
    from app.models import Debt
    debt = await db.get(Debt, p.debt_id)
    if debt is None:
        return
    if op == "create":
        debt.balance_minor = max(debt.balance_minor - p.amount_minor, 0)
    elif op == "delete":
        debt.balance_minor += p.amount_minor
    if debt.balance_minor == 0 and debt.status != "closed":
        debt.status = "closed"
    elif debt.balance_minor > 0 and debt.status == "closed":
        debt.status = "active"
    debt.version += 1


@on_write("goal_contributions")
async def _goal_contribution(db: AsyncSession, owner_id: uuid.UUID, c, op: str, changes: dict[str, Any]) -> None:
    from app.models import FinancialGoal
    goal = await db.get(FinancialGoal, c.goal_id)
    if goal is None:
        return
    if op == "create":
        goal.saved_minor += c.amount_minor
    elif op == "delete":
        goal.saved_minor -= c.amount_minor
    if goal.saved_minor >= goal.target_minor and goal.status == "active":
        goal.status = "done"
    goal.version += 1


@on_write("notes")
async def _note(db: AsyncSession, owner_id: uuid.UUID, note: Note, op: str, changes: dict[str, Any]) -> None:
    if op == "create" and note.text.strip():
        await upsert_memory(db, owner_id, type="working", category="notes", content=note.text.strip()[:4000],
                            source_kind="note", importance=2, confidence=0.9, status="confirmed",
                            evidence=[{"kind": "note", "id": str(note.id)}])
        note.processed_at = utcnow()
