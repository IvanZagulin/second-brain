"""Unit tests for logic that needs no database: money, parsers, statements, budget maths helpers,
recurrence, model routing and pricing, tool schemas."""

from datetime import date, datetime, timedelta, timezone
from decimal import Decimal

import pytest

from agent.jobs import next_occurrence
from agent.persona import build_system_prompt, crisis_signal
from agent.tools import REGISTRY, tool_definitions
from app.core.money import fmt_rub, to_minor
from app.core.timeutil import in_quiet_hours, tz
from app.services.finance.categorize import hint_category, normalize_merchant
from app.services.finance.engine import _advance, next_income_date
from app.services.finance.parsers import parse_notification
from app.services.finance.receipts import parse_fiscal_qr
from app.services.fitness_catalog import DEFAULT_EXERCISES
from app.services.finance.statements import _fingerprints, parse_1c, parse_csv, parse_payment_receipt, \
    parse_sber_certificate
from app.services.llm.budget import choose_model, usage_cost
from app.services.llm.pricing import cost_usd

UTC = timezone.utc


@pytest.mark.parametrize("raw,minor", [
    ("2 554,30", 255430), ("38", 3800), ("10 000.00", 1000000), ("-450,5", -45050), ("−120", -12000),
    ("1.234.56", 123456), (Decimal("0.01"), 1), (5, 500),
])
def test_to_minor(raw, minor):
    assert to_minor(raw) == minor


def test_fmt_rub():
    assert fmt_rub(1234567) == "12 345 ₽"
    assert fmt_rub(-5000) == "-50 ₽"


def test_mirpay_notification_uses_time_from_text():
    posted = datetime(2026, 10, 26, 11, 49, tzinfo=UTC)  # 14:49 MSK
    op = parse_notification("ru.nspk.mirpay", "Оплата покупки", "Сумма 2 554,30 ₽. 26.10.2026, 14:14. Карта Mir •••• 0617",
                            posted, "Europe/Moscow")
    assert op.amount_minor == 255430 and op.direction == "out" and op.flow_type == "expense"
    assert op.card_last4 == "0617" and op.merchant is None
    assert op.occurred_at == datetime(2026, 10, 26, 14, 14, tzinfo=tz("Europe/Moscow"))


@pytest.mark.parametrize("text,amount,direction,flow,merchant,balance", [
    ("Покупка 450р PYATEROCHKA Баланс: 10 000,55р", 45000, "out", "expense", "PYATEROCHKA", 1000055),
    ("Покупка, карта *1234. 450 RUB. MAGNIT MM. Доступно 1000 RUB", 45000, "out", "expense", "MAGNIT MM", 100000),
    ("Зачисление зарплаты 13 000 ₽", 1300000, "in", "income", None, None),
    ("Возврат 300 ₽ OZON", 30000, "in", "refund", None, None),
    ("Снятие наличных 5 000 ₽", 500000, "out", "cash_withdrawal", None, None),
])
def test_bank_notifications(text, amount, direction, flow, merchant, balance):
    op = parse_notification("ru.sberbankmobile", None, text, datetime.now(UTC), "Europe/Moscow")
    assert (op.amount_minor, op.direction, op.flow_type) == (amount, direction, flow)
    if merchant:
        assert op.merchant == merchant
    assert op.balance_minor == balance


@pytest.mark.parametrize("text", ["Ваш код подтверждения 1234", "Операция отклонена: недостаточно средств 500 ₽", ""])
def test_ignored_notifications(text):
    assert parse_notification("ru.sberbankmobile", None, text, datetime.now(UTC)) is None


def test_incoming_transfer_is_ambiguous():
    op = parse_notification("ru.sberbankmobile", None, "Перевод от Иван И. +5 000 ₽", datetime.now(UTC))
    assert op.direction == "in" and op.ambiguous


