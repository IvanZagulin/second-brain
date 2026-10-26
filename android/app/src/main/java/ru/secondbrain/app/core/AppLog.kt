package ru.secondbrain.app.core

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Ring-buffer log on disk plus a crash handler, so problems on the phone can be investigated
 * without a cable: Settings → Diagnostics → "Отправить логи" uploads them to the server.
 * Never logs message texts, bank notification texts or tokens.
 */
object AppLog {
    private const val MAX_BYTES = 400_000
    private lateinit var file: File
    private lateinit var crashDir: File
    private val fmt = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss.SSS")
    private val lock = Any()

    fun init(context: Context) {
        file = File(context.filesDir, "app.log")
        crashDir = File(context.filesDir, "crashes").apply { mkdirs() }
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                File(crashDir, "crash-${System.currentTimeMillis()}.txt").writeText(
                    "${Build.MANUFACTURER} ${Build.MODEL} Android ${Build.VERSION.RELEASE}\nthread=${thread.name}\n" +
                        stackTrace(error)
                )
            }
            previous?.uncaughtException(thread, error)
        }
        i("app", "start ${Build.MODEL} sdk ${Build.VERSION.SDK_INT}")
    }

    fun i(tag: String, msg: String) = write("I", tag, msg, null)
    fun w(tag: String, msg: String, e: Throwable? = null) = write("W", tag, msg, e)
    fun e(tag: String, msg: String, e: Throwable? = null) = write("E", tag, msg, e)

    private fun write(level: String, tag: String, msg: String, e: Throwable?) {
        when (level) {
            "E" -> Log.e("SB/$tag", msg, e)
            "W" -> Log.w("SB/$tag", msg, e)
            else -> Log.i("SB/$tag", msg)
        }
        if (!::file.isInitialized) return
        val line = "${LocalDateTime.now().format(fmt)} $level/$tag: $msg" + (e?.let { "\n" + stackTrace(it) } ?: "") + "\n"
        synchronized(lock) {
            runCatching {
                if (file.exists() && file.length() > MAX_BYTES) {
                    val tail = file.readText().takeLast(MAX_BYTES / 2)
                    file.writeText(tail)
                }
                file.appendText(line)
            }
        }
    }

    fun read(): String = synchronized(lock) { if (::file.isInitialized && file.exists()) file.readText() else "" }

    fun crashes(): List<File> = if (::crashDir.isInitialized) crashDir.listFiles()?.sortedBy { it.name } ?: emptyList() else emptyList()

    fun clearCrashes() = crashes().forEach { it.delete() }

    private fun stackTrace(e: Throwable): String = StringWriter().also { e.printStackTrace(PrintWriter(it)) }.toString()
}
