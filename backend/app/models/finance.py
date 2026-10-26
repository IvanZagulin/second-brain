"""Finance. Amounts are positive BIGINT kopecks; ``direction`` says whether money left (out) or
arrived (in); ``flow_type`` says what it means for income/expense totals."""

from __future__ import annotations

import uuid
from datetime import date, datetime
from decimal import Decimal
from typing import Any

from sqlalchemy import BigInteger, ForeignKey, Index, String, UniqueConstraint, func
from sqlalchemy.orm import Mapped, mapped_column

from app.models.base import Base, OwnedMixin, SyncMixin, TimestampMixin, UUIDPkMixin

FLOW_TYPES = (
    "expense", "income", "transfer", "cash_deposit", "cash_withdrawal",
    "debt_payment", "tax", "refund", "savings", "correction",
)
TX_STATUSES = ("new", "matched", "needs_review", "confirmed", "possible_duplicate", "excluded")


class Account(UUIDPkMixin, OwnedMixin, SyncMixin, TimestampMixin, Base):
    __tablename__ = "accounts"
    __sync__ = True
    __sync_push__ = True
    __sync_sort__ = "created_at"

    name: Mapped[str]
    kind: Mapped[str] = mapped_column(default="card")  # card | current | cash | savings | credit
    bank: Mapped[str | None]  # sber | tbank | cash | other
    currency: Mapped[str] = mapped_column(String(3), default="RUB")
    card_last4: Mapped[list[Any]] = mapped_column(default=list)
    is_business: Mapped[bool] = mapped_column(default=False)
    include_in_free: Mapped[bool] = mapped_column(default=True)
    # Balance the user (or a statement) confirmed at a moment; later movements are added on top.
    balance_minor: Mapped[int] = mapped_column(BigInteger, default=0)
    balance_at: Mapped[datetime | None]
    balance_source: Mapped[str | None]  # manual | statement | notification
    archived: Mapped[bool] = mapped_column(default=False)
    sort: Mapped[int] = mapped_column(default=0)


class BudgetCategory(UUIDPkMixin, OwnedMixin, SyncMixin, TimestampMixin, Base):
    __tablename__ = "budget_categories"
    __sync__ = True
    __sync_push__ = True
    __sync_sort__ = "created_at"

    name: Mapped[str]
    code: Mapped[str | None] = mapped_column(index=True)  # stable code for system categories
    kind: Mapped[str] = mapped_column(default="expense")  # expense | income | transfer
    icon: Mapped[str | None]
    color: Mapped[str | None]
    parent_id: Mapped[uuid.UUID | None]
    is_system: Mapped[bool] = mapped_column(default=False)
    is_impulse: Mapped[bool] = mapped_column(default=False)  # watched by the impulse-purchase control
    monthly_limit_minor: Mapped[int | None] = mapped_column(BigInteger)
    sort: Mapped[int] = mapped_column(default=0)


class CategoryRule(UUIDPkMixin, OwnedMixin, TimestampMixin, Base):
    """Learned from the user's corrections: merchant pattern → category/scope/flow."""

    __tablename__ = "category_rules"

    pattern: Mapped[str] = mapped_column(index=True)
    match_type: Mapped[str] = mapped_column(default="exact")  # exact | contains | regex
    category_id: Mapped[uuid.UUID | None] = mapped_column(ForeignKey("budget_categories.id", ondelete="CASCADE"))
    scope: Mapped[str | None]
    flow_type: Mapped[str | None]
    priority: Mapped[int] = mapped_column(default=0)
    hits: Mapped[int] = mapped_column(default=0)
    origin: Mapped[str] = mapped_column(default="user")  # user | auto | llm


