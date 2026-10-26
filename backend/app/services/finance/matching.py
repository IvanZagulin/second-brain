"""Turning evidence (notifications, statement rows, receipts) into transactions without duplicates.

Rules:
* every piece of evidence is a TransactionSource with a fingerprint — the same evidence twice is a
  no-op;
* evidence that matches exactly one existing transaction is linked to it and may enrich it (a more
  complete source wins, the original stays stored);
* evidence matching several transactions creates a ``possible_duplicate`` transaction plus pending
  TransactionMatch rows; nothing is merged without the owner's decision;
* money moving between the owner's own accounts (IP account → card, cash → card) is paired and
  marked as a transfer so it is never counted as income twice.
"""

from __future__ import annotations

import hashlib
import re
import uuid
from dataclasses import dataclass
from datetime import datetime, timedelta
from typing import Any

from sqlalchemy import and_, or_, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.timeutil import utcnow
from app.models import Account, Transaction, TransactionMatch, TransactionSource
from app.services.events import emit
from app.services.finance.categorize import apply_rules, category_ids, normalize_merchant
from app.services.finance.parsers import ParsedOp, parse_notification
from app.services.finance.statements import StatementData, StatementRow

RANK = {"manual": 1, "notification": 2, "receipt": 3, "statement": 4}
EXCLUDED_STATUSES = ("excluded", "possible_duplicate")


@dataclass
class IngestResult:
    status: str  # created | linked | duplicate | ambiguous | ignored
    transaction_id: uuid.UUID | None = None
    source_id: uuid.UUID | None = None

    def as_dict(self) -> dict[str, Any]:
        return {"status": self.status, "transaction_id": str(self.transaction_id) if self.transaction_id else None,
                "source_id": str(self.source_id) if self.source_id else None}


def notification_fingerprint(package: str | None, text: str, posted_at: datetime) -> str:
    norm = re.sub(r"\s+", " ", text.strip().lower())
    minute = posted_at.strftime("%Y-%m-%dT%H:%M")
    return hashlib.sha256(f"notif|{package}|{norm}|{minute}".encode()).hexdigest()


async def _source_exists(db: AsyncSession, owner_id: uuid.UUID, fingerprint: str) -> TransactionSource | None:
    return (await db.execute(
        select(TransactionSource).where(TransactionSource.owner_id == owner_id,
                                        TransactionSource.fingerprint == fingerprint)
    )).scalar_one_or_none()


async def resolve_account(db: AsyncSession, owner_id: uuid.UUID, *, card_last4: str | None = None,
                          bank: str | None = None) -> Account | None:
    accounts = (await db.execute(
        select(Account).where(Account.owner_id == owner_id, Account.deleted_at.is_(None), Account.archived.is_(False))
    )).scalars().all()
    if card_last4:
        for acc in accounts:
            if card_last4 in (acc.card_last4 or []):
                return acc
    if bank:
        family = "tbank" if bank.startswith("tbank") else bank
        same_bank = [a for a in accounts if (a.bank or "").startswith(family)]
        if bank == "tbank_business":
            same_bank = [a for a in same_bank if a.is_business] or same_bank
        if len(same_bank) == 1:
            return same_bank[0]
    return None


async def _candidates(db: AsyncSession, owner_id: uuid.UUID, *, amount_minor: int, direction: str,
                      at: datetime, window: timedelta, card_last4: str | None, account_id: uuid.UUID | None,
                      exclude_source_kind: str | None) -> list[Transaction]:
    q = select(Transaction).where(
        Transaction.owner_id == owner_id, Transaction.amount_minor == amount_minor,
        Transaction.direction == direction, Transaction.deleted_at.is_(None),
        Transaction.status != "excluded",
        Transaction.occurred_at.between(at - window, at + window),
    )
    if card_last4:
        q = q.where(or_(Transaction.card_last4.is_(None), Transaction.card_last4 == card_last4))
    if account_id:
        q = q.where(or_(Transaction.account_id.is_(None), Transaction.account_id == account_id))
    found = list((await db.execute(q)).scalars().all())
    if exclude_source_kind and found:
        # A transaction that already has evidence of this kind is a different operation.
        taken = set((await db.execute(
            select(TransactionSource.transaction_id).where(
                TransactionSource.transaction_id.in_([t.id for t in found]),
                TransactionSource.kind == exclude_source_kind)
        )).scalars().all())
        found = [t for t in found if t.id not in taken]
    return found


