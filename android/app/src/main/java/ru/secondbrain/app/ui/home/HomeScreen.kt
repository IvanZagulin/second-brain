package ru.secondbrain.app.ui.home

import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.automirrored.outlined.ListAlt
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.outlined.Analytics
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.EmojiEmotions
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Face
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Loop
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.booleanPreferencesKey
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import ru.secondbrain.app.core.Prefs
import ru.secondbrain.app.core.parseInstant
import ru.secondbrain.app.core.relative
import ru.secondbrain.app.core.ruDate
import ru.secondbrain.app.core.ruWeekday
import ru.secondbrain.app.data.api.PlainJson
import ru.secondbrain.app.data.model.Conversation
import ru.secondbrain.app.data.model.E
import ru.secondbrain.app.data.model.Experiment
import ru.secondbrain.app.data.model.Habit
import ru.secondbrain.app.data.model.Memory
import ru.secondbrain.app.data.model.MoodEntry
import ru.secondbrain.app.data.model.Note
import ru.secondbrain.app.data.model.Person
import ru.secondbrain.app.data.model.Task
import ru.secondbrain.app.data.repo.jsonOf
import ru.secondbrain.app.data.repo.str
import ru.secondbrain.app.ui.components.AppCard
import ru.secondbrain.app.ui.components.Divider
import ru.secondbrain.app.ui.components.EmptyState
import ru.secondbrain.app.ui.components.Field
import ru.secondbrain.app.ui.components.Gap
import ru.secondbrain.app.ui.components.glueNumbers
import ru.secondbrain.app.ui.components.Label
import ru.secondbrain.app.ui.components.ListRow
import ru.secondbrain.app.ui.components.LocalContainer
import ru.secondbrain.app.ui.components.LocalNavigate
import ru.secondbrain.app.ui.components.Markdown
import ru.secondbrain.app.ui.components.Screen
import ru.secondbrain.app.ui.components.Secondary
import ru.secondbrain.app.ui.components.Spark
import ru.secondbrain.app.ui.components.rememberAction
import ru.secondbrain.app.ui.components.rememberLoad
import ru.secondbrain.app.ui.theme.AgentProse
import ru.secondbrain.app.ui.theme.Tone
import java.time.LocalDate

/** Kept so «Как пользоваться» can still be hidden from the menu once read. */
val HelpDismissedKey = booleanPreferencesKey("help_dismissed")

/**
 * The agent is the home screen, as in Claude: today's few lines in its voice, a handful of things to ask,
 * one question at a time, recent conversations and a composer. Everything else lives in the ☰ menu.
 */
