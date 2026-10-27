"""Per-turn <context> block: only what this message plausibly needs (no bank history for a
relationship question, no private chats for a budget question). Deeper data comes via tools."""

from __future__ import annotations

import re
import uuid
from datetime import datetime, timedelta

from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession

from agent.persona import MODE_LABELS
from app.core.money import fmt_rub
from app.core.timeutil import RU_WEEKDAYS, local_now, parse_hhmm, ru_date, utcnow
from app.models import AgentAction, Conversation, Memory, Message, Task, User, Workout, WorkoutPlan
from app.services.finance.engine import finance_summary
from app.services.memory import format_memories, search_memories
from app.services.settings import get_all_settings

FINANCE_WORDS = re.compile(r"деньг|бюджет|трат|потрат|купи|покупк|зарплат|долг|займ|кредит|налог|ип\b|счёт|счет|"
                           r"карт|наличн|руб|₽|подписк|накоп|копл|цел|донат|машин|отпуск|сколько у меня", re.I)
FITNESS_WORDS = re.compile(r"трен|зал|жим|присед|тяга|подход|повтор|вес|кг|мышц|спин|грудь|ног|бицепс|трицепс|"
                           r"плеч|кардио|похуд|жир|питан|калор|белок|сон|устал", re.I)
TASK_WORDS = re.compile(r"задач|сделать|сделаю|обещ|план|дедлайн|напомни|завтра|неделе|успел|прокраст|откладыв", re.I)


def _day_phase(settings: dict, now) -> str:
    sch = settings["schedule"]
    t = now.time()
    if t < parse_hhmm(sch.get("wake"), "07:00"):
        return "ночь"
    if t < parse_hhmm(sch.get("work_start"), "09:00"):
        return "утро перед работой"
    if t < parse_hhmm(sch.get("work_end"), "18:00"):
        return "рабочее время" if now.weekday() < 5 else "день"
    if t < parse_hhmm(sch.get("sleep_start"), "23:30"):
        return "вечер"
    return "поздний вечер, время сна"


async def recent_digest(db: AsyncSession, owner_id: uuid.UUID, now: datetime, *, since: datetime,
                        exclude: uuid.UUID | None = None, with_day: bool = True) -> str | None:
    """What the owner said in other conversations since ``since`` and, with ``with_day``, what today already
    holds. Conversations don't see each other otherwise: the evening check-in asked how the day went right after
    the owner had written in another chat that he was ill and skipped the gym."""
    q = (select(Message.created_at, Message.text, Conversation.title, Conversation.kind)
         .join(Conversation, Conversation.id == Message.conversation_id)
         .where(Message.owner_id == owner_id, Message.role == "user", Message.created_at >= since,
                Conversation.deleted_at.is_(None)))
    if exclude is not None:
        q = q.where(Message.conversation_id != exclude)
    rows = (await db.execute(q.order_by(Message.created_at.desc()).limit(12))).all()
    parts = []
    if rows:
        parts.append("Что владелец писал в других диалогах:\n" + "\n".join(
            f"- {r.created_at.astimezone(now.tzinfo):%d.%m %H:%M}, «{'Агент' if r.kind == 'agent' else r.title}»: "
            f"{r.text[:300]}" for r in reversed(rows)))
    if with_day:
        start = now.replace(hour=0, minute=0, second=0, microsecond=0)
        w = (await db.execute(select(Workout).where(
            Workout.owner_id == owner_id, Workout.deleted_at.is_(None), Workout.started_at >= start,
            Workout.status.in_(("done", "skipped"))).order_by(Workout.started_at.desc()).limit(1))).scalar_one_or_none()
        if w is not None:
            parts.append("Тренировка сегодня: " + ("сделана" if w.status == "done" else "пропущена")
                         + (f" ({w.skip_reason})" if w.skip_reason else "") + (f"; {w.notes}" if w.notes else "") + ".")
        fresh = (await db.execute(select(Memory).where(
            Memory.owner_id == owner_id, Memory.deleted_at.is_(None), Memory.type == "episodic",
            Memory.source_kind != "import", Memory.created_at >= utcnow() - timedelta(hours=36))
            .order_by(Memory.created_at.desc()).limit(6))).scalars().all()
        if fresh:
            parts.append("Свежее в памяти:\n" + format_memories(fresh))
    return "\n".join(parts) or None


