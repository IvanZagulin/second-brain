"""Proactive messages. Something happened → check limits (enabled, pause, quiet hours, per-day cap,
minimum interval, dedupe) → Claude Haiku decides whether it is worth writing and drafts the text →
message lands in the «Агент» conversation and a push goes out."""

from __future__ import annotations

import logging
import uuid
from datetime import datetime, timedelta
from typing import Any

from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession

from agent.chat import system_prompt
from agent.context import recent_digest
from app.core.db import session_scope
from app.core.errors import ApiError
from app.core.timeutil import in_quiet_hours, local_now, utcnow
from app.models import AgentJob, Conversation, Message, Notification, Transaction, User
from app.services.llm.client import call_json
from app.services.memory import format_memories, search_memories
from app.services.notifications import create_notification
from app.services.settings import get_all_settings

log = logging.getLogger(__name__)

DECISION_SCHEMA = {
    "type": "object",
    "properties": {
        "send": {"type": "boolean"},
        "text": {"type": "string"},
        "priority": {"type": "string", "enum": ["low", "normal", "high"]},
        "followup_in_hours": {"type": ["number", "null"]},
        "followup_goal": {"type": ["string", "null"]},
        "reason": {"type": "string"},
    },
    "required": ["send", "text", "priority", "followup_in_hours", "followup_goal", "reason"],
    "additionalProperties": False,
}


async def agent_conversation(db: AsyncSession, owner_id: uuid.UUID) -> Conversation:
    conv = (await db.execute(select(Conversation).where(Conversation.owner_id == owner_id, Conversation.kind == "agent",
                                                        Conversation.deleted_at.is_(None)))).scalars().first()
    if conv is None:
        conv = Conversation(owner_id=owner_id, title="Агент", kind="agent", mode="auto", pinned=True)
        db.add(conv)
        await db.flush()
    return conv


async def deliver_message(db: AsyncSession, owner_id: uuid.UUID, text: str, *, trigger: str,
                          priority: str = "normal", dedupe_key: str | None = None) -> uuid.UUID:
    conv = await agent_conversation(db, owner_id)
    now = utcnow()
    # The trigger is stored as a user-role event so the thread stays a valid user/assistant sequence.
    db.add(Message(owner_id=owner_id, conversation_id=conv.id, role="trigger", text=f"[Событие: {trigger}]",
                   blocks=[{"type": "text", "text": f"<event>{trigger}</event>"}], source="agent", created_at=now))
    msg = Message(owner_id=owner_id, conversation_id=conv.id, role="assistant", text=text,
                  blocks=[{"type": "text", "text": text}], source="agent", created_at=now + timedelta(microseconds=10))
    db.add(msg)
    conv.last_message_at = now
    conv.version += 1
    await db.flush()
    privacy = (await get_all_settings(db, owner_id))["privacy"]
    await create_notification(db, owner_id, title="Второй мозг", body=text, category="agent", priority=priority,
                              conversation_id=conv.id, message_id=msg.id, dedupe_key=dedupe_key,
                              sensitive=bool(privacy.get("hide_sensitive_in_notifications")))
    return msg.id


async def _limits_ok(db: AsyncSession, owner_id: uuid.UUID, priority: int) -> tuple[bool, str]:
    settings = await get_all_settings(db, owner_id)
    ini = settings["initiative"]
    if not ini.get("enabled", True):
        return False, "initiative disabled"
    paused = ini.get("paused_until")
    if paused:
        try:
            if datetime.fromisoformat(paused) > utcnow():
                return False, "paused"
        except ValueError:
            pass
    user = await db.get(User, owner_id)
    now_local = local_now(user.timezone if user else None)
    sch = settings["schedule"]
    if priority < 3 and in_quiet_hours(now_local, sch.get("sleep_start", "23:30"), sch.get("sleep_end", "07:00")):
        return False, "quiet hours"
    day_start = now_local.replace(hour=0, minute=0, second=0, microsecond=0)
    sent_today = (await db.execute(select(func.count()).select_from(Notification).where(
        Notification.owner_id == owner_id, Notification.category == "agent",
        Notification.created_at >= day_start))).scalar_one()
    if priority < 2 and sent_today >= int(ini.get("max_per_day", 6)):
        return False, "daily cap"
    last = (await db.execute(select(func.max(Notification.created_at)).where(
        Notification.owner_id == owner_id, Notification.category == "agent"))).scalar_one()
    if priority < 2 and last and utcnow() - last < timedelta(minutes=int(ini.get("min_interval_minutes", 90))):
        return False, "min interval"
    return True, "ok"


