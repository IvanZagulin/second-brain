package ru.secondbrain.app.ui.finance

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Button
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import ru.secondbrain.app.core.decimal
import ru.secondbrain.app.core.local
import ru.secondbrain.app.core.nowIso
import ru.secondbrain.app.core.parseDate
import ru.secondbrain.app.core.parseInstant
import ru.secondbrain.app.core.parseRubToMinor
import ru.secondbrain.app.core.relative
import ru.secondbrain.app.core.ruDate
import ru.secondbrain.app.core.rub
import ru.secondbrain.app.data.model.Account
import ru.secondbrain.app.data.model.BudgetCategory
import ru.secondbrain.app.data.model.Debt
import ru.secondbrain.app.data.model.DebtPayment
import ru.secondbrain.app.data.model.E
import ru.secondbrain.app.data.model.FLOW_LABELS
import ru.secondbrain.app.data.model.FinancialGoal
import ru.secondbrain.app.data.model.Subscription
import ru.secondbrain.app.data.model.TaxPayment
import ru.secondbrain.app.data.model.Transaction
import ru.secondbrain.app.data.model.WishlistItem
import ru.secondbrain.app.data.repo.jsonOf
import ru.secondbrain.app.data.repo.str
import ru.secondbrain.app.ui.components.AppCard
import ru.secondbrain.app.ui.components.BigNumber
import ru.secondbrain.app.ui.components.ChoiceChips
import ru.secondbrain.app.ui.components.ConfirmDialog
import ru.secondbrain.app.ui.components.DateField
import ru.secondbrain.app.ui.components.Divider
import ru.secondbrain.app.ui.components.Dropdown
import ru.secondbrain.app.ui.components.EmptyState
import ru.secondbrain.app.ui.components.ErrorCard
import ru.secondbrain.app.ui.components.Field
import ru.secondbrain.app.ui.components.Gap
import ru.secondbrain.app.ui.components.Label
import ru.secondbrain.app.ui.components.ListRow
import ru.secondbrain.app.ui.components.Loading
import ru.secondbrain.app.ui.components.LocalBack
import ru.secondbrain.app.ui.components.LocalContainer
import ru.secondbrain.app.ui.components.LocalNavigate
import ru.secondbrain.app.ui.components.Markdown
import ru.secondbrain.app.ui.components.Pill
import ru.secondbrain.app.ui.components.ProgressBar
import ru.secondbrain.app.ui.components.Screen
import ru.secondbrain.app.ui.components.SectionTitle
import ru.secondbrain.app.ui.components.Secondary
import ru.secondbrain.app.ui.components.Sheet
import ru.secondbrain.app.ui.components.SwitchRow
import ru.secondbrain.app.ui.components.TimeField
import ru.secondbrain.app.ui.components.rememberAction
import ru.secondbrain.app.ui.components.rememberLoad
import ru.secondbrain.app.ui.components.toIsoStartOfDay
import ru.secondbrain.app.ui.theme.Tone
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

private fun minorText(v: Long?) = v?.let { (it / 100.0).let { d -> if (d % 1.0 == 0.0) d.toLong().toString() else "%.2f".format(d) } } ?: ""

// ----------------------------------------------------------------- transaction

