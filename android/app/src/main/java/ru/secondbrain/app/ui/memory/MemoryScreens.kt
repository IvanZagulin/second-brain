package ru.secondbrain.app.ui.memory

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Help
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.Source
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody
import okio.BufferedSink
import okio.source
import ru.secondbrain.app.core.parseInstant
import ru.secondbrain.app.core.relative
import ru.secondbrain.app.data.model.E
import ru.secondbrain.app.data.model.LifeEvent
import ru.secondbrain.app.data.model.MEMORY_CATEGORIES
import ru.secondbrain.app.data.model.MEMORY_TYPES
import ru.secondbrain.app.data.model.Memory
import ru.secondbrain.app.data.model.Person
import ru.secondbrain.app.data.repo.jsonOf
import ru.secondbrain.app.data.repo.str
import ru.secondbrain.app.ui.components.AppCard
import ru.secondbrain.app.ui.components.ChoiceChips
import ru.secondbrain.app.ui.components.ConfirmDialog
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
import ru.secondbrain.app.ui.components.Pill
import ru.secondbrain.app.ui.components.ProgressBar
import ru.secondbrain.app.ui.components.Screen
import ru.secondbrain.app.ui.components.SectionTitle
import ru.secondbrain.app.ui.components.Secondary
import ru.secondbrain.app.ui.components.Sheet
import ru.secondbrain.app.ui.components.SwitchRow
import ru.secondbrain.app.ui.components.rememberAction
import ru.secondbrain.app.ui.components.rememberLoad
import ru.secondbrain.app.ui.theme.Tone

