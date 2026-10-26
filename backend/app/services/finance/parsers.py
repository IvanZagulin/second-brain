"""Parsing of bank / Mir Pay push notifications into a structured operation.

Formats differ between app versions, so this is keyword + regex heuristics with a confidence
score; the raw text is always stored next to the result. Nothing is invented: if no amount is
found the notification is kept as an unparsed source and no transaction is created."""

from __future__ import annotations

import re
from dataclasses import asdict, dataclass, field
from datetime import datetime, timedelta

from app.core.money import to_minor
from app.core.timeutil import tz

BANK_BY_PACKAGE = {
    "ru.nspk.mirpay": "mirpay",
    "ru.sberbankmobile": "sber",
    "ru.sberbank.sberbankid": "sber",
    "com.idamob.tinkoff.android": "tbank",
    "com.tinkoff.business": "tbank_business",
    "ru.tinkoff.business": "tbank_business",
}

_NUM = r"\d{1,3}(?:[   ]\d{3})+(?:[.,]\d{1,2})?|\d+(?:[.,]\d{1,2})?"
_CUR = r"₽|руб(?:\.|лей|ля|ль)?|р\.|р\b|RUB|RUR|\$|USD|€|EUR"
AMOUNT_RE = re.compile(rf"(?P<sign>[+\-−])?\s?(?P<amt>{_NUM})\s?(?P<cur>{_CUR})", re.IGNORECASE)
BALANCE_RE = re.compile(rf"(?:баланс|доступно|остаток|доступный остаток)\s*:?\s*(?P<amt>{_NUM})\s?(?P<cur>{_CUR})?",
                        re.IGNORECASE)
CARD_RE = re.compile(
    r"(?:карт[аыеуой]*|счёт|счет|сч[её]т\s*карты|mir|мир|visa|ecmc|maestro|mastercard|pay)\s*[*•·.]*\s?(\d{4})\b"
    r"|[*•·]{1,4}\s?(\d{4})\b",
    re.IGNORECASE,
)

KW_CASH_OUT = ("снятие наличных", "выдача наличных", "снятие нал")
KW_CASH_IN = ("внесение наличных", "взнос наличных", "пополнение наличными")
KW_REFUND = ("возврат", "отмена покупки", "отмена операции")
KW_IN = ("зачисление", "зачислен", "пополнение", "поступление", "перевод от", "вам перевели", "получен перевод",
         "входящий перевод", "зарплата", "аванс", "кэшбэк", "кешбэк", "cashback", "проценты по вкладу", "+")
KW_OUT = ("покупка", "оплата", "списание", "списан", "перевод", "снятие", "платёж", "платеж", "оплачено",
          "автоплатёж", "автоплатеж", "покупку", "оплатили")
KW_DECLINE = ("отказ", "отклонен", "отклонён", "недостаточно средств", "операция не выполнена", "код подтверждения",
              "код для", "никому не сообщайте", "пароль")
STOP_WORDS_AFTER_MERCHANT = re.compile(
    r"\b(баланс|доступно|остаток|карта|счёт|счет|комиссия|подробнее)\b.*$", re.IGNORECASE)


@dataclass
class ParsedOp:
    amount_minor: int
    direction: str  # out | in
    flow_type: str
    currency: str = "RUB"
    merchant: str | None = None
    card_last4: str | None = None
    balance_minor: int | None = None
    occurred_at: datetime | None = None
    bank: str | None = None
    kind_hint: str | None = None  # purchase | transfer | salary | refund | cash
    ambiguous: bool = False
    confidence: float = 0.5
    notes: list[str] = field(default_factory=list)

    def as_dict(self) -> dict:
        d = asdict(self)
        if self.occurred_at:
            d["occurred_at"] = self.occurred_at.isoformat()
        return d


def _currency(raw: str | None) -> str:
    if not raw:
        return "RUB"
    r = raw.lower()
    if r in ("$", "usd"):
        return "USD"
    if r in ("€", "eur"):
        return "EUR"
    return "RUB"


def _has(text: str, words: tuple[str, ...]) -> bool:
    return any(w in text for w in words)


OP_TIME_RE = re.compile(r"(\d{2})\.(\d{2})\.(\d{4}),?\s+(\d{1,2}):(\d{2})")


