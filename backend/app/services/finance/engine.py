"""Deterministic budget maths. Claude only explains these numbers, it never computes them.

Key figure: ``free_minor`` — money available for non-essential spending after obligations due
before the next income date and reserves. Future income is never counted as available. If balances
aren't confirmed by bank data recently, the result is flagged ``estimated``."""

from __future__ import annotations

import calendar
import uuid
from collections import defaultdict
from dataclasses import dataclass
from datetime import date, datetime, timedelta
from typing import Any

from sqlalchemy import case, func, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.timeutil import local_day_bounds, local_now, month_bounds, utcnow
from app.models import Account, Budget, Debt, FinancialGoal, Subscription, TaxPayment, Transaction, User
from app.services.settings import get_setting

ACTIVE = ("new", "matched", "needs_review", "confirmed")
DISCRETIONARY_FLOWS = ("expense",)
CONFIRMED_FRESH = timedelta(days=3)


def _sign(tx: Transaction) -> int:
    return tx.amount_minor if tx.direction == "in" else -tx.amount_minor


def next_income_date(today: date, income_days: list[int]) -> date:
    days = sorted({d for d in income_days if 1 <= d <= 31})
    if not days:
        last = calendar.monthrange(today.year, today.month)[1]
        return date(today.year, today.month, last) + timedelta(days=1)
    y, m = today.year, today.month
    for _ in range(3):
        last = calendar.monthrange(y, m)[1]
        for d in days:
            candidate = date(y, m, min(d, last))
            if candidate > today:
                return candidate
        y, m = (y + 1, 1) if m == 12 else (y, m + 1)
    return today + timedelta(days=15)


def _to_rub(amount_minor: int, currency: str, fx: dict[str, float]) -> int:
    if currency == "RUB":
        return amount_minor
    rate = fx.get(currency)
    return int(amount_minor * rate) if rate else amount_minor


def _advance(d: date, period: str, period_days: int | None) -> date:
    if period == "weekly":
        return d + timedelta(days=7)
    if period == "days" and period_days:
        return d + timedelta(days=period_days)
    months = {"monthly": 1, "quarterly": 3, "yearly": 12}.get(period, 1)
    m = d.month - 1 + months
    y = d.year + m // 12
    m = m % 12 + 1
    return date(y, m, min(d.day, calendar.monthrange(y, m)[1]))


@dataclass
class Obligation:
    kind: str
    title: str
    due: date
    amount_minor: int
    ref_id: str
    business: bool = False
    estimated: bool = False

    def as_dict(self) -> dict[str, Any]:
        return {"kind": self.kind, "title": self.title, "due": self.due.isoformat(), "amount_minor": self.amount_minor,
                "ref_id": self.ref_id, "business": self.business, "estimated": self.estimated}


async def account_balances(db: AsyncSession, owner_id: uuid.UUID) -> list[dict[str, Any]]:
    accounts = (await db.execute(
        select(Account).where(Account.owner_id == owner_id, Account.deleted_at.is_(None),
                              Account.archived.is_(False)).order_by(Account.sort)
    )).scalars().all()
    out = []
    now = utcnow()
    for acc in accounts:
        q = select(func.coalesce(func.sum(
            case((Transaction.direction == "in", Transaction.amount_minor), else_=-Transaction.amount_minor)), 0)
        ).where(Transaction.owner_id == owner_id, Transaction.account_id == acc.id,
                Transaction.deleted_at.is_(None), Transaction.status.in_(ACTIVE))
        if acc.balance_at is not None:
            q = q.where(Transaction.occurred_at > acc.balance_at)
        delta = int((await db.execute(q)).scalar_one() or 0)
        confirmed = acc.balance_at is not None and acc.balance_source in ("statement", "notification", "manual") \
            and now - acc.balance_at <= CONFIRMED_FRESH
        out.append({"account": acc, "balance_minor": acc.balance_minor + delta, "confirmed": confirmed})
    return out