@Composable
fun MemoryScreen() {
    val c = LocalContainer.current
    val nav = LocalNavigate.current
    val act = rememberAction()
    val memories by c.store.observe(E.MEMORIES, Memory.serializer()).collectAsState(initial = emptyList())
    val stats = rememberLoad { c.api.get("/api/v1/memories/stats").jsonObject }
    val questions = rememberLoad { c.api.get("/api/v1/memories/questions/open").jsonObject["items"]!!.jsonArray.size }
    var type by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var found by remember { mutableStateOf<List<JsonObject>?>(null) }
    var adding by remember { mutableStateOf(false) }
    LaunchedEffect(query, type) {
        if (query.length < 3) { found = null; return@LaunchedEffect }
        delay(400)
        found = runCatching {
            c.api.get("/api/v1/memories", mapOf("q" to query, "type" to type, "limit" to 40)).jsonObject["items"]!!.jsonArray.map { it.jsonObject }
        }.getOrNull()
    }
    val list = memories.filter { it.isActual && it.status != "rejected" && (type == null || it.type == type) }
        .sortedByDescending { it.createdAt ?: "" }
    Screen("Память", onRefresh = { c.sync.syncNow(); stats.reload(); questions.reload() },
        fab = { ExtendedFloatingActionButton(onClick = { adding = true }, icon = { Icon(Icons.Default.Add, null) }, text = { Text("Запомнить") }) }) {
        item {
            AppCard(tint = Tone.current.memory) {
                val byType = stats.value?.get("by_type") as? JsonObject
                Text("${byType?.values?.sumOf { it.jsonPrimitive.contentOrNull?.toIntOrNull() ?: 0 } ?: memories.size} воспоминаний",
                    style = MaterialTheme.typography.headlineSmall)
                Secondary(MEMORY_TYPES.entries.joinToString(" · ") { (k, v) -> "$v ${byType?.get(k)?.jsonPrimitive?.contentOrNull ?: 0}" })
                Secondary("Людей: ${stats.value?.str("people") ?: "—"}")
            }
        }
        item {
            AppCard(padding = 6.dp) {
                ListRow("Вопросы агента", (questions.value ?: 0).let { if (it > 0) "$it без ответа" else "нет новых" },
                    icon = Icons.Outlined.Help, iconTint = Tone.current.memory) { nav("memory/questions") }
                Divider()
                ListRow("Люди", icon = Icons.Outlined.People, iconTint = Tone.current.memory) { nav("memory/people") }
                Divider()
                ListRow("Источники и доступ", icon = Icons.Outlined.Source, iconTint = Tone.current.memory) { nav("memory/sources") }
                Divider()
                ListRow("Импорт архивов (Telegram, ChatGPT, Claude, Gemini)", icon = Icons.Outlined.Archive, iconTint = Tone.current.memory) { nav("memory/imports") }
            }
        }
        item { Field(query, { query = it }, "Поиск по смыслу") }
        item { ChoiceChips(listOf<Pair<String?, String>>(null to "Все") + MEMORY_TYPES.entries.map { it.key to it.value }, type, { type = it }) }
        val f = found
        if (f != null) {
            if (f.isEmpty()) item { Secondary("Ничего не нашлось") }
            items(f, key = { "f" + it.str("id") }) { m ->
                MemoryRow(m.str("content") ?: "", m.str("type") ?: "", m.str("category"), m.str("status"),
                    m.str("confidence")?.toDoubleOrNull()) { nav("memory/item/${m.str("id")}") }
            }
        } else {
            if (list.isEmpty()) item { EmptyState("Память пока пустая. Она наполняется из разговоров, импортов и заметок.") }
            items(list.take(300), key = { it.id }) { m ->
                MemoryRow(m.content, m.type, m.category, m.status, m.confidence) { nav("memory/item/${m.id}") }
            }
        }
    }
    if (adding) Sheet({ adding = false }) {
        var text by remember { mutableStateOf("") }
        var t by remember { mutableStateOf("permanent") }
        var sens by remember { mutableStateOf("normal") }
        Text("Запомнить", style = MaterialTheme.typography.headlineSmall)
        Field(text, { text = it }, "Факт о себе", singleLine = false, minLines = 3)
        Dropdown("Тип", MEMORY_TYPES.entries.map { it.key to it.value }, t, { t = it })
        Dropdown("Конфиденциальность", listOf("normal" to "Обычное", "private" to "Личное", "secret" to "Секретное (не отправлять в Claude)"), sens, { sens = it })
        Button(enabled = text.length > 2, onClick = {
            act("Запомнено") {
                c.store.putFromServer(E.MEMORIES, c.api.post("/api/v1/memories", jsonOf("content" to text, "type" to t, "sensitivity" to sens)))
                adding = false
            }
        }) { Text("Сохранить") }
    }
}

@Composable
private fun MemoryRow(content: String, type: String, category: String?, status: String?, confidence: Double?, onClick: () -> Unit) {
    AppCard(padding = 12.dp, onClick = onClick) {
        Text(content, style = MaterialTheme.typography.bodyLarge, maxLines = 4)
        Gap(4)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Pill(MEMORY_TYPES[type] ?: type, Tone.current.memory)
            if (category != null && category != "general") Pill(MEMORY_CATEGORIES[category] ?: category)
            if (status == "unconfirmed" && confidence != null) Pill("уверенность ${(confidence * 100).toInt()}%", Tone.current.motivation)
            if (status == "confirmed") Pill("подтверждено", Tone.current.finance)
        }
    }
}

