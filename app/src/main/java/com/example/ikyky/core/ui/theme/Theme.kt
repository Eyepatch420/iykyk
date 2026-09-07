package com.example.ikyky.core.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * The app theme. **No dynamic colour** — the palette is fixed so every device
 * shows the same restrained, warm-neutral look. Light and dark are the same
 * design, tone-swapped.
 */
private val LightScheme = lightColorScheme(
    primary = Accent,
    onPrimary = OnAccent,
    primaryContainer = SurfaceMuted,
    onPrimaryContainer = InkPrimary,
    secondary = InkSecondary,
    onSecondary = Surface,
    background = WarmWhite,
    onBackground = InkPrimary,
    surface = Surface,
    onSurface = InkPrimary,
    surfaceVariant = SurfaceMuted,
    onSurfaceVariant = InkSecondary,
    outline = Hairline,
    outlineVariant = Hairline,
    error = DangerLight,
    onError = Surface,
    scrim = Color(0x66000000),
)

private val DarkScheme = darkColorScheme(
    primary = AccentOnDark,
    onPrimary = Color(0xFF20120C),
    primaryContainer = SurfaceMutedDark,
    onPrimaryContainer = InkPrimaryDark,
    secondary = InkSecondaryDark,
    onSecondary = SurfaceDark,
    background = WarmBlack,
    onBackground = InkPrimaryDark,
    surface = SurfaceDark,
    onSurface = InkPrimaryDark,
    surfaceVariant = SurfaceMutedDark,
    onSurfaceVariant = InkSecondaryDark,
    outline = HairlineDark,
    outlineVariant = HairlineDark,
    error = DangerDark,
    onError = WarmBlack,
    scrim = Color(0x99000000),
)

@Composable
fun IkykyTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val scheme = if (darkTheme) DarkScheme else LightScheme

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = scheme,
        typography = AppTypography,
        shapes = AppShapes,
        content = content,
    )
}