def test_sber_certificate():
    text = ("Справка по операции\nПАО «Сбербанк» сообщает¹, что указанная ниже операция списания была\n"
            "совершена по платёжному счёту •• 0296, держателем которого является\nИван И.\n"
            "Описание Статус операции\nАпрель Платёж выполнен\n(APTEKA APREL Volgograd RUS)\n"
            "Операция совершена Тип операции\n26 октября 2026 в 14:14 Оплата товаров и услуг\n"
            "Сумма в валюте счёта Сумма в валюте операции²\n2 554,30 ₽ 2 554,30 ₽\nКод авторизации\n173898\n")
    data = parse_sber_certificate(text, "Europe/Moscow")
    row = data.rows[0]
    assert data.account_hint == "0296"
    assert (row.amount_minor, row.direction, row.bank_op_id) == (255430, "out", "173898")
    assert row.merchant == "APTEKA APREL Volgograd RUS"
    assert row.occurred_at.hour == 14 and row.occurred_at.minute == 14
    assert normalize_merchant(row.merchant) == "APTEKA APREL"
    assert hint_category(row.merchant, row.description, None) == "health"


def test_payment_receipt_tax():
    text = ("ОПЛАЧЕНО\nДата: 06.10.26\nБанк: ООО НКО «ЮМани»\nНАЗНАЧЕНИЕ ПЛАТЕЖА ЕДИНЫЙ НАЛОГОВЫЙ ПЛАТЕЖ\n"
            "СУММА 390 P.\nПОЛУЧАТЕЛЬ КАЗНАЧЕЙСТВО РОССИИ (ФНС РОССИИ)\n")
    row = parse_payment_receipt(text, "Europe/Moscow").rows[0]
    assert row.amount_minor == 39000 and row.flow_hint == "tax" and row.occurred_at.date() == date(2026, 10, 6)


def test_tbank_csv_and_fingerprints():
    csv_text = ("Дата операции;Дата платежа;Номер карты;Статус;Сумма операции;Валюта операции;Кэшбэк;Категория;MCC;Описание\n"
                "26.10.2026 14:14:00;26.10.2026;*1234;OK;-450,00;RUB;;Супермаркеты;5411;Пятёрочка\n"
                "26.10.2026 14:14:00;26.10.2026;*1234;OK;-450,00;RUB;;Супермаркеты;5411;Пятёрочка\n"
                "26.10.2026 15:00:00;26.10.2026;*1234;FAILED;-99,00;RUB;;Прочее;;Отказ\n"
                "25.10.2026 10:00:00;25.10.2026;*1234;OK;13000,00;RUB;;Пополнения;;Зарплата\n")
    data = parse_csv(csv_text.encode("cp1251"), "Europe/Moscow")
    assert data.bank == "tbank" and data.account_hint == "1234"
    assert [r.amount_minor for r in data.rows] == [45000, 45000, 1300000]
    assert data.rows[0].mcc == "5411" and data.rows[2].direction == "in"
    _fingerprints(data.bank, data.account_hint, data.rows)
    # two genuine identical purchases stay distinct, and re-parsing yields the same keys
    assert data.rows[0].fingerprint != data.rows[1].fingerprint
    again = parse_csv(csv_text.encode("cp1251"), "Europe/Moscow")
    _fingerprints(again.bank, again.account_hint, again.rows)
    assert [r.fingerprint for r in again.rows] == [r.fingerprint for r in data.rows]


def test_1c_exchange():
    text = ("1CClientBankExchange\nРасчСчет=40802810800000000001\nСекцияДокумент=Платежное поручение\nДата=29.06.2026\n"
            "Сумма=10000.00\nПлательщикСчет=40702810000000000002\nПлательщик1=ООО КЛИЕНТ\n"
            "ПолучательСчет=40802810800000000001\nНазначениеПлатежа=Оплата по счету 40\nКонецДокумента\n"
            "СекцияДокумент=Платежное поручение\nДата=30.06.2026\nСумма=2948.43\n"
            "ПлательщикСчет=40802810800000000001\nПолучатель1=ИВАНОВ ИВАН\nПолучательСчет=40817810000000000003\n"
            "НазначениеПлатежа=Перевод собственных средств\nКонецДокумента\n")
    data = parse_1c(text.encode("cp1251"), "Europe/Moscow")
    assert [(r.direction, r.amount_minor) for r in data.rows] == [("in", 1000000), ("out", 294843)]


