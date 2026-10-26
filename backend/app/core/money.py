"""Money is stored as integer minor units (kopecks) plus an ISO currency code. Never floats."""

from __future__ import annotations

import re
from decimal import ROUND_HALF_UP, Decimal, InvalidOperation

_AMOUNT_CLEAN = re.compile(r"[\s  ]")


def to_minor(value: str | int | float | Decimal) -> int:
    if isinstance(value, int):
        return value * 100
    if isinstance(value, float):
        value = Decimal(str(value))
    if isinstance(value, str):
        cleaned = _AMOUNT_CLEAN.sub("", value).replace(",", ".").replace("−", "-").replace("–", "-")
        cleaned = re.sub(r"[^0-9.\-+]", "", cleaned)
        # "1.234.56" style thousand separators: keep only the last dot as decimal point.
        if cleaned.count(".") > 1:
            head, _, tail = cleaned.rpartition(".")
            cleaned = head.replace(".", "") + "." + tail
        try:
            value = Decimal(cleaned)
        except InvalidOperation as exc:
            raise ValueError(f"not an amount: {value!r}") from exc
    return int((value * 100).quantize(Decimal("1"), rounding=ROUND_HALF_UP))


def from_minor(minor: int) -> Decimal:
    return (Decimal(minor) / 100).quantize(Decimal("0.01"))


def fmt_rub(minor: int, with_kopecks: bool = False) -> str:
    sign = "-" if minor < 0 else ""
    value = abs(minor)
    rub, kop = divmod(value, 100)
    rub_s = f"{rub:,}".replace(",", " ")
    if with_kopecks and kop:
        return f"{sign}{rub_s},{kop:02d} ₽"
    return f"{sign}{rub_s} ₽"