@Composable
fun TransactionScreen(id: String?) {
    val c = LocalContainer.current
    val back = LocalBack.current
    val act = rememberAction()
    val existingFlow = remember(id) {
        if (id != null) c.store.observeOne(E.TRANSACTIONS, id, Transaction.serializer()) else kotlinx.coroutines.flow.flowOf(null)
    }
    val existing by existingFlow.collectAsState(initial = null)
    val accounts by c.store.observe(E.ACCOUNTS, Account.serializer()).collectAsState(initial = emptyList())
    val cats by c.store.observe(E.CATEGORIES, BudgetCategory.serializer()).collectAsState(initial = emptyList())
    var amount by remember { mutableStateOf("") }
    var direction by remember { mutableStateOf("out") }
    var flow by remember { mutableStateOf("expense") }
    var scope by remember { mutableStateOf("personal") }
    var category by remember { mutableStateOf<String?>(null) }
    var account by remember { mutableStateOf<String?>(null) }
    var merchant by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var date by remember { mutableStateOf<LocalDate?>(LocalDate.now()) }
    var time by remember { mutableStateOf<LocalTime?>(LocalTime.now().withSecond(0).withNano(0)) }
    var loaded by remember { mutableStateOf(id == null) }
    var confirmExclude by remember { mutableStateOf(false) }
    val sources = rememberLoad(id) { if (id != null) c.api.get("/api/v1/finance/transactions/$id/sources").jsonObject else null }

    LaunchedEffect(existing) {
        val t = existing ?: return@LaunchedEffect
        if (loaded) return@LaunchedEffect
        amount = minorText(t.amountMinor); direction = t.direction; flow = t.flowType; scope = t.scope
        category = t.categoryId; account = t.accountId; merchant = t.merchant ?: ""; note = t.note ?: t.description ?: ""
        t.at?.local()?.let { date = it.toLocalDate(); time = if (t.hasTime) it.toLocalTime().withSecond(0).withNano(0) else null }
        loaded = true
    }

    Screen(if (id == null) "Новая операция" else "Операция") {
        item {
            AppCard {
                Field(amount, { amount = it }, "Сумма, ₽", number = true)
                Gap(8)
                ChoiceChips(listOf("out" to "Расход", "in" to "Поступление"), direction, { direction = it; flow = if (it == "in") "income" else "expense" })
                Gap(8)
                Dropdown("Тип", FLOW_LABELS.entries.map { it.key to it.value }, flow, { flow = it })
                Gap(8)
                ChoiceChips(listOf("personal" to "Личное", "business" to "ИП"), scope, { scope = it })
                Gap(8)
                Dropdown("Категория", listOf<Pair<String?, String>>(null to "Без категории") +
                    cats.filter { it.kind == (if (direction == "in") "income" else "expense") || it.kind == "transfer" }
                        .sortedBy { it.sort }.map { it.id to it.name }, category, { category = it })
                Gap(8)
                Dropdown("Счёт", listOf<Pair<String?, String>>(null to "Не выбран") + accounts.map { it.id to it.name }, account, { account = it })
                Gap(8)
                Field(merchant, { merchant = it }, "Магазин / получатель")
                Gap(8)
                Field(note, { note = it }, "Комментарий", singleLine = false)
                Gap(8)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DateField("Дата", date, { date = it }, Modifier.weight(1f))
                    TimeField("Время", time, { time = it }, Modifier.weight(1f))
                }
            }
        }
        existing?.let { t ->
            item {
                AppCard(tint = Tone.current.finance) {
                    Label("Статус: ${statusLabel(t.status)} · источник: ${originLabel(t.origin)}")
                    if (t.categorySource == "llm") Secondary("Категорию определил агент — исправьте, если ошибся; система запомнит.")
                }
            }
        }
        sources.value?.get("items")?.jsonArray?.takeIf { it.isNotEmpty() }?.let { arr ->
            item {
                AppCard {
                    Text("Исходные данные", style = MaterialTheme.typography.titleMedium)
                    arr.forEach { s ->
                        val o = s.jsonObject
                        ListRow(originLabel(o.str("kind") ?: ""), o.str("raw_text")?.take(200) ?: o.str("package_name"))
                    }
                }
            }
        }
        item {
            Button(modifier = Modifier.then(Modifier), onClick = {
                val minor = parseRubToMinor(amount) ?: return@Button
                val at = (date ?: LocalDate.now()).atTime(time ?: LocalTime.MIDNIGHT).atZone(ZoneId.systemDefault()).toOffsetDateTime().toString()
                act(null) {
                    c.store.save(E.TRANSACTIONS, jsonOf("amount_minor" to minor, "direction" to direction, "flow_type" to flow,
                        "scope" to scope, "category_id" to category, "account_id" to account, "merchant" to merchant.ifBlank { null },
                        "note" to note.ifBlank { null }, "occurred_at" to at, "has_time" to (time != null), "currency" to "RUB") +
                        (if (id == null) jsonOf("origin" to "manual", "status" to "confirmed", "description" to note.ifBlank { null })
                        else jsonOf("category_source" to if (category != existing?.categoryId) "user" else existing?.categorySource)), id)
                    back()
                }
            }) { Text("Сохранить") }
        }
        if (id != null) item {
            OutlinedButton(onClick = { confirmExclude = true }) { Text("Исключить из учёта") }
        }
    }
    if (confirmExclude && id != null) ConfirmDialog("Исключить операцию?", "Она перестанет учитываться в бюджете (не удаляется).",
        onConfirm = { act(null) { c.store.save(E.TRANSACTIONS, jsonOf("status" to "excluded"), id); back() } },
        onDismiss = { confirmExclude = false })
}

private operator fun JsonObject.plus(other: JsonObject) = JsonObject(this.toMap() + other.toMap())

fun statusLabel(s: String) = mapOf("new" to "новая", "matched" to "найдено совпадение", "needs_review" to "требует проверки",
    "confirmed" to "подтверждена", "possible_duplicate" to "возможный дубликат", "excluded" to "исключена")[s] ?: s

fun originLabel(s: String) = mapOf("manual" to "вручную", "notification" to "уведомление банка", "statement" to "выписка",
    "receipt" to "чек", "agent" to "агент", "qr" to "QR-код")[s] ?: s

// ----------------------------------------------------------------- accounts & categories

