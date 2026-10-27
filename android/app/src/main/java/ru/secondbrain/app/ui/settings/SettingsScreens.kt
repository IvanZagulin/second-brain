package ru.secondbrain.app.ui.settings

import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Face
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.Payments
import androidx.compose.material.icons.outlined.PrivacyTip
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material.icons.outlined.Work
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.health.connect.client.PermissionController
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import ru.secondbrain.app.BuildConfig
import ru.secondbrain.app.SecondBrainApp
import ru.secondbrain.app.core.AppLog
import ru.secondbrain.app.core.Prefs
import ru.secondbrain.app.core.parseInstant
import ru.secondbrain.app.core.relative
import ru.secondbrain.app.core.ruWeekdayShort
import ru.secondbrain.app.data.repo.jsonOf
import ru.secondbrain.app.data.repo.str
import ru.secondbrain.app.health.HealthSync
import ru.secondbrain.app.notifications.AgentNotifier
import ru.secondbrain.app.notifications.injectTestBankNotification
import ru.secondbrain.app.ui.components.AppCard
import ru.secondbrain.app.ui.components.Bars
import ru.secondbrain.app.ui.components.ChoiceChips
import ru.secondbrain.app.ui.components.ConfirmDialog
import ru.secondbrain.app.ui.components.Divider
import ru.secondbrain.app.ui.components.Dropdown
import ru.secondbrain.app.ui.components.ErrorCard
import ru.secondbrain.app.ui.components.Field
import ru.secondbrain.app.ui.components.Gap
import ru.secondbrain.app.ui.components.Label
import ru.secondbrain.app.ui.components.LineChart
import ru.secondbrain.app.ui.components.ListRow
import ru.secondbrain.app.ui.components.Loading
import ru.secondbrain.app.ui.components.LocalContainer
import ru.secondbrain.app.ui.components.LocalNavigate
import ru.secondbrain.app.ui.components.Markdown
import ru.secondbrain.app.ui.components.ProgressBar
import ru.secondbrain.app.ui.components.Screen
import ru.secondbrain.app.ui.components.SectionTitle
import ru.secondbrain.app.ui.components.Secondary
import ru.secondbrain.app.ui.components.SwitchRow
import ru.secondbrain.app.ui.components.TimeField
import ru.secondbrain.app.ui.components.rememberAction
import ru.secondbrain.app.ui.components.rememberLoad
import ru.secondbrain.app.ui.theme.Tone
import ru.secondbrain.app.update.Release
import java.time.LocalTime

@Composable
private fun settingsLoad() = LocalContainer.current.let { c -> rememberLoad { c.api.get("/api/v1/settings").jsonObject } }

private fun JsonObject.obj(k: String) = this[k] as? JsonObject ?: JsonObject(emptyMap())
private fun JsonObject.bool(k: String) = this[k]?.jsonPrimitive?.contentOrNull == "true"
private fun t(s: String?) = s?.let { runCatching { LocalTime.parse(it) }.getOrNull() }

