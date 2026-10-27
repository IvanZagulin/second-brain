from __future__ import annotations

from datetime import date, datetime, time, timedelta, timezone
from zoneinfo import ZoneInfo

UTC = timezone.utc


def utcnow() -> datetime:
    return datetime.now(UTC)


def tz(name: str | None) -> ZoneInfo:
    try:
        return ZoneInfo(name or "Europe/Moscow")
    except Exception:
        return ZoneInfo("Europe/Moscow")


def local_now(tz_name: str | None) -> datetime:
    return datetime.now(tz(tz_name))


def local_day_bounds(day: date, tz_name: str | None) -> tuple[datetime, datetime]:
    zone = tz(tz_name)
    start = datetime.combine(day, time.min, zone)
    return start.astimezone(UTC), (start + timedelta(days=1)).astimezone(UTC)


def month_bounds(day: date, tz_name: str | None) -> tuple[datetime, datetime]:
    zone = tz(tz_name)
    start = datetime(day.year, day.month, 1, tzinfo=zone)
    nxt = datetime(day.year + (day.month == 12), day.month % 12 + 1, 1, tzinfo=zone)
    return start.astimezone(UTC), nxt.astimezone(UTC)


def parse_hhmm(value: str | None, default: str = "00:00") -> time:
    raw = value or default
    try:
        h, m = raw.split(":")[:2]
        return time(int(h), int(m))
    except Exception:
        h, m = default.split(":")
        return time(int(h), int(m))


def in_quiet_hours(now_local: datetime, start: str, end: str) -> bool:
    """True when local time is inside [start, end), which may wrap past midnight."""
    s, e, t = parse_hhmm(start), parse_hhmm(end), now_local.time()
    if s <= e:
        return s <= t < e
    return t >= s or t < e


RU_WEEKDAYS = ["понедельник", "вторник", "среда", "четверг", "пятница", "суббота", "воскресенье"]
RU_MONTHS_GEN = ["января", "февраля", "марта", "апреля", "мая", "июня", "июля", "августа",
                 "сентября", "октября", "ноября", "декабря"]


def ru_date(d: date | datetime) -> str:
    return f"{d.day} {RU_MONTHS_GEN[d.month - 1]} {d.year}"


def day_part(now_local: datetime) -> str:
    return "утро" if now_local.hour < 12 else "день" if now_local.hour < 18 else "вечер"


def brief_key(now_local: datetime, last_workout_id: object | None) -> str:
    """When the home brief is due again: a new part of the day or a newly finished workout."""
    return f"{now_local.date().isoformat()}:{day_part(now_local)}:{last_workout_id or '-'}"
