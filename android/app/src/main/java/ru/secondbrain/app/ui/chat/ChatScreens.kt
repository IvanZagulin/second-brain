package ru.secondbrain.app.ui.chat

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import ru.secondbrain.app.core.parseInstant
import ru.secondbrain.app.core.relative
import ru.secondbrain.app.data.api.ApiException
import ru.secondbrain.app.data.api.ChatEvent
import ru.secondbrain.app.data.model.CHAT_MODES
import ru.secondbrain.app.data.model.Conversation
import ru.secondbrain.app.data.model.E
import ru.secondbrain.app.data.model.Message
import ru.secondbrain.app.data.repo.jsonOf
import ru.secondbrain.app.data.repo.str
import ru.secondbrain.app.ui.components.AppCard
import ru.secondbrain.app.ui.components.EmptyState
import ru.secondbrain.app.ui.components.Label
import ru.secondbrain.app.ui.components.LocalBack
import ru.secondbrain.app.ui.components.LocalContainer
import ru.secondbrain.app.ui.components.LocalNavigate
import ru.secondbrain.app.ui.components.LocalSnackbar
import ru.secondbrain.app.ui.components.Markdown
import ru.secondbrain.app.ui.components.Pill
import ru.secondbrain.app.ui.components.Screen
import ru.secondbrain.app.ui.components.Secondary
import ru.secondbrain.app.ui.components.Spark
import ru.secondbrain.app.ui.theme.AgentProse
import ru.secondbrain.app.ui.theme.Tone
import java.util.UUID

@Composable
fun ConversationsScreen() {
    val c = LocalContainer.current
    val nav = LocalNavigate.current
    val scope = rememberCoroutineScope()
    val convs by c.store.observe(E.CONVERSATIONS, Conversation.serializer()).collectAsState(initial = emptyList())
    val messages by c.store.observe(E.MESSAGES, Message.serializer()).collectAsState(initial = null)
    var showArchived by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<Conversation?>(null) }
    // A conversation opened and left without a word: hidden, and removable in one tap.
    val withMessages = remember(messages) { messages?.mapTo(HashSet()) { it.conversationId } }
    val empty = if (withMessages == null) emptyList() else convs.filter { it.kind == "chat" && it.id !in withMessages }
    val list = convs.filter { it.archived == showArchived && it !in empty }
        .sortedWith(compareByDescending<Conversation> { it.pinned || it.kind == "agent" }.thenByDescending { it.lastMessageAt ?: "" })
    Screen("Диалоги", onRefresh = { c.sync.syncNow() },
        actions = { TextButton(onClick = { showArchived = !showArchived }) { Text(if (showArchived) "Активные" else "Архив") } },
        fab = {
            ExtendedFloatingActionButton(onClick = { scope.launch { nav("chat/${c.chat.newConversation()}") } },
                icon = { Icon(Icons.Default.Add, null) }, text = { Text("Новый диалог") })
        }) {
        if (list.isEmpty()) item { EmptyState("Диалогов пока нет. Начните новый — агент помнит всё между диалогами.") }
        items(list, key = { it.id }) { conv ->
            AppCard(onClick = { nav("chat/${conv.id}") }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    when {
                        conv.kind == "agent" -> { Spark(14.dp); Spacer(Modifier.width(8.dp)) }
                        conv.pinned -> {
                            Icon(Icons.Outlined.PushPin, null, Modifier.size(16.dp), tint = Tone.current.textSecondary)
                            Spacer(Modifier.width(6.dp))
                        }
                    }
                    Text((if (conv.kind == "agent") "Агент пишет первым" else conv.title), style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (conv.mode != "auto") Pill(CHAT_MODES[conv.mode] ?: conv.mode)
                    if (conv.kind != "agent") ConversationMenu(conv, onDelete = { confirmDelete = conv })
                }
                Secondary(relative(parseInstant(conv.lastMessageAt)))
            }
        }
        if (empty.isNotEmpty() && !showArchived) item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Secondary("Пустых диалогов: ${empty.size}", Modifier.weight(1f))
                TextButton(onClick = { scope.launch { empty.forEach { c.store.delete(E.CONVERSATIONS, it.id) } } }) { Text("Убрать") }
            }
        }
    }
    confirmDelete?.let { conv ->
        AlertDialog(onDismissRequest = { confirmDelete = null },
            title = { Text("Удалить диалог?") },
            text = { Text("«${conv.title}» пропадёт из списка. То, что агент уже запомнил из него, останется в памяти.") },
            confirmButton = {
                TextButton(onClick = { scope.launch { c.store.delete(E.CONVERSATIONS, conv.id) }; confirmDelete = null }) { Text("Удалить") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Отмена") } })
    }
}

@Composable
private fun ConversationMenu(conv: Conversation, onDelete: () -> Unit) {
    val c = LocalContainer.current
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Outlined.MoreVert, "Действия", tint = Tone.current.textSecondary)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            fun set(field: String, value: Boolean) {
                open = false
                scope.launch { c.store.save(E.CONVERSATIONS, jsonOf(field to value), conv.id) }
            }
            DropdownMenuItem(text = { Text(if (conv.pinned) "Открепить" else "Закрепить") }, onClick = { set("pinned", !conv.pinned) })
            DropdownMenuItem(text = { Text(if (conv.archived) "Вернуть из архива" else "В архив") }, onClick = { set("archived", !conv.archived) })
            DropdownMenuItem(text = { Text("Удалить", color = MaterialTheme.colorScheme.error) }, onClick = { open = false; onDelete() })
        }
    }
}

