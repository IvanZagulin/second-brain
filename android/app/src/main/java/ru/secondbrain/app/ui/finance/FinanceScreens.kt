package ru.secondbrain.app.ui.finance

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.AccountBalance
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.CreditCard
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.Receipt
import androidx.compose.material.icons.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.ShoppingBag
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import ru.secondbrain.app.core.daysWord
import ru.secondbrain.app.core.local
import ru.secondbrain.app.core.parseDate
import ru.secondbrain.app.core.parseInstant
import ru.secondbrain.app.core.relative
import ru.secondbrain.app.core.ruDate
import ru.secondbrain.app.core.rub
import ru.secondbrain.app.data.model.BudgetCategory
import ru.secondbrain.app.data.model.E
import ru.secondbrain.app.data.model.Transaction
import ru.secondbrain.app.data.repo.jsonOf
import ru.secondbrain.app.data.repo.str
import ru.secondbrain.app.ui.components.AppCard
import ru.secondbrain.app.ui.components.Bars
import ru.secondbrain.app.ui.components.BigNumber
import ru.secondbrain.app.ui.components.Divider
import ru.secondbrain.app.ui.components.ErrorCard
import ru.secondbrain.app.ui.components.Gap
import ru.secondbrain.app.ui.components.HGap
import ru.secondbrain.app.ui.components.Label
import ru.secondbrain.app.ui.components.ListRow
import ru.secondbrain.app.ui.components.Loading
import ru.secondbrain.app.ui.components.LocalContainer
import ru.secondbrain.app.ui.components.LocalNavigate
import ru.secondbrain.app.ui.components.LocalSnackbar
import ru.secondbrain.app.ui.components.Pill
import ru.secondbrain.app.ui.components.Screen
import ru.secondbrain.app.ui.components.SectionTitle
import ru.secondbrain.app.ui.components.Secondary
import ru.secondbrain.app.ui.components.StatTile
import ru.secondbrain.app.ui.components.rememberLoad
import ru.secondbrain.app.ui.theme.Tone
import java.io.File
import java.time.LocalDate

@Composable
fun categoriesMap(): Map<String, BudgetCategory> {
    val c = LocalContainer.current
    val cats by c.store.observe(E.CATEGORIES, BudgetCategory.serializer()).collectAsState(initial = emptyList())
    return cats.associateBy { it.id }
}

