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
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.secondbrain.app.R

/**
 * Warm, quiet palette: ivory paper, near-black ink, one clay accent. Section "tints" are only faint
 * washes now (icons, pills) — cards stay neutral so the screen reads as one calm surface.
 */
@Immutable
data class Tones(
    val bg: Color, val surface: Color, val text: Color, val textSecondary: Color, val accent: Color,
    val finance: Color, val training: Color, val memory: Color, val motivation: Color, val divider: Color,
    val positive: Color, val negative: Color, val onTint: Color,
    /** Selected chips, the user's chat bubble, the nav indicator. */
    val fill: Color,
    /** Input and button outlines: a step darker than [divider]. */
    val outline: Color,
)

val LightTones = Tones(
    bg = Color(0xFFFAF9F5), surface = Color(0xFFFFFFFF), text = Color(0xFF1F1E1D), textSecondary = Color(0xFF6F6C64),
    accent = Color(0xFFC96442), finance = Color(0xFFEEF0E8), training = Color(0xFFF5EDE7), memory = Color(0xFFEFEDF3),
    motivation = Color(0xFFF5F1E4), divider = Color(0xFFEAE7DD), positive = Color(0xFF3F7A4F), negative = Color(0xFFB4492F),
    onTint = Color(0xFF1F1E1D), fill = Color(0xFFF0EEE6), outline = Color(0xFFD8D4C8),
)

val DarkTones = Tones(
    bg = Color(0xFF262624), surface = Color(0xFF30302E), text = Color(0xFFF5F4EE), textSecondary = Color(0xFFA39F95),
    accent = Color(0xFFD97757), finance = Color(0xFF2E322C), training = Color(0xFF3A302A), memory = Color(0xFF33313A),
    motivation = Color(0xFF37342A), divider = Color(0xFF3E3D39), positive = Color(0xFF86BE93), negative = Color(0xFFE38A70),
    onTint = Color(0xFFF5F4EE), fill = Color(0xFF393937), outline = Color(0xFF4A4945),
)

val LocalTones = staticCompositionLocalOf { LightTones }

/** Source Serif 4 (variable, OFL): one file, weights 400–600, optical size tuned per use. */
@OptIn(ExperimentalTextApi::class)
private fun serif(opticalSize: Float) = FontFamily(
    listOf(400 to FontWeight.Normal, 500 to FontWeight.Medium, 600 to FontWeight.SemiBold).map { (w, weight) ->
        Font(R.font.source_serif_4, weight, variationSettings = FontVariation.Settings(
            FontVariation.weight(w), FontVariation.Setting("opsz", opticalSize)))
    }
)

/** Headlines and big numbers. */
val SerifDisplay = serif(36f)

/** Running text in a serif — the agent's voice. */
val SerifText = serif(18f)

/** The agent's answers and observations: serif prose, generous leading. */
val AgentProse = TextStyle(fontFamily = SerifText, fontSize = 17.sp, lineHeight = 27.sp)

private fun typography(t: Tones) = Typography(
    displayLarge = TextStyle(fontFamily = SerifDisplay, fontSize = 40.sp, lineHeight = 46.sp, letterSpacing = (-0.4).sp, color = t.text),
    displayMedium = TextStyle(fontFamily = SerifDisplay, fontSize = 34.sp, lineHeight = 40.sp, letterSpacing = (-0.3).sp, color = t.text),
    displaySmall = TextStyle(fontFamily = SerifDisplay, fontSize = 30.sp, lineHeight = 36.sp, letterSpacing = (-0.2).sp, color = t.text),
    headlineLarge = TextStyle(fontFamily = SerifDisplay, fontSize = 28.sp, lineHeight = 34.sp, letterSpacing = (-0.2).sp),
    headlineMedium = TextStyle(fontFamily = SerifDisplay, fontSize = 24.sp, lineHeight = 30.sp),
    headlineSmall = TextStyle(fontFamily = SerifDisplay, fontSize = 20.sp, lineHeight = 26.sp),
    titleLarge = TextStyle(fontFamily = SerifDisplay, fontSize = 20.sp, lineHeight = 26.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 22.sp),
    titleSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp),
    labelSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 14.sp),
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp), small = RoundedCornerShape(10.dp), medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp), extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun SecondBrainTheme(themeMode: String? = null, content: @Composable () -> Unit) {
    val dark = when (themeMode) {
        "light" -> false
        "dark" -> true
        else -> isSystemInDarkTheme()
    }
    val t = if (dark) DarkTones else LightTones
    // primaryContainer drives the FABs: ink on paper (inverted in the dark theme), like a pen, not a sticker.
    val scheme = if (dark) darkColorScheme(
        primary = t.accent, onPrimary = Color.White, secondary = t.textSecondary, background = t.bg,
        onBackground = t.text, surface = t.surface, onSurface = t.text, surfaceVariant = t.fill,
        onSurfaceVariant = t.textSecondary, outline = t.outline, outlineVariant = t.divider, error = t.negative,
        surfaceContainerLowest = t.bg, surfaceContainerLow = t.bg, surfaceContainer = t.surface,
        surfaceContainerHigh = t.surface, surfaceContainerHighest = t.fill, surfaceTint = Color.Transparent,
        primaryContainer = t.text, onPrimaryContainer = t.bg, secondaryContainer = t.fill, onSecondaryContainer = t.text,
        tertiaryContainer = t.fill, onTertiaryContainer = t.text,
    ) else lightColorScheme(
        primary = t.accent, onPrimary = Color.White, secondary = t.textSecondary, background = t.bg,
        onBackground = t.text, surface = t.surface, onSurface = t.text, surfaceVariant = t.fill,
        onSurfaceVariant = t.textSecondary, outline = t.outline, outlineVariant = t.divider, error = t.negative,
        surfaceContainerLowest = t.surface, surfaceContainerLow = t.bg, surfaceContainer = t.surface,
        surfaceContainerHigh = t.surface, surfaceContainerHighest = t.fill, surfaceTint = Color.Transparent,
        primaryContainer = t.text, onPrimaryContainer = t.bg, secondaryContainer = t.fill, onSecondaryContainer = t.text,
        tertiaryContainer = t.fill, onTertiaryContainer = t.text,
    )
    CompositionLocalProvider(LocalTones provides t) {
        MaterialTheme(colorScheme = scheme, typography = typography(t), shapes = AppShapes, content = content)
    }
}

object Tone {
    val current: Tones @Composable get() = LocalTones.current
}