def test_fiscal_qr():
    q = parse_fiscal_qr("t=20261026T1414&s=2554.30&fn=7281440500123456&i=12345&fp=1234567890&n=1", "Europe/Moscow")
    assert q["total_minor"] == 255430 and q["fd"] == "12345" and q["occurred_at"].hour == 14


def test_next_income_date():
    assert next_income_date(date(2026, 10, 26), [5, 20]) == date(2026, 11, 5)
    assert next_income_date(date(2026, 11, 5), [5, 20]) == date(2026, 11, 20)
    assert next_income_date(date(2026, 2, 27), [30]) == date(2026, 2, 28)
    assert next_income_date(date(2026, 10, 26), []) == date(2026, 11, 1)


def test_subscription_advance():
    assert _advance(date(2026, 1, 31), "monthly", None) == date(2026, 2, 28)
    assert _advance(date(2026, 1, 1), "yearly", None) == date(2027, 1, 1)
    assert _advance(date(2026, 1, 1), "days", 10) == date(2026, 1, 11)


def test_recurrence():
    after = datetime(2026, 10, 26, 18, 0, tzinfo=UTC)  # Monday 21:00 MSK
    assert next_occurrence("every:15m", after, "Europe/Moscow") == after + timedelta(minutes=15)
    d = next_occurrence("daily@07:40", after, "Europe/Moscow").astimezone(tz("Europe/Moscow"))
    assert (d.day, d.hour, d.minute) == (27, 7, 40)
    w = next_occurrence("weekly:7@19:00", after, "Europe/Moscow").astimezone(tz("Europe/Moscow"))
    assert w.isoweekday() == 7 and w.hour == 19
    m = next_occurrence("monthly:5@10:00", after, "Europe/Moscow").astimezone(tz("Europe/Moscow"))
    assert (m.month, m.day) == (11, 5)


def test_quiet_hours_wrap_midnight():
    zone = tz("Europe/Moscow")
    assert in_quiet_hours(datetime(2026, 1, 1, 23, 45, tzinfo=zone), "23:30", "07:00")
    assert in_quiet_hours(datetime(2026, 1, 1, 3, 0, tzinfo=zone), "23:30", "07:00")
    assert not in_quiet_hours(datetime(2026, 1, 1, 12, 0, tzinfo=zone), "23:30", "07:00")


def test_model_degradation_near_cap():
    models = {"chat": "claude-sonnet-5-5", "analysis": "claude-opus-5-5", "background": "claude-haiku-5-5"}
    efforts = {"chat": "medium", "analysis": "high", "background": "low"}
    cfg = {"degrade": True, "analysis_downgrade_ratio": 0.8, "chat_downgrade_ratio": 0.95}
    assert choose_model(models, efforts, cfg, "analysis", 0.5) == ("claude-opus-5-5", "high")
    assert choose_model(models, efforts, cfg, "analysis", 0.85)[0] == "claude-sonnet-5-5"
    assert choose_model(models, efforts, cfg, "chat", 0.96) == ("claude-haiku-5-5", "low")


def test_pricing():
    assert cost_usd("claude-sonnet-5-5", 1_000_000, 0) == Decimal("2.000000")
    assert cost_usd("claude-haiku-5-5", 50_000, 1_000_000) == Decimal("0.505000")
    # prompts above 100K input tokens move Haiku 5.5 to the long-context tier
    assert cost_usd("claude-haiku-5-5", 1_000_000, 1_000_000) == Decimal("3.000000")
    # unknown ids are priced conservatively high
    assert cost_usd("claude-unknown", 1_000_000, 0) >= Decimal("10")

    class It:
        def __init__(self, model, i, o):
            self.model, self.input_tokens, self.output_tokens = model, i, o
            self.cache_read_input_tokens = self.cache_creation_input_tokens = 0

    class U:
        input_tokens, output_tokens, cache_read_input_tokens, cache_creation_input_tokens = 2000, 2000, 0, 0
        iterations = [It(None, 1000, 1000), It("claude-opus-4-8", 1000, 1000)]

    cost, totals = usage_cost("claude-sonnet-5-5", U())
    assert cost == cost_usd("claude-sonnet-5-5", 1000, 1000) + cost_usd("claude-opus-4-8", 1000, 1000)
    assert totals["input_tokens"] == 2000


