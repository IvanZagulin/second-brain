"""Categorisation: user-learned rules first, then built-in merchant/MCC hints, then (in the
background) Claude Haiku for the rest. Corrections become rules and are applied retroactively."""

from __future__ import annotations

import re
import uuid
from typing import Any

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models import BudgetCategory, CategoryRule, Transaction

DEFAULT_CATEGORIES: list[dict[str, Any]] = [
    {"code": "products", "name": "Продукты", "icon": "basket", "color": "#9CC5A1"},
    {"code": "cafe", "name": "Кафе и рестораны", "icon": "coffee", "color": "#E8B38A"},
    {"code": "transport", "name": "Транспорт", "icon": "bus", "color": "#8FB3D9"},
    {"code": "subscriptions", "name": "Подписки", "icon": "repeat", "color": "#B7A4D8"},
    {"code": "entertainment", "name": "Развлечения", "icon": "ticket", "color": "#F0C987"},
    {"code": "games", "name": "Игры и донаты", "icon": "gamepad", "color": "#E58C7A", "is_impulse": True},
    {"code": "clothes", "name": "Одежда", "icon": "shirt", "color": "#D9A5B3"},
    {"code": "health", "name": "Здоровье", "icon": "heart", "color": "#F2A7A7"},
    {"code": "sport", "name": "Спорт", "icon": "dumbbell", "color": "#F4C3A0"},
    {"code": "tech", "name": "Техника", "icon": "device", "color": "#A9B8C9"},
    {"code": "home", "name": "Дом", "icon": "home", "color": "#C8B79C"},
    {"code": "gifts", "name": "Подарки", "icon": "gift", "color": "#E7A8C8"},
    {"code": "work", "name": "Рабочие расходы", "icon": "briefcase", "color": "#A3B8A0"},
    {"code": "business", "name": "Расходы ИП", "icon": "building", "color": "#8FA88A"},
    {"code": "taxes", "name": "Налоги", "icon": "receipt", "color": "#B9A27A"},
    {"code": "debt", "name": "Платежи по долгам", "icon": "credit", "color": "#C98B7B"},
    {"code": "people", "name": "Переводы людям", "icon": "users", "color": "#B4B4D6"},
    {"code": "transfers", "name": "Переводы между своими счетами", "icon": "swap", "color": "#C9C4BC",
     "kind": "transfer"},
    {"code": "other", "name": "Другое", "icon": "dots", "color": "#CFC8BE"},
    {"code": "salary", "name": "Зарплата", "icon": "wallet", "color": "#8CC09A", "kind": "income"},
    {"code": "business_income", "name": "Доход ИП", "icon": "building", "color": "#7DB38A", "kind": "income"},
    {"code": "extra_income", "name": "Дополнительные поступления", "icon": "plus", "color": "#9DCBA8",
     "kind": "income"},
    {"code": "cashback", "name": "Кэшбэк и проценты", "icon": "percent", "color": "#B5D6BC", "kind": "income"},
    {"code": "refund", "name": "Возвраты", "icon": "undo", "color": "#C2DCC7", "kind": "income"},
    {"code": "other_income", "name": "Прочие доходы", "icon": "dots", "color": "#CFE3D3", "kind": "income"},
]

