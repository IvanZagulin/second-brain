package ru.secondbrain.app.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import kotlinx.serialization.json.JsonObject
import ru.secondbrain.app.MainActivity
import ru.secondbrain.app.R
import ru.secondbrain.app.data.db.AppDatabase
import ru.secondbrain.app.data.db.ShownNotificationEntity
import ru.secondbrain.app.data.repo.str

/** Builds local notifications for agent messages, finance alerts, reminders and confirmations. */
class AgentNotifier(private val context: Context, private val db: AppDatabase) {

    data class Spec(
        val id: String, val title: String, val body: String, val category: String, val priority: String,
        val silent: Boolean, val sensitive: Boolean, val conversationId: String?, val actionId: String?,
        val taskId: String?, val shareText: String?,
    )

    fun createChannels() {
        val nm = context.getSystemService(NotificationManager::class.java)
        listOf(
            NotificationChannel(CH_AGENT, "Сообщения агента", NotificationManager.IMPORTANCE_HIGH),
            NotificationChannel(CH_CONFIRM, "Подтверждения действий", NotificationManager.IMPORTANCE_HIGH),
            NotificationChannel(CH_FINANCE, "Финансы", NotificationManager.IMPORTANCE_DEFAULT),
            NotificationChannel(CH_TASKS, "Задачи и напоминания", NotificationManager.IMPORTANCE_DEFAULT),
            NotificationChannel(CH_QUIET, "Тихие (ночью)", NotificationManager.IMPORTANCE_LOW).apply { setSound(null, null) },
            NotificationChannel(CH_SYSTEM, "Служебные", NotificationManager.IMPORTANCE_LOW),
        ).forEach { nm.createNotificationChannel(it) }
    }

    suspend fun showFromServer(o: JsonObject) {
        val data = o["data"] as? JsonObject
        show(Spec(
            id = o.str("id") ?: return, title = o.str("title") ?: "Второй мозг", body = o.str("body") ?: "",
            category = o.str("category") ?: "agent", priority = o.str("priority") ?: "normal",
            silent = o.str("silent") == "true", sensitive = o.str("sensitive") == "true",
            conversationId = o.str("conversation_id"), actionId = o.str("action_id"),
            taskId = data?.str("task_id"), shareText = data?.str("share_text"),
        ))
    }

    suspend fun showFromPush(data: Map<String, String>) {
        show(Spec(
            id = data["id"] ?: return, title = data["title"] ?: "Второй мозг", body = data["body"] ?: "",
            category = data["category"] ?: "agent", priority = data["priority"] ?: "normal",
            silent = data["silent"] == "1", sensitive = data["sensitive"] == "1",
            conversationId = data["conversation_id"]?.ifBlank { null }, actionId = data["action_id"]?.ifBlank { null },
            taskId = null, shareText = null,
        ))
    }