@Composable
fun SettingsScreen() {
    val c = LocalContainer.current
    val nav = LocalNavigate.current
    val act = rememberAction()
    val theme by c.prefs.flow(Prefs.Keys.theme).collectAsState(initial = null)
    val biometric by c.prefs.flow(Prefs.Keys.biometricEnabled).collectAsState(initial = true)
    val timeout by c.prefs.flow(Prefs.Keys.lockTimeoutMin).collectAsState(initial = 5)
    val sync by c.sync.state.collectAsState()
    var unpair by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Screen("Настройки") {
        item {
            AppCard(padding = 6.dp) {
                ListRow("Характер агента", icon = Icons.Outlined.Face, iconTint = Tone.current.memory) { nav("agent/persona") }
                Divider()
                ListRow("Права агента (A/B/C)", icon = Icons.Outlined.Security, iconTint = Tone.current.memory) { nav("agent/tools") }
                Divider()
                ListRow("Инициативность", "когда и как часто пишет первым", icon = Icons.Outlined.NotificationsActive, iconTint = Tone.current.memory) { nav("settings/initiative") }
                Divider()
                ListRow("Задания агента", icon = Icons.Outlined.AccountTree, iconTint = Tone.current.memory) { nav("agent/jobs") }
            }
        }
        item {
            AppCard(padding = 6.dp) {
                ListRow("Модели Claude", icon = Icons.Outlined.Memory, iconTint = Tone.current.finance) { nav("settings/models") }
                Divider()
                ListRow("Расходы на API", icon = Icons.Outlined.Payments, iconTint = Tone.current.finance) { nav("settings/usage") }
                Divider()
                ListRow("Распорядок дня", icon = Icons.Outlined.Schedule, iconTint = Tone.current.finance) { nav("settings/schedule") }
                Divider()
                ListRow("Приватность", icon = Icons.Outlined.PrivacyTip, iconTint = Tone.current.finance) { nav("settings/privacy") }
                Divider()
                ListRow("Интеграции", "банки, Health Connect, экранное время", icon = Icons.Outlined.Extension, iconTint = Tone.current.finance) { nav("settings/integrations") }
            }
        }
        item {
            AppCard {
                Text("Безопасность", style = MaterialTheme.typography.titleMedium)
                SwitchRow("Вход по отпечатку", checked = biometric != false) { on -> act(null) { c.prefs.set(Prefs.Keys.biometricEnabled, on) } }
                Label("Блокировать после фона, минут")
                ChoiceChips(listOf(1 to "1", 5 to "5", 15 to "15", 60 to "60"), timeout ?: 5, { m -> act(null) { c.prefs.set(Prefs.Keys.lockTimeoutMin, m) } })
                Label("Тема")
                ChoiceChips(listOf<Pair<String?, String>>(null to "Как в системе", "light" to "Светлая", "dark" to "Тёмная"), theme, { th -> act(null) { c.prefs.set(Prefs.Keys.theme, th) } })
                TextButton(onClick = { nav("settings/devices") }) { Text("Устройства и коды подключения") }
            }
        }
        item {
            AppCard(padding = 6.dp) {
                ListRow("Обновления", "версия ${BuildConfig.VERSION_NAME}", icon = Icons.Outlined.SystemUpdate, iconTint = Tone.current.motivation) { nav("updates") }
                Divider()
                ListRow("Диагностика", sync.lastSyncAt?.let { "синхронизация ${relative(java.time.Instant.ofEpochMilli(it))}" },
                    icon = Icons.Outlined.BugReport, iconTint = Tone.current.motivation) { nav("diagnostics") }
                Divider()
                ListRow("История агента", icon = Icons.Outlined.Work, iconTint = Tone.current.motivation) { nav("agent/history") }
            }
        }
        item { TextButton(onClick = { unpair = true }) { Text("Отключить это устройство", color = Tone.current.negative) } }
    }
    if (unpair) ConfirmDialog("Отключить устройство?", "Локальные данные будут удалены с телефона (на сервере всё останется). Для повторного входа понадобится новый код.", "Отключить",
        onConfirm = {
            scope.launch {
                runCatching { val me = c.api.get("/api/v1/auth/me").jsonObject; c.api.post("/api/v1/auth/devices/${me["device"]!!.jsonObject.str("id")}/revoke") }
                c.db.records().clear(); c.db.outbox().clear(); c.prefs.resetAll()
                (c.context as? SecondBrainApp)?.let { android.os.Process.killProcess(android.os.Process.myPid()) }
            }
        }, onDismiss = { unpair = false })
}