@Composable
fun AccountsScreen() {
    val c = LocalContainer.current
    val act = rememberAction()
    val accounts by c.store.observe(E.ACCOUNTS, Account.serializer()).collectAsState(initial = emptyList())
    val summary = rememberLoad { c.api.get("/api/v1/finance/summary").jsonObject }
    val balances = summary.value?.get("accounts")?.jsonArray?.associate { it.jsonObject.str("id") to it.jsonObject } ?: emptyMap()
    var editing by remember { mutableStateOf<Account?>(null) }
    Screen("Счета", onRefresh = { c.sync.syncNow(); summary.reload() },
        fab = { ExtendedFloatingActionButton(onClick = { editing = Account(id = "") }, icon = { Icon(Icons.Default.Add, null) }, text = { Text("Счёт") }) }) {
        items(accounts.filter { !it.archived }.sortedBy { it.sort }, key = { it.id }) { a ->
            val b = balances[a.id]
            AppCard(tint = if (a.isBusiness) Tone.current.memory else Tone.current.finance, onClick = { editing = a }) {
                Text(a.name, style = MaterialTheme.typography.titleMedium)
                BigNumber(rub(b?.get("balance_minor")?.jsonPrimitive?.longOrNull ?: a.balanceMinor))
                Secondary(listOfNotNull(
                    if (b?.str("confirmed") == "true") "подтверждено" else "оценка",
                    parseInstant(a.balanceAt)?.let { "остаток от ${relative(it)}" },
                    a.cardLast4.takeIf { it.isNotEmpty() }?.joinToString(prefix = "карты ") { "•$it" },
                    if (a.isBusiness) "счёт ИП" else null).joinToString(" · "))
            }
        }
    }
    editing?.let { a ->
        Sheet({ editing = null }) {
            var name by remember { mutableStateOf(a.name) }
            var kind by remember { mutableStateOf(a.kind) }
            var cards by remember { mutableStateOf(a.cardLast4.joinToString(", ")) }
            var business by remember { mutableStateOf(a.isBusiness) }
            var include by remember { mutableStateOf(a.includeInFree) }
            var balance by remember { mutableStateOf("") }
            Text(if (a.id.isEmpty()) "Новый счёт" else "Счёт", style = MaterialTheme.typography.headlineSmall)
            Field(name, { name = it }, "Название")
            Dropdown("Тип", listOf("card" to "Карта", "current" to "Расчётный счёт", "cash" to "Наличные",
                "savings" to "Накопительный", "credit" to "Кредитная карта"), kind, { kind = it })
            Field(cards, { cards = it }, "Последние 4 цифры карт/счёта (через запятую)")
            SwitchRow("Счёт ИП", checked = business) { business = it }
            SwitchRow("Учитывать в свободных деньгах", checked = include) { include = it }
            Field(balance, { balance = it }, "Текущий остаток, ₽ (если хотите уточнить)", number = true)
            Button(onClick = {
                act("Сохранено") {
                    val fields = jsonOf("name" to name, "kind" to kind, "is_business" to business, "include_in_free" to include,
                        "card_last4" to cards.split(",").map { it.trim() }.filter { it.length == 4 },
                        "bank" to (a.bank ?: if (kind == "cash") "cash" else null))
                    val withBalance = parseRubToMinor(balance)?.let { fields + jsonOf("balance_minor" to it, "balance_at" to nowIso(), "balance_source" to "manual") } ?: fields
                    c.store.save(E.ACCOUNTS, withBalance, a.id.ifEmpty { null })
                    editing = null
                }
            }) { Text("Сохранить") }
            if (a.id.isNotEmpty()) TextButton(onClick = { act(null) { c.store.save(E.ACCOUNTS, jsonOf("archived" to true), a.id); editing = null } }) { Text("В архив") }
        }
    }
}

@Composable
fun CategoriesScreen() {
    val c = LocalContainer.current
    val act = rememberAction()
    val cats by c.store.observe(E.CATEGORIES, BudgetCategory.serializer()).collectAsState(initial = emptyList())
    val rules = rememberLoad { c.api.get("/api/v1/finance/rules").jsonObject["items"]!!.jsonArray.map { it.jsonObject } }
    var editing by remember { mutableStateOf<BudgetCategory?>(null) }
    Screen("Категории", fab = { ExtendedFloatingActionButton(onClick = { editing = BudgetCategory() }, icon = { Icon(Icons.Default.Add, null) }, text = { Text("Категория") }) }) {
        listOf("expense" to "Расходы", "income" to "Доходы", "transfer" to "Переводы").forEach { (kind, title) ->
            item { SectionTitle(title) }
            item {
                AppCard(padding = 8.dp) {
                    cats.filter { it.kind == kind }.sortedBy { it.sort }.forEach { cat ->
                        ListRow(cat.name, listOfNotNull(cat.monthlyLimitMinor?.let { "лимит ${rub(it)}/мес" },
                            if (cat.isImpulse) "контроль импульсивных трат" else null).joinToString(" · ")) { editing = cat }
                    }
                }
            }
        }
        item { SectionTitle("Правила (выучены из исправлений)") }
        val list = rules.value ?: emptyList()
        if (list.isEmpty()) item { Secondary("Пока нет — исправьте категорию у операции, и правило появится.") }
        items(list, key = { it.str("id")!! }) { r ->
            AppCard(padding = 10.dp) {
                ListRow(r.str("pattern") ?: "", "${cats.firstOrNull { it.id == r.str("category_id") }?.name ?: "—"} · применено ${r.str("hits")} раз")
                TextButton(onClick = { act("Удалено") { c.api.delete("/api/v1/finance/rules/${r.str("id")}"); rules.reload() } }) { Text("Удалить правило") }
            }
        }
    }
    editing?.let { cat ->
        Sheet({ editing = null }) {
            var name by remember { mutableStateOf(cat.name) }
            var limit by remember { mutableStateOf(minorText(cat.monthlyLimitMinor)) }
            var impulse by remember { mutableStateOf(cat.isImpulse) }
            var kind by remember { mutableStateOf(cat.kind) }
            Field(name, { name = it }, "Название")
            ChoiceChips(listOf("expense" to "Расход", "income" to "Доход"), kind, { kind = it })
            Field(limit, { limit = it }, "Лимит в месяц, ₽ (необязательно)", number = true)
            SwitchRow("Импульсивная категория", "Агент комментирует каждую такую трату", impulse) { impulse = it }
            Button(onClick = {
                act("Сохранено") {
                    c.store.save(E.CATEGORIES, jsonOf("name" to name, "kind" to kind, "is_impulse" to impulse,
                        "monthly_limit_minor" to parseRubToMinor(limit)), cat.id.ifEmpty { null })
                    editing = null
                }
            }) { Text("Сохранить") }
        }
    }
}

// ----------------------------------------------------------------- subscriptions