@Composable
fun FinanceScreen() {
    val c = LocalContainer.current
    val nav = LocalNavigate.current
    val snackbar = LocalSnackbar.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val summary = rememberLoad { c.api.get("/api/v1/finance/summary").jsonObject }
    val review = rememberLoad { c.api.get("/api/v1/finance/review").jsonObject }
    val txs by c.store.observe(E.TRANSACTIONS, Transaction.serializer()).collectAsState(initial = emptyList())
    val cats = categoriesMap()
    var photoFile by remember { mutableStateOf<File?>(null) }

    val takePhoto = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val f = photoFile ?: return@rememberLauncherForActivityResult
        if (!ok) return@rememberLauncherForActivityResult
        scope.launch {
            try {
                val res = c.api.upload("/api/v1/chat/attachments", f.name, "image/jpeg", f.readBytes().toRequestBody("image/jpeg".toMediaTypeOrNull())).jsonObject
                val r = c.api.post("/api/v1/finance/receipts/attachment", jsonOf("attachment_id" to res.str("id"))).jsonObject
                snackbar.showSnackbar("Чек распознан: ${rub(r["total_minor"]?.jsonPrimitive?.longOrNull)} ${r.str("store") ?: ""}")
                c.sync.requestSync(0)
            } catch (e: Exception) { snackbar.showSnackbar(e.message ?: "Не удалось распознать чек") }
        }
    }

    fun scanQr() {
        val scanner = GmsBarcodeScanning.getClient(context, GmsBarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE).enableAutoZoom().build())
        scope.launch {
            try {
                val code = scanner.startScan().await().rawValue ?: return@launch
                val r = c.api.post("/api/v1/finance/receipts/qr", jsonOf("qr" to code)).jsonObject
                val linked = r.str("transaction_id") != null
                snackbar.showSnackbar("Чек на ${rub(r["total_minor"]?.jsonPrimitive?.longOrNull)}" +
                    if (linked) " связан с операцией" else " добавлен")
                c.sync.requestSync(0)
            } catch (e: Exception) { snackbar.showSnackbar(e.message ?: "Сканирование отменено") }
        }
    }

    Screen("Финансы", back = false, onRefresh = { c.sync.syncNow(); summary.reload(); review.reload() },
        fab = {
            ExtendedFloatingActionButton(onClick = { nav("finance/new-tx") }, icon = { Icon(Icons.Default.Add, null) },
                text = { Text("Операция") }, containerColor = Tone.current.accent, contentColor = Tone.current.surface)
        }) {
        val s = summary.value
        item {
            when {
                s != null -> SummaryCard(s) { nav("finance/accounts") }
                summary.loading -> Loading()
                else -> ErrorCard(summary.error ?: "Нет данных", summary.reload)
            }
        }
        item {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(onClick = { nav("finance/wishlist") }, label = { Text("Хочу купить") }, leadingIcon = { Icon(Icons.Outlined.ShoppingBag, null) })
                AssistChip(onClick = { scanQr() }, label = { Text("QR чека") }, leadingIcon = { Icon(Icons.Outlined.QrCodeScanner, null) })
                AssistChip(onClick = {
                    val f = File(context.cacheDir, "captures/receipt-${System.currentTimeMillis()}.jpg").apply { parentFile?.mkdirs() }
                    photoFile = f
                    takePhoto.launch(FileProvider.getUriForFile(context, "${context.packageName}.files", f))
                }, label = { Text("Фото чека") }, leadingIcon = { Icon(Icons.Outlined.Receipt, null) })
                AssistChip(onClick = { nav("finance/import") }, label = { Text("Выписка") }, leadingIcon = { Icon(Icons.Outlined.UploadFile, null) })
            }
        }
        val rv = review.value
        val reviewCount = (rv?.get("review")?.jsonArray?.size ?: 0) + (rv?.get("matches")?.jsonArray?.size ?: 0)
        val uncategorized = rv?.get("uncategorized")?.jsonArray?.size ?: 0
        if (reviewCount + uncategorized > 0) item {
            AppCard(tint = Tone.current.motivation, onClick = { nav("finance/review") }) {
                Text("Нужно проверить", style = MaterialTheme.typography.titleMedium)
                Secondary(listOfNotNull(
                    if (reviewCount > 0) "$reviewCount спорных операций" else null,
                    if (uncategorized > 0) "$uncategorized без категории" else null).joinToString(" · "))
            }
        }
        if (s != null) {
            val obligations = s["obligations_until_income"]?.jsonArray?.map { it.jsonObject } ?: emptyList()
            item {
                AppCard {
                    Text("До поступления ${s.str("next_income_date")?.let { parseDate(it) }?.let { ruDate(it) } ?: ""}",
                        style = MaterialTheme.typography.titleMedium)
                    if (obligations.isEmpty()) Secondary("Обязательных платежей нет")
                    obligations.forEach { o ->
                        ListRow(o.str("title") ?: "", parseDate(o.str("due"))?.let { ruDate(it) } +
                            if (o.str("estimated") == "true") " · примерно" else "", rub(o["amount_minor"]?.jsonPrimitive?.longOrNull))
                    }
                    val reserves = (s["reserves_minor"]?.jsonPrimitive?.longOrNull ?: 0) + (s["goal_reserve_minor"]?.jsonPrimitive?.longOrNull ?: 0)
                    if (reserves > 0) ListRow("Резерв на цели и подушку", null, rub(reserves))
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatTile("За неделю", rub(s["week_spent_minor"]?.jsonPrimitive?.longOrNull), Tone.current.surface, Modifier.weight(1f))
                    StatTile("За месяц", rub(s["month_spent_minor"]?.jsonPrimitive?.longOrNull), Tone.current.surface, Modifier.weight(1f),
                        hint = "прогноз ${rub(s["forecast_month_minor"]?.jsonPrimitive?.longOrNull)}")
                }
            }
            val byCat = (s["month_by_category"] as? JsonObject)?.entries?.take(6) ?: emptyList()
            if (byCat.isNotEmpty()) item {
                AppCard(onClick = { nav("analytics") }) {
                    Text("Траты месяца по категориям", style = MaterialTheme.typography.titleMedium)
                    Gap(8)
                    Bars(byCat.map { (k, v) ->
                        val minor = v.jsonPrimitive.longOrNull ?: 0
                        Triple(cats[k]?.name ?: "Без категории", minor.toDouble(), rub(minor))
                    })
                }
            }
        }
        item { SectionTitle("Последние операции", "Все") { nav("finance/transactions") } }
        val recent = txs.filter { it.status != "excluded" }.sortedByDescending { it.occurredAt ?: "" }.take(8)
        if (recent.isEmpty()) item { Secondary("Операций пока нет. Они появятся из уведомлений банка, выписок или вручную.") }
        items(recent, key = { it.id }) { t -> TxRow(t, cats) { nav("finance/tx/${t.id}") } }
        item { SectionTitle("Учёт") }
        item {
            AppCard(padding = 6.dp) {
                ListRow("Счета и наличные", icon = Icons.Outlined.AccountBalance, iconTint = Tone.current.finance) { nav("finance/accounts") }
                Divider()
                ListRow("Подписки", icon = Icons.Outlined.Repeat, iconTint = Tone.current.finance) { nav("finance/subscriptions") }
                Divider()
                ListRow("Долги", icon = Icons.Outlined.CreditCard, iconTint = Tone.current.finance) { nav("finance/debts") }
                Divider()
                ListRow("Цели и накопления", icon = Icons.Outlined.Flag, iconTint = Tone.current.finance) { nav("finance/goals") }
                Divider()
                ListRow("Налоги ИП", icon = Icons.Outlined.CalendarMonth, iconTint = Tone.current.finance) { nav("finance/taxes") }
                Divider()
                ListRow("Категории и правила", icon = Icons.Outlined.Category, iconTint = Tone.current.finance) { nav("finance/categories") }
                Divider()
                ListRow("Импорт выписки", icon = Icons.Outlined.ReceiptLong, iconTint = Tone.current.finance) { nav("finance/import") }
            }
        }
    }
}

