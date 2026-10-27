package ru.secondbrain.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import ru.secondbrain.app.core.ruDate
import ru.secondbrain.app.ui.theme.Tone
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

/** Quiet inputs: hairline outline, neutral focus ring, clay caret. */
@Composable
fun fieldColors() = OutlinedTextFieldDefaults.colors(
    unfocusedBorderColor = Tone.current.outline, focusedBorderColor = Tone.current.textSecondary,
    focusedLabelColor = Tone.current.textSecondary, cursorColor = Tone.current.accent,
    focusedContainerColor = Tone.current.surface, unfocusedContainerColor = Tone.current.surface,
)

@Composable
fun Field(value: String, onChange: (String) -> Unit, label: String, modifier: Modifier = Modifier,
          singleLine: Boolean = true, number: Boolean = false, minLines: Int = 1, placeholder: String? = null) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) }, singleLine = singleLine, minLines = minLines,
        placeholder = placeholder?.let { { Text(it) } },
        keyboardOptions = if (number) KeyboardOptions(keyboardType = KeyboardType.Decimal) else KeyboardOptions.Default,
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = fieldColors(),
    )
}

@Composable
fun <T> Dropdown(label: String, options: List<Pair<T, String>>, selected: T?, onSelect: (T) -> Unit,
                 modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    Box(modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = options.firstOrNull { it.first == selected }?.second ?: "—", onValueChange = {}, readOnly = true,
            label = { Text(label) }, trailingIcon = { Icon(Icons.Default.ArrowDropDown, null) },
            modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium,
            colors = fieldColors(),
        )
        Box(Modifier.matchParentSize().clickable { open = true })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (v, l) -> DropdownMenuItem(text = { Text(l) }, onClick = { onSelect(v); open = false }) }
        }
    }
}

@Composable
fun DateField(label: String, value: LocalDate?, onChange: (LocalDate?) -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    Box(modifier.fillMaxWidth()) {
        OutlinedTextField(value = value?.let { ruDate(it, true) } ?: "", onValueChange = {}, readOnly = true,
            label = { Text(label) }, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium,
            colors = fieldColors())
        Box(Modifier.matchParentSize().clickable { open = true })
    }
    if (open) {
        val state = rememberDatePickerState(initialSelectedDateMillis =
            (value ?: LocalDate.now()).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(onDismissRequest = { open = false }, confirmButton = {
            TextButton(onClick = {
                onChange(state.selectedDateMillis?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() })
                open = false
            }) { Text("Готово") }
        }, dismissButton = {
            TextButton(onClick = { onChange(null); open = false }) { Text("Очистить") }
        }) { DatePicker(state = state) }
    }
}

@Composable
fun TimeField(label: String, value: LocalTime?, onChange: (LocalTime) -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    Box(modifier.fillMaxWidth()) {
        OutlinedTextField(value = value?.toString()?.take(5) ?: "", onValueChange = {}, readOnly = true,
            label = { Text(label) }, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium,
            colors = fieldColors())
        Box(Modifier.matchParentSize().clickable { open = true })
    }
    if (open) {
        val state = rememberTimePickerState(value?.hour ?: 9, value?.minute ?: 0, is24Hour = true)
        androidx.compose.material3.AlertDialog(onDismissRequest = { open = false },
            confirmButton = { TextButton(onClick = { onChange(LocalTime.of(state.hour, state.minute)); open = false }) { Text("Готово") } },
            text = { TimePicker(state = state) })
    }
}

fun LocalDate.toIsoStartOfDay(): String = atStartOfDay(ZoneId.systemDefault()).toOffsetDateTime().toString()

@Composable
fun Sheet(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Tone.current.bg) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            content()
        }
    }
}

private val GroupSpace = Regex("(?<=\\d) (?=\\d{3}(?!\\d))")
private val UnitSpace = Regex("(?<=\\d) (?=[₽%$€])")

/** Keeps «3 370 ₽» on one line: the model writes plain spaces inside numbers and before the sign. */
fun glueNumbers(text: String): String = text.replace(GroupSpace, "\u00A0").replace(UnitSpace, "\u00A0")

