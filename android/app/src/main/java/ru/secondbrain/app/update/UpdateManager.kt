package ru.secondbrain.app.update

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.Request
import ru.secondbrain.app.BuildConfig
import ru.secondbrain.app.SecondBrainApp
import ru.secondbrain.app.core.AppLog
import ru.secondbrain.app.core.Prefs
import ru.secondbrain.app.data.api.ApiClient
import ru.secondbrain.app.data.api.PlainJson
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

data class Release(val version: String, val notes: String, val apkUrl: String, val apkSize: Long, val shaUrl: String?)

/**
 * In-app updates from GitHub Releases: check → download APK → verify SHA-256 → hand to the system
 * installer. Android always asks the owner to confirm installation. The app checks when it opens and in
 * the background; on Wi-Fi the new version is fetched ahead, so "Установить" starts the installer at once.
 */
class UpdateManager(private val context: Context, private val api: ApiClient, private val prefs: Prefs) {

    private val dir get() = File(context.cacheDir, "updates")

    /** A release newer than the installed version, once a check has seen one (drives the home banner). */
    private val _available = MutableStateFlow<Release?>(null)
    val available: StateFlow<Release?> = _available

    @Volatile private var checkedAt = 0L

    /** Called when the app opens: at most one check per half hour per process. */
    suspend fun checkIfStale() {
        val now = System.currentTimeMillis()
        if (now - checkedAt < 30 * 60_000L) return
        checkedAt = now
        runCatching { latest() }
    }

    suspend fun latest(): Release? = fetchLatest()?.also { r -> _available.value = r.takeIf { isNewer(it.version) } }

    private suspend fun fetchLatest(): Release? = withContext(Dispatchers.IO) {
        val direct = runCatching {
            val req = Request.Builder().url("https://api.github.com/repos/${BuildConfig.GITHUB_REPO}/releases/latest")
                .header("Accept", "application/vnd.github+json").build()
            api.http.newCall(req).execute().use { r ->
                if (!r.isSuccessful) null else PlainJson.parseToJsonElement(r.body.string()).jsonObject
            }
        }.getOrNull()
        val rel: JsonObject = direct?.let { normalizeGithub(it) }
            ?: runCatching { api.get("/api/v1/system/updates/latest").jsonObject }.getOrNull()
            ?: return@withContext null
        if (rel["available"]?.jsonPrimitive?.contentOrNull == "false") return@withContext null
        val tag = rel["tag"]?.jsonPrimitive?.contentOrNull ?: return@withContext null
        val assets = rel["assets"]?.jsonArray?.map { it.jsonObject } ?: emptyList()
        val apk = assets.firstOrNull { it.name().endsWith(".apk") } ?: return@withContext null
        val sha = assets.firstOrNull { it.name() == apk.name() + ".sha256" || it.name().endsWith(".sha256") }
        prefs.set(Prefs.Keys.lastUpdateCheck, System.currentTimeMillis())
        Release(tag.removePrefix("v"), rel["body"]?.jsonPrimitive?.contentOrNull ?: "", apk.url(),
            apk["size"]?.jsonPrimitive?.longOrNull ?: 0, sha?.url())
    }

    private fun JsonObject.name() = this["name"]?.jsonPrimitive?.contentOrNull ?: ""
    private fun JsonObject.url() = (this["url"] ?: this["browser_download_url"])?.jsonPrimitive?.contentOrNull ?: ""

    private fun normalizeGithub(o: JsonObject): JsonObject = kotlinx.serialization.json.buildJsonObject {
        put("tag", o["tag_name"] ?: return@buildJsonObject)
        o["body"]?.let { put("body", it) }
        put("assets", kotlinx.serialization.json.JsonArray(o["assets"]?.jsonArray?.map { a ->
            val ao = a.jsonObject
            kotlinx.serialization.json.buildJsonObject {
                ao["name"]?.let { put("name", it) }
                ao["browser_download_url"]?.let { put("url", it) }
                ao["size"]?.let { put("size", it) }
            }
        } ?: emptyList()))
    }