private data class LiveTurn(val userText: String, val answer: String = "", val tools: List<Pair<String, String>> = emptyList(),
                            val error: String? = null, val done: Boolean = false)

/** ``initial``: a question asked from the home screen, sent as soon as the conversation opens. */
@Composable
fun ChatScreen(conversationId: String, initial: String? = null) {
    val c = LocalContainer.current
    val back = LocalBack.current
    val context = LocalContext.current
    val snackbar = LocalSnackbar.current
    val scope = rememberCoroutineScope()
    val conv by c.store.observeOne(E.CONVERSATIONS, conversationId, Conversation.serializer()).collectAsState(initial = null)
    val messages by c.store.observeChildren(E.MESSAGES, conversationId, Message.serializer()).collectAsState(initial = emptyList())
    val pending by c.chat.pending(conversationId).collectAsState(initial = emptyList())
    var input by remember { mutableStateOf("") }
    var live by remember { mutableStateOf<LiveTurn?>(null) }
    val attachments = remember { mutableStateListOf<Pair<String, String>>() }  // id to name
    var uploading by remember { mutableStateOf(false) }
    var modeMenu by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    // Tool results and tool-only assistant turns are plumbing: their effect shows as chips on the answer.
    val visible = messages.filter {
        when (it.role) {
            "user", "trigger" -> true
            "assistant" -> it.text.isNotBlank()
            else -> false
        }
    }.sortedBy { it.createdAt ?: "" }

    LaunchedEffect(visible.size, live?.answer?.length, pending.size) {
        val count = visible.size + pending.size + (if (live != null) 2 else 0)
        if (count > 0) listState.animateScrollToItem(count - 1)
    }

    val voice = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) {
            val text = r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            if (!text.isNullOrBlank()) input = if (input.isBlank()) text else "$input $text"
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            uploading = true
            try {
                val cr = context.contentResolver
                val mime = cr.getType(uri) ?: "application/octet-stream"
                val bytes = cr.openInputStream(uri)!!.use { it.readBytes() }
                val name = uri.lastPathSegment?.substringAfterLast('/') ?: "file"
                val res = c.api.upload("/api/v1/chat/attachments", name, mime, bytes.toRequestBody(mime.toMediaTypeOrNull())).jsonObject
                attachments += res["id"]!!.jsonPrimitive.content to name
            } catch (e: Exception) {
                snackbar.showSnackbar(e.message ?: "Не удалось загрузить файл")
            }
            uploading = false
        }
    }

    fun send() {
        val text = input.trim()
        if (text.isEmpty() && attachments.isEmpty()) return
        val ids = attachments.map { it.first }
        input = ""
        attachments.clear()
        live = LiveTurn(text)
        val clientId = UUID.randomUUID().toString()
        scope.launch {
            c.chat.send(conversationId, text, ids, null, clientId).collect { ev ->
                live = when (ev) {
                    is ChatEvent.Delta -> live?.copy(answer = (live?.answer ?: "") + ev.text)
                    is ChatEvent.Tool -> live?.let { l ->
                        val others = l.tools.filter { it.first != ev.name }
                        l.copy(tools = others + (ev.name to (if (ev.status == "running") "…" else "✓") + " " + (ev.title ?: toolLabel(ev.name))))
                    }
                    is ChatEvent.Done -> live?.copy(done = true)
                    is ChatEvent.Error -> {
                        if (ev.code == "offline") {
                            c.chat.queueMessage(conversationId, text, ids)
                            null
                        } else live?.copy(error = ev.message, done = true)
                    }
                    is ChatEvent.Start -> live
                }
            }
            runCatching { c.sync.syncNow() }
            if (live?.error == null) live = null
        }
    }

    var initialSent by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(initial) {
        if (initial != null && !initialSent) {
            initialSent = true
            input = initial
            send()
        }
    }
    Scaffold(
        containerColor = Tone.current.bg,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(if (conv?.kind == "agent") "Агент" else conv?.title ?: "Диалог", style = MaterialTheme.typography.titleLarge,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Box {
                            Text("Режим: ${CHAT_MODES[conv?.mode ?: "auto"]}", style = MaterialTheme.typography.labelMedium,
                                color = Tone.current.textSecondary, modifier = Modifier.padding(top = 2.dp))
                        }
                    }
                },
                navigationIcon = { IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") } },
                actions = {
                    Box {
                        TextButton(onClick = { modeMenu = true }) { Text("Режим") }
                        DropdownMenu(expanded = modeMenu, onDismissRequest = { modeMenu = false }) {
                            CHAT_MODES.forEach { (k, v) ->
                                DropdownMenuItem(text = { Text(v) }, onClick = {
                                    modeMenu = false
                                    scope.launch { c.store.save(E.CONVERSATIONS, jsonOf("mode" to k), conversationId) }
                                })
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Tone.current.bg, scrolledContainerColor = Tone.current.bg),
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState,
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (visible.isEmpty() && live == null && pending.isEmpty()) item {
                    Column(Modifier.fillMaxWidth().padding(top = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Spark(36.dp)
                        Text("С чего начнём?", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(top = 14.dp))
                        Secondary("Спросите про деньги, тренировку, планы — или просто поговорите. Режим меняется сверху.",
                            modifier = Modifier.padding(top = 6.dp, start = 24.dp, end = 24.dp))
                    }
                }
                items(visible, key = { it.id }) { m -> Bubble(m) }
                items(pending, key = { it.clientMessageId }) { p ->
                    UserBubble(p.text, footer = if (p.status == "failed") "Не отправлено: ${p.error ?: ""}" else "Отправится, когда будет связь") {
                        if (p.status == "failed") TextButton(onClick = { scope.launch { c.chat.retry(p.clientMessageId) } }) { Text("Повторить") }
                    }
                }
                live?.let { l ->
                    if (visible.none { it.role == "user" && it.text == l.userText }) item { UserBubble(l.userText) }
                    item { AssistantLive(l) }
                }
            }
            if (attachments.isNotEmpty() || uploading) {
                FlowRow(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    attachments.forEach { (id, name) ->
                        InputChip(selected = true, onClick = { attachments.removeAll { it.first == id } }, label = { Text(name.take(24)) })
                    }
                    if (uploading) CircularProgressIndicator(Modifier.size(20.dp))
                }
            }
            Surface(Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 12.dp, end = 12.dp, bottom = 10.dp, top = 4.dp),
                shape = RoundedCornerShape(24.dp), color = Tone.current.surface, border = BorderStroke(1.dp, Tone.current.outline),
                shadowElevation = 1.dp) {
                Column(Modifier.padding(start = 6.dp, end = 8.dp, top = 2.dp, bottom = 6.dp)) {
                    TextField(value = input, onValueChange = { input = it }, modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("Напишите сообщение…", color = Tone.current.textSecondary) }, maxLines = 6,
                        textStyle = MaterialTheme.typography.bodyLarge,
                        colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent, focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent, cursorColor = Tone.current.accent))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { picker.launch("*/*") }) {
                            Icon(Icons.Outlined.AttachFile, "Вложение", tint = Tone.current.textSecondary)
                        }
                        Spacer(Modifier.weight(1f))
                        if (input.isBlank() && attachments.isEmpty()) {
                            IconButton(onClick = {
                                runCatching {
                                    voice.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                                        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                                        .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
                                        .putExtra(RecognizerIntent.EXTRA_PROMPT, "Говорите"))
                                }.onFailure { scope.launch { snackbar.showSnackbar("Распознавание речи недоступно на устройстве") } }
                            }) { Icon(Icons.Outlined.Mic, "Голосом", tint = Tone.current.textSecondary) }
                        } else {
                            val canSend = live == null || live?.done == true
                            Surface(onClick = { send() }, enabled = canSend, shape = RoundedCornerShape(12.dp),
                                color = if (canSend) Tone.current.accent else Tone.current.fill, modifier = Modifier.size(36.dp)) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(Icons.Filled.ArrowUpward, "Отправить", Modifier.size(20.dp),
                                        tint = if (canSend) Color.White else Tone.current.textSecondary)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private val TOOL_LABELS = mapOf(
    "search_memory" to "Вспоминаю", "search_archive" to "Ищу в переписках", "finance_summary" to "Смотрю финансы",
    "list_transactions" to "Смотрю операции", "spending_breakdown" to "Считаю траты", "list_obligations" to "Смотрю платежи",
    "workout_overview" to "Смотрю тренировки", "exercise_progress" to "Смотрю прогресс", "list_tasks" to "Смотрю задачи",
    "save_memory" to "Запоминаю", "create_task" to "Создаю задачу", "schedule_followup" to "Планирую проверку",
)

private fun toolLabel(name: String) = TOOL_LABELS[name] ?: name

@Composable
private fun Bubble(m: Message) {
    when (m.role) {
        "user" -> UserBubble(m.text, footer = relative(parseInstant(m.createdAt)))
        "trigger" -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Label(m.text.removePrefix("[Событие: ").removeSuffix("]"))
        }
        else -> {
            val tools = m.toolEvents.mapNotNull { runCatching { it.jsonObject.str("title") }.getOrNull() }
            AssistantBubble(m.text, tools, relative(parseInstant(m.createdAt)))
        }
    }
}

@Composable
private fun UserBubble(text: String, footer: String? = null, extra: @Composable () -> Unit = {}) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Surface(color = Tone.current.fill, shape = RoundedCornerShape(18.dp),
            modifier = Modifier.padding(start = 48.dp).widthIn(max = 300.dp)) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                SelectionContainer { Text(text, style = MaterialTheme.typography.bodyLarge) }
                if (footer != null) Row(verticalAlignment = Alignment.CenterVertically) {
                    if (footer.startsWith("Отправится")) Icon(Icons.Outlined.Schedule, null, Modifier.size(12.dp), tint = Tone.current.textSecondary)
                    Label(footer)
                }
                extra()
            }
        }
    }
}

