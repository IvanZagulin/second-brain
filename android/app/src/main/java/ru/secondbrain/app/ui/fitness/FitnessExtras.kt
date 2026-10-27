package ru.secondbrain.app.ui.fitness

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material3.Button
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch
import kotlinx.serialization.json.jsonObject
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import ru.secondbrain.app.core.decimal
import ru.secondbrain.app.core.local
import ru.secondbrain.app.core.nowIso
import ru.secondbrain.app.core.parseInstant
import ru.secondbrain.app.core.relative
import ru.secondbrain.app.core.ruDate
import ru.secondbrain.app.data.model.BodyMetric
import ru.secondbrain.app.data.model.E
import ru.secondbrain.app.data.model.Meal
import ru.secondbrain.app.data.model.ProgressPhoto
import ru.secondbrain.app.data.repo.jsonOf
import ru.secondbrain.app.data.repo.str
import ru.secondbrain.app.ui.components.AppCard
import ru.secondbrain.app.ui.components.BigNumber
import ru.secondbrain.app.ui.components.ChoiceChips
import ru.secondbrain.app.ui.components.Dropdown
import ru.secondbrain.app.ui.components.EmptyState
import ru.secondbrain.app.ui.components.Field
import ru.secondbrain.app.ui.components.Gap
import ru.secondbrain.app.ui.components.Label
import ru.secondbrain.app.ui.components.LineChart
import ru.secondbrain.app.ui.components.ListRow
import ru.secondbrain.app.ui.components.Loading
import ru.secondbrain.app.ui.components.LocalContainer
import ru.secondbrain.app.data.api.ApiException
import ru.secondbrain.app.ui.components.LocalSnackbar
import ru.secondbrain.app.ui.components.LocalNavigate
import ru.secondbrain.app.ui.components.Screen
import ru.secondbrain.app.ui.components.SectionTitle
import ru.secondbrain.app.ui.components.Secondary
import ru.secondbrain.app.ui.components.Sheet
import ru.secondbrain.app.ui.components.StatTile
import ru.secondbrain.app.ui.components.rememberAction
import ru.secondbrain.app.ui.components.rememberLoad
import ru.secondbrain.app.ui.theme.Tone
import java.io.File
import java.time.LocalDate

val METRIC_KINDS = linkedMapOf("weight" to "Вес, кг", "waist" to "Талия, см", "chest" to "Грудь, см", "arm" to "Рука, см",
    "hips" to "Бёдра, см", "thigh" to "Бедро, см", "neck" to "Шея, см", "body_fat" to "Жир, %")

@Composable
fun MetricsScreen() {
    val c = LocalContainer.current
    val act = rememberAction()
    val metrics by c.store.observe(E.METRICS, BodyMetric.serializer()).collectAsState(initial = emptyList())
    var kind by remember { mutableStateOf("weight") }
    var adding by remember { mutableStateOf(false) }
    val series = metrics.filter { it.kind == kind }.sortedBy { it.measuredAt ?: "" }
    Screen("Вес и замеры", fab = { ExtendedFloatingActionButton(onClick = { adding = true }, icon = { Icon(Icons.Default.Add, null) }, text = { Text("Замер") }) }) {
        item { ChoiceChips(METRIC_KINDS.entries.map { it.key to it.value.substringBefore(",") }, kind, { kind = it }) }
        item {
            AppCard(tint = Tone.current.training) {
                Label(METRIC_KINDS[kind] ?: kind)
                BigNumber(series.lastOrNull()?.let { decimal(it.value) } ?: "—")
                if (series.size >= 2) {
                    val delta = series.last().value - series.first().value
                    Secondary("${if (delta > 0) "+" else ""}${decimal(delta)} с ${parseInstant(series.first().measuredAt)?.local()?.toLocalDate()?.let { ruDate(it) }}")
                }
                Gap(8)
                LineChart(series.map { it.value })
            }
        }
        items(series.reversed(), key = { it.id }) { m ->
            AppCard(padding = 12.dp) {
                ListRow(decimal(m.value) + " " + m.unit, relative(parseInstant(m.measuredAt)) + (m.note?.let { " · $it" } ?: ""))
                TextButton(onClick = { act(null) { c.store.delete(E.METRICS, m.id) } }) { Text("Удалить") }
            }
        }
    }
    if (adding) Sheet({ adding = false }) {
        var k by remember { mutableStateOf(kind) }
        var value by remember { mutableStateOf("") }
        var note by remember { mutableStateOf("") }
        Text("Новый замер", style = MaterialTheme.typography.headlineSmall)
        Dropdown("Что", METRIC_KINDS.entries.map { it.key to it.value }, k, { k = it })
        Field(value, { value = it }, "Значение", number = true)
        Field(note, { note = it }, "Комментарий")
        Button(onClick = {
            val v = value.replace(",", ".").toDoubleOrNull() ?: return@Button
            act("Записано") {
                c.store.save(E.METRICS, jsonOf("kind" to k, "value" to v, "unit" to if (k == "weight") "kg" else if (k == "body_fat") "%" else "cm",
                    "measured_at" to nowIso(), "note" to note.ifBlank { null }))
                adding = false
            }
        }) { Text("Сохранить") }
    }
}