@Composable
fun ModelsScreen() {
    val c = LocalContainer.current
    val act = rememberAction()
    val s = settingsLoad()
    val models = rememberLoad { c.api.get("/api/v1/settings/models").jsonObject["items"]!!.jsonArray.map { it.jsonObject } }
    Screen("Модели Claude") {
        val st = s.value
        if (st == null) { item { if (s.loading) Loading() else ErrorCard(s.error ?: "Ошибка", s.reload) }; return@Screen }
        val supported = (models.value ?: emptyList()).filter { it.str("supported") == "true" }.map { it.str("id")!! to (it.str("display_name") ?: it.str("id")!!) }
        val current = st.obj("models")
        val effort = st.obj("effort")
        val budget = st.obj("api_budget")
        item { Secondary("Список моделей берётся из Claude API. Простые вычисления (бюджет, долги, поиск операций) делает код, без модели.") }
        listOf("chat" to "Обычное общение", "analysis" to "Сложный анализ (психология, критик, недельный отчёт)",
            "background" to "Фоновые задачи", "import" to "Обработка архивов", "vision" to "Чеки и изображения").forEach { (k, label) ->
            item {
                AppCard {
                    Text(label, style = MaterialTheme.typography.titleSmall)
                    Dropdown("Модель", supported.ifEmpty { listOf((current.str(k) ?: "") to (current.str(k) ?: "")) }, current.str(k), { m ->
                        act(null) { c.api.patch("/api/v1/settings/models", jsonOf(k to m)); s.reload() }
                    })
                    Label("Глубина рассуждений (effort)")
                    ChoiceChips(listOf("low" to "низкая", "medium" to "средняя", "high" to "высокая", "xhigh" to "очень высокая"), effort.str(k), { e ->
                        act(null) { c.api.patch("/api/v1/settings/effort", jsonOf(k to e)); s.reload() }
                    })
                }
            }
        }
        item {
            var limit by remember(budget) { mutableStateOf(budget.str("monthly_limit_usd") ?: "100") }
            AppCard(tint = Tone.current.finance) {
                Text("Месячный лимит API", style = MaterialTheme.typography.titleMedium)
                Field(limit, { limit = it }, "Лимит, $", number = true)
                SwitchRow("Экономить у лимита", "С 80% — фоновые задачи на паузу и анализ на Sonnet; с 95% — чат на Haiku", budget.bool("degrade")) { on ->
                    act(null) { c.api.patch("/api/v1/settings/api_budget", jsonOf("degrade" to on)); s.reload() }
                }
                Button(onClick = { act("Сохранено") { c.api.patch("/api/v1/settings/api_budget", jsonOf("monthly_limit_usd" to (limit.replace(",", ".").toDoubleOrNull() ?: 100.0))); s.reload() } }) { Text("Сохранить лимит") }
                Secondary("Это дополнительная защита. Основной лимит задаётся в Claude Console.")
            }
        }
    }
}

@Composable
fun UsageScreen() {
    val c = LocalContainer.current
    val summary = rememberLoad { c.api.get("/api/v1/usage/summary").jsonObject }
    val daily = rememberLoad { c.api.get("/api/v1/usage/daily", mapOf("days" to 30)).jsonObject["items"]!!.jsonArray.map { it.jsonObject } }
    Screen("Расходы на Claude API", onRefresh = { summary.reload(); daily.reload() }) {
        val u = summary.value
        if (u == null) { item { if (summary.loading) Loading() else ErrorCard(summary.error ?: "Ошибка", summary.reload) }; return@Screen }
        val spent = u.str("spent_usd")?.toDoubleOrNull() ?: 0.0
        val limit = u.str("limit_usd")?.toDoubleOrNull() ?: 100.0
        item {
            AppCard(tint = Tone.current.finance) {
                Label("Месяц ${u.str("month")}")
                Text("$%.2f из $%.0f".format(spent, limit), style = MaterialTheme.typography.displaySmall)
                ProgressBar((spent / limit).toFloat().coerceIn(0f, 1f), if (spent / limit > 0.8) Tone.current.negative else Tone.current.accent)
                Secondary("Прогноз на месяц: $${u.str("forecast_usd")} · фоновые задачи: $${u.str("background_usd")}" + if (u.bool("blocked")) " · лимит исчерпан" else "")
            }
        }
        item {
            AppCard {
                Text("По моделям", style = MaterialTheme.typography.titleMedium)
                Bars(u["by_model"]!!.jsonArray.map { it.jsonObject }.map { Triple(it.str("model") ?: "", it.str("cost_usd")?.toDoubleOrNull() ?: 0.0,
                    "$%.3f · %s запр.".format(it.str("cost_usd")?.toDoubleOrNull() ?: 0.0, it.str("requests"))) })
            }
        }
        item {
            AppCard {
                Text("По задачам", style = MaterialTheme.typography.titleMedium)
                Bars(u["by_purpose"]!!.jsonArray.map { it.jsonObject }.map { Triple(purposeLabel(it.str("purpose")), it.str("cost_usd")?.toDoubleOrNull() ?: 0.0,
                    "$%.3f".format(it.str("cost_usd")?.toDoubleOrNull() ?: 0.0)) })
            }
        }
        val days = (daily.value ?: emptyList()).groupBy { it.str("date") }.toSortedMap(compareBy { it }).map { (_, v) -> v.sumOf { it.str("cost_usd")?.toDoubleOrNull() ?: 0.0 } }
        item { AppCard { Label("По дням, $"); LineChart(days) } }
    }
}

