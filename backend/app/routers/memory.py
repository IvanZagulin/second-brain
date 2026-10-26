from __future__ import annotations

import uuid
from typing import Any

from fastapi import APIRouter, Depends, Query
from pydantic import BaseModel, Field
from sqlalchemy import func, select, update
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.db import get_db
from app.core.deps import Auth, get_auth
from app.core.errors import ApiError, NotFound
from app.core.timeutil import utcnow
from app.models import (
    ClarifyingQuestion, ImportMessage, LifeEvent, Memory, MemorySource, Message, MoodEntry, Person,
)
from app.services.audit import audit
from app.services.crud import crud_router, serialize
from app.services.memory import delete_source, search_memories, upsert_memory

router = APIRouter(prefix="/api/v1/memories", tags=["memory"])


@router.get("")
async def list_memories(type: str | None = None, category: str | None = None, status: str | None = None,
                        q: str | None = None, limit: int = Query(50, le=500), offset: int = 0,
                        auth: Auth = Depends(get_auth), db: AsyncSession = Depends(get_db)):
    if q:
        found = await search_memories(db, auth.owner_id, q, limit=limit, types=[type] if type else None, for_llm=False,
                                      category=category)
        return {"items": [serialize(m) | {"score": round(s, 3)} for m, s in found]}
    stmt = select(Memory).where(Memory.owner_id == auth.owner_id, Memory.deleted_at.is_(None))
    if type:
        stmt = stmt.where(Memory.type == type)
    if category:
        stmt = stmt.where(Memory.category == category)
    if status:
        stmt = stmt.where(Memory.status == status)
    rows = (await db.execute(stmt.order_by(Memory.created_at.desc()).limit(limit).offset(offset))).scalars().all()
    return {"items": [serialize(m) for m in rows]}


@router.get("/stats")
async def stats(auth: Auth = Depends(get_auth), db: AsyncSession = Depends(get_db)):
    rows = (await db.execute(select(Memory.type, func.count()).where(Memory.owner_id == auth.owner_id,
                                                                     Memory.deleted_at.is_(None))
                             .group_by(Memory.type))).all()
    cats = (await db.execute(select(Memory.category, func.count()).where(Memory.owner_id == auth.owner_id,
                                                                         Memory.deleted_at.is_(None))
                             .group_by(Memory.category).order_by(func.count().desc()).limit(30))).all()
    people = (await db.execute(select(func.count()).select_from(Person).where(Person.owner_id == auth.owner_id,
                                                                              Person.deleted_at.is_(None)))).scalar_one()
    return {"by_type": dict(rows), "by_category": dict(cats), "people": people}


class MemoryIn(BaseModel):
    type: str = "permanent"
    content: str = Field(min_length=2, max_length=4000)
    category: str = "general"
    importance: int = Field(3, ge=1, le=5)
    sensitivity: str = "normal"


@router.post("", status_code=201)
async def create_memory(body: MemoryIn, auth: Auth = Depends(get_auth), db: AsyncSession = Depends(get_db)):
    mem, _ = await upsert_memory(db, auth.owner_id, type=body.type, content=body.content, category=body.category,
                                 importance=body.importance, sensitivity=body.sensitivity, source_kind="manual",
                                 status="confirmed", confidence=1.0, evidence=[{"kind": "manual"}])
    return serialize(mem)


class MemoryPatch(BaseModel):
    content: str | None = None
    category: str | None = None
    type: str | None = None
    status: str | None = None
    is_actual: bool | None = None
    importance: int | None = Field(None, ge=1, le=5)
    sensitivity: str | None = None


async def _mem(db: AsyncSession, auth: Auth, mem_id: uuid.UUID) -> Memory:
    m = await db.get(Memory, mem_id)
    if m is None or m.owner_id != auth.owner_id or m.deleted_at is not None:
        raise NotFound("Воспоминание не найдено")
    return m


@router.patch("/{mem_id}")
async def patch_memory(mem_id: uuid.UUID, body: MemoryPatch, auth: Auth = Depends(get_auth),
                       db: AsyncSession = Depends(get_db)):
    m = await _mem(db, auth, mem_id)
    for k, v in body.model_dump(exclude_none=True).items():
        setattr(m, k, v)
    if body.content:
        m.status = "confirmed"
        from app.models import MemoryEmbedding
        emb = await db.get(MemoryEmbedding, m.id)
        if emb is not None:
            await db.delete(emb)  # stale vector; the worker re-embeds the new text
    m.version += 1
    return serialize(m)


@router.delete("/{mem_id}")
async def delete_memory(mem_id: uuid.UUID, auth: Auth = Depends(get_auth), db: AsyncSession = Depends(get_db)):
    m = await _mem(db, auth, mem_id)
    m.deleted_at = utcnow()
    m.version += 1
    from app.models import MemoryEmbedding
    emb = await db.get(MemoryEmbedding, m.id)
    if emb is not None:
        await db.delete(emb)
    await audit(db, auth.owner_id, auth.actor, "delete_memory", "memories", m.id)
    return {"ok": True}


