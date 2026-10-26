package ru.secondbrain.app.widgets

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.action.clickable
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import ru.secondbrain.app.MainActivity
import ru.secondbrain.app.SecondBrainApp
import ru.secondbrain.app.core.Prefs
import ru.secondbrain.app.core.rub
import ru.secondbrain.app.data.api.PlainJson
import ru.secondbrain.app.notifications.AgentNotifier

private val Bg = ColorProvider(Color(0xFFFFFDF9))
private val Text1 = ColorProvider(Color(0xFF302E2B))
private val Text2 = ColorProvider(Color(0xFF77716B))
private val Accent = ColorProvider(Color(0xFFCF785D))
private val Finance = ColorProvider(Color(0xFFDCEBDD))

object Widgets {
    suspend fun updateAll(context: Context) {
        runCatching { FinanceWidget().updateAll(context) }
        runCatching { AgentWidget().updateAll(context) }
    }
}

private suspend fun homeCache(context: Context): JsonObject? {
    val prefs = (context.applicationContext as SecondBrainApp).container.prefs
    return prefs.get(Prefs.Keys.homeCache)?.let { runCatching { PlainJson.parseToJsonElement(it).jsonObject }.getOrNull() }
}

private fun openApp(context: Context, route: String) = actionStartActivity(
    Intent(context, MainActivity::class.java).putExtra(AgentNotifier.EXTRA_ROUTE, route)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))

class FinanceWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val home = homeCache(context)
        val fin = home?.get("finance") as? JsonObject
        val left = fin?.get("today_left_minor")?.jsonPrimitive?.longOrNull
        val free = fin?.get("free_minor")?.jsonPrimitive?.longOrNull
        val estimated = fin?.get("estimated")?.jsonPrimitive?.contentOrNull == "true"
        provideContent {
            GlanceTheme {
                Column(GlanceModifier.fillMaxSize().background(Finance).cornerRadius(24.dp).padding(14.dp)
                    .clickable(openApp(context, "finance")), verticalAlignment = Alignment.CenterVertically) {
                    Text("Можно потратить сегодня", style = TextStyle(color = Text2, fontSize = 12.sp))
                    Text(if (left != null) rub(left) else "—",
                        style = TextStyle(color = Text1, fontSize = 26.sp, fontWeight = FontWeight.Bold))
                    Text("Свободно ${rub(free)}" + if (estimated) " · оценка" else "",
                        style = TextStyle(color = Text2, fontSize = 12.sp))
                }
            }
        }
    }
}

class AgentWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val home = homeCache(context)
        val obs = (home?.get("observation") as? JsonObject)?.get("text")?.jsonPrimitive?.contentOrNull
        val conv = (home?.get("observation") as? JsonObject)?.get("conversation_id")?.jsonPrimitive?.contentOrNull
        provideContent {
            GlanceTheme {
                Column(GlanceModifier.fillMaxSize().background(Bg).cornerRadius(24.dp).padding(14.dp)
                    .clickable(openApp(context, if (conv != null) "chat/$conv" else "chat"))) {
                    Text("Второй мозг", style = TextStyle(color = Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold))
                    Spacer(GlanceModifier.height(6.dp))
                    Text(obs?.take(220) ?: "Нажмите, чтобы поговорить с агентом",
                        style = TextStyle(color = Text1, fontSize = 14.sp), maxLines = 5)
                }
            }
        }
    }
}

class FinanceWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = FinanceWidget()
}

class AgentWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = AgentWidget()
}

@Suppress("unused")
suspend fun hasWidgets(context: Context): Boolean =
    GlanceAppWidgetManager(context).getGlanceIds(FinanceWidget::class.java).isNotEmpty()