fun purposeLabel(p: String?) = mapOf("chat" to "Чат", "analysis" to "Анализ", "background" to "Фон", "import" to "Архивы",
    "vision" to "Изображения", "extraction" to "Память из разговоров")[p] ?: (p ?: "")

@Composable
fun InitiativeScreen() {
    val c = LocalContainer.current
    val act = rememberAction()
    val s = settingsLoad()
    Screen("Инициативность") {
        val st = s.value ?: run { item { Loading() }; return@Screen }
        val ini = st.obj("initiative")
        fun patch(vararg p: Pair<String, Any?>) = act(null) { c.api.patch("/api/v1/settings/initiative", jsonOf(*p)); s.reload() }
        item {
            AppCard(tint = Tone.current.memory) {
                SwitchRow("Агент пишет первым", checked = ini.bool("enabled")) { patch("enabled" to it) }
                val paused = parseInstant(ini.str("paused_until"))
                if (paused != null && paused.isAfter(java.time.Instant.now())) Secondary("На паузе до ${relative(paused)}")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { act("Пауза на 3 часа") { c.api.post("/api/v1/agent/initiative/pause", jsonOf("hours" to 3)); s.reload() } }) { Text("Пауза 3 ч") }
                    OutlinedButton(onClick = { act("Пауза до завтра") { c.api.post("/api/v1/agent/initiative/pause", jsonOf("hours" to 16)); s.reload() } }) { Text("До завтра") }
                    TextButton(onClick = { act("Возобновлено") { c.api.post("/api/v1/agent/initiative/pause", jsonOf("hours" to null)); s.reload() } }) { Text("Снять") }
                }
            }
        }
        item {
            AppCard {
                Label("Не больше сообщений в день (важное — всегда)")
                ChoiceChips(listOf(2, 4, 6, 10, 20).map { it to "$it" }, ini.str("max_per_day")?.toIntOrNull(), { patch("max_per_day" to it) })
                Label("Минимум минут между сообщениями")
                ChoiceChips(listOf(30, 60, 90, 180).map { it to "$it" }, ini.str("min_interval_minutes")?.toIntOrNull(), { patch("min_interval_minutes" to it) })
            }
        }
        item {
            AppCard {
                SwitchRow("Утреннее сообщение", checked = ini.bool("morning_brief")) { patch("morning_brief" to it) }
                TimeField("Время", t(ini.str("morning_time")), { patch("morning_time" to it.toString()) })
                SwitchRow("Вечерний чек-ин (настроение и итоги)", checked = ini.bool("evening_checkin")) { patch("evening_checkin" to it) }
                TimeField("Время", t(ini.str("evening_time")), { patch("evening_time" to it.toString()) })
                Label("Недельный отчёт")
                ChoiceChips((1..7).map { it to ruWeekdayShort(it) }, ini.str("weekly_report_day")?.toIntOrNull(), { patch("weekly_report_day" to it) })
                TimeField("Время отчёта", t(ini.str("weekly_report_time")), { patch("weekly_report_time" to it.toString()) })
            }
        }
        item { Secondary("Ночью (по времени сна из распорядка) уведомления приходят беззвучно, а неважные откладываются до утра.") }
    }
}

