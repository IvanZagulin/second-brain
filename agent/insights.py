"""Deterministic summaries the agent reads but never computes itself: sleep, steps and heart rate from
Health Connect, habit status and streaks, nutrition totals, and the owner's ratings of the agent's own
messages. Everything is in the owner's local calendar."""

from __future__ import annotations

import uuid
from collections import defaultdict
from datetime import date, datetime, time, timedelta
from typing import Any

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.timeutil import local_now, tz, utcnow
from app.models import Habit, HabitLog, HealthRecord, Meal, Message

SHORT_SLEEP_MIN = 6 * 60
STREAK_WORTH_NOTICING = 7


def fmt_minutes(minutes: int | None) -> str:
    if minutes is None:
        return "нет данных"
    h, m = divmod(int(minutes), 60)
    return f"{h} ч {m:02d} мин" if h else f"{m} мин"


# ------------------------------------------------------------------ health

async def health_by_day(db: AsyncSession, owner_id: uuid.UUID, start: date, end: date,
                        tz_name: str | None) -> dict[date, dict[str, Any]]:
    """Per local day: sleep minutes (a session counts for the day it ends on), steps, resting heart rate."""
    zone = tz(tz_name)
    lo = datetime.combine(start - timedelta(days=1), time.min, zone)
    hi = datetime.combine(end + timedelta(days=1), time.min, zone)
    rows = (await db.execute(select(HealthRecord.kind, HealthRecord.start_at, HealthRecord.end_at, HealthRecord.value)
                             .where(HealthRecord.owner_id == owner_id, HealthRecord.start_at >= lo,
                                    HealthRecord.start_at < hi,
                                    HealthRecord.kind.in_(("sleep", "steps", "resting_hr", "heart_rate"))))).all()
    days: dict[date, dict[str, Any]] = defaultdict(lambda: {"sleep_min": 0, "steps": 0, "hr": []})
    for kind, s_at, e_at, value in rows:
        if kind == "sleep":
            day = (e_at or s_at).astimezone(zone).date()
            days[day]["sleep_min"] += int(value or 0)
        elif kind == "steps":
            days[s_at.astimezone(zone).date()]["steps"] += int(value or 0)
        elif kind == "resting_hr":
            days[s_at.astimezone(zone).date()]["hr"].append(float(value or 0))
    out: dict[date, dict[str, Any]] = {}
    for day, d in days.items():
        if start <= day <= end:
            out[day] = {"sleep_min": d["sleep_min"] or None, "steps": d["steps"] or None,
                        "resting_hr": round(sum(d["hr"]) / len(d["hr"])) if d["hr"] else None}
    return out


async def health_overview(db: AsyncSession, owner_id: uuid.UUID, days: int, tz_name: str | None) -> dict[str, Any]:
    today = local_now(tz_name).date()
    start = today - timedelta(days=days - 1)
    by_day = await health_by_day(db, owner_id, start, today, tz_name)
    sleeps = [d["sleep_min"] for d in by_day.values() if d["sleep_min"]]
    steps = [d["steps"] for d in by_day.values() if d["steps"]]
    hrs = [d["resting_hr"] for d in by_day.values() if d["resting_hr"]]
    return {
        "days_with_data": len(by_day),
        "last_night_sleep_min": (by_day.get(today) or {}).get("sleep_min"),
        "sleep_avg_min": round(sum(sleeps) / len(sleeps)) if sleeps else None,
        "short_nights": sum(1 for s in sleeps if s < SHORT_SLEEP_MIN),
        "steps_avg": round(sum(steps) / len(steps)) if steps else None,
        "resting_hr_avg": round(sum(hrs) / len(hrs)) if hrs else None,
        "days": [{"date": d.isoformat(), **v} for d, v in sorted(by_day.items())][-days:],
    }


async def last_night_sleep(db: AsyncSession, owner_id: uuid.UUID, tz_name: str | None) -> int | None:
    today = local_now(tz_name).date()
    by_day = await health_by_day(db, owner_id, today, today, tz_name)
    return (by_day.get(today) or {}).get("sleep_min")


# ------------------------------------------------------------------ habits

def habit_due(schedule: str | None, day: date) -> bool:
    rule = (schedule or "daily").strip()
    if rule == "weekdays":
        return day.isoweekday() <= 5
    if rule.startswith("weekly:"):
        days = {int(x) for x in rule.split(":", 1)[1].split(",") if x.strip().isdigit()}
        return day.isoweekday() in days
    return True


def _streak(done: set[date], schedule: str | None, last: date) -> int:
    """Consecutive due days with a mark, counting back from ``last`` (days the habit is not due are skipped)."""
    streak, d = 0, last
    for _ in range(400):
        if habit_due(schedule, d):
            if d not in done:
                break
            streak += 1
        d -= timedelta(days=1)
    return streak


async def habits_status(db: AsyncSession, owner_id: uuid.UUID, today: date) -> list[dict[str, Any]]:
    habits = (await db.execute(select(Habit).where(Habit.owner_id == owner_id, Habit.deleted_at.is_(None),
                                                   Habit.is_active.is_(True)))).scalars().all()
    if not habits:
        return []
    logs = (await db.execute(select(HabitLog.habit_id, HabitLog.day).where(
        HabitLog.owner_id == owner_id, HabitLog.deleted_at.is_(None), HabitLog.day > today - timedelta(days=400),
        HabitLog.habit_id.in_([h.id for h in habits])))).all()
    done: dict[uuid.UUID, set[date]] = defaultdict(set)
    for hid, day in logs:
        done[hid].add(day)
    out = []
    yesterday = today - timedelta(days=1)
    for h in habits:
        marks = done[h.id]
        done_today = today in marks
        streak = _streak(marks, h.schedule, today if done_today else yesterday)
        # A run that ended the day before yesterday and has no mark for yesterday: the streak is broken.
        before = _streak(marks, h.schedule, yesterday - timedelta(days=1))
        broken = (habit_due(h.schedule, yesterday) and yesterday not in marks and not done_today
                  and before >= STREAK_WORTH_NOTICING)
        out.append({"id": str(h.id), "name": h.name, "kind": h.kind, "schedule": h.schedule,
                    "due_today": habit_due(h.schedule, today), "done_today": done_today,
                    "streak_days": streak, "broken_streak_days": before if broken else 0})
    return out