@Composable
fun MemoryItemScreen(id: String) {
    val c = LocalContainer.current
    val back = LocalBack.current
    val act = rememberAction()
    val data = rememberLoad(id) { c.api.get("/api/v1/memories/$id/evidence").jsonObject }
    var confirmDelete by remember { mutableStateOf(false) }
    Screen("Воспоминание") {
        val d = data.value
        if (d == null) { item { if (data.loading) Loading() else ErrorCard(data.error ?: "Ошибка", data.reload) }; return@Screen }
        val m = d["memory"]!!.jsonObject
        item {
            var content by remember(m) { mutableStateOf(m.str("content") ?: "") }
            var type by remember(m) { mutableStateOf(m.str("type") ?: "permanent") }
            var sens by remember(m) { mutableStateOf(m.str("sensitivity") ?: "normal") }
            var importance by remember(m) { mutableStateOf(m.str("importance")?.toIntOrNull() ?: 3) }
            var actual by remember(m) { mutableStateOf(m.str("is_actual") != "false") }
            AppCard(tint = Tone.current.memory) {
                Field(content, { content = it }, "Текст", singleLine = false, minLines = 3)
                Gap(6)
                Dropdown("Тип", MEMORY_TYPES.entries.map { it.key to it.value }, type, { type = it })
                Gap(6)
                Dropdown("Конфиденциальность", listOf("normal" to "Обычное", "private" to "Личное", "secret" to "Секретное"), sens, { sens = it })
                Label("Важность")
                ChoiceChips((1..5).map { it to "$it" }, importance, { importance = it })
                SwitchRow("Актуально", checked = actual) { actual = it }
                Secondary("Источник: ${m.str("source_kind")} · ${relative(parseInstant(m.str("created_at")))} · статус ${m.str("status")}")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        act("Сохранено") {
                            c.store.putFromServer(E.MEMORIES, c.api.patch("/api/v1/memories/$id", jsonOf("content" to content, "type" to type,
                                "sensitivity" to sens, "importance" to importance, "is_actual" to actual, "status" to "confirmed")))
                            data.reload()
                        }
                    }) { Text("Сохранить и подтвердить") }
                    OutlinedButton(onClick = {
                        act("Отмечено как неверное") {
                            c.store.putFromServer(E.MEMORIES, c.api.patch("/api/v1/memories/$id", jsonOf("status" to "rejected")))
                            back()
                        }
                    }) { Text("Неверно") }
                }
            }
        }
        val ev = d["evidence"]?.jsonArray?.map { it.jsonObject } ?: emptyList()
        item { SectionTitle("На чём основано") }
        if (ev.isEmpty()) item { Secondary("Основания не записаны") }
        items(ev.size) { i ->
            val e = ev[i]
            AppCard(padding = 12.dp) {
                Label(when (e.str("kind")) {
                    "message" -> "Сообщение в чате"; "import_message" -> "Импортированная переписка · ${e.str("author") ?: ""}"
                    "conversation" -> "Разговор"; "note" -> "Заметка"; "reasoning" -> "Рассуждение агента"; "manual" -> "Введено вручную"
                    "report" -> "Данные отчёта"; else -> e.str("kind") ?: ""
                } + (parseInstant(e.str("at"))?.let { " · ${relative(it)}" } ?: ""))
                (e.str("text") ?: e.str("excerpt"))?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            }
        }
        (d["source"] as? JsonObject)?.let { s -> item { Secondary("Архив: ${s.str("title")} (${s.str("kind")})") } }
        item { TextButton(onClick = { confirmDelete = true }) { Text("Удалить воспоминание", color = Tone.current.negative) } }
    }
    if (confirmDelete) ConfirmDialog("Удалить?", "Воспоминание будет удалено и исключено из поиска.",
        onConfirm = { act("Удалено") { c.api.delete("/api/v1/memories/$id"); c.sync.requestSync(0); back() } },
        onDismiss = { confirmDelete = false })
}

