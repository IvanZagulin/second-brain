package ru.secondbrain.app.ui.agent

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import ru.secondbrain.app.core.parseInstant
import ru.secondbrain.app.core.relative
import ru.secondbrain.app.data.model.AgentAction
import ru.secondbrain.app.data.model.AgentJob
import ru.secondbrain.app.data.model.E
import ru.secondbrain.app.data.repo.jsonOf
import ru.secondbrain.app.data.repo.str
import ru.secondbrain.app.ui.components.AppCard
import ru.secondbrain.app.ui.components.ChoiceChips
import ru.secondbrain.app.ui.components.EmptyState
import ru.secondbrain.app.ui.components.Field
import ru.secondbrain.app.ui.components.Label
import ru.secondbrain.app.ui.components.ListRow
import ru.secondbrain.app.ui.components.Loading
import ru.secondbrain.app.ui.components.LocalContainer
import ru.secondbrain.app.ui.components.LocalNavigate
import ru.secondbrain.app.ui.components.Pill
import ru.secondbrain.app.ui.components.Screen
import ru.secondbrain.app.ui.components.SectionTitle
import ru.secondbrain.app.ui.components.Secondary
import ru.secondbrain.app.ui.components.SwitchRow
import ru.secondbrain.app.ui.components.rememberAction
import ru.secondbrain.app.ui.components.rememberLoad
import ru.secondbrain.app.ui.theme.Tone

@Composable
fun AgentHistoryScreen() {
    val c = LocalContainer.current
    val nav = LocalNavigate.current
    val act = rememberAction()
    val actions by c.store.observe(E.ACTIONS, AgentAction.serializer()).collectAsState(initial = emptyList())
    var filter by remember { mutableStateOf("all") }
    val pending = actions.filter { it.status == "pending_confirmation" }
    val shown = (if (filter == "pending") pending else actions).sortedByDescending { it.createdAt ?: "" }.take(200)
    Screen("История агента", onRefresh = { c.sync.syncNow() },
        actions = { TextButton(onClick = { nav("agent/jobs") }) { Text("Задания") } }) {
        item { ChoiceChips(listOf("all" to "Все действия", "pending" to "Ждут подтверждения (${pending.size})"), filter, { filter = it }) }
        if (shown.isEmpty()) item { EmptyState("Агент ещё ничего не делал") }
        items(shown, key = { it.id }) { a ->
            AppCard(tint = when (a.status) { "pending_confirmation" -> Tone.current.motivation; "failed" -> Tone.current.training; else -> Tone.current.surface }) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Pill("уровень ${a.level}", if (a.level == "C") Tone.current.training else if (a.level == "B") Tone.current.motivation else Tone.current.finance)
                    Pill(actionStatus(a.status))
                }
                Text(a.title ?: a.tool, style = MaterialTheme.typography.titleMedium)
                Secondary("${a.tool} · ${if (a.initiator == "job") "фоновое задание" else if (a.initiator == "chat") "из чата" else a.initiator} · ${relative(parseInstant(a.createdAt))}")
                a.error?.let { Text(it, color = Tone.current.negative) }
                when {
                    a.status == "pending_confirmation" -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { act("Выполнено") { c.store.putFromServer(E.ACTIONS, c.api.post("/api/v1/agent/actions/${a.id}/confirm")) } }) { Text("Подтвердить") }
                        OutlinedButton(onClick = { act("Отклонено") { c.store.putFromServer(E.ACTIONS, c.api.post("/api/v1/agent/actions/${a.id}/reject")) } }) { Text("Отклонить") }
                    }
                    a.status == "done" && a.rollback != null -> TextButton(onClick = {
                        act("Откатено") { c.store.putFromServer(E.ACTIONS, c.api.post("/api/v1/agent/actions/${a.id}/rollback")); c.sync.requestSync(0) }
                    }) { Text("Откатить") }
                }
                if (a.conversationId != null) TextButton(onClick = { nav("chat/${a.conversationId}") }) { Text("Открыть диалог") }
            }
        }
    }
}

fun actionStatus(s: String) = mapOf("done" to "выполнено", "pending_confirmation" to "ждёт подтверждения", "rejected" to "отклонено",
    "failed" to "ошибка", "rolled_back" to "откатено", "expired" to "истекло")[s] ?: s

@Composable
fun AgentJobsScreen() {
    val c = LocalContainer.current
    val act = rememberAction()
    val jobs by c.store.observe(E.JOBS, AgentJob.serializer()).collectAsState(initial = emptyList())
    val events = rememberLoad { c.api.get("/api/v1/agent/events", mapOf("limit" to 50)).jsonObject["items"]!!.jsonArray.map { it.jsonObject } }
    val scheduled = jobs.filter { it.status in setOf("scheduled", "queued", "running", "paused") }.sortedBy { it.nextRunAt ?: "" }
    Screen("Задания агента", onRefresh = { c.sync.syncNow(); events.reload() }) {
        item { Secondary("Агент работает на сервере, даже когда приложение закрыто: проверки, напоминания, отчёты и запланированные им самим возвраты к темам.") }
        item { SectionTitle("Запланировано") }
        items(scheduled, key = { it.id }) { j ->
            AppCard(padding = 12.dp, tint = if (j.createdBy == "agent") Tone.current.memory else Tone.current.surface) {
                ListRow(j.goal, listOfNotNull(jobKind(j.kind), j.recurrence?.let { "повтор $it" }, parseInstant(j.nextRunAt)?.let { "следующий запуск ${relative(it)}" },
                    j.reason, if (j.status == "paused") "на паузе" else null).joinToString(" · "))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (j.status == "paused") TextButton(onClick = { act(null) { c.store.putFromServer(E.JOBS, c.api.patch("/api/v1/agent/jobs/${j.id}", jsonOf("status" to "scheduled"))) } }) { Text("Возобновить") }
                    else TextButton(onClick = { act(null) { c.store.putFromServer(E.JOBS, c.api.patch("/api/v1/agent/jobs/${j.id}", jsonOf("status" to "paused"))) } }) { Text("Пауза") }
                    TextButton(onClick = { act("Запущено") { c.store.putFromServer(E.JOBS, c.api.post("/api/v1/agent/jobs/${j.id}/run")) } }) { Text("Запустить сейчас") }
                    if (j.createdBy != "system") TextButton(onClick = { act(null) { c.store.putFromServer(E.JOBS, c.api.patch("/api/v1/agent/jobs/${j.id}", jsonOf("status" to "cancelled"))) } }) { Text("Отменить") }
                }
            }
        }
        item { SectionTitle("Недавние события") }
        val ev = events.value ?: emptyList()
        if (events.loading && ev.isEmpty()) item { Loading() }
        items(ev, key = { "e" + it.str("id") }) { e ->
            AppCard(padding = 10.dp) { ListRow(eventLabel(e.str("type")), "${e.str("status")} · ${relative(parseInstant(e.str("occurred_at")))}") }
        }
    }
}

