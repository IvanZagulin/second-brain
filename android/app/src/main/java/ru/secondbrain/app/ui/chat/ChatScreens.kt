package ru.secondbrain.app.ui.chat

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Schedule
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import ru.secondbrain.app.ui.theme.Tone
import java.util.UUID

@Composable
fun ConversationsScreen() {
    val c = LocalContainer.current
    val nav = LocalNavigate.current
    val scope = rememberCoroutineScope()
    val convs by c.store.observe(E.CONVERSATIONS, Conversation.serializer()).collectAsState(initial = emptyList())
    var showArchived by remember { mutableStateOf(false) }
    val list = convs.filter { it.archived == showArchived }
        .sortedWith(compareByDescending<Conversation> { it.pinned || it.kind == "agent" }.thenByDescending { it.lastMessageAt ?: "" })
    Screen("Чат", back = false, onRefresh = { c.sync.syncNow() },
        actions = { TextButton(onClick = { showArchived = !showArchived }) { Text(if (showArchived) "Активные" else "Архив") } },
        fab = {
            ExtendedFloatingActionButton(onClick = { scope.launch { nav("chat/${c.chat.newConversation()}") } },
                icon = { Icon(Icons.Default.Add, null) }, text = { Text("Новый диалог") },
                containerColor = Tone.current.accent, contentColor = Tone.current.surface)
        }) {
        if (list.isEmpty()) item { EmptyState("Диалогов пока нет. Начните новый — агент помнит всё между диалогами.") }
        items(list, key = { it.id }) { conv ->
            AppCard(tint = if (conv.kind == "agent") Tone.current.memory else Tone.current.surface, onClick = { nav("chat/${conv.id}") }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (conv.pinned || conv.kind == "agent") Icon(Icons.Outlined.PushPin, null, Modifier.size(16.dp), tint = Tone.current.accent)
                    Text((if (conv.kind == "agent") "Агент пишет первым" else conv.title), style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f).padding(start = 4.dp))
                    if (conv.mode != "auto") Pill(CHAT_MODES[conv.mode] ?: conv.mode, Tone.current.training)
                }
                Secondary(relative(parseInstant(conv.lastMessageAt)))
            }
        }
    }
}

private data class LiveTurn(val userText: String, val answer: String = "", val tools: List<Pair<String, String>> = emptyList(),
                            val error: String? = null, val done: Boolean = false)

@Composable
fun ChatScreen(conversationId: String) {
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
            // A conversation created offline must reach the server before its first message.
            if (c.store.isDirty(E.CONVERSATIONS, conversationId)) runCatching { c.sync.syncNow() }
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

    Scaffold(
        containerColor = Tone.current.bg,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(if (conv?.kind == "agent") "Агент" else conv?.title ?: "Диалог", style = MaterialTheme.typography.titleLarge, maxLines = 1)
                        Box {
                            Text("Режим: ${CHAT_MODES[conv?.mode ?: "auto"]}", style = MaterialTheme.typography.labelMedium,
                                color = Tone.current.accent, modifier = Modifier.padding(top = 2.dp).then(Modifier))
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
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Tone.current.bg),
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState,
                contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (visible.isEmpty() && live == null && pending.isEmpty()) item {
                    AppCard(tint = Tone.current.memory) {
                        Text("С чего начнём?", style = MaterialTheme.typography.titleMedium)
                        Secondary("Спросите про деньги, тренировку, планы — или просто поговорите. Можно сменить режим сверху.")
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
            Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(8.dp), verticalAlignment = Alignment.Bottom) {
                IconButton(onClick = { picker.launch("*/*") }) { Icon(Icons.Outlined.AttachFile, "Вложение") }
                OutlinedTextField(value = input, onValueChange = { input = it }, modifier = Modifier.weight(1f),
                    placeholder = { Text("Сообщение") }, maxLines = 6, shape = RoundedCornerShape(24.dp),
                    colors = OutlinedTextFieldDefaults.colors(unfocusedContainerColor = Tone.current.surface,
                        focusedContainerColor = Tone.current.surface, unfocusedBorderColor = Tone.current.divider))
                if (input.isBlank() && attachments.isEmpty()) {
                    IconButton(onClick = {
                        runCatching {
                            voice.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
                                .putExtra(RecognizerIntent.EXTRA_PROMPT, "Говорите"))
                        }.onFailure { scope.launch { snackbar.showSnackbar("Распознавание речи недоступно на устройстве") } }
                    }) { Icon(Icons.Outlined.Mic, "Голосом") }
                } else {
                    IconButton(onClick = { send() }, enabled = live == null || live?.done == true) {
                        Icon(Icons.AutoMirrored.Filled.Send, "Отправить", tint = Tone.current.accent)
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
        Surface(color = Tone.current.training, shape = RoundedCornerShape(22.dp, 22.dp, 6.dp, 22.dp),
            modifier = Modifier.widthIn(max = 320.dp)) {
            Column(Modifier.padding(14.dp)) {
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

@Composable
private fun AssistantBubble(text: String, tools: List<String>, footer: String?) {
    Column(Modifier.fillMaxWidth()) {
        if (tools.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 4.dp)) {
            tools.forEach { Pill("✓ $it", Tone.current.memory) }
        }
        Surface(color = Tone.current.surface, shape = RoundedCornerShape(22.dp, 22.dp, 22.dp, 6.dp),
            modifier = Modifier.widthIn(max = 340.dp)) {
            Column(Modifier.padding(14.dp)) {
                SelectionContainer { Markdown(text) }
                if (footer != null) Label(footer)
            }
        }
    }
}

@Composable
private fun AssistantLive(l: LiveTurn) {
    Column(Modifier.fillMaxWidth()) {
        if (l.tools.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 4.dp)) {
            l.tools.forEach { Pill(it.second, Tone.current.memory) }
        }
        Surface(color = Tone.current.surface, shape = RoundedCornerShape(22.dp, 22.dp, 22.dp, 6.dp), modifier = Modifier.widthIn(max = 340.dp)) {
            Column(Modifier.padding(14.dp)) {
                when {
                    l.error != null -> Text(l.error, color = Tone.current.negative)
                    l.answer.isBlank() -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Text("  думает…", color = Tone.current.textSecondary)
                    }
                    else -> Markdown(l.answer)
                }
            }
        }
    }
}