async def consider(owner_id: uuid.UUID, trigger: str, facts: str, *, priority: int = 1,
                   dedupe_key: str | None = None, defer_if_quiet: bool = True,
                   validate: dict[str, Any] | None = None) -> dict[str, Any]:
    """Maybe write to the owner first. ``priority``: 0 low … 3 critical (critical ignores quiet hours).

    ``validate`` describes what must still hold if the message is deferred to the morning (see ``stale_reason``).
    """
    async with session_scope() as db:
        if dedupe_key:
            seen = (await db.execute(select(Notification.id).where(Notification.owner_id == owner_id,
                                                                   Notification.dedupe_key == dedupe_key))).scalar_one_or_none()
            if seen:
                return {"sent": False, "reason": "duplicate"}
        ok, why = await _limits_ok(db, owner_id, priority)
        if not ok:
            if why == "quiet hours" and defer_if_quiet and priority >= 1:
                settings = await get_all_settings(db, owner_id)
                user = await db.get(User, owner_id)
                wake = settings["schedule"].get("sleep_end", "07:00")
                now_local = local_now(user.timezone if user else None)
                h, m = (int(x) for x in wake.split(":")[:2])
                run_local = now_local.replace(hour=h, minute=m, second=0, microsecond=0)
                if run_local <= now_local:
                    run_local += timedelta(days=1)
                db.add(AgentJob(owner_id=owner_id, kind="initiative", goal=trigger, reason="отложено до утра",
                                next_run_at=run_local, priority=priority,
                                payload={"trigger": trigger, "facts": facts, "dedupe_key": dedupe_key,
                                         "validate": validate or {}}))
            return {"sent": False, "reason": why}
        system = await system_prompt(db, owner_id)
        mems = await search_memories(db, owner_id, f"{trigger} {facts}"[:500], limit=6)
        conv = await agent_conversation(db, owner_id)
        recent = (await db.execute(select(Message).where(Message.conversation_id == conv.id,
                                                         Message.role.in_(("assistant", "user")))
                                   .order_by(Message.created_at.desc()).limit(6))).scalars().all()
        user = await db.get(User, owner_id)
        now_local = local_now(user.timezone if user else None)
        digest = await recent_digest(db, owner_id, now_local, since=utcnow() - timedelta(hours=20), exclude=conv.id)
    recent_s = "\n".join(f"{'Ты' if m.role == 'assistant' else 'Владелец'} ({m.created_at:%d.%m %H:%M}): {m.text[:400]}"
                         for m in reversed(recent)) or "—"
    prompt = (
        f"Сейчас {now_local:%A %d.%m %H:%M}. Произошло: {trigger}.\nФакты:\n{facts}\n\n"
        f"Относящиеся воспоминания:\n{format_memories(mems) or '—'}\n\nПоследние сообщения в твоём канале:\n{recent_s}\n\n"
        f"Остальное за сутки (другие диалоги, тренировка, свежая память):\n{digest or '—'}\n\n"
        "Не спрашивай о том, что владелец уже рассказал в любом из диалогов: опирайся на это. "
        "Реши, стоит ли сейчас написать владельцу первым. Пиши только если есть реальный повод: предупредить, "
        "напомнить об обещании, прокомментировать важное, задать нужный вопрос. Не повторяй то, что уже писал. "
        "Если пишешь — коротко (1–4 предложения), в своей манере, с конкретикой. Если нужна проверка позже — "
        "укажи followup_in_hours и followup_goal."
    )
    try:
        decision = await call_json(owner_id, "background", system=system, user=prompt, schema=DECISION_SCHEMA,
                                   max_tokens=2000, priority=priority)
    except ApiError as exc:
        return {"sent": False, "reason": exc.code}
    if not decision.get("send") or not decision.get("text", "").strip():
        return {"sent": False, "reason": decision.get("reason", "model declined")}
    async with session_scope() as db:
        msg_id = await deliver_message(db, owner_id, decision["text"].strip(), trigger=trigger,
                                       priority=decision.get("priority", "normal"), dedupe_key=dedupe_key)
        if decision.get("followup_in_hours") and decision.get("followup_goal"):
            from agent.tools import DEFAULT_JOB_TOOLS
            db.add(AgentJob(owner_id=owner_id, kind="followup", goal=decision["followup_goal"], reason=trigger,
                            next_run_at=utcnow() + timedelta(hours=float(decision["followup_in_hours"])),
                            priority=1, created_by="agent", tools=DEFAULT_JOB_TOOLS))
    return {"sent": True, "message_id": str(msg_id)}


async def stale_reason(owner_id: uuid.UUID, validate: dict[str, Any]) -> str | None:
    """Why a message deferred overnight no longer applies (None = still relevant).

    ``transaction_id``: the transaction must still count (not deleted, excluded or a duplicate).
    ``overspent_date``: today must still be that date and still be overspent.
    """
    from app.services.finance.engine import finance_summary

    async with session_scope() as db:
        tx_id = validate.get("transaction_id")
        if tx_id:
            tx = await db.get(Transaction, uuid.UUID(tx_id))
            if tx is None or tx.deleted_at is not None or tx.status in ("excluded", "possible_duplicate"):
                return "transaction no longer counts"
        day = validate.get("overspent_date")
        if day:
            summary = await finance_summary(db, owner_id)
            if str(summary["date"]) != day:
                return "overspend was for another day"
            if not summary["overspent_today"]:
                return "no longer overspent"
    return None


async def run_deferred(owner_id: uuid.UUID, job: dict[str, Any]) -> dict[str, Any]:
    """A message deferred by quiet hours: re-check that it still applies, then decide again."""
    payload = job.get("payload") or {}
    why = await stale_reason(owner_id, payload.get("validate") or {})
    if why:
        log.info("deferred initiative dropped: %s", why)
        return {"sent": False, "reason": f"stale: {why}"}
    return await consider(owner_id, payload.get("trigger", job["goal"]), payload.get("facts", ""),
                          priority=max(job.get("priority", 1), 2), defer_if_quiet=False,
                          dedupe_key=payload.get("dedupe_key"))

