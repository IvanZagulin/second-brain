package ru.secondbrain.app.health

import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.request.AggregateGroupByPeriodRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import ru.secondbrain.app.SecondBrainApp
import ru.secondbrain.app.core.AppLog
import ru.secondbrain.app.core.Prefs
import ru.secondbrain.app.data.repo.jsonOf
import ru.secondbrain.app.ui.theme.SecondBrainTheme
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.Period
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/**
 * Health Connect → server. Galaxy Watch data reaches Health Connect through Samsung Health
 * (Samsung Health → Settings → Health Connect); what is available depends on the watch, app
 * versions and granted permissions. Also uploads per-app screen time when that permission is on.
 */
object HealthSync {
    val PERMISSIONS = setOf(
        HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.getReadPermission(SleepSessionRecord::class),
        HealthPermission.getReadPermission(HeartRateRecord::class),
        HealthPermission.getReadPermission(RestingHeartRateRecord::class),
        HealthPermission.getReadPermission(ExerciseSessionRecord::class),
        HealthPermission.getReadPermission(WeightRecord::class),
        HealthPermission.getReadPermission(ActiveCaloriesBurnedRecord::class),
    )

    fun available(context: Context) = HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE

    suspend fun granted(context: Context): Set<String> =
        if (!available(context)) emptySet()
        else HealthConnectClient.getOrCreate(context).permissionController.getGrantedPermissions()

    fun schedule(context: Context) {
        val req = PeriodicWorkRequestBuilder<HealthSyncWorker>(6, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork("health-sync", ExistingPeriodicWorkPolicy.KEEP, req)
    }

    suspend fun syncHealth(context: Context, days: Long = 7): Int {
        if (!available(context)) return 0
        val client = HealthConnectClient.getOrCreate(context)
        val granted = client.permissionController.getGrantedPermissions()
        if (granted.isEmpty()) return 0
        val zone = ZoneId.systemDefault()
        val startDate = LocalDate.now().minusDays(days)
        val startLdt = startDate.atStartOfDay()
        val endLdt = LocalDate.now().plusDays(1).atStartOfDay()
        val items = mutableListOf<JsonObject>()
        fun dayStart(ld: java.time.LocalDateTime) = ld.atZone(zone).toInstant().toString()

        suspend fun daily(metric: androidx.health.connect.client.aggregate.AggregateMetric<*>, kind: String, unit: String,
                          value: (Any) -> Double) {
            val res = client.aggregateGroupByPeriod(AggregateGroupByPeriodRequest(setOf(metric),
                TimeRangeFilter.between(startLdt, endLdt), Period.ofDays(1)))
            for (g in res) {
                val v = g.result[metric] ?: continue
                items += jsonOf("kind" to kind, "start_at" to dayStart(g.startTime), "end_at" to dayStart(g.endTime),
                    "value" to value(v), "unit" to unit, "source" to "health_connect")
            }
        }
        if (HealthPermission.getReadPermission(StepsRecord::class) in granted)
            daily(StepsRecord.COUNT_TOTAL, "steps", "count") { (it as Long).toDouble() }
        if (HealthPermission.getReadPermission(HeartRateRecord::class) in granted)
            daily(HeartRateRecord.BPM_AVG, "heart_rate", "bpm") { (it as Long).toDouble() }
        if (HealthPermission.getReadPermission(RestingHeartRateRecord::class) in granted)
            daily(RestingHeartRateRecord.BPM_AVG, "resting_hr", "bpm") { (it as Long).toDouble() }
        if (HealthPermission.getReadPermission(ActiveCaloriesBurnedRecord::class) in granted)
            daily(ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL, "active_kcal", "kcal") {
                (it as androidx.health.connect.client.units.Energy).inKilocalories
            }

        val range = TimeRangeFilter.between(startDate.atStartOfDay(zone).toInstant(), Instant.now())
        if (HealthPermission.getReadPermission(SleepSessionRecord::class) in granted) {
            client.readRecords(ReadRecordsRequest(SleepSessionRecord::class, range)).records.forEach { r ->
                items += jsonOf("kind" to "sleep", "start_at" to r.startTime.toString(), "end_at" to r.endTime.toString(),
                    "value" to Duration.between(r.startTime, r.endTime).toMinutes().toDouble(), "unit" to "min",
                    "source" to "health_connect", "data" to jsonOf("stages" to r.stages.size, "title" to r.title))
            }
        }
        if (HealthPermission.getReadPermission(ExerciseSessionRecord::class) in granted) {
            client.readRecords(ReadRecordsRequest(ExerciseSessionRecord::class, range)).records.forEach { r ->
                items += jsonOf("kind" to "exercise", "start_at" to r.startTime.toString(), "end_at" to r.endTime.toString(),
                    "value" to Duration.between(r.startTime, r.endTime).toMinutes().toDouble(), "unit" to "min",
                    "source" to "health_connect", "data" to jsonOf("type" to r.exerciseType, "title" to r.title))
            }
        }
        if (HealthPermission.getReadPermission(WeightRecord::class) in granted) {
            client.readRecords(ReadRecordsRequest(WeightRecord::class, range)).records.forEach { r ->
                items += jsonOf("kind" to "weight", "start_at" to r.time.toString(), "value" to r.weight.inKilograms,
                    "unit" to "kg", "source" to "health_connect")
            }
        }
        if (items.isEmpty()) return 0
        val api = (context.applicationContext as SecondBrainApp).container.api
        items.chunked(1000).forEach { api.post("/api/v1/health/records", jsonOf("items" to JsonArray(it))) }
        return items.size
    }

    fun usageAllowed(context: Context): Boolean {
        val ops = context.getSystemService(AppOpsManager::class.java)
        return ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName) ==
            AppOpsManager.MODE_ALLOWED
    }