/** Claude-style answer: no bubble, full-width serif prose; tools as one quiet line above. */
@Composable
private fun AssistantBubble(text: String, tools: List<String>, footer: String?) {
    Column(Modifier.fillMaxWidth().padding(end = 8.dp)) {
        if (tools.isNotEmpty()) ToolLine(tools)
        SelectionContainer { Markdown(text, style = AgentProse) }
        if (footer != null) Label(footer, Modifier.padding(top = 6.dp))
    }
}

@Composable
private fun ToolLine(tools: List<String>) {
    Row(Modifier.padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.Check, null, Modifier.size(14.dp), tint = Tone.current.textSecondary)
        Spacer(Modifier.width(6.dp))
        Text(tools.joinToString(" · "), style = MaterialTheme.typography.labelMedium, color = Tone.current.textSecondary)
    }
}

@Composable
private fun AssistantLive(l: LiveTurn) {
    Column(Modifier.fillMaxWidth().padding(end = 8.dp)) {
        if (l.tools.isNotEmpty()) ToolLine(l.tools.map { it.second })
        when {
            l.error != null -> Text(l.error, color = Tone.current.negative)
            l.answer.isBlank() -> Row(verticalAlignment = Alignment.CenterVertically) {
                val spin by rememberInfiniteTransition(label = "spark").animateFloat(0f, 360f,
                    infiniteRepeatable(tween(2400, easing = LinearEasing)), label = "spin")
                Box(Modifier.rotate(spin)) { Spark(18.dp) }
                Text("  думает…", style = MaterialTheme.typography.bodyMedium, color = Tone.current.textSecondary)
            }
            else -> Markdown(l.answer, style = AgentProse)
        }
    }
}