@Composable
fun SubscriptionsScreen() {
    val c = LocalContainer.current
    val act = rememberAction()
    val subs by c.store.observe(E.SUBSCRIPTIONS, Subscription.serializer()).collectAsState(initial = emptyList())
    val suggestions = rememberLoad { c.api.get("/api/v1/finance/subscriptions/suggestions").jsonObject["items"]!!.jsonArray.map { it.jsonObject } }
    var editing by remember { mutableStateOf<Subscription?>(null) }
    val active = subs.filter { it.isActive }
    Screen("Подписки", fab = { ExtendedFloatingActionButton(onClick = { editing = Subscription() }, icon = { Icon(Icons.Default.Add, null) }, text = { Text("Подписка") }) }) {
        item {
            AppCard(tint = Tone.current.finance) {
                Label("В месяц")
                BigNumber(rub(active.sumOf { it.monthlyMinor }))
                Secondary("В год ≈ ${rub(active.sumOf { it.monthlyMinor } * 12)} · активных: ${active.size}")
            }
        }
        items(subs.sortedBy { it.nextChargeDate ?: "9" }, key = { it.id }) { s ->
            AppCard(onClick = { editing = s }) {
                ListRow(s.name, listOfNotNull(s.next?.let { "следующее списание ${ruDate(it)}" }, periodLabel(s.period),
                    s.usefulness?.let { "полезность $it/5" }, if (!s.isActive) "неактивна" else null).joinToString(" · "),
                    "${rub(s.amountMinor)}${if (s.currency != "RUB") " " + s.currency else ""}")
            }
        }
        val sugg = suggestions.value ?: emptyList()
        if (sugg.isNotEmpty()) {
            item { SectionTitle("Похоже на подписки") }
            items(sugg, key = { it.str("merchant")!! }) { s ->
                AppCard(tint = Tone.current.motivation) {
                    ListRow(s.str("name") ?: "", "${s.str("occurrences")} списаний · ${periodLabel(s.str("period") ?: "")}",
                        rub(s["amount_minor"]?.jsonPrimitive?.longOrNull))
                    TextButton(onClick = {
                        act("Подписка добавлена") {
                            c.store.save(E.SUBSCRIPTIONS, jsonOf("name" to s.str("name"), "amount_minor" to s["amount_minor"]?.jsonPrimitive?.longOrNull,
                                "period" to s.str("period"), "next_charge_date" to s.str("next_charge_estimate"),
                                "merchant_pattern" to s.str("merchant"), "category_id" to s.str("category_id"), "origin" to "detected"))
                            suggestions.reload()
                        }
                    }) { Text("Добавить") }
                }
            }
        }
    }
    editing?.let { s ->
        Sheet({ editing = null }) {
            var name by remember { mutableStateOf(s.name) }
            var amount by remember { mutableStateOf(minorText(s.amountMinor.takeIf { it > 0 })) }
            var currency by remember { mutableStateOf(s.currency) }
            var period by remember { mutableStateOf(s.period) }
            var next by remember { mutableStateOf(s.next) }
            var useful by remember { mutableStateOf(s.usefulness ?: 0) }
            var activeS by remember { mutableStateOf(s.isActive) }
            Field(name, { name = it }, "Название")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Field(amount, { amount = it }, "Сумма", number = true, modifier = Modifier.weight(1f))
                Dropdown("Валюта", listOf("RUB" to "₽", "USD" to "$", "EUR" to "€"), currency, { currency = it }, Modifier.weight(1f))
            }
            Dropdown("Периодичность", listOf("weekly" to "Еженедельно", "monthly" to "Ежемесячно", "quarterly" to "Раз в квартал", "yearly" to "Ежегодно"), period, { period = it })
            DateField("Следующее списание", next, { next = it })
            Label("Полезность")
            ChoiceChips((1..5).map { it to "$it" }, useful, { useful = it })
            SwitchRow("Активна", checked = activeS) { activeS = it }
            Button(onClick = {
                act("Сохранено") {
                    c.store.save(E.SUBSCRIPTIONS, jsonOf("name" to name, "amount_minor" to (parseRubToMinor(amount) ?: 0), "currency" to currency,
                        "period" to period, "next_charge_date" to next?.toString(), "usefulness" to useful.takeIf { it > 0 },
                        "is_active" to activeS), s.id.ifEmpty { null })
                    editing = null
                }
            }) { Text("Сохранить") }
        }
    }
}

fun periodLabel(p: String) = mapOf("weekly" to "еженедельно", "monthly" to "ежемесячно", "quarterly" to "раз в квартал",
    "yearly" to "ежегодно", "days" to "по дням")[p] ?: p

// ----------------------------------------------------------------- debts

