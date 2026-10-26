"""Schema generation and a generic CRUD router for the many simple entities.

Pydantic models are derived from the SQLAlchemy columns so the API, the sync protocol and the
tables can't drift apart. Domain side effects live in WRITE_HOOKS and run for both REST writes
and offline sync pushes."""


import uuid
from collections.abc import Awaitable, Callable
from datetime import date, datetime
from decimal import Decimal
from typing import Any, Optional

from fastapi import APIRouter, Depends, Query
from pydantic import BaseModel, ConfigDict, create_model
from sqlalchemy import Boolean, Date, DateTime, Float, Integer, BigInteger, Numeric, String, Text, inspect, select
from sqlalchemy.dialects.postgresql import JSONB, TSVECTOR, UUID as PG_UUID
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.db import get_db
from app.core.deps import Auth, get_auth
from app.core.errors import NotFound
from app.core.timeutil import utcnow
from app.models.base import Base

SERVER_FIELDS = {"owner_id", "server_seq", "version", "created_at", "updated_at", "deleted_at", "tsv", "dedupe_key"}
HIDDEN_FIELDS = {"owner_id", "tsv", "dedupe_key"}
# Large or internal fields not mirrored to the phone.
SYNC_EXCLUDE: dict[str, set[str]] = {"messages": {"blocks", "usage"}}

WriteHook = Callable[[AsyncSession, uuid.UUID, Any, str, dict[str, Any]], Awaitable[None]]
WRITE_HOOKS: dict[str, list[WriteHook]] = {}


def on_write(table: str):
    def deco(fn: WriteHook) -> WriteHook:
        WRITE_HOOKS.setdefault(table, []).append(fn)
        return fn
    return deco


async def run_hooks(db: AsyncSession, owner_id: uuid.UUID, obj: Any, op: str, changes: dict[str, Any]) -> None:
    for hook in WRITE_HOOKS.get(obj.__tablename__, []):
        await hook(db, owner_id, obj, op, changes)


def _py_type(col) -> Any:
    t = col.type
    if isinstance(t, TSVECTOR):
        return None
    if isinstance(t, PG_UUID):
        return uuid.UUID
    if isinstance(t, JSONB):
        return Any
    if isinstance(t, (Integer, BigInteger)):
        return int
    if isinstance(t, Numeric) and not isinstance(t, Float):
        return float
    if isinstance(t, Float):
        return float
    if isinstance(t, Boolean):
        return bool
    if isinstance(t, DateTime):
        return datetime
    if isinstance(t, Date):
        return date
    if isinstance(t, (String, Text)):
        return str
    try:
        return t.python_type
    except Exception:
        return Any


_cache: dict[tuple[str, str], type[BaseModel]] = {}


def schema_for(model: type[Base], kind: str) -> type[BaseModel]:
    """kind: "out" (read), "create", "patch"."""
    key = (model.__tablename__, kind)
    if key in _cache:
        return _cache[key]
    fields: dict[str, Any] = {}
    for col in inspect(model).columns:
        name = col.key
        typ = _py_type(col)
        if typ is None or name in HIDDEN_FIELDS:
            continue
        if kind == "out":
            fields[name] = (Optional[typ], None)
            continue
        if name in SERVER_FIELDS or (name == "id" and kind == "patch"):
            continue
        if name == "id":
            fields[name] = (Optional[uuid.UUID], None)
            continue
        has_default = col.default is not None or col.server_default is not None or col.nullable
        if kind == "create" and not has_default:
            fields[name] = (typ, ...)
        else:
            fields[name] = (Optional[typ], None)
    cfg = ConfigDict(from_attributes=True, extra="ignore" if kind != "out" else "ignore")
    schema = create_model(f"{model.__name__}{kind.title()}", __config__=cfg, **fields)
    _cache[key] = schema
    return schema


def serialize(obj: Any, exclude: set[str] | None = None) -> dict[str, Any]:
    data = schema_for(type(obj), "out").model_validate(obj).model_dump(mode="json")
    for k in exclude or ():
        data.pop(k, None)
    return data