async def habits_week(db: AsyncSession, owner_id: uuid.UUID, start: date, end: date) -> list[dict[str, Any]]:
    """Done/due counts per habit for the period (inclusive)."""
    habits = (await db.execute(select(Habit).where(Habit.owner_id == owner_id, Habit.deleted_at.is_(None),
                                                   Habit.is_active.is_(True)))).scalars().all()
    if not habits:
        return []
    logs = (await db.execute(select(HabitLog.habit_id, HabitLog.day).where(
        HabitLog.owner_id == owner_id, HabitLog.deleted_at.is_(None), HabitLog.day >= start, HabitLog.day <= end,
        HabitLog.habit_id.in_([h.id for h in habits])))).all()
    done: dict[uuid.UUID, set[date]] = defaultdict(set)
    for hid, day in logs:
        done[hid].add(day)
    out = []
    for h in habits:
        due = [start + timedelta(days=i) for i in range((end - start).days + 1) if habit_due(h.schedule, start + timedelta(days=i))]
        out.append({"name": h.name, "kind": h.kind, "due": len(due), "done": sum(1 for d in due if d in done[h.id])})
    return out


def habits_line(status: list[dict[str, Any]], *, only_missing: bool = False) -> str | None:
    due = [h for h in status if h["due_today"]]
    if not due:
        return None
    if only_missing:
        missing = [h["name"] for h in due if not h["done_today"]]
        return ("Не отмечены сегодня: " + ", ".join(missing) + ".") if missing else None
    parts = [f"{h['name']} ({'есть' if h['done_today'] else 'нет'}, серия {h['streak_days']})" for h in due]
    return "Привычки на сегодня: " + "; ".join(parts) + "."


# ------------------------------------------------------------------ nutrition

async def nutrition_overview(db: AsyncSession, owner_id: uuid.UUID, days: int, tz_name: str | None) -> dict[str, Any]:
    zone = tz(tz_name)
    today = local_now(tz_name).date()
    start = today - timedelta(days=days - 1)
    rows = (await db.execute(select(Meal).where(Meal.owner_id == owner_id, Meal.deleted_at.is_(None),
                                                Meal.eaten_at >= datetime.combine(start, time.min, zone)))).scalars().all()
    by_day: dict[date, dict[str, Any]] = defaultdict(lambda: {"kcal": 0, "protein_g": 0.0, "fat_g": 0.0, "carbs_g": 0.0,
                                                               "meals": 0, "estimated": 0})
    for m in rows:
        d = by_day[m.eaten_at.astimezone(zone).date()]
        d["meals"] += 1
        d["kcal"] += int(m.kcal or 0)
        d["protein_g"] += float(m.protein_g or 0)
        d["fat_g"] += float(m.fat_g or 0)
        d["carbs_g"] += float(m.carbs_g or 0)
        d["estimated"] += int(m.estimated_by == "llm")
    tracked = [d for d in by_day.values() if d["kcal"]]
    return {
        "days_tracked": len(tracked),
        "avg_kcal": round(sum(d["kcal"] for d in tracked) / len(tracked)) if tracked else None,
        "avg_protein_g": round(sum(d["protein_g"] for d in tracked) / len(tracked)) if tracked else None,
        "days": [{"date": d.isoformat(), **{k: (round(v) if isinstance(v, float) else v) for k, v in val.items()}}
                 for d, val in sorted(by_day.items())],
        "note": "Оценки по фото имеют погрешность 20–40 %; дни без записей не значат, что владелец не ел.",
    }


# ------------------------------------------------------------------ owner feedback on agent messages

async def feedback_hint(db: AsyncSession, owner_id: uuid.UUID, limit: int = 20) -> str | None:
    """One line for the initiative prompt: how the owner rated the agent's last messages."""
    rows = (await db.execute(select(Message.text, Message.feedback).where(
        Message.owner_id == owner_id, Message.role == "assistant", Message.source == "agent",
        Message.deleted_at.is_(None)).order_by(Message.created_at.desc()).limit(limit))).all()
    rated = [(t, f) for t, f in rows if f in ("useful", "noise")]
    if not rated:
        return None
    useful = sum(1 for _, f in rated if f == "useful")
    noise = [t for t, f in rated if f == "noise"]
    line = f"Оценки владельца твоих последних сообщений: полезно {useful}, лишнее {len(noise)} из {len(rated)} оценённых."
    if noise:
        line += " Лишними он назвал: " + "; ".join(f"«{t[:70].strip()}…»" for t in noise[:3])
    if len(noise) >= 3 and len(noise) >= useful:
        line += " Вывод: пиши заметно реже и только по весомому поводу."
    return line


__all__ = ["fmt_minutes", "health_by_day", "health_overview", "last_night_sleep", "habit_due", "habits_status",
           "habits_week", "habits_line", "nutrition_overview", "feedback_hint", "SHORT_SLEEP_MIN", "utcnow"]
