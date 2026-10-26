package ru.secondbrain.app.ui.onboarding

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import ru.secondbrain.app.BuildConfig
import ru.secondbrain.app.core.Prefs
import ru.secondbrain.app.core.nowIso
import ru.secondbrain.app.core.parseRubToMinor
import ru.secondbrain.app.data.api.ApiException
import ru.secondbrain.app.data.model.Account
import ru.secondbrain.app.data.model.E
import ru.secondbrain.app.data.repo.jsonOf
import ru.secondbrain.app.ui.components.AppCard
import ru.secondbrain.app.ui.components.Field
import ru.secondbrain.app.ui.components.Gap
import ru.secondbrain.app.ui.components.LocalContainer
import ru.secondbrain.app.ui.components.Secondary
import ru.secondbrain.app.ui.components.SwitchRow
import ru.secondbrain.app.ui.theme.Tone

@Composable
fun OnboardingScreen(startAtSetup: Boolean, onDone: () -> Unit) {
    var step by remember { mutableStateOf(if (startAtSetup) 2 else 0) }
    Column(Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        when (step) {
            0 -> Welcome { step = 1 }
            1 -> Pairing { step = 2 }
            2 -> Balances { step = 3 }
            else -> Permissions(onDone)
        }
    }
}

@Composable
private fun Welcome(next: () -> Unit) {
    Gap(40)
    Text("Второй мозг", style = MaterialTheme.typography.displayMedium)
    Text("Личный агент, который помнит, считает деньги, ведёт тренировки и пишет первым, когда это важно.",
        style = MaterialTheme.typography.bodyLarge, color = Tone.current.textSecondary)
    Gap(24)
    AppCard(tint = Tone.current.memory) {
        Text("Что понадобится", style = MaterialTheme.typography.titleMedium)
        Gap(6)
        Secondary("1. Одноразовый код подключения с вашего сервера.")
        Secondary("2. Текущие остатки на счетах — один раз, дальше всё считается само.")
        Secondary("3. Разрешения: уведомления, доступ к уведомлениям банков, отпечаток.")
    }
    Gap(12)
    Button(onClick = next, modifier = Modifier.fillMaxWidth()) { Text("Начать") }
}

@Composable
private fun Pairing(next: () -> Unit) {
    val c = LocalContainer.current
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf(BuildConfig.DEFAULT_SERVER_URL) }
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    Text("Подключение к серверу", style = MaterialTheme.typography.headlineLarge)
    Secondary("Код одноразовый и действует ограниченное время. Новый можно получить на сервере или с уже подключённого телефона.")
    Field(url, { url = it.trim() }, "Адрес сервера")
    Field(code, { code = it.uppercase() }, "Код подключения", placeholder = "XXXX-XXXX")
    if (error != null) Text(error!!, color = Tone.current.negative)
    Button(enabled = !busy && code.length >= 8, modifier = Modifier.fillMaxWidth(), onClick = {
        scope.launch {
            busy = true
            error = null
            try {
                c.prefs.set(Prefs.Keys.serverUrl, url.trimEnd('/'))
                val res = c.api.post("/api/v1/auth/pair", jsonOf("code" to code, "device_name" to Build.MODEL,
                    "model" to "${Build.MANUFACTURER} ${Build.MODEL}", "app_version" to BuildConfig.VERSION_NAME)).jsonObject
                c.prefs.saveToken(res["access_token"]!!.jsonPrimitive.content, res["device_id"]!!.jsonPrimitive.content)
                (res["server"]?.jsonObject?.get("firebase") as? JsonObject)?.let {
                    c.prefs.set(Prefs.Keys.firebaseOptions, it.toString())
                }
                // The code is spent at this point: a failed first sync must not strand the user on this screen.
                runCatching { c.sync.syncNow(full = true) }
                c.refreshLocalSettings()
                c.initPush()
                next()
            } catch (e: ApiException) {
                error = e.message
            } catch (e: Exception) {
                error = e.message ?: "Не удалось подключиться"
            }
            busy = false
        }
    }) { if (busy) CircularProgressIndicator(Modifier.padding(2.dp)) else Text("Подключить") }
}

