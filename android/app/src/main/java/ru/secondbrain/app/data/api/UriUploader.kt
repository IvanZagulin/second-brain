package ru.secondbrain.app.data.api

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.serialization.json.JsonElement
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okio.BufferedSink
import okio.source
import java.io.File

/**
 * Multipart uploads of content URIs (share sheet, document picker, camera) and local files. The body streams
 * straight from [ContentResolver.openInputStream] — nothing is read into memory, so a gigabyte archive costs
 * the same as a photo. Names come from OpenableColumns.DISPLAY_NAME, which is what the picker shows the user.
 */
object UriUploader {
    private const val OCTET = "application/octet-stream"

    fun displayName(resolver: ContentResolver, uri: Uri, fallback: String = "file"): String =
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cur ->
            val idx = cur.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (cur.moveToFirst() && idx >= 0) cur.getString(idx) else null
        }?.takeIf { it.isNotBlank() } ?: fallback

    fun mimeType(resolver: ContentResolver, uri: Uri): String = resolver.getType(uri) ?: OCTET

    /** Streaming body; the length is reported when the provider knows it, otherwise the request is chunked. */
    fun body(resolver: ContentResolver, uri: Uri, mime: String): RequestBody {
        val size = resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cur ->
            val idx = cur.getColumnIndex(OpenableColumns.SIZE)
            if (cur.moveToFirst() && idx >= 0 && !cur.isNull(idx)) cur.getLong(idx) else null
        } ?: -1L
        return UriBody(resolver, uri, mime.toMediaTypeOrNull(), size)
    }

    /** Uploads [uri] as the `file` part of [path]. [name] and [mime] default to what the provider reports. */
    suspend fun upload(api: ApiClient, resolver: ContentResolver, path: String, uri: Uri,
                       fields: Map<String, String> = emptyMap(), name: String? = null, mime: String? = null): JsonElement {
        val type = mime ?: mimeType(resolver, uri)
        return api.upload(path, name ?: displayName(resolver, uri), type, body(resolver, uri, type), fields)
    }

    /** Same for a file the app wrote itself (camera captures); OkHttp streams it from disk. */
    suspend fun uploadFile(api: ApiClient, path: String, file: File, mime: String,
                           fields: Map<String, String> = emptyMap()): JsonElement =
        api.upload(path, file.name, mime, file.asRequestBody(mime.toMediaTypeOrNull()), fields)

    private class UriBody(private val resolver: ContentResolver, private val uri: Uri, private val type: MediaType?,
                          private val length: Long) : RequestBody() {
        override fun contentType() = type
        override fun contentLength() = length
        override fun writeTo(sink: BufferedSink) {
            (resolver.openInputStream(uri) ?: throw java.io.FileNotFoundException(uri.toString())).source().use { sink.writeAll(it) }
        }
    }
}
