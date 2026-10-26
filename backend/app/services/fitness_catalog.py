"""Starter exercise catalog so the first workout does not begin with typing every name.

Idempotent: adds only names the owner does not have yet (case-insensitive), never touches user edits.
"""
from __future__ import annotations

import uuid

from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.fitness import Exercise

# (name, muscle_group, equipment, secondary_groups)
DEFAULT_EXERCISES: list[tuple[str, str, str, list[str]]] = [
    # back
    ("Подтягивания", "back", "bodyweight", ["biceps"]),
    ("Тяга верхнего блока к груди", "back", "cable", ["biceps"]),
    ("Тяга штанги в наклоне", "back", "barbell", ["biceps"]),
    ("Тяга гантели одной рукой в наклоне", "back", "dumbbell", ["biceps"]),
    ("Тяга горизонтального блока", "back", "cable", ["biceps"]),
    ("Тяга Т-грифа", "back", "barbell", ["biceps"]),
    ("Тяга в рычажном тренажёре", "back", "machine", ["biceps"]),
    ("Становая тяга", "back", "barbell", ["legs"]),
    ("Гиперэкстензия", "back", "bodyweight", ["legs"]),
    ("Пуловер на верхнем блоке", "back", "cable", []),
    ("Шраги со штангой", "back", "barbell", ["shoulders"]),
    # biceps
    ("Подъём штанги на бицепс", "biceps", "barbell", []),
    ("Подъём гантелей на бицепс", "biceps", "dumbbell", []),
    ("Молотки с гантелями", "biceps", "dumbbell", []),
    ("Подъём на бицепс на скамье Скотта", "biceps", "barbell", []),
    ("Концентрированный подъём гантели", "biceps", "dumbbell", []),
    ("Сгибания рук на нижнем блоке", "biceps", "cable", []),
    # shoulders
    ("Жим штанги стоя", "shoulders", "barbell", ["triceps"]),
    ("Жим гантелей сидя", "shoulders", "dumbbell", ["triceps"]),
    ("Жим в тренажёре на плечи", "shoulders", "machine", ["triceps"]),
    ("Махи гантелями в стороны", "shoulders", "dumbbell", []),
    ("Махи в наклоне на заднюю дельту", "shoulders", "dumbbell", ["back"]),
    ("Обратная бабочка", "shoulders", "machine", ["back"]),
    ("Тяга каната к лицу", "shoulders", "cable", ["back"]),
    ("Подъём гантелей перед собой", "shoulders", "dumbbell", []),
    ("Тяга штанги к подбородку", "shoulders", "barbell", []),
    # chest
    ("Жим штанги лёжа", "chest", "barbell", ["triceps", "shoulders"]),
    ("Жим штанги на наклонной скамье", "chest", "barbell", ["triceps", "shoulders"]),
    ("Жим гантелей лёжа", "chest", "dumbbell", ["triceps", "shoulders"]),
    ("Жим гантелей на наклонной скамье", "chest", "dumbbell", ["triceps", "shoulders"]),
    ("Жим в тренажёре от груди", "chest", "machine", ["triceps"]),
    ("Разводка гантелей лёжа", "chest", "dumbbell", []),
    ("Сведение рук в кроссовере", "chest", "cable", []),
    ("Бабочка (пек-дек)", "chest", "machine", []),
    ("Отжимания на брусьях", "chest", "bodyweight", ["triceps"]),
    ("Отжимания от пола", "chest", "bodyweight", ["triceps"]),
    # triceps
    ("Французский жим лёжа", "triceps", "barbell", []),
    ("Разгибания на верхнем блоке с канатом", "triceps", "cable", []),
    ("Разгибания на верхнем блоке с прямой рукоятью", "triceps", "cable", []),
    ("Жим лёжа узким хватом", "triceps", "barbell", ["chest"]),
    ("Разгибание гантели из-за головы", "triceps", "dumbbell", []),
    ("Разгибание руки с гантелью в наклоне", "triceps", "dumbbell", []),
    ("Обратные отжимания от скамьи", "triceps", "bodyweight", ["chest"]),
    # legs
    ("Приседания со штангой", "legs", "barbell", ["core"]),
    ("Гоблет-приседания", "legs", "dumbbell", []),
    ("Жим ногами", "legs", "machine", []),
    ("Гакк-приседания", "legs", "machine", []),
    ("Выпады с гантелями", "legs", "dumbbell", []),
    ("Болгарские сплит-приседания", "legs", "dumbbell", []),
    ("Румынская тяга", "legs", "barbell", ["back"]),
    ("Разгибания ног в тренажёре", "legs", "machine", []),
    ("Сгибания ног в тренажёре", "legs", "machine", []),
    ("Ягодичный мост со штангой", "legs", "barbell", []),
    ("Сведение ног в тренажёре", "legs", "machine", []),
    ("Разведение ног в тренажёре", "legs", "machine", []),
    ("Подъём на носки стоя", "legs", "machine", []),
    ("Подъём на носки сидя", "legs", "machine", []),
    # core
    ("Планка", "core", "bodyweight", []),
    ("Боковая планка", "core", "bodyweight", []),
    ("Скручивания", "core", "bodyweight", []),
    ("Подъём ног в висе", "core", "bodyweight", []),
    ("Скручивания на верхнем блоке", "core", "cable", []),
    ("Русские скручивания", "core", "bodyweight", []),
    ("Ролик для пресса", "core", "other", []),
    # cardio
    ("Беговая дорожка", "cardio", "machine", []),
    ("Велотренажёр", "cardio", "machine", []),
    ("Эллипс", "cardio", "machine", []),
    ("Гребной тренажёр", "cardio", "machine", []),
    ("Степпер", "cardio", "machine", []),
    ("Скакалка", "cardio", "other", []),
]


async def ensure_default_exercises(db: AsyncSession, owner_id: uuid.UUID) -> int:
    have = set((await db.execute(
        select(func.lower(Exercise.name)).where(Exercise.owner_id == owner_id, Exercise.deleted_at.is_(None))
    )).scalars())
    added = 0
    for name, group, equipment, secondary in DEFAULT_EXERCISES:
        if name.lower() in have:
            continue
        db.add(Exercise(owner_id=owner_id, name=name, muscle_group=group, equipment=equipment,
                        secondary_groups=secondary, is_bodyweight=equipment == "bodyweight"))
        added += 1
    await db.flush()
    return added
