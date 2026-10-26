package ru.secondbrain.app.ui.fitness

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.MonitorWeight
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.Restaurant
import androidx.compose.material3.Button
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import ru.secondbrain.app.core.decimal
import ru.secondbrain.app.core.local
import ru.secondbrain.app.core.nowIso
import ru.secondbrain.app.core.parseInstant
import ru.secondbrain.app.core.relative
import ru.secondbrain.app.core.ruDate
import ru.secondbrain.app.core.ruWeekdayName
import ru.secondbrain.app.core.ruWeekdayShort
import ru.secondbrain.app.data.model.BodyMetric
import ru.secondbrain.app.data.model.E
import ru.secondbrain.app.data.model.Exercise
import ru.secondbrain.app.data.model.MUSCLE_GROUPS
import ru.secondbrain.app.data.model.PlanExercise
import ru.secondbrain.app.data.model.Workout
import ru.secondbrain.app.data.model.WorkoutPlan
import ru.secondbrain.app.data.model.WorkoutSet
import ru.secondbrain.app.data.repo.jsonOf
import ru.secondbrain.app.data.repo.str
import ru.secondbrain.app.ui.components.AppCard
import ru.secondbrain.app.ui.components.BigNumber
import ru.secondbrain.app.ui.components.ChoiceChips
import ru.secondbrain.app.ui.components.ConfirmDialog
import ru.secondbrain.app.ui.components.Divider
import ru.secondbrain.app.ui.components.Dropdown
import ru.secondbrain.app.ui.components.EmptyState
import ru.secondbrain.app.ui.components.Field
import ru.secondbrain.app.ui.components.Gap
import ru.secondbrain.app.ui.components.Label
import ru.secondbrain.app.ui.components.LineChart
import ru.secondbrain.app.ui.components.ListRow
import ru.secondbrain.app.ui.components.LocalBack
import ru.secondbrain.app.ui.components.LocalContainer
import ru.secondbrain.app.ui.components.LocalNavigate
import ru.secondbrain.app.ui.components.Markdown
import ru.secondbrain.app.ui.components.Pill
import ru.secondbrain.app.ui.components.ProgressBar
import ru.secondbrain.app.ui.components.Screen
import ru.secondbrain.app.ui.components.SectionTitle
import ru.secondbrain.app.ui.components.Secondary
import ru.secondbrain.app.ui.components.Sheet
import ru.secondbrain.app.ui.components.WeekStrip
import ru.secondbrain.app.ui.components.rememberAction
import ru.secondbrain.app.ui.components.rememberLoad
import ru.secondbrain.app.ui.theme.Tone
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

