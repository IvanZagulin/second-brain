"""Domain events. Producers only record them; agent.rules decides what to do."""

from __future__ import annotations

import uuid
from typing import Any

from sqlalchemy.dialects.postgresql import insert
from sqlalchemy.ext.asyncio import AsyncSession

from app.models import AgentEvent
from app.services.queue import enqueue


async def emit(db: AsyncSession, owner_id: uuid.UUID, type_: str, payload: dict[str, Any] | None = None,
               dedupe_key: str | None = None) -> None:
    stmt = insert(AgentEvent).values(owner_id=owner_id, type=type_, payload=payload or {}, dedupe_key=dedupe_key)
    if dedupe_key:
        stmt = stmt.on_conflict_do_nothing(index_elements=["owner_id", "dedupe_key"])
    await db.execute(stmt)
    await enqueue("process_events", job_id="process_events", defer_seconds=5)
