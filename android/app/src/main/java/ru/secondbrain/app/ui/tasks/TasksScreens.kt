package ru.secondbrain.app.ui.tasks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.Button
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.jsonObject
import ru.secondbrain.app.core.local
import ru.secondbrain.app.core.nowIso
import ru.secondbrain.app.core.parseDate
import ru.secondbrain.app.core.parseInstant
import ru.secondbrain.app.core.relative
import ru.secondbrain.app.core.ruDate
import ru.secondbrain.app.core.ruWeekdayShort
import ru.secondbrain.app.data.model.E
import ru.secondbrain.app.data.model.Experiment
import ru.secondbrain.app.data.model.Habit
import ru.secondbrain.app.data.model.HabitLog
import ru.secondbrain.app.data.model.MoodEntry
import ru.secondbrain.app.data.model.Note
import ru.secondbrain.app.data.model.Task
import ru.secondbrain.app.data.repo.jsonOf
import ru.secondbrain.app.data.repo.str
import ru.secondbrain.app.ui.components.AppCard
import ru.secondbrain.app.ui.components.ChoiceChips
import ru.secondbrain.app.ui.components.DateField
import ru.secondbrain.app.ui.components.Dropdown
import ru.secondbrain.app.ui.components.EmptyState
import ru.secondbrain.app.ui.components.Field
import ru.secondbrain.app.ui.components.Gap
import ru.secondbrain.app.ui.components.Label
import ru.secondbrain.app.ui.components.LineChart
import ru.secondbrain.app.ui.components.ListRow
import ru.secondbrain.app.ui.components.LocalContainer
import ru.secondbrain.app.ui.components.Markdown
import ru.secondbrain.app.ui.components.Pill
import ru.secondbrain.app.ui.components.Screen
import ru.secondbrain.app.ui.components.SectionTitle
import ru.secondbrain.app.ui.components.Secondary
import ru.secondbrain.app.ui.components.Sheet
import ru.secondbrain.app.ui.components.TimeField
import ru.secondbrain.app.ui.components.WeekStrip
import ru.secondbrain.app.ui.components.rememberAction
import ru.secondbrain.app.ui.theme.Tone
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

@Composable
fun TasksScreen() {
    val c = LocalContainer.current
    val act = rememberAction()
    val tasks by c.store.observe(E.TASKS, Task.serializer()).collectAsState(initial = emptyList())
    var filter by remember { mutableStateOf("open") }
    var editing by remember { mutableStateOf<Task?>(null) }
    val top = tasks.filter { it.parentId == null }
    val shown = when (filter) {
        "open" -> top.filter { it.status == "open" }.sortedWith(compareBy({ it.due == null }, { it.dueAt }, { -it.priority }))
        "proposed" -> top.filter { it.status == "proposed" }
        else -> top.filter { it.status == "done" }.sortedByDescending { it.completedAt ?: "" }.take(100)
    }
    val proposed = top.count { it.status == "proposed" }
    Screen("Задачи и обещания", fab = { ExtendedFloatingActionButton(onClick = { editing = Task() }, icon = { Icon(Icons.Default.Add, null) }, text = { Text("Задача") }) }) {
        item { ChoiceChips(listOf("open" to "Открытые", "proposed" to "Предложены агентом${if (proposed > 0) " ($proposed)" else ""}", "done" to "Выполнены"), filter, { filter = it }) }
        if (shown.isEmpty()) item { EmptyState(if (filter == "proposed") "Агент пока ничего не предложил" else "Пусто") }
        items(shown, key = { it.id }) { t ->
            val subs = tasks.filter { it.parentId == t.id && it.status != "cancelled" }
            val overdue = t.status == "open" && t.due?.isBefore(java.time.Instant.now()) == true
            AppCard(padding = 12.dp, tint = if (overdue) Tone.current.training else Tone.current.surface, onClick = { editing = t }) {
                ListRow(t.title, listOfNotNull(
                    t.due?.let { if (overdue) "просрочено (${relative(it)})" else relative(it) },
                    commitmentLabel(t.commitment).takeIf { t.commitment != "firm" || t.source != "user" },
                    if (t.postponedCount > 0) "переносилась ${t.postponedCount}×" else null,
                    t.repeatRule?.let { "повтор" }, if (subs.isNotEmpty()) "подзадач ${subs.count { it.status == "done" }}/${subs.size}" else null,
                ).joinToString(" · "),
                    icon = if (t.status == "done") Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                    iconTint = Tone.current.bg, onClick = {
                        act(null) {
                            if (t.status == "done") c.store.save(E.TASKS, jsonOf("status" to "open", "completed_at" to null), t.id)
                            else c.store.save(E.TASKS, jsonOf("status" to "done", "completed_at" to nowIso()), t.id)
                        }
                    })
                if (t.status == "proposed") Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { act(null) { c.store.save(E.TASKS, jsonOf("status" to "open"), t.id) } }) { Text("Принять") }
                    TextButton(onClick = { act(null) { c.store.save(E.TASKS, jsonOf("status" to "cancelled"), t.id) } }) { Text("Не нужно") }
                }
            }
        }
    }
    editing?.let { t -> TaskSheet(t, tasks.filter { it.parentId == t.id && t.id.isNotEmpty() }) { editing = null } }
}

