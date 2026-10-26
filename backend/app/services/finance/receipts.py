"""Receipts: fiscal QR codes (t=…&s=…&fn=…&i=…&fp=…&n=…), photos/PDF via Claude vision (only when
image analysis is allowed), optional item lookup through proverkacheka.com when a token is set.
A QR code alone gives total/time/fiscal ids — not necessarily the item list."""

from __future__ import annotations

import base64
import hashlib
import logging
import uuid
from datetime import datetime, timedelta
from pathlib import Path
from typing import Any
from urllib.parse import parse_qs

import httpx
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.errors import ApiError
from app.core.money import to_minor
from app.core.timeutil import tz
from app.models import Attachment, Receipt, ReceiptItem, Transaction, User
from app.services.finance.matching import ingest_evidence
from app.services.settings import get_setting

log = logging.getLogger(__name__)


def parse_fiscal_qr(raw: str, tz_name: str | None) -> dict[str, Any]:
    qs = {k.lower(): v[0] for k, v in parse_qs(raw.strip()).items()}
    if not {"t", "s", "fn"} <= qs.keys():
        raise ApiError("Это не фискальный QR-код чека", code="not_fiscal_qr")
    t = qs["t"].upper()
    for fmt in ("%Y%m%dT%H%M%S", "%Y%m%dT%H%M"):
        try:
            dt = datetime.strptime(t, fmt).replace(tzinfo=tz(tz_name))
            break
        except ValueError:
            dt = None
    if dt is None:
        raise ApiError("Не удалось прочитать дату в QR-коде", code="bad_qr_date")
    return {"occurred_at": dt, "total_minor": to_minor(qs["s"]), "fn": qs.get("fn"), "fd": qs.get("i"),
            "fp": qs.get("fp"), "op_type": qs.get("n", "1"), "raw": raw.strip()}


async def receipt_from_qr(db: AsyncSession, owner_id: uuid.UUID, raw: str) -> Receipt:
    user = await db.get(User, owner_id)
    q = parse_fiscal_qr(raw, user.timezone if user else None)
    existing = (await db.execute(
        select(Receipt).where(Receipt.owner_id == owner_id, Receipt.fn == q["fn"], Receipt.fd == q["fd"],
                              Receipt.fp == q["fp"], Receipt.deleted_at.is_(None))
    )).scalar_one_or_none()
    if existing:
        return existing
    receipt = Receipt(owner_id=owner_id, occurred_at=q["occurred_at"], total_minor=q["total_minor"], fn=q["fn"],
                      fd=q["fd"], fp=q["fp"], op_type=q["op_type"], qr_raw=q["raw"], origin="qr", status="partial")
    db.add(receipt)
    await db.flush()
    await _link_transaction(db, receipt)
    fin = await get_setting(db, owner_id, "finance")
    token = fin.get("proverkacheka_token")
    if token:
        await _load_items_proverkacheka(db, receipt, token)
    return receipt


async def _link_transaction(db: AsyncSession, receipt: Receipt) -> None:
    if not receipt.total_minor or not receipt.occurred_at:
        return
    direction = "in" if receipt.op_type == "2" else "out"  # n=2 is a refund
    fp = hashlib.sha256(f"receipt|{receipt.fn}|{receipt.fd}|{receipt.fp}|{receipt.total_minor}".encode()).hexdigest()
    res = await ingest_evidence(
        db, receipt.owner_id, kind="receipt", fingerprint=fp, amount_minor=receipt.total_minor, direction=direction,
        flow_type="refund" if direction == "in" else "expense", occurred_at=receipt.occurred_at, has_time=True,
        merchant=receipt.store, description="Чек", parsed={"receipt_id": str(receipt.id)},
    )
    if res.transaction_id:
        receipt.transaction_id = res.transaction_id
        tx = await db.get(Transaction, res.transaction_id)
        if tx:
            tx.receipt_id = receipt.id