@Composable
fun HomeScreen() {
    val c = LocalContainer.current
    val nav = LocalNavigate.current
    val scope = rememberCoroutineScope()
    val cached by c.prefs.flow(Prefs.Keys.homeCache).collectAsState(initial = null)
    val home = cached?.let { runCatching { PlainJson.parseToJsonElement(it).jsonObject }.getOrNull() }
    val update by c.updates.available.collectAsState()
    val convs by c.store.observe(E.CONVERSATIONS, Conversation.serializer()).collectAsState(initial = emptyList())
    val notes by c.store.observe(E.NOTES, Note.serializer()).collectAsState(initial = emptyList())
    LaunchedEffect(Unit) { c.sync.refreshHome() }

    fun ask(text: String) = scope.launch {
        val id = c.chat.newConversation()
        nav("chat/$id?q=${Uri.encode(text)}")
    }

    val nw = home?.get("next_workout") as? JsonObject
    val trainingToday = nw?.str("in_days") == "0"
    val hasPortrait = notes.any { "портрет" in it.tags }
    val recent = convs.filter { !it.archived && (it.kind == "agent" || it.title != "Новый диалог") }
        .sortedByDescending { it.lastMessageAt ?: "" }.take(3)
    val agentConv = (home?.get("observation") as? JsonObject)?.str("conversation_id")

    Column(Modifier.fillMaxSize().background(Tone.current.bg)) {
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item {
                Row(Modifier.fillMaxWidth().statusBarsPadding(), verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { nav("chat") }) { Icon(Icons.Outlined.History, "Все диалоги", tint = Tone.current.textSecondary) }
                    IconButton(onClick = { nav("more") }) { Icon(Icons.Outlined.Menu, "Меню", tint = Tone.current.textSecondary) }
                }
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Spark(28.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(home?.str("greeting") ?: "Привет", style = MaterialTheme.typography.displaySmall)
                }
                Text("${ruWeekday(LocalDate.now()).replaceFirstChar { it.uppercase() }}, ${ruDate(LocalDate.now())}",
                    style = MaterialTheme.typography.bodyMedium, color = Tone.current.textSecondary,
                    modifier = Modifier.padding(start = 40.dp, top = 2.dp))
            }
            update?.let { u ->
                item {
                    AppCard(onClick = { nav("updates") }, padding = 12.dp) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.SystemUpdate, null, tint = Tone.current.accent)
                            Spacer(Modifier.width(12.dp))
                            Text("Доступна версия ${u.version}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                            Text("Обновить", style = MaterialTheme.typography.labelLarge, color = Tone.current.accent)
                        }
                    }
                }
            }
            item {
                Text(glueNumbers(home?.str("brief") ?: "Секунду, собираю картину дня…"), style = AgentProse,
                    modifier = Modifier.clickable(enabled = agentConv != null) { nav("chat/$agentConv") })
            }
            // Right under the brief, above the fold: further down it ended up behind the composer.
            item { QuestionCard() }
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (hasPortrait) Suggestion("Что ты обо мне знаешь") { nav("portrait") }
                    if (trainingToday) Suggestion("Начать тренировку") { nav("fitness") }
                    Suggestion("Разбери мои траты") { ask("Разбери мои траты за последнюю неделю: что лишнее и где я сливаю деньги?") }
                    Suggestion("Как прошла неделя") { ask("Как прошла моя неделя? Коротко: деньги, тренировки, что я обещал и не сделал.") }
                    Suggestion("Как этим пользоваться") { nav("help") }
                }
            }
            if (recent.isNotEmpty()) {
                item { Label("Недавние", Modifier.padding(top = 6.dp)) }
                recent.forEach { conv ->
                    item(key = conv.id) {
                        Row(Modifier.fillMaxWidth().clickable { nav("chat/${conv.id}") }.padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            if (conv.kind == "agent") Spark(14.dp)
                            else Icon(Icons.Outlined.ChatBubbleOutline, null, Modifier.size(16.dp), tint = Tone.current.textSecondary)
                            Spacer(Modifier.width(10.dp))
                            Text(if (conv.kind == "agent") "Агент пишет первым" else conv.title, style = MaterialTheme.typography.bodyLarge,
                                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            Text(relative(parseInstant(conv.lastMessageAt)), style = MaterialTheme.typography.bodySmall,
                                color = Tone.current.textSecondary)
                        }
                    }
                }
            }
        }
        Composer { text -> ask(text) }
    }
}

@Composable
private fun Suggestion(text: String, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(16.dp), color = Tone.current.surface,
        border = BorderStroke(1.dp, Tone.current.outline)) {
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp))
    }
}