@Composable
fun DebtsScreen() {
    val c = LocalContainer.current
    val act = rememberAction()
    val debts by c.store.observe(E.DEBTS, Debt.serializer()).collectAsState(initial = emptyList())
    val payments by c.store.observe(E.DEBT_PAYMENTS, DebtPayment.serializer()).collectAsState(initial = emptyList())
    var editing by remember { mutableStateOf<Debt?>(null) }
    var paying by remember { mutableStateOf<Debt?>(null) }
    val active = debts.filter { it.status != "closed" }
    Screen("Долги", fab = { ExtendedFloatingActionButton(onClick = { editing = Debt() }, icon = { Icon(Icons.Default.Add, null) }, text = { Text("Долг") }) }) {
        item {
            AppCard(tint = Tone.current.training) {
                Label("Всего осталось")
                BigNumber(rub(active.sumOf { it.balanceMinor }))
                Secondary("Активных долгов: ${active.size}. Проценты и сроки считаются только по подтверждённым условиям.")
            }
        }
        items(debts.sortedBy { it.status == "closed" }, key = { it.id }) { d ->
            val ps = payments.filter { it.debtId == d.id }.sortedByDescending { it.paidAt ?: "" }
            AppCard(onClick = { editing = d }) {
                Row { Text(d.creditor, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f)); Pill(debtStatus(d.status)) }
                Text(rub(d.balanceMinor), style = MaterialTheme.typography.headlineSmall)
                Secondary(listOfNotNull(
                    parseDate(d.holidayUntil)?.let { "каникулы до ${ruDate(it)}" },
                    parseDate(d.nextPaymentDate)?.let { "платёж ${ruDate(it)}" + (d.nextPaymentMinor?.let { m -> " — ${rub(m)}" } ?: "") },
                    d.ratePercent?.let { "ставка ${decimal(it)}% в ${if (d.ratePeriod == "day") "день" else if (d.ratePeriod == "month") "месяц" else "год"}" },
                    if (!d.termsConfirmed) "условия не подтверждены" else null).joinToString(" · "))
                if (d.principalMinor > 0) { Gap(6); ProgressBar(1f - d.balanceMinor.toFloat() / d.principalMinor) }
                if (ps.isNotEmpty()) Secondary("Последний платёж: ${rub(ps.first().amountMinor)} ${parseInstant(ps.first().paidAt)?.let { relative(it) } ?: ""}")
                TextButton(onClick = { paying = d }) { Text("Внести платёж") }
            }
        }
    }
    editing?.let { d ->
        Sheet({ editing = null }) {
            var creditor by remember { mutableStateOf(d.creditor) }
            var kind by remember { mutableStateOf(d.kind) }
            var principal by remember { mutableStateOf(minorText(d.principalMinor.takeIf { it > 0 })) }
            var balance by remember { mutableStateOf(minorText(d.balanceMinor.takeIf { it > 0 })) }
            var rate by remember { mutableStateOf(d.ratePercent?.toString() ?: "") }
            var ratePeriod by remember { mutableStateOf(d.ratePeriod) }
            var nextDate by remember { mutableStateOf(parseDate(d.nextPaymentDate)) }
            var nextAmount by remember { mutableStateOf(minorText(d.nextPaymentMinor)) }
            var holiday by remember { mutableStateOf(parseDate(d.holidayUntil)) }
            var terms by remember { mutableStateOf(d.holidayTerms ?: "") }
            var confirmed by remember { mutableStateOf(d.termsConfirmed) }
            var status by remember { mutableStateOf(d.status) }
            Field(creditor, { creditor = it }, "Кредитор")
            Dropdown("Тип", listOf("microloan" to "Микрозайм", "loan" to "Кредит", "credit_card" to "Кредитная карта",
                "personal" to "Личный долг", "tax" to "Налоговый", "other" to "Другое"), kind, { kind = it })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Field(principal, { principal = it }, "Сумма долга", number = true, modifier = Modifier.weight(1f))
                Field(balance, { balance = it }, "Остаток", number = true, modifier = Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Field(rate, { rate = it }, "Ставка, %", number = true, modifier = Modifier.weight(1f))
                Dropdown("за", listOf("day" to "день", "month" to "месяц", "year" to "год"), ratePeriod, { ratePeriod = it }, Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DateField("Следующий платёж", nextDate, { nextDate = it }, Modifier.weight(1f))
                Field(nextAmount, { nextAmount = it }, "Сумма платежа", number = true, modifier = Modifier.weight(1f))
            }
            DateField("Каникулы до", holiday, { holiday = it })
            Field(terms, { terms = it }, "Условия (из договора)", singleLine = false)
            SwitchRow("Условия подтверждены документами", checked = confirmed) { confirmed = it }
            Dropdown("Статус", listOf("active" to "Активен", "holiday" to "Каникулы", "overdue" to "Просрочен", "closed" to "Закрыт"), status, { status = it })
            Button(onClick = {
                act("Сохранено") {
                    c.store.save(E.DEBTS, jsonOf("creditor" to creditor, "kind" to kind, "principal_minor" to (parseRubToMinor(principal) ?: 0),
                        "balance_minor" to (parseRubToMinor(balance) ?: 0), "rate_percent" to rate.replace(",", ".").toDoubleOrNull(),
                        "rate_period" to ratePeriod, "next_payment_date" to nextDate?.toString(), "next_payment_minor" to parseRubToMinor(nextAmount),
                        "holiday_until" to holiday?.toString(), "holiday_terms" to terms.ifBlank { null }, "terms_confirmed" to confirmed,
                        "status" to status), d.id.ifEmpty { null })
                    editing = null
                }
            }) { Text("Сохранить") }
        }
    }
    paying?.let { d ->
        Sheet({ paying = null }) {
            var amount by remember { mutableStateOf("") }
            var date by remember { mutableStateOf<LocalDate?>(LocalDate.now()) }
            Text("Платёж: ${d.creditor}", style = MaterialTheme.typography.headlineSmall)
            Field(amount, { amount = it }, "Сумма, ₽", number = true)
            DateField("Дата", date, { date = it })
            Button(onClick = {
                val minor = parseRubToMinor(amount) ?: return@Button
                act("Платёж записан") {
                    c.store.save(E.DEBT_PAYMENTS, jsonOf("debt_id" to d.id, "amount_minor" to minor, "paid_at" to (date ?: LocalDate.now()).toIsoStartOfDay()))
                    c.store.save(E.DEBTS, jsonOf("balance_minor" to maxOf(d.balanceMinor - minor, 0)), d.id)
                    paying = null
                }
            }) { Text("Записать") }
        }
    }
}

fun debtStatus(s: String) = mapOf("active" to "активен", "holiday" to "каникулы", "overdue" to "просрочен", "closed" to "закрыт")[s] ?: s

// ----------------------------------------------------------------- goals