def _enrich(tx: Transaction, *, rank: int, merchant: str | None, description: str | None,
            occurred_at: datetime | None, has_time: bool, bank_op_id: str | None, mcc: str | None,
            balance_after: int | None, card_last4: str | None, account_id: uuid.UUID | None) -> None:
    better = rank > tx.source_rank
    if merchant and (better or not tx.merchant):
        tx.merchant, tx.merchant_norm = merchant, normalize_merchant(merchant)
    if description and (better or not tx.description):
        tx.description = description
    if occurred_at and has_time and (not tx.has_time or better):
        tx.occurred_at, tx.has_time = occurred_at, True
    tx.bank_op_id = tx.bank_op_id or bank_op_id
    tx.mcc = tx.mcc or mcc
    tx.card_last4 = tx.card_last4 or card_last4
    tx.account_id = tx.account_id or account_id
    if balance_after is not None and better:
        tx.balance_after_minor = balance_after
    tx.source_rank = max(tx.source_rank, rank)
    if tx.status in ("new", "needs_review") and rank >= RANK["statement"]:
        tx.status = "confirmed"
    elif tx.status == "new":
        tx.status = "matched"


async def ingest_evidence(db: AsyncSession, owner_id: uuid.UUID, *, kind: str, fingerprint: str,
                          amount_minor: int, direction: str, flow_type: str, occurred_at: datetime,
                          has_time: bool = True, currency: str = "RUB", merchant: str | None = None,
                          description: str | None = None, card_last4: str | None = None,
                          bank: str | None = None, bank_op_id: str | None = None, mcc: str | None = None,
                          balance_after: int | None = None, account_id: uuid.UUID | None = None,
                          ambiguous: bool = False, raw_text: str | None = None, parsed: dict | None = None,
                          package_name: str | None = None, import_job_id: uuid.UUID | None = None,
                          confidence: float = 0.8, scope: str | None = None) -> IngestResult:
    if await _source_exists(db, owner_id, fingerprint):
        return IngestResult("duplicate")
    rank = RANK.get(kind, 1)
    account = await db.get(Account, account_id) if account_id else \
        await resolve_account(db, owner_id, card_last4=card_last4, bank=bank)
    source = TransactionSource(owner_id=owner_id, kind=kind, fingerprint=fingerprint, package_name=package_name,
                               raw_text=raw_text, parsed=parsed or {}, received_at=occurred_at,
                               import_job_id=import_job_id)
    db.add(source)

    if kind == "notification":
        window = timedelta(minutes=10)
    elif has_time:
        window = timedelta(hours=6)
    else:
        window = timedelta(days=1, hours=12)
    cands = await _candidates(db, owner_id, amount_minor=amount_minor, direction=direction, at=occurred_at,
                              window=window, card_last4=card_last4, account_id=account.id if account else None,
                              exclude_source_kind=kind)
    if len(cands) == 1:
        tx = cands[0]
        _enrich(tx, rank=rank, merchant=merchant, description=description, occurred_at=occurred_at,
                has_time=has_time, bank_op_id=bank_op_id, mcc=mcc, balance_after=balance_after,
                card_last4=card_last4, account_id=account.id if account else None)
        source.transaction_id, source.status = tx.id, "linked"
        await db.flush()
        if account and balance_after is not None and rank >= RANK["notification"]:
            _update_confirmed_balance(account, balance_after, occurred_at, kind)
        return IngestResult("linked", tx.id, source.id)

    status = "possible_duplicate" if cands else ("confirmed" if rank >= RANK["statement"] else "new")
    if ambiguous and status != "possible_duplicate":
        status = "needs_review"
    tx = Transaction(
        owner_id=owner_id, account_id=account.id if account else None, occurred_at=occurred_at, has_time=has_time,
        amount_minor=amount_minor, currency=currency, direction=direction, flow_type=flow_type,
        scope=scope or ("business" if account and account.is_business else "personal"),
        merchant=merchant, merchant_norm=normalize_merchant(merchant), description=description,
        card_last4=card_last4, bank_op_id=bank_op_id, mcc=mcc, status=status, source_rank=rank,
        balance_after_minor=balance_after, origin=kind, category_confidence=None,
    )
    db.add(tx)
    await db.flush()
    source.transaction_id, source.status = tx.id, "linked"
    for cand in cands:
        db.add(TransactionMatch(owner_id=owner_id, source_id=source.id, transaction_id=cand.id,
                                pending_transaction_id=tx.id, score=0.6,
                                reasons=["та же сумма", "близкое время"]))
    await apply_rules(db, tx)
    await _pair_internal(db, tx)
    await _cash_counterpart(db, tx)
    if account and balance_after is not None:
        _update_confirmed_balance(account, balance_after, occurred_at, kind)
    await db.flush()
    if status != "possible_duplicate":
        await emit(db, owner_id, "transaction.created",
                   {"transaction_id": str(tx.id), "amount_minor": amount_minor, "direction": direction,
                    "flow_type": tx.flow_type, "merchant": merchant, "category_id": str(tx.category_id or "")},
                   dedupe_key=f"tx:{tx.id}")
    return IngestResult("ambiguous" if cands else "created", tx.id, source.id)