fun commitmentLabel(c: String) = mapOf("firm" to "обещание", "tentative" to "намерение", "idea" to "идея")[c] ?: c

@Composable
private fun TaskSheet(t: Task, subtasks: List<Task>, onClose: () -> Unit) {
    val c = LocalContainer.current
    val act = rememberAction()
    val zone = ZoneId.systemDefault()
    var title by remember { mutableStateOf(t.title) }
    var notes by remember { mutableStateOf(t.notes ?: "") }
    var date by remember { mutableStateOf(t.due?.atZone(zone)?.toLocalDate()) }
    var time by remember { mutableStateOf(if (t.hasTime) t.due?.atZone(zone)?.toLocalTime() else null) }
    var priority by remember { mutableIntStateOf(t.priority) }
    var commitment by remember { mutableStateOf(t.commitment) }
    var repeat by remember { mutableStateOf(t.repeatRule ?: "") }
    var remind by remember { mutableStateOf(t.remindAt != null) }
    var sub by remember { mutableStateOf("") }
    Sheet(onClose) {
        Text(if (t.id.isEmpty()) "Новая задача" else "Задача", style = MaterialTheme.typography.headlineSmall)
        Field(title, { title = it }, "Что сделать")
        Field(notes, { notes = it }, "Заметки", singleLine = false)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DateField("Срок", date, { date = it }, Modifier.weight(1f))
            TimeField("Время", time, { time = it }, Modifier.weight(1f))
        }
        Label("Приоритет")
        ChoiceChips(listOf(0 to "Низкий", 1 to "Обычный", 2 to "Высокий", 3 to "Срочно"), priority, { priority = it })
        Label("Что это")
        ChoiceChips(listOf("firm" to "Обещание", "tentative" to "Намерение", "idea" to "Идея"), commitment, { commitment = it })
        Dropdown("Повтор", listOf("" to "Нет", "daily" to "Каждый день", "weekdays" to "По будням", "weekly:${(date ?: LocalDate.now()).dayOfWeek.value}" to "Каждую неделю",
            "monthly:${(date ?: LocalDate.now()).dayOfMonth}" to "Каждый месяц"), repeat, { repeat = it })
        ru.secondbrain.app.ui.components.SwitchRow("Напомнить в срок", checked = remind) { remind = it }
        if (t.id.isNotEmpty()) {
            Label("Подзадачи")
            subtasks.forEach { s ->
                ListRow(s.title, null, icon = if (s.status == "done") Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                    iconTint = Tone.current.bg, onClick = { act(null) { c.store.save(E.TASKS, jsonOf("status" to if (s.status == "done") "open" else "done"), s.id) } })
            }
            Row { Field(sub, { sub = it }, "Новая подзадача", Modifier.weight(1f))
                TextButton(onClick = { if (sub.isNotBlank()) act(null) { c.store.save(E.TASKS, jsonOf("title" to sub, "parent_id" to t.id, "status" to "open")); sub = "" } }) { Text("+") } }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = title.isNotBlank(), onClick = {
                val due = date?.atTime(time ?: LocalTime.of(23, 59))?.atZone(zone)?.toOffsetDateTime()?.toString()
                val postponed = t.due != null && due != null && parseInstant(due)!!.isAfter(t.due)
                act(null) {
                    c.store.save(E.TASKS, jsonOf("title" to title, "notes" to notes.ifBlank { null }, "due_at" to due, "has_time" to (time != null),
                        "priority" to priority, "commitment" to commitment, "repeat_rule" to repeat.ifBlank { null },
                        "remind_at" to if (remind) due else null, "status" to if (t.id.isEmpty()) "open" else t.status,
                        "postponed_count" to t.postponedCount + if (postponed) 1 else 0), t.id.ifEmpty { null })
                    onClose()
                }
            }) { Text("Сохранить") }
            if (t.id.isNotEmpty()) OutlinedButton(onClick = {
                act("Перенесено на завтра") {
                    val base = (t.due ?: java.time.Instant.now()).atZone(zone).plusDays(1).toOffsetDateTime().toString()
                    c.store.save(E.TASKS, jsonOf("due_at" to base, "postponed_count" to t.postponedCount + 1), t.id); onClose()
                }
            }) { Text("На завтра") }
        }
        if (t.id.isNotEmpty()) TextButton(onClick = { act(null) { c.store.save(E.TASKS, jsonOf("status" to "cancelled"), t.id); onClose() } }) {
            Text("Отменить задачу", color = Tone.current.negative)
        }
    }
}

