"""Long-term memory: dedupe-aware writes, hybrid search (pgvector + Russian full-text + trigrams),
archive search over raw imported messages, and source deletion with derived-data cleanup."""

from __future__ import annotations

import hashlib
import re
import uuid
from datetime import datetime
from typing import Any

from sqlalchemy import delete, func, select, text, update
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.timeutil import utcnow
from app.models import Memory, MemorySource, Person
from app.services.embeddings import embed, to_pgvector
from app.services.queue import enqueue
from app.services.settings import get_setting

_WORD = re.compile(r"[\wё]+", re.IGNORECASE)


def dedupe_key_for(type_: str, content: str) -> str:
    words = " ".join(_WORD.findall(content.lower().replace("ё", "е")))
    return hashlib.sha1(f"{type_}|{words}".encode()).hexdigest()[:32]


async def upsert_memory(db: AsyncSession, owner_id: uuid.UUID, *, type: str, content: str,
                        category: str = "general", title: str | None = None, data: dict | None = None,
                        event_at: datetime | None = None, source_kind: str = "chat",
                        source_id: uuid.UUID | None = None, evidence: list | None = None,
                        confidence: float = 0.7, status: str = "unconfirmed", sensitivity: str = "normal",
                        importance: int = 3) -> tuple[Memory, bool]:
    content = content.strip()
    key = dedupe_key_for(type, content)
    existing = (await db.execute(
        select(Memory).where(Memory.owner_id == owner_id, Memory.dedupe_key == key, Memory.deleted_at.is_(None))
    )).scalars().first()
    if existing is not None:
        merged = list(existing.evidence or [])
        for ev in evidence or []:
            if ev not in merged:
                merged.append(ev)
        existing.evidence = merged[-30:]
        existing.confidence = max(existing.confidence, confidence)
        existing.importance = max(existing.importance, importance)
        existing.is_actual = True
        return existing, False
    mem = Memory(owner_id=owner_id, type=type, category=category, title=title, content=content,
                 data=data or {}, event_at=event_at, source_kind=source_kind, source_id=source_id,
                 evidence=evidence or [], confidence=max(0.0, min(confidence, 1.0)), status=status,
                 sensitivity=sensitivity, importance=max(1, min(importance, 5)), dedupe_key=key)
    db.add(mem)
    await db.flush()
    await enqueue("embed_memories", job_id="embed_memories", defer_seconds=10)
    return mem, True


async def allowed_types(db: AsyncSession, owner_id: uuid.UUID) -> tuple[list[str], bool]:
    privacy = await get_setting(db, owner_id, "privacy")
    access = privacy.get("memory_access", {})
    types = [t for t, ok in access.items() if ok]
    return types, bool(privacy.get("send_secret_memories", False))


async def search_memories(db: AsyncSession, owner_id: uuid.UUID, query: str, *, limit: int = 8,
                          types: list[str] | None = None, for_llm: bool = True,
                          category: str | None = None) -> list[tuple[Memory, float]]:
    query = (query or "").strip()
    if not query:
        return []
    include_secret = True
    if for_llm:
        allowed, include_secret = await allowed_types(db, owner_id)
        types = [t for t in (types or allowed) if t in allowed]
        if not types:
            return []
    vectors = await embed([query], "query")
    vec = to_pgvector(vectors[0]) if vectors else None
    sql = f"""
        WITH q AS (SELECT websearch_to_tsquery('russian', :q) AS tsq)
        SELECT m.id,
               ts_rank_cd(m.tsv, q.tsq) AS fts,
               similarity(m.content, :q) AS trgm,
               {"1 - (e.embedding <=> CAST(:vec AS vector))" if vec else "0"} AS vsim
        FROM memories m
        CROSS JOIN q
        LEFT JOIN memory_embeddings e ON e.memory_id = m.id
        WHERE m.owner_id = :owner AND m.deleted_at IS NULL AND m.status <> 'rejected' AND m.is_actual
          {"AND m.type = ANY(:types)" if types else ""}
          {"AND m.category = :category" if category else ""}
          {"" if include_secret else "AND m.sensitivity <> 'secret'"}
          AND (m.tsv @@ q.tsq OR similarity(m.content, :q) > 0.12
               {"OR (e.embedding <=> CAST(:vec AS vector)) < 0.55" if vec else ""})
        ORDER BY (0.55 * {"1 - (e.embedding <=> CAST(:vec AS vector))" if vec else "0"}
                  + 0.30 * LEAST(ts_rank_cd(m.tsv, q.tsq) * 10, 1)
                  + 0.15 * similarity(m.content, :q)
                  + 0.02 * m.importance) DESC NULLS LAST
        LIMIT :limit
    """
    params: dict[str, Any] = {"q": query, "owner": owner_id, "limit": limit}
    if vec:
        params["vec"] = vec
    if types:
        params["types"] = types
    if category:
        params["category"] = category
    rows = (await db.execute(text(sql), params)).all()
    if not rows:
        return []
    scores = {r.id: 0.55 * float(r.vsim or 0) + 0.3 * min(float(r.fts or 0) * 10, 1) + 0.15 * float(r.trgm or 0)
              for r in rows}
    # Usage counters are bumped with plain SQL (no ORM sync, no updated_at/server_seq churn) before
    # the rows are loaded, so the returned objects are fresh.
    await db.execute(text("UPDATE memories SET use_count = use_count + 1, last_used_at = now() WHERE id = ANY(:ids)"),
                     {"ids": list(scores)})
    mems = (await db.execute(select(Memory).where(Memory.id.in_(scores))
                             .execution_options(populate_existing=True))).scalars().all()
    return sorted(((m, scores[m.id]) for m in mems), key=lambda x: -x[1])