@Composable
fun PhotosScreen() {
    val c = LocalContainer.current
    val nav = LocalNavigate.current
    val context = LocalContext.current
    val act = rememberAction()
    val scope = rememberCoroutineScope()
    val photos by c.store.observe(E.PHOTOS, ProgressPhoto.serializer()).collectAsState(initial = emptyList())
    var pending by remember { mutableStateOf<File?>(null) }
    var pose by remember { mutableStateOf("front") }
    val selected = remember { mutableStateListOf<String>() }
    val take = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val f = pending ?: return@rememberLauncherForActivityResult
        if (ok) act("Фото сохранено") {
            c.store.save(E.PHOTOS, jsonOf("taken_at" to nowIso(), "pose" to pose, "local_path" to f.absolutePath))
        }
    }
    val visible = photos.filter { it.localPath?.let { p -> File(p).exists() } == true }.sortedByDescending { it.takenAt ?: "" }
    Screen("Фото формы", subtitle = "хранятся только на телефоне",
        fab = {
            ExtendedFloatingActionButton(onClick = {
                val f = File(context.filesDir, "photos/form-${System.currentTimeMillis()}.jpg").apply { parentFile?.mkdirs() }
                pending = f
                take.launch(FileProvider.getUriForFile(context, "${context.packageName}.files", f))
            }, icon = { Icon(Icons.Outlined.PhotoCamera, null) }, text = { Text("Снять") })
        }) {
        item { ChoiceChips(listOf("front" to "Спереди", "side" to "Сбоку", "back" to "Сзади"), pose, { pose = it }) }
        if (selected.size == 2) item {
            val a = visible.first { it.id == selected[0] }
            val b = visible.first { it.id == selected[1] }
            AppCard(tint = Tone.current.training) {
                Text("Сравнение", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(a, b).sortedBy { it.takenAt }.forEach { p ->
                        Column(Modifier.weight(1f)) {
                            AsyncImage(model = File(p.localPath!!), contentDescription = null, contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxWidth().aspectRatio(0.75f).clip(RoundedCornerShape(16.dp)))
                            Label(parseInstant(p.takenAt)?.local()?.toLocalDate()?.let { ruDate(it) } ?: "")
                        }
                    }
                }
                OutlinedButton(onClick = {
                    scope.launch {
                        val ids = listOf(a, b).map { p ->
                            val f = File(p.localPath!!)
                            c.api.upload("/api/v1/chat/attachments", f.name, "image/jpeg", f.readBytes().toRequestBody("image/jpeg".toMediaTypeOrNull()),
                                mapOf("purpose" to "progress")).jsonObject.str("id")!!
                        }
                        val conv = c.chat.newConversation("trainer", "Сравнение формы")
                        c.chat.queueMessage(conv, "Сравни мою форму на двух фото (раньше и сейчас). Что изменилось и что подтянуть?", ids, "trainer")
                        nav("chat/$conv")
                    }
                }) { Text("Обсудить с тренером") }
                Secondary("Для разговора эти два фото загружаются на ваш сервер. Claude увидит их, только если в «Приватности» включено «Фото формы»; иначе тренер ответит без них.")
            }
        }
        if (visible.isEmpty()) item { EmptyState("Фото пока нет. Снимайте раз в 2–4 недели при одинаковом свете.") }
        items(visible, key = { it.id }) { p ->
            AppCard(padding = 10.dp, tint = if (p.id in selected) Tone.current.training else Tone.current.surface,
                onClick = { if (p.id in selected) selected.remove(p.id) else { if (selected.size == 2) selected.removeAt(0); selected.add(p.id) } }) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    AsyncImage(model = File(p.localPath!!), contentDescription = null, contentScale = ContentScale.Crop,
                        modifier = Modifier.weight(0.4f).aspectRatio(0.75f).clip(RoundedCornerShape(14.dp)))
                    Column(Modifier.weight(0.6f)) {
                        Text(parseInstant(p.takenAt)?.local()?.toLocalDate()?.let { ruDate(it, true) } ?: "", style = MaterialTheme.typography.titleMedium)
                        Secondary(mapOf("front" to "спереди", "side" to "сбоку", "back" to "сзади")[p.pose] ?: "")
                        Secondary(if (p.id in selected) "выбрано для сравнения" else "нажмите, чтобы сравнить")
                    }
                }
            }
        }
    }
}

