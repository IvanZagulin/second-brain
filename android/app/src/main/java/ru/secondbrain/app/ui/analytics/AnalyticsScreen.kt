package ru.secondbrain.app.ui.analytics

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import ru.secondbrain.app.core.local
import ru.secondbrain.app.core.parseInstant
import ru.secondbrain.app.core.rub
import ru.secondbrain.app.data.model.BodyMetric
import ru.secondbrain.app.data.model.BudgetCategory
import ru.secondbrain.app.data.model.E
import ru.secondbrain.app.data.model.MoodEntry
import ru.secondbrain.app.data.model.Transaction
import ru.secondbrain.app.data.model.Workout
import ru.secondbrain.app.data.repo.str
import ru.secondbrain.app.ui.components.AppCard
import ru.secondbrain.app.ui.components.Bars
import ru.secondbrain.app.ui.components.ChoiceChips
import ru.secondbrain.app.ui.components.Gap
import ru.secondbrain.app.ui.components.Label
import ru.secondbrain.app.ui.components.LineChart
import ru.secondbrain.app.ui.components.LocalContainer
import ru.secondbrain.app.ui.components.Screen
import ru.secondbrain.app.ui.components.Secondary
import ru.secondbrain.app.ui.components.rememberLoad
import ru.secondbrain.app.ui.theme.Tone
import java.time.LocalDate

@Composable
fun AnalyticsScreen() {
    val c = LocalContainer.current
    var days by remember { mutableStateOf(30) }
    val txs by c.store.observe(E.TRANSACTIONS, Transaction.serializer()).collectAsState(initial = emptyList())
    val cats by c.store.observe(E.CATEGORIES, BudgetCategory.serializer()).collectAsState(initial = emptyList())
    val mood by c.store.observe(E.MOOD, MoodEntry.serializer()).collectAsState(initial = emptyList())
    val metrics by c.store.observe(E.METRICS, BodyMetric.serializer()).collectAsState(initial = emptyList())
    val workouts by c.store.observe(E.WORKOUTS, Workout.serializer()).collectAsState(initial = emptyList())
    val health = rememberLoad(days) { c.api.get("/api/v1/health/summary", mapOf("days" to days)).jsonObject }
    val usage = rememberLoad { c.api.get("/api/v1/usage/summary").jsonObject }
    val since = LocalDate.now().minusDays(days.toLong())
    val catMap = cats.associateBy { it.id }
    val expenses = txs.filter { it.direction == "out" && it.flowType == "expense" && it.status != "excluded" && it.scope == "personal" &&
        (it.at?.local()?.toLocalDate()?.isAfter(since) == true) }
    val byDay = (0 until days).map { back -> val d = LocalDate.now().minusDays((days - 1 - back).toLong())
        expenses.filter { it.at?.local()?.toLocalDate() == d }.sumOf { it.amountMinor } / 100.0 }
    Screen("Аналитика") {
        item { ChoiceChips(listOf(7 to "Неделя", 30 to "Месяц", 90 to "3 месяца"), days, { days = it }) }
        item {
            AppCard(tint = Tone.current.finance) {
                Label("Траты по дням")
                Text(rub(expenses.sumOf { it.amountMinor }), style = MaterialTheme.typography.headlineSmall)
                Gap(6)
                LineChart(byDay)
            }
        }
        item {
            AppCard {
                Text("По категориям", style = MaterialTheme.typography.titleMedium)
                Gap(6)
                Bars(expenses.groupBy { it.categoryId }.map { (k, v) -> Triple(catMap[k]?.name ?: "Без категории", v.sumOf { it.amountMinor }.toDouble(), rub(v.sumOf { it.amountMinor })) }
                    .sortedByDescending { it.second }.take(10))
            }
        }
        val impulse = expenses.filter { catMap[it.categoryId]?.isImpulse == true }
        if (impulse.isNotEmpty()) item {
            AppCard(tint = Tone.current.training) {
                Label("Импульсивные траты")
                Text(rub(impulse.sumOf { it.amountMinor }), style = MaterialTheme.typography.headlineSmall)
                Secondary("${impulse.size} покупок за период")
            }
        }
        item {
            AppCard(tint = Tone.current.motivation) {
                Label("Настроение")
                LineChart(mood.filter { parseInstant(it.occurredAt)?.local()?.toLocalDate()?.isAfter(since) == true }
                    .sortedBy { it.occurredAt ?: "" }.map { it.score.toDouble() })
            }
        }
        item {
            AppCard(tint = Tone.current.training) {
                Label("Вес")
                LineChart(metrics.filter { it.kind == "weight" }.sortedBy { it.measuredAt ?: "" }.map { it.value })
                Secondary("Тренировок за период: ${workouts.count { it.status == "done" && it.start?.local()?.toLocalDate()?.isAfter(since) == true }}")
            }
        }
        val h = health.value
        listOf("steps" to "Шаги", "sleep" to "Сон, мин", "resting_hr" to "Пульс покоя").forEach { (k, label) ->
            val series = h?.get(k)?.jsonArray?.map { it.jsonObject.str(if (k == "resting_hr") "avg" else "sum")?.toDoubleOrNull() ?: 0.0 }
            if (!series.isNullOrEmpty()) item { AppCard { Label("$label (Health Connect)"); LineChart(series) } }
        }
        val apps = h?.get("app_usage")?.jsonArray?.map { it.jsonObject }
        if (!apps.isNullOrEmpty()) item {
            AppCard {
                Label("Экранное время, мин в день (всего)")
                LineChart(apps.map { it.str("sum")?.toDoubleOrNull() ?: 0.0 })
            }
        }
        usage.value?.let { u ->
            item {
                AppCard {
                    Text("Claude API", style = MaterialTheme.typography.titleMedium)
                    Secondary("Потрачено $${u.str("spent_usd")} из $${u.str("limit_usd")}, прогноз на месяц $${u.str("forecast_usd")}")
                    Gap(6)
                    Bars(u["by_model"]?.jsonArray.orEmpty().map { it.jsonObject }.map {
                        Triple(it.str("model") ?: "", it.str("cost_usd")?.toDoubleOrNull() ?: 0.0, "$" + (it.str("cost_usd") ?: "0"))
                    })
                }
            }
        }
    }
}
