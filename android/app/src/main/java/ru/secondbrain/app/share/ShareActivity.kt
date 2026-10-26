package ru.secondbrain.app.share

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.jsonObject
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import ru.secondbrain.app.AppContainer
import ru.secondbrain.app.MainActivity
import ru.secondbrain.app.SecondBrainApp
import ru.secondbrain.app.core.Prefs
import ru.secondbrain.app.data.model.E
import ru.secondbrain.app.data.repo.jsonOf
import ru.secondbrain.app.data.repo.str
import ru.secondbrain.app.notifications.AgentNotifier
import ru.secondbrain.app.ui.components.AppCard
import ru.secondbrain.app.ui.components.Secondary
import ru.secondbrain.app.ui.theme.SecondBrainTheme
import ru.secondbrain.app.ui.theme.Tone
import kotlin.coroutines.resume

/** Receives links, text, images, PDFs, statements and archives from the system share sheet. */
class ShareActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val c = (application as SecondBrainApp).container
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)
        val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT)
        @Suppress("DEPRECATION")
        val uri: Uri? = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else intent.getParcelableExtra(Intent.EXTRA_STREAM)
        val mime = intent.type ?: "text/plain"
        setContent { SecondBrainTheme { ShareSheet(c, text, subject, uri, mime) } }
    }

    private suspend fun unlock(c: AppContainer): Boolean {
        if (c.prefs.get(Prefs.Keys.biometricEnabled) == false) return true
        val auth = if (Build.VERSION.SDK_INT >= 30) BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL
        else BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL
        if (BiometricManager.from(this).canAuthenticate(auth) != BiometricManager.BIOMETRIC_SUCCESS) return true
        return suspendCancellableCoroutine { cont ->
            BiometricPrompt(this, ContextCompat.getMainExecutor(this), object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) { if (cont.isActive) cont.resume(true) }
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) { if (cont.isActive) cont.resume(false) }
            }).authenticate(BiometricPrompt.PromptInfo.Builder().setTitle("Второй мозг").setAllowedAuthenticators(auth).build())
        }
    }

    private fun open(route: String) {
        startActivity(Intent(this, MainActivity::class.java).putExtra(AgentNotifier.EXTRA_ROUTE, route)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
        finish()
    }

    private fun fileName(uri: Uri): String = contentResolver.query(uri, null, null, null, null)?.use { cur ->
        val idx = cur.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (cur.moveToFirst() && idx >= 0) cur.getString(idx) else null
    } ?: "file"

    @Composable
    private fun ShareSheet(c: AppContainer, text: String?, subject: String?, uri: Uri?, mime: String) {
        val scope = rememberCoroutineScope()
        var busy by remember { mutableStateOf(false) }
        var result by remember { mutableStateOf<String?>(null) }
        val url = text?.let { Regex("https?://\\S+").find(it)?.value }
        val name = uri?.let { fileName(it) } ?: ""
        val lower = name.lowercase()

        fun run(block: suspend () -> Unit) = scope.launch {
            if (!c.prefs.isPaired()) { result = "Сначала подключите приложение к серверу"; return@launch }
            if (!unlock(c)) return@launch
            busy = true
            try { block() } catch (e: Exception) { result = e.message ?: "Ошибка" }
            busy = false
        }

        suspend fun uploadAttachment(): String {
            val bytes = contentResolver.openInputStream(uri!!)!!.use { it.readBytes() }
            return c.api.upload("/api/v1/chat/attachments", name, mime, bytes.toRequestBody(mime.toMediaTypeOrNull())).jsonObject.str("id")!!
        }

        Column(Modifier.fillMaxSize().systemBarsPadding().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Что сделать?", style = MaterialTheme.typography.headlineLarge)
            AppCard(tint = Tone.current.memory) {
                Secondary(listOfNotNull(subject, text?.take(300), name.takeIf { it.isNotEmpty() }).joinToString("\n").ifBlank { mime })
            }
            if (busy) CircularProgressIndicator()
            result?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
            if (text != null) {
                if (url != null) Button(onClick = { run {
                    val r = c.api.post("/api/v1/finance/wishlist/analyze", jsonOf("title" to (subject ?: text.replace(url, "").trim().ifBlank { "Покупка по ссылке" }), "url" to url)).jsonObject
                    c.sync.requestSync(0); result = "Разбор готов"; open("finance/wishlist"); r
                } }) { Text("Хочу купить — разобрать") }
                Button(onClick = { run { c.store.save(E.NOTES, jsonOf("text" to listOfNotNull(subject, text).joinToString("\n"))); result = "Запомнено"; finish() } }) { Text("Запомнить") }
                OutlinedButton(onClick = { run {
                    val conv = c.chat.newConversation()
                    c.chat.queueMessage(conv, listOfNotNull(subject, text).joinToString("\n"), source = "share")
                    open("chat/$conv")
                } }) { Text("Обсудить с агентом") }
                OutlinedButton(onClick = { run { c.store.save(E.TASKS, jsonOf("title" to (subject ?: text).take(200), "notes" to text, "status" to "open")); result = "Задача создана"; finish() } }) { Text("В задачи") }
            }
            if (uri != null) {
                val isImage = mime.startsWith("image/")
                val isPdf = mime == "application/pdf" || lower.endsWith(".pdf")
                val isTable = lower.endsWith(".csv") || lower.endsWith(".xlsx") || lower.endsWith(".txt")
                val isArchive = lower.endsWith(".zip") || lower.endsWith(".json")
                if (isImage || isPdf) Button(onClick = { run {
                    val att = uploadAttachment()
                    val r = c.api.post("/api/v1/finance/receipts/attachment", jsonOf("attachment_id" to att)).jsonObject
                    c.sync.requestSync(0)
                    result = "Чек: ${r.str("store") ?: ""} ${r["total_minor"]?.toString() ?: ""}"
                } }) { Text("Это чек") }
                if (isPdf || isTable) Button(onClick = { run {
                    val bytes = contentResolver.openInputStream(uri)!!.use { it.readBytes() }
                    val r = c.api.upload("/api/v1/finance/statements", name, mime, bytes.toRequestBody(mime.toMediaTypeOrNull())).jsonObject
                    c.sync.requestSync(0)
                    result = "Выписка: новых ${r.str("created")}, совпало ${r.str("linked")}, уже было ${r.str("duplicate")}"
                } }) { Text("Импорт выписки") }
                if (isArchive) Button(onClick = { run {
                    val bytes = contentResolver.openInputStream(uri)!!.use { it.readBytes() }
                    val job = c.api.upload("/api/v1/imports", name, mime, bytes.toRequestBody(mime.toMediaTypeOrNull()), mapOf("kind" to "auto")).jsonObject
                    open("memory/import/${job.str("id")}")
                } }) { Text("Импорт архива переписки") }
                OutlinedButton(onClick = { run {
                    val att = uploadAttachment()
                    val conv = c.chat.newConversation()
                    c.chat.queueMessage(conv, if (isImage) "Посмотри на изображение." else "Посмотри документ «$name».", listOf(att), source = "share")
                    open("chat/$conv")
                } }) { Text("Обсудить с агентом") }
            }
            TextButton(onClick = { finish() }) { Text("Закрыть") }
        }
    }
}