@Composable
fun FitnessScreen() {
    val c = LocalContainer.current
    val nav = LocalNavigate.current
    val scope = rememberCoroutineScope()
    val plans by c.store.observe(E.PLANS, WorkoutPlan.serializer()).collectAsState(initial = emptyList())
    val workouts by c.store.observe(E.WORKOUTS, Workout.serializer()).collectAsState(initial = emptyList())
    val metrics by c.store.observe(E.METRICS, BodyMetric.serializer()).collectAsState(initial = emptyList())
    val settings = rememberLoad { c.api.get("/api/v1/settings").jsonObject["fitness"]!!.jsonObject }
    val health = rememberLoad { c.api.get("/api/v1/health/summary", mapOf("days" to 7)).jsonObject }
    val trainingDays = settings.value?.get("training_days")?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull?.toIntOrNull() }
        ?: plans.mapNotNull { it.weekday }
    var newPlan by remember { mutableStateOf(false) }
    val active = workouts.firstOrNull { it.status == "in_progress" }
    val today = LocalDate.now()
    val nextDay = (0..7).map { today.plusDays(it.toLong()) }.firstOrNull { it.dayOfWeek.value in trainingDays }
    val nextPlan = nextDay?.let { d -> plans.firstOrNull { it.weekday == d.dayOfWeek.value && it.isActive } }

    fun start(plan: WorkoutPlan?) {
        scope.launch {
            val id = c.store.save(E.WORKOUTS, jsonOf("plan_id" to plan?.id, "started_at" to nowIso(), "status" to "in_progress"))
            nav("fitness/workout/$id")
        }
    }

    Screen("Тренировки", back = false, onRefresh = { c.sync.syncNow(); health.reload() },
        fab = {
            if (active == null) ExtendedFloatingActionButton(onClick = { start(nextPlan?.takeIf { nextDay == today } ?: nextPlan) },
                icon = { Icon(Icons.Default.Add, null) }, text = { Text("Начать тренировку") },
                containerColor = Tone.current.accent, contentColor = Tone.current.surface)
        }) {
        if (active != null) item {
            AppCard(tint = Tone.current.training, onClick = { nav("fitness/workout/${active.id}") }) {
                Label("Идёт тренировка")
                Text(plans.firstOrNull { it.id == active.planId }?.name ?: "Свободная тренировка", style = MaterialTheme.typography.headlineSmall)
                Secondary("Начата ${active.start?.let { relative(it) } ?: ""}")
                Button(onClick = { nav("fitness/workout/${active.id}") }) { Text("Продолжить") }
            }
        }
        item {
            AppCard(tint = Tone.current.training) {
                Label("Следующая тренировка")
                if (nextDay == null) Secondary("Дни тренировок не заданы — откройте программу и укажите день недели.")
                else {
                    Text((if (nextDay == today) "Сегодня" else if (nextDay == today.plusDays(1)) "Завтра" else ruWeekdayName(nextDay.dayOfWeek.value).replaceFirstChar { it.uppercase() }) +
                        (settings.value?.str("training_time")?.let { " · $it" } ?: ""), style = MaterialTheme.typography.headlineSmall)
                    Secondary(nextPlan?.name ?: "Программа на этот день не выбрана")
                }
                Gap(10)
                val weekStart = today.minusDays(today.dayOfWeek.value - 1L)
                WeekStrip((1..7).map { d ->
                    val day = weekStart.plusDays(d - 1L)
                    ruWeekdayShort(d) to workouts.any { w -> w.status == "done" && w.start?.local()?.toLocalDate() == day }
                })
            }
        }
        val done4w = workouts.count { it.status == "done" && it.start?.isAfter(Instant.now().minus(Duration.ofDays(28))) == true }
        val planned4w = trainingDays.size * 4
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ru.secondbrain.app.ui.components.StatTile("За 4 недели", "$done4w из $planned4w", Tone.current.surface, Modifier.weight(1f),
                    hint = if (planned4w > 0) "регулярность ${done4w * 100 / planned4w}%" else null)
                val weight = metrics.filter { it.kind == "weight" }.maxByOrNull { it.measuredAt ?: "" }
                ru.secondbrain.app.ui.components.StatTile("Вес", weight?.let { "${decimal(it.value)} кг" } ?: "—", Tone.current.surface, Modifier.weight(1f),
                    hint = settings.value?.str("target_weight")?.let { "цель $it кг" })
            }
        }
        val steps = health.value?.get("steps")?.jsonArray?.lastOrNull()?.jsonObject?.str("sum")?.toDoubleOrNull()
        val sleep = health.value?.get("sleep")?.jsonArray?.lastOrNull()?.jsonObject?.str("sum")?.toDoubleOrNull()
        if (steps != null || sleep != null) item {
            AppCard {
                Label("С часов (Health Connect)")
                Secondary(listOfNotNull(steps?.let { "шаги сегодня ${it.toLong()}" }, sleep?.let { "сон ${(it / 60).toInt()} ч ${(it % 60).toInt()} мин" }).joinToString(" · "))
            }
        }
        item { SectionTitle("Программы", "Добавить") { newPlan = true } }
        if (plans.isEmpty()) item { EmptyState("Программ пока нет") }
        items(plans.filter { it.isActive }.sortedBy { it.weekday ?: 9 }, key = { it.id }) { p ->
            AppCard(onClick = { nav("fitness/plan/${p.id}") }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(p.name, style = MaterialTheme.typography.titleMedium)
                        Secondary(listOfNotNull(p.weekday?.let { ruWeekdayName(it) }, p.plannedMinutes?.let { "~$it мин" }).joinToString(" · "))
                    }
                    TextButton(onClick = { start(p) }) { Text("Начать") }
                }
            }
        }
        item { SectionTitle("История") }
        val history = workouts.filter { it.status != "in_progress" }.sortedByDescending { it.startedAt ?: "" }.take(12)
        if (history.isEmpty()) item { Secondary("Записанных тренировок пока нет") }
        items(history, key = { it.id }) { w ->
            val minutes = if (w.start != null && w.end != null) Duration.between(w.start, w.end).toMinutes() else null
            AppCard(onClick = { nav("fitness/workout/${w.id}") }, padding = 12.dp) {
                ListRow(plans.firstOrNull { it.id == w.planId }?.name ?: "Тренировка",
                    listOfNotNull(w.start?.let { relative(it) }, minutes?.let { "$it мин" }, w.feeling?.let { "самочувствие $it/10" },
                        if (w.status == "skipped") "пропущена" else null).joinToString(" · "))
            }
        }
        item {
            AppCard(padding = 6.dp) {
                ListRow("Вес и замеры", icon = Icons.Outlined.MonitorWeight, iconTint = Tone.current.training) { nav("fitness/metrics") }
                Divider()
                ListRow("Фото формы", icon = Icons.Outlined.PhotoCamera, iconTint = Tone.current.training) { nav("fitness/photos") }
                Divider()
                ListRow("Питание", icon = Icons.Outlined.Restaurant, iconTint = Tone.current.training) { nav("fitness/nutrition") }
            }
        }
    }
    if (newPlan) Sheet({ newPlan = false }) {
        var name by remember { mutableStateOf("") }
        var weekday by remember { mutableStateOf<Int?>(null) }
        Text("Новая программа", style = MaterialTheme.typography.headlineSmall)
        Field(name, { name = it }, "Название, например «Спина, бицепс, плечи»")
        Label("День недели")
        ChoiceChips(listOf<Pair<Int?, String>>(null to "Любой") + (1..7).map { it to ruWeekdayShort(it) }, weekday, { weekday = it })
        Button(onClick = {
            scope.launch {
                val id = c.store.save(E.PLANS, jsonOf("name" to name, "weekday" to weekday, "is_active" to true))
                newPlan = false
                nav("fitness/plan/$id")
            }
        }, enabled = name.isNotBlank()) { Text("Создать") }
    }
}