@router.get("/{mem_id}/evidence")
async def evidence(mem_id: uuid.UUID, auth: Auth = Depends(get_auth), db: AsyncSession = Depends(get_db)):
    """Resolve what a memory is based on: chat messages, archived messages, notes, reasoning."""
    m = await _mem(db, auth, mem_id)
    out: list[dict[str, Any]] = []
    for ev in m.evidence or []:
        kind = ev.get("kind")
        item: dict[str, Any] = {"kind": kind, "excerpt": ev.get("excerpt")}
        try:
            if kind == "message":
                msg = await db.get(Message, uuid.UUID(ev["id"]))
                if msg and msg.owner_id == auth.owner_id:
                    item.update(text=msg.text[:1500], at=msg.created_at.isoformat(), role=msg.role,
                                conversation_id=str(msg.conversation_id))
            elif kind == "import_message":
                im = await db.get(ImportMessage, int(ev["id"]))
                if im and im.owner_id == auth.owner_id:
                    item.update(text=im.text[:1500], at=im.sent_at.isoformat() if im.sent_at else None, author=im.author)
            elif kind in ("conversation", "import_chat", "note"):
                item["id"] = ev.get("id")
        except (ValueError, KeyError):
            pass
        out.append(item)
    source = await db.get(MemorySource, m.source_id) if m.source_id else None
    return {"memory": serialize(m), "evidence": out, "source": serialize(source) if source else None}


@router.delete("/by-type/{mem_type}")
async def delete_type(mem_type: str, confirm: str = Query(...), auth: Auth = Depends(get_auth),
                      db: AsyncSession = Depends(get_db)):
    if confirm != "УДАЛИТЬ":
        raise ApiError("Для удаления категории передайте confirm=УДАЛИТЬ", code="confirm_required")
    result = await db.execute(update(Memory).where(Memory.owner_id == auth.owner_id, Memory.type == mem_type,
                                                   Memory.deleted_at.is_(None))
                              .values(deleted_at=utcnow(), version=Memory.version + 1))
    await audit(db, auth.owner_id, auth.actor, "delete_memory_type", "memories", mem_type)
    return {"deleted": result.rowcount}


# ---------------------------------------------------------------- sources

@router.get("/sources/all")
async def sources(auth: Auth = Depends(get_auth), db: AsyncSession = Depends(get_db)):
    rows = (await db.execute(select(MemorySource, func.count(Memory.id))
                             .outerjoin(Memory, (Memory.source_id == MemorySource.id) & Memory.deleted_at.is_(None))
                             .where(MemorySource.owner_id == auth.owner_id).group_by(MemorySource.id)
                             .order_by(MemorySource.created_at.desc()))).all()
    return {"items": [serialize(s) | {"memories": n} for s, n in rows]}


class SourcePatch(BaseModel):
    excluded: bool | None = None
    allow_llm: bool | None = None


@router.patch("/sources/{source_id}")
async def patch_source(source_id: uuid.UUID, body: SourcePatch, auth: Auth = Depends(get_auth),
                       db: AsyncSession = Depends(get_db)):
    s = await db.get(MemorySource, source_id)
    if s is None or s.owner_id != auth.owner_id:
        raise NotFound("Источник не найден")
    for k, v in body.model_dump(exclude_none=True).items():
        setattr(s, k, v)
    if body.excluded:  # excluded sources stop feeding search right away
        await db.execute(update(Memory).where(Memory.source_id == source_id).values(is_actual=False,
                                                                                    version=Memory.version + 1))
    elif body.excluded is False:
        await db.execute(update(Memory).where(Memory.source_id == source_id).values(is_actual=True,
                                                                                    version=Memory.version + 1))
    return serialize(s)


@router.delete("/sources/{source_id}")
async def remove_source(source_id: uuid.UUID, auth: Auth = Depends(get_auth), db: AsyncSession = Depends(get_db)):
    s = await db.get(MemorySource, source_id)
    if s is None or s.owner_id != auth.owner_id:
        raise NotFound("Источник не найден")
    result = await delete_source(db, auth.owner_id, source_id)
    await audit(db, auth.owner_id, auth.actor, "delete_source", "memory_sources", source_id, result)
    return result


# ---------------------------------------------------------------- clarifying questions

@router.get("/questions/open")
async def questions(auth: Auth = Depends(get_auth), db: AsyncSession = Depends(get_db)):
    rows = (await db.execute(select(ClarifyingQuestion).where(ClarifyingQuestion.owner_id == auth.owner_id,
                                                              ClarifyingQuestion.status == "open")
                             .order_by(ClarifyingQuestion.priority.desc(), ClarifyingQuestion.created_at))).scalars().all()
    return {"items": [serialize(q) for q in rows]}


class AnswerIn(BaseModel):
    answer: str = Field(min_length=1, max_length=4000)


@router.post("/questions/{qid}/answer")
async def answer(qid: uuid.UUID, body: AnswerIn, auth: Auth = Depends(get_auth), db: AsyncSession = Depends(get_db)):
    q = await db.get(ClarifyingQuestion, qid)
    if q is None or q.owner_id != auth.owner_id:
        raise NotFound("Вопрос не найден")
    q.status, q.answer, q.answered_at = "answered", body.answer, utcnow()
    q.version += 1
    mem, _ = await upsert_memory(db, auth.owner_id, type="permanent", category="clarified",
                                 content=f"{q.question.rstrip('?')}? — {body.answer}", source_kind="manual",
                                 status="confirmed", confidence=1.0, importance=4,
                                 evidence=[{"kind": "question", "id": str(q.id)}])
    return {"ok": True, "memory_id": str(mem.id)}


@router.post("/questions/{qid}/dismiss")
async def dismiss(qid: uuid.UUID, auth: Auth = Depends(get_auth), db: AsyncSession = Depends(get_db)):
    q = await db.get(ClarifyingQuestion, qid)
    if q is None or q.owner_id != auth.owner_id:
        raise NotFound("Вопрос не найден")
    q.status = "dismissed"
    q.version += 1
    return {"ok": True}


people_router = crud_router(Person, "/api/v1/people", "memory", order_by="updated_at")
events_router = crud_router(LifeEvent, "/api/v1/life-events", "memory", order_by="occurred_at")
mood_router = crud_router(MoodEntry, "/api/v1/mood", "memory", order_by="occurred_at")
