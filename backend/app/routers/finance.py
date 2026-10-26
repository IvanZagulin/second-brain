from __future__ import annotations

import uuid
from datetime import date, datetime, timedelta

from fastapi import APIRouter, Depends, File, Form, Query, UploadFile
from pydantic import BaseModel, Field
from sqlalchemy import or_, select
from sqlalchemy.ext.asyncio import AsyncSession

from agent.reports import analyze_purchase
from app.core.db import get_db
from app.core.deps import Auth, get_auth
from app.core.errors import ApiError, NotFound
from app.core.timeutil import local_day_bounds, tz, utcnow
from app.models import (
    Account, Attachment, Budget, BudgetCategory, CategoryRule, Debt, DebtPayment, FinancialGoal, GoalContribution,
    Receipt, ReceiptItem, Subscription, TaxPayment, Transaction, TransactionSource, WishlistItem,
)
from app.services.audit import audit
from app.services.crud import crud_router, serialize
from app.services.finance import engine
from app.services.finance.matching import decide_match, ingest_notification, ingest_statement, review_queue
from app.services.finance.receipts import receipt_from_attachment, receipt_from_qr
from app.services.finance.recurring import detect_subscriptions, refresh_tax_calendar
from app.services.finance.statements import UnsupportedStatement, parse_statement

router = APIRouter(prefix="/api/v1/finance", tags=["finance"])


@router.get("/summary")
async def summary(auth: Auth = Depends(get_auth), db: AsyncSession = Depends(get_db)):
    return await engine.finance_summary(db, auth.owner_id)


@router.get("/spending")
async def spending(date_from: date, date_to: date, scope: str = "personal", auth: Auth = Depends(get_auth),
                   db: AsyncSession = Depends(get_db)):
    start, _ = local_day_bounds(date_from, auth.user.timezone)
    _, end = local_day_bounds(date_to, auth.user.timezone)
    return await engine.spending(db, auth.owner_id, start, end, scope)


@router.get("/transactions")
async def transactions(date_from: date | None = None, date_to: date | None = None, account_id: uuid.UUID | None = None,
                       category_id: uuid.UUID | None = None, status: str | None = None, q: str | None = None,
                       limit: int = Query(100, le=1000), offset: int = 0, auth: Auth = Depends(get_auth),
                       db: AsyncSession = Depends(get_db)):
    stmt = select(Transaction).where(Transaction.owner_id == auth.owner_id, Transaction.deleted_at.is_(None))
    if date_from:
        stmt = stmt.where(Transaction.occurred_at >= local_day_bounds(date_from, auth.user.timezone)[0])
    if date_to:
        stmt = stmt.where(Transaction.occurred_at < local_day_bounds(date_to, auth.user.timezone)[1])
    if account_id:
        stmt = stmt.where(Transaction.account_id == account_id)
    if category_id:
        stmt = stmt.where(Transaction.category_id == category_id)
    if status:
        stmt = stmt.where(Transaction.status == status)
    if q:
        stmt = stmt.where(or_(Transaction.merchant.ilike(f"%{q}%"), Transaction.description.ilike(f"%{q}%")))
    rows = (await db.execute(stmt.order_by(Transaction.occurred_at.desc()).limit(limit).offset(offset))).scalars().all()
    return {"items": [serialize(t) for t in rows]}


@router.get("/transactions/{tx_id}/sources")
async def tx_sources(tx_id: uuid.UUID, auth: Auth = Depends(get_auth), db: AsyncSession = Depends(get_db)):
    rows = (await db.execute(select(TransactionSource).where(TransactionSource.transaction_id == tx_id,
                                                             TransactionSource.owner_id == auth.owner_id))).scalars().all()
    await audit(db, auth.owner_id, auth.actor, "read_sensitive", "transaction_sources", tx_id)
    return {"items": [serialize(s) for s in rows]}


class NotificationIn(BaseModel):
    package: str | None = None
    title: str | None = None
    text: str = Field(min_length=1, max_length=2000)
    posted_at: datetime


