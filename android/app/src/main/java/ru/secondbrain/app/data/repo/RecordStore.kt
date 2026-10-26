package ru.secondbrain.app.data.repo

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import ru.secondbrain.app.core.AppLog
import ru.secondbrain.app.core.nowIso
import ru.secondbrain.app.data.api.AppJson
import ru.secondbrain.app.data.api.PlainJson
import ru.secondbrain.app.data.db.AppDatabase
import ru.secondbrain.app.data.db.OutboxEntity
import ru.secondbrain.app.data.db.RecordEntity
import java.util.UUID

/** Fields the server owns; never sent back in a push. */
private val SERVER_FIELDS = setOf("owner_id", "server_seq", "version", "created_at", "updated_at", "deleted_at", "tsv")

/** Column the phone indexes as parent / sort key, mirroring the server's __sync_parent__/__sync_sort__. */
private val PARENT = mapOf("messages" to "conversation_id", "plan_exercises" to "plan_id", "workout_sets" to "workout_id",
    "debt_payments" to "debt_id", "goal_contributions" to "goal_id", "habit_logs" to "habit_id", "tasks" to "parent_id")
private val SORT = mapOf("transactions" to "occurred_at", "messages" to "created_at", "workouts" to "started_at",
    "workout_sets" to "done_at", "body_metrics" to "measured_at", "tasks" to "due_at", "mood_entries" to "occurred_at",
    "conversations" to "last_message_at", "memories" to "created_at", "notifications" to "created_at",
    "life_events" to "occurred_at", "meals" to "eaten_at", "progress_photos" to "taken_at", "debt_payments" to "paid_at",
    "goal_contributions" to "at", "habit_logs" to "day", "subscriptions" to "next_charge_date", "debts" to "next_payment_date",
    "agent_actions" to "created_at", "agent_jobs" to "next_run_at", "tax_payments" to "due_date",
    "financial_goals" to "deadline", "receipts" to "occurred_at", "wishlist_items" to "created_at")

fun jsonOf(vararg pairs: Pair<String, Any?>): JsonObject = buildJsonObject {
    pairs.forEach { (k, v) -> put(k, toJson(v)) }
}

fun toJson(v: Any?): JsonElement = when (v) {
    null -> JsonNull
    is JsonElement -> v
    is String -> JsonPrimitive(v)
    is Number -> JsonPrimitive(v)
    is Boolean -> JsonPrimitive(v)
    is List<*> -> kotlinx.serialization.json.JsonArray(v.map { toJson(it) })
    is Map<*, *> -> JsonObject(v.entries.associate { (k, value) -> k.toString() to toJson(value) })
    else -> JsonPrimitive(v.toString())
}

fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

class RecordStore(private val db: AppDatabase, private val onLocalChange: () -> Unit) {
    private val records = db.records()

    // Flows are cached per query so composables calling observe() on every recomposition keep
    // collecting the same instance instead of restarting the Room query each time.
    private val flows = java.util.concurrent.ConcurrentHashMap<String, Flow<*>>()

    @Suppress("UNCHECKED_CAST")
    private fun <F> cached(key: String, create: () -> Flow<F>): Flow<F> = flows.getOrPut(key) { create() } as Flow<F>

    fun <T> observe(entity: String, serializer: KSerializer<T>): Flow<List<T>> = cached("all/$entity") {
        records.observe(entity).map { list -> list.mapNotNull { decode(it, serializer) } }
    }

    fun <T> observeChildren(entity: String, parentId: String, serializer: KSerializer<T>): Flow<List<T>> =
        cached("children/$entity/$parentId") {
            records.observeChildren(entity, parentId).map { list -> list.mapNotNull { decode(it, serializer) } }
        }

    fun <T> observeOne(entity: String, id: String, serializer: KSerializer<T>): Flow<T?> = cached("one/$entity/$id") {
        records.observeOne(entity, id).map { r -> r?.takeIf { !it.deleted }?.let { decode(it, serializer) } }
    }