def coerce_numeric(model: type[Base], data: dict[str, Any]) -> dict[str, Any]:
    cols = {c.key: c for c in inspect(model).columns}
    out = {}
    for k, v in data.items():
        col = cols.get(k)
        if col is not None and isinstance(col.type, Numeric) and not isinstance(col.type, Float) and v is not None:
            v = Decimal(str(v))
        out[k] = v
    return out


def crud_router(model: type[Base], prefix: str, tag: str, *, order_by: str | None = None,
                soft_delete: bool = True) -> APIRouter:
    router = APIRouter(prefix=prefix, tags=[tag])
    Create = schema_for(model, "create")
    Patch = schema_for(model, "patch")
    has_deleted = hasattr(model, "deleted_at")
    order_col = getattr(model, order_by or ("created_at" if hasattr(model, "created_at") else "id"))

    @router.get("")
    async def list_items(limit: int = Query(100, le=1000), offset: int = 0, include_deleted: bool = False,
                         auth: Auth = Depends(get_auth), db: AsyncSession = Depends(get_db)) -> dict[str, Any]:
        q = select(model).where(model.owner_id == auth.owner_id)
        if has_deleted and not include_deleted:
            q = q.where(model.deleted_at.is_(None))
        rows = (await db.execute(q.order_by(order_col.desc().nulls_last()).limit(limit).offset(offset))).scalars().all()
        return {"items": [serialize(r) for r in rows], "limit": limit, "offset": offset}

    @router.get("/{item_id}")
    async def get_item(item_id: uuid.UUID, auth: Auth = Depends(get_auth), db: AsyncSession = Depends(get_db)):
        obj = await db.get(model, item_id)
        if obj is None or obj.owner_id != auth.owner_id:
            raise NotFound("Запись не найдена")
        return serialize(obj)

    @router.post("", status_code=201)
    async def create_item(body: Create, auth: Auth = Depends(get_auth), db: AsyncSession = Depends(get_db)):  # type: ignore[valid-type]
        data = coerce_numeric(model, body.model_dump(exclude_unset=True))
        if data.get("id"):
            existing = await db.get(model, data["id"])
            if existing is not None:  # idempotent create with a client id
                if existing.owner_id != auth.owner_id:
                    raise NotFound("Запись не найдена")
                return serialize(existing)
        else:
            data.pop("id", None)
        obj = model(owner_id=auth.owner_id, **data)
        db.add(obj)
        await db.flush()
        await run_hooks(db, auth.owner_id, obj, "create", data)
        await db.flush()
        await db.refresh(obj)
        return serialize(obj)

    @router.patch("/{item_id}")
    async def patch_item(item_id: uuid.UUID, body: Patch, auth: Auth = Depends(get_auth),  # type: ignore[valid-type]
                         db: AsyncSession = Depends(get_db)):
        obj = await db.get(model, item_id)
        if obj is None or obj.owner_id != auth.owner_id:
            raise NotFound("Запись не найдена")
        data = coerce_numeric(model, body.model_dump(exclude_unset=True))
        for k, v in data.items():
            setattr(obj, k, v)
        if hasattr(obj, "version"):
            obj.version += 1
        await run_hooks(db, auth.owner_id, obj, "update", data)
        await db.flush()
        await db.refresh(obj)
        return serialize(obj)

    @router.delete("/{item_id}")
    async def delete_item(item_id: uuid.UUID, auth: Auth = Depends(get_auth), db: AsyncSession = Depends(get_db)):
        obj = await db.get(model, item_id)
        if obj is None or obj.owner_id != auth.owner_id:
            raise NotFound("Запись не найдена")
        if soft_delete and has_deleted:
            obj.deleted_at = utcnow()
            obj.version += 1
        else:
            await db.delete(obj)
        await run_hooks(db, auth.owner_id, obj, "delete", {})
        return {"ok": True}

    return router
