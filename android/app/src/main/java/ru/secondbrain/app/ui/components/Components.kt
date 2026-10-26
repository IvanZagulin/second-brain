package ru.secondbrain.app.ui.components

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import ru.secondbrain.app.AppContainer
import ru.secondbrain.app.data.api.ApiException
import ru.secondbrain.app.ui.theme.Tone

val LocalContainer = staticCompositionLocalOf<AppContainer> { error("no container") }
val LocalSnackbar = staticCompositionLocalOf<SnackbarHostState> { error("no snackbar") }

/** A navigation action that every screen can call: route strings like "finance/tx/123". */
val LocalNavigate = staticCompositionLocalOf<(String) -> Unit> { {} }
val LocalBack = staticCompositionLocalOf<() -> Unit> { {} }

@Composable
fun Screen(
    title: String,
    subtitle: String? = null,
    back: Boolean = true,
    actions: @Composable RowScope.() -> Unit = {},
    fab: @Composable () -> Unit = {},
    onRefresh: (suspend () -> Unit)? = null,
    content: LazyListScope.() -> Unit,
) {
    val goBack = LocalBack.current
    val scope = rememberCoroutineScope()
    var refreshing by remember { mutableStateOf(false) }
    Scaffold(
        containerColor = Tone.current.bg,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(title, style = MaterialTheme.typography.headlineMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Tone.current.textSecondary)
                    }
                },
                navigationIcon = {
                    if (back) IconButton(onClick = goBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") }
                },
                actions = actions,
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Tone.current.bg),
            )
        },
        floatingActionButton = fab,
        snackbarHost = { SnackbarHost(LocalSnackbar.current) },
    ) { padding ->
        val list = @Composable {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 4.dp,
                    bottom = padding.calculateBottomPadding() + 96.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                content = content,
            )
        }
        if (onRefresh != null) {
            PullToRefreshBox(isRefreshing = refreshing, onRefresh = {
                scope.launch { refreshing = true; runCatching { onRefresh() }; refreshing = false }
            }) { list() }
        } else list()
    }
}

@Composable
fun AppCard(
    modifier: Modifier = Modifier,
    tint: Color = Tone.current.surface,
    onClick: (() -> Unit)? = null,
    padding: Dp = 18.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth().animateContentSize(),
        shape = RoundedCornerShape(24.dp),
        color = tint,
        border = if (tint == Tone.current.surface) BorderStroke(1.dp, Tone.current.divider) else null,
        onClick = onClick ?: {},
        enabled = onClick != null,
    ) {
        Column(Modifier.padding(padding), content = content)
    }
}

@Composable
fun SectionTitle(text: String, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        if (action != null && onAction != null) TextButton(onClick = onAction) { Text(action) }
    }
}

@Composable
fun Label(text: String, modifier: Modifier = Modifier) =
    Text(text, modifier = modifier, style = MaterialTheme.typography.labelMedium, color = Tone.current.textSecondary)

@Composable
fun Secondary(text: String, modifier: Modifier = Modifier, maxLines: Int = Int.MAX_VALUE) =
    Text(text, modifier = modifier, style = MaterialTheme.typography.bodyMedium, color = Tone.current.textSecondary,
        maxLines = maxLines, overflow = TextOverflow.Ellipsis)

@Composable
fun BigNumber(text: String, color: Color = Tone.current.text) =
    Text(text, style = MaterialTheme.typography.displaySmall, color = color)

@Composable
fun Pill(text: String, tint: Color = Tone.current.divider, textColor: Color = Tone.current.text) {
    Box(Modifier.background(tint, RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 4.dp)) {
        Text(text, style = MaterialTheme.typography.labelMedium, color = textColor)
    }
}

@Composable
fun Dot(color: Color, size: Dp = 8.dp) = Box(Modifier.size(size).background(color, CircleShape))