async def build_context(db: AsyncSession, owner_id: uuid.UUID, conversation: Conversation, user_text: str,
                        *, first_in_segment: bool, mode: str) -> str:
    user = await db.get(User, owner_id)
    settings = await get_all_settings(db, owner_id)
    now = local_now(user.timezone if user else None)
    fit = settings["fitness"]
    training_days = fit.get("training_days") or []
    lines = [
        f"Сейчас: {RU_WEEKDAYS[now.weekday()]}, {ru_date(now)}, {now:%H:%M} ({user.timezone if user else 'MSK'}). "
        f"Фаза дня: {_day_phase(settings, now)}."
        + (" Сегодня день тренировки." if now.isoweekday() in training_days else ""),
        f"Режим: {MODE_LABELS.get(mode, mode)}.",
    ]
    if first_in_segment:
        profile = settings.get("profile_summary")
        if profile:
            lines.append(f"Кто владелец (сводка памяти):\n{profile}")
        if conversation.summary:
            lines.append(f"Краткое содержание предыдущей части этого разговора:\n{conversation.summary}")

    mems = await search_memories(db, owner_id, user_text, limit=6) if user_text.strip() else []
    if mems:
        lines.append("Возможно относящиеся воспоминания:\n" + format_memories(mems))

    # Each turn's context stays in the history, so later turns carry only what is new since the last one here.
    last_here = (await db.execute(select(func.max(Message.created_at)).where(
        Message.conversation_id == conversation.id))).scalar_one_or_none()
    since = utcnow() - timedelta(hours=20)
    if not first_in_segment and last_here is not None:
        since = max(since, last_here)
    digest = await recent_digest(db, owner_id, now, since=since, exclude=conversation.id, with_day=first_in_segment)
    if digest:
        lines.append(digest)

    want_finance = mode == "finance" or bool(FINANCE_WORDS.search(user_text))
    want_fitness = mode == "trainer" or bool(FITNESS_WORDS.search(user_text))
    want_tasks = mode == "coach" or first_in_segment or bool(TASK_WORDS.search(user_text))

    if want_finance:
        try:
            s = await finance_summary(db, owner_id)
            est = " (оценка: остатки не подтверждены банком)" if s["estimated"] else ""
            obligations = "; ".join(f"{o['title']} {o['due']} — {fmt_rub(o['amount_minor'])}"
                                    for o in s["obligations_until_income"][:4]) or "нет"
            lines.append(
                f"Финансы{est}: свободно {fmt_rub(s['free_minor'])}, бюджет на сегодня {fmt_rub(s['daily_budget_minor'])}, "
                f"осталось на сегодня {fmt_rub(s['today_left_minor'])}, до поступления {s['days_until_income']} дн. "
                f"Обязательства до поступления: {obligations}. Потрачено за месяц {fmt_rub(s['month_spent_minor'])}.")
        except Exception:
            lines.append("Финансы: сводка временно недоступна.")

    if want_fitness:
        last = (await db.execute(select(Workout).where(Workout.owner_id == owner_id, Workout.deleted_at.is_(None),
                                                       Workout.status == "done")
                                 .order_by(Workout.started_at.desc()).limit(1))).scalar_one_or_none()
        plans = (await db.execute(select(WorkoutPlan).where(WorkoutPlan.owner_id == owner_id,
                                                            WorkoutPlan.deleted_at.is_(None),
                                                            WorkoutPlan.is_active.is_(True)))).scalars().all()
        plan_s = ", ".join(f"{RU_WEEKDAYS[p.weekday - 1] if p.weekday else '—'}: {p.name}" for p in plans) or "нет программы"
        last_s = f"{last.started_at.astimezone(now.tzinfo):%d.%m}" if last else "нет записей"
        lines.append(f"Тренировки: программа — {plan_s}. Последняя записанная тренировка: {last_s}.")

    if want_tasks:
        tasks = (await db.execute(select(Task).where(
            Task.owner_id == owner_id, Task.deleted_at.is_(None), Task.status == "open",
            Task.due_at.is_not(None), Task.due_at < utcnow() + timedelta(days=1))
            .order_by(Task.due_at).limit(5))).scalars().all()
        if tasks:
            def describe(t: Task) -> str:
                when = "просрочено" if t.due_at < utcnow() else f"до {t.due_at.astimezone(now.tzinfo):%H:%M}"
                moved = f", переносилась {t.postponed_count} раз" if t.postponed_count else ""
                return f"{t.title} ({when}{moved})"
            lines.append("Задачи на сегодня и просроченные: " + "; ".join(describe(t) for t in tasks))

    pending = (await db.execute(select(func.count()).select_from(AgentAction).where(
        AgentAction.owner_id == owner_id, AgentAction.status == "pending_confirmation"))).scalar_one()
    if pending:
        lines.append(f"Ожидают подтверждения владельца: {pending} действий.")
    return "<context>\n" + "\n".join(lines) + "\n</context>"
