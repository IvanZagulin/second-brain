"""LLM estimates that write structured results: meal nutrition (photo/description) and personal
experiment conclusions."""

from __future__ import annotations

import base64
import uuid
from datetime import datetime, time, timedelta
from pathlib import Path
from typing import Any

from sqlalchemy import func, select

from agent.chat import system_prompt
from app.core.db import session_scope
from app.core.errors import ApiError
from app.core.money import fmt_rub
from app.core.timeutil import tz, utcnow
from app.models import (
    Attachment, BodyMetric, BudgetCategory, Experiment, HealthRecord, Meal, MoodEntry, Transaction, User, Workout,
)
from app.services.llm.client import call, call_json
from app.services.settings import get_setting

MEAL_SCHEMA = {
    "type": "object",
    "properties": {
        "description": {"type": "string"},
        "kcal": {"type": "integer"},
        "protein_g": {"type": "number"},
        "fat_g": {"type": "number"},
        "carbs_g": {"type": "number"},
        "error_pct": {"type": "integer", "description": "Честная оценка погрешности, %"},
        "comment": {"type": "string"},
    },
    "required": ["description", "kcal", "protein_g", "fat_g", "carbs_g", "error_pct", "comment"],
    "additionalProperties": False,
}


async def estimate_meal(owner_id: uuid.UUID, description: str | None, attachment_id: uuid.UUID | None,
                        estimate: bool = True) -> Meal:
    image_block = None
    async with session_scope() as db:
        privacy = await get_setting(db, owner_id, "privacy")
        fit = await get_setting(db, owner_id, "fitness")
        if attachment_id:
            att = await db.get(Attachment, attachment_id)
            if att is None or att.owner_id != owner_id:
                raise ApiError("Фото не найдено", status_code=404)
            if privacy.get("allow_image_analysis"):
                image_block = {"type": "image", "source": {"type": "base64", "media_type": att.mime,
                                                           "data": base64.standard_b64encode(Path(att.path).read_bytes()).decode()}}
    parsed: dict[str, Any] | None = None
    if estimate and (description or image_block):
        content: list[dict] = []
        if image_block:
            content.append(image_block)
        content.append({"type": "text", "text": f"Описание: {description or 'по фото'}. Оцени калорийность и БЖУ порции. "
                                                f"Режим учёта: {fit.get('nutrition_mode')}. Цель владельца: снижение жира с сохранением мышц."})
        parsed = await call_json(owner_id, "vision", schema=MEAL_SCHEMA, max_tokens=2000, user=content,
                                 system="Ты нутрициолог. Оцениваешь приём пищи по фото и описанию. Не преувеличивай точность: "
                                        "указывай реалистичную погрешность (обычно 20–40% по фото).")
    async with session_scope() as db:
        meal = Meal(owner_id=owner_id, eaten_at=utcnow(), description=(parsed or {}).get("description") or description,
                    attachment_id=attachment_id, kcal=(parsed or {}).get("kcal"), protein_g=(parsed or {}).get("protein_g"),
                    fat_g=(parsed or {}).get("fat_g"), carbs_g=(parsed or {}).get("carbs_g"),
                    error_pct=(parsed or {}).get("error_pct"), estimated_by="llm" if parsed else "user",
                    note=(parsed or {}).get("comment"))
        db.add(meal)
        await db.flush()
        await db.refresh(meal)
        return meal


async def evaluate_experiment(owner_id: uuid.UUID, experiment_id: uuid.UUID) -> Experiment:
    async with session_scope() as db:
        exp = await db.get(Experiment, experiment_id)
        if exp is None or exp.owner_id != owner_id:
            raise ApiError("Эксперимент не найден", status_code=404)
        user = await db.get(User, owner_id)
        zone = tz(user.timezone if user else None)
        start = datetime.combine(exp.start_date, time.min, zone)
        end = datetime.combine(min(exp.end_date, utcnow().astimezone(zone).date()), time.max, zone)
        length = max((end - start).days, 1)
        before_start = start - timedelta(days=length)

        async def period(a: datetime, b: datetime) -> dict[str, Any]:
            spend = (await db.execute(select(BudgetCategory.name, func.sum(Transaction.amount_minor))
                                      .join(BudgetCategory, BudgetCategory.id == Transaction.category_id, isouter=True)
                                      .where(Transaction.owner_id == owner_id, Transaction.direction == "out",
                                             Transaction.flow_type == "expense", Transaction.deleted_at.is_(None),
                                             Transaction.occurred_at.between(a, b))
                                      .group_by(BudgetCategory.name))).all()
            mood = (await db.execute(select(func.avg(MoodEntry.score)).where(
                MoodEntry.owner_id == owner_id, MoodEntry.occurred_at.between(a, b)))).scalar_one()
            sleep = (await db.execute(select(func.avg(HealthRecord.value)).where(
                HealthRecord.owner_id == owner_id, HealthRecord.kind == "sleep", HealthRecord.start_at.between(a, b)))).scalar_one()
            workouts = (await db.execute(select(func.count()).select_from(Workout).where(
                Workout.owner_id == owner_id, Workout.status == "done", Workout.started_at.between(a, b)))).scalar_one()
            weight = (await db.execute(select(BodyMetric.value).where(
                BodyMetric.owner_id == owner_id, BodyMetric.kind == "weight", BodyMetric.measured_at.between(a, b))
                .order_by(BodyMetric.measured_at.desc()).limit(1))).scalar_one_or_none()
            return {"spend": {n or "без категории": fmt_rub(int(s or 0)) for n, s in spend},
                    "mood_avg": round(float(mood), 1) if mood else None,
                    "sleep_avg_min": round(float(sleep)) if sleep else None, "workouts": workouts,
                    "last_weight": float(weight) if weight else None}

        during = await period(start, end)
        before = await period(before_start, start)
        system = await system_prompt(db, owner_id)
        title, hypothesis, metrics, finished = exp.title, exp.hypothesis, exp.metrics, exp.end_date <= utcnow().date()
    result = await call(owner_id, "analysis", system=system, max_tokens=4000, priority=2, messages=[{"role": "user", "content": (
        f"Личный эксперимент «{title}». Гипотеза: {hypothesis}. Что измеряем: {metrics}.\n"
        f"До эксперимента ({length} дн.): {before}\nВо время: {during}\n"
        "Сравни, подтвердилась ли гипотеза, честно оцени, достаточно ли данных, и предложи, что делать дальше. До 180 слов.")}])
    async with session_scope() as db:
        exp = await db.get(Experiment, experiment_id)
        exp.results = {"before": before, "during": during, "evaluated_at": utcnow().isoformat()}
        exp.conclusion = result.text
        if finished and exp.status == "active":
            exp.status = "finished"
        exp.version += 1
        await db.flush()
        await db.refresh(exp)
        return exp