@Composable
fun ScheduleScreen() {
    val c = LocalContainer.current
    val act = rememberAction()
    val s = settingsLoad()
    Screen("Распорядок") {
        val st = s.value ?: run { item { Loading() }; return@Screen }
        val sch = st.obj("schedule")
        val fit = st.obj("fitness")
        val fin = st.obj("finance")
        fun patch(key: String, vararg p: Pair<String, Any?>) = act(null) { c.api.patch("/api/v1/settings/$key", jsonOf(*p)); s.reload() }
        item {
            AppCard {
                Text("День", style = MaterialTheme.typography.titleMedium)
                listOf("wake" to "Подъём", "leave_home" to "Выход из дома", "work_start" to "Начало работы", "work_end" to "Конец работы",
                    "home_no_training" to "Дома (без тренировки)", "sleep_start" to "Сон с", "sleep_end" to "Сон до").forEach { (k, label) ->
                    TimeField(label, t(sch.str(k)), { patch("schedule", k to it.toString()) })
                    Gap(4)
                }
                var notes by remember(sch) { mutableStateOf(sch.str("notes") ?: "") }
                Field(notes, { notes = it }, "Особенности (собака, подработка вечером…)", singleLine = false)
                TextButton(onClick = { patch("schedule", "notes" to notes) }) { Text("Сохранить заметку") }
            }
        }
        item {
            AppCard(tint = Tone.current.training) {
                Text("Тренировки", style = MaterialTheme.typography.titleMedium)
                val days = fit["training_days"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull?.toIntOrNull() } ?: emptyList()
                Label("Дни")
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    (1..7).forEach { d ->
                        androidx.compose.material3.FilterChip(selected = d in days, onClick = {
                            patch("fitness", "training_days" to JsonArray((if (d in days) days - d else days + d).sorted().map { JsonPrimitive(it) }))
                        }, label = { Text(ruWeekdayShort(d)) })
                    }
                }
                TimeField("Время тренировки", t(fit.str("training_time")), { patch("fitness", "training_time" to it.toString()) })
                TimeField("Проверять пропуск после", t(fit.str("missed_check_time")), { patch("fitness", "missed_check_time" to it.toString()) })
                var target by remember(fit) { mutableStateOf(fit.str("target_weight") ?: "") }
                Field(target, { target = it }, "Целевой вес, кг", number = true)
                TextButton(onClick = { patch("fitness", "target_weight" to target.replace(",", ".").toDoubleOrNull()) }) { Text("Сохранить") }
            }
        }
        item {
            AppCard(tint = Tone.current.finance) {
                Text("Поступления", style = MaterialTheme.typography.titleMedium)
                var daysText by remember(fin) { mutableStateOf(fin["income_days"]?.jsonArray?.joinToString(", ") { it.jsonPrimitive.content } ?: "") }
                Field(daysText, { daysText = it }, "Дни зарплаты (через запятую)")
                Secondary("Бюджет на день считается до ближайшего из этих дней. Будущие поступления не считаются деньгами, пока не пришли.")
                TextButton(onClick = {
                    patch("finance", "income_days" to JsonArray(daysText.split(",").mapNotNull { it.trim().toIntOrNull() }.filter { it in 1..31 }.map { JsonPrimitive(it) }))
                }) { Text("Сохранить") }
            }
        }
    }
}

@Composable
fun PrivacyScreen() {
    val c = LocalContainer.current
    val act = rememberAction()
    val s = settingsLoad()
    Screen("Приватность") {
        val st = s.value ?: run { item { Loading() }; return@Screen }
        val p = st.obj("privacy")
        fun patch(vararg v: Pair<String, Any?>) = act(null) { c.api.patch("/api/v1/settings/privacy", jsonOf(*v)); s.reload() }
        item {
            AppCard {
                Text("Что можно отправлять в Claude", style = MaterialTheme.typography.titleMedium)
                SwitchRow("Изображения (чеки, еда, вложения)", checked = p.bool("allow_image_analysis")) { patch("allow_image_analysis" to it) }
                SwitchRow("Фото формы", checked = p.bool("allow_progress_photos_llm")) { patch("allow_progress_photos_llm" to it) }
                SwitchRow("Фрагменты импортированных переписок", checked = p.bool("allow_import_llm")) { patch("allow_import_llm" to it) }
                SwitchRow("Секретные воспоминания", "по умолчанию никогда не отправляются", p.bool("send_secret_memories")) { patch("send_secret_memories" to it) }
            }
        }
        item {
            val access = p.obj("memory_access")
            AppCard {
                Text("Доступ агента к памяти", style = MaterialTheme.typography.titleMedium)
                ru.secondbrain.app.data.model.MEMORY_TYPES.forEach { (k, label) ->
                    SwitchRow(label, checked = access[k]?.jsonPrimitive?.contentOrNull != "false") { on ->
                        patch("memory_access" to (access.toMap() + (k to JsonPrimitive(on))))
                    }
                }
            }
        }
        item {
            AppCard {
                SwitchRow("Скрывать текст уведомлений на экране блокировки", checked = p.bool("hide_sensitive_in_notifications")) { patch("hide_sensitive_in_notifications" to it) }
                Secondary("Агент получает только нужные данные: для бюджета — цифры, а не переписки; для отношений — память, а не банк.")
            }
        }
    }
}