class Transaction(UUIDPkMixin, OwnedMixin, SyncMixin, TimestampMixin, Base):
    __tablename__ = "transactions"
    __sync__ = True
    __sync_push__ = True
    __sync_sort__ = "occurred_at"
    __table_args__ = (
        Index("ix_transactions_owner_occurred", "owner_id", "occurred_at"),
        Index("ix_transactions_amount", "owner_id", "amount_minor"),
    )

    account_id: Mapped[uuid.UUID | None] = mapped_column(ForeignKey("accounts.id", ondelete="SET NULL"), index=True)
    occurred_at: Mapped[datetime]
    has_time: Mapped[bool] = mapped_column(default=True)
    amount_minor: Mapped[int] = mapped_column(BigInteger)
    currency: Mapped[str] = mapped_column(String(3), default="RUB")
    direction: Mapped[str] = mapped_column(default="out")  # out | in
    flow_type: Mapped[str] = mapped_column(default="expense")
    scope: Mapped[str] = mapped_column(default="personal")  # personal | business
    category_id: Mapped[uuid.UUID | None] = mapped_column(ForeignKey("budget_categories.id", ondelete="SET NULL"))
    category_confidence: Mapped[float | None]
    category_source: Mapped[str | None]  # user | rule | llm | bank
    merchant: Mapped[str | None]
    merchant_norm: Mapped[str | None] = mapped_column(index=True)
    description: Mapped[str | None]
    card_last4: Mapped[str | None] = mapped_column(String(4))
    bank_op_id: Mapped[str | None] = mapped_column(index=True)
    mcc: Mapped[str | None]
    status: Mapped[str] = mapped_column(default="new")
    # Completeness rank of the best source so far: manual 1, notification 2, receipt 3, statement 4.
    source_rank: Mapped[int] = mapped_column(default=1)
    balance_after_minor: Mapped[int | None] = mapped_column(BigInteger)
    counterpart_id: Mapped[uuid.UUID | None]  # paired transfer leg
    receipt_id: Mapped[uuid.UUID | None]
    subscription_id: Mapped[uuid.UUID | None]
    debt_id: Mapped[uuid.UUID | None]
    goal_id: Mapped[uuid.UUID | None]
    note: Mapped[str | None]
    tags: Mapped[list[Any]] = mapped_column(default=list)
    origin: Mapped[str] = mapped_column(default="manual")  # manual | notification | statement | receipt | agent


class TransactionSource(UUIDPkMixin, OwnedMixin, Base):
    """The original evidence for a transaction, kept verbatim."""

    __tablename__ = "transaction_sources"
    __table_args__ = (UniqueConstraint("owner_id", "fingerprint"),)

    transaction_id: Mapped[uuid.UUID | None] = mapped_column(ForeignKey("transactions.id", ondelete="SET NULL"), index=True)
    kind: Mapped[str]  # notification | statement | receipt | manual | qr
    fingerprint: Mapped[str] = mapped_column(String(64))
    package_name: Mapped[str | None]
    raw_text: Mapped[str | None]  # sensitive; never logged
    parsed: Mapped[dict[str, Any]] = mapped_column(default=dict)
    received_at: Mapped[datetime]
    import_job_id: Mapped[uuid.UUID | None]
    status: Mapped[str] = mapped_column(default="parsed")  # parsed | linked | ignored | failed
    error: Mapped[str | None]
    created_at: Mapped[datetime] = mapped_column(server_default=func.now())


class TransactionMatch(UUIDPkMixin, OwnedMixin, Base):
    """A candidate link between a new source and an existing transaction that needs a decision."""

    __tablename__ = "transaction_matches"

    source_id: Mapped[uuid.UUID] = mapped_column(ForeignKey("transaction_sources.id", ondelete="CASCADE"))
    transaction_id: Mapped[uuid.UUID] = mapped_column(ForeignKey("transactions.id", ondelete="CASCADE"))
    pending_transaction_id: Mapped[uuid.UUID | None] = mapped_column(ForeignKey("transactions.id", ondelete="CASCADE"))
    score: Mapped[float]
    reasons: Mapped[list[Any]] = mapped_column(default=list)
    status: Mapped[str] = mapped_column(default="pending")  # pending | accepted | rejected
    created_at: Mapped[datetime] = mapped_column(server_default=func.now())
    decided_at: Mapped[datetime | None]