    suspend fun show(s: Spec) {
        if (db.shown().mark(ShownNotificationEntity(s.id)) == -1L) return  // already shown via the other path
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val notifId = s.id.hashCode()
        val channel = when {
            s.silent -> CH_QUIET
            s.category == "confirm" -> CH_CONFIRM
            s.category in setOf("finance", "budget") -> CH_FINANCE
            s.category == "task" -> CH_TASKS
            s.category == "system" -> CH_SYSTEM
            else -> CH_AGENT
        }
        val open = PendingIntent.getActivity(context, notifId,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(EXTRA_ROUTE, when {
                    s.conversationId != null -> "chat/${s.conversationId}"
                    s.actionId != null -> "agent/history"
                    s.category in setOf("finance", "budget") -> "finance"
                    s.category == "task" -> "tasks"
                    else -> "home"
                })
                putExtra(EXTRA_NOTIFICATION_ID, s.id)
            }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val builder = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(0xFFCF785D.toInt())
            .setContentTitle(s.title)
            .setContentText(s.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(s.body))
            .setAutoCancel(true)
            .setContentIntent(open)
            .setPriority(if (s.priority == "high") NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(if (s.category == "agent") NotificationCompat.CATEGORY_MESSAGE else NotificationCompat.CATEGORY_REMINDER)
        if (s.sensitive) {
            builder.setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            builder.setPublicVersion(NotificationCompat.Builder(context, channel).setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Второй мозг").setContentText("Новое сообщение").build())
        }

        if (s.conversationId != null) {
            val replyIntent = actionIntent(ACTION_REPLY, notifId, s) { putExtra(EXTRA_CONVERSATION_ID, s.conversationId) }
            val input = RemoteInput.Builder(KEY_REPLY).setLabel("Ответ").build()
            builder.addAction(NotificationCompat.Action.Builder(0, "Ответить", replyIntent).addRemoteInput(input)
                .setAllowGeneratedReplies(false).build())
        }
        if (s.actionId != null && s.category == "confirm") {
            builder.addAction(0, "Подтвердить", actionIntent(ACTION_CONFIRM, notifId, s) { putExtra(EXTRA_ACTION_ID, s.actionId) })
            builder.addAction(0, "Отклонить", actionIntent(ACTION_REJECT, notifId, s) { putExtra(EXTRA_ACTION_ID, s.actionId) })
        } else {
            builder.addAction(0, "Отложить на час", actionIntent(ACTION_SNOOZE, notifId, s) {})
        }
        if (s.taskId != null) {
            builder.addAction(0, "Готово", actionIntent(ACTION_TASK_DONE, notifId, s) { putExtra(EXTRA_TASK_ID, s.taskId) })
        }
        if (s.shareText != null) {
            val share = Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"; putExtra(Intent.EXTRA_TEXT, s.shareText)
            }, "Отправить").apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }
            builder.addAction(0, "Отправить", PendingIntent.getActivity(context, notifId + 7, share,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        }
        NotificationManagerCompat.from(context).notify(notifId, builder.build())
    }

    private fun actionIntent(action: String, notifId: Int, s: Spec, extra: Intent.() -> Unit): PendingIntent {
        val intent = Intent(context, NotificationActionReceiver::class.java).apply {
            this.action = action
            putExtra(EXTRA_NOTIFICATION_ID, s.id)
            putExtra(EXTRA_NOTIF_INT, notifId)
            extra()
        }
        val mutable = if (action == ACTION_REPLY) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(context, (action + notifId).hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or mutable)
    }

    /** A local notification; tapping it opens the app, on [route] when given. */
    fun simple(id: Int, title: String, body: String, channel: String = CH_SYSTEM, route: String? = null) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val open = PendingIntent.getActivity(context, id,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                route?.let { putExtra(EXTRA_ROUTE, it) }
            }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        NotificationManagerCompat.from(context).notify(id, NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification).setContentTitle(title).setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body)).setContentIntent(open).setAutoCancel(true).build())
    }

    companion object {
        const val CH_AGENT = "agent"
        const val CH_CONFIRM = "confirm"
        const val CH_FINANCE = "finance"
        const val CH_TASKS = "tasks"
        const val CH_QUIET = "quiet"
        const val CH_SYSTEM = "system"
        const val EXTRA_ROUTE = "route"
        const val EXTRA_NOTIFICATION_ID = "notification_id"
        const val EXTRA_NOTIF_INT = "notif_int"
        const val EXTRA_CONVERSATION_ID = "conversation_id"
        const val EXTRA_ACTION_ID = "action_id"
        const val EXTRA_TASK_ID = "task_id"
        const val KEY_REPLY = "reply_text"
        const val ACTION_REPLY = "ru.secondbrain.app.REPLY"
        const val ACTION_CONFIRM = "ru.secondbrain.app.CONFIRM"
        const val ACTION_REJECT = "ru.secondbrain.app.REJECT"
        const val ACTION_SNOOZE = "ru.secondbrain.app.SNOOZE"
        const val ACTION_TASK_DONE = "ru.secondbrain.app.TASK_DONE"
    }
}