/** One question at a time: answer, ask later, or never ask again. */
@Composable
private fun QuestionCard() {
    val c = LocalContainer.current
    val act = rememberAction()
    val data = rememberLoad { c.api.get("/api/v1/memories/questions/open").jsonObject["items"]!!.jsonArray.map { it.jsonObject } }
    val skipped = remember { mutableStateListOf<String>() }
    var answering by remember { mutableStateOf(false) }
    var answer by remember { mutableStateOf("") }
    val queue = (data.value ?: emptyList()).filter { it.str("id") !in skipped }
    val q = queue.firstOrNull() ?: return
    val id = q.str("id")!!
    fun next() { answering = false; answer = ""; skipped += id }
    AppCard(padding = 14.dp) {
        Label(if (queue.size > 1) "Вопрос · ещё ${queue.size - 1}" else "Вопрос")
        Gap(4)
        Text(q.str("question") ?: "", style = MaterialTheme.typography.bodyLarge)
        if (answering) {
            Gap(8)
            Field(answer, { answer = it }, "Ответ", singleLine = false)
        }
        Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (answering) TextButton(enabled = answer.isNotBlank(), onClick = {
                act("Запомнил") { c.api.post("/api/v1/memories/questions/$id/answer", jsonOf("answer" to answer)); next() }
            }) { Text("Сохранить") }
            else TextButton(onClick = { answering = true }) { Text("Ответить") }
            TextButton(onClick = { next() }) { Text("Позже", color = Tone.current.textSecondary) }
            TextButton(onClick = { act(null) { c.api.post("/api/v1/memories/questions/$id/dismiss"); next() } }) {
                Text("Не спрашивать", color = Tone.current.textSecondary)
            }
        }
    }
}

@Composable
private fun Composer(onSend: (String) -> Unit) {
    var input by remember { mutableStateOf("") }
    Surface(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 8.dp),
        shape = RoundedCornerShape(24.dp), color = Tone.current.surface, border = BorderStroke(1.dp, Tone.current.outline),
        shadowElevation = 1.dp) {
        Row(Modifier.padding(start = 4.dp, end = 8.dp, top = 2.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            TextField(value = input, onValueChange = { input = it }, modifier = Modifier.weight(1f), maxLines = 5,
                placeholder = { Text("Спросите что угодно…", color = Tone.current.textSecondary) },
                textStyle = MaterialTheme.typography.bodyLarge,
                colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                    cursorColor = Tone.current.accent))
            val ready = input.isNotBlank()
            Surface(onClick = { if (ready) { onSend(input.trim()); input = "" } }, enabled = ready, shape = RoundedCornerShape(12.dp),
                color = if (ready) Tone.current.accent else Tone.current.fill, modifier = Modifier.size(36.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.ArrowUpward, "Спросить", Modifier.size(20.dp),
                        tint = if (ready) Color.White else Tone.current.textSecondary)
                }
            }
        }
    }
}

private data class MenuItem(val label: String, val icon: ImageVector, val route: String, val count: Int?, val hint: String? = null)

