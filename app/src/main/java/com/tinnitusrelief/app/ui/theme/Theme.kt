package com.tinnitusrelief.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** Extra semantic colours that Material's scheme doesn't name. */
@Immutable
data class ReliefColors(
    val muted: Color,
    val hairline: Color,
    val glow: Color,
    val warn: Color,
)

val LocalReliefColors = staticCompositionLocalOf {
    ReliefColors(Color.Gray, Color.LightGray, Color.Cyan, Color.Red)
}

// Palette "C · light and airy": warm paper background, white grouped surfaces, one calm sea-green accent.
// A matching warm charcoal variant is used at night (system dark mode).
private val Dark = darkColorScheme(
    primary = Color(0xFF9CCFC6),
    onPrimary = Color(0xFF0B1A18),
    primaryContainer = Color(0xFF21302D),
    onPrimaryContainer = Color(0xFFCFE6E1),
    background = Color(0xFF161816),
    onBackground = Color(0xFFECE8E1),
    surface = Color(0xFF161816),
    onSurface = Color(0xFFECE8E1),
    surfaceVariant = Color(0xFF262A27),
    onSurfaceVariant = Color(0xFFA3A9A5),
    surfaceContainerLowest = Color(0xFF1F2220),
    surfaceContainerLow = Color(0xFF1C1F1D),
    surfaceContainer = Color(0xFF262A27),
    surfaceContainerHigh = Color(0xFF222624),
    secondaryContainer = Color(0xFF21302D),
    onSecondaryContainer = Color(0xFFCFE6E1),
    outline = Color(0xFF3A403C),
    outlineVariant = Color(0xFF2E3330),
    error = Color(0xFFF0A08A),
)

private val Light = lightColorScheme(
    primary = Color(0xFF2E6F67),
    onPrimary = Color(0xFFF6FBFA),
    primaryContainer = Color(0xFFDCE7E3),
    onPrimaryContainer = Color(0xFF173A35),
    background = Color(0xFFF4F1EC),
    onBackground = Color(0xFF1E2422),
    surface = Color(0xFFF4F1EC),
    onSurface = Color(0xFF1E2422),
    surfaceVariant = Color(0xFFEAE6DF),
    onSurfaceVariant = Color(0xFF6F7672),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF8F6F2),
    surfaceContainer = Color(0xFFEAE6DF),
    surfaceContainerHigh = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFDCE7E3),
    onSecondaryContainer = Color(0xFF173A35),
    outline = Color(0xFFD9D3C9),
    outlineVariant = Color(0xFFE9E4DC),
    error = Color(0xFFB24A2E),
)

/** Stock Material 3 type scale on the system font (One UI Sans on Samsung, Roboto elsewhere). */
private val type = Typography()

@Composable
fun ReliefTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val scheme = if (dark) Dark else Light
    val extra = ReliefColors(
        muted = scheme.onSurfaceVariant,
        hairline = scheme.outlineVariant,
        glow = scheme.primary,
        warn = scheme.error,
    )
    androidx.compose.runtime.CompositionLocalProvider(LocalReliefColors provides extra) {
        MaterialTheme(colorScheme = scheme, typography = type, content = content)
    }
}
