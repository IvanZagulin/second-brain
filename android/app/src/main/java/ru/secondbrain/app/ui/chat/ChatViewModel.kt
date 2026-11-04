package ru.secondbrain.app.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import ru.secondbrain.app.AppContainer
import ru.secondbrain.app.data.api.ChatEvent
import java.util.UUID

/** The turn being streamed right now: shown under the stored messages until sync delivers the real ones. */
data class LiveTurn(val userText: String, val answer: String = "", val tools: List<Pair<String, String>> = emptyList(),
                    val error: String? = null, val done: Boolean = false)

/**
 * Owns the streaming send for one conversation. Lives in the back-stack entry's ViewModelStore, so a rotation
 * or a screen pushed on top no longer cancels the stream and drops the live answer.
 */
class ChatViewModel(private val c: AppContainer, private val conversationId: String) : ViewModel() {
    private val _live = MutableStateFlow<LiveTurn?>(null)
    val live: StateFlow<LiveTurn?> = _live.asStateFlow()

    fun send(text: String, attachmentIds: List<String>) {
        _live.value = LiveTurn(text)
        val clientId = UUID.randomUUID().toString()
        viewModelScope.launch {
            // Set by the Start event: from then on the server owns the turn and the answer arrives through sync.
            var started = false
            c.chat.send(conversationId, text, attachmentIds, null, clientId).collect { ev ->
                val live = _live.value
                _live.value = when (ev) {
                    is ChatEvent.Delta -> live?.copy(answer = (live.answer) + ev.text)
                    is ChatEvent.Tool -> live?.let { l ->
                        val others = l.tools.filter { it.first != ev.name }
                        l.copy(tools = others + (ev.name to (if (ev.status == "running") "…" else "✓") + " " + (ev.title ?: toolLabel(ev.name))))
                    }
                    is ChatEvent.Done -> live?.copy(done = true)
                    is ChatEvent.Error -> {
                        if (ev.code == "offline") {
                            // Never reached the server: queue it under the SAME client id so a late delivery of the
                            // original request cannot produce a second message. Dropped mid-stream: the server keeps
                            // running the turn; the sync below pulls the stored messages for this conversation.
                            if (!started) c.chat.queueMessage(conversationId, text, attachmentIds, clientMessageId = clientId)
                            null
                        } else live?.copy(error = ev.message, done = true)
                    }
                    is ChatEvent.Start -> { started = true; live }
                }
            }
            runCatching { c.sync.syncNow() }
            if (_live.value?.error == null) _live.value = null
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