class NotificationsIn(BaseModel):
    items: list[NotificationIn] = Field(max_length=200)


@router.post("/notifications")
async def notifications(body: NotificationsIn, auth: Auth = Depends(get_auth), db: AsyncSession = Depends(get_db)):
    """Bank / Mir Pay push notifications captured by the phone (only allow-listed apps)."""
    results = []
    for n in body.items:
        res = await ingest_notification(db, auth.owner_id, package=n.package, title=n.title, text=n.text,
                                        posted_at=n.posted_at)
        results.append(res.as_dict())
    return {"results": results}


@router.post("/statements")
async def statements(file: UploadFile = File(...), account_id: uuid.UUID | None = Form(None),
                     bank: str | None = Form(None), auth: Auth = Depends(get_auth), db: AsyncSession = Depends(get_db)):
    raw = await file.read()
    if len(raw) > 30 * 1024 * 1024:
        raise ApiError("Файл больше 30 МБ", code="too_large")
    try:
        data = parse_statement(file.filename or "statement", raw, auth.user.timezone, bank)
    except UnsupportedStatement as exc:
        raise ApiError(str(exc), code="unsupported_statement") from exc
    report = await ingest_statement(db, auth.owner_id, data, account_id=account_id, import_job_id=None)
    await audit(db, auth.owner_id, auth.actor, "import_statement", "transactions", None,
                {k: v for k, v in report.items() if k != "warnings"})
    return report


@router.get("/review")
async def review(auth: Auth = Depends(get_auth), db: AsyncSession = Depends(get_db)):
    data = await review_queue(db, auth.owner_id)
    return {"review": [serialize(t) for t in data["review"]], "matches": [serialize(m) for m in data["matches"]],
            "uncategorized": [serialize(t) for t in data["uncategorized"]]}


@router.post("/matches/{match_id}/{decision}")
async def match_decision(match_id: uuid.UUID, decision: str, auth: Auth = Depends(get_auth),
                         db: AsyncSession = Depends(get_db)):
    if decision not in ("accept", "reject"):
        raise ApiError("decision: accept | reject")
    return await decide_match(db, auth.owner_id, match_id, decision == "accept")


@router.get("/subscriptions/suggestions")
async def subscription_suggestions(auth: Auth = Depends(get_auth), db: AsyncSession = Depends(get_db)):
    return {"items": await detect_subscriptions(db, auth.owner_id)}


@router.post("/tax/refresh")
async def tax_refresh(year: int | None = None, auth: Auth = Depends(get_auth), db: AsyncSession = Depends(get_db)):
    rows = await refresh_tax_calendar(db, auth.owner_id, year)
    return {"items": [serialize(r) for r in rows]}


class RuleIn(BaseModel):
    pattern: str = Field(min_length=2)
    match_type: str = "contains"
    category_id: uuid.UUID | None = None
    scope: str | None = None
    flow_type: str | None = None


@router.get("/rules")
async def rules(auth: Auth = Depends(get_auth), db: AsyncSession = Depends(get_db)):
    rows = (await db.execute(select(CategoryRule).where(CategoryRule.owner_id == auth.owner_id)
                             .order_by(CategoryRule.hits.desc()))).scalars().all()
    return {"items": [serialize(r) for r in rows]}


@router.post("/rules", status_code=201)
async def add_rule(body: RuleIn, auth: Auth = Depends(get_auth), db: AsyncSession = Depends(get_db)):
    r = CategoryRule(owner_id=auth.owner_id, origin="user", priority=20, **body.model_dump())
    db.add(r)
    await db.flush()
    return serialize(r)


@router.delete("/rules/{rule_id}")
async def delete_rule(rule_id: uuid.UUID, auth: Auth = Depends(get_auth), db: AsyncSession = Depends(get_db)):
    r = await db.get(CategoryRule, rule_id)
    if r is None or r.owner_id != auth.owner_id:
        raise NotFound("Правило не найдено")
    await db.delete(r)
    return {"ok": True}