// ------------------------------------------------------------------ active workout

@Composable
fun WorkoutScreen(id: String) {
    val c = LocalContainer.current
    val back = LocalBack.current
    val act = rememberAction()
    val haptic = LocalHapticFeedback.current
    val workout by c.store.observeOne(E.WORKOUTS, id, Workout.serializer()).collectAsState(initial = null)
    val plans by c.store.observe(E.PLANS, WorkoutPlan.serializer()).collectAsState(initial = emptyList())
    val planItems by c.store.observe(E.PLAN_EXERCISES, PlanExercise.serializer()).collectAsState(initial = emptyList())
    val exercises by c.store.observe(E.EXERCISES, Exercise.serializer()).collectAsState(initial = emptyList())
    val allSets by c.store.observe(E.SETS, WorkoutSet.serializer()).collectAsState(initial = emptyList())
    val allWorkouts by c.store.observe(E.WORKOUTS, Workout.serializer()).collectAsState(initial = emptyList())
    val w = workout ?: return Screen("Тренировка") { item { Secondary("Загрузка…") } }
    val sets = allSets.filter { it.workoutId == id }.sortedBy { it.doneAt ?: "" }
    val exById = exercises.associateBy { it.id }
    val plan = plans.firstOrNull { it.id == w.planId }
    val planExercises = planItems.filter { it.planId == w.planId }.sortedBy { it.position }
    val order = (planExercises.map { it.exerciseId } + sets.map { it.exerciseId }).distinct()
    var restLeft by remember { mutableIntStateOf(0) }
    var restTotal by remember { mutableIntStateOf(90) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var addExercise by remember { mutableStateOf(false) }
    var finishing by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val inProgress = w.status == "in_progress"

    LaunchedEffect(restLeft) {
        if (restLeft > 0) {
            delay(1000)
            restLeft -= 1
            if (restLeft == 0) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        }
    }
    LaunchedEffect(inProgress) { while (inProgress) { now = System.currentTimeMillis(); delay(1000) } }

    // Previous session per exercise (last done workout that had this exercise).
    val previous: Map<String, List<WorkoutSet>> = remember(allSets, allWorkouts, id) {
        val doneIds = allWorkouts.filter { it.id != id && it.status == "done" }.sortedByDescending { it.startedAt ?: "" }.map { it.id }
        order.associateWith { exId ->
            val wid = doneIds.firstOrNull { wid -> allSets.any { it.workoutId == wid && it.exerciseId == exId } }
            allSets.filter { it.workoutId == wid && it.exerciseId == exId && !it.isWarmup }.sortedBy { it.setNo }
        }
    }

    val elapsed = w.start?.let { Duration.ofMillis((if (inProgress) now else (w.end?.toEpochMilli() ?: now)) - it.toEpochMilli()) }
    Screen(plan?.name ?: "Тренировка", subtitle = elapsed?.let { "%d:%02d".format(it.toHours(), it.toMinutes() % 60) + if (inProgress) " · идёт" else "" },
        actions = { IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Outlined.Delete, "Удалить") } }) {
        if (restLeft > 0) item {
            AppCard(tint = Tone.current.motivation) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Label("Отдых")
                        BigNumber("%d:%02d".format(restLeft / 60, restLeft % 60))
                    }
                    TextButton(onClick = { restLeft += 30 }) { Text("+30 с") }
                    TextButton(onClick = { restLeft = 0 }) { Text("Хватит") }
                }
                ProgressBar(restLeft.toFloat() / restTotal)
            }
        }
        if (!inProgress && w.analysis != null) item {
            AppCard(tint = Tone.current.memory) { Label("Разбор тренера"); Markdown(w.analysis!!) }
        }
        items(order, key = { it }) { exId ->
            val ex = exById[exId]
            val target = planExercises.firstOrNull { it.exerciseId == exId }
            val mySets = sets.filter { it.exerciseId == exId }
            ExerciseCard(ex, target, mySets, previous[exId].orEmpty(), inProgress,
                onAdd = { weight, reps, rpe ->
                    act(null) {
                        c.store.save(E.SETS, jsonOf("workout_id" to id, "exercise_id" to exId, "set_no" to mySets.count { !it.isWarmup } + 1,
                            "weight" to weight, "reps" to reps, "rpe" to rpe, "done_at" to nowIso()))
                        restTotal = target?.restSeconds ?: c.prefs.get(ru.secondbrain.app.core.Prefs.Keys.restSeconds) ?: 90
                        restLeft = restTotal
                    }
                },
                onDelete = { s -> act(null) { c.store.delete(E.SETS, s.id) } })
        }
        if (inProgress) item {
            OutlinedButton(onClick = { addExercise = true }, modifier = Modifier.fillMaxWidth()) { Text("Добавить упражнение") }
        }
        if (inProgress) item {
            Button(onClick = { finishing = true }, modifier = Modifier.fillMaxWidth()) { Text("Завершить тренировку") }
        } else item {
            Secondary(listOfNotNull(w.feeling?.let { "Самочувствие $it/10" }, w.painNote?.let { "Жалобы: $it" }, w.notes).joinToString("\n"))
        }
    }
    if (addExercise) Sheet({ addExercise = false }) {
        var name by remember { mutableStateOf("") }
        var group by remember { mutableStateOf("other") }
        Text("Упражнение", style = MaterialTheme.typography.headlineSmall)
        // Today's muscle groups first (in plan order), then everything else alphabetically.
        val focus = plan?.muscleGroups.orEmpty()
        val matches = exercises.filter { !it.archived && (name.isBlank() || it.name.contains(name, true)) && it.id !in order }
            .sortedWith(compareBy({ focus.indexOf(it.muscleGroup).let { i -> if (i < 0) focus.size else i } }, { it.name }))
            .take(8)
        Field(name, { name = it }, "Найти или создать")
        matches.forEach { e ->
            ListRow(e.name, MUSCLE_GROUPS[e.muscleGroup], onClick = {
                act(null) {
                    c.store.save(E.SETS, jsonOf("workout_id" to id, "exercise_id" to e.id, "set_no" to 0, "is_warmup" to true,
                        "reps" to 0, "weight" to 0, "note" to "добавлено", "done_at" to nowIso()))
                    addExercise = false
                }
            })
        }
        if (name.isNotBlank() && exercises.none { it.name.equals(name, true) }) {
            Label("Группа мышц")
            ChoiceChips(MUSCLE_GROUPS.entries.map { it.key to it.value }, group, { group = it })
            Button(onClick = {
                act(null) {
                    val exId = c.store.save(E.EXERCISES, jsonOf("name" to name, "muscle_group" to group))
                    c.store.save(E.SETS, jsonOf("workout_id" to id, "exercise_id" to exId, "set_no" to 0, "is_warmup" to true,
                        "reps" to 0, "weight" to 0, "note" to "добавлено", "done_at" to nowIso()))
                    addExercise = false
                }
            }) { Text("Создать «$name»") }
        }
    }
    if (finishing) Sheet({ finishing = false }) {
        var feeling by remember { mutableIntStateOf(7) }
        var pain by remember { mutableStateOf("") }
        var notes by remember { mutableStateOf(w.notes ?: "") }
        Text("Как прошло?", style = MaterialTheme.typography.headlineSmall)
        Label("Самочувствие")
        ChoiceChips((1..10).map { it to "$it" }, feeling, { feeling = it })
        Field(pain, { pain = it }, "Боль или дискомфорт (если есть)")
        Field(notes, { notes = it }, "Комментарий", singleLine = false)
        Secondary("После завершения тренер разберёт тренировку и напишет в чат агента.")
        Button(onClick = {
            act("Тренировка сохранена") {
                c.store.save(E.WORKOUTS, jsonOf("status" to "done", "ended_at" to nowIso(), "feeling" to feeling,
                    "pain_note" to pain.ifBlank { null }, "notes" to notes.ifBlank { null }), id)
                finishing = false
                c.sync.requestSync(0)
            }
        }) { Text("Завершить") }
    }
    if (confirmDelete) ConfirmDialog("Удалить тренировку?", "Подходы этой тренировки тоже будут удалены.",
        onConfirm = { act(null) { sets.forEach { c.store.delete(E.SETS, it.id) }; c.store.delete(E.WORKOUTS, id); back() } },
        onDismiss = { confirmDelete = false })
}

