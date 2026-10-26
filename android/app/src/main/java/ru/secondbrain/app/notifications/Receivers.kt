package ru.secondbrain.app.notifications

import android.app.Notification
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import ru.secondbrain.app.SecondBrainApp
import ru.secondbrain.app.core.AppLog
import ru.secondbrain.app.core.Prefs
import ru.secondbrain.app.data.db.BankNotificationEntity
import ru.secondbrain.app.data.repo.jsonOf
import java.security.MessageDigest
import java.util.UUID

private val receiverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

/** Actions from notification buttons: reply, confirm/reject an agent action, snooze, task done. */
class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as SecondBrainApp
        val c = app.container
        val notifId = intent.getIntExtra(AgentNotifier.EXTRA_NOTIF_INT, 0)
        val serverId = intent.getStringExtra(AgentNotifier.EXTRA_NOTIFICATION_ID)
        val pending = goAsync()
        receiverScope.launch {
            try {
                when (intent.action) {
                    AgentNotifier.ACTION_REPLY -> {
                        val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(AgentNotifier.KEY_REPLY)?.toString()
                        val conv = intent.getStringExtra(AgentNotifier.EXTRA_CONVERSATION_ID)
                        if (!text.isNullOrBlank() && conv != null) {
                            c.chat.queueMessage(conv, text, source = "notification_reply")
                            c.notifier.simple(notifId, "Ответ отправлен", text, AgentNotifier.CH_QUIET)
                        }
                    }
                    AgentNotifier.ACTION_CONFIRM -> {
                        intent.getStringExtra(AgentNotifier.EXTRA_ACTION_ID)?.let { c.api.post("/api/v1/agent/actions/$it/confirm") }
                        NotificationManagerCompat.from(context).cancel(notifId)
                    }
                    AgentNotifier.ACTION_REJECT -> {
                        intent.getStringExtra(AgentNotifier.EXTRA_ACTION_ID)?.let { c.api.post("/api/v1/agent/actions/$it/reject") }
                        NotificationManagerCompat.from(context).cancel(notifId)
                    }
                    AgentNotifier.ACTION_SNOOZE -> {
                        serverId?.let { c.api.post("/api/v1/notifications/$it/snooze", jsonOf("minutes" to 60)) }
                        NotificationManagerCompat.from(context).cancel(notifId)
                    }
                    AgentNotifier.ACTION_TASK_DONE -> {
                        intent.getStringExtra(AgentNotifier.EXTRA_TASK_ID)?.let {
                            c.store.save("tasks", jsonOf("status" to "done", "completed_at" to ru.secondbrain.app.core.nowIso()), it)
                        }
                        NotificationManagerCompat.from(context).cancel(notifId)
                    }
                }
                serverId?.let { runCatching { c.api.post("/api/v1/notifications/$it/read") } }
                c.sync.requestSync()
            } catch (e: Exception) {
                AppLog.w("notif", "action ${intent.action} failed: ${e.message}")
            } finally {
                pending.finish()
            }
        }
    }
}

/** FCM: data-only messages; the app renders the notification itself. */
class PushService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        val c = (applicationContext as SecondBrainApp).container
        receiverScope.launch {
            c.prefs.set(Prefs.Keys.fcmToken, token)
            runCatching { c.api.post("/api/v1/auth/push-token", jsonOf("fcm_token" to token)) }
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val c = (applicationContext as SecondBrainApp).container
        receiverScope.launch {
            if (message.data["type"] == "notification") c.notifier.showFromPush(message.data)
            c.sync.requestSync(1)
        }
    }
}

/**
 * Reads notifications of allow-listed bank / payment apps only (Mir Pay, Sber, T-Bank by default),
 * stores them locally and uploads them for parsing. Nothing else is read or kept.
 */
class BankNotificationListener : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return
        val n = sbn.notification ?: return
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val c = (applicationContext as? SecondBrainApp)?.container ?: return
        receiverScope.launch {
            val allowed = c.allowedPackages()
            if (sbn.packageName !in allowed) return@launch
            val extras = n.extras
            val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
            val big = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
            val text = big ?: extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
                ?: extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.joinToString("\n")
            if (text.isNullOrBlank()) return@launch
            val key = sha("${sbn.packageName}|$title|$text|${sbn.postTime / 60000}")
            val id = c.db.bankNotifications().insert(BankNotificationEntity(packageName = sbn.packageName, title = title,
                text = text, postedAt = sbn.postTime, dedupeKey = key))
            if (id != -1L) {
                AppLog.i("bank", "captured from ${sbn.packageName}")
                c.sync.requestSync(3)
            }
        }
    }

    private fun sha(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray())
        .joinToString("") { "%02x".format(it) }
}

/** Injects a synthetic bank notification into the same pipeline (Diagnostics screen, emulator tests). */
suspend fun injectTestBankNotification(app: SecondBrainApp, packageName: String, title: String, text: String) {
    val c = app.container
    c.db.bankNotifications().insert(BankNotificationEntity(packageName = packageName, title = title, text = text,
        postedAt = System.currentTimeMillis(), dedupeKey = UUID.randomUUID().toString()))
    c.sync.requestSync(1)
}