def parse_notification(package: str | None, title: str | None, text: str,
                       posted_at: datetime | None = None, tz_name: str | None = None) -> ParsedOp | None:
    full = " ".join(p for p in (title, text) if p).strip()
    low = full.lower()
    if not full or _has(low, KW_DECLINE):
        return None
    # Mir Pay: "Сумма 2 554,30 ₽. 26.10.2026, 14:14. Карта Mir •••• 0617" — the purchase time is in the
    # text and may differ from the moment the notification was posted.
    occurred = posted_at
    tm = OP_TIME_RE.search(full)
    if tm:
        try:
            occurred = datetime(int(tm.group(3)), int(tm.group(2)), int(tm.group(1)), int(tm.group(4)),
                                int(tm.group(5)), tzinfo=tz(tz_name))
            # A time in the text later than the post time is a parse slip, not a future purchase.
            if posted_at is not None and occurred > posted_at + timedelta(minutes=5):
                occurred = posted_at
        except ValueError:
            occurred = posted_at
        full_for_amount = OP_TIME_RE.sub(" ", full)
    else:
        full_for_amount = full

    balance_minor = None
    full = full_for_amount
    bal = BALANCE_RE.search(full)
    scan = full
    if bal:
        try:
            balance_minor = to_minor(bal.group("amt"))
        except ValueError:
            balance_minor = None
        scan = full[:bal.start()] + " " + full[bal.end():]

    amount_match = AMOUNT_RE.search(scan)
    if not amount_match:
        return None
    try:
        amount_minor = to_minor(amount_match.group("amt"))
    except ValueError:
        return None
    if amount_minor <= 0:
        return None
    sign = amount_match.group("sign")

    # Direction and meaning.
    flow, direction, kind, ambiguous = "expense", "out", "purchase", False
    if _has(low, KW_CASH_OUT):
        flow, direction, kind = "cash_withdrawal", "out", "cash"
    elif _has(low, KW_CASH_IN):
        flow, direction, kind = "cash_deposit", "in", "cash"
    elif _has(low, KW_REFUND):
        flow, direction, kind = "refund", "in", "refund"
    elif sign == "+" or any(w in low for w in KW_IN if w != "+"):
        flow, direction = "income", "in"
        if "перевод" in low or "пополнение" in low:
            kind, ambiguous = "transfer", True  # may be our own money from another account
        elif "зарплат" in low or "аванс" in low:
            kind = "salary"
        else:
            kind = "income"
    elif _has(low, KW_OUT):
        if "перевод" in low and "покупка" not in low and "оплата" not in low:
            kind, ambiguous = "transfer", True  # to a person or to our own account
        flow, direction = "expense", "out"
    elif sign in ("-", "−"):
        flow, direction = "expense", "out"
    else:
        ambiguous = True

    card = None
    m = CARD_RE.search(full)
    if m:
        card = m.group(1) or m.group(2)

    merchant = _extract_merchant(full, amount_match, bal)
    confidence = 0.55
    if card:
        confidence += 0.1
    if merchant:
        confidence += 0.15
    if not ambiguous:
        confidence += 0.15

    return ParsedOp(
        amount_minor=amount_minor, direction=direction, flow_type=flow, currency=_currency(amount_match.group("cur")),
        merchant=merchant, card_last4=card, balance_minor=balance_minor, occurred_at=occurred,
        bank=BANK_BY_PACKAGE.get(package or "", None), kind_hint=kind, ambiguous=ambiguous,
        confidence=round(min(confidence, 0.95), 2),
    )


def _extract_merchant(full: str, amount_match: re.Match, balance_match: re.Match | None) -> str | None:
    # 1) "... в <MERCHANT>" / "... у <MERCHANT>"
    m = re.search(r"\b(?:в|во|у)\s+([A-Za-zА-Яа-яЁё0-9\"'«»&.\- ]{3,60})", full[amount_match.end():])
    candidate = None
    if m:
        candidate = m.group(1)
    else:
        # 2) The text right after the amount (Sber SMS, T-Bank push).
        tail = full[amount_match.end():]
        if balance_match and balance_match.start() > amount_match.end():
            tail = full[amount_match.end():balance_match.start()]
        tail = tail.strip(" .,:;-–—\n")
        candidate = tail.split(". ")[0] if tail else None
    if not candidate:
        return None
    candidate = STOP_WORDS_AFTER_MERCHANT.sub("", candidate).strip(" .,:;-–—\"'«»")
    if len(candidate) < 2 or re.fullmatch(r"[\d\s.,:*]+", candidate):
        return None
    return candidate[:80]