async def _load_items_proverkacheka(db: AsyncSession, receipt: Receipt, token: str) -> None:
    try:
        async with httpx.AsyncClient(timeout=30) as client:
            resp = await client.post("https://proverkacheka.com/api/v1/check/get",
                                     data={"token": token, "qrraw": receipt.qr_raw})
        payload = resp.json()
    except Exception as exc:
        log.warning("proverkacheka failed: %s", type(exc).__name__)
        return
    if payload.get("code") != 1:
        receipt.data = {**(receipt.data or {}), "lookup_error": payload.get("data")}
        return
    j = payload.get("data", {}).get("json", {})
    receipt.store = j.get("user") or j.get("retailPlace") or receipt.store
    receipt.inn = j.get("userInn")
    for item in j.get("items", []):
        db.add(ReceiptItem(owner_id=receipt.owner_id, receipt_id=receipt.id, name=item.get("name", "")[:300],
                           quantity=item.get("quantity", 1), price_minor=item.get("price"),
                           sum_minor=item.get("sum")))
    receipt.status = "items_loaded"
    if receipt.transaction_id:
        tx = await db.get(Transaction, receipt.transaction_id)
        if tx and not tx.merchant and receipt.store:
            from app.services.finance.categorize import normalize_merchant
            tx.merchant, tx.merchant_norm = receipt.store, normalize_merchant(receipt.store)


RECEIPT_SCHEMA = {
    "type": "object",
    "properties": {
        "is_receipt": {"type": "boolean"},
        "store": {"type": ["string", "null"]},
        "datetime": {"type": ["string", "null"], "description": "ISO 8601 local time"},
        "total": {"type": ["number", "null"]},
        "fn": {"type": ["string", "null"]},
        "fd": {"type": ["string", "null"]},
        "fp": {"type": ["string", "null"]},
        "items": {
            "type": "array",
            "items": {
                "type": "object",
                "properties": {"name": {"type": "string"}, "quantity": {"type": "number"},
                               "price": {"type": ["number", "null"]}, "sum": {"type": ["number", "null"]},
                               "discount": {"type": ["number", "null"]}},
                "required": ["name", "quantity", "price", "sum", "discount"],
                "additionalProperties": False,
            },
        },
    },
    "required": ["is_receipt", "store", "datetime", "total", "fn", "fd", "fp", "items"],
    "additionalProperties": False,
}


async def receipt_from_attachment(db: AsyncSession, owner_id: uuid.UUID, attachment: Attachment) -> Receipt:
    from app.services.llm.client import call_json

    privacy = await get_setting(db, owner_id, "privacy")
    if not privacy.get("allow_image_analysis"):
        raise ApiError("Анализ изображений через Claude выключен в настройках приватности",
                       code="image_analysis_disabled", status_code=403)
    data = Path(attachment.path).read_bytes()
    b64 = base64.standard_b64encode(data).decode()
    block = ({"type": "document", "source": {"type": "base64", "media_type": "application/pdf", "data": b64}}
             if attachment.mime == "application/pdf" else
             {"type": "image", "source": {"type": "base64", "media_type": attachment.mime, "data": b64}})
    parsed = await call_json(
        owner_id, "vision", system="Ты извлекаешь данные из кассовых чеков. Отвечай строго по схеме. "
        "Если поле не видно — null. Ничего не придумывай.",
        user=[block, {"type": "text", "text": "Извлеки данные чека."}], schema=RECEIPT_SCHEMA, max_tokens=4000)
    if not parsed.get("is_receipt"):
        raise ApiError("На изображении не найден чек", code="not_a_receipt")
    user = await db.get(User, owner_id)
    occurred = None
    if parsed.get("datetime"):
        try:
            occurred = datetime.fromisoformat(parsed["datetime"])
            if occurred.tzinfo is None:
                occurred = occurred.replace(tzinfo=tz(user.timezone if user else None))
        except ValueError:
            occurred = None
    receipt = Receipt(owner_id=owner_id, store=parsed.get("store"), occurred_at=occurred or attachment.created_at,
                      total_minor=to_minor(str(parsed["total"])) if parsed.get("total") is not None else None,
                      fn=parsed.get("fn"), fd=parsed.get("fd"), fp=parsed.get("fp"),
                      origin="pdf" if attachment.mime == "application/pdf" else "photo", attachment_id=attachment.id,
                      status="items_loaded" if parsed.get("items") else "partial", data={"llm": True})
    db.add(receipt)
    await db.flush()
    for item in parsed.get("items", []):
        db.add(ReceiptItem(owner_id=owner_id, receipt_id=receipt.id, name=item["name"][:300],
                           quantity=item.get("quantity") or 1,
                           price_minor=to_minor(str(item["price"])) if item.get("price") is not None else None,
                           sum_minor=to_minor(str(item["sum"])) if item.get("sum") is not None else None,
                           discount_minor=to_minor(str(item["discount"])) if item.get("discount") else None))
    await _link_transaction(db, receipt)
    return receipt