    fun openUsageSettings(context: Context) =
        context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

    /** Screen time per app for the last 3 days (top apps only) — input for habit and focus analysis. */
    suspend fun syncUsage(context: Context): Int {
        if (!usageAllowed(context)) return 0
        val usm = context.getSystemService(UsageStatsManager::class.java)
        val pm = context.packageManager
        val zone = ZoneId.systemDefault()
        val items = mutableListOf<JsonObject>()
        for (back in 0L..2L) {
            val day = LocalDate.now().minusDays(back)
            val start = day.atStartOfDay(zone).toInstant()
            val end = day.plusDays(1).atStartOfDay(zone).toInstant()
            val stats = usm.queryAndAggregateUsageStats(start.toEpochMilli(), end.toEpochMilli())
            stats.values.filter { it.totalTimeInForeground > 60_000 && it.packageName != context.packageName }
                .sortedByDescending { it.totalTimeInForeground }.take(15).forEach { s ->
                    @Suppress("DEPRECATION")
                    val label = runCatching {
                        pm.getApplicationLabel(pm.getApplicationInfo(s.packageName, 0)).toString()
                    }.getOrDefault(s.packageName)
                    items += jsonOf("kind" to "app_usage", "start_at" to start.toString(), "end_at" to end.toString(),
                        "value" to s.totalTimeInForeground / 60000.0, "unit" to "min", "source" to s.packageName,
                        "data" to jsonOf("label" to label))
                }
        }
        if (items.isEmpty()) return 0
        val api = (context.applicationContext as SecondBrainApp).container.api
        api.post("/api/v1/health/records", jsonOf("items" to JsonArray(items)))
        return items.size
    }
}

class HealthSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val c = (applicationContext as SecondBrainApp).container
        if (!c.prefs.isPaired()) return Result.success()
        try {
            if (c.prefs.get(Prefs.Keys.healthEnabled) == true) {
                val n = HealthSync.syncHealth(applicationContext)
                c.prefs.set(Prefs.Keys.lastHealthSync, System.currentTimeMillis())
                AppLog.i("health", "uploaded $n records")
            }
            if (c.prefs.get(Prefs.Keys.usageEnabled) == true) {
                HealthSync.syncUsage(applicationContext)
                c.prefs.set(Prefs.Keys.lastUsageSync, System.currentTimeMillis())
            }
        } catch (e: Exception) {
            AppLog.w("health", "sync failed: ${e.message}")
            return if (runAttemptCount < 2) Result.retry() else Result.success()
        }
        return Result.success()
    }
}

/** Shown by Health Connect when the owner asks why the app reads health data. */
class HealthPermissionsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SecondBrainTheme {
                Column(Modifier.fillMaxSize().padding(24.dp)) {
                    Text("Зачем «Второму мозгу» данные здоровья", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        "Приложение читает шаги, сон, пульс, тренировки, вес и активные калории из Health Connect, " +
                            "чтобы агент-тренер видел восстановление и нагрузку и связывал их с тренировками, " +
                            "настроением и тратами. Данные уходят только на ваш собственный сервер и никуда больше. " +
                            "Доступ можно отозвать в настройках Health Connect.",
                        modifier = Modifier.padding(top = 16.dp), style = MaterialTheme.typography.bodyLarge)
                    Button(onClick = { finish() }, modifier = Modifier.padding(top = 24.dp)) { Text("Понятно") }
                }
            }
        }
    }
}
