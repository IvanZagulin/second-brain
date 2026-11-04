"""Behaviour patterns computed by code, not guessed by the model: spending by weekday and hour, money
against mood, sleep against training and mood, impulse categories on bad days, promises kept. Each
finding carries its numbers and sample sizes; a finding is only reported when both groups are big
enough and the difference is large enough to mean something. Claude turns them into words."""

from __future__ import annotations

import uuid
from collections import defaultdict
from datetime import date, datetime, time, timedelta
from typing import Any

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from agent.insights import SHORT_SLEEP_MIN, health_by_day
from app.core.money import fmt_rub
from app.core.timeutil import RU_WEEKDAYS, local_now, tz
from app.models import BudgetCategory, MoodEntry, Task, Transaction, Workout
from app.services.finance.engine import ACTIVE

MIN_GROUP = 3          # days per group before two groups are compared
MIN_RATIO = 1.3        # the bigger side must be at least this many times the smaller
LOW_MOOD, GOOD_MOOD = 4, 7


def _ratio(a: float, b: float) -> float:
    return a / b if b else (float("inf") if a else 1.0)


async def _spend_by_day(db: AsyncSession, owner_id: uuid.UUID, start: date, end: date, zone) -> tuple[dict, dict, dict]:
    """Per local day: discretionary spend, impulse-category spend, and spend after 20:00."""
    impulse = set((await db.execute(select(BudgetCategory.id).where(BudgetCategory.owner_id == owner_id,
                                                                    BudgetCategory.is_impulse.is_(True)))).scalars())
    rows = (await db.execute(select(Transaction.occurred_at, Transaction.amount_minor, Transaction.category_id,
                                    Transaction.has_time).where(
        Transaction.owner_id == owner_id, Transaction.deleted_at.is_(None), Transaction.status.in_(ACTIVE),
        Transaction.direction == "out", Transaction.flow_type == "expense", Transaction.scope == "personal",
        Transaction.occurred_at >= datetime.combine(start, time.min, zone),
        Transaction.occurred_at < datetime.combine(end + timedelta(days=1), time.min, zone)))).all()
    spend: dict[date, int] = defaultdict(int)
    imp: dict[date, int] = defaultdict(int)
    evening: dict[date, int] = defaultdict(int)
    for at, amount, cat, has_time in rows:
        local = at.astimezone(zone)
        spend[local.date()] += amount
        if cat in impulse:
            imp[local.date()] += amount
        if has_time and local.hour >= 20:
            evening[local.date()] += amount
    return spend, imp, evening