@Composable
private fun ExerciseCard(ex: Exercise?, target: PlanExercise?, sets: List<WorkoutSet>, previous: List<WorkoutSet>, editable: Boolean,
                         onAdd: (Double?, Int?, Double?) -> Unit, onDelete: (WorkoutSet) -> Unit) {
    val real = sets.filter { !(it.isWarmup && it.note == "добавлено") }
    val last = real.lastOrNull() ?: previous.lastOrNull()
    var weight by remember(ex?.id, real.size) { mutableStateOf(last?.weight?.let { decimal(it) } ?: target?.targetWeight?.let { decimal(it) } ?: "") }
    var reps by remember(ex?.id, real.size) { mutableStateOf(last?.reps?.toString() ?: target?.targetReps?.substringBefore("-") ?: "") }
    var rpe by remember(ex?.id, real.size) { mutableStateOf("") }
    AppCard {
        Text(ex?.name ?: "Упражнение", style = MaterialTheme.typography.titleMedium)
        Secondary(listOfNotNull(ex?.muscleGroup?.let { MUSCLE_GROUPS[it] },
            target?.let { t -> listOfNotNull(t.targetSets?.let { "$it подх." }, t.targetReps?.let { "по $it" },
                t.targetWeight?.let { "${decimal(it)} кг" }).joinToString(" ").ifBlank { null } }).joinToString(" · "))
        if (previous.isNotEmpty()) Label("В прошлый раз: " + previous.joinToString(", ") { "${decimal(it.weight)}×${it.reps}" })
        real.forEachIndexed { i, s ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${i + 1}.  ${decimal(s.weight)} кг × ${s.reps ?: 0}" + (s.rpe?.let { " · RPE ${decimal(it)}" } ?: ""),
                    style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                if (editable) IconButton(onClick = { onDelete(s) }) { Icon(Icons.Outlined.Delete, "Удалить подход", tint = Tone.current.textSecondary) }
            }
        }
        if (editable) {
            Gap(6)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Field(weight, { weight = it }, "кг", number = true, modifier = Modifier.weight(1f))
                Field(reps, { reps = it }, "повт.", number = true, modifier = Modifier.weight(1f))
                Field(rpe, { rpe = it }, "RPE", number = true, modifier = Modifier.weight(0.8f))
            }
            Button(onClick = { onAdd(weight.replace(",", ".").toDoubleOrNull(), reps.toIntOrNull(), rpe.replace(",", ".").toDoubleOrNull()) },
                enabled = reps.isNotBlank()) { Text("Записать подход") }
        }
    }
}