    fun isNewer(version: String): Boolean = compare(version, BuildConfig.VERSION_NAME.removeSuffix("-debug")) > 0

    private fun compare(a: String, b: String): Int {
        val pa = a.split(".").map { it.toIntOrNull() ?: 0 }
        val pb = b.split(".").map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val d = (pa.getOrElse(i) { 0 }).compareTo(pb.getOrElse(i) { 0 })
            if (d != 0) return d
        }
        return 0
    }

    private fun apkFile(release: Release) = File(dir, "second-brain-${release.version}.apk")
    private fun okFile(release: Release) = File(dir, apkFile(release).name + ".ok")

    /** The APK for [release] if it is already downloaded and verified. */
    fun ready(release: Release): File? = apkFile(release).takeIf {
        it.exists() && okFile(release).exists() && (release.apkSize <= 0 || it.length() == release.apkSize)
    }

    /** Background download (Wi-Fi only by the caller); null on any failure — the owner can still tap to download. */
    suspend fun prefetch(release: Release): File? = runCatching { download(release) {} }
        .onFailure { AppLog.w("update", "prefetch failed: ${it.message}") }.getOrNull()

    suspend fun download(release: Release, onProgress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        ready(release)?.let { onProgress(1f); return@withContext it }
        dir.mkdirs()
        dir.listFiles()?.forEach { it.delete() }  // older downloads in the app's own cache
        val file = apkFile(release)
        api.downloadTo(release.apkUrl, file) { done, total -> if (total > 0) onProgress(done.toFloat() / total) }
        release.shaUrl?.let { url ->
            val shaFile = File(dir, "apk.sha256")
            api.downloadTo(url, shaFile)
            val expected = shaFile.readText().trim().split(Regex("\\s+")).first().lowercase()
            val actual = sha256(file)
            if (expected != actual) {
                file.delete()
                throw SecurityException("Контрольная сумма не совпала — файл повреждён или подменён")
            }
        }
        okFile(release).writeText(sha256(file))
        AppLog.i("update", "downloaded ${release.version}")
        file
    }

    fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

    fun openInstallPermissionSettings() {
        context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${context.packageName}".toUri())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun install(apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", apk)
        context.startActivity(Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        fun schedule(context: Context) {
            val req = PeriodicWorkRequestBuilder<UpdateCheckWorker>(6, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
            val wm = WorkManager.getInstance(context)
            wm.cancelUniqueWork("update-check")  // 1.0.0–1.0.1 checked every 12 h under this name
            wm.enqueueUniquePeriodicWork("update-check-6h", ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }
}

class UpdateCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val c = (applicationContext as SecondBrainApp).container
        if (!c.prefs.isPaired()) return Result.success()  // the release feed is served by our server and needs a token
        val rel = runCatching { c.updates.latest() }.getOrNull() ?: return Result.success()
        if (!c.updates.isNewer(rel.version)) return Result.success()
        // 27 MB: fetch ahead only on an unmetered network; on mobile data the owner decides.
        val metered = applicationContext.getSystemService(ConnectivityManager::class.java).isActiveNetworkMetered
        val apk = c.updates.ready(rel) ?: if (!metered) c.updates.prefetch(rel) else null
        val state = "${rel.version}:${if (apk != null) "ready" else "available"}"
        if (c.prefs.get(Prefs.Keys.updateNotified) == state) return Result.success()  // one notice per version/state
        c.prefs.set(Prefs.Keys.updateNotified, state)
        if (apk != null) c.notifier.simple(7001, "Обновление ${rel.version} готово",
            "Нажмите, чтобы установить: файл уже скачан и проверен.", route = "updates")
        else c.notifier.simple(7001, "Доступно обновление ${rel.version}", "Нажмите, чтобы скачать и установить.",
            route = "updates")
        return Result.success()
    }
}