@Composable
private fun Balances(next: () -> Unit) {
    val c = LocalContainer.current
    val scope = rememberCoroutineScope()
    var accounts by remember { mutableStateOf<List<Account>>(emptyList()) }
    val values = remember { mutableStateMapOf<String, String>() }
    LaunchedEffect(Unit) {
        if (c.store.all(E.ACCOUNTS, Account.serializer()).isEmpty()) runCatching { c.sync.syncNow(full = true) }
        accounts = c.store.all(E.ACCOUNTS, Account.serializer()).filter { !it.archived }.sortedBy { it.sort }
    }
    Text("Сколько сейчас денег", style = MaterialTheme.typography.headlineLarge)
    Secondary("Введите текущие остатки — от них агент считает свободные деньги. Потом остатки уточняются по выпискам и уведомлениям банков.")
    if (accounts.isEmpty()) Secondary("Счета появятся после синхронизации. Их можно добавить позже в «Финансы → Счета».")
    accounts.forEach { a ->
        Field(values[a.id] ?: "", { values[a.id] = it }, "${a.name}, ₽", number = true)
    }
    Button(modifier = Modifier.fillMaxWidth(), onClick = {
        scope.launch {
            values.forEach { (id, text) ->
                val minor = parseRubToMinor(text) ?: return@forEach
                c.store.save(E.ACCOUNTS, jsonOf("balance_minor" to minor, "balance_at" to nowIso(),
                    "balance_source" to "manual"), id)
            }
            c.sync.syncNow()
            next()
        }
    }) { Text("Дальше") }
    TextButton(onClick = next) { Text("Пропустить") }
}

@Composable
private fun Permissions(onDone: () -> Unit) {
    val c = LocalContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var notifGranted by remember { mutableStateOf(NotificationManagerCompat.from(context).areNotificationsEnabled()) }
    var listener by remember { mutableStateOf(NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)) }
    var biometric by remember { mutableStateOf(true) }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { notifGranted = it }

    Text("Разрешения", style = MaterialTheme.typography.headlineLarge)
    AppCard {
        Text("Уведомления", style = MaterialTheme.typography.titleMedium)
        Secondary("Чтобы агент мог писать первым, напоминать о платежах и тренировках.")
        if (!notifGranted && Build.VERSION.SDK_INT >= 33)
            OutlinedButton(onClick = { notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }) { Text("Разрешить") }
        else Text("Включены", color = Tone.current.positive)
    }
    AppCard {
        Text("Доступ к уведомлениям банков", style = MaterialTheme.typography.titleMedium)
        Secondary("Приложение читает только уведомления Mir Pay, Сбера и Т-Банка (список можно изменить) и учитывает покупки автоматически. Остальные уведомления не читаются и не сохраняются.")
        if (!listener) OutlinedButton(onClick = {
            context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }) { Text("Открыть настройки") }
        else Text("Включено", color = Tone.current.positive)
        TextButton(onClick = {
            listener = NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
        }) { Text("Проверить") }
    }
    AppCard {
        SwitchRow("Вход по отпечатку", "Блокировка при запуске и после 5 минут в фоне. Если отпечаток недоступен — PIN телефона.",
            biometric) { biometric = it }
    }
    Secondary("Health Connect (часы), статистику приложений и прочее можно включить позже в «Настройки → Интеграции».")
    Button(modifier = Modifier.fillMaxWidth(), onClick = {
        scope.launch {
            c.prefs.set(Prefs.Keys.biometricEnabled, biometric)
            c.prefs.set(Prefs.Keys.lockTimeoutMin, 5)
            c.prefs.set(Prefs.Keys.onboardingDone, true)
            onDone()
        }
    }) { Text("Готово") }
}
