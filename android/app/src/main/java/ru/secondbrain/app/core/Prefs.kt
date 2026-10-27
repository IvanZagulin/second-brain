package ru.secondbrain.app.core

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import ru.secondbrain.app.BuildConfig
import java.security.SecureRandom

private val Context.dataStore by preferencesDataStore(name = "second_brain")

/** Small local settings. Secrets (device token, DB key) are stored wrapped by [KeyStoreCipher]. */
class Prefs(private val context: Context) {
    private val store = context.dataStore

    object Keys {
        val serverUrl = stringPreferencesKey("server_url")
        val deviceId = stringPreferencesKey("device_id")
        val tokenEnc = stringPreferencesKey("token_enc")
        val dbKeyEnc = stringPreferencesKey("db_key_enc")
        val syncCursor = longPreferencesKey("sync_cursor")
        val lastSyncAt = longPreferencesKey("last_sync_at")
        val lastNotificationsAt = stringPreferencesKey("last_notifications_at")
        val biometricEnabled = booleanPreferencesKey("biometric_enabled")
        val lockTimeoutMin = intPreferencesKey("lock_timeout_min")
        val theme = stringPreferencesKey("theme")
        val homeCache = stringPreferencesKey("home_cache")
        val firebaseOptions = stringPreferencesKey("firebase_options")
        val fcmToken = stringPreferencesKey("fcm_token")
        val notificationPackages = stringPreferencesKey("notification_packages")
        val onboardingDone = booleanPreferencesKey("onboarding_done")
        val lastUpdateCheck = longPreferencesKey("last_update_check")
        val updateNotified = stringPreferencesKey("update_notified")
        val healthEnabled = booleanPreferencesKey("health_enabled")
        val usageEnabled = booleanPreferencesKey("usage_enabled")
        val lastHealthSync = longPreferencesKey("last_health_sync")
        val lastUsageSync = longPreferencesKey("last_usage_sync")
        val restSeconds = intPreferencesKey("rest_seconds")
    }

    val data: Flow<Preferences> = store.data

    @Volatile private var cachedToken: String? = null
    @Volatile private var cachedUrl: String? = null

    suspend fun <T> get(key: Preferences.Key<T>): T? = store.data.first()[key]
    fun <T> flow(key: Preferences.Key<T>): Flow<T?> = store.data.map { it[key] }
    suspend fun <T> set(key: Preferences.Key<T>, value: T?) {
        store.edit { if (value == null) it.remove(key) else it[key] = value }
        if (key == Keys.serverUrl) cachedUrl = value as String?
    }

    suspend fun serverUrl(): String =
        cachedUrl ?: (get(Keys.serverUrl) ?: BuildConfig.DEFAULT_SERVER_URL).trimEnd('/').also { cachedUrl = it }

    suspend fun token(): String? {
        cachedToken?.let { return it }
        val enc = get(Keys.tokenEnc) ?: return null
        return runCatching { KeyStoreCipher.decryptString(enc) }.getOrNull()?.also { cachedToken = it }
    }

    suspend fun saveToken(token: String?, deviceId: String?) {
        cachedToken = token
        store.edit {
            if (token == null) it.remove(Keys.tokenEnc) else it[Keys.tokenEnc] = KeyStoreCipher.encryptString(token)
            if (deviceId == null) it.remove(Keys.deviceId) else it[Keys.deviceId] = deviceId
        }
    }

    suspend fun isPaired(): Boolean = token() != null

    /** Random 32-byte SQLCipher passphrase, created on first launch and kept wrapped by the Keystore. */
    fun databaseKey(): ByteArray = runBlocking {
        val enc = get(Keys.dbKeyEnc)
        if (enc != null) return@runBlocking KeyStoreCipher.decrypt(enc)
        val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
        set(Keys.dbKeyEnc, KeyStoreCipher.encrypt(key))
        key
    }

    suspend fun resetAll() {
        cachedToken = null
        cachedUrl = null
        store.edit { prefs ->
            val dbKey = prefs[Keys.dbKeyEnc]
            prefs.clear()
            if (dbKey != null) prefs[Keys.dbKeyEnc] = dbKey
        }
    }
}