async def obligations_until(db: AsyncSession, owner_id: uuid.UUID, today: date, until: date,
                            fx: dict[str, float]) -> list[Obligation]:
    items: list[Obligation] = []
    debts = (await db.execute(
        select(Debt).where(Debt.owner_id == owner_id, Debt.deleted_at.is_(None), Debt.status != "closed")
    )).scalars().all()
    for d in debts:
        if not d.next_payment_date or not d.next_payment_minor:
            continue
        if d.holiday_until and d.next_payment_date <= d.holiday_until:
            continue
        if d.next_payment_date <= until:
            items.append(Obligation("debt", f"Платёж: {d.creditor}", d.next_payment_date, d.next_payment_minor,
                                    str(d.id), estimated=not d.terms_confirmed))
    subs = (await db.execute(
        select(Subscription).where(Subscription.owner_id == owner_id, Subscription.deleted_at.is_(None),
                                   Subscription.is_active.is_(True), Subscription.next_charge_date.is_not(None))
    )).scalars().all()
    for s in subs:
        due = s.next_charge_date
        guard = 0
        while due and due <= until and guard < 8:
            if due >= today:
                items.append(Obligation("subscription", s.name, due, _to_rub(s.amount_minor, s.currency, fx), str(s.id)))
            due = _advance(due, s.period, s.period_days)
            guard += 1
    taxes = (await db.execute(
        select(TaxPayment).where(TaxPayment.owner_id == owner_id, TaxPayment.deleted_at.is_(None),
                                 TaxPayment.status != "paid", TaxPayment.due_date <= until)
    )).scalars().all()
    for t in taxes:
        left = max(t.estimated_minor - t.paid_minor, 0)
        if left:
            items.append(Obligation("tax", _tax_title(t.kind, t.period), t.due_date, left, str(t.id),
                                    business=True, estimated=True))
    return sorted(items, key=lambda o: o.due)


def _tax_title(kind: str, period: str) -> str:
    return {
        "usn_advance": f"Аванс УСН за {period}", "usn_annual": f"УСН за {period} год",
        "insurance_fixed": f"Фиксированные взносы {period}", "insurance_1pct": f"Взнос 1% за {period}",
        "npd": f"НПД за {period}",
    }.get(kind, f"Налог {period}")


async def spending(db: AsyncSession, owner_id: uuid.UUID, start: datetime, end: datetime,
                   scope: str = "personal") -> dict[str, Any]:
    rows = (await db.execute(
        select(Transaction.category_id, Transaction.flow_type, Transaction.direction,
               func.sum(Transaction.amount_minor), func.count())
        .where(Transaction.owner_id == owner_id, Transaction.deleted_at.is_(None), Transaction.status.in_(ACTIVE),
               Transaction.scope == scope, Transaction.occurred_at >= start, Transaction.occurred_at < end)
        .group_by(Transaction.category_id, Transaction.flow_type, Transaction.direction)
    )).all()
    by_cat: dict[str, int] = defaultdict(int)
    totals = defaultdict(int)
    for cat, flow, direction, total, _n in rows:
        total = int(total or 0)
        if flow in DISCRETIONARY_FLOWS and direction == "out":
            by_cat[str(cat) if cat else "none"] += total
            totals["expense"] += total
        elif flow == "refund":
            totals["refund"] += total
        elif flow == "income":
            totals["income"] += total
        elif flow == "debt_payment":
            totals["debt_payment"] += total
        elif flow == "tax":
            totals["tax"] += total
    totals["expense_net"] = max(totals["expense"] - totals["refund"], 0)
    return {"totals": dict(totals), "by_category": dict(sorted(by_cat.items(), key=lambda kv: -kv[1]))}


