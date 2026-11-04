from __future__ import annotations

from collections.abc import AsyncIterator
from contextlib import asynccontextmanager

from sqlalchemy import event, inspect
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker, create_async_engine
from sqlalchemy.orm import Session

from app.core.config import get_settings

_settings = get_settings()

engine = create_async_engine(
    _settings.database_url,
    pool_size=5,
    max_overflow=5,
    pool_pre_ping=True,
    pool_recycle=1800,
)

SessionLocal = async_sessionmaker(engine, expire_on_commit=False, autoflush=False)


@event.listens_for(Session, "before_flush")
def _bump_sync_versions(session: Session, _ctx, _instances) -> None:
    """Every mirrored row that changes gets a new ``version`` — the phone's optimistic-concurrency token —
    whether or not the code path remembered ``obj.version += 1``. A manual bump in the same flush is kept."""
    from app.models.base import SyncMixin  # local import: models import this module

    for obj in session.dirty:
        if not isinstance(obj, SyncMixin) or not session.is_modified(obj, include_collections=False):
            continue
        state = inspect(obj)
        if state.attrs.version.history.has_changes():
            continue
        changed = [a.key for a in state.attrs if a.history.has_changes()]
        if changed and set(changed) - {"use_count", "last_used_at", "updated_at"}:
            obj.version = (obj.version or 0) + 1


async def get_db() -> AsyncIterator[AsyncSession]:
    async with SessionLocal() as session:
        try:
            yield session
            await session.commit()
        except Exception:
            await session.rollback()
            raise


@asynccontextmanager
async def session_scope() -> AsyncIterator[AsyncSession]:
    """Session for workers and background code: commits on success, rolls back on error."""
    async with SessionLocal() as session:
        try:
            yield session
            await session.commit()
        except Exception:
            await session.rollback()
            raise