class Receipt(UUIDPkMixin, OwnedMixin, SyncMixin, TimestampMixin, Base):
    __tablename__ = "receipts"
    __sync__ = True
    __sync_sort__ = "occurred_at"

    transaction_id: Mapped[uuid.UUID | None] = mapped_column(ForeignKey("transactions.id", ondelete="SET NULL"))
    store: Mapped[str | None]
    inn: Mapped[str | None]
    occurred_at: Mapped[datetime | None]
    total_minor: Mapped[int | None] = mapped_column(BigInteger)
    fn: Mapped[str | None]
    fd: Mapped[str | None]
    fp: Mapped[str | None]
    op_type: Mapped[str | None]
    qr_raw: Mapped[str | None]
    status: Mapped[str] = mapped_column(default="parsed")  # parsed | partial | items_loaded | failed
    origin: Mapped[str] = mapped_column(default="qr")  # qr | photo | pdf | share
    attachment_id: Mapped[uuid.UUID | None]
    data: Mapped[dict[str, Any]] = mapped_column(default=dict)


class ReceiptItem(UUIDPkMixin, OwnedMixin, Base):
    __tablename__ = "receipt_items"

    receipt_id: Mapped[uuid.UUID] = mapped_column(ForeignKey("receipts.id", ondelete="CASCADE"), index=True)
    name: Mapped[str]
    quantity: Mapped[Decimal] = mapped_column(default=Decimal(1))
    price_minor: Mapped[int | None] = mapped_column(BigInteger)
    sum_minor: Mapped[int | None] = mapped_column(BigInteger)
    discount_minor: Mapped[int | None] = mapped_column(BigInteger)
    category_id: Mapped[uuid.UUID | None]


class Budget(UUIDPkMixin, OwnedMixin, SyncMixin, TimestampMixin, Base):
    __tablename__ = "budgets"
    __sync__ = True
    __sync_push__ = True
    __table_args__ = (UniqueConstraint("owner_id", "month"),)

    month: Mapped[date]
    total_limit_minor: Mapped[int | None] = mapped_column(BigInteger)
    category_limits: Mapped[dict[str, Any]] = mapped_column(default=dict)
    reserves: Mapped[list[Any]] = mapped_column(default=list)  # [{"name", "amount_minor"}]
    savings_mode_until: Mapped[date | None]
    notes: Mapped[str | None]


class Subscription(UUIDPkMixin, OwnedMixin, SyncMixin, TimestampMixin, Base):
    __tablename__ = "subscriptions"
    __sync__ = True
    __sync_push__ = True
    __sync_sort__ = "next_charge_date"

    name: Mapped[str]
    amount_minor: Mapped[int] = mapped_column(BigInteger)
    currency: Mapped[str] = mapped_column(String(3), default="RUB")
    period: Mapped[str] = mapped_column(default="monthly")  # weekly | monthly | quarterly | yearly | days
    period_days: Mapped[int | None]
    next_charge_date: Mapped[date | None]
    account_id: Mapped[uuid.UUID | None]
    category_id: Mapped[uuid.UUID | None]
    merchant_pattern: Mapped[str | None]
    is_active: Mapped[bool] = mapped_column(default=True)
    usefulness: Mapped[int | None]  # 1..5, the user's rating
    last_charged_at: Mapped[datetime | None]
    remind_days_before: Mapped[int] = mapped_column(default=2)
    notes: Mapped[str | None]
    origin: Mapped[str] = mapped_column(default="manual")  # manual | detected


class Debt(UUIDPkMixin, OwnedMixin, SyncMixin, TimestampMixin, Base):
    __tablename__ = "debts"
    __sync__ = True
    __sync_push__ = True
    __sync_sort__ = "next_payment_date"

    creditor: Mapped[str]
    kind: Mapped[str] = mapped_column(default="microloan")  # microloan | loan | credit_card | personal | tax | other
    principal_minor: Mapped[int] = mapped_column(BigInteger, default=0)
    balance_minor: Mapped[int] = mapped_column(BigInteger, default=0)
    rate_percent: Mapped[Decimal | None]
    rate_period: Mapped[str] = mapped_column(default="year")  # day | month | year
    next_payment_date: Mapped[date | None]
    next_payment_minor: Mapped[int | None] = mapped_column(BigInteger)
    payment_period: Mapped[str | None]  # monthly | once | custom
    holiday_until: Mapped[date | None]
    holiday_terms: Mapped[str | None]
    # Interest maths only runs on confirmed terms; until then values are shown as estimates.
    terms_confirmed: Mapped[bool] = mapped_column(default=False)
    status: Mapped[str] = mapped_column(default="active")  # active | holiday | closed | overdue
    early_repayment_allowed: Mapped[bool | None]
    priority: Mapped[int] = mapped_column(default=2)
    notes: Mapped[str | None]


