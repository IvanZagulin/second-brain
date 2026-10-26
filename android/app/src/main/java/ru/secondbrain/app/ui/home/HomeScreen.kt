package ru.secondbrain.app.ui.home

import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ListAlt
import androidx.compose.material.icons.outlined.Analytics
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.EmojiEmotions
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Loop
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import ru.secondbrain.app.core.Prefs
import ru.secondbrain.app.core.daysWord
import ru.secondbrain.app.core.nowIso
import ru.secondbrain.app.core.parseDate
import ru.secondbrain.app.core.parseInstant
import ru.secondbrain.app.core.relative
import ru.secondbrain.app.core.ruDate
import ru.secondbrain.app.core.ruWeekday
import ru.secondbrain.app.core.rub
import ru.secondbrain.app.data.api.PlainJson
import ru.secondbrain.app.data.model.E
import ru.secondbrain.app.data.model.Task
import ru.secondbrain.app.data.repo.jsonOf
import ru.secondbrain.app.data.repo.str
import ru.secondbrain.app.ui.components.AppCard
import ru.secondbrain.app.ui.components.BigNumber
import ru.secondbrain.app.ui.components.Divider
import ru.secondbrain.app.ui.components.Gap
import ru.secondbrain.app.ui.components.Label
import ru.secondbrain.app.ui.components.ListRow
import ru.secondbrain.app.ui.components.LocalContainer
import ru.secondbrain.app.ui.components.LocalNavigate
import ru.secondbrain.app.ui.components.Markdown
import ru.secondbrain.app.ui.components.Pill
import ru.secondbrain.app.ui.components.ProgressBar
import ru.secondbrain.app.ui.components.Screen
import ru.secondbrain.app.ui.components.SectionTitle
import ru.secondbrain.app.ui.components.Secondary
import ru.secondbrain.app.ui.components.SwitchRow
import ru.secondbrain.app.ui.tasks.MoodSheet
import ru.secondbrain.app.ui.theme.Tone
import java.time.LocalDate

val HOME_CARDS = linkedMapOf(
    "observation" to "Наблюдение агента", "finance" to "Деньги на сегодня", "obligations" to "Обязательства",
    "workout" to "Ближайшая тренировка", "tasks" to "Задачи дня", "state" to "Состояние", "events" to "Последние события",
    "budget" to "Расходы на Claude API",
)
val HomeCardsKey = stringPreferencesKey("home_cards")