@Composable
private fun SummaryCard(s: JsonObject, onAccounts: () -> Unit) {
    val left = s["today_left_minor"]?.jsonPrimitive?.longOrNull ?: 0
    AppCard(tint = Tone.current.finance, onClick = onAccounts) {
        Row {
            Label("Можно потратить сегодня", Modifier.weight(1f))
            if (s.str("estimated") == "true") Pill("оценка", Tone.current.surface)
            if (s.str("savings_mode") == "true") { HGap(4); Pill("экономия", Tone.current.motivation) }
        }
        BigNumber(rub(left), if (left < 0) Tone.current.negative else Tone.current.text)
        val days = s["days_until_income"]?.jsonPrimitive?.longOrNull ?: 0
        Secondary("Бюджет на день ${rub(s["daily_budget_minor"]?.jsonPrimitive?.longOrNull)} · сегодня потрачено " +
            rub(s["today_spent_minor"]?.jsonPrimitive?.longOrNull))
        Gap(10)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile("Свободно", rub(s["free_minor"]?.jsonPrimitive?.longOrNull), Tone.current.surface, Modifier.weight(1f),
                hint = "до поступления $days ${daysWord(days)}")
            StatTile("Долги", rub(s["debts_total_minor"]?.jsonPrimitive?.longOrNull), Tone.current.surface, Modifier.weight(1f))
        }
        if (s.str("estimated") == "true") {
            Gap(6)
            Secondary("Остатки не подтверждены банком — нажмите, чтобы уточнить.")
        }
    }
}

@Composable
fun TxRow(t: Transaction, cats: Map<String, BudgetCategory>, onClick: () -> Unit) {
    val cat = t.categoryId?.let { cats[it] }
    val sub = listOfNotNull(cat?.name ?: if (t.direction == "out" && t.flowType == "expense") "Без категории" else null,
        t.at?.let { relative(it, t.hasTime) }, if (t.scope == "business") "ИП" else null).joinToString(" · ")
    AppCard(onClick = onClick, padding = 12.dp) {
        ListRow(t.title, sub, (if (t.direction == "in") "+" else "−") + rub(t.amountMinor, true).removePrefix("−"),
            trailingColor = if (t.direction == "in") Tone.current.positive else Tone.current.text,
            extra = if (t.status in setOf("needs_review", "possible_duplicate")) ({ Pill("проверить", Tone.current.motivation) }) else null)
    }
}