@Composable
fun HabitsScreen() {
    val c = LocalContainer.current
    val act = rememberAction()
    val habits by c.store.observe(E.HABITS, Habit.serializer()).collectAsState(initial = emptyList())
    val logs by c.store.observe(E.HABIT_LOGS, HabitLog.serializer()).collectAsState(initial = emptyList())
    var adding by remember { mutableStateOf(false) }
    val today = LocalDate.now()
    Screen("Привычки", fab = { ExtendedFloatingActionButton(onClick = { adding = true }, icon = { Icon(Icons.Default.Add, null) }, text = { Text("Привычка") }) }) {
        if (habits.isEmpty()) item { EmptyState("Добавьте привычку — хорошую, которую нужно держать, или плохую, от которой отказываетесь.") }
        items(habits.filter { it.isActive }, key = { it.id }) { h ->
            val my = logs.filter { it.habitId == h.id }.mapNotNull { parseDate(it.day) }.toSet()
            var streak = 0
            var d = if (today in my) today else today.minusDays(1)
            while (d in my) { streak++; d = d.minusDays(1) }
            val done = today in my
            AppCard(tint = if (h.kind == "bad") Tone.current.training else Tone.current.motivation) {
                Row {
                    Text(h.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    Pill(if (h.kind == "bad") "отказ" else "держать")
                }
                Secondary("Серия: $streak ${ru.secondbrain.app.core.daysWord(streak.toLong())}")
                Gap(6)
                WeekStrip((6 downTo 0).map { back -> val day = today.minusDays(back.toLong()); ruWeekdayShort(day.dayOfWeek.value) to (day in my) })
                Button(onClick = {
                    act(null) {
                        val existing = logs.firstOrNull { it.habitId == h.id && it.day == today.toString() }
                        if (existing != null) c.store.delete(E.HABIT_LOGS, existing.id)
                        else c.store.save(E.HABIT_LOGS, jsonOf("habit_id" to h.id, "day" to today.toString(), "value" to 1))
                    }
                }) { Text(if (done) "Сегодня ✓ (отменить)" else if (h.kind == "bad") "Сегодня удержался" else "Сделал сегодня") }
            }
        }
    }
    if (adding) Sheet({ adding = false }) {
        var name by remember { mutableStateOf("") }
        var kind by remember { mutableStateOf("good") }
        Field(name, { name = it }, "Название")
        ChoiceChips(listOf("good" to "Держать", "bad" to "Отказаться"), kind, { kind = it })
        Button(enabled = name.isNotBlank(), onClick = { act(null) { c.store.save(E.HABITS, jsonOf("name" to name, "kind" to kind, "is_active" to true)); adding = false } }) { Text("Добавить") }
    }
}

@Composable
fun ExperimentsScreen() {
    val c = LocalContainer.current
    val act = rememberAction()
    val experiments by c.store.observe(E.EXPERIMENTS, Experiment.serializer()).collectAsState(initial = emptyList())
    var adding by remember { mutableStateOf(false) }
    Screen("Эксперименты", fab = { ExtendedFloatingActionButton(onClick = { adding = true }, icon = { Icon(Icons.Default.Add, null) }, text = { Text("Эксперимент") }) }) {
        item { Secondary("Гипотеза → срок → данные → вывод. Например: «без донатов месяц — минус 5 000 ₽ и лучше сон».") }
        if (experiments.isEmpty()) item { EmptyState("Экспериментов пока нет") }
        items(experiments.sortedByDescending { it.startDate ?: "" }, key = { it.id }) { e ->
            AppCard(tint = if (e.status == "active") Tone.current.motivation else Tone.current.surface) {
                Text(e.title, style = MaterialTheme.typography.titleMedium)
                Secondary(e.hypothesis)
                Secondary("${parseDate(e.startDate)?.let { ruDate(it) }} — ${parseDate(e.endDate)?.let { ruDate(it) }} · ${expStatus(e.status)}")
                e.conclusion?.let { Gap(6); Markdown(it) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { act("Анализ готов") { c.store.putFromServer(E.EXPERIMENTS, c.api.post("/api/v1/experiments/${e.id}/evaluate")) } }) { Text("Подвести итог") }
                    if (e.status == "active") TextButton(onClick = { act(null) { c.store.save(E.EXPERIMENTS, jsonOf("status" to "abandoned"), e.id) } }) { Text("Бросить") }
                }
            }
        }
    }
    if (adding) Sheet({ adding = false }) {
        var title by remember { mutableStateOf("") }
        var hypothesis by remember { mutableStateOf("") }
        var start by remember { mutableStateOf<LocalDate?>(LocalDate.now()) }
        var end by remember { mutableStateOf<LocalDate?>(LocalDate.now().plusDays(30)) }
        var metrics by remember { mutableStateOf("") }
        Field(title, { title = it }, "Название")
        Field(hypothesis, { hypothesis = it }, "Гипотеза", singleLine = false)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DateField("Начало", start, { start = it }, Modifier.weight(1f))
            DateField("Конец", end, { end = it }, Modifier.weight(1f))
        }
        Field(metrics, { metrics = it }, "Что измеряем (траты на игры, сон, вес…)", singleLine = false)
        Button(enabled = title.isNotBlank() && hypothesis.isNotBlank(), onClick = {
            act(null) {
                c.store.save(E.EXPERIMENTS, jsonOf("title" to title, "hypothesis" to hypothesis, "start_date" to start.toString(),
                    "end_date" to end.toString(), "status" to "active",
                    "metrics" to metrics.split(",", "\n").map { it.trim() }.filter { it.isNotEmpty() }.map { mapOf("key" to it) }))
                adding = false
            }
        }) { Text("Начать") }
    }
}

