"""Import stage 1 (free): parse the uploaded archive into import_chats / import_messages, dedupe on
(source, message id), collect participants and estimate what the paid analysis would cost.
Stage 2 (paid, after the owner confirms) lives in agent.archive."""

from __future__ import annotations

import logging
import uuid
from collections import Counter
from decimal import Decimal
from pathlib import Path
from typing import Any

from sqlalchemy import func, select
from sqlalchemy.dialects.postgresql import insert

from app.core.db import session_scope
from app.core.timeutil import utcnow
from app.models import ImportChat, ImportJob, ImportMessage, MemorySource
from app.services.imports import adapters
from app.services.llm.pricing import price_for

log = logging.getLogger(__name__)

BATCH = 1000


async def parse_import(owner_id: uuid.UUID, job_id: uuid.UUID) -> dict[str, Any]:
    async with session_scope() as db:
        job = await db.get(ImportJob, job_id)
        job.status = "parsing"
        path = Path(job.file_path)
        kind = job.kind
    try:
        if kind in ("auto", "unknown"):
            kind = adapters.detect(path)
        info: dict[str, Any] = {}
        if kind == "telegram":
            info, chats = adapters.telegram_chats(path)
        elif kind == "chatgpt":
            chats = adapters.chatgpt_chats(path)
        elif kind == "claude":
            chats = adapters.claude_chats(path)
        elif kind == "gemini":
            chats = adapters.gemini_chats(path)
        elif kind == "telegram_html":
            chats = adapters.telegram_html_chats(path)
        elif kind == "claude_memory":
            chats = adapters.claude_memory_chats(path)
        else:
            raise adapters.UnknownFormat(f"Неизвестный тип импорта {kind}")

        totals = {"chats": 0, "messages": 0, "new_messages": 0, "chars": 0}
        chat_stats = []
        for chat in chats:
            stats = await _store_chat(owner_id, job_id, chat, info.get("self_id"))
            totals["chats"] += 1
            totals["messages"] += stats["messages"]
            totals["new_messages"] += stats["new"]
            totals["chars"] += stats["chars"]
            chat_stats.append(stats)
            async with session_scope() as db:
                j = await db.get(ImportJob, job_id)
                j.stats = {**(j.stats or {}), "progress_chats": totals["chats"]}
        # AI chats are read grouped by time, so together they add one period of overhead, not one per chat.
        units = sum(1 for c in chat_stats if not c["ai"]) + (1 if any(c["ai"] for c in chat_stats) else 0)
        estimates = {t: float(estimate_cost(totals["chars"], units, totals["messages"], t)) for t in READ_MODELS}
        async with session_scope() as db:
            j = await db.get(ImportJob, job_id)
            j.kind = kind
            j.status = "parsed"
            j.stats = {**totals, "self_id": info.get("self_id"), "estimate_usd": estimates["balanced"], "estimates": estimates,
                       "chats_detail": sorted(chat_stats, key=lambda c: -c["messages"])[:300]}
            j.progress = 0.0
        return {"ok": True, "kind": kind, **totals}
    except Exception as exc:
        log.exception("import parse failed")
        async with session_scope() as db:
            j = await db.get(ImportJob, job_id)
            j.status, j.error = "failed", str(exc)[:1000]
        return {"ok": False, "error": str(exc)}


# Mirrors agent.archive: which model reads each period, how big a period is; the portrait is always Opus.
READ_MODELS = {"economy": "claude-haiku-5-5", "balanced": "claude-sonnet-5-5", "max": "claude-opus-5-5"}
PERIOD_TOKENS = {"economy": 80_000, "balanced": 110_000, "max": 110_000}
PORTRAIT_MODEL = "claude-opus-5-5"