// ------------------------------------------------------------------ plan editor

@Composable
fun PlanScreen(id: String) {
    val c = LocalContainer.current
    val nav = LocalNavigate.current
    val act = rememberAction()
    val plan by c.store.observeOne(E.PLANS, id, WorkoutPlan.serializer()).collectAsState(initial = null)
    val items by c.store.observeChildren(E.PLAN_EXERCISES, id, PlanExercise.serializer()).collectAsState(initial = emptyList())
    val exercises by c.store.observe(E.EXERCISES, Exercise.serializer()).collectAsState(initial = emptyList())
    val revisions = rememberLoad(id) { c.api.get("/api/v1/workouts/plans/$id/revisions").jsonObject["items"]!!.jsonArray.map { it.jsonObject } }
    var editItem by remember { mutableStateOf<PlanExercise?>(null) }
    var adding by remember { mutableStateOf(false) }
    val p = plan ?: return Screen("Программа") { item { Secondary("Загрузка…") } }
    val sorted = items.sortedBy { it.position }
    val exById = exercises.associateBy { it.id }
    Screen(p.name, subtitle = p.weekday?.let { ruWeekdayName(it) }) {
        item {
            AppCard {
                var name by remember(p.id) { mutableStateOf(p.name) }
                var minutes by remember(p.id) { mutableStateOf(p.plannedMinutes?.toString() ?: "") }
                Field(name, { name = it }, "Название")
                Label("День недели")
                ChoiceChips(listOf<Pair<Int?, String>>(null to "Любой") + (1..7).map { it to ruWeekdayShort(it) }, p.weekday,
                    { d -> act(null) { c.store.save(E.PLANS, jsonOf("weekday" to d), id) } })
                Field(minutes, { minutes = it }, "Длительность, мин", number = true)
                TextButton(onClick = { act("Сохранено") { c.store.save(E.PLANS, jsonOf("name" to name, "planned_minutes" to minutes.toIntOrNull()), id) } }) { Text("Сохранить") }
            }
        }
        item { SectionTitle("Упражнения", "Добавить") { adding = true } }
        if (sorted.isEmpty()) item { EmptyState("Добавьте упражнения — их можно взять из памяти или попросить агента-тренера составить список.") }
        items(sorted, key = { it.id }) { pe ->
            val ex = exById[pe.exerciseId]
            AppCard(padding = 12.dp, onClick = { editItem = pe }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(ex?.name ?: "—", style = MaterialTheme.typography.titleMedium)
                        Secondary(listOfNotNull(pe.targetSets?.let { "$it подх." }, pe.targetReps?.let { "по $it" },
                            pe.targetWeight?.let { "${decimal(it)} кг" }, pe.restSeconds?.let { "отдых ${it}с" }).joinToString(" · "))
                    }
                    IconButton(onClick = { act(null) { swap(c, sorted, pe, -1) } }) { Icon(Icons.Outlined.KeyboardArrowUp, "Выше") }
                    IconButton(onClick = { act(null) { swap(c, sorted, pe, 1) } }) { Icon(Icons.Outlined.KeyboardArrowDown, "Ниже") }
                }
                TextButton(onClick = { nav("fitness/exercise/${pe.exerciseId}") }) { Text("Прогресс") }
            }
        }
        val revs = revisions.value ?: emptyList()
        if (revs.isNotEmpty()) {
            item { SectionTitle("Версии программы") }
            items(revs, key = { it.str("id")!! }) { r ->
                AppCard(padding = 12.dp) {
                    ListRow("${if (r.str("author") == "agent") "Агент" else "Вы"}: ${r.str("reason") ?: "изменение"}", relative(parseInstant(r.str("created_at"))))
                    TextButton(onClick = { act("Программа восстановлена") {
                        c.api.post("/api/v1/workouts/plans/$id/revisions/${r.str("id")}/restore"); c.sync.syncNow(); revisions.reload()
                    } }) { Text("Вернуть эту версию") }
                }
            }
        }
    }
    if (adding) Sheet({ adding = false }) {
        var name by remember { mutableStateOf("") }
        var group by remember { mutableStateOf(p.muscleGroups.firstOrNull() ?: "other") }
        Text("Добавить упражнение", style = MaterialTheme.typography.headlineSmall)
        Field(name, { name = it }, "Название")
        exercises.filter { !it.archived && name.isNotBlank() && it.name.contains(name, true) }.take(6).forEach { e ->
            ListRow(e.name, MUSCLE_GROUPS[e.muscleGroup], onClick = {
                act(null) { c.store.save(E.PLAN_EXERCISES, jsonOf("plan_id" to id, "exercise_id" to e.id, "position" to sorted.size)); adding = false }
            })
        }
        Label("Группа мышц")
        ChoiceChips(MUSCLE_GROUPS.entries.map { it.key to it.value }, group, { group = it })
        Button(enabled = name.isNotBlank(), onClick = {
            act(null) {
                val exId = exercises.firstOrNull { it.name.equals(name, true) }?.id ?: c.store.save(E.EXERCISES, jsonOf("name" to name, "muscle_group" to group))
                c.store.save(E.PLAN_EXERCISES, jsonOf("plan_id" to id, "exercise_id" to exId, "position" to sorted.size))
                adding = false
            }
        }) { Text("Добавить") }
    }
    editItem?.let { pe ->
        Sheet({ editItem = null }) {
            var sets by remember { mutableStateOf(pe.targetSets?.toString() ?: "") }
            var reps by remember { mutableStateOf(pe.targetReps ?: "") }
            var weight by remember { mutableStateOf(pe.targetWeight?.let { decimal(it) } ?: "") }
            var rest by remember { mutableStateOf(pe.restSeconds?.toString() ?: "") }
            Text(exById[pe.exerciseId]?.name ?: "Упражнение", style = MaterialTheme.typography.headlineSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Field(sets, { sets = it }, "Подходы", number = true, modifier = Modifier.weight(1f))
                Field(reps, { reps = it }, "Повторы (8-12)", modifier = Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Field(weight, { weight = it }, "Вес, кг", number = true, modifier = Modifier.weight(1f))
                Field(rest, { rest = it }, "Отдых, сек", number = true, modifier = Modifier.weight(1f))
            }
            Button(onClick = {
                act("Сохранено") {
                    c.store.save(E.PLAN_EXERCISES, jsonOf("target_sets" to sets.toIntOrNull(), "target_reps" to reps.ifBlank { null },
                        "target_weight" to weight.replace(",", ".").toDoubleOrNull(), "rest_seconds" to rest.toIntOrNull()), pe.id)
                    editItem = null
                }
            }) { Text("Сохранить") }
            TextButton(onClick = { act(null) { c.store.delete(E.PLAN_EXERCISES, pe.id); editItem = null } }) { Text("Убрать из программы") }
        }
    }
}