async def find_patterns(db: AsyncSession, owner_id: uuid.UUID, weeks: int, tz_name: str | None) -> list[dict[str, Any]]:
    zone = tz(tz_name)
    today = local_now(tz_name).date()
    start = today - timedelta(days=weeks * 7 - 1)
    spend, impulse, evening = await _spend_by_day(db, owner_id, start, today, zone)
    findings: list[dict[str, Any]] = []
    all_days = [start + timedelta(days=i) for i in range((today - start).days + 1)]

    # 1. Weekday with the heaviest spending.
    by_wd: dict[int, list[int]] = defaultdict(list)
    for d in all_days:
        by_wd[d.weekday()].append(spend.get(d, 0))
    avgs = {wd: sum(v) / len(v) for wd, v in by_wd.items() if len(v) >= MIN_GROUP}
    if avgs and sum(spend.values()):
        overall = sum(spend.values()) / len(all_days)
        top_wd, top = max(avgs.items(), key=lambda kv: kv[1])
        if overall and top / overall >= 1.4:
            findings.append({"kind": "weekday_spending",
                             "finding": f"Самый дорогой день недели — {RU_WEEKDAYS[top_wd]}: в среднем {fmt_rub(int(top))} "
                                        f"против {fmt_rub(int(overall))} в обычный день (за {weeks} нед.).",
                             "numbers": {"weekday": top_wd, "avg_minor": int(top), "overall_avg_minor": int(overall)}})

    # 2. Share of spending after 20:00.
    total = sum(spend.values())
    eve = sum(evening.values())
    if total and eve / total >= 0.35:
        findings.append({"kind": "evening_spending",
                         "finding": f"{round(100 * eve / total)} % трат приходится на время после 20:00 "
                                    f"({fmt_rub(eve)} из {fmt_rub(total)}).",
                         "numbers": {"share_pct": round(100 * eve / total), "evening_minor": eve, "total_minor": total}})

    # 3–4. Mood against spending (same day and the day after) and impulse spending on low-mood days.
    moods = (await db.execute(select(MoodEntry.occurred_at, MoodEntry.score).where(
        MoodEntry.owner_id == owner_id, MoodEntry.deleted_at.is_(None),
        MoodEntry.occurred_at >= datetime.combine(start, time.min, zone)))).all()
    mood_by_day: dict[date, list[int]] = defaultdict(list)
    for at, score in moods:
        mood_by_day[at.astimezone(zone).date()].append(score)
    low = [d for d, s in mood_by_day.items() if sum(s) / len(s) <= LOW_MOOD]
    good = [d for d, s in mood_by_day.items() if sum(s) / len(s) >= GOOD_MOOD]
    if len(low) >= MIN_GROUP and len(good) >= MIN_GROUP:
        for label, shift in (("в тот же день", 0), ("на следующий день", 1)):
            a = sum(spend.get(d + timedelta(days=shift), 0) for d in low) / len(low)
            b = sum(spend.get(d + timedelta(days=shift), 0) for d in good) / len(good)
            if max(a, b) and _ratio(max(a, b), min(a, b)) >= MIN_RATIO:
                more = "плохого" if a > b else "хорошего"
                findings.append({"kind": f"mood_spending_{shift}",
                                 "finding": f"После {more} настроения траты {label} выше: при настроении ≤{LOW_MOOD} в среднем "
                                            f"{fmt_rub(int(a))}, при ≥{GOOD_MOOD} — {fmt_rub(int(b))} "
                                            f"(дней: {len(low)} и {len(good)}).",
                                 "numbers": {"low_avg_minor": int(a), "good_avg_minor": int(b), "n_low": len(low), "n_good": len(good)}})
        a = sum(impulse.get(d, 0) for d in low) / len(low)
        b = sum(impulse.get(d, 0) for d in good) / len(good)
        if a and _ratio(a, b) >= MIN_RATIO:
            findings.append({"kind": "impulse_low_mood",
                             "finding": f"В дни с плохим настроением импульсные категории (донаты, еда навынос и т. п.) тянут "
                                        f"{fmt_rub(int(a))} против {fmt_rub(int(b))} в хорошие дни.",
                             "numbers": {"low_avg_minor": int(a), "good_avg_minor": int(b)}})

    # 5–6. Sleep against training and mood.
    health = await health_by_day(db, owner_id, start, today, tz_name)
    short = {d for d, h in health.items() if h["sleep_min"] and h["sleep_min"] < SHORT_SLEEP_MIN}
    rested = {d for d, h in health.items() if h["sleep_min"] and h["sleep_min"] >= 7 * 60}
    if len(short) >= MIN_GROUP and len(rested) >= MIN_GROUP:
        workouts = (await db.execute(select(Workout.started_at, Workout.status).where(
            Workout.owner_id == owner_id, Workout.deleted_at.is_(None),
            Workout.started_at >= datetime.combine(start, time.min, zone)))).all()
        wd: dict[date, str] = {}
        for at, status in workouts:
            wd[at.astimezone(zone).date()] = status
        planned_short = [d for d in short if d in wd]
        planned_rested = [d for d in rested if d in wd]
        if len(planned_short) >= MIN_GROUP and len(planned_rested) >= MIN_GROUP:
            ds = sum(1 for d in planned_short if wd[d] == "done") / len(planned_short)
            dr = sum(1 for d in planned_rested if wd[d] == "done") / len(planned_rested)
            if dr and _ratio(dr, ds) >= MIN_RATIO:
                findings.append({"kind": "sleep_training",
                                 "finding": f"После короткой ночи (<6 ч) тренировка состоялась в {round(100 * ds)} % случаев, после "
                                            f"нормальной (≥7 ч) — в {round(100 * dr)} % ({len(planned_short)} и {len(planned_rested)} дн.).",
                                 "numbers": {"done_short_pct": round(100 * ds), "done_rested_pct": round(100 * dr)}})
        ms = [sum(mood_by_day[d]) / len(mood_by_day[d]) for d in short if d in mood_by_day]
        mr = [sum(mood_by_day[d]) / len(mood_by_day[d]) for d in rested if d in mood_by_day]
        if len(ms) >= MIN_GROUP and len(mr) >= MIN_GROUP and abs(sum(mr) / len(mr) - sum(ms) / len(ms)) >= 1.5:
            findings.append({"kind": "sleep_mood",
                             "finding": f"Настроение после короткой ночи в среднем {sum(ms) / len(ms):.1f}, после нормальной — "
                                        f"{sum(mr) / len(mr):.1f} (дней: {len(ms)} и {len(mr)}).",
                             "numbers": {"mood_short": round(sum(ms) / len(ms), 1), "mood_rested": round(sum(mr) / len(mr), 1)}})

    # 7. Promises: firm tasks finished on time vs overdue or moved.
    tasks = (await db.execute(select(Task.due_at, Task.completed_at, Task.status, Task.postponed_count).where(
        Task.owner_id == owner_id, Task.deleted_at.is_(None), Task.commitment == "firm", Task.due_at.is_not(None),
        Task.due_at >= datetime.combine(start, time.min, zone)))).all()
    firm = [t for t in tasks if t.status in ("done", "open", "cancelled") and t.due_at <= datetime.now(zone)]
    if len(firm) >= 5:
        on_time = sum(1 for t in firm if t.status == "done" and t.completed_at and t.completed_at <= t.due_at
                      and not t.postponed_count)
        moved = sum(1 for t in firm if t.postponed_count)
        share = on_time / len(firm)
        if share < 0.6:
            findings.append({"kind": "promises",
                             "finding": f"Из {len(firm)} твёрдых обещаний со сроком в срок выполнено {on_time}, переносилось {moved}.",
                             "numbers": {"firm": len(firm), "on_time": on_time, "moved": moved}})
    return findings


def patterns_text(findings: list[dict[str, Any]], limit: int = 4) -> str:
    return "\n".join(f"- {f['finding']}" for f in findings[:limit]) or "—"