@Composable
fun IntegrationsScreen() {
    val c = LocalContainer.current
    val context = LocalContext.current
    val act = rememberAction()
    val scope = rememberCoroutineScope()
    var listener by remember { mutableStateOf(NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)) }
    val healthEnabled by c.prefs.flow(Prefs.Keys.healthEnabled).collectAsState(initial = false)
    val usageEnabled by c.prefs.flow(Prefs.Keys.usageEnabled).collectAsState(initial = false)
    var granted by remember { mutableIntStateOf(0) }
    var packages by remember { mutableStateOf<List<String>>(emptyList()) }
    val recent by c.db.bankNotifications().recent().collectAsState(initial = emptyList())
    LaunchedEffect(Unit) {
        packages = c.allowedPackages().toList()
        granted = runCatching { HealthSync.granted(context).size }.getOrDefault(0)
    }
    val hcLauncher = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) { set ->
        granted = set.size
        if (set.isNotEmpty()) scope.launch { c.prefs.set(Prefs.Keys.healthEnabled, true) }
    }
    Screen("Интеграции") {
        item {
            AppCard(tint = Tone.current.finance) {
                Text("Уведомления банков и Mir Pay", style = MaterialTheme.typography.titleMedium)
                Secondary(if (listener) "Доступ включён. Последние захваты: ${recent.size}" else "Доступ выключен — покупки через Mir Pay не будут учитываться автоматически.")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }) { Text("Настройки доступа") }
                    TextButton(onClick = { listener = NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName) }) { Text("Проверить") }
                }
                Label("Читаются только эти приложения:")
                var text by remember(packages) { mutableStateOf(packages.joinToString("\n")) }
                Field(text, { text = it }, "Пакеты (по одному в строке)", singleLine = false)
                TextButton(onClick = {
                    act("Сохранено") {
                        val list = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
                        c.setAllowedPackages(list)
                        c.api.patch("/api/v1/settings/finance", jsonOf("notification_packages" to list))
                    }
                }) { Text("Сохранить список") }
                recent.take(5).forEach { n -> Secondary("${n.packageName}: ${n.result ?: "ожидает отправки"} · ${relative(java.time.Instant.ofEpochMilli(n.postedAt))}") }
            }
        }
        item {
            AppCard(tint = Tone.current.training) {
                Text("Health Connect · Galaxy Watch", style = MaterialTheme.typography.titleMedium)
                if (!HealthSync.available(context)) {
                    Secondary("Health Connect недоступен. Установите/обновите его и включите синхронизацию в Samsung Health → Настройки → Health Connect.")
                } else {
                    Secondary("Разрешено типов данных: $granted из ${HealthSync.PERMISSIONS.size}. Данные часов попадают сюда через Samsung Health.")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { hcLauncher.launch(HealthSync.PERMISSIONS) }) { Text("Выдать доступ") }
                        OutlinedButton(onClick = { act(null) { val n = HealthSync.syncHealth(context); c.prefs.set(Prefs.Keys.lastHealthSync, System.currentTimeMillis()); AppLog.i("health", "manual $n") } }) { Text("Синхронизировать") }
                    }
                    SwitchRow("Синхронизировать каждые 6 часов", checked = healthEnabled == true) { on -> scope.launch { c.prefs.set(Prefs.Keys.healthEnabled, on) } }
                }
            }
        }
        item {
            AppCard(tint = Tone.current.motivation) {
                Text("Экранное время", style = MaterialTheme.typography.titleMedium)
                Secondary("Отдельное разрешение. Агент видит, сколько минут в каких приложениях (например, в играх) — для анализа привычек.")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { HealthSync.openUsageSettings(context) }) { Text("Разрешение") }
                    TextButton(onClick = { act(null) { HealthSync.syncUsage(context) } }) { Text("Отправить сейчас") }
                }
                SwitchRow("Включено", if (HealthSync.usageAllowed(context)) "разрешение выдано" else "разрешение не выдано", usageEnabled == true) { on ->
                    scope.launch { c.prefs.set(Prefs.Keys.usageEnabled, on) }
                }
            }
        }
        item {
            AppCard {
                Text("Виджеты", style = MaterialTheme.typography.titleMedium)
                Secondary("Долгое нажатие на рабочем столе → Виджеты → «Второй мозг»: «Свободные деньги» и «Агент».")
            }
        }
    }
}