@Composable
fun PeopleScreen() {
    val c = LocalContainer.current
    val nav = LocalNavigate.current
    val act = rememberAction()
    val people by c.store.observe(E.PEOPLE, Person.serializer()).collectAsState(initial = emptyList())
    var adding by remember { mutableStateOf(false) }
    Screen("Люди", fab = { ExtendedFloatingActionButton(onClick = { adding = true }, icon = { Icon(Icons.Default.Add, null) }, text = { Text("Человек") }) }) {
        item { Secondary("Карточки — это ваш взгляд и наблюдения агента, а не объективные характеристики людей.") }
        if (people.isEmpty()) item { EmptyState("Пока никого. Люди появятся из разговоров и импортов.") }
        items(people.sortedWith(compareByDescending<Person> { it.importance }.thenByDescending { it.lastMentionedAt ?: "" }), key = { it.id }) { p ->
            AppCard(padding = 12.dp, onClick = { nav("memory/person/${p.id}") }) {
                ListRow(p.name, listOfNotNull(p.relation, p.lastMentionedAt?.let { "упоминался ${relative(parseInstant(it))}" },
                    if (p.unresolved.isNotEmpty()) "нерешённых вопросов: ${p.unresolved.size}" else null).joinToString(" · "))
            }
        }
    }
    if (adding) Sheet({ adding = false }) {
        var name by remember { mutableStateOf("") }
        var relation by remember { mutableStateOf("") }
        Field(name, { name = it }, "Имя или прозвище")
        Field(relation, { relation = it }, "Кто это вам")
        Button(enabled = name.isNotBlank(), onClick = {
            act(null) { c.store.save(E.PEOPLE, jsonOf("name" to name, "relation" to relation.ifBlank { null })); adding = false }
        }) { Text("Добавить") }
    }
}

@Composable
fun PersonScreen(id: String) {
    val c = LocalContainer.current
    val nav = LocalNavigate.current
    val act = rememberAction()
    val scope = rememberCoroutineScope()
    val person by c.store.observeOne(E.PEOPLE, id, Person.serializer()).collectAsState(initial = null)
    val events by c.store.observe(E.EVENTS, LifeEvent.serializer()).collectAsState(initial = emptyList())
    val p = person ?: return Screen("Человек") { item { Secondary("Загрузка…") } }
    var relation by remember(p.id) { mutableStateOf(p.relation ?: "") }
    var description by remember(p.id) { mutableStateOf(p.description ?: "") }
    var issue by remember { mutableStateOf("") }
    Screen(p.name, subtitle = p.relation) {
        item {
            AppCard(tint = Tone.current.memory) {
                Field(relation, { relation = it }, "Отношения")
                Field(description, { description = it }, "Описание", singleLine = false)
                TextButton(onClick = { act("Сохранено") { c.store.save(E.PEOPLE, jsonOf("relation" to relation, "description" to description), id) } }) { Text("Сохранить") }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    scope.launch {
                        val conv = c.chat.newConversation("psych", "Отношения: ${p.name}")
                        c.chat.queueMessage(conv, "Разбери мои отношения с ${p.name}: что повторяется, где я ошибаюсь, что с нерешёнными вопросами.", mode = "psych")
                        nav("chat/$conv")
                    }
                }) { Text("Разобрать с агентом") }
                OutlinedButton(onClick = {
                    scope.launch {
                        val conv = c.chat.newConversation("psych", "Подготовка к разговору: ${p.name}")
                        nav("chat/$conv")
                    }
                }) { Text("Подготовиться к разговору") }
            }
        }
        item {
            AppCard {
                Text("Нерешённые вопросы", style = MaterialTheme.typography.titleMedium)
                p.unresolved.forEachIndexed { i, u ->
                    ListRow(u, null, onClick = null)
                    TextButton(onClick = { act(null) { c.store.save(E.PEOPLE, jsonOf("unresolved" to p.unresolved.filterIndexed { j, _ -> j != i }), id) } }) { Text("Решено") }
                }
                Field(issue, { issue = it }, "Добавить вопрос")
                TextButton(enabled = issue.isNotBlank(), onClick = { act(null) { c.store.save(E.PEOPLE, jsonOf("unresolved" to p.unresolved + issue), id); issue = "" } }) { Text("Добавить") }
            }
        }
        if (p.observations.isNotEmpty()) item {
            AppCard {
                Text("Наблюдения", style = MaterialTheme.typography.titleMedium)
                p.observations.forEach { o ->
                    val obj = o as? JsonObject
                    ListRow(obj?.str("text") ?: (o as? JsonPrimitive)?.contentOrNull ?: "", obj?.str("at"))
                }
            }
        }
        val related = events.filter { id in it.people }.sortedByDescending { it.occurredAt ?: "" }
        if (related.isNotEmpty()) item {
            AppCard {
                Text("События", style = MaterialTheme.typography.titleMedium)
                related.forEach { e -> ListRow(e.title, listOfNotNull(e.kind.takeIf { it != "event" }, relative(parseInstant(e.occurredAt))).joinToString(" · ")) }
            }
        }
    }
}