@Composable
fun GoalsScreen() {
    val c = LocalContainer.current
    val act = rememberAction()
    val goals by c.store.observe(E.GOALS, FinancialGoal.serializer()).collectAsState(initial = emptyList())
    var editing by remember { mutableStateOf<FinancialGoal?>(null) }
    var adding by remember { mutableStateOf<FinancialGoal?>(null) }
    Screen("Цели", fab = { ExtendedFloatingActionButton(onClick = { editing = FinancialGoal() }, icon = { Icon(Icons.Default.Add, null) }, text = { Text("Цель") }) }) {
        if (goals.isEmpty()) item { EmptyState("Целей пока нет") }
        items(goals.sortedByDescending { it.priority }, key = { it.id }) { g ->
            AppCard(tint = Tone.current.motivation, onClick = { editing = g }) {
                Text(g.name, style = MaterialTheme.typography.titleMedium)
                Text("${rub(g.savedMinor)} из ${if (g.targetMinor > 0) rub(g.targetMinor) else "—"}" +
                    (g.targetMaxMinor?.let { "–${rub(it)}" } ?: ""), style = MaterialTheme.typography.headlineSmall)
                Gap(6)
                ProgressBar(g.progress)
                Secondary(listOfNotNull(parseDate(g.deadline)?.let { "срок ${ruDate(it)}" },
                    g.monthlyPlanMinor?.let { "план ${rub(it)}/мес" },
                    g.monthlyPlanMinor?.takeIf { it > 0 && g.targetMinor > g.savedMinor }?.let { "≈ ${(g.targetMinor - g.savedMinor + it - 1) / it} мес." },
                    if (g.targetMinor == 0L) "бюджет не задан" else null).joinToString(" · "))
                TextButton(onClick = { adding = g }) { Text("Отложить деньги") }
            }
        }
    }
    editing?.let { g ->
        Sheet({ editing = null }) {
            var name by remember { mutableStateOf(g.name) }
            var target by remember { mutableStateOf(minorText(g.targetMinor.takeIf { it > 0 })) }
            var targetMax by remember { mutableStateOf(minorText(g.targetMaxMinor)) }
            var deadline by remember { mutableStateOf(parseDate(g.deadline)) }
            var plan by remember { mutableStateOf(minorText(g.monthlyPlanMinor)) }
            var priority by remember { mutableStateOf(g.priority) }
            Field(name, { name = it }, "Название")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Field(target, { target = it }, "Сумма от", number = true, modifier = Modifier.weight(1f))
                Field(targetMax, { targetMax = it }, "до (необяз.)", number = true, modifier = Modifier.weight(1f))
            }
            DateField("Срок", deadline, { deadline = it })
            Field(plan, { plan = it }, "Откладывать в месяц, ₽", number = true)
            Label("Приоритет")
            ChoiceChips(listOf(1 to "Низкий", 2 to "Средний", 3 to "Высокий"), priority, { priority = it })
            Button(onClick = {
                act("Сохранено") {
                    c.store.save(E.GOALS, jsonOf("name" to name, "target_minor" to (parseRubToMinor(target) ?: 0),
                        "target_max_minor" to parseRubToMinor(targetMax), "deadline" to deadline?.toString(),
                        "monthly_plan_minor" to parseRubToMinor(plan), "priority" to priority), g.id.ifEmpty { null })
                    editing = null
                }
            }) { Text("Сохранить") }
        }
    }
    adding?.let { g ->
        Sheet({ adding = null }) {
            var amount by remember { mutableStateOf("") }
            Text("Пополнить: ${g.name}", style = MaterialTheme.typography.headlineSmall)
            Field(amount, { amount = it }, "Сумма, ₽ (минус — снять)", number = true)
            Button(onClick = {
                val minor = parseRubToMinor(amount.replace("−", "-")) ?: return@Button
                act("Записано") {
                    c.store.save(E.GOAL_CONTRIBUTIONS, jsonOf("goal_id" to g.id, "amount_minor" to minor, "at" to nowIso()))
                    c.store.save(E.GOALS, jsonOf("saved_minor" to g.savedMinor + minor), g.id)
                    adding = null
                }
            }) { Text("Записать") }
        }
    }
}

// ----------------------------------------------------------------- taxes

