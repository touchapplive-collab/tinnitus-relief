package com.tinnitusrelief.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.ui.platform.LocalContext
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
    outlineVariant = Color(0xFF1F282B),
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
    outlineVariant = Color(0xFFDDE2E0),
    error = Color(0xFFB24A2E),
)

/** Stock Material 3 type scale on the system font (One UI Sans on Samsung, Roboto elsewhere). */
private val type = Typography()

@Composable
fun ReliefTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val context = LocalContext.current
    // Material You: on Android 12+ the colours come from the user's wallpaper (One UI / Pixel),
    // so the app looks like part of the phone. Older phones use the built-in calm palette.
    val scheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> Dark
        else -> Light
    }
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