@Composable
fun QuestionsScreen() {
    val c = LocalContainer.current
    val act = rememberAction()
    val data = rememberLoad { c.api.get("/api/v1/memories/questions/open").jsonObject["items"]!!.jsonArray.map { it.jsonObject } }
    Screen("Вопросы агента", onRefresh = { data.reload() }) {
        item { Secondary("Агент задаёт их, когда что-то в памяти неясно или противоречит друг другу. Ответ сразу станет подтверждённым фактом.") }
        val list = data.value ?: emptyList()
        if (data.loading && list.isEmpty()) item { Loading() }
        if (!data.loading && list.isEmpty()) item { EmptyState("Вопросов нет") }
        items(list, key = { it.str("id")!! }) { q ->
            var answer by remember { mutableStateOf("") }
            AppCard(tint = if (q.str("question")?.startsWith("Противоречие") == true) Tone.current.motivation else Tone.current.memory) {
                Text(q.str("question") ?: "", style = MaterialTheme.typography.bodyLarge)
                Field(answer, { answer = it }, "Ответ", singleLine = false)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = answer.isNotBlank(), onClick = { act("Записано в память") { c.api.post("/api/v1/memories/questions/${q.str("id")}/answer", jsonOf("answer" to answer)); data.reload() } }) { Text("Ответить") }
                    TextButton(onClick = { act(null) { c.api.post("/api/v1/memories/questions/${q.str("id")}/dismiss"); data.reload() } }) { Text("Пропустить") }
                }
            }
        }
    }
}

@Composable
fun SourcesScreen() {
    val c = LocalContainer.current
    val act = rememberAction()
    val data = rememberLoad { c.api.get("/api/v1/memories/sources/all").jsonObject["items"]!!.jsonArray.map { it.jsonObject } }
    var deleting by remember { mutableStateOf<JsonObject?>(null) }
    Screen("Источники", onRefresh = { data.reload() }) {
        item { Secondary("Отключённый источник перестаёт использоваться в поиске. «Не отправлять в Claude» — его фрагменты не уходят в API. Удаление стирает источник и все выводы из него.") }
        val list = data.value ?: emptyList()
        if (list.isEmpty() && !data.loading) item { EmptyState("Импортированных источников пока нет") }
        items(list, key = { it.str("id")!! }) { s ->
            AppCard {
                Text(s.str("title") ?: "", style = MaterialTheme.typography.titleMedium)
                Secondary("${s.str("kind")} · воспоминаний ${s.str("memories")}")
                SwitchRow("Использовать", checked = s.str("excluded") != "true") { on ->
                    act(null) { c.api.patch("/api/v1/memories/sources/${s.str("id")}", jsonOf("excluded" to !on)); data.reload() }
                }
                SwitchRow("Можно отправлять фрагменты в Claude", checked = s.str("allow_llm") != "false") { on ->
                    act(null) { c.api.patch("/api/v1/memories/sources/${s.str("id")}", jsonOf("allow_llm" to on)); data.reload() }
                }
                TextButton(onClick = { deleting = s }) { Text("Удалить источник", color = Tone.current.negative) }
            }
        }
    }
    deleting?.let { s ->
        ConfirmDialog("Удалить «${s.str("title")}»?", "Удалятся исходные сообщения и все воспоминания, события и связи, выведенные из них.", "Удалить",
            onConfirm = { act("Удалено") { c.api.delete("/api/v1/memories/sources/${s.str("id")}"); data.reload(); c.sync.requestSync(0) } },
            onDismiss = { deleting = null })
    }
}

