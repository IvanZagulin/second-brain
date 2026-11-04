package ru.secondbrain.app.data.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import ru.secondbrain.app.SecondBrainApp
import ru.secondbrain.app.core.AppLog
import ru.secondbrain.app.core.Prefs
import ru.secondbrain.app.data.api.ApiClient
import ru.secondbrain.app.data.api.ApiException
import ru.secondbrain.app.data.api.PlainJson
import ru.secondbrain.app.data.db.AppDatabase
import ru.secondbrain.app.data.db.OutboxEntity
import ru.secondbrain.app.data.repo.RecordStore
import ru.secondbrain.app.data.repo.jsonOf
import ru.secondbrain.app.data.repo.str
import ru.secondbrain.app.notifications.AgentNotifier
import ru.secondbrain.app.widgets.Widgets
import java.time.Instant
import java.util.concurrent.TimeUnit

data class SyncState(val running: Boolean = false, val lastSyncAt: Long? = null, val error: String? = null,
                     val offline: Boolean = false)

/**
 * push (outbox) → pull (changes since cursor) → upload captured bank notifications → send queued
 * chat messages → show notifications that didn't arrive by FCM → refresh home cache and widgets.
 *
 * Every stage runs in its own try/catch so one failing stage does not block the others; the first
 * error is reported in [state]. Only "no network" and "token rejected" stop the whole run early.
 */