/** Minimal Markdown for agent answers: headings, **bold**, *italic*, `code`, bullet and numbered lists. */
@Composable
fun Markdown(text: String, color: Color = Tone.current.text, style: TextStyle = MaterialTheme.typography.bodyLarge) {
    // Indented lines continue the previous item (wrapped list items in release notes and changelogs).
    val listItem = Regex("^\\s*([-*•]|\\d+[.)]) ")
    val lines = mutableListOf<String>()
    glueNumbers(text).trim().split("\n").forEach { raw ->
        val continues = raw.startsWith("  ") && raw.isNotBlank() && !listItem.containsMatchIn(raw) &&
            lines.lastOrNull()?.isNotBlank() == true
        if (continues) lines[lines.lastIndex] = lines.last() + " " + raw.trim() else lines += raw
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        lines.forEach { raw ->
            val line = raw.trimEnd()
            when {
                line.isBlank() -> Spacer(Modifier.height(2.dp))
                line.startsWith("#") -> {
                    // # and ## read as section titles in the serif display face, ### and deeper as bold lines.
                    val level = line.takeWhile { it == '#' }.length
                    Text(inline(line.trimStart('#').trim()), color = color, modifier = Modifier.padding(top = if (level <= 2) 10.dp else 4.dp),
                        style = if (level <= 2) MaterialTheme.typography.headlineSmall else style.copy(fontWeight = FontWeight.SemiBold))
                }
                line.trimStart().startsWith("- ") || line.trimStart().startsWith("* ") || line.trimStart().startsWith("• ") ->
                    Row {
                        Text("•  ", style = style, color = Tone.current.textSecondary)
                        Text(inline(line.trimStart().drop(2)), style = style, color = color)
                    }
                Regex("^\\s*\\d+[.)] ").containsMatchIn(line) -> {
                    val m = Regex("^\\s*(\\d+)[.)] (.*)").find(line)!!
                    Row {
                        Text("${m.groupValues[1]}.  ", style = style, color = Tone.current.textSecondary)
                        Text(inline(m.groupValues[2]), style = style, color = color)
                    }
                }
                else -> Text(inline(line), style = style, color = color)
            }
        }
    }
}

private fun inline(s: String): AnnotatedString = buildAnnotatedString {
    var i = 0
    while (i < s.length) {
        when {
            s.startsWith("**", i) -> {
                val end = s.indexOf("**", i + 2)
                if (end > 0) { pushStyle(SpanStyle(fontWeight = FontWeight.SemiBold)); append(s.substring(i + 2, end)); pop(); i = end + 2 }
                else { append(s[i]); i++ }
            }
            s[i] == '*' && i + 1 < s.length && s[i + 1] != ' ' -> {
                val end = s.indexOf('*', i + 1)
                if (end > 0) { pushStyle(SpanStyle(fontStyle = FontStyle.Italic)); append(s.substring(i + 1, end)); pop(); i = end + 1 }
                else { append(s[i]); i++ }
            }
            s[i] == '`' -> {
                val end = s.indexOf('`', i + 1)
                if (end > 0) { pushStyle(SpanStyle(fontFamily = FontFamily.Monospace)); append(s.substring(i + 1, end)); pop(); i = end + 1 }
                else { append(s[i]); i++ }
            }
            else -> { append(s[i]); i++ }
        }
    }
}

/** Simple line chart for weight, spending or mood series. */
@Composable
fun LineChart(points: List<Double>, modifier: Modifier = Modifier, color: Color = Tone.current.accent, height: Int = 140) {
    if (points.size < 2) { Secondary("Пока мало данных для графика"); return }
    val min = points.min()
    val max = points.max().let { if (it == min) min + 1 else it }
    val grid = Tone.current.divider
    Canvas(modifier.fillMaxWidth().height(height.dp)) {
        val stepX = size.width / (points.size - 1)
        fun y(v: Double) = (size.height * (1 - (v - min) / (max - min))).toFloat() * 0.85f + size.height * 0.075f
        listOf(0.25f, 0.5f, 0.75f).forEach { f ->
            drawLine(grid, Offset(0f, size.height * f), Offset(size.width, size.height * f), strokeWidth = 1f)
        }
        val path = Path()
        points.forEachIndexed { i, v -> if (i == 0) path.moveTo(0f, y(v)) else path.lineTo(i * stepX, y(v)) }
        drawPath(path, color, style = Stroke(width = 5f, cap = StrokeCap.Round))
        drawCircle(color, 8f, Offset((points.size - 1) * stepX, y(points.last())))
    }
}

/** Horizontal bars with labels (spending by category, volume by muscle group). */
@Composable
fun Bars(items: List<Triple<String, Double, String>>, color: Color = Tone.current.accent) {
    val max = items.maxOfOrNull { it.second }?.takeIf { it > 0 } ?: 1.0
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items.forEach { (label, value, shown) ->
            Column {
                Row(Modifier.fillMaxWidth()) {
                    Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Text(shown, style = MaterialTheme.typography.bodyMedium)
                }
                Spacer(Modifier.height(4.dp))
                ProgressBar((value / max).toFloat(), color)
            }
        }
    }
}

@Composable
fun WeekStrip(values: List<Pair<String, Boolean>>) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        values.forEach { (label, on) ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Dot(if (on) Tone.current.accent else Tone.current.divider, 14.dp)
                Spacer(Modifier.height(4.dp))
                Label(label)
            }
        }
    }
}

@Composable
fun Gap(h: Int = 8) = Spacer(Modifier.height(h.dp))

@Composable
fun HGap(w: Int = 8) = Spacer(Modifier.width(w.dp))
