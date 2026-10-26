package ru.secondbrain.app

import android.app.Application
import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import ru.secondbrain.app.core.AppLog
import ru.secondbrain.app.core.Prefs
import ru.secondbrain.app.data.api.ApiClient
import ru.secondbrain.app.data.api.PlainJson
import ru.secondbrain.app.data.db.AppDatabase
import ru.secondbrain.app.data.repo.ChatRepository
import ru.secondbrain.app.data.repo.RecordStore
import ru.secondbrain.app.data.repo.jsonOf
import ru.secondbrain.app.data.repo.str
import ru.secondbrain.app.data.sync.SyncManager
import ru.secondbrain.app.health.HealthSync
import ru.secondbrain.app.notifications.AgentNotifier
import ru.secondbrain.app.update.UpdateManager

class SecondBrainApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        AppLog.init(this)
        container = AppContainer(this)
        container.notifier.createChannels()
        SyncManager.schedulePeriodic(this)
        HealthSync.schedule(this)
        UpdateManager.schedule(this)
        container.scope.launch { container.initPush() }
    }
}

/** Manual dependency container (one user, a handful of singletons — no DI framework needed). */
class AppContainer(val context: Context) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val prefs = Prefs(context)
    val db: AppDatabase by lazy { AppDatabase.create(context, prefs.databaseKey()) }
    val api = ApiClient(prefs)
    val notifier: AgentNotifier by lazy { AgentNotifier(context, db) }
    val store: RecordStore by lazy { RecordStore(db) { sync.requestSync(4) } }
    val sync: SyncManager by lazy { SyncManager(context, api, db, prefs, store, notifier) }
    val chat: ChatRepository by lazy { ChatRepository(api, db, store, sync) }
    val updates: UpdateManager by lazy { UpdateManager(context, api, prefs) }

    @Volatile private var packagesCache: Set<String>? = null

    suspend fun allowedPackages(): Set<String> {
        packagesCache?.let { return it }
        val stored = prefs.get(Prefs.Keys.notificationPackages)
        val set = stored?.split(",")?.filter { it.isNotBlank() }?.toSet() ?: DEFAULT_BANK_PACKAGES
        packagesCache = set
        return set
    }

    suspend fun setAllowedPackages(packages: Collection<String>) {
        packagesCache = packages.toSet()
        prefs.set(Prefs.Keys.notificationPackages, packages.joinToString(","))
    }

    /** Pull server settings that the phone needs locally (notification allow-list). */
    suspend fun refreshLocalSettings() {
        runCatching {
            val s = api.get("/api/v1/settings").jsonObject
            val pk = s["finance"]?.jsonObject?.get("notification_packages")?.jsonArray?.map { it.jsonPrimitive.content }
            if (!pk.isNullOrEmpty()) setAllowedPackages(pk)
        }
    }

    /** Firebase is initialised at runtime from options the server hands out (no google-services.json in the APK). */
    suspend fun initPush() {
        if (!prefs.isPaired()) return
        try {
            var optionsJson = prefs.get(Prefs.Keys.firebaseOptions)
            if (optionsJson == null) {
                val info = api.get("/api/v1/system/info").jsonObject
                val fb = info["firebase"] as? JsonObject ?: return
                optionsJson = fb.toString()
                prefs.set(Prefs.Keys.firebaseOptions, optionsJson)
            }
            val o = PlainJson.parseToJsonElement(optionsJson).jsonObject
            if (FirebaseApp.getApps(context).isEmpty()) {
                FirebaseApp.initializeApp(context, FirebaseOptions.Builder()
                    .setApplicationId(o.str("application_id")!!)
                    .setApiKey(o.str("api_key")!!)
                    .setProjectId(o.str("project_id")!!)
                    .setGcmSenderId(o.str("sender_id")!!)
                    .setStorageBucket(o.str("storage_bucket"))
                    .build())
            }
            val fm = FirebaseMessaging.getInstance()
            fm.isAutoInitEnabled = true
            val token = fm.token.await()
            if (token != prefs.get(Prefs.Keys.fcmToken)) {
                api.post("/api/v1/auth/push-token", jsonOf("fcm_token" to token))
                prefs.set(Prefs.Keys.fcmToken, token)
            }
            AppLog.i("push", "fcm ready")
        } catch (e: Exception) {
            AppLog.w("push", "fcm unavailable, polling only: ${e.message}")
        }
    }

    companion object {
        val DEFAULT_BANK_PACKAGES = setOf("ru.nspk.mirpay", "ru.sberbankmobile", "ru.sberbank.sberbankid",
            "com.idamob.tinkoff.android", "com.tinkoff.business")
    }
}