/** Everything that isn't a tab. Sections with nothing in them yet fold away under one row. */
@Composable
fun MoreScreen() {
    val c = LocalContainer.current
    val nav = LocalNavigate.current
    val tasks by c.store.observe(E.TASKS, Task.serializer()).collectAsState(initial = emptyList())
    val habits by c.store.observe(E.HABITS, Habit.serializer()).collectAsState(initial = emptyList())
    val notes by c.store.observe(E.NOTES, Note.serializer()).collectAsState(initial = emptyList())
    val exps by c.store.observe(E.EXPERIMENTS, Experiment.serializer()).collectAsState(initial = emptyList())
    val moods by c.store.observe(E.MOOD, MoodEntry.serializer()).collectAsState(initial = emptyList())
    val memories by c.store.observe(E.MEMORIES, Memory.serializer()).collectAsState(initial = emptyList())
    val people by c.store.observe(E.PEOPLE, Person.serializer()).collectAsState(initial = emptyList())
    val convs by c.store.observe(E.CONVERSATIONS, Conversation.serializer()).collectAsState(initial = emptyList())
    var showEmpty by remember { mutableStateOf(false) }

    val you = listOfNotNull(
        MenuItem("Что я о тебе знаю", Icons.Outlined.Face, "portrait", null).takeIf { notes.any { "портрет" in it.tags } },
        MenuItem("Память", Icons.Outlined.Psychology, "memory", memories.size),
        MenuItem("Люди", Icons.Outlined.People, "memory/people", people.size),
        MenuItem("Все диалоги", Icons.Outlined.ChatBubbleOutline, "chat", convs.count { !it.archived }),
    )
    val life = listOf(
        MenuItem("Задачи и обещания", Icons.AutoMirrored.Outlined.ListAlt, "tasks", tasks.count { it.status == "open" },
            "Агент сам ведёт то, что вы пообещали в разговорах"),
        MenuItem("Привычки", Icons.Outlined.Loop, "habits", habits.count { it.isActive }, "Хорошие держать, плохие бросать"),
        MenuItem("Настроение", Icons.Outlined.EmojiEmotions, "mood", moods.size, "Пара секунд вечером — агент увидит связь со сном и тратами"),
        MenuItem("Заметки", Icons.Outlined.EditNote, "notes", notes.size, "Быстро записать мысль, агент разберёт"),
        MenuItem("Эксперименты", Icons.Outlined.Science, "experiments", exps.size, "«Месяц без донатов» — и честный итог"),
    )
    val (filled, empty) = life.partition { (it.count ?: 0) > 0 }

    Screen("Меню") {
        item { MenuGroup(you, nav) }
        if (filled.isNotEmpty()) item { MenuGroup(filled, nav) }
        if (empty.isNotEmpty()) item {
            AppCard(padding = 6.dp) {
                ListRow(if (showEmpty) "Скрыть" else "Ещё разделы · ${empty.size}", null,
                    icon = if (showEmpty) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore) { showEmpty = !showEmpty }
                if (showEmpty) empty.forEach { m ->
                    Divider()
                    ListRow(m.label, m.hint, icon = m.icon) { nav(m.route) }
                }
            }
        }
        item {
            MenuGroup(listOf(
                MenuItem("Аналитика", Icons.Outlined.Analytics, "analytics", null),
                MenuItem("История агента", Icons.Outlined.History, "agent/history", null),
                MenuItem("Настройки", Icons.Outlined.Settings, "settings", null),
                MenuItem("Обновления", Icons.Outlined.SystemUpdate, "updates", null),
                MenuItem("Как пользоваться", Icons.AutoMirrored.Outlined.HelpOutline, "help", null),
            ), nav)
        }
    }
}

@Composable
private fun MenuGroup(items: List<MenuItem>, nav: (String) -> Unit) {
    if (items.isEmpty()) return
    AppCard(padding = 6.dp) {
        items.forEachIndexed { i, m ->
            ListRow(m.label, null, trailing = m.count?.takeIf { it > 0 }?.toString(), trailingColor = Tone.current.textSecondary,
                icon = m.icon) { nav(m.route) }
            if (i != items.lastIndex) Divider()
        }
    }
}

/** The portrait written from archives, on its own screen rather than buried among notes. */
@Composable
fun PortraitScreen() {
    val c = LocalContainer.current
    val nav = LocalNavigate.current
    val notes by c.store.observe(E.NOTES, Note.serializer()).collectAsState(initial = emptyList())
    val memories by c.store.observe(E.MEMORIES, Memory.serializer()).collectAsState(initial = emptyList())
    val people by c.store.observe(E.PEOPLE, Person.serializer()).collectAsState(initial = emptyList())
    val portrait = notes.filter { "портрет" in it.tags }.maxByOrNull { it.createdAt ?: "" }
    Screen("Что я о тебе знаю") {
        item {
            Secondary("${memories.size} воспоминаний · ${people.size} людей. Это взгляд по переписке и разговорам, " +
                "а не оценка — поправьте, если что-то не так.")
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Suggestion("Память") { nav("memory") }
                Suggestion("Люди") { nav("memory/people") }
                Suggestion("Импорт архивов") { nav("memory/imports") }
            }
        }
        if (portrait == null) item {
            EmptyState("Портрет появится, когда агент прочитает ваши архивы или узнает вас по разговорам.",
                "Импорт архивов") { nav("memory/imports") }
        } else item {
            // The note's first line is its title; the screen already has one.
            Markdown(portrait.text.substringAfter("\n").trim(), style = AgentProse)
        }
    }
}