@Composable
fun DevicesScreen() {
    val c = LocalContainer.current
    val act = rememberAction()
    val data = rememberLoad { c.api.get("/api/v1/auth/devices").jsonObject["items"]!!.jsonArray.map { it.jsonObject } }
    var code by remember { mutableStateOf<String?>(null) }
    Screen("Устройства") {
        items(data.value ?: emptyList(), key = { it.str("id")!! }) { d ->
            AppCard(padding = 12.dp) {
                ListRow(d.str("name") ?: "", listOfNotNull(d.str("model"), d.str("app_version"),
                    if (d.str("revoked_at") != null) "отозвано" else d.str("last_seen_at")?.let { "был ${relative(parseInstant(it))}" },
                    if (d.str("current") == "true") "это устройство" else null).joinToString(" · "))
                if (d.str("revoked_at") == null && d.str("current") != "true")
                    TextButton(onClick = { act("Отозвано") { c.api.post("/api/v1/auth/devices/${d.str("id")}/revoke"); data.reload() } }) { Text("Отозвать") }
            }
        }
        item {
            AppCard(tint = Tone.current.memory) {
                Text("Новый телефон или переустановка", style = MaterialTheme.typography.titleMedium)
                Secondary("Код действует 30 минут. Если доступа к этому телефону нет — получите код на сервере: python -m app.cli pair")
                Button(onClick = { act(null) { code = c.api.post("/api/v1/auth/pairing-code").jsonObject.str("code") } }) { Text("Получить код") }
                code?.let { Text(it, style = MaterialTheme.typography.displaySmall) }
            }
        }
    }
}

@Composable
fun UpdatesScreen() {
    val c = LocalContainer.current
    val scope = rememberCoroutineScope()
    var release by remember { mutableStateOf<Release?>(null) }
    var checking by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(-1f) }
    var error by remember { mutableStateOf<String?>(null) }
    var checked by remember { mutableStateOf(false) }
    fun check() = scope.launch {
        checking = true; error = null
        try { release = c.updates.latest(); checked = true } catch (e: Exception) { error = e.message }
        checking = false
    }
    LaunchedEffect(Unit) { check() }
    Screen("Обновления") {
        item {
            AppCard {
                Label("Установлено")
                Text(BuildConfig.VERSION_NAME, style = MaterialTheme.typography.displaySmall)
                Button(onClick = { check() }, enabled = !checking) { Text(if (checking) "Проверяю…" else "Проверить обновления") }
            }
        }
        error?.let { item { ErrorCard(it) } }
        if (checked && release == null && error == null) item {
            AppCard { Secondary("Опубликованных версий пока нет — установлена актуальная.") }
        }
        release?.let { r ->
            item {
                val newer = c.updates.isNewer(r.version)
                AppCard(tint = if (newer) Tone.current.finance else Tone.current.surface) {
                    Text(if (newer) "Доступна версия ${r.version}" else "У вас последняя версия (${r.version})", style = MaterialTheme.typography.titleMedium)
                    if (r.notes.isNotBlank()) Markdown(r.notes.take(3000))
                    if (newer) {
                        if (progress >= 0f) ProgressBar(progress)
                        Button(enabled = progress < 0f, onClick = {
                            scope.launch {
                                try {
                                    if (!c.updates.canInstall()) { c.updates.openInstallPermissionSettings(); return@launch }
                                    progress = 0f
                                    val apk = c.updates.download(r) { progress = it }
                                    progress = -1f
                                    c.updates.install(apk)
                                } catch (e: Exception) { progress = -1f; error = e.message }
                            }
                        }) {
                            Text(when {
                                progress >= 0f -> "Скачиваю…"
                                c.updates.ready(r) != null -> "Установить"
                                else -> "Скачать и установить"
                            })
                        }
                        Secondary("Файл проверяется по контрольной сумме. Android попросит подтвердить установку; данные приложения сохранятся.")
                    }
                }
            }
        }
    }
}