@Composable
fun TaxesScreen() {
    val c = LocalContainer.current
    val act = rememberAction()
    val taxes by c.store.observe(E.TAXES, TaxPayment.serializer()).collectAsState(initial = emptyList())
    val settings = rememberLoad { c.api.get("/api/v1/settings").jsonObject }
    val tax = settings.value?.get("finance")?.jsonObject?.get("tax")?.jsonObject
    var regime by remember(tax) { mutableStateOf(tax?.str("regime") ?: "usn6") }
    var rate by remember(tax) { mutableStateOf(tax?.str("rate_pct") ?: "6") }
    var insurance by remember(tax) { mutableStateOf(minorText(tax?.get("insurance_fixed_minor")?.jsonPrimitive?.longOrNull)) }
    var paying by remember { mutableStateOf<TaxPayment?>(null) }
    Screen("Налоги ИП", onRefresh = { act(null) { c.api.post("/api/v1/finance/tax/refresh"); c.sync.syncNow() } }) {
        item {
            AppCard(tint = Tone.current.memory) {
                Text("Параметры", style = MaterialTheme.typography.titleMedium)
                Secondary("Расчёт предварительный и выполняется кодом по этим параметрам. Проверяйте актуальные ставки и сроки.")
                Dropdown("Режим", listOf("usn6" to "УСН «Доходы»", "usn15" to "УСН «Доходы минус расходы»", "npd" to "НПД",
                    "patent" to "Патент", "none" to "Не считать"), regime, { regime = it })
                Field(rate, { rate = it }, "Ставка, %", number = true)
                Field(insurance, { insurance = it }, "Фиксированные взносы за год, ₽", number = true)
                Button(onClick = {
                    act("Пересчитано") {
                        c.api.patch("/api/v1/settings/finance", jsonOf("tax" to jsonOf("regime" to regime,
                            "rate_pct" to (rate.replace(",", ".").toDoubleOrNull() ?: 6.0), "insurance_fixed_minor" to (parseRubToMinor(insurance) ?: 0))))
                        c.api.post("/api/v1/finance/tax/refresh")
                        c.sync.syncNow()
                    }
                }) { Text("Сохранить и пересчитать") }
            }
        }
        item { SectionTitle("Календарь") }
        if (taxes.isEmpty()) item { Secondary("Нажмите «Сохранить и пересчитать», чтобы построить календарь.") }
        items(taxes.sortedBy { it.dueDate ?: "" }, key = { it.id }) { t ->
            AppCard(tint = if (t.status == "overdue") Tone.current.training else Tone.current.surface, onClick = { paying = t }) {
                ListRow(taxTitle(t), parseDate(t.dueDate)?.let { "до ${ruDate(it, true)}" } + " · " +
                    (if (t.status == "paid") "оплачено ${rub(t.paidMinor)}" else if (t.status == "overdue") "просрочено" else "примерно"),
                    rub(maxOf(t.estimatedMinor - t.paidMinor, 0)))
            }
        }
    }
    paying?.let { t ->
        Sheet({ paying = null }) {
            var amount by remember { mutableStateOf(minorText(maxOf(t.estimatedMinor - t.paidMinor, 0))) }
            Text(taxTitle(t), style = MaterialTheme.typography.headlineSmall)
            Field(amount, { amount = it }, "Оплачено, ₽", number = true)
            Button(onClick = {
                val minor = parseRubToMinor(amount) ?: return@Button
                act("Отмечено") {
                    c.store.save(E.TAXES, jsonOf("paid_minor" to t.paidMinor + minor, "paid_at" to nowIso(),
                        "status" to if (t.paidMinor + minor >= t.estimatedMinor) "paid" else t.status), t.id)
                    paying = null
                }
            }) { Text("Отметить оплату") }
        }
    }
}

fun taxTitle(t: TaxPayment) = when (t.kind) {
    "usn_advance" -> "Аванс УСН ${t.period}"
    "usn_annual" -> "УСН за ${t.period} год"
    "insurance_fixed" -> "Фиксированные взносы ${t.period}"
    "insurance_1pct" -> "Взнос 1% за ${t.period}"
    "npd" -> "НПД ${t.period}"
    else -> "Налог ${t.period}"
}

// ----------------------------------------------------------------- review

@Composable
fun ReviewScreen() {
    val c = LocalContainer.current
    val nav = LocalNavigate.current
    val act = rememberAction()
    val data = rememberLoad { c.api.get("/api/v1/finance/review").jsonObject }
    val cats by c.store.observe(E.CATEGORIES, BudgetCategory.serializer()).collectAsState(initial = emptyList())
    val catMap = cats.associateBy { it.id }
    Screen("Проверка операций", onRefresh = { data.reload() }) {
        val d = data.value
        if (d == null) { item { if (data.loading) Loading() else ErrorCard(data.error ?: "Ошибка", data.reload) }; return@Screen }
        val matches = d["matches"]!!.jsonArray.map { it.jsonObject }
        val review = d["review"]!!.jsonArray.map { it.jsonObject }
        val uncategorized = d["uncategorized"]!!.jsonArray.map { it.jsonObject }
        if (matches.isEmpty() && review.isEmpty() && uncategorized.isEmpty()) item { EmptyState("Всё проверено 👌") }
        if (matches.isNotEmpty()) item { SectionTitle("Возможные дубликаты") }
        items(matches, key = { it.str("id")!! }) { m ->
            AppCard(tint = Tone.current.motivation) {
                Secondary("Новая запись похожа на уже существующую операцию (та же сумма и время). Это одна и та же операция?")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { act("Объединено") { c.api.post("/api/v1/finance/matches/${m.str("id")}/accept"); data.reload(); c.sync.requestSync(0) } }) { Text("Да, объединить") }
                    OutlinedButton(onClick = { act("Оставлены раздельно") { c.api.post("/api/v1/finance/matches/${m.str("id")}/reject"); data.reload(); c.sync.requestSync(0) } }) { Text("Нет, разные") }
                }
                TextButton(onClick = { m.str("transaction_id")?.let { nav("finance/tx/$it") } }) { Text("Открыть существующую") }
            }
        }
        if (review.isNotEmpty()) item { SectionTitle("Уточните тип") }
        items(review, key = { "r" + it.str("id") }) { t ->
            AppCard(onClick = { nav("finance/tx/${t.str("id")}") }) {
                ListRow(t.str("merchant") ?: t.str("description") ?: "Операция", relative(parseInstant(t.str("occurred_at"))) +
                    " · ${statusLabel(t.str("status") ?: "")}", rub(t["amount_minor"]?.jsonPrimitive?.longOrNull))
                Secondary("Например: перевод между своими счетами не должен считаться доходом.")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { act(null) { c.store.save(E.TRANSACTIONS, jsonOf("flow_type" to "transfer", "status" to "confirmed"), t.str("id")); data.reload() } }) { Text("Свой перевод") }
                    TextButton(onClick = { act(null) { c.store.save(E.TRANSACTIONS, jsonOf("status" to "confirmed"), t.str("id")); data.reload() } }) { Text("Всё верно") }
                }
            }
        }
        if (uncategorized.isNotEmpty()) item { SectionTitle("Без категории") }
        items(uncategorized.take(40), key = { "u" + it.str("id") }) { t ->
            var open by remember { mutableStateOf(false) }
            AppCard(padding = 12.dp) {
                ListRow(t.str("merchant") ?: t.str("description") ?: "Операция", relative(parseInstant(t.str("occurred_at"))),
                    rub(t["amount_minor"]?.jsonPrimitive?.longOrNull), onClick = { open = !open })
                if (open) ChoiceChips(cats.filter { it.kind == "expense" }.sortedBy { it.sort }.map { it.id to it.name }, null, { cid ->
                    act("Запомнил для похожих операций") {
                        c.store.save(E.TRANSACTIONS, jsonOf("category_id" to cid, "category_source" to "user"), t.str("id")); c.sync.syncNow(); data.reload()
                    }
                })
            }
        }
    }
    @Suppress("UNUSED_VARIABLE") val unused = catMap
}

