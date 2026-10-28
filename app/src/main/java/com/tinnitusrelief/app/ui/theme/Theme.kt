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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

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

// Palette: still water at dusk. Deep blue-green ink, mineral greys, one calm sea-glass accent.
private val Dark = darkColorScheme(
    primary = Color(0xFF9CCFC6),
    onPrimary = Color(0xFF0B1A18),
    background = Color(0xFF0B1012),
    onBackground = Color(0xFFE6ECEA),
    surface = Color(0xFF0B1012),
    onSurface = Color(0xFFE6ECEA),
    surfaceVariant = Color(0xFF151C1F),
    onSurfaceVariant = Color(0xFFB4BFBC),
    surfaceContainerLow = Color(0xFF12191B),
    surfaceContainer = Color(0xFF151C1F),
    surfaceContainerHigh = Color(0xFF1A2225),
    secondaryContainer = Color(0xFF1E2B2C),
    onSecondaryContainer = Color(0xFFCFE6E1),
    outline = Color(0xFF2A3437),
    error = Color(0xFFF0A08A),
)

private val Light = lightColorScheme(
    primary = Color(0xFF2E6F67),
    onPrimary = Color(0xFFF6FBFA),
    background = Color(0xFFF2F4F2),
    onBackground = Color(0xFF111819),
    surface = Color(0xFFF2F4F2),
    onSurface = Color(0xFF111819),
    surfaceVariant = Color(0xFFE6EAE8),
    onSurfaceVariant = Color(0xFF46504E),
    surfaceContainerLow = Color(0xFFECEFED),
    surfaceContainer = Color(0xFFE6EAE8),
    surfaceContainerHigh = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFDCE8E5),
    onSecondaryContainer = Color(0xFF173A35),
    outline = Color(0xFFD3D9D7),
    error = Color(0xFFB24A2E),
)

private val type = Typography(
    displaySmall = TextStyle(fontWeight = FontWeight.Light, fontSize = 40.sp, lineHeight = 46.sp, letterSpacing = (-0.8).sp),
    headlineSmall = TextStyle(fontWeight = FontWeight.Normal, fontSize = 26.sp, lineHeight = 32.sp, letterSpacing = (-0.3).sp),
    titleMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 17.sp, lineHeight = 24.sp),
    bodyLarge = TextStyle(fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 21.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.2.sp),
    labelMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 16.sp, letterSpacing = 2.sp),
)

@Composable
fun ReliefTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val extra = if (dark) {
        ReliefColors(Color(0xFF7E8A88), Color(0xFF1F282B), Color(0xFF9CCFC6), Color(0xFFF0A08A))
    } else {
        ReliefColors(Color(0xFF6B7573), Color(0xFFDDE2E0), Color(0xFF2E6F67), Color(0xFFB24A2E))
    }
    androidx.compose.runtime.CompositionLocalProvider(LocalReliefColors provides extra) {
        MaterialTheme(colorScheme = if (dark) Dark else Light, typography = type, content = content)
    }
}
