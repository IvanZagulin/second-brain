package ru.secondbrain.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Section tints and neutral tokens beyond the Material scheme. */
@Immutable
data class Tones(
    val bg: Color, val surface: Color, val text: Color, val textSecondary: Color, val accent: Color,
    val finance: Color, val training: Color, val memory: Color, val motivation: Color, val divider: Color,
    val positive: Color, val negative: Color, val onTint: Color,
)

val LightTones = Tones(
    bg = Color(0xFFF8F5EF), surface = Color(0xFFFFFDF9), text = Color(0xFF302E2B), textSecondary = Color(0xFF77716B),
    accent = Color(0xFFCF785D), finance = Color(0xFFDCEBDD), training = Color(0xFFF4D9C8), memory = Color(0xFFE8E1F3),
    motivation = Color(0xFFF3E9BE), divider = Color(0xFFE9E4DD), positive = Color(0xFF4E8A5B), negative = Color(0xFFB8533B),
    onTint = Color(0xFF302E2B),
)

val DarkTones = Tones(
    bg = Color(0xFF1C1A18), surface = Color(0xFF262321), text = Color(0xFFF1ECE5), textSecondary = Color(0xFFA9A199),
    accent = Color(0xFFE38E72), finance = Color(0xFF2D3B2F), training = Color(0xFF473226), memory = Color(0xFF363044),
    motivation = Color(0xFF433D22), divider = Color(0xFF39342F), positive = Color(0xFF7FBF8C), negative = Color(0xFFE88B73),
    onTint = Color(0xFFF1ECE5),
)

val LocalTones = staticCompositionLocalOf { LightTones }

/** Display text in a serif face (presentation feel), body in the system sans. */
private val Display = FontFamily.Serif

private fun typography(t: Tones) = Typography(
    displayLarge = TextStyle(fontFamily = Display, fontWeight = FontWeight.Normal, fontSize = 44.sp, lineHeight = 50.sp, color = t.text),
    displayMedium = TextStyle(fontFamily = Display, fontWeight = FontWeight.Normal, fontSize = 36.sp, lineHeight = 42.sp, color = t.text),
    displaySmall = TextStyle(fontFamily = Display, fontWeight = FontWeight.Normal, fontSize = 30.sp, lineHeight = 36.sp, color = t.text),
    headlineLarge = TextStyle(fontFamily = Display, fontWeight = FontWeight.Normal, fontSize = 28.sp, lineHeight = 34.sp),
    headlineMedium = TextStyle(fontFamily = Display, fontWeight = FontWeight.Normal, fontSize = 24.sp, lineHeight = 30.sp),
    headlineSmall = TextStyle(fontFamily = Display, fontWeight = FontWeight.Normal, fontSize = 21.sp, lineHeight = 27.sp),
    titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 19.sp, lineHeight = 25.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp),
    titleSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp),
    labelSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 14.sp),
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp), small = RoundedCornerShape(12.dp), medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp), extraLarge = RoundedCornerShape(32.dp),
)

@Composable
fun SecondBrainTheme(themeMode: String? = null, content: @Composable () -> Unit) {
    val dark = when (themeMode) {
        "light" -> false
        "dark" -> true
        else -> isSystemInDarkTheme()
    }
    val t = if (dark) DarkTones else LightTones
    val scheme = if (dark) darkColorScheme(
        primary = t.accent, onPrimary = Color(0xFF1C1A18), secondary = t.textSecondary, background = t.bg,
        onBackground = t.text, surface = t.surface, onSurface = t.text, surfaceVariant = t.divider,
        onSurfaceVariant = t.textSecondary, outline = t.divider, outlineVariant = t.divider, error = t.negative,
        surfaceContainer = t.surface, surfaceContainerHigh = t.surface, surfaceContainerLow = t.bg,
        surfaceContainerHighest = t.divider, primaryContainer = t.training, onPrimaryContainer = t.text,
        secondaryContainer = t.memory, onSecondaryContainer = t.text,
    ) else lightColorScheme(
        primary = t.accent, onPrimary = Color.White, secondary = t.textSecondary, background = t.bg,
        onBackground = t.text, surface = t.surface, onSurface = t.text, surfaceVariant = t.divider,
        onSurfaceVariant = t.textSecondary, outline = t.divider, outlineVariant = t.divider, error = t.negative,
        surfaceContainer = t.surface, surfaceContainerHigh = t.surface, surfaceContainerLow = t.bg,
        surfaceContainerHighest = t.divider, primaryContainer = t.training, onPrimaryContainer = t.text,
        secondaryContainer = t.memory, onSecondaryContainer = t.text,
    )
    CompositionLocalProvider(LocalTones provides t) {
        MaterialTheme(colorScheme = scheme, typography = typography(t), shapes = AppShapes, content = content)
    }
}

object Tone {
    val current: Tones @Composable get() = LocalTones.current
}