// ----------------------------------------------------------------- wishlist

@Composable
fun WishlistScreen() {
    val c = LocalContainer.current
    val act = rememberAction()
    val items by c.store.observe(E.WISHLIST, WishlistItem.serializer()).collectAsState(initial = emptyList())
    var title by remember { mutableStateOf("") }
    var price by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<JsonObject?>(null) }
    val scope = rememberCoroutineScope()
    Screen("Хочу купить") {
        item {
            AppCard(tint = Tone.current.motivation) {
                Text("Сначала разбор — потом покупка", style = MaterialTheme.typography.titleMedium)
                Secondary("Агент посчитает, как покупка ударит по бюджету, обязательствам и целям, найдёт похожие траты и предложит подождать.")
                Field(title, { title = it }, "Что хотите купить")
                Field(price, { price = it }, "Цена, ₽", number = true)
                Field(url, { url = it }, "Ссылка (необязательно)")
                Field(note, { note = it }, "Зачем", singleLine = false)
                Button(enabled = title.isNotBlank() && !busy, onClick = {
                    scope.launch {
                        busy = true
                        try {
                            result = c.api.post("/api/v1/finance/wishlist/analyze", jsonOf("title" to title,
                                "price_rub" to parseRubToMinor(price)?.let { it / 100.0 }, "url" to url.ifBlank { null },
                                "note" to note.ifBlank { null })).jsonObject
                            c.sync.requestSync(0)
                        } catch (e: Exception) {
                            result = jsonOf("error" to (e.message ?: "Ошибка"))
                        }
                        busy = false
                    }
                }) { Text(if (busy) "Думаю…" else "Разобрать покупку") }
            }
        }
        if (busy) item { Loading() }
        result?.let { r ->
            item {
                if (r.str("error") != null) ErrorCard(r.str("error")!!) else Verdict(r["analysis"] as? JsonObject ?: r)
            }
        }
        if (items.isNotEmpty()) item { SectionTitle("История") }
        items(items.sortedByDescending { it.createdAt ?: "" }, key = { it.id }) { w ->
            AppCard {
                ListRow(w.title, listOfNotNull(verdictLabel(w.verdict), w.decision?.let { decisionLabel(it) },
                    parseDate(w.waitUntil)?.let { "подождать до ${ruDate(it)}" }).joinToString(" · "), w.priceMinor?.let { rub(it) })
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { act(null) { c.store.save(E.WISHLIST, jsonOf("decision" to "bought", "decided_at" to nowIso()), w.id) } }) { Text("Купил") }
                    TextButton(onClick = { act(null) { c.store.save(E.WISHLIST, jsonOf("decision" to "skipped", "decided_at" to nowIso()), w.id) } }) { Text("Передумал") }
                }
            }
        }
    }
}

@Composable
private fun Verdict(a: JsonObject) {
    AppCard(tint = when (a.str("verdict")) { "buy" -> Tone.current.finance; "skip" -> Tone.current.training; else -> Tone.current.motivation }) {
        Text(verdictLabel(a.str("verdict")) ?: "Разбор", style = MaterialTheme.typography.headlineSmall)
        a.str("wait_days")?.toIntOrNull()?.let { Secondary("Предлагаю подождать $it дн.") }
        Gap(6)
        Markdown(a.str("comment") ?: "")
        val pros = a["pros"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
        val cons = a["cons"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
        if (pros.isNotEmpty()) { Gap(6); Label("За"); pros.forEach { Text("• $it") } }
        if (cons.isNotEmpty()) { Gap(6); Label("Против"); cons.forEach { Text("• $it") } }
        val impact = a["impact"] as? JsonObject
        if (impact != null) {
            Divider()
            Secondary("После покупки бюджет на день: ${rub(impact["daily_budget_after_minor"]?.jsonPrimitive?.longOrNull)}; " +
                "это ${impact.str("days_of_budget") ?: "?"} дн. бюджета" + if (impact.str("estimated") == "true") " (оценка)" else "")
        }
    }
}

fun verdictLabel(v: String?) = when (v) { "buy" -> "Можно брать"; "wait" -> "Подождать"; "skip" -> "Не стоит"; else -> null }
fun decisionLabel(v: String) = when (v) { "bought" -> "куплено"; "skipped" -> "передумал"; else -> "ждём" }

@Suppress("unused")
private fun contentOrEmpty(o: JsonObject, k: String) = o[k]?.jsonPrimitive?.contentOrNull ?: ""