MERCHANT_HINTS: list[tuple[str, str]] = [
    (r"пят[её]рочк|pyaterochk|магнит|magnit|перекр[её]ст|perekrestok|лента|lenta|вкусвилл|vkusvill|ашан|auchan|"
     r"дикси|dixy|spar|окей|okey|metro c&c|самокат|samokat|яндекс лавка|lavka|азбука вкуса|fix price|светофор|"
     r"верный|пятерочк|магнолия|globus|глобус|чижик", "products"),
    (r"kfc|ростикс|вкусно.{0,3}точка|burger|бургер|mcdonald|теремок|шоколадниц|coffee|кофе|starbucks|"
     r"додо|dodo|pizza|пицц|суши|sushi|кафе|cafe|ресторан|restaurant|столов|delivery club|яндекс еда|eda\.yandex|"
     r"cofix|surf coffee|stars coffee", "cafe"),
    (r"yandex\s?go|яндекс\s?go|taxi|такси|uber|ситимобил|metro|метро|тройк|troika|mosgortrans|мосгортранс|"
     r"транспорт|ржд|rzd|аэроэкспресс|whoosh|urent|автобус|бензин|азс|лукойл|lukoil|газпромнефть|роснефть",
     "transport"),
    (r"steam|playstation|psn|xbox|nintendo|epic games|google\s?play|app\s?store|apple\.com|supercell|"
     r"riot|blizzard|донат|donat|genshin|hoyoverse|mihoyo|roblox|wargaming|lesta|игр", "games"),
    (r"anthropic|claude|openai|chatgpt|gemini|google one|midjourney|perplexity|cursor|github|copilot|"
     r"spotify|яндекс плюс|yandex plus|кинопоиск|okko|ivi|netflix|youtube|telegram premium|vk музыка|boosty",
     "subscriptions"),
    (r"vpn|outline|wireguard|nordvpn|surfshark|proton", "subscriptions"),
    (r"timeweb|hetzner|selectel|aeza|reg\.ru|beget|digitalocean|vultr|linode|cloudflare|vps|vds|хостинг|domain",
     "work"),
    (r"аптек|apteka|eapteka|здравсити|горздрав|ригла|36[,.]6|клиник|стоматолог|анализ|инвитро|invitro|гемотест",
     "health"),
    (r"спортмастер|sportmaster|decathlon|декатлон|фитнес|fitness|gym|спортзал|world class|xfit|ddx|протеин",
     "sport"),
    (r"dns|мвидео|m\.video|эльдорадо|eldorado|citilink|ситилинк|re:store|технопарк|samsung", "tech"),
    (r"леруа|leroy|obi|икеа|ikea|hoff|хофф|fix price|жкх|жилищн|электроэнерг|мосэнерго|водоканал|интернет|"
     r"ростелеком|мтс|mts|билайн|beeline|мегафон|megafon|tele2|теле2|yota", "home"),
    (r"zara|h&m|uniqlo|gloria jeans|befree|ostin|o'stin|lamoda|спортмастер одежда|love republic|befree|"
     r"мегастиль|bershka|pull&bear", "clothes"),
    (r"фнс|налог|nalog|уфк|казначейств", "taxes"),
    (r"займ|zaim|микрофинанс|мфк|мкк|кредит|loan|weber|moneyman|манимен|екапуста|ekapusta|займер|zaymer|"
     r"lime-zaim|webbankir|вебзайм", "debt"),
]

MCC_HINTS: dict[str, str] = {
    "5411": "products", "5422": "products", "5441": "products", "5451": "products", "5462": "products",
    "5499": "products", "5812": "cafe", "5813": "cafe", "5814": "cafe", "4111": "transport",
    "4121": "transport", "4131": "transport", "4112": "transport", "5541": "transport", "5542": "transport",
    "5816": "games", "5815": "subscriptions", "5817": "subscriptions", "5818": "subscriptions",
    "4899": "subscriptions", "5912": "health", "8011": "health", "8021": "health", "8062": "health",
    "8099": "health", "5941": "sport", "7997": "sport", "5732": "tech", "5734": "tech", "5045": "tech",
    "5651": "clothes", "5691": "clothes", "5699": "clothes", "5661": "clothes", "5200": "home", "5211": "home",
    "5712": "home", "4900": "home", "4814": "home", "9311": "taxes", "5947": "gifts", "5992": "gifts",
    "7832": "entertainment", "7922": "entertainment", "7996": "entertainment",
}

_LEGAL = re.compile(r"\b(ооо|оао|зао|пао|ао|ип|llc|ltd|inc|gmbh|sp\.? z o\.?o\.?)\b", re.IGNORECASE)
_TAIL = re.compile(r"(\s+[A-Za-zА-Яа-яЁё\-]+)?\s+(rus|ru|russia)\.?\s*$"
                   r"|\b(moscow|moskva|москва|spb|volgograd|волгоград|g\.\s?\w+|г\.\s?\w+)\b.*$", re.IGNORECASE)


def normalize_merchant(raw: str | None) -> str | None:
    if not raw:
        return None
    s = raw.upper().replace("Ё", "Е")
    s = _LEGAL.sub(" ", s)
    s = _TAIL.sub(" ", s)
    s = re.sub(r"[*#№\"'«»().,/\\]", " ", s)
    s = re.sub(r"\b\d{3,}\b", " ", s)
    s = re.sub(r"\s+", " ", s).strip()
    return s[:60] or None


async def category_ids(db: AsyncSession, owner_id: uuid.UUID) -> dict[str, uuid.UUID]:
    rows = (await db.execute(
        select(BudgetCategory.code, BudgetCategory.id)
        .where(BudgetCategory.owner_id == owner_id, BudgetCategory.deleted_at.is_(None))
    )).all()
    return {code: cid for code, cid in rows if code}


