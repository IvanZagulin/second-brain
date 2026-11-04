"""Background scheduler: keeps system jobs in place, dispatches due agent_jobs to the worker,
recovers jobs stuck after a crash and sweeps pending pushes, events and embeddings.

Run: ``python -m workers.scheduler``"""

from __future__ import annotations

import asyncio
import logging
import signal
from datetime import timedelta

import httpx
from sqlalchemy import or_, select, update

from agent.jobs import ensure_system_jobs
from app.core.config import get_settings
from app.core.db import session_scope
from app.core.logging import setup_logging
from app.core.redis import get_redis
from app.core.timeutil import utcnow
from app.models import AgentEvent, AgentJob, Notification, User
from app.services.notifications import create_notification
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
        # ``sending`` is a claim held by a worker; one older than 5 minutes died mid-send and is reclaimed.
        stale = (await db.execute(update(Notification).where(
            Notification.push_status == "sending", Notification.created_at < utcnow() - timedelta(minutes=5))
            .values(push_status="pending"))).rowcount
        if stale:
            log.warning("reclaimed %d stuck push claims", stale)
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


HEARTBEAT_KEY = "scheduler:heartbeat"
EMBEDDER_FAIL_KEY = "ops:embedder_failures"
BACKUP_MARKER = "backup-last-ok"
BACKUP_STALE_HOURS = 36


async def heartbeat() -> None:
    """The API's /system/health reads this: a scheduler that stopped ticking is otherwise invisible."""
    try:
        await get_redis().set(HEARTBEAT_KEY, utcnow().isoformat(), ex=600)
    except Exception as exc:
        log.warning("heartbeat failed: %s", type(exc).__name__)


async def _notify_owners(title: str, body: str, dedupe_key: str) -> None:
    async with session_scope() as db:
        for user in (await db.execute(select(User))).scalars():
            await create_notification(db, user.id, title=title, body=body, category="system", priority="normal",
                                      dedupe_key=dedupe_key)


async def ops_checks() -> None:
    """Hourly: the owner is the only operator, so the things that fail silently push to the phone."""
    s = get_settings()
    today = utcnow().date().isoformat()
    # Backups: backup.sh writes a marker after a verified dump (the backup service shares /data).
    marker = s.data_dir / BACKUP_MARKER
    age_h = None
    if marker.exists():
        try:
            from datetime import datetime
            age_h = (utcnow() - datetime.fromisoformat(marker.read_text().strip())).total_seconds() / 3600
        except ValueError:
            age_h = None
    if age_h is None or age_h > BACKUP_STALE_HOURS:
        await _notify_owners("Бэкап не делался",
                             "Последняя удачная резервная копия " + (f"{int(age_h)} ч назад" if age_h else "не найдена")
                             + ". Проверь `docker compose logs backup` на сервере.", f"ops:backup:{today}")
    # Embedder: two failed hourly pings in a row = search is running on full text only.
    redis = get_redis()
    try:
        async with httpx.AsyncClient(timeout=10) as client:
            (await client.get(f"{s.embedder_url}/health")).raise_for_status()
        await redis.delete(EMBEDDER_FAIL_KEY)
    except Exception:
        failures = await redis.incr(EMBEDDER_FAIL_KEY)
        await redis.expire(EMBEDDER_FAIL_KEY, 6 * 3600)
        if failures >= 2:
            await _notify_owners("Сервис эмбеддингов не отвечает",
                                 "Поиск по памяти работает только по словам, новые воспоминания не индексируются. "
                                 "На сервере: `docker compose restart embedder`.", f"ops:embedder:{today}")


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
            if tick % 240 == 0:  # every ~hour
                await ops_checks()
            await dispatch_due()
            await sweep()
            await heartbeat()
        except Exception:
            log.exception("scheduler tick failed")
        tick += 1
        try:
            await asyncio.wait_for(_stop.wait(), timeout=TICK_SECONDS)
        except asyncio.TimeoutError:
            pass


if __name__ == "__main__":
    asyncio.run(main())