fun expStatus(s: String) = mapOf("planned" to "запланирован", "active" to "идёт", "finished" to "завершён", "abandoned" to "брошен")[s] ?: s

@Composable
fun MoodSheet(onClose: () -> Unit) {
    val c = LocalContainer.current
    val act = rememberAction()
    var score by remember { mutableIntStateOf(6) }
    var energy by remember { mutableIntStateOf(6) }
    var cause by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    Sheet(onClose) {
        Text("Как вы сейчас?", style = MaterialTheme.typography.headlineSmall)
        Label("Настроение")
        ChoiceChips((1..10).map { it to "$it" }, score, { score = it })
        Label("Энергия")
        ChoiceChips((1..10).map { it to "$it" }, energy, { energy = it })
        Field(cause, { cause = it }, "Почему так")
        Field(note, { note = it }, "Заметка", singleLine = false)
        Button(onClick = {
            act("Записано") {
                c.store.save(E.MOOD, jsonOf("score" to score, "energy" to energy, "cause" to cause.ifBlank { null },
                    "note" to note.ifBlank { null }, "occurred_at" to nowIso()))
                onClose()
            }
        }) { Text("Сохранить") }
    }
}

@Composable
fun MoodScreen() {
    val c = LocalContainer.current
    val entries by c.store.observe(E.MOOD, MoodEntry.serializer()).collectAsState(initial = emptyList())
    var open by remember { mutableStateOf(false) }
    val sorted = entries.sortedBy { it.occurredAt ?: "" }
    Screen("Настроение", fab = { ExtendedFloatingActionButton(onClick = { open = true }, icon = { Icon(Icons.Default.Add, null) }, text = { Text("Записать") }) }) {
        item {
            AppCard(tint = Tone.current.motivation) {
                Label("Последние 30 записей")
                LineChart(sorted.takeLast(30).map { it.score.toDouble() })
                val week = sorted.filter { parseInstant(it.occurredAt)?.isAfter(java.time.Instant.now().minusSeconds(7 * 86400)) == true }
                if (week.isNotEmpty()) Secondary("Среднее за неделю: ${"%.1f".format(week.map { it.score }.average())}")
            }
        }
        item { SectionTitle("История") }
        items(sorted.reversed().take(100), key = { it.id }) { m ->
            AppCard(padding = 12.dp) {
                ListRow("${m.score}/10" + (m.energy?.let { " · энергия $it" } ?: ""), listOfNotNull(m.cause, m.note, relative(parseInstant(m.occurredAt))).joinToString(" · "))
            }
        }
    }
    if (open) MoodSheet { open = false }
}

@Composable
fun NotesScreen() {
    val c = LocalContainer.current
    val act = rememberAction()
    val notes by c.store.observe(E.NOTES, Note.serializer()).collectAsState(initial = emptyList())
    var text by remember { mutableStateOf("") }
    Screen("Заметки") {
        item {
            AppCard(tint = Tone.current.memory) {
                Field(text, { text = it }, "Быстрая заметка (работает без интернета)", singleLine = false, minLines = 3)
                Button(enabled = text.isNotBlank(), onClick = { act("Сохранено") { c.store.save(E.NOTES, jsonOf("text" to text)); text = "" } }) { Text("Сохранить") }
                Secondary("Агент прочитает заметку и добавит важное в память.")
            }
        }
        items(notes.sortedByDescending { it.createdAt ?: "" }, key = { it.id }) { n ->
            AppCard(padding = 12.dp) {
                Text(n.text, style = MaterialTheme.typography.bodyLarge)
                Label(relative(parseInstant(n.createdAt)))
                TextButton(onClick = { act(null) { c.store.delete(E.NOTES, n.id) } }) { Text("Удалить") }
            }
        }
    }
}
