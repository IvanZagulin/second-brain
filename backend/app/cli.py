"""Admin CLI, run inside the api container:

    python -m app.cli init                 # create the owner, default categories and agent profile
    python -m app.cli pair [--days 7]      # one-time code to pair a phone
    python -m app.cli seed /secrets/seed.json   # load personal starting data (kept off GitHub)
"""

from __future__ import annotations

import argparse
import asyncio
import json
from datetime import date, datetime, timedelta
from decimal import Decimal
from pathlib import Path

from sqlalchemy import select

from agent.persona import DEFAULT_PERSONA
from app.core.db import session_scope
from app.core.money import to_minor
from app.core.security import hash_secret, new_pairing_code
from app.core.timeutil import utcnow
from app.models import (
    Account, AgentProfile, Debt, Exercise, FinancialGoal, PairingCode, Subscription, User, WorkoutPlan,
)
from app.services.finance.categorize import ensure_default_categories
from app.services.settings import set_setting


async def get_owner(db) -> User:
    user = (await db.execute(select(User).order_by(User.created_at))).scalars().first()
    if user is None:
        user = User(name="", timezone="Europe/Moscow")
        db.add(user)
        await db.flush()
    return user


async def cmd_init() -> None:
    async with session_scope() as db:
        user = await get_owner(db)
        await ensure_default_categories(db, user.id)
        if (await db.execute(select(AgentProfile).where(AgentProfile.owner_id == user.id))).first() is None:
            db.add(AgentProfile(owner_id=user.id, persona=DEFAULT_PERSONA, rules=[], style={}))
        if (await db.execute(select(Account).where(Account.owner_id == user.id, Account.kind == "cash"))).first() is None:
            db.add(Account(owner_id=user.id, name="Наличные", kind="cash", bank="cash", sort=90))
        print(f"owner {user.id} ready")


async def cmd_pair(days: int) -> None:
    async with session_scope() as db:
        user = await get_owner(db)
        code = new_pairing_code()
        db.add(PairingCode(owner_id=user.id, code_hash=hash_secret(code), expires_at=utcnow() + timedelta(days=days),
                           note="cli"))
    print(code)


def _d(value: str | None) -> date | None:
    return date.fromisoformat(value) if value else None


async def cmd_seed(path: str) -> None:
    """Personal starting values from the spec (schedule, incomes, accounts, debts, goals, training)."""
    data = json.loads(Path(path).read_text(encoding="utf-8"))
    async with session_scope() as db:
        user = await get_owner(db)
        await ensure_default_categories(db, user.id)
        prof = data.get("user", {})
        user.name = prof.get("name", user.name)
        user.timezone = prof.get("timezone", user.timezone)
        user.profile = {**(user.profile or {}), **prof.get("profile", {})}
        for key in ("schedule", "finance", "fitness", "initiative"):
            if key in data:
                await set_setting(db, user.id, key, data[key])
        existing_accounts = {a.name for a in (await db.execute(select(Account).where(Account.owner_id == user.id))).scalars()}
        for i, a in enumerate(data.get("accounts", [])):
            if a["name"] in existing_accounts:
                continue
            db.add(Account(owner_id=user.id, name=a["name"], kind=a.get("kind", "card"), bank=a.get("bank"),
                           card_last4=a.get("card_last4", []), is_business=a.get("is_business", False),
                           include_in_free=a.get("include_in_free", not a.get("is_business", False)), sort=i))
        names = {d.creditor for d in (await db.execute(select(Debt).where(Debt.owner_id == user.id))).scalars()}
        for d in data.get("debts", []):
            if d["creditor"] in names:
                continue
            db.add(Debt(owner_id=user.id, creditor=d["creditor"], kind=d.get("kind", "microloan"),
                        principal_minor=to_minor(Decimal(str(d.get("principal_rub", 0)))),
                        balance_minor=to_minor(Decimal(str(d.get("balance_rub", 0)))),
                        holiday_until=_d(d.get("holiday_until")), holiday_terms=d.get("holiday_terms"),
                        status=d.get("status", "holiday" if d.get("holiday_until") else "active"),
                        terms_confirmed=d.get("terms_confirmed", False), notes=d.get("notes")))
        goals = {g.name for g in (await db.execute(select(FinancialGoal).where(FinancialGoal.owner_id == user.id))).scalars()}
        for g in data.get("goals", []):
            if g["name"] in goals:
                continue
            db.add(FinancialGoal(owner_id=user.id, name=g["name"], target_minor=to_minor(Decimal(str(g["target_rub"]))),
                                 target_max_minor=to_minor(Decimal(str(g["target_max_rub"]))) if g.get("target_max_rub") else None,
                                 deadline=_d(g.get("deadline")), priority=g.get("priority", 2), notes=g.get("notes")))
        subs = {s.name for s in (await db.execute(select(Subscription).where(Subscription.owner_id == user.id))).scalars()}
        for s in data.get("subscriptions", []):
            if s["name"] in subs:
                continue
            db.add(Subscription(owner_id=user.id, name=s["name"], amount_minor=to_minor(Decimal(str(s["amount"]))),
                                currency=s.get("currency", "RUB"), period=s.get("period", "monthly"),
                                next_charge_date=_d(s.get("next_charge_date")), merchant_pattern=s.get("merchant_pattern")))
        plans = {p.name for p in (await db.execute(select(WorkoutPlan).where(WorkoutPlan.owner_id == user.id))).scalars()}
        for p in data.get("workout_plans", []):
            if p["name"] in plans:
                continue
            db.add(WorkoutPlan(owner_id=user.id, name=p["name"], weekday=p.get("weekday"),
                               muscle_groups=p.get("muscle_groups", []), planned_minutes=p.get("planned_minutes")))
        exercises = {e.name for e in (await db.execute(select(Exercise).where(Exercise.owner_id == user.id))).scalars()}
        for e in data.get("exercises", []):
            if e["name"] not in exercises:
                db.add(Exercise(owner_id=user.id, name=e["name"], muscle_group=e["muscle_group"]))
        if data.get("weight_kg"):
            from app.models import BodyMetric
            db.add(BodyMetric(owner_id=user.id, measured_at=utcnow(), kind="weight",
                              value=Decimal(str(data["weight_kg"])), unit="kg", note="стартовое значение"))
        for m in data.get("memories", []):
            from app.services.memory import upsert_memory
            await upsert_memory(db, user.id, type=m.get("type", "permanent"), content=m["content"],
                                category=m.get("category", "general"), importance=m.get("importance", 4),
                                source_kind="manual", status="confirmed", confidence=1.0,
                                evidence=[{"kind": "manual", "excerpt": "начальные данные из ТЗ"}])
    print("seed loaded")


def main() -> None:
    parser = argparse.ArgumentParser(prog="app.cli")
    sub = parser.add_subparsers(dest="cmd", required=True)
    sub.add_parser("init")
    p = sub.add_parser("pair")
    p.add_argument("--days", type=int, default=7)
    s = sub.add_parser("seed")
    s.add_argument("path")
    args = parser.parse_args()
    if args.cmd == "init":
        asyncio.run(cmd_init())
    elif args.cmd == "pair":
        asyncio.run(cmd_pair(args.days))
    elif args.cmd == "seed":
        asyncio.run(cmd_seed(args.path))


if __name__ == "__main__":
    main()