/** Streams a picked file to the server without loading it into memory (archives can be gigabytes). */
private class UriBody(private val context: android.content.Context, private val uri: Uri, private val type: MediaType?,
                      private val length: Long) : RequestBody() {
    override fun contentType() = type
    override fun contentLength() = length
    override fun writeTo(sink: BufferedSink) {
        context.contentResolver.openInputStream(uri)!!.source().use { sink.writeAll(it) }
    }
}

@Composable
fun ImportsScreen() {
    val c = LocalContainer.current
    val nav = LocalNavigate.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val data = rememberLoad { c.api.get("/api/v1/imports").jsonObject["items"]!!.jsonArray.map { it.jsonObject } }
    var kind by remember { mutableStateOf("auto") }
    var uploading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            uploading = true; error = null
            try {
                var name = "export"
                var size = -1L
                context.contentResolver.query(uri, null, null, null, null)?.use { cur ->
                    if (cur.moveToFirst()) {
                        cur.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { name = cur.getString(it) }
                        cur.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 }?.let { size = cur.getLong(it) }
                    }
                }
                val type = (context.contentResolver.getType(uri) ?: "application/octet-stream").toMediaTypeOrNull()
                val job = c.api.upload("/api/v1/imports", name, type.toString(), UriBody(context, uri, type, size), mapOf("kind" to kind)).jsonObject
                nav("memory/import/${job.str("id")}")
            } catch (e: Exception) { error = e.message }
            uploading = false
        }
    }
    Screen("Импорт архивов", onRefresh = { data.reload() }) {
        item {
            AppCard(tint = Tone.current.memory) {
                Text("Загрузите экспорт", style = MaterialTheme.typography.titleMedium)
                Secondary("Telegram Desktop → Экспорт данных → JSON (result.json или zip). ChatGPT/Claude — архив из настроек аккаунта. Gemini — Google Takeout. Повторная загрузка не создаёт дублей.")
                Gap(6)
                ChoiceChips(listOf("auto" to "Определить", "telegram" to "Telegram", "chatgpt" to "ChatGPT", "claude" to "Claude", "gemini" to "Gemini"), kind, { kind = it })
                Button(enabled = !uploading, onClick = { picker.launch(arrayOf("application/json", "application/zip", "application/octet-stream", "*/*")) }) {
                    Text(if (uploading) "Загружаю…" else "Выбрать файл")
                }
                Secondary("Разбор бесплатный. Анализ через Claude запускается только после вашего подтверждения с оценкой стоимости.")
            }
        }
        error?.let { item { ErrorCard(it) } }
        if (uploading) item { Loading() }
        items(data.value ?: emptyList(), key = { it.str("id")!! }) { j ->
            AppCard(onClick = { nav("memory/import/${j.str("id")}") }) {
                ListRow(j.str("file_name") ?: "", "${j.str("kind")} · ${importStatus(j.str("status"))} · ${relative(parseInstant(j.str("created_at")))}")
            }
        }
    }
}

fun importStatus(s: String?) = mapOf("uploaded" to "загружено", "parsing" to "разбирается", "parsed" to "ждёт подтверждения",
    "analyzing" to "анализ", "paused" to "на паузе", "done" to "готово", "failed" to "ошибка", "cancelled" to "отменено")[s] ?: (s ?: "")

