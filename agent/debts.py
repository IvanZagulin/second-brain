"""Debt payoff scenarios, computed by code from confirmed terms. Interest accrues monthly at the debt's
rate once its holiday is over; a monthly budget covers minimum payments first and the rest goes to the
most expensive debt (avalanche). Nothing here is a promise about what a creditor will actually charge:
the figures are labelled as estimates whenever ``terms_confirmed`` is false."""

from __future__ import annotations

import uuid
from dataclasses import dataclass
from datetime import date
from decimal import Decimal
from typing import Any

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.money import fmt_rub
from app.models import Debt

MAX_MONTHS = 120


@dataclass
class DebtTerms:
    id: str
    creditor: str
    balance_minor: int
    monthly_rate: float          # fraction per month
    min_payment_minor: int
    holiday_until: date | None
    confirmed: bool


def monthly_rate(rate_percent: Decimal | float | None, period: str | None) -> float:
    r = float(rate_percent or 0) / 100
    if period == "day":
        return r * 30
    if period == "month":
        return r
    return r / 12


def _next_month(d: date) -> date:
    y, m = (d.year + 1, 1) if d.month == 12 else (d.year, d.month + 1)
    return date(y, m, 1)


def simulate(debts: list[DebtTerms], monthly_budget_minor: int, start: date) -> dict[str, Any]:
    """Months to clear everything, total interest, and when each debt closes."""
    balances = {d.id: d.balance_minor for d in debts}
    interest_total = 0
    closed: dict[str, date] = {}
    month = date(start.year, start.month, 1)
    months = 0
    while any(b > 0 for b in balances.values()) and months < MAX_MONTHS:
        months += 1
        month = _next_month(month)
        # Interest first (skipped while a holiday lasts).
        for d in debts:
            if balances[d.id] <= 0:
                continue
            if d.holiday_until and month <= d.holiday_until:
                continue
            charge = int(balances[d.id] * d.monthly_rate)
            balances[d.id] += charge
            interest_total += charge
        budget = monthly_budget_minor
        # Minimums.
        for d in debts:
            if balances[d.id] <= 0:
                continue
            pay = min(d.min_payment_minor, balances[d.id], budget)
            balances[d.id] -= pay
            budget -= pay
        # The rest to the most expensive open debt.
        for d in sorted(debts, key=lambda x: -x.monthly_rate):
            if budget <= 0:
                break
            if balances[d.id] <= 0:
                continue
            pay = min(budget, balances[d.id])
            balances[d.id] -= pay
            budget -= pay
        for d in debts:
            if balances[d.id] <= 0 and d.id not in closed:
                closed[d.id] = month
    done = all(b <= 0 for b in balances.values())
    return {"months": months if done else None, "cleared_by": month.isoformat() if done else None,
            "interest_total_minor": interest_total, "closes": {k: v.isoformat() for k, v in closed.items()},
            "left_minor": {k: max(v, 0) for k, v in balances.items()}}


async def load_terms(db: AsyncSession, owner_id: uuid.UUID) -> list[DebtTerms]:
    rows = (await db.execute(select(Debt).where(Debt.owner_id == owner_id, Debt.deleted_at.is_(None),
                                                Debt.status != "closed", Debt.balance_minor > 0))).scalars().all()
    return [DebtTerms(id=str(d.id), creditor=d.creditor, balance_minor=d.balance_minor,
                      monthly_rate=monthly_rate(d.rate_percent, d.rate_period),
                      min_payment_minor=d.next_payment_minor or 0, holiday_until=d.holiday_until,
                      confirmed=bool(d.terms_confirmed)) for d in rows]


async def scenarios(db: AsyncSession, owner_id: uuid.UUID, today: date,
                    monthly_rub: float | None = None) -> dict[str, Any]:
    debts = await load_terms(db, owner_id)
    if not debts:
        return {"debts": [], "scenarios": [], "note": "Открытых долгов нет."}
    total = sum(d.balance_minor for d in debts)
    minimums = sum(d.min_payment_minor for d in debts)
    budgets = []
    if monthly_rub:
        budgets.append(int(round(monthly_rub * 100)))
    else:
        base = max(minimums, 500_000)
        budgets = sorted({base, base * 2, base * 3, max(total // 6, base)})
    out = []
    for b in budgets:
        sim = simulate(debts, b, today)
        out.append({"monthly": fmt_rub(b), "monthly_minor": b,
                    "months": sim["months"], "cleared_by": sim["cleared_by"],
                    "interest_total": fmt_rub(sim["interest_total_minor"]),
                    "closes": {next(d.creditor for d in debts if d.id == k): v for k, v in sim["closes"].items()},
                    "not_enough": sim["months"] is None})
    holidays = [d for d in debts if d.holiday_until and d.holiday_until >= today]
    return {
        "today": today.isoformat(),
        "total": fmt_rub(total), "total_minor": total, "minimums_monthly": fmt_rub(minimums),
        "debts": [{"creditor": d.creditor, "balance": fmt_rub(d.balance_minor),
                   "rate_monthly_pct": round(d.monthly_rate * 100, 2), "min_payment": fmt_rub(d.min_payment_minor),
                   "holiday_until": d.holiday_until.isoformat() if d.holiday_until else None,
                   "terms_confirmed": d.confirmed} for d in debts],
        "holiday_ends": min((d.holiday_until for d in holidays), default=None).isoformat() if holidays else None,
        "scenarios": out,
        "note": "Расчёт по условиям из карточек долгов; где terms_confirmed=false — оценка. Проценты начисляются "
                "помесячно после окончания каникул; переплата считается до полного закрытия.",
    }


def scenarios_text(data: dict[str, Any]) -> str:
    if not data.get("scenarios"):
        return data.get("note", "")
    lines = [f"Долгов {data['total']}, минимальные платежи {data['minimums_monthly']}/мес."]
    for s in data["scenarios"]:
        if s["not_enough"]:
            lines.append(f"- {s['monthly']}/мес: не закрывает долги за 10 лет (проценты съедают платёж).")
        else:
            lines.append(f"- {s['monthly']}/мес: закроешь за {s['months']} мес. (к {s['cleared_by'][:7]}), переплата {s['interest_total']}.")
    return "\n".join(lines)
