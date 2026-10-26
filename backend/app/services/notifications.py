from __future__ import annotations

import uuid
from typing import Any

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.timeutil import in_quiet_hours, local_now
from app.models import Notification, User
from app.services.queue import enqueue
from app.services.settings import get_setting


async def create_notification(db: AsyncSession, owner_id: uuid.UUID, *, title: str, body: str,
                              category: str = "agent", priority: str = "normal", silent: bool | None = None,
                              conversation_id: uuid.UUID | None = None, message_id: uuid.UUID | None = None,
                              action_id: uuid.UUID | None = None, data: dict[str, Any] | None = None,
                              dedupe_key: str | None = None, sensitive: bool = False) -> Notification | None:
    if dedupe_key:
        exists = (await db.execute(
            select(Notification.id).where(Notification.owner_id == owner_id, Notification.dedupe_key == dedupe_key)
        )).scalar_one_or_none()
        if exists:
            return None
    if silent is None:
        schedule = await get_setting(db, owner_id, "schedule")
        user = await db.get(User, owner_id)
        now = local_now(user.timezone if user else None)
        silent = priority != "high" and in_quiet_hours(now, schedule.get("sleep_start", "23:30"),
                                                       schedule.get("sleep_end", "07:00"))
    n = Notification(owner_id=owner_id, title=title[:200], body=body[:4000], category=category,
                     priority=priority, silent=silent, conversation_id=conversation_id, message_id=message_id,
                     action_id=action_id, data=data or {}, dedupe_key=dedupe_key, sensitive=sensitive)
    db.add(n)
    await db.flush()
    # Deferred so the row is committed before the worker reads it; the scheduler sweeps stragglers.
    await enqueue("push_notification", str(n.id), job_id=f"push:{n.id}", defer_seconds=3)
    return n