    suspend fun <T> all(entity: String, serializer: KSerializer<T>): List<T> =
        records.all(entity).mapNotNull { decode(it, serializer) }

    suspend fun <T> children(entity: String, parentId: String, serializer: KSerializer<T>): List<T> =
        records.children(entity, parentId).mapNotNull { decode(it, serializer) }

    suspend fun <T> get(entity: String, id: String, serializer: KSerializer<T>): T? =
        records.get(entity, id)?.takeIf { !it.deleted }?.let { decode(it, serializer) }

    suspend fun isDirty(entity: String, id: String): Boolean = records.get(entity, id)?.dirty == true

    private fun <T> decode(r: RecordEntity, serializer: KSerializer<T>): T? =
        runCatching { AppJson.decodeFromString(serializer, r.data) }
            .onFailure { AppLog.w("store", "decode ${r.entity}: ${it.message}") }.getOrNull()

    /**
     * Create or edit a record locally (works offline): merges [fields] into the stored document,
     * marks it dirty and queues it for push. Returns the record id.
     */
    suspend fun save(entity: String, fields: JsonObject, id: String? = null): String {
        val recordId = id ?: fields.str("id") ?: UUID.randomUUID().toString()
        val existing = records.get(entity, recordId)
        val base = existing?.let { PlainJson.parseToJsonElement(it.data).jsonObject } ?: JsonObject(emptyMap())
        val merged = JsonObject(base + fields + ("id" to JsonPrimitive(recordId)))
        records.upsert(
            RecordEntity(entity = entity, id = recordId, data = merged.toString(),
                parentId = PARENT[entity]?.let { merged.str(it) }, sortAt = SORT[entity]?.let { merged.str(it) }
                    ?: existing?.sortAt ?: nowIso(),
                version = existing?.version ?: 0, serverSeq = existing?.serverSeq ?: 0, deleted = false, dirty = true)
        )
        val payload = JsonObject(merged.filterKeys { it !in SERVER_FIELDS })
        val queued = db.outbox().find(entity, recordId)
        db.outbox().put(OutboxEntity(seq = queued?.seq ?: 0, entity = entity, recordId = recordId, op = "upsert",
            data = payload.toString(), baseVersion = queued?.baseVersion ?: existing?.version?.takeIf { it > 0 },
            clientUpdatedAt = nowIso()))
        onLocalChange()
        return recordId
    }

    suspend fun delete(entity: String, id: String) {
        val existing = records.get(entity, id) ?: return
        records.upsert(existing.copy(deleted = true, dirty = true, updatedAt = System.currentTimeMillis()))
        val queued = db.outbox().find(entity, id)
        db.outbox().put(OutboxEntity(seq = queued?.seq ?: 0, entity = entity, recordId = id, op = "delete", data = "{}",
            baseVersion = existing.version.takeIf { it > 0 }, clientUpdatedAt = nowIso()))
        onLocalChange()
    }

    /** Apply a server row (from pull or a REST response). Local unsynced edits win until pushed. */
    suspend fun applyServer(entity: String, id: String, data: JsonObject, version: Int, serverSeq: Long, deleted: Boolean,
                            parentId: String? = null, sortAt: String? = null) {
        val local = records.get(entity, id)
        if (local != null && local.dirty && db.outbox().find(entity, id) != null) return
        records.upsert(RecordEntity(entity = entity, id = id, data = data.toString(),
            parentId = parentId ?: PARENT[entity]?.let { data.str(it) },
            sortAt = sortAt ?: SORT[entity]?.let { data.str(it) } ?: data.str("created_at"),
            version = version, serverSeq = serverSeq, deleted = deleted, dirty = false))
    }

    /** Insert an object returned by a REST call so the UI updates before the next pull. */
    suspend fun putFromServer(entity: String, obj: JsonElement) {
        val o = obj as? JsonObject ?: return
        val id = o.str("id") ?: return
        applyServer(entity, id, o, o["version"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 1,
            o["server_seq"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0, o.str("deleted_at") != null)
    }
}
