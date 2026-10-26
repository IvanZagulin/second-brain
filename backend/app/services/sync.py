"""Offline sync protocol.

push: the phone sends its outbox — ``{entity, id, op: upsert|delete, data, base_version,
client_updated_at}``. A change applies when ``base_version`` equals the server version (or the row
is new). On a version conflict the newer ``client_updated_at`` wins; the loser is reported back with
the server copy so nothing is silently lost.

pull: rows changed since a cursor (``server_seq``, bumped by a DB trigger on every write), merged
across all mirrored tables in global order.
"""

from __future__ import annotations

import uuid
from datetime import datetime
from typing import Any

from pydantic import BaseModel, ValidationError
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.timeutil import UTC, utcnow
from app.models import SyncEvent, sync_models
from app.models.base import Base
from app.services.crud import SYNC_EXCLUDE, coerce_numeric, run_hooks, schema_for, serialize

_TABLE_ORDER = {t.name: i for i, t in enumerate(Base.metadata.sorted_tables)}


class Change(BaseModel):
    entity: str
    id: uuid.UUID
    op: str = "upsert"
    data: dict[str, Any] = {}
    base_version: int | None = None
    client_updated_at: datetime | None = None


def _aware(dt: datetime | None) -> datetime | None:
    if dt is not None and dt.tzinfo is None:
        return dt.replace(tzinfo=UTC)
    return dt


async def push(db: AsyncSession, owner_id: uuid.UUID, device_id: uuid.UUID | None,
               changes: list[Change]) -> list[dict[str, Any]]:
    models = sync_models()
    ordered = sorted(changes, key=lambda c: (_TABLE_ORDER.get(c.entity, 999),
                                             0 if not c.data.get("parent_id") else 1))
    results = []
    for ch in ordered:
        model = models.get(ch.entity)
        if model is None or not model.__sync_push__:
            results.append({"entity": ch.entity, "id": str(ch.id), "status": "error", "error": "read_only"})
            continue
        try:
            async with db.begin_nested():
                res = await _apply(db, owner_id, model, ch)
        except (ValidationError, ValueError) as exc:
            res = {"status": "error", "error": "validation", "details": str(exc)[:500]}
        except Exception as exc:  # keep the rest of the batch going
            res = {"status": "error", "error": type(exc).__name__}
        res.update({"entity": ch.entity, "id": str(ch.id)})
        results.append(res)
        if res["status"] != "applied":
            db.add(SyncEvent(owner_id=owner_id, device_id=device_id, direction="push", entity=ch.entity,
                             entity_id=str(ch.id), op=ch.op, status=res["status"],
                             details={k: v for k, v in res.items() if k not in ("server",)}))
    return results


async def _apply(db: AsyncSession, owner_id: uuid.UUID, model: type[Base], ch: Change) -> dict[str, Any]:
    obj = await db.get(model, ch.id)
    if obj is not None and obj.owner_id != owner_id:
        return {"status": "error", "error": "forbidden"}
    client_ts = _aware(ch.client_updated_at) or utcnow()

    if ch.op == "delete":
        if obj is None:
            return {"status": "applied", "version": 0}
        if obj.deleted_at is None:
            obj.deleted_at = utcnow()
            obj.version += 1
            obj.client_updated_at = client_ts
            await run_hooks(db, owner_id, obj, "delete", {})
        await db.flush()
        return {"status": "applied", "version": obj.version}

    schema = schema_for(model, "patch" if obj is not None else "create")
    data = schema.model_validate(ch.data).model_dump(exclude_unset=True)
    data.pop("id", None)
    data = coerce_numeric(model, data)

    if obj is None:
        obj = model(id=ch.id, owner_id=owner_id, client_updated_at=client_ts, **data)
        db.add(obj)
        await db.flush()
        await run_hooks(db, owner_id, obj, "create", data)
        await db.flush()
        await db.refresh(obj)
        return {"status": "applied", "version": obj.version, "server_seq": obj.server_seq}

    conflict = ch.base_version is not None and ch.base_version != obj.version
    if conflict:
        server_ts = _aware(obj.client_updated_at) or _aware(getattr(obj, "updated_at", None)) or utcnow()
        if client_ts <= server_ts:
            return {"status": "conflict_server_wins", "version": obj.version, "server": serialize(obj)}
    for k, v in data.items():
        setattr(obj, k, v)
    obj.version += 1
    obj.client_updated_at = client_ts
    await run_hooks(db, owner_id, obj, "update", data)
    await db.flush()
    await db.refresh(obj)
    return {"status": "applied_conflict" if conflict else "applied", "version": obj.version,
            "server_seq": obj.server_seq}


async def pull(db: AsyncSession, owner_id: uuid.UUID, cursor: int, limit: int = 500,
               entities: list[str] | None = None) -> dict[str, Any]:
    models = sync_models()
    if entities:
        models = {k: v for k, v in models.items() if k in entities}
    batch: list[tuple[int, str, Any]] = []
    for name, model in models.items():
        rows = (await db.execute(
            select(model).where(model.owner_id == owner_id, model.server_seq > cursor)
            .order_by(model.server_seq).limit(limit)
        )).scalars().all()
        batch.extend((r.server_seq, name, r) for r in rows)
    batch.sort(key=lambda x: x[0])
    batch = batch[:limit]
    changes = []
    for seq, name, row in batch:
        model = models[name]
        parent_attr = model.__sync_parent__
        sort_attr = model.__sync_sort__
        sort_val = getattr(row, sort_attr) if sort_attr else None
        changes.append({
            "entity": name, "id": str(row.id), "version": row.version, "server_seq": seq,
            "deleted": row.deleted_at is not None,
            "parent_id": str(getattr(row, parent_attr)) if parent_attr and getattr(row, parent_attr) else None,
            "sort_at": sort_val.isoformat() if hasattr(sort_val, "isoformat") else sort_val,
            "data": serialize(row, SYNC_EXCLUDE.get(name)),
        })
    next_cursor = batch[-1][0] if batch else cursor
    return {"changes": changes, "cursor": next_cursor, "has_more": len(batch) >= limit}