@Composable
fun HomeScreen() {
    val c = LocalContainer.current
    val nav = LocalNavigate.current
    val scope = rememberCoroutineScope()
    val cached by c.prefs.flow(Prefs.Keys.homeCache).collectAsState(initial = null)
    val cardsPref by c.prefs.flow(HomeCardsKey).collectAsState(initial = null)
    val cards = cardsPref?.split(",")?.toSet() ?: HOME_CARDS.keys
    val home = cached?.let { runCatching { PlainJson.parseToJsonElement(it).jsonObject }.getOrNull() }
    val tasks by c.store.observe(E.TASKS, Task.serializer()).collectAsState(initial = emptyList())
    var moodOpen by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { c.sync.refreshHome() }

    Screen(title = home?.str("greeting") ?: "Привет", subtitle = "${ruWeekday(LocalDate.now()).replaceFirstChar { it.uppercase() }}, ${ruDate(LocalDate.now())}",
        back = false, onRefresh = { c.sync.syncNow() },
        actions = { IconButton(onClick = { nav("more") }) { Icon(Icons.Outlined.Tune, "Разделы") } }) {

        item {
            AppCard(onClick = { scope.launch { nav("chat/${c.chat.newConversation()}") } }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.AutoAwesome, null, tint = Tone.current.accent)
                    Text("  Спросить агента…", style = MaterialTheme.typography.bodyLarge, color = Tone.current.textSecondary)
                }
            }
        }

        val obs = home?.get("observation") as? JsonObject
        if ("observation" in cards && obs != null) item {
            AppCard(tint = Tone.current.memory, onClick = { obs.str("conversation_id")?.let { nav("chat/$it") } }) {
                Label("Агент · ${relative(parseInstant(obs.str("at")))}")
                Gap(6)
                Text(obs.str("text") ?: "", style = MaterialTheme.typography.bodyLarge, maxLines = 6, overflow = TextOverflow.Ellipsis)
            }
        }

        val fin = home?.get("finance") as? JsonObject
        if ("finance" in cards) item {
            AppCard(tint = Tone.current.finance, onClick = { nav("finance") }) {
                Label("Можно потратить сегодня" + if (fin?.str("estimated") == "true") " · оценка" else "")
                BigNumber(rub(fin?.get("today_left_minor")?.jsonPrimitive?.longOrNull),
                    if ((fin?.get("today_left_minor")?.jsonPrimitive?.longOrNull ?: 0) < 0) Tone.current.negative else Tone.current.text)
                val days = fin?.get("days_until_income")?.jsonPrimitive?.longOrNull
                Secondary("Свободно ${rub(fin?.get("free_minor")?.jsonPrimitive?.longOrNull)}" +
                    (days?.let { " · до поступления $it ${daysWord(it)}" } ?: ""))
                if (fin?.str("savings_mode") == "true") { Gap(6); Pill("Режим экономии", Tone.current.motivation) }
            }
        }

        val obligations = fin?.get("obligations")?.jsonArray?.map { it.jsonObject } ?: emptyList()
        if ("obligations" in cards && obligations.isNotEmpty()) item {
            AppCard {
                Text("Обязательства", style = MaterialTheme.typography.titleMedium)
                obligations.forEach { o ->
                    val due = parseDate(o.str("due"))
                    ListRow(o.str("title") ?: "", due?.let { ruDate(it) }, rub(o["amount_minor"]?.jsonPrimitive?.longOrNull))
                }
            }
        }

        val nw = home?.get("next_workout") as? JsonObject
        if ("workout" in cards) item {
            AppCard(tint = Tone.current.training, onClick = { nav("fitness") }) {
                Label("Ближайшая тренировка")
                if (nw == null) Text("Дни тренировок не заданы", style = MaterialTheme.typography.titleMedium)
                else {
                    val inDays = nw["in_days"]?.jsonPrimitive?.longOrNull ?: 0
                    val whenText = when (inDays) { 0L -> "Сегодня"; 1L -> "Завтра"; else -> parseDate(nw.str("date"))?.let { ruWeekday(it).replaceFirstChar { c -> c.uppercase() } } ?: "" }
                    Text("$whenText${nw.str("time")?.let { " · $it" } ?: ""}", style = MaterialTheme.typography.headlineSmall)
                    Secondary(nw.str("plan_name") ?: "Программа не выбрана")
                }
            }
        }

        val openTasks = tasks.filter { it.status == "open" && (it.due == null || parseDate(it.dueAt)?.let { d -> !d.isAfter(LocalDate.now()) } == true) }
            .sortedWith(compareBy({ it.due == null }, { it.due })).take(6)
        if ("tasks" in cards) item {
            AppCard {
                Text("Задачи дня", style = MaterialTheme.typography.titleMedium)
                if (openTasks.isEmpty()) Secondary("На сегодня задач нет")
                openTasks.forEach { t ->
                    val overdue = t.due?.isBefore(java.time.Instant.now()) == true
                    ListRow(t.title, if (overdue) "просрочено" else t.due?.let { relative(it) },
                        icon = Icons.Outlined.RadioButtonUnchecked, iconTint = Tone.current.bg,
                        onClick = { scope.launch { c.store.save(E.TASKS, jsonOf("status" to "done", "completed_at" to nowIso()), t.id) } })
                }
                OutlinedButton(onClick = { nav("tasks") }) { Text("Открыть задачи") }
            }
        }

        val mood = home?.get("mood") as? JsonObject
        if ("state" in cards) item {
            AppCard(tint = Tone.current.motivation) {
                Label("Состояние")
                if (mood != null) {
                    Text("Настроение ${mood.str("score")}/10", style = MaterialTheme.typography.headlineSmall)
                    Secondary(listOfNotNull(mood.str("cause"), relative(parseInstant(mood.str("occurred_at")))).joinToString(" · "))
                } else Secondary("Настроение ещё не записано")
                Gap(6)
                Button(onClick = { moodOpen = true }) { Text("Записать настроение") }
            }
        }

        val events = home?.get("events")?.jsonArray?.map { it.jsonObject } ?: emptyList()
        if ("events" in cards && events.isNotEmpty()) item {
            AppCard(onClick = { nav("memory") }) {
                Text("Последние события", style = MaterialTheme.typography.titleMedium)
                events.forEach { e -> ListRow(e.str("title") ?: "", relative(parseInstant(e.str("occurred_at")))) }
            }
        }

        val budget = home?.get("api_budget") as? JsonObject
        if ("budget" in cards && budget != null) item {
            AppCard(onClick = { nav("settings/usage") }) {
                val spent = budget.str("spent_usd")?.toDoubleOrNull() ?: 0.0
                val limit = budget.str("limit_usd")?.toDoubleOrNull() ?: 100.0
                Label("Claude API в этом месяце")
                Text("$%.2f из $%.0f".format(spent, limit), style = MaterialTheme.typography.titleMedium)
                Gap(6)
                ProgressBar((spent / limit).toFloat().coerceIn(0f, 1f))
            }
        }

        item { SectionTitle("Разделы") }
        item { SectionsGrid(nav) }
    }
    if (moodOpen) MoodSheet { moodOpen = false }
}

@Composable
private fun SectionsGrid(nav: (String) -> Unit) {
    val items = listOf(
        Triple("Задачи и цели", Icons.AutoMirrored.Outlined.ListAlt, "tasks"),
        Triple("Привычки", Icons.Outlined.Loop, "habits"),
        Triple("Эксперименты", Icons.Outlined.Science, "experiments"),
        Triple("Настроение", Icons.Outlined.EmojiEmotions, "mood"),
        Triple("Заметки", Icons.Outlined.EditNote, "notes"),
        Triple("Аналитика", Icons.Outlined.Analytics, "analytics"),
        Triple("История агента", Icons.Outlined.History, "agent/history"),
        Triple("Обновления", Icons.Outlined.SystemUpdate, "updates"),
        Triple("Настройки", Icons.Outlined.Settings, "settings"),
    )
    AppCard(padding = 6.dp) {
        items.forEachIndexed { i, (label, icon, route) ->
            ListRow(label, icon = icon, iconTint = Tone.current.bg, onClick = { nav(route) })
            if (i != items.lastIndex) Divider()
        }
    }
}

@Composable
fun MoreScreen() {
    val c = LocalContainer.current
    val scope = rememberCoroutineScope()
    val pref by c.prefs.flow(HomeCardsKey).collectAsState(initial = null)
    val enabled = pref?.split(",")?.toSet() ?: HOME_CARDS.keys
    val nav = LocalNavigate.current
    Screen("Разделы и главная") {
        item { SectionsGrid(nav) }
        item { SectionTitle("Карточки на главной") }
        item {
            AppCard {
                HOME_CARDS.forEach { (key, label) ->
                    SwitchRow(label, checked = key in enabled) { on ->
                        val next = if (on) enabled + key else enabled - key
                        scope.launch { c.prefs.set(HomeCardsKey, next.joinToString(",")) }
                    }
                }
            }
        }
    }
}