async def finance_summary(db: AsyncSession, owner_id: uuid.UUID, today: date | None = None) -> dict[str, Any]:
    user = await db.get(User, owner_id)
    tz_name = user.timezone if user else None
    now_local = local_now(tz_name)
    today = today or now_local.date()
    fin = await get_setting(db, owner_id, "finance")
    fx = fin.get("fx_rates") or {"USD": 95.0, "EUR": 105.0}

    balances = await account_balances(db, owner_id)
    personal = [b for b in balances if not b["account"].is_business and b["account"].include_in_free]
    business = [b for b in balances if b["account"].is_business]
    personal_total = sum(b["balance_minor"] for b in personal)
    business_total = sum(b["balance_minor"] for b in business)
    estimated = not personal or any(not b["confirmed"] for b in personal)

    nxt = next_income_date(today, fin.get("income_days") or [])
    days_left = max((nxt - today).days, 1)
    obligations = await obligations_until(db, owner_id, today, nxt, fx)
    personal_obl = sum(o.amount_minor for o in obligations if not o.business)
    business_obl = sum(o.amount_minor for o in obligations if o.business)
    business_gap = max(business_obl - business_total, 0)

    month_start = date(today.year, today.month, 1)
    budget = (await db.execute(
        select(Budget).where(Budget.owner_id == owner_id, Budget.month == month_start, Budget.deleted_at.is_(None))
    )).scalar_one_or_none()
    reserves = sum(int(r.get("amount_minor", 0)) for r in (budget.reserves if budget else []))
    goals = (await db.execute(
        select(FinancialGoal).where(FinancialGoal.owner_id == owner_id, FinancialGoal.deleted_at.is_(None),
                                    FinancialGoal.status == "active")
    )).scalars().all()
    goal_reserve = sum(int((g.monthly_plan_minor or 0) * min(days_left, 30) / 30) for g in goals)

    free = personal_total - personal_obl - reserves - goal_reserve - business_gap

    d_start, d_end = local_day_bounds(today, tz_name)
    today_spend = (await spending(db, owner_id, d_start, d_end))["totals"].get("expense_net", 0)
    w_start, _ = local_day_bounds(today - timedelta(days=today.weekday()), tz_name)
    week_spend = (await spending(db, owner_id, w_start, d_end))["totals"].get("expense_net", 0)
    m_start, m_end = month_bounds(today, tz_name)
    month = await spending(db, owner_id, m_start, m_end)
    month_spend = month["totals"].get("expense_net", 0)
    last30_start, _ = local_day_bounds(today - timedelta(days=30), tz_name)
    last30 = (await spending(db, owner_id, last30_start, d_start))["totals"].get("expense_net", 0)
    avg_daily = last30 // 30 if last30 else 0
    days_in_month = calendar.monthrange(today.year, today.month)[1]
    forecast_month = month_spend + avg_daily * (days_in_month - today.day)

    savings_mode = bool(fin.get("savings_mode")) or bool(
        budget and budget.savings_mode_until and budget.savings_mode_until >= today)
    # Today's own spending already left the balance: add it back to get today's allowance.
    daily_budget = max(free + today_spend, 0) // days_left
    if savings_mode:
        daily_budget = daily_budget * (100 - int(fin.get("savings_mode_cut_pct", 30))) // 100
    today_left = daily_budget - today_spend

    upcoming = await obligations_until(db, owner_id, today, today + timedelta(days=30), fx)
    debts_total = (await db.execute(
        select(func.coalesce(func.sum(Debt.balance_minor), 0))
        .where(Debt.owner_id == owner_id, Debt.deleted_at.is_(None), Debt.status != "closed")
    )).scalar_one()

    return {
        "date": today.isoformat(),
        "estimated": estimated,
        "accounts": [{"id": str(b["account"].id), "name": b["account"].name, "kind": b["account"].kind,
                      "is_business": b["account"].is_business, "balance_minor": b["balance_minor"],
                      "confirmed": b["confirmed"],
                      "balance_at": b["account"].balance_at.isoformat() if b["account"].balance_at else None}
                     for b in balances],
        "personal_total_minor": personal_total,
        "business_total_minor": business_total,
        "next_income_date": nxt.isoformat(),
        "days_until_income": days_left,
        "obligations_until_income": [o.as_dict() for o in obligations],
        "obligations_personal_minor": personal_obl,
        "obligations_business_minor": business_obl,
        "business_gap_minor": business_gap,
        "reserves_minor": reserves,
        "goal_reserve_minor": goal_reserve,
        "free_minor": free,
        "daily_budget_minor": daily_budget,
        "today_spent_minor": today_spend,
        "today_left_minor": today_left,
        "week_spent_minor": week_spend,
        "month_spent_minor": month_spend,
        "month_by_category": month["by_category"],
        "month_income_minor": month["totals"].get("income", 0),
        "forecast_month_minor": forecast_month,
        "avg_daily_minor": avg_daily,
        "overspent_today": today_spend > daily_budget,
        "deficit": free < 0,
        "savings_mode": savings_mode,
        "debts_total_minor": int(debts_total or 0),
        "upcoming_30d": [o.as_dict() for o in upcoming],
    }


async def purchase_impact(db: AsyncSession, owner_id: uuid.UUID, price_minor: int) -> dict[str, Any]:
    """Deterministic part of «Хочу купить»."""
    s = await finance_summary(db, owner_id)
    free = s["free_minor"]
    daily = s["daily_budget_minor"] or 1
    goals = (await db.execute(
        select(FinancialGoal).where(FinancialGoal.owner_id == owner_id, FinancialGoal.deleted_at.is_(None),
                                    FinancialGoal.status == "active").order_by(FinancialGoal.priority.desc())
    )).scalars().all()
    goal_delays = []
    for g in goals:
        if g.monthly_plan_minor:
            goal_delays.append({"goal": g.name, "delay_days": round(price_minor / g.monthly_plan_minor * 30)})
    return {
        "price_minor": price_minor,
        "free_minor": free,
        "affordable": price_minor <= max(free, 0),
        "share_of_free_pct": round(price_minor / free * 100, 1) if free > 0 else None,
        "days_of_budget": round(price_minor / daily, 1),
        "free_after_minor": free - price_minor,
        "daily_budget_after_minor": max(free - price_minor, 0) // max(s["days_until_income"], 1),
        "goal_delays": goal_delays,
        "estimated": s["estimated"],
        "obligations_personal_minor": s["obligations_personal_minor"],
        "days_until_income": s["days_until_income"],
    }