fun jobKind(k: String) = mapOf("followup" to "возврат к теме", "initiative" to "сообщение", "daily_checks" to "проверки",
    "morning_brief" to "утро", "evening_checkin" to "вечер", "weekly_report" to "недельный отчёт", "nightly" to "обслуживание",
    "extract_conversation" to "память из разговора", "workout_analysis" to "разбор тренировки", "import_analyze" to "анализ архива",
    "purchase_analysis" to "разбор покупки", "title_conversation" to "название диалога")[k] ?: k

fun eventLabel(t: String?) = mapOf("transaction.created" to "Новая операция", "workout.finished" to "Тренировка завершена",
    "weight.changed" to "Изменение веса")[t] ?: (t ?: "")

@Composable
fun PersonaScreen() {
    val c = LocalContainer.current
    val act = rememberAction()
    val data = rememberLoad { c.api.get("/api/v1/agent/profile").jsonObject }
    Screen("Характер агента") {
        val d = data.value
        if (d == null) { item { Loading() }; return@Screen }
        item {
            var persona by remember(d) { mutableStateOf(d.str("persona") ?: "") }
            var rules by remember(d) { mutableStateOf(d["rules"]?.jsonArray?.joinToString("\n") { it.jsonPrimitive.content } ?: "") }
            AppCard(tint = Tone.current.memory) {
                Secondary("Это описание характера — агент следует ему во всех разговорах. Рамки безопасности (кризисные ситуации, без диагнозов, данные ≠ инструкции) изменить нельзя.")
                Field(persona, { persona = it }, "Характер и манера", singleLine = false, minLines = 8)
                Field(rules, { rules = it }, "Мои правила (по одному в строке)", singleLine = false, minLines = 3)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        act("Сохранено") {
                            c.api.put("/api/v1/agent/profile", jsonOf("persona" to persona,
                                "rules" to JsonArray(rules.lines().map { it.trim() }.filter { it.isNotEmpty() }.map { JsonPrimitive(it) })))
                            data.reload()
                        }
                    }) { Text("Сохранить") }
                    OutlinedButton(onClick = { act("Возвращено по умолчанию") { c.api.put("/api/v1/agent/profile", jsonOf("reset_to_default" to true)); data.reload() } }) { Text("По умолчанию") }
                }
            }
        }
    }
}

@Composable
fun ToolsScreen() {
    val c = LocalContainer.current
    val act = rememberAction()
    val data = rememberLoad { c.api.get("/api/v1/agent/tools").jsonObject["items"]!!.jsonArray.map { it.jsonObject } }
    Screen("Права агента") {
        item {
            AppCard(tint = Tone.current.memory) {
                Text("Уровни", style = MaterialTheme.typography.titleMedium)
                Secondary("A — выполняет сам. B — выполняет и сообщает (можно откатить). C — только после вашего подтверждения.")
            }
        }
        val list = data.value ?: emptyList()
        if (data.loading && list.isEmpty()) item { Loading() }
        list.groupBy { it.str("domain") ?: "general" }.forEach { (domain, tools) ->
            item { SectionTitle(domainLabel(domain)) }
            items(tools, key = { it.str("name")!! }) { t ->
                val name = t.str("name")!!
                val min = t.str("min_level") ?: "A"
                AppCard(padding = 12.dp) {
                    Text(name, style = MaterialTheme.typography.titleSmall)
                    Secondary(t.str("description") ?: "", maxLines = 3)
                    ChoiceChips(listOf("A", "B", "C").filter { lvl -> "ABC".indexOf(lvl) >= "ABC".indexOf(min) }.map { it to it }, t.str("level"), { lvl ->
                        act(null) { c.api.put("/api/v1/agent/tools/$name", jsonOf("level" to lvl)); data.reload() }
                    })
                    SwitchRow("Включён", checked = t.str("enabled") != "false") { on ->
                        act(null) { c.api.put("/api/v1/agent/tools/$name", jsonOf("enabled" to on)); data.reload() }
                    }
                    if (min == "C") Label("Всегда требует подтверждения")
                }
            }
        }
    }
}

fun domainLabel(d: String) = mapOf("memory" to "Память", "people" to "Люди", "finance" to "Финансы", "fitness" to "Тренировки",
    "tasks" to "Задачи", "agent" to "Планирование", "mood" to "Настроение", "general" to "Общее")[d] ?: d