async def ensure_default_categories(db: AsyncSession, owner_id: uuid.UUID) -> None:
    existing = await category_ids(db, owner_id)
    for i, cat in enumerate(DEFAULT_CATEGORIES):
        if cat["code"] in existing:
            continue
        db.add(BudgetCategory(owner_id=owner_id, code=cat["code"], name=cat["name"], icon=cat.get("icon"),
                              color=cat.get("color"), kind=cat.get("kind", "expense"), is_system=True,
                              is_impulse=cat.get("is_impulse", False), sort=i))
    await db.flush()


def hint_category(merchant: str | None, description: str | None, mcc: str | None) -> str | None:
    if mcc and mcc in MCC_HINTS:
        return MCC_HINTS[mcc]
    hay = " ".join(x for x in (merchant, description) if x).lower()
    if not hay:
        return None
    for pattern, code in MERCHANT_HINTS:
        if re.search(pattern, hay):
            return code
    return None


async def apply_rules(db: AsyncSession, tx: Transaction) -> bool:
    """Set category/scope/flow from rules or hints. Returns True if a category was assigned."""
    if tx.category_source == "user":
        return False
    rules = (await db.execute(
        select(CategoryRule).where(CategoryRule.owner_id == tx.owner_id).order_by(CategoryRule.priority.desc())
    )).scalars().all()
    norm = tx.merchant_norm or ""
    desc = (tx.description or "").upper()
    for rule in rules:
        pat = rule.pattern.upper()
        hit = (
            (rule.match_type == "exact" and norm == pat)
            or (rule.match_type == "contains" and (pat in norm or pat in desc))
            or (rule.match_type == "regex" and _safe_regex(rule.pattern, f"{norm} {desc}"))
        )
        if hit:
            if rule.category_id:
                tx.category_id = rule.category_id
            if rule.scope:
                tx.scope = rule.scope
            if rule.flow_type:
                tx.flow_type = rule.flow_type
            tx.category_source, tx.category_confidence = "rule", 0.95
            rule.hits += 1
            return True
    if tx.direction == "in":
        codes = await category_ids(db, tx.owner_id)
        code = {"refund": "refund", "income": None}.get(tx.flow_type)
        if code and code in codes:
            tx.category_id, tx.category_source, tx.category_confidence = codes[code], "rule", 0.9
            return True
        return False
    code = hint_category(tx.merchant, tx.description, tx.mcc)
    if code:
        codes = await category_ids(db, tx.owner_id)
        if code in codes:
            tx.category_id, tx.category_source, tx.category_confidence = codes[code], "rule", 0.8
            if code == "debt" and tx.flow_type == "expense":
                tx.flow_type = "debt_payment"
            if code == "taxes" and tx.flow_type == "expense":
                tx.flow_type = "tax"
            return True
    return False


def _safe_regex(pattern: str, text: str) -> bool:
    try:
        return re.search(pattern, text, re.IGNORECASE) is not None
    except re.error:
        return False


async def learn_correction(db: AsyncSession, tx: Transaction, *, category_id: uuid.UUID | None,
                           scope: str | None = None, flow_type: str | None = None) -> int:
    """Turn a user's correction into a rule and apply it to similar not-yet-reviewed operations."""
    if not tx.merchant_norm:
        return 0
    rule = (await db.execute(
        select(CategoryRule).where(CategoryRule.owner_id == tx.owner_id, CategoryRule.pattern == tx.merchant_norm,
                                   CategoryRule.match_type == "exact")
    )).scalar_one_or_none()
    if rule is None:
        rule = CategoryRule(owner_id=tx.owner_id, pattern=tx.merchant_norm, match_type="exact", origin="user",
                            priority=10)
        db.add(rule)
    rule.category_id = category_id
    rule.scope = scope
    rule.flow_type = flow_type
    others = (await db.execute(
        select(Transaction).where(
            Transaction.owner_id == tx.owner_id, Transaction.merchant_norm == tx.merchant_norm,
            Transaction.id != tx.id, Transaction.deleted_at.is_(None),
            (Transaction.category_source.is_(None)) | (Transaction.category_source != "user"))
    )).scalars().all()
    for other in others:
        other.category_id = category_id
        if scope:
            other.scope = scope
        if flow_type:
            other.flow_type = flow_type
        other.category_source, other.category_confidence = "rule", 0.95
    return len(others)