@Composable
fun TransactionsScreen() {
    val c = LocalContainer.current
    val nav = LocalNavigate.current
    val txs by c.store.observe(E.TRANSACTIONS, Transaction.serializer()).collectAsState(initial = emptyList())
    val cats = categoriesMap()
    var month by remember { mutableStateOf(LocalDate.now().withDayOfMonth(1)) }
    var query by remember { mutableStateOf("") }
    val filtered = txs.filter { t ->
        val d = t.at?.local()?.toLocalDate()
        d != null && !d.isBefore(month) && d.isBefore(month.plusMonths(1)) && t.status != "excluded" &&
            (query.isBlank() || (t.title + " " + (t.description ?: "")).contains(query, true))
    }.sortedByDescending { it.occurredAt ?: "" }
    val spent = filtered.filter { it.direction == "out" && it.flowType == "expense" }.sumOf { it.amountMinor }
    val income = filtered.filter { it.direction == "in" && it.flowType == "income" }.sumOf { it.amountMinor }
    Screen("Операции", subtitle = "${month.month.getDisplayName(java.time.format.TextStyle.FULL_STANDALONE, java.util.Locale.forLanguageTag("ru"))} ${month.year}",
        onRefresh = { c.sync.syncNow() }) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(onClick = { month = month.minusMonths(1) }, label = { Text("← Месяц") })
                AssistChip(onClick = { month = month.plusMonths(1) }, label = { Text("Месяц →") })
            }
        }
        item { ru.secondbrain.app.ui.components.Field(query, { query = it }, "Поиск по магазину или описанию") }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTile("Расходы", rub(spent), Tone.current.surface, Modifier.weight(1f))
                StatTile("Доходы", rub(income), Tone.current.finance, Modifier.weight(1f))
            }
        }
        var lastDay: LocalDate? = null
        filtered.forEach { t ->
            val d = t.at?.local()?.toLocalDate()
            if (d != lastDay) {
                lastDay = d
                item(key = "d$d") { Label(d?.let { ruDate(it) } ?: "") }
            }
            item(key = t.id) { TxRow(t, cats) { nav("finance/tx/${t.id}") } }
        }
    }
}

@Composable
fun ImportStatementScreen() {
    val c = LocalContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var report by remember { mutableStateOf<JsonObject?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true; error = null
            try {
                val cr = context.contentResolver
                val name = cr.query(uri, null, null, null, null)?.use { cur ->
                    val idx = cur.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (cur.moveToFirst() && idx >= 0) cur.getString(idx) else null
                } ?: "statement"
                val bytes = cr.openInputStream(uri)!!.use { it.readBytes() }
                report = c.api.upload("/api/v1/finance/statements", name, cr.getType(uri) ?: "application/octet-stream",
                    bytes.toRequestBody((cr.getType(uri) ?: "application/octet-stream").toMediaTypeOrNull())).jsonObject
                c.sync.requestSync(0)
            } catch (e: Exception) { error = e.message }
            busy = false
        }
    }
    Screen("Импорт выписки") {
        item {
            AppCard(tint = Tone.current.finance) {
                Text("Сбербанк, Т-Банк и другие", style = MaterialTheme.typography.titleMedium)
                Secondary("PDF-выписки Сбера и Т-Банка (в т.ч. счёт ИП), справки по операциям, CSV/XLSX, формат 1С. " +
                    "Повторная загрузка той же выписки не создаёт дублей; совпадения с уведомлениями объединяются, спорные — на проверку.")
                Gap(8)
                androidx.compose.material3.Button(onClick = {
                    picker.launch(arrayOf("application/pdf", "text/csv", "text/comma-separated-values", "text/plain",
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "application/octet-stream"))
                }, enabled = !busy) { Text(if (busy) "Загружаю…" else "Выбрать файл") }
            }
        }
        if (busy) item { Loading() }
        error?.let { item { ErrorCard(it) } }
        report?.let { r ->
            item {
                AppCard {
                    Text("Готово", style = MaterialTheme.typography.titleMedium)
                    val n = { k: String -> r[k]?.jsonPrimitive?.contentOrNull ?: "0" }
                    ListRow("Операций в файле", null, n("rows"))
                    ListRow("Новых", null, n("created"))
                    ListRow("Объединено с уведомлениями", null, n("linked"))
                    ListRow("Уже были загружены", null, n("duplicate"))
                    ListRow("Требуют проверки", null, n("ambiguous"))
                    r["balance_confirmed_minor"]?.jsonPrimitive?.longOrNull?.let { ListRow("Подтверждён остаток", null, rub(it)) }
                }
            }
        }
    }
}

@Suppress("unused")
private fun instantLabel(s: String?) = parseInstant(s)?.let { relative(it) } ?: ""
