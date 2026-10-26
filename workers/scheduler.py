"""Background scheduler: keeps system jobs in place, dispatches due agent_jobs to the worker,
recovers jobs stuck after a crash and sweeps pending pushes, events and embeddings.

Run: ``python -m workers.scheduler``"""

from __future__ import annotations

import asyncio
import logging
import signal
from datetime import timedelta

from sqlalchemy import or_, select, update

from agent.jobs import ensure_system_jobs
from app.core.config import get_settings
from app.core.db import session_scope
from app.core.logging import setup_logging
from app.core.timeutil import utcnow
from app.models import AgentEvent, AgentJob, Notification
from app.services.queue import enqueue

log = logging.getLogger("scheduler")
TICK_SECONDS = 15
_stop = asyncio.Event()


async def dispatch_due() -> int:
    async with session_scope() as db:
        due = (await db.execute(
            select(AgentJob).where(AgentJob.status == "scheduled", AgentJob.next_run_at <= utcnow())
            .order_by(AgentJob.priority.desc(), AgentJob.next_run_at).limit(40).with_for_update(skip_locked=True)
        )).scalars().all()
        ids = [(j.id, j.attempts) for j in due]
        for j in due:
            j.status = "queued"
    for job_id, attempts in ids:
        ok = await enqueue("run_agent_job", str(job_id), job_id=f"agentjob:{job_id}:{attempts}")
        if not ok:
            async with session_scope() as db:
                j = await db.get(AgentJob, job_id)
                if j is not None and j.status == "queued":
                    j.status = "scheduled"
    return len(ids)


async def recover_stuck() -> None:
    cutoff = utcnow() - timedelta(minutes=35)
    async with session_scope() as db:
        await db.execute(update(AgentJob).where(AgentJob.status.in_(("queued", "running")),
                                                AgentJob.updated_at < cutoff)
                         .values(status="scheduled", next_run_at=utcnow()))


async def sweep() -> None:
    async with session_scope() as db:
        pending_push = (await db.execute(select(Notification.id).where(
            Notification.push_status == "pending", Notification.created_at < utcnow() - timedelta(seconds=30))
            .limit(50))).scalars().all()
        pending_events = (await db.execute(select(AgentEvent.id).where(
            or_(AgentEvent.status == "pending",
                (AgentEvent.status == "processing") & (AgentEvent.occurred_at < utcnow() - timedelta(minutes=30))))
            .limit(1))).scalars().all()
        if pending_events:
            await db.execute(update(AgentEvent).where(AgentEvent.status == "processing",
                                                      AgentEvent.occurred_at < utcnow() - timedelta(minutes=30))
                             .values(status="pending"))
    for nid in pending_push:
        await enqueue("push_notification", str(nid), job_id=f"push:{nid}:sweep")
    if pending_events:
        await enqueue("process_events", job_id="process_events")


async def main() -> None:
    setup_logging(get_settings().log_level)
    loop = asyncio.get_running_loop()
    for sig in (signal.SIGINT, signal.SIGTERM):
        try:
            loop.add_signal_handler(sig, _stop.set)
        except NotImplementedError:
            pass
    log.info("scheduler started")
    tick = 0
    while not _stop.is_set():
        try:
            if tick % 20 == 0:  # every ~5 minutes
                await ensure_system_jobs()
                await recover_stuck()
            if tick % 40 == 0:  # every ~10 minutes
                await enqueue("embed_memories", job_id=f"embed_memories:{tick}")
            await dispatch_due()
            await sweep()
        except Exception:
            log.exception("scheduler tick failed")
        tick += 1
        try:
            await asyncio.wait_for(_stop.wait(), timeout=TICK_SECONDS)
        except asyncio.TimeoutError:
            pass


if __name__ == "__main__":
    asyncio.run(main())