def _update_confirmed_balance(account: Account, balance: int, at: datetime, kind: str) -> None:
    if account.balance_at is None or at >= account.balance_at:
        account.balance_minor, account.balance_at, account.balance_source = balance, at, kind


async def _pair_internal(db: AsyncSession, tx: Transaction) -> None:
    """Pair an outgoing and an incoming leg of the same amount between two own accounts."""
    text = f"{tx.description or ''} {tx.merchant or ''}".lower()
    looks_like_transfer = tx.flow_type in ("income", "expense") and (
        "перевод" in text or "пополнение" in text or "между своими" in text or "с карты" in text
        or "на карту" in text or tx.origin == "statement" and tx.scope == "business")
    if not looks_like_transfer or tx.account_id is None:
        return
    opposite = "in" if tx.direction == "out" else "out"
    legs = (await db.execute(
        select(Transaction).where(
            Transaction.owner_id == tx.owner_id, Transaction.id != tx.id, Transaction.amount_minor == tx.amount_minor,
            Transaction.direction == opposite, Transaction.deleted_at.is_(None),
            Transaction.counterpart_id.is_(None), Transaction.account_id.is_not(None),
            Transaction.account_id != tx.account_id,
            Transaction.occurred_at.between(tx.occurred_at - timedelta(days=2), tx.occurred_at + timedelta(days=2)),
            Transaction.status.not_in(EXCLUDED_STATUSES),
        )
    )).scalars().all()
    if not legs:
        return
    codes = await category_ids(db, tx.owner_id)
    if len(legs) == 1:
        other = legs[0]
        for leg in (tx, other):
            leg.flow_type = "transfer"
            leg.category_id = codes.get("transfers", leg.category_id)
            leg.category_source, leg.category_confidence = "rule", 0.9
            if leg.status == "needs_review":
                leg.status = "matched"
        tx.counterpart_id, other.counterpart_id = other.id, tx.id
    else:
        tx.status = "needs_review"