def estimate_cost(chars: int, chats: int, messages: int = 0, tier: str = "balanced") -> Decimal:
    """Reading: text plus the compact per-line overhead, a few thousand tokens of context per period and the
    period's facts + chronicle (+ thinking) as output. Portrait: every chronicle in, one long answer out."""
    tokens = int((chars + messages * 4) / 2.5)
    periods = max(tokens // PERIOD_TOKENS[tier] + chats, 1)
    read = price_for(READ_MODELS[tier], PERIOD_TOKENS[tier])
    portrait = price_for(PORTRAIT_MODEL, 200_000)
    cost = (Decimal(tokens + periods * 3500) * read.input + Decimal(periods * 7000) * read.output
            + Decimal(periods * 1800 + 5000) * portrait.input + Decimal(30_000) * portrait.output) / 1_000_000
    return cost.quantize(Decimal("0.01"))


async def _store_chat(owner_id: uuid.UUID, job_id: uuid.UUID, chat: adapters.ParsedChat,
                      self_id: str | None) -> dict[str, Any]:
    async with session_scope() as db:
        source = (await db.execute(select(MemorySource).where(
            MemorySource.owner_id == owner_id, MemorySource.kind == chat.kind,
            MemorySource.external_id == chat.external_id))).scalar_one_or_none()
        if source is None:
            source = MemorySource(owner_id=owner_id, kind=chat.kind, title=chat.title, external_id=chat.external_id,
                                  import_job_id=job_id, meta=chat.meta)
            db.add(source)
            await db.flush()
        ic = (await db.execute(select(ImportChat).where(ImportChat.source_id == source.id))).scalar_one_or_none()
        if ic is None:
            ic = ImportChat(owner_id=owner_id, import_job_id=job_id, source_id=source.id, title=chat.title,
                            chat_type=chat.chat_type)
            db.add(ic)
            await db.flush()
        else:
            ic.import_job_id = job_id
        source_id, chat_id = source.id, ic.id

    authors: Counter = Counter()
    names: dict[str, str] = {}
    total = new = chars = 0
    batch: list[dict[str, Any]] = []

    async def flush() -> int:
        if not batch:
            return 0
        async with session_scope() as db:
            stmt = insert(ImportMessage).values(batch).on_conflict_do_nothing(
                index_elements=["source_id", "external_id"]).returning(ImportMessage.id)
            inserted = len((await db.execute(stmt)).all())
        batch.clear()
        return inserted

    for m in chat.messages:
        total += 1
        # As the analysis will see it: assistant replies are read briefly (agent.archive.ASSISTANT_REPLY_CHARS).
        chars += min(len(m.text), 600) if m.author_id in ("assistant", "model") else min(len(m.text), 4000)
        if m.author_id:
            authors[m.author_id] += 1
            names[m.author_id] = m.author or m.author_id
        is_self = m.is_self if m.is_self is not None else bool(self_id and m.author_id == self_id)
        batch.append({"owner_id": owner_id, "chat_id": chat_id, "source_id": source_id, "external_id": m.external_id,
                      "author": (m.author or "")[:200] or None, "author_id": m.author_id, "is_self": is_self,
                      "sent_at": m.sent_at, "text": m.text[:20000], "reply_to": m.reply_to})
        if len(batch) >= BATCH:
            new += await flush()
    new += await flush()

    async with session_scope() as db:
        ic = await db.get(ImportChat, chat_id)
        agg = (await db.execute(select(func.count(), func.min(ImportMessage.sent_at), func.max(ImportMessage.sent_at))
                                .where(ImportMessage.chat_id == chat_id))).one()
        ic.message_count, ic.first_at, ic.last_at = agg[0], agg[1], agg[2]
        ic.participants = [{"id": a, "name": names.get(a), "messages": n} for a, n in authors.most_common(20)]
    return {"chat_id": str(chat_id), "source_id": str(source_id), "title": chat.title, "ai": chat.chat_type == "ai",
            "messages": total, "new": new,
            "chars": chars, "participants": [{"id": a, "name": names.get(a), "messages": n}
                                             for a, n in authors.most_common(5)]}


async def mark_self(owner_id: uuid.UUID, job_id: uuid.UUID, self_ids: list[str]) -> int:
    """Telegram single-chat exports don't say which participant is the owner; the owner picks."""
    from sqlalchemy import update

    async with session_scope() as db:
        chat_ids = (await db.execute(select(ImportChat.id).where(ImportChat.import_job_id == job_id,
                                                                 ImportChat.owner_id == owner_id))).scalars().all()
        if not chat_ids:
            return 0
        await db.execute(update(ImportMessage).where(ImportMessage.chat_id.in_(chat_ids))
                         .values(is_self=ImportMessage.author_id.in_(self_ids)))
        return len(chat_ids)