class SyncManager(
    private val context: Context,
    private val api: ApiClient,
    private val db: AppDatabase,
    private val prefs: Prefs,
    private val store: RecordStore,
    private val notifier: AgentNotifier,
    /** Device revoked from another device: wipe everything local and go back to pairing. */
    private val onRevoked: suspend () -> Unit = { prefs.saveToken(null, null) },
) {
    private val mutex = Mutex()
    private val _state = MutableStateFlow(SyncState())
    val state: StateFlow<SyncState> = _state

    /** Set by [requestSync] while a run is in progress: the run loops once more before returning. */
    @Volatile private var requestedAgain = false

    /** Why the run should stop early (offline / auth); other stage errors are collected and reported. */
    private class StopSync(val api: ApiException) : Exception(api)

    suspend fun syncNow(full: Boolean = false): Boolean {
        if (!prefs.isPaired()) return false
        return mutex.withLock {
            var ok: Boolean
            var fullRun = full
            do {
                requestedAgain = false
                ok = runOnce(fullRun)
                fullRun = false
            } while (ok && requestedAgain)
            ok
        }
    }

    private suspend fun runOnce(full: Boolean): Boolean {
        _state.value = _state.value.copy(running = true, error = null)
        var firstError: Exception? = null

        suspend fun stage(name: String, block: suspend () -> Unit) {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                if (firstError == null) firstError = e
                AppLog.w("sync", "$name failed: ${e.status} ${e.code} ${e.message}")
                if (e.isOffline || e.isAuth) throw StopSync(e)
            } catch (e: Exception) {
                if (firstError == null) firstError = e
                AppLog.e("sync", "$name failed", e)
            }
        }

        try {
            // Messages are only 'sending' while this process holds them; a crash leaves them stuck.
            runCatching { db.pendingMessages().resetSending() }
            stage("push") { push() }
            stage("pull") {
                if (full) prefs.set(Prefs.Keys.syncCursor, 0L)
                pull()
            }
            // Bank notifications become transactions and queued chat messages get answered on the server
            // right away: pull again so the results show now, not on the next sync.
            var uploaded = 0
            stage("bank") { uploaded += uploadBankNotifications() }
            stage("chat") { uploaded += sendPendingMessages() }
            if (uploaded > 0) stage("pull2") { pull() }
            stage("notifications") { pollNotifications() }
            stage("home") { refreshHome() }
        } catch (e: StopSync) {
            val cause = e.api
            if (cause.isAuth) AppLog.w("sync", "device token rejected")
            // Revoked from another device: back to pairing instead of showing stale data forever.
            if (cause.isRevoked) runCatching { onRevoked() }.onFailure { AppLog.e("sync", "wipe after revoke failed", it) }
            _state.value = _state.value.copy(running = false, error = cause.message, offline = cause.isOffline)
            return false
        }

        val now = System.currentTimeMillis()
        val err = firstError
        if (err == null) {
            prefs.set(Prefs.Keys.lastSyncAt, now)
            _state.value = SyncState(running = false, lastSyncAt = now)
            return true
        }
        _state.value = _state.value.copy(running = false, error = err.message ?: err.javaClass.simpleName,
            offline = (err as? ApiException)?.isOffline == true)
        return false
    }

    private suspend fun push() {
        val outbox = db.outbox()
        while (true) {
            val batch = outbox.next(200)
            if (batch.isEmpty()) return
            val changes = JsonArray(batch.map {
                jsonOf("entity" to it.entity, "id" to it.recordId, "op" to it.op,
                    "data" to PlainJson.parseToJsonElement(it.data), "base_version" to it.baseVersion,
                    "client_updated_at" to it.clientUpdatedAt)
            })
            val res = api.post("/api/v1/sync/push", jsonOf("changes" to changes)).jsonObject["results"]!!.jsonArray
            val bySeq = batch.associateBy { "${it.entity}/${it.recordId}" }
            val answered = HashSet<String>()
            for (r in res) {
                val o = r.jsonObject
                val key = "${o.str("entity")}/${o.str("id")}"
                val item = bySeq[key] ?: continue
                answered += key
                when (val status = o.str("status")) {
                    "applied", "applied_conflict" -> {
                        val version = o["version"]?.jsonPrimitive?.int ?: 1
                        val seq = o["server_seq"]?.jsonPrimitive?.longOrNull ?: 0
                        val current = outbox.find(item.entity, item.recordId)
                        if (current != null && current.clientUpdatedAt != item.clientUpdatedAt) {
                            // Edited again while the push was in flight: keep the newer edit, rebased.
                            outbox.put(current.copy(baseVersion = version))
                            db.records().setVersion(item.entity, item.recordId, version, seq)
                        } else {
                            outbox.remove(item.seq)
                            db.records().markSynced(item.entity, item.recordId, version, seq)
                        }
                    }
                    "conflict_server_wins" -> {
                        outbox.remove(item.seq)
                        val server = o["server"] as? JsonObject
                        if (server != null) {
                            db.records().clearDirty(item.entity, item.recordId)
                            store.applyServer(item.entity, item.recordId, server, o["version"]?.jsonPrimitive?.int ?: 1, 0,
                                server.str("deleted_at") != null)
                        }
                    }
                    else -> failedPush(item, "$status ${o.str("error") ?: ""}")
                }
            }
            // Items the server did not answer count as failures too, so a full batch cannot spin forever.
            for ((key, item) in bySeq) if (key !in answered) failedPush(item, "no result from server")
            if (batch.size < 200) return
        }
    }

    /** Retry a rejected outbox item up to 5 times; then drop it (and the dirty flag) and log it for Diagnostics. */
    private suspend fun failedPush(item: OutboxEntity, reason: String) {
        val outbox = db.outbox()
        if (item.attempts >= 4) {
            AppLog.e("sync", "push ${item.entity}/${item.recordId} dropped after ${item.attempts + 1} attempts: $reason")
            outbox.remove(item.seq)
            db.records().clearDirty(item.entity, item.recordId)
        } else {
            AppLog.w("sync", "push ${item.entity}/${item.recordId} failed (attempt ${item.attempts + 1}): $reason")
            outbox.failed(item.seq)
        }
    }

    private suspend fun pull() {
        var cursor = prefs.get(Prefs.Keys.syncCursor) ?: 0L
        repeat(200) {
            val res = api.get("/api/v1/sync/pull", mapOf("cursor" to cursor, "limit" to 1000)).jsonObject
            val changes = res["changes"]!!.jsonArray
            for (c in changes) {
                val o = c.jsonObject
                store.applyServer(o.str("entity")!!, o.str("id")!!, o["data"]!!.jsonObject,
                    o["version"]!!.jsonPrimitive.int, o["server_seq"]!!.jsonPrimitive.long,
                    o["deleted"]?.jsonPrimitive?.contentOrNull == "true", o.str("parent_id"), o.str("sort_at"))
            }
            cursor = res["cursor"]!!.jsonPrimitive.long
            prefs.set(Prefs.Keys.syncCursor, cursor)
            if (res["has_more"]?.jsonPrimitive?.contentOrNull != "true") return
        }
    }

    private suspend fun uploadBankNotifications(): Int {
        val dao = db.bankNotifications()
        val pending = dao.pending()
        if (pending.isEmpty()) return 0
        val items = JsonArray(pending.map {
            jsonOf("package" to it.packageName, "title" to it.title, "text" to it.text,
                "posted_at" to Instant.ofEpochMilli(it.postedAt).toString(), "dedupe_key" to it.dedupeKey)
        })
        val res = api.post("/api/v1/finance/notifications", jsonOf("items" to items)).jsonObject["results"]!!.jsonArray
        // Match by dedupe_key when the server echoes it; otherwise results are positional and must line up exactly.
        val echoed = res.all { it.jsonObject.str("dedupe_key") != null }
        if (echoed) {
            val byKey = res.associateBy { it.jsonObject.str("dedupe_key")!! }
            for (n in pending) {
                val r = byKey[n.dedupeKey] ?: continue
                dao.markUploaded(n.id, r.jsonObject.str("status"))
            }
        } else {
            if (res.size != pending.size) {
                throw IllegalStateException("bank upload: sent ${pending.size} notifications, got ${res.size} results")
            }
            pending.zip(res).forEach { (n, r) -> dao.markUploaded(n.id, r.jsonObject.str("status")) }
        }
        dao.prune(System.currentTimeMillis() - 14L * 24 * 3600 * 1000)
        AppLog.i("sync", "uploaded ${pending.size} bank notifications")
        return pending.size
    }

    private suspend fun sendPendingMessages(): Int {
        val dao = db.pendingMessages()
        var sent = 0
        for (m in dao.sendable()) {
            dao.setStatus(m.clientMessageId, "sending", null)
            try {
                api.post("/api/v1/chat/conversations/${m.conversationId}/messages", jsonOf(
                    "text" to m.text, "client_message_id" to m.clientMessageId, "mode" to m.mode,
                    "attachment_ids" to PlainJson.parseToJsonElement(m.attachmentIds)), mapOf("stream" to "false"))
                dao.remove(m.clientMessageId)
                sent++
            } catch (e: CancellationException) {
                dao.setStatus(m.clientMessageId, "pending", null)
                throw e
            } catch (e: ApiException) {
                // Server trouble (5xx, 429), no network or a rejected token: keep the message for the next sync.
                // Only a real client error (other 4xx) marks it failed; the owner can retry it by hand.
                val retryable = e.isOffline || e.isAuth || e.status == 429 || e.status >= 500
                dao.setStatus(m.clientMessageId, if (retryable) "pending" else "failed", e.message)
                if (e.isOffline || e.isAuth) throw e
            } catch (e: Exception) {
                dao.setStatus(m.clientMessageId, "pending", e.message)
                throw e
            }
        }
        return sent
    }

    private suspend fun pollNotifications() {
        val since = prefs.get(Prefs.Keys.lastNotificationsAt)
        val res = api.get("/api/v1/notifications", mapOf("since" to since, "limit" to 50)).jsonObject["items"]!!.jsonArray
        var newest = since
        for (item in res.reversed()) {
            val o = item.jsonObject
            val created = o.str("created_at")
            if (newest == null || (created != null && created > newest)) newest = created
            if (o.str("read_at") != null) continue
            val createdAt = runCatching { java.time.OffsetDateTime.parse(created).toInstant() }.getOrNull()
            if (createdAt != null && createdAt.isBefore(Instant.now().minusSeconds(24 * 3600))) continue
            notifier.showFromServer(o)
        }
        if (newest != null) prefs.set(Prefs.Keys.lastNotificationsAt, newest)
    }

    suspend fun refreshHome() {
        runCatching { api.get("/api/v1/home") }.onSuccess {
            prefs.set(Prefs.Keys.homeCache, it.toString())
            Widgets.updateAll(context)
        }
    }

    /**
     * Schedules a sync. KEEP (not REPLACE): replacing would cancel a sync mid-request. A request that arrives
     * while a run is in progress sets [requestedAgain], and [syncNow] loops once more before releasing the lock.
     */
    fun requestSync(delaySeconds: Long = 2) {
        requestedAgain = true
        val req = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInitialDelay(delaySeconds, TimeUnit.SECONDS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork("sync-now", ExistingWorkPolicy.KEEP, req)
    }

    companion object {
        fun schedulePeriodic(context: Context) {
            val req = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("sync-periodic", ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }
}

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as SecondBrainApp
        val ok = app.container.sync.syncNow()
        return if (ok || runAttemptCount > 3) Result.success() else Result.retry()
    }
}
