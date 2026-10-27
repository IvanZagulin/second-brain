package ru.secondbrain.app.data.repo

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import ru.secondbrain.app.core.nowIso
import ru.secondbrain.app.data.api.ApiClient
import ru.secondbrain.app.data.api.ChatEvent
import ru.secondbrain.app.data.db.AppDatabase
import ru.secondbrain.app.data.db.PendingMessageEntity
import ru.secondbrain.app.data.model.E
import ru.secondbrain.app.data.sync.SyncManager
import java.util.UUID

class ChatRepository(
    private val api: ApiClient,
    private val db: AppDatabase,
    private val store: RecordStore,
    private val sync: SyncManager,
) {
    /**
     * Opens instantly: the conversation is created locally and pushed by the next sync; if the first message
     * gets there sooner, the server creates the conversation from it. Never waits on the network.
     */
    suspend fun newConversation(mode: String = "auto", title: String = "Новый диалог"): String =
        store.save(E.CONVERSATIONS, jsonOf("title" to title, "mode" to mode, "kind" to "chat",
            "last_message_at" to nowIso()), UUID.randomUUID().toString())

    /** Streams an answer. The caller shows deltas; the final messages arrive through sync. */
    fun send(conversationId: String, text: String, attachmentIds: List<String>, mode: String?,
             clientMessageId: String = UUID.randomUUID().toString()): Flow<ChatEvent> =
        api.streamChat(conversationId, jsonOf(
            "text" to text, "attachment_ids" to JsonArray(attachmentIds.map { JsonPrimitive(it) }),
            "client_message_id" to clientMessageId, "mode" to mode,
        )).onCompletion { sync.requestSync(0) }

    /** Offline or background (notification reply): queue and let the sync worker deliver it. */
    suspend fun queueMessage(conversationId: String, text: String, attachmentIds: List<String> = emptyList(),
                             mode: String? = null, source: String = "app") {
        db.pendingMessages().upsert(PendingMessageEntity(
            clientMessageId = UUID.randomUUID().toString(), conversationId = conversationId, text = text,
            attachmentIds = JsonArray(attachmentIds.map { JsonPrimitive(it) }).toString(), mode = mode,
        ))
        sync.requestSync(0)
    }

    fun pending(conversationId: String) = db.pendingMessages().observe(conversationId)

    suspend fun retry(clientMessageId: String) {
        db.pendingMessages().setStatus(clientMessageId, "pending", null)
        sync.requestSync(0)
    }
}