async def _cash_counterpart(db: AsyncSession, tx: Transaction) -> None:
    """Card ↔ cash moves: mirror them on the cash account so its balance stays right."""
    if tx.flow_type not in ("cash_withdrawal", "cash_deposit") or tx.counterpart_id:
        return
    cash = (await db.execute(
        select(Account).where(Account.owner_id == tx.owner_id, Account.kind == "cash", Account.deleted_at.is_(None),
                              Account.archived.is_(False))
    )).scalars().first()
    if cash is None or cash.id == tx.account_id:
        return
    mirror = Transaction(
        owner_id=tx.owner_id, account_id=cash.id, occurred_at=tx.occurred_at, has_time=tx.has_time,
        amount_minor=tx.amount_minor, currency=tx.currency, direction="in" if tx.direction == "out" else "out",
        flow_type=tx.flow_type, scope="personal", merchant="Наличные", merchant_norm="НАЛИЧНЫЕ",
        description="Автоматически: движение между картой и наличными", status="matched",
        source_rank=tx.source_rank, origin="agent", counterpart_id=tx.id,
    )
    db.add(mirror)
    await db.flush()
    tx.counterpart_id = mirror.id


async def ingest_notification(db: AsyncSession, owner_id: uuid.UUID, *, package: str | None, title: str | None,
                              text: str, posted_at: datetime) -> IngestResult:
    fingerprint = notification_fingerprint(package, f"{title or ''} {text}", posted_at)
    if await _source_exists(db, owner_id, fingerprint):
        return IngestResult("duplicate")
    from app.models import User
    user = await db.get(User, owner_id)
    op: ParsedOp | None = parse_notification(package, title, text, posted_at, user.timezone if user else None)
    if op is None:
        db.add(TransactionSource(owner_id=owner_id, kind="notification", fingerprint=fingerprint,
                                 package_name=package, raw_text=f"{title or ''}\n{text}".strip(),
                                 received_at=posted_at, status="ignored"))
        return IngestResult("ignored")
    return await ingest_evidence(
        db, owner_id, kind="notification", fingerprint=fingerprint, amount_minor=op.amount_minor,
        direction=op.direction, flow_type=op.flow_type, occurred_at=op.occurred_at or posted_at, currency=op.currency,
        merchant=op.merchant, card_last4=op.card_last4, bank=op.bank, balance_after=op.balance_minor,
        ambiguous=op.ambiguous, raw_text=f"{title or ''}\n{text}".strip(), parsed=op.as_dict(),
        package_name=package, confidence=op.confidence,
    )


async def ingest_statement(db: AsyncSession, owner_id: uuid.UUID, data: StatementData, *,
                           account_id: uuid.UUID | None, import_job_id: uuid.UUID | None) -> dict[str, Any]:
    report = {"rows": len(data.rows), "created": 0, "linked": 0, "duplicate": 0, "ambiguous": 0,
              "bank": data.bank, "format": data.format, "account_hint": data.account_hint,
              "warnings": data.warnings}
    account = await db.get(Account, account_id) if account_id else \
        await resolve_account(db, owner_id, card_last4=data.account_hint, bank=data.bank)
    report["account_id"] = str(account.id) if account else None
    latest_balance: tuple[datetime, int] | None = None
    codes = await category_ids(db, owner_id)
    for row in sorted(data.rows, key=lambda r: r.occurred_at):
        flow = row.flow_hint or _statement_flow(row)
        scope = row.scope_hint or ("business" if account and account.is_business and row.direction == "in" else None)
        res = await ingest_evidence(
            db, owner_id, kind="statement", fingerprint=row.fingerprint, amount_minor=row.amount_minor,
            direction=row.direction, flow_type=flow, occurred_at=row.occurred_at, has_time=row.has_time,
            currency=row.currency, merchant=row.merchant, description=row.description, card_last4=row.card_last4,
            bank=data.bank, bank_op_id=row.bank_op_id, mcc=row.mcc, balance_after=None,
            account_id=account.id if account else None,
            ambiguous=row.flow_hint is None and _statement_ambiguous(row),
            parsed={"bank_category": row.bank_category, "counterparty_account": row.counterparty_account},
            import_job_id=import_job_id, scope=scope or "personal",
        )
        if res.status == "created" and res.transaction_id and row.category_hint in codes:
            tx = await db.get(Transaction, res.transaction_id)
            if tx.category_source != "user":
                tx.category_id, tx.category_source, tx.category_confidence = codes[row.category_hint], "bank", 0.9
        report[res.status] = report.get(res.status, 0) + 1
        if row.balance_after_minor is not None:
            latest_balance = (row.occurred_at, row.balance_after_minor)
    if account and latest_balance:
        _update_confirmed_balance(account, latest_balance[1], latest_balance[0], "statement")
    return report


