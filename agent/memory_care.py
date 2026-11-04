"""Nightly care of long-term memory: working notes go stale after a month, near-duplicate facts are merged
by meaning (the exact-words dedupe misses «Владелец курит» vs «Владелец — курильщик»), and facts that look
like they contradict each other become one clarifying question. Plus the portrait's monthly diff."""

from __future__ import annotations

import logging
import uuid
from datetime import timedelta
from typing import Any

from sqlalchemy import select, text, update

from agent.initiative import deliver_message
from app.core.db import session_scope
from app.core.errors import ApiError
from app.core.timeutil import utcnow
from app.models import ClarifyingQuestion, Memory
from app.services.llm.client import call, call_json
from app.services.settings import get_setting, set_setting

log = logging.getLogger(__name__)

WORKING_STALE_DAYS = 30
MERGE_DISTANCE = 0.08      # cosine distance; 0.92 similarity and above is the same fact in other words
CONTRADICTION_BAND = (0.08, 0.30)
MERGE_TYPES = ("permanent", "behavioral", "working")
MAX_MERGES = 20
MAX_PAIRS_TO_JUDGE = 8

JUDGE_SCHEMA = {
    "type": "object",
    "properties": {"pairs": {"type": "array", "items": {
        "type": "object",
        "properties": {"ref": {"type": "integer"}, "contradiction": {"type": "boolean"},
                       "question": {"type": ["string", "null"]}},
        "required": ["ref", "contradiction", "question"], "additionalProperties": False}}},
    "required": ["pairs"], "additionalProperties": False,
}


async def stale_working(owner_id: uuid.UUID) -> int:
    async with session_scope() as db:
        res = await db.execute(update(Memory).where(
            Memory.owner_id == owner_id, Memory.type == "working", Memory.is_actual.is_(True),
            Memory.deleted_at.is_(None), Memory.status != "confirmed",
            Memory.created_at < utcnow() - timedelta(days=WORKING_STALE_DAYS)).values(is_actual=False))
        return int(res.rowcount or 0)


_PAIRS_SQL = """
    SELECT a.id AS a_id, b.id AS b_id, (ea.embedding <=> eb.embedding) AS dist
    FROM memories a
    JOIN memory_embeddings ea ON ea.memory_id = a.id
    JOIN memories b ON b.owner_id = a.owner_id AND b.type = a.type AND b.id <> a.id AND b.created_at > a.created_at
    JOIN memory_embeddings eb ON eb.memory_id = b.id
    WHERE a.owner_id = :owner AND a.deleted_at IS NULL AND b.deleted_at IS NULL
      AND a.is_actual AND b.is_actual AND a.status <> 'rejected' AND b.status <> 'rejected'
      AND a.type = ANY(:types) {category}
      AND (ea.embedding <=> eb.embedding) BETWEEN :lo AND :hi
    ORDER BY dist
    LIMIT :limit
"""


async def merge_duplicates(owner_id: uuid.UUID) -> int:
    merged = 0
    async with session_scope() as db:
        rows = (await db.execute(text(_PAIRS_SQL.format(category="")),
                                 {"owner": owner_id, "types": list(MERGE_TYPES), "lo": 0.0, "hi": MERGE_DISTANCE,
                                  "limit": MAX_MERGES})).all()
        gone: set[uuid.UUID] = set()
        for a_id, b_id, _dist in rows:
            if a_id in gone or b_id in gone:
                continue
            a, b = await db.get(Memory, a_id), await db.get(Memory, b_id)
            if a is None or b is None:
                continue
            # Keep the older record (its id is already cited elsewhere); it absorbs the newer one's evidence.
            evidence = list(a.evidence or [])
            for ev in b.evidence or []:
                if ev not in evidence:
                    evidence.append(ev)
            a.evidence = evidence[-30:]
            a.confidence = max(a.confidence, b.confidence)
            a.importance = max(a.importance, b.importance)
            if b.status == "confirmed":
                a.status = "confirmed"
            if len(b.content) > len(a.content) + 20 and b.status != "rejected":
                a.data = {**(a.data or {}), "merged_text": b.content}
            a.version += 1
            b.deleted_at = utcnow()
            b.version += 1
            gone.add(b_id)
            merged += 1
    return merged