@Composable
fun DiagnosticsScreen() {
    val c = LocalContainer.current
    val context = LocalContext.current
    val act = rememberAction()
    val sync by c.sync.state.collectAsState()
    val pending by c.db.outbox().pendingCount().collectAsState(initial = 0)
    var records by remember { mutableIntStateOf(0) }
    var server by remember { mutableStateOf("") }
    LaunchedEffect(sync.lastSyncAt) { records = c.db.records().count(); server = c.prefs.serverUrl() }
    Screen("Диагностика") {
        item {
            AppCard {
                ListRow("Версия", null, BuildConfig.VERSION_NAME)
                ListRow("Устройство", "${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}")
                ListRow("Сервер", server)
                ListRow("Последняя синхронизация", sync.lastSyncAt?.let { relative(java.time.Instant.ofEpochMilli(it)) } ?: "—")
                sync.error?.let { ListRow("Ошибка", it) }
                ListRow("Записей на телефоне", null, records.toString())
                ListRow("Ждут отправки", null, pending.toString())
                ListRow("Сбоев приложения", null, AppLog.crashes().size.toString())
            }
        }
        item {
            AppCard {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { act("Синхронизировано") { if (!c.sync.syncNow()) error(c.sync.state.value.error ?: "Не удалось") } }) { Text("Синхронизировать") }
                    OutlinedButton(onClick = { act("Полная синхронизация завершена") { c.sync.syncNow(full = true) } }) { Text("Полностью") }
                }
                Button(onClick = {
                    act("Логи отправлены разработчику на сервер") {
                        val crashes = AppLog.crashes().joinToString("\n\n") { it.readText() }
                        c.api.post("/api/v1/diagnostics/client-log", jsonOf("app_version" to BuildConfig.VERSION_NAME,
                            "device" to "${Build.MANUFACTURER} ${Build.MODEL}", "android" to Build.VERSION.RELEASE,
                            "kind" to if (crashes.isNotBlank()) "crash" else "log", "text" to (crashes + "\n\n=== log ===\n" + AppLog.read()).takeLast(1_500_000)))
                        AppLog.clearCrashes()
                    }
                }) { Text("Отправить логи") }
            }
        }
        item { SectionTitle("Проверки") }
        item {
            AppCard {
                Secondary("Проверка цепочки учёта: в приложение подаётся тестовое уведомление Mir Pay, как будто оно пришло от банка.")
                OutlinedButton(onClick = {
                    act("Тестовое уведомление отправлено в обработку") {
                        val now = java.time.LocalDateTime.now()
                        injectTestBankNotification(context.applicationContext as SecondBrainApp, "ru.nspk.mirpay", "Оплата покупки",
                            "Сумма 1,00 ₽. ${now.format(java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy, HH:mm"))}. Карта Mir •••• 0000")
                    }
                }) { Text("Тест: уведомление Mir Pay на 1 ₽") }
                OutlinedButton(onClick = {
                    act(null) { c.notifier.simple(4242, "Проверка уведомлений", "Если вы это видите — уведомления работают.", AgentNotifier.CH_AGENT) }
                }) { Text("Тест: системное уведомление") }
            }
        }
    }
}