async def search_archive(db: AsyncSession, owner_id: uuid.UUID, query: str, limit: int = 10,
                         person: str | None = None) -> list[dict[str, Any]]:
    """Full-text search over raw imported chats and the app's own chat history."""
    params: dict[str, Any] = {"q": query, "owner": owner_id, "limit": limit}
    person_filter = ""
    if person:
        person_filter = "AND (im.author ILIKE :person OR ic.title ILIKE :person)"
        params["person"] = f"%{person}%"
    rows = (await db.execute(text(f"""
        WITH q AS (SELECT websearch_to_tsquery('russian', :q) AS tsq)
        SELECT 'import' AS kind, im.id::text AS id, im.sent_at AS at, im.author, ic.title AS chat,
               ts_headline('russian', im.text, q.tsq, 'MaxWords=40, MinWords=15') AS snippet,
               ts_rank_cd(im.tsv, q.tsq) AS rank, im.source_id::text AS source_id
        FROM import_messages im
        JOIN import_chats ic ON ic.id = im.chat_id
        JOIN memory_sources ms ON ms.id = im.source_id
        CROSS JOIN q
        WHERE im.owner_id = :owner AND im.tsv @@ q.tsq AND NOT im.excluded AND NOT ic.excluded
              AND NOT ms.excluded AND ms.allow_llm {person_filter}
        UNION ALL
        SELECT 'chat', m.id::text, m.created_at, m.role, c.title,
               ts_headline('russian', m.text, q.tsq, 'MaxWords=40, MinWords=15'),
               ts_rank_cd(m.tsv, q.tsq), NULL
        FROM messages m JOIN conversations c ON c.id = m.conversation_id CROSS JOIN q
        WHERE m.owner_id = :owner AND m.tsv @@ q.tsq AND m.deleted_at IS NULL
        ORDER BY rank DESC LIMIT :limit
    """), params)).all()
    return [{"kind": r.kind, "id": r.id, "at": r.at.isoformat() if r.at else None, "author": r.author,
             "chat": r.chat, "snippet": r.snippet, "source_id": r.source_id} for r in rows]


async def delete_source(db: AsyncSession, owner_id: uuid.UUID, source_id: uuid.UUID) -> dict[str, int]:
    """Remove an imported source with everything derived from it.

    Memories whose primary source is this one go via ON DELETE CASCADE (embeddings too). Memories
    from other sources that merely cite it lose that evidence; if nothing is left they are deleted.
    """
    sid = str(source_id)
    cited = (await db.execute(
        select(Memory).where(Memory.owner_id == owner_id,
                             Memory.evidence.contains([{"source_id": sid}]))
    )).scalars().all()
    pruned = removed = 0
    for mem in cited:
        if mem.source_id == source_id:
            continue
        rest = [e for e in mem.evidence or [] if e.get("source_id") != sid]
        if rest:
            mem.evidence = rest
            mem.confidence = max(0.3, mem.confidence - 0.15)
            pruned += 1
        else:
            await db.delete(mem)
            removed += 1
    derived = (await db.execute(
        select(func.count()).select_from(Memory).where(Memory.source_id == source_id))).scalar_one()
    await db.execute(delete(MemorySource).where(MemorySource.id == source_id, MemorySource.owner_id == owner_id))
    return {"derived_deleted": int(derived), "evidence_pruned": pruned, "orphans_deleted": removed}


async def find_person(db: AsyncSession, owner_id: uuid.UUID, name: str) -> Person | None:
    """A card by its name or one of its aliases, ignoring case. The list is short and kept by hand
    (the owner removes whoever doesn't matter), so a strict match beats creating near-duplicates."""
    key = name.strip().lower()
    if not key:
        return None
    for p in (await db.execute(select(Person).where(Person.owner_id == owner_id, Person.deleted_at.is_(None))
                               .order_by(Person.importance.desc()))).scalars():
        if p.name.strip().lower() == key or key in (str(a).strip().lower() for a in p.aliases or []):
            return p
    return None


def format_memories(items: list[tuple[Memory, float]] | list[Memory]) -> str:
    lines = []
    for item in items:
        mem = item[0] if isinstance(item, tuple) else item
        when = f", {mem.event_at:%d.%m.%Y}" if mem.event_at else ""
        mark = "" if mem.status == "confirmed" else f", уверенность {mem.confidence:.1f}"
        lines.append(f"- [{mem.type}/{mem.category}{when}{mark}] {mem.content} (id {mem.id})")
    return "\n".join(lines)