@Composable
fun ImportDetailScreen(id: String) {
    val c = LocalContainer.current
    val back = LocalBack.current
    val act = rememberAction()
    val data = rememberLoad(id) { c.api.get("/api/v1/imports/$id").jsonObject }
    var confirm by remember { mutableStateOf(false) }
    var delete by remember { mutableStateOf(false) }
    val status = data.value?.str("status")
    // Poll while the server works on it: every reload produces a new value, which restarts this effect.
    LaunchedEffect(status, data.value) {
        if (status in setOf("uploaded", "parsing", "analyzing")) { delay(5000); data.reload() }
    }
    Screen("Импорт", onRefresh = { data.reload() }) {
        val j = data.value
        if (j == null) { item { if (data.loading) Loading() else ErrorCard(data.error ?: "Ошибка", data.reload) }; return@Screen }
        val stats = j["stats"] as? JsonObject
        item {
            AppCard(tint = Tone.current.memory) {
                Text(j.str("file_name") ?: "", style = MaterialTheme.typography.titleMedium)
                Secondary("${j.str("kind")} · ${importStatus(status)}")
                j.str("error")?.let { Text(it, color = Tone.current.negative) }
                if (status == "analyzing") { Gap(6); ProgressBar(j.str("progress")?.toFloatOrNull() ?: 0f) }
                stats?.let { s ->
                    Gap(6)
                    Secondary("Чатов ${s.str("chats") ?: "—"} · сообщений ${s.str("messages") ?: "—"} (новых ${s.str("new_messages") ?: "—"})")
                    s.str("estimate_usd")?.let { Secondary("Оценка стоимости анализа: ≈ $$it (Claude Haiku)") }
                    (s["analysis"] as? JsonObject)?.let { a -> Secondary("Найдено: фактов ${a.str("facts")}, людей ${a.str("people")}, событий ${a.str("events")}") }
                }
            }
        }
        val chats = j["chats"]?.jsonArray?.map { it.jsonObject } ?: emptyList()
        val needsSelf = j.str("kind") == "telegram" && stats?.str("self_id") == null
        if (needsSelf && chats.isNotEmpty() && status == "parsed") item {
            AppCard(tint = Tone.current.motivation) {
                Text("Кто из участников — вы?", style = MaterialTheme.typography.titleMedium)
                val participants = chats.flatMap { it["participants"]?.jsonArray?.map { p -> p.jsonObject } ?: emptyList() }
                    .distinctBy { it.str("id") }.take(10)
                participants.forEach { p ->
                    TextButton(onClick = { act("Отмечено") { c.api.post("/api/v1/imports/$id/self", jsonOf("self_ids" to listOf(p.str("id")))); data.reload() } }) {
                        Text("${p.str("name")} (${p.str("messages")} сообщ.)")
                    }
                }
            }
        }
        if (status in setOf("parsed", "paused", "done")) item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { confirm = true }) { Text(if (status == "done") "Проанализировать новое" else "Запустить анализ") }
                OutlinedButton(onClick = { delete = true }) { Text("Удалить импорт") }
            }
        }
        if (status == "analyzing") item { OutlinedButton(onClick = { act("Остановлено") { c.api.post("/api/v1/imports/$id/cancel"); data.reload() } }) { Text("Остановить") } }
        if (chats.isNotEmpty()) item { SectionTitle("Чаты (${chats.size})") }
        items(chats.take(200), key = { it.str("id")!! }) { ch ->
            AppCard(padding = 10.dp) {
                SwitchRow(ch.str("title") ?: "", "${ch.str("message_count")} сообщений", checked = ch.str("excluded") != "true") { on ->
                    act(null) { c.api.patch("/api/v1/imports/$id/chats/${ch.str("id")}", jsonOf("excluded" to !on)); data.reload() }
                }
            }
        }
    }
    if (confirm) ConfirmDialog("Запустить анализ?",
        "Фрагменты переписки будут отправлены в Claude API (≈ $${(data.value?.get("stats") as? JsonObject)?.str("estimate_usd") ?: "?"}). Исключённые чаты не отправляются. Анализ остановится, если закончится бюджет API.",
        "Запустить", onConfirm = { act("Анализ запущен") { c.api.post("/api/v1/imports/$id/analyze", jsonOf("allow_llm" to true)); data.reload() } },
        onDismiss = { confirm = false })
    if (delete) ConfirmDialog("Удалить импорт?", "Удалятся загруженный файл, сообщения и все выводы из него.", "Удалить",
        onConfirm = { act("Удалено") { c.api.delete("/api/v1/imports/$id"); c.sync.requestSync(0); back() } },
        onDismiss = { delete = false })
}