def test_tool_definitions_are_stable_and_valid():
    defs = tool_definitions()
    names = [d["name"] for d in defs]
    assert names == sorted(names) and "message_user" not in names
    assert "message_user" in [d["name"] for d in tool_definitions(include_job_only=True)]
    for d in defs:
        assert d["input_schema"]["type"] == "object"
        assert "title" not in d["input_schema"]
    assert REGISTRY["draft_message_to_person"].min_level == "C"
    assert REGISTRY["delete_memories"].min_level == "C"


def test_persona():
    prompt = build_system_prompt(None, ["Не называть меня по фамилии"], "Иван")
    assert "Владельца зовут Иван" in prompt and "Не называть меня по фамилии" in prompt and "112" in prompt
    assert crisis_signal("я больше не хочу жить") and not crisis_signal("хочу жить у моря")


def test_exercise_catalog():
    names = [n.lower() for n, *_ in DEFAULT_EXERCISES]
    assert len(names) == len(set(names))
    groups = {"back", "biceps", "shoulders", "chest", "triceps", "legs", "core", "cardio", "other"}
    for _, group, _, secondary in DEFAULT_EXERCISES:
        assert group in groups and set(secondary) <= groups
    assert {g for _, g, _, _ in DEFAULT_EXERCISES} >= {"back", "biceps", "shoulders", "chest", "triceps", "legs"}


def test_telegram_html_and_claude_memory_imports(tmp_path):
    import zipfile
    from app.services.imports import adapters

    page = """<div class="page_header"><div class="content"><div class="text bold">Аня</div></div></div>
<div class="history">
 <div class="message service" id="message-1"><div class="body details">29 марта 2021</div></div>
 <div class="message default clearfix" id="message1"><div class="body">
  <div class="pull_right date details" title="29.03.2021 12:53:11 GMT+03:00">12:53</div>
  <div class="from_name">Аня</div><div class="text">Привет<br>как дела</div></div></div>
 <div class="message default clearfix joined" id="message2"><div class="body">
  <div class="pull_right date details" title="29.03.2021 12:53:20 UTC+03:00">12:53</div>
  <div class="text">ты где?</div></div></div>
 <div class="message default clearfix" id="message3"><div class="body">
  <div class="pull_right date details" title="29.03.2021 12:54:00 GMT+03:00">12:54</div>
  <div class="from_name">Иван</div><div class="media_wrap clearfix"><div class="title bold">Photo</div></div></div></div>
</div>"""
    page2 = """<div class="history"><div class="message default clearfix joined" id="message4"><div class="body">
  <div class="pull_right date details" title="30.03.2021 09:00:00 GMT+03:00">09:00</div>
  <div class="text">утро</div></div></div></div>"""
    tg = tmp_path / "tg.zip"
    with zipfile.ZipFile(tg, "w") as z:
        z.writestr("ChatExport/messages2.html", page2)
        z.writestr("ChatExport/messages.html", page)
    assert adapters.detect(tg) == "telegram_html"
    (chat,) = list(adapters.telegram_html_chats(tg))
    msgs = list(chat.messages)
    assert chat.title == "Аня" and [m.external_id for m in msgs] == ["1", "2", "4"]  # media-only message skipped
    assert msgs[0].text == "Привет\nкак дела" and msgs[0].sent_at.isoformat() == "2021-03-29T12:53:11+03:00"
    assert msgs[1].author == "Аня"  # joined message keeps the previous sender
    assert msgs[2].author == "Иван" and msgs[2].author_id == "name:Иван"  # continues across pages

    mem = tmp_path / "claude-memory.zip"
    with zipfile.ZipFile(mem, "w") as z:
        z.writestr("claude-memory/profile.md", "# Профиль\nЖивёт в Волгограде")
        z.writestr("claude-memory/topics/pets.md", "Собака")
        z.writestr("claude-memory/empty.md", "   ")
    assert adapters.detect(mem) == "claude_memory"
    (chat,) = list(adapters.claude_memory_chats(mem))
    notes = list(chat.messages)
    assert [n.external_id for n in notes] == ["profile.md", "topics/pets.md"]
    assert notes[0].text.startswith("[Заметка памяти Claude: profile.md]") and notes[0].is_self is False
