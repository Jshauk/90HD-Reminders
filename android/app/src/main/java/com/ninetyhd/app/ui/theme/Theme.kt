package com.ninetyhd.app.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val Teal = Color(0xFF00D4AA)
private val DarkBackground = Color(0xFF1A1A2E)
private val DarkSurface = Color(0xFF16213E)
private val DarkSurfaceVariant = Color(0xFF0F3460)

private val DarkColorScheme = darkColorScheme(
    primary = Teal,
    onPrimary = DarkBackground,
    secondary = Teal,
    tertiary = Color(0xFF7B68EE),
    background = DarkBackground,
    surface = DarkSurface,
    surfaceVariant = DarkSurfaceVariant,
    onBackground = Color(0xFFEEEEEE),
    onSurface = Color(0xFFEEEEEE),
    onSurfaceVariant = Color(0xFFAAAAAA),
    error = Color(0xFFE94560),
    onError = Color.White,
)

@Composable
fun NinetyHDTheme(content: @Composable () -> Unit) {
    val colorScheme = DarkColorScheme
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.background.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