private suspend fun swap(c: ru.secondbrain.app.AppContainer, list: List<PlanExercise>, item: PlanExercise, dir: Int) {
    val i = list.indexOf(item)
    val j = i + dir
    if (j !in list.indices) return
    c.store.save(E.PLAN_EXERCISES, jsonOf("position" to j), item.id)
    c.store.save(E.PLAN_EXERCISES, jsonOf("position" to i), list[j].id)
}

@Composable
fun ExerciseScreen(id: String) {
    val c = LocalContainer.current
    val data = rememberLoad(id) { c.api.get("/api/v1/workouts/progress/$id").jsonObject }
    val sessions = data.value?.get("sessions")?.jsonArray?.map { it.jsonObject } ?: emptyList()
    Screen(data.value?.get("exercise")?.jsonObject?.str("name") ?: "Прогресс") {
        item {
            AppCard(tint = Tone.current.training) {
                Label("Оценка максимума на 1 повтор (e1RM)")
                LineChart(sessions.mapNotNull { it.str("e1rm")?.toDoubleOrNull() })
            }
        }
        items(sessions.reversed(), key = { it.str("date") + it.str("e1rm") }) { s ->
            AppCard(padding = 12.dp) {
                ListRow(s.str("date")?.let { ru.secondbrain.app.core.parseDate(it) }?.let { ruDate(it) } ?: "",
                    "лучший вес ${s.str("top_weight")} кг · подходов ${s.str("sets")} · объём ${s.str("volume")?.toDoubleOrNull()?.toLong()} кг",
                    s.str("e1rm"))
            }
        }
    }
}
