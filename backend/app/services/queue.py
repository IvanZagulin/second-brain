"""Thin wrapper over the arq Redis queue shared by the API, the scheduler and the worker."""

from __future__ import annotations

from datetime import timedelta
from typing import Any

from arq import ArqRedis, create_pool
from arq.connections import RedisSettings

from app.core.config import get_settings

_pool: ArqRedis | None = None


def redis_settings() -> RedisSettings:
    return RedisSettings.from_dsn(get_settings().redis_url)


async def get_pool() -> ArqRedis:
    global _pool
    if _pool is None:
        _pool = await create_pool(redis_settings())
    return _pool


async def enqueue(function: str, *args: Any, job_id: str | None = None,
                  defer_seconds: float | None = None, **kwargs: Any) -> bool:
    """Enqueue a worker function. Returns False when a job with the same id is already queued."""
    try:
        pool = await get_pool()
        job = await pool.enqueue_job(
            function, *args, _job_id=job_id,
            _defer_by=timedelta(seconds=defer_seconds) if defer_seconds else None, **kwargs)
        return job is not None
    except Exception:
        # The scheduler sweeps pending work, so a Redis hiccup only delays it.
        return False