@Composable
fun ListRow(
    title: String,
    subtitle: String? = null,
    trailing: String? = null,
    trailingColor: Color = Tone.current.text,
    icon: ImageVector? = null,
    iconTint: Color = Tone.current.divider,
    onClick: (() -> Unit)? = null,
    extra: @Composable (() -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth().let { if (onClick != null) it.clickable(onClick = onClick) else it }.padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Box(Modifier.size(40.dp).background(iconTint, CircleShape), contentAlignment = Alignment.Center) {
                Icon(icon, null, modifier = Modifier.size(20.dp), tint = Tone.current.text)
            }
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (!subtitle.isNullOrBlank()) Secondary(subtitle, maxLines = 2)
            extra?.invoke()
        }
        if (trailing != null) {
            Spacer(Modifier.width(8.dp))
            Text(trailing, style = MaterialTheme.typography.titleMedium, color = trailingColor)
        }
    }
}

@Composable
fun Divider() = HorizontalDivider(color = Tone.current.divider)

@Composable
fun EmptyState(text: String, action: String? = null, onAction: (() -> Unit)? = null) {
    AppCard {
        Text(text, style = MaterialTheme.typography.bodyLarge, color = Tone.current.textSecondary)
        if (action != null && onAction != null) TextButton(onClick = onAction) { Text(action) }
    }
}

@Composable
fun Loading() = Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
    CircularProgressIndicator()
}

@Composable
fun ErrorCard(message: String, onRetry: (() -> Unit)? = null) {
    AppCard(tint = Tone.current.training) {
        Text(message, style = MaterialTheme.typography.bodyLarge)
        if (onRetry != null) TextButton(onClick = onRetry) { Text("Повторить") }
    }
}

@Composable
fun ProgressBar(progress: Float, color: Color = Tone.current.accent) =
    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().height(8.dp),
        color = color, trackColor = Tone.current.divider, strokeCap = androidx.compose.ui.graphics.StrokeCap.Round)

@Composable
fun SwitchRow(title: String, subtitle: String? = null, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) Secondary(subtitle)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
fun <T> ChoiceChips(options: List<Pair<T, String>>, selected: T?, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        options.forEach { (value, label) ->
            FilterChip(selected = value == selected, onClick = { onSelect(value) }, label = { Text(label) },
                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Tone.current.training))
        }
    }
}

@Composable
fun ConfirmDialog(title: String, text: String, confirm: String = "Да", onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = { Text(text) },
        confirmButton = { TextButton(onClick = { onConfirm(); onDismiss() }) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } })
}

@Composable
fun StatTile(label: String, value: String, tint: Color, modifier: Modifier = Modifier, hint: String? = null) {
    Surface(modifier, shape = RoundedCornerShape(20.dp), color = tint) {
        Column(Modifier.padding(14.dp)) {
            Label(label)
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            if (hint != null) Text(hint, style = MaterialTheme.typography.bodySmall, color = Tone.current.textSecondary)
        }
    }
}

/** Loads data with a suspend block; exposes value/error/loading and a reload trigger. */
class Loadable<T>(val value: T?, val error: String?, val loading: Boolean, val reload: () -> Unit)

@Composable
fun <T> rememberLoad(vararg keys: Any?, block: suspend () -> T): Loadable<T> {
    var tick by remember { mutableIntStateOf(0) }
    var value by remember { mutableStateOf<T?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    LaunchedEffect(tick, *keys) {
        loading = true
        try {
            value = block()
            error = null
        } catch (e: ApiException) {
            error = e.message
        } catch (e: Exception) {
            error = e.message ?: "Ошибка"
        }
        loading = false
    }
    return Loadable(value, error, loading) { tick++ }
}

/** Runs an action with a snackbar for errors/success. */
@Composable
fun rememberAction(): (String?, suspend () -> Unit) -> Unit {
    val scope = rememberCoroutineScope()
    val snackbar = LocalSnackbar.current
    return { success, block ->
        scope.launch {
            try {
                block()
                if (success != null) snackbar.showSnackbar(success)
            } catch (e: Exception) {
                snackbar.showSnackbar(e.message ?: "Ошибка")
            }
        }
    }
}