class WishIn(BaseModel):
    id: uuid.UUID | None = None
    title: str = Field(min_length=1, max_length=300)
    price_rub: float | None = Field(None, ge=0)
    url: str | None = None
    note: str | None = None
    attachment_id: uuid.UUID | None = None


@router.post("/wishlist/analyze")
async def wish_analyze(body: WishIn, auth: Auth = Depends(get_auth), db: AsyncSession = Depends(get_db)):
    """«Хочу купить»: deterministic budget impact + Claude's critical review, in one call."""
    from decimal import Decimal
    from app.core.money import to_minor
    item = WishlistItem(id=body.id or uuid.uuid4(), owner_id=auth.owner_id, title=body.title,
                        price_minor=to_minor(Decimal(str(body.price_rub))) if body.price_rub else None,
                        url=body.url, note=body.note, attachment_id=body.attachment_id, analysis={"pending": True},
                        decision="waiting")
    db.add(item)
    await db.commit()
    analysis = await analyze_purchase(auth.owner_id, item.id)
    await db.refresh(item)
    return serialize(item) | {"analysis": analysis or item.analysis}


class QrIn(BaseModel):
    qr: str = Field(min_length=10, max_length=500)


@router.post("/receipts/qr")
async def receipt_qr(body: QrIn, auth: Auth = Depends(get_auth), db: AsyncSession = Depends(get_db)):
    r = await receipt_from_qr(db, auth.owner_id, body.qr)
    return await _receipt_out(db, r)


class AttachmentRef(BaseModel):
    attachment_id: uuid.UUID


@router.post("/receipts/attachment")
async def receipt_attachment(body: AttachmentRef, auth: Auth = Depends(get_auth), db: AsyncSession = Depends(get_db)):
    att = await db.get(Attachment, body.attachment_id)
    if att is None or att.owner_id != auth.owner_id:
        raise NotFound("Файл не найден")
    r = await receipt_from_attachment(db, auth.owner_id, att)
    return await _receipt_out(db, r)


@router.get("/receipts/{receipt_id}/items")
async def receipt_items(receipt_id: uuid.UUID, auth: Auth = Depends(get_auth), db: AsyncSession = Depends(get_db)):
    r = await db.get(Receipt, receipt_id)
    if r is None or r.owner_id != auth.owner_id:
        raise NotFound("Чек не найден")
    return await _receipt_out(db, r)


async def _receipt_out(db: AsyncSession, r: Receipt) -> dict:
    await db.flush()
    items = (await db.execute(select(ReceiptItem).where(ReceiptItem.receipt_id == r.id))).scalars().all()
    return serialize(r) | {"items": [serialize(i) for i in items]}


crud_routers = [
    crud_router(Account, "/api/v1/finance/accounts", "finance", order_by="sort"),
    crud_router(BudgetCategory, "/api/v1/finance/categories", "finance", order_by="sort"),
    crud_router(Transaction, "/api/v1/finance/tx", "finance", order_by="occurred_at"),
    crud_router(Subscription, "/api/v1/finance/subscriptions", "finance", order_by="next_charge_date"),
    crud_router(Debt, "/api/v1/debts", "finance", order_by="next_payment_date"),
    crud_router(DebtPayment, "/api/v1/debts-payments", "finance", order_by="paid_at"),
    crud_router(FinancialGoal, "/api/v1/finance/goals", "finance", order_by="deadline"),
    crud_router(GoalContribution, "/api/v1/finance/goal-contributions", "finance", order_by="at"),
    crud_router(TaxPayment, "/api/v1/finance/taxes", "finance", order_by="due_date"),
    crud_router(Budget, "/api/v1/budgets", "finance", order_by="month"),
    crud_router(WishlistItem, "/api/v1/finance/wishlist", "finance", order_by="created_at"),
    crud_router(Receipt, "/api/v1/receipts", "finance", order_by="occurred_at"),
]
