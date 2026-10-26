"""Detection of recurring charges (subscription candidates) and the IP tax calendar."""

from __future__ import annotations

import statistics
import uuid
from collections import defaultdict
from datetime import date, timedelta
from typing import Any

from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.timeutil import utcnow
from app.models import Subscription, TaxPayment, Transaction
from app.services.settings import get_setting


async def detect_subscriptions(db: AsyncSession, owner_id: uuid.UUID) -> list[dict[str, Any]]:
    since = utcnow() - timedelta(days=200)
    txs = (await db.execute(
        select(Transaction).where(Transaction.owner_id == owner_id, Transaction.deleted_at.is_(None),
                                  Transaction.direction == "out", Transaction.flow_type == "expense",
                                  Transaction.merchant_norm.is_not(None), Transaction.occurred_at >= since)
        .order_by(Transaction.occurred_at)
    )).scalars().all()
    known = {s.merchant_pattern for s in (await db.execute(
        select(Subscription).where(Subscription.owner_id == owner_id, Subscription.deleted_at.is_(None))
    )).scalars().all() if s.merchant_pattern}
    groups: dict[str, list[Transaction]] = defaultdict(list)
    for tx in txs:
        groups[tx.merchant_norm].append(tx)
    out = []
    for merchant, items in groups.items():
        if merchant in known or len(items) < 2:
            continue
        amounts = [t.amount_minor for t in items]
        med = statistics.median(amounts)
        similar = [t for t in items if abs(t.amount_minor - med) <= med * 0.12]
        if len(similar) < 2:
            continue
        gaps = [(b.occurred_at - a.occurred_at).days for a, b in zip(similar, similar[1:])]
        if not gaps:
            continue
        gap = statistics.median(gaps)
        period = "monthly" if 26 <= gap <= 33 else "weekly" if 6 <= gap <= 8 else \
            "yearly" if 350 <= gap <= 380 else "quarterly" if 85 <= gap <= 95 else None
        if period is None:
            continue
        last = similar[-1].occurred_at.date()
        step = {"monthly": 30, "weekly": 7, "yearly": 365, "quarterly": 91}[period]
        out.append({"merchant": merchant, "name": similar[-1].merchant or merchant, "amount_minor": int(med),
                    "period": period, "occurrences": len(similar), "last_charge": last.isoformat(),
                    "next_charge_estimate": (last + timedelta(days=step)).isoformat(),
                    "category_id": str(similar[-1].category_id) if similar[-1].category_id else None})
    return out


QUARTER_DUE = {1: (4, 28), 2: (7, 28), 3: (10, 28)}


async def refresh_tax_calendar(db: AsyncSession, owner_id: uuid.UUID, year: int | None = None) -> list[TaxPayment]:
    """Upsert planned tax entries for the year from IP income so far. Rates come from settings."""
    cfg = (await get_setting(db, owner_id, "finance"))["tax"]
    regime = cfg.get("regime", "usn6")
    if regime == "none":
        return []
    year = year or date.today().year
    rate = float(cfg.get("rate_pct", 6)) / 100

    async def income_until(month_end: int) -> int:
        start = date(year, 1, 1)
        end = date(year + (month_end == 12), (month_end % 12) + 1, 1)
        value = (await db.execute(
            select(func.coalesce(func.sum(Transaction.amount_minor), 0)).where(
                Transaction.owner_id == owner_id, Transaction.deleted_at.is_(None), Transaction.scope == "business",
                Transaction.direction == "in", Transaction.flow_type == "income",
                Transaction.occurred_at >= start, Transaction.occurred_at < end)
        )).scalar_one()
        return int(value or 0)

    plan: list[tuple[str, str, date, int]] = []
    insurance = int(cfg.get("insurance_fixed_minor", 0))
    if regime in ("usn6", "usn15"):
        paid_before = 0
        for q, (m, d) in QUARTER_DUE.items():
            cumulative = int(await income_until(q * 3) * rate)
            if cfg.get("reduce_by_insurance") and regime == "usn6":
                cumulative = max(cumulative - insurance * q // 4, 0)
            plan.append(("usn_advance", f"{year}-Q{q}", date(year, m, d), max(cumulative - paid_before, 0)))
            paid_before = max(cumulative, paid_before)
        annual = int(await income_until(12) * rate)
        if cfg.get("reduce_by_insurance") and regime == "usn6":
            annual = max(annual - insurance, 0)
        plan.append(("usn_annual", str(year), date(year + 1, 4, 28), max(annual - paid_before, 0)))
        plan.append(("insurance_fixed", str(year), date(year, 12, 28), insurance))
        over = await income_until(12) - int(cfg.get("insurance_1pct_threshold_minor", 30_000_000))
        if over > 0:
            plan.append(("insurance_1pct", str(year), date(year + 1, 7, 1), over // 100))
    elif regime == "npd":
        for m in range(1, 13):
            month_income = await income_until(m) - (await income_until(m - 1) if m > 1 else 0)
            due_m, due_y = (m + 1, year) if m < 12 else (1, year + 1)
            plan.append(("npd", f"{year}-{m:02d}", date(due_y, due_m, 28), int(month_income * rate)))

    existing = {(t.kind, t.period): t for t in (await db.execute(
        select(TaxPayment).where(TaxPayment.owner_id == owner_id, TaxPayment.deleted_at.is_(None))
    )).scalars().all()}
    result = []
    for kind, period, due, amount in plan:
        row = existing.get((kind, period))
        if row is None:
            row = TaxPayment(owner_id=owner_id, kind=kind, period=period, due_date=due, estimated_minor=amount)
            db.add(row)
        elif row.status != "paid":
            row.estimated_minor, row.due_date = amount, due
            if due < date.today() and row.paid_minor < amount:
                row.status = "overdue"
        result.append(row)
    await db.flush()
    return result