async def find_contradictions(owner_id: uuid.UUID) -> int:
    """Pairs of permanent facts in the same category that are close but not identical: Haiku says whether
    they contradict; a contradiction becomes one clarifying question. Each pair is judged once."""
    async with session_scope() as db:
        rows = (await db.execute(text(_PAIRS_SQL.format(category="AND b.category = a.category")),
                                 {"owner": owner_id, "types": ["permanent"], "lo": CONTRADICTION_BAND[0],
                                  "hi": CONTRADICTION_BAND[1], "limit": 60})).all()
        pairs = []
        for a_id, b_id, _ in rows:
            a, b = await db.get(Memory, a_id), await db.get(Memory, b_id)
            if a is None or b is None or str(b_id) in (a.data or {}).get("judged_against", []):
                continue
            pairs.append((a, b))
            if len(pairs) >= MAX_PAIRS_TO_JUDGE:
                break
        if not pairs:
            return 0
        listing = "\n".join(f"[{i}] A: {a.content}\n    B: {b.content}" for i, (a, b) in enumerate(pairs))
        ids = [(str(a.id), str(b.id)) for a, b in pairs]
    try:
        parsed = await call_json(owner_id, "background", schema=JUDGE_SCHEMA, max_tokens=1500, priority=0,
                                 system="Ты проверяешь память личного ИИ-агента на противоречия. Для каждой пары фактов "
                                        "о владельце скажи, противоречат ли они друг другу по существу (не просто разные "
                                        "формулировки и не уточнение одного другим). Если да — задай владельцу один "
                                        "короткий вопрос на «ты», который снимет противоречие.",
                                 user=f"Пары:\n{listing}")
    except ApiError as exc:
        log.warning("contradiction check skipped: %s", exc.code)
        return 0
    created = 0
    async with session_scope() as db:
        for item in parsed.get("pairs", []):
            i = item.get("ref")
            if not isinstance(i, int) or i >= len(ids):
                continue
            a = await db.get(Memory, uuid.UUID(ids[i][0]))
            if a is not None:
                a.data = {**(a.data or {}), "judged_against": [*(a.data or {}).get("judged_against", []), ids[i][1]][-20:]}
            if item.get("contradiction") and item.get("question") and created < 2:
                db.add(ClarifyingQuestion(owner_id=owner_id, question=item["question"][:500], context="memory_care",
                                          memory_ids=list(ids[i]), status="open", priority=2))
                created += 1
    return created


async def nightly(owner_id: uuid.UUID) -> dict[str, Any]:
    out: dict[str, Any] = {}
    for name, fn in (("stale_working", stale_working), ("merged", merge_duplicates),
                     ("contradiction_questions", find_contradictions)):
        try:
            out[name] = await fn(owner_id)
        except Exception as exc:
            log.exception("memory care %s failed", name)
            out[name] = f"error: {type(exc).__name__}"
    return out


# ------------------------------------------------------------------ portrait history

PROFILE_HISTORY_KEEP = 12
PROFILE_DIFF_MIN_DAYS = 25


async def remember_profile_version(db, owner_id: uuid.UUID, text_: str) -> None:
    history = await get_setting(db, owner_id, "profile_history") or []
    if history and history[-1].get("text") == text_:
        return
    history = [*history, {"at": utcnow().isoformat(), "text": text_}][-PROFILE_HISTORY_KEEP:]
    await set_setting(db, owner_id, "profile_history", history, merge=False)


async def profile_diff(owner_id: uuid.UUID) -> dict[str, Any]:
    """Monthly: what changed in the owner's portrait since a version at least 25 days old."""
    async with session_scope() as db:
        history = await get_setting(db, owner_id, "profile_history") or []
        current = await get_setting(db, owner_id, "profile_summary")
    if not current or not history:
        return {"skip": "no history"}
    from datetime import datetime
    old = next((h for h in history if (utcnow() - datetime.fromisoformat(h["at"])).days >= PROFILE_DIFF_MIN_DAYS), None)
    if old is None or old["text"] == current:
        return {"skip": "nothing old enough or no change"}
    result = await call(owner_id, "background", max_tokens=1500, priority=0,
                        system="Ты — личный ИИ-агент владельца; пишешь ему коротко, на «ты», в своей манере.",
                        messages=[{"role": "user", "content": (
                            f"Портрет владельца месяц назад:\n{old['text']}\n\nПортрет сейчас:\n{current}\n\n"
                            "Напиши 3–6 предложений: что в нём изменилось за месяц (новые цели, люди, привычки, "
                            "настроение, что ушло). Без пересказа всего портрета, только изменения. Если изменения "
                            "косметические — так и скажи одной фразой.")}])
    async with session_scope() as db:
        await deliver_message(db, owner_id, result.text.strip(), trigger="Что изменилось за месяц", priority="low",
                              dedupe_key=f"profile_diff:{utcnow():%Y-%m}")
    return {"ok": True}
