"""arq worker: executes agent jobs, pushes notifications, processes events, embeds memories.

Run: ``arq workers.worker.WorkerSettings``"""

from __future__ import annotations

import logging
import uuid
from typing import Any

from sqlalchemy import select

from agent import jobs, reports, rules
from app.core.config import get_settings
from app.core.db import session_scope
from app.core.logging import setup_logging
from app.core.timeutil import utcnow
from app.models import Device, Memory, MemoryEmbedding, Notification
from app.services.embeddings import MODEL_NAME, embed
from app.services.push import fcm
from app.services.queue import redis_settings

log = logging.getLogger("worker")


async def run_agent_job(ctx: dict, job_id: str) -> dict[str, Any]:
    return await jobs.run_job(uuid.UUID(job_id))


async def process_events(ctx: dict) -> int:
    return await rules.process_events()


async def run_system_task(ctx: dict, name: str, owner_id: str) -> Any:
    fn = {"categorize_pending": reports.categorize_pending,
          "profile_summary": reports.refresh_profile_summary,
          "home_brief": reports.home_brief}.get(name)
    return await fn(uuid.UUID(owner_id)) if fn else None


async def push_notification(ctx: dict, notification_id: str) -> str:
    async with session_scope() as db:
        n = await db.get(Notification, uuid.UUID(notification_id))
        if n is None or n.push_status != "pending":
            return "skip"
        devices = (await db.execute(select(Device).where(Device.owner_id == n.owner_id, Device.revoked_at.is_(None),
                                                         Device.fcm_token.is_not(None)))).scalars().all()
        if not devices or not fcm.available:
            n.push_status = "skipped"  # the phone picks it up on its next poll
            return "skipped"
        payload = {"type": "notification", "id": str(n.id), "title": n.title, "body": n.body, "category": n.category,
                   "priority": n.priority, "silent": "1" if n.silent else "0", "sensitive": "1" if n.sensitive else "0",
                   "conversation_id": str(n.conversation_id or ""), "action_id": str(n.action_id or ""),
                   "message_id": str(n.message_id or "")}
        targets = [(d.id, d.fcm_token) for d in devices]
    statuses = []
    for device_id, token in targets:
        try:
            status = await fcm.send(token, payload, high_priority=True)
        except Exception as exc:
            log.warning("fcm error %s", type(exc).__name__)
            status = "failed"
        statuses.append(status)
        if status == "unregistered":
            async with session_scope() as db:
                d = await db.get(Device, device_id)
                d.fcm_token = None
    async with session_scope() as db:
        n = await db.get(Notification, uuid.UUID(notification_id))
        n.push_status = "sent" if "sent" in statuses else "failed"
        if n.push_status == "sent":
            n.delivered_at = utcnow()
    return n.push_status


async def embed_memories(ctx: dict) -> int:
    total = 0
    for _ in range(20):
        async with session_scope() as db:
            rows = (await db.execute(
                select(Memory.id, Memory.owner_id, Memory.title, Memory.content)
                .outerjoin(MemoryEmbedding, MemoryEmbedding.memory_id == Memory.id)
                .where(MemoryEmbedding.memory_id.is_(None), Memory.deleted_at.is_(None)).limit(64))).all()
        if not rows:
            break
        vectors = await embed([f"{r.title or ''} {r.content}".strip() for r in rows], "passage")
        if vectors is None:
            break
        async with session_scope() as db:
            for r, vec in zip(rows, vectors):
                db.add(MemoryEmbedding(memory_id=r.id, owner_id=r.owner_id, model=MODEL_NAME, embedding=vec))
        total += len(rows)
    return total


async def startup(ctx: dict) -> None:
    setup_logging(get_settings().log_level)
    log.info("worker started")


class WorkerSettings:
    functions = [run_agent_job, process_events, run_system_task, push_notification, embed_memories]
    redis_settings = redis_settings()
    on_startup = startup
    max_jobs = 6
    job_timeout = 1800
    # Results aren't needed, and a kept result would block re-enqueueing jobs that reuse a fixed
    # _job_id for dedupe (process_events, embed_memories) until it expired.
    keep_result = 0
    max_tries = 3
