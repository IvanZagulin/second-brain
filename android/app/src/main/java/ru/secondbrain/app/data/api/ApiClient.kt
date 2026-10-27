package ru.secondbrain.app.data.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNamingStrategy
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import ru.secondbrain.app.BuildConfig
import ru.secondbrain.app.core.AppLog
import ru.secondbrain.app.core.Prefs
import java.io.IOException
import java.util.concurrent.TimeUnit

val AppJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    coerceInputValues = true
    isLenient = true
    encodeDefaults = true
    namingStrategy = JsonNamingStrategy.SnakeCase
}

/** Same JSON settings without renaming: for raw JsonObject payloads already in snake_case. */
val PlainJson = Json { ignoreUnknownKeys = true; explicitNulls = false; isLenient = true }

class ApiException(val status: Int, val code: String, override val message: String) : IOException(message) {
    val isAuth get() = status == 401
    val isRevoked get() = status == 401 && code == "device_revoked"
    val isOffline get() = status == 0
}

sealed interface ChatEvent {
    data class Start(val userMessage: JsonObject?) : ChatEvent
    data class Delta(val text: String) : ChatEvent
    data class Tool(val name: String, val status: String, val title: String?) : ChatEvent
    data class Done(val message: JsonObject?, val costUsd: Double?) : ChatEvent
    data class Error(val code: String, val message: String) : ChatEvent
}

class ApiClient(private val prefs: Prefs) {
    private val json = "application/json; charset=utf-8".toMediaType()

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(300, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val streamHttp: OkHttpClient = http.newBuilder().readTimeout(0, TimeUnit.SECONDS).build()

    private suspend fun request(path: String, query: Map<String, Any?> = emptyMap()): Request.Builder {
        val base = prefs.serverUrl()
        val url = (base + path).toHttpUrl().newBuilder().apply {
            query.forEach { (k, v) -> if (v != null) addQueryParameter(k, v.toString()) }
        }.build()
        return Request.Builder().url(url).apply {
            prefs.token()?.let { header("Authorization", "Bearer $it") }
            header("X-App-Version", BuildConfig.VERSION_NAME)
        }
    }

    private suspend fun execute(builder: Request.Builder, client: OkHttpClient = http): JsonElement =
        withContext(Dispatchers.IO) {
            val call: Call = client.newCall(builder.build())
            val response: Response = try {
                call.execute()
            } catch (e: IOException) {
                throw ApiException(0, "offline", "Нет связи с сервером")
            }
            response.use { parse(it) }
        }

    private fun parse(response: Response): JsonElement {
        val text = response.body.string()
        if (!response.isSuccessful) {
            val err = runCatching { PlainJson.parseToJsonElement(text).jsonObject["error"]?.jsonObject }.getOrNull()
            val code = err?.get("code")?.jsonPrimitive?.contentOrNull ?: "http_${response.code}"
            val msg = err?.get("message")?.jsonPrimitive?.contentOrNull ?: "Ошибка сервера (${response.code})"
            AppLog.w("api", "${response.request.method} ${response.request.url.encodedPath} → ${response.code} $code")
            throw ApiException(response.code, code, msg)
        }
        return if (text.isBlank()) JsonNull else PlainJson.parseToJsonElement(text)
    }

    suspend fun get(path: String, query: Map<String, Any?> = emptyMap()): JsonElement =
        execute(request(path, query).get())

    suspend fun post(path: String, body: JsonElement? = null, query: Map<String, Any?> = emptyMap()): JsonElement =
        execute(request(path, query).post((body ?: JsonObject(emptyMap())).toString().toRequestBody(json)))

    suspend fun put(path: String, body: JsonElement): JsonElement =
        execute(request(path).put(body.toString().toRequestBody(json)))

    suspend fun patch(path: String, body: JsonElement): JsonElement =
        execute(request(path).patch(body.toString().toRequestBody(json)))

    suspend fun delete(path: String, query: Map<String, Any?> = emptyMap()): JsonElement =
        execute(request(path, query).delete())

    suspend fun upload(path: String, fileName: String, mime: String, bytes: RequestBody,
                       fields: Map<String, String> = emptyMap()): JsonElement {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM).apply {
            fields.forEach { (k, v) -> addFormDataPart(k, v) }
            addFormDataPart("file", fileName, bytes)
        }.build()
        return execute(request(path).post(body), streamHttp)
    }

    /** POST that answers with Server-Sent Events (chat). */
    fun streamChat(conversationId: String, body: JsonObject): Flow<ChatEvent> = callbackFlow {
        val req = request("/api/v1/chat/conversations/$conversationId/messages")
            .header("Accept", "text/event-stream")
            .post(body.toString().toRequestBody(json))
            .build()
        val call = streamHttp.newCall(req)
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    val err = runCatching { parse(response) }.exceptionOrNull() as? ApiException
                    trySend(ChatEvent.Error(err?.code ?: "http_${response.code}", err?.message ?: "Ошибка сервера"))
                    return@use
                }
                val source = response.body.source()
                while (!source.exhausted()) {
                    val line = source.readUtf8Line() ?: break
                    if (!line.startsWith("data: ")) continue
                    val obj = runCatching { PlainJson.parseToJsonElement(line.substring(6)).jsonObject }.getOrNull() ?: continue
                    val str = { k: String -> obj[k]?.jsonPrimitive?.contentOrNull }
                    val event = when (str("type")) {
                        "start" -> ChatEvent.Start(obj["user_message"] as? JsonObject)
                        "delta" -> ChatEvent.Delta(str("text") ?: "")
                        "tool" -> ChatEvent.Tool(str("name") ?: "", str("status") ?: "", str("title"))
                        "done" -> ChatEvent.Done(obj["message"] as? JsonObject, str("cost_usd")?.toDoubleOrNull())
                        "error" -> ChatEvent.Error(str("code") ?: "error", str("message") ?: "Ошибка")
                        else -> null
                    }
                    if (event != null) trySend(event)
                }
            }
        } catch (e: IOException) {
            if (!call.isCanceled()) trySend(ChatEvent.Error("offline", "Связь прервалась. Ответ сохранится и придёт при синхронизации."))
        }
        close()
        awaitClose { call.cancel() }
    }.flowOn(Dispatchers.IO)

    suspend fun downloadTo(url: String, target: java.io.File, onProgress: (Long, Long) -> Unit = { _, _ -> }) =
        withContext(Dispatchers.IO) {
            val req = Request.Builder().url(url).header("Accept", "application/octet-stream").build()
            streamHttp.newCall(req).execute().use { response ->
                if (!response.isSuccessful) throw ApiException(response.code, "download", "Не удалось скачать файл")
                val total = response.body.contentLength()
                target.parentFile?.mkdirs()
                target.outputStream().use { out ->
                    val input = response.body.byteStream()
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        onProgress(done, total)
                    }
                }
            }
        }
}