def _statement_flow(row: StatementRow) -> str:
    text = f"{row.description} {row.bank_category or ''}".lower()
    if "снятие наличных" in text or "выдача наличных" in text:
        return "cash_withdrawal"
    if "внесение наличных" in text or "взнос наличных" in text:
        return "cash_deposit"
    if row.direction == "in":
        return "refund" if "возврат" in text else "income"
    if "налог" in text or "уфк" in text or "фнс" in text:
        return "tax"
    return "expense"


def _statement_ambiguous(row: StatementRow) -> bool:
    text = f"{row.description} {row.bank_category or ''}".lower()
    return "перевод" in text and row.direction == "in"


async def decide_match(db: AsyncSession, owner_id: uuid.UUID, match_id: uuid.UUID, accept: bool) -> dict[str, Any]:
    match = await db.get(TransactionMatch, match_id)
    if match is None or match.owner_id != owner_id or match.status != "pending":
        return {"status": "not_found"}
    match.status, match.decided_at = ("accepted" if accept else "rejected"), utcnow()
    pending = await db.get(Transaction, match.pending_transaction_id) if match.pending_transaction_id else None
    target = await db.get(Transaction, match.transaction_id)
    siblings = (await db.execute(
        select(TransactionMatch).where(TransactionMatch.pending_transaction_id == match.pending_transaction_id,
                                       TransactionMatch.id != match.id, TransactionMatch.status == "pending")
    )).scalars().all()
    if accept and pending and target:
        sources = (await db.execute(
            select(TransactionSource).where(TransactionSource.transaction_id == pending.id))).scalars().all()
        for s in sources:
            s.transaction_id = target.id
        _enrich(target, rank=pending.source_rank, merchant=pending.merchant, description=pending.description,
                occurred_at=pending.occurred_at, has_time=pending.has_time, bank_op_id=pending.bank_op_id,
                mcc=pending.mcc, balance_after=pending.balance_after_minor, card_last4=pending.card_last4,
                account_id=pending.account_id)
        pending.deleted_at, pending.status = utcnow(), "excluded"
        for s in siblings:
            s.status, s.decided_at = "rejected", utcnow()
    elif pending and not siblings:
        # Every candidate rejected → it is a separate operation.
        pending.status = "confirmed" if pending.source_rank >= RANK["statement"] else "new"
        await emit(db, owner_id, "transaction.created",
                   {"transaction_id": str(pending.id), "amount_minor": pending.amount_minor,
                    "direction": pending.direction, "flow_type": pending.flow_type, "merchant": pending.merchant},
                   dedupe_key=f"tx:{pending.id}")
    return {"status": match.status}


async def review_queue(db: AsyncSession, owner_id: uuid.UUID) -> dict[str, Any]:
    review = (await db.execute(
        select(Transaction).where(Transaction.owner_id == owner_id, Transaction.deleted_at.is_(None),
                                  Transaction.status.in_(("needs_review", "possible_duplicate")))
        .order_by(Transaction.occurred_at.desc()).limit(200)
    )).scalars().all()
    matches = (await db.execute(
        select(TransactionMatch).where(TransactionMatch.owner_id == owner_id, TransactionMatch.status == "pending")
    )).scalars().all()
    uncategorized = (await db.execute(
        select(Transaction).where(and_(Transaction.owner_id == owner_id, Transaction.deleted_at.is_(None),
                                       Transaction.category_id.is_(None), Transaction.direction == "out",
                                       Transaction.status.not_in(EXCLUDED_STATUSES)))
        .order_by(Transaction.occurred_at.desc()).limit(200)
    )).scalars().all()
    return {"review": review, "matches": matches, "uncategorized": uncategorized}
