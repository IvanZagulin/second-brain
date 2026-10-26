"""Key-value app settings stored per owner. Defaults hold no personal data: the owner's real
schedule, income plan and debts are loaded once by ``app.cli seed`` and then edited in the app."""

from __future__ import annotations

import copy
import uuid
from typing import Any

from sqlalchemy import select
from sqlalchemy.dialects.postgresql import insert
from sqlalchemy.ext.asyncio import AsyncSession

from app.models import AppSetting

DEFAULTS: dict[str, Any] = {
    "models": {
        "chat": "claude-sonnet-5-5",
        "analysis": "claude-opus-5-5",
        "background": "claude-haiku-5-5",
        "import": "claude-haiku-5-5",
        "vision": "claude-haiku-5-5",
    },
    "effort": {"chat": "medium", "analysis": "high", "background": "low", "import": "low", "vision": "low"},
    "api_budget": {
        "monthly_limit_usd": 100,
        "warn_thresholds": [0.5, 0.8, 0.95],
        "degrade": True,
        "background_pause_ratio": 0.8,
        "analysis_downgrade_ratio": 0.8,
        "chat_downgrade_ratio": 0.95,
    },
    "initiative": {
        "enabled": True,
        "max_per_day": 6,
        "min_interval_minutes": 90,
        "paused_until": None,
        "morning_brief": True,
        "morning_time": "07:40",
        "evening_checkin": True,
        "evening_time": "21:30",
        "weekly_report_day": 7,
        "weekly_report_time": "19:00",
    },
    "schedule": {
        "wake": "07:00",
        "leave_home": "08:00",
        "work_start": "09:00",
        "work_end": "18:00",
        "home_no_training": "19:30",
        "sleep_start": "23:30",
        "sleep_end": "07:00",
        "notes": "",
    },
    "privacy": {
        "allow_image_analysis": False,
        "allow_progress_photos_llm": False,
        "allow_import_llm": True,
        "send_secret_memories": False,
        "hide_sensitive_in_notifications": True,
        "memory_access": {t: True for t in ("permanent", "episodic", "behavioral", "working", "analytical")},
    },
    "finance": {
        "large_purchase_minor": 300_000,
        "income_days": [],
        "income_plan": [],  # [{"name", "day", "amount_minor", "account": "card"|"cash"}], estimates only
        "savings_mode": False,
        "savings_mode_cut_pct": 30,
        "notification_packages": [
            "ru.nspk.mirpay",
            "ru.sberbankmobile",
            "ru.sberbank.sberbankid",
            "com.idamob.tinkoff.android",
            "com.tinkoff.business",
        ],
        "proverkacheka_token": None,
        "tax": {
            "regime": "usn6",  # usn6 | usn15 | npd | patent | none
            "rate_pct": 6,
            "insurance_fixed_minor": 5_739_000,  # 2026 fixed contributions; check annually
            "insurance_1pct_threshold_minor": 30_000_000,
            "reduce_by_insurance": True,
        },
    },
    "fitness": {
        "training_days": [],
        "training_time": "19:00",
        "missed_check_time": "22:30",
        "height_cm": None,
        "target_weight": None,
        "nutrition_mode": "off",  # off | simple | detailed
    },
    "agent": {
        "crisis_support": True,
        "auto_mode": True,
        "extract_memories": True,
    },
    "tool_permissions": {},  # {"tool_name": {"level": "A"|"B"|"C", "enabled": bool}}
}


def _merge(base: Any, override: Any) -> Any:
    if isinstance(base, dict) and isinstance(override, dict):
        out = dict(base)
        for k, v in override.items():
            out[k] = _merge(base.get(k), v) if k in base else v
        return out
    return copy.deepcopy(override)


async def get_setting(db: AsyncSession, owner_id: uuid.UUID, key: str) -> Any:
    row = (await db.execute(
        select(AppSetting.value).where(AppSetting.owner_id == owner_id, AppSetting.key == key)
    )).scalar_one_or_none()
    default = copy.deepcopy(DEFAULTS.get(key))
    if row is None:
        return default
    return _merge(default, row) if isinstance(default, dict) else row


async def get_all_settings(db: AsyncSession, owner_id: uuid.UUID) -> dict[str, Any]:
    rows = (await db.execute(
        select(AppSetting.key, AppSetting.value).where(AppSetting.owner_id == owner_id)
    )).all()
    stored = {k: v for k, v in rows}
    out = {}
    for key, default in DEFAULTS.items():
        out[key] = _merge(copy.deepcopy(default), stored[key]) if key in stored and isinstance(default, dict) \
            else stored.get(key, copy.deepcopy(default))
    for key, value in stored.items():
        out.setdefault(key, value)
    return out


async def set_setting(db: AsyncSession, owner_id: uuid.UUID, key: str, value: Any, merge: bool = True) -> Any:
    if merge and isinstance(value, dict):
        current = await get_setting(db, owner_id, key)
        if isinstance(current, dict):
            value = _merge(current, value)
    stmt = insert(AppSetting).values(owner_id=owner_id, key=key, value=value)
    stmt = stmt.on_conflict_do_update(index_elements=["owner_id", "key"], set_={"value": value})
    await db.execute(stmt)
    return value