@Composable
fun NutritionScreen() {
    val c = LocalContainer.current
    val context = LocalContext.current
    val act = rememberAction()
    val scope = rememberCoroutineScope()
    val meals by c.store.observe(E.MEALS, Meal.serializer()).collectAsState(initial = emptyList())
    val nav = LocalNavigate.current
    val snackbar = LocalSnackbar.current
    val allSettings = rememberLoad { c.api.get("/api/v1/settings").jsonObject }
    val settings = allSettings.value?.get("fitness")?.jsonObject
    // Meal photos are shown nowhere in the app: without image analysis they would only sit on the server.
    val imagesAllowed = allSettings.value?.get("privacy")?.jsonObject?.str("allow_image_analysis") == "true"
    var mode by remember(settings) { mutableStateOf(settings?.str("nutrition_mode") ?: "off") }
    var description by remember { mutableStateOf("") }
    var photo by remember { mutableStateOf<File?>(null) }
    var busy by remember { mutableStateOf(false) }
    val take = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok -> if (!ok) photo = null }
    val today = meals.filter { parseInstant(it.eatenAt)?.local()?.toLocalDate() == LocalDate.now() }
    Screen("Питание") {
        item {
            AppCard {
                Label("Режим")
                ChoiceChips(listOf("off" to "Без контроля", "simple" to "Упрощённый", "detailed" to "Подробный"), mode, { m ->
                    mode = m
                    act(null) { c.api.patch("/api/v1/settings/fitness", jsonOf("nutrition_mode" to m)) }
                })
                if (mode == "off") Secondary("Калории не считаются. Можно просто записывать приёмы пищи — тренер учтёт.")
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTile("Сегодня, ккал", today.sumOf { it.kcal ?: 0 }.toString(), Tone.current.training, Modifier.weight(1f),
                    hint = "± ${today.mapNotNull { it.errorPct }.maxOrNull() ?: 0}%")
                StatTile("Белок, г", decimal(today.sumOf { it.proteinG ?: 0.0 }, 0), Tone.current.surface, Modifier.weight(1f))
            }
        }
        item {
            AppCard(tint = Tone.current.training) {
                Text("Записать приём пищи", style = MaterialTheme.typography.titleMedium)
                Field(description, { description = it }, "Что съели", singleLine = false)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (imagesAllowed) OutlinedButton(onClick = {
                        val f = File(context.cacheDir, "captures/meal-${System.currentTimeMillis()}.jpg").apply { parentFile?.mkdirs() }
                        photo = f
                        take.launch(FileProvider.getUriForFile(context, "${context.packageName}.files", f))
                    }) { Text(if (photo != null) "Фото есть ✓" else "Фото") }
                    Button(enabled = !busy && (description.isNotBlank() || photo != null), onClick = {
                        scope.launch {
                            busy = true
                            try {
                                val attId = photo?.takeIf { it.exists() }?.let { f ->
                                    c.api.upload("/api/v1/chat/attachments", f.name, "image/jpeg", f.readBytes().toRequestBody("image/jpeg".toMediaTypeOrNull())).jsonObject.str("id")
                                }
                                c.api.post("/api/v1/health/meals/estimate", jsonOf("description" to description.ifBlank { null },
                                    "attachment_id" to attId, "estimate" to (mode != "off")))
                                description = ""; photo = null
                                c.sync.syncNow()
                            } catch (e: ApiException) {
                                if (e.isOffline) {
                                    // No network: keep the meal without an estimate; it syncs later.
                                    c.store.save(E.MEALS, jsonOf("eaten_at" to nowIso(), "description" to description, "estimated_by" to "user"))
                                    description = ""; photo = null
                                    snackbar.showSnackbar("Нет связи: записано без оценки")
                                } else snackbar.showSnackbar(e.message ?: "Не удалось оценить")
                            } catch (e: Exception) {
                                snackbar.showSnackbar(e.message ?: "Не удалось оценить")
                            }
                            busy = false
                        }
                    }) { Text(if (busy) "Оцениваю…" else "Сохранить") }
                }
                if (mode != "off") Secondary(if (imagesAllowed)
                    "Калории и БЖУ по фото и описанию — приблизительная оценка, погрешность указывается. Фото смотрит Claude Haiku 5.5."
                    else "Калории и БЖУ по описанию — приблизительная оценка, погрешность указывается.")
                if (!imagesAllowed && allSettings.value != null) {
                    Secondary("Распознавание еды по фото выключено, поэтому кнопки «Фото» нет: снимок нигде бы не использовался.")
                    TextButton(onClick = { nav("settings/privacy") }) { Text("Включить в «Приватности»") }
                }
            }
        }
        if (busy) item { Loading() }
        item { SectionTitle("Сегодня") }
        if (today.isEmpty()) item { Secondary("Записей пока нет") }
        items(meals.sortedByDescending { it.eatenAt ?: "" }.take(40), key = { it.id }) { m ->
            AppCard(padding = 12.dp) {
                ListRow(m.description ?: "Приём пищи", relative(parseInstant(m.eatenAt)) +
                    (m.kcal?.let { " · $it ккал" } ?: "") + (m.proteinG?.let { " · Б ${decimal(it, 0)}" } ?: "") +
                    (m.fatG?.let { " Ж ${decimal(it, 0)}" } ?: "") + (m.carbsG?.let { " У ${decimal(it, 0)}" } ?: "") +
                    (m.errorPct?.let { " · ±$it%" } ?: ""))
            }
        }
    }
}