class DebtPayment(UUIDPkMixin, OwnedMixin, SyncMixin, TimestampMixin, Base):
    __tablename__ = "debt_payments"
    __sync__ = True
    __sync_push__ = True
    __sync_parent__ = "debt_id"
    __sync_sort__ = "paid_at"

    debt_id: Mapped[uuid.UUID] = mapped_column(ForeignKey("debts.id", ondelete="CASCADE"), index=True)
    paid_at: Mapped[datetime]
    amount_minor: Mapped[int] = mapped_column(BigInteger)
    kind: Mapped[str] = mapped_column(default="scheduled")  # scheduled | extra
    transaction_id: Mapped[uuid.UUID | None]
    note: Mapped[str | None]


class FinancialGoal(UUIDPkMixin, OwnedMixin, SyncMixin, TimestampMixin, Base):
    __tablename__ = "financial_goals"
    __sync__ = True
    __sync_push__ = True
    __sync_sort__ = "deadline"

    name: Mapped[str]
    target_minor: Mapped[int] = mapped_column(BigInteger)
    target_max_minor: Mapped[int | None] = mapped_column(BigInteger)
    saved_minor: Mapped[int] = mapped_column(BigInteger, default=0)
    deadline: Mapped[date | None]
    priority: Mapped[int] = mapped_column(default=2)
    monthly_plan_minor: Mapped[int | None] = mapped_column(BigInteger)
    status: Mapped[str] = mapped_column(default="active")  # active | paused | done
    notes: Mapped[str | None]


class GoalContribution(UUIDPkMixin, OwnedMixin, SyncMixin, TimestampMixin, Base):
    __tablename__ = "goal_contributions"
    __sync__ = True
    __sync_push__ = True
    __sync_parent__ = "goal_id"
    __sync_sort__ = "at"

    goal_id: Mapped[uuid.UUID] = mapped_column(ForeignKey("financial_goals.id", ondelete="CASCADE"), index=True)
    at: Mapped[datetime]
    amount_minor: Mapped[int] = mapped_column(BigInteger)  # negative = withdrawal
    transaction_id: Mapped[uuid.UUID | None]
    note: Mapped[str | None]


class TaxPayment(UUIDPkMixin, OwnedMixin, SyncMixin, TimestampMixin, Base):
    """Tax calendar entries for the sole proprietorship; amounts are estimates until paid."""

    __tablename__ = "tax_payments"
    __sync__ = True
    __sync_push__ = True
    __sync_sort__ = "due_date"
    __table_args__ = (UniqueConstraint("owner_id", "kind", "period"),)

    kind: Mapped[str]  # usn_advance | usn_annual | insurance_fixed | insurance_1pct | npd | other
    period: Mapped[str]  # "2026-Q3", "2026"
    due_date: Mapped[date]
    estimated_minor: Mapped[int] = mapped_column(BigInteger, default=0)
    paid_minor: Mapped[int] = mapped_column(BigInteger, default=0)
    paid_at: Mapped[datetime | None]
    status: Mapped[str] = mapped_column(default="planned")  # planned | paid | overdue
    notes: Mapped[str | None]


class WishlistItem(UUIDPkMixin, OwnedMixin, SyncMixin, TimestampMixin, Base):
    """«Хочу купить»: purchase considerations and their verdicts."""

    __tablename__ = "wishlist_items"
    __sync__ = True
    __sync_push__ = True
    __sync_sort__ = "created_at"

    title: Mapped[str]
    price_minor: Mapped[int | None] = mapped_column(BigInteger)
    url: Mapped[str | None]
    note: Mapped[str | None]
    attachment_id: Mapped[uuid.UUID | None]
    analysis: Mapped[dict[str, Any]] = mapped_column(default=dict)
    verdict: Mapped[str | None]  # buy | wait | skip
    wait_until: Mapped[date | None]
    decision: Mapped[str | None]  # bought | skipped | waiting
    decided_at: Mapped[datetime | None]
