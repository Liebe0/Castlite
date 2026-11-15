package com.castlite.phone.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** CastLite is dark-only: it's used in dim rooms next to a TV. */
private val CastLiteColors = darkColorScheme(
    primary = Color(0xFF4FC3F7),
    onPrimary = Color(0xFF002233),
    primaryContainer = Color(0xFF0D3A4F),
    onPrimaryContainer = Color(0xFFCDEFFF),
    secondary = Color(0xFF9FB3C8),
    background = Color(0xFF0F1115),
    onBackground = Color(0xFFE6E6E6),
    surface = Color(0xFF0F1115),
    onSurface = Color(0xFFE6E6E6),
    surfaceVariant = Color(0xFF1B1F26),
    onSurfaceVariant = Color(0xFFB4BAC4),
    surfaceContainer = Color(0xFF181B21),
    surfaceContainerHigh = Color(0xFF1F232A),
    error = Color(0xFFFF8A80),
    errorContainer = Color(0xFF4A1515),
    onErrorContainer = Color(0xFFFFDAD6),
    outline = Color(0xFF3A404A),
)

/** Accent used for "everything is fine" status dots. */
val StatusGood = Color(0xFF81C784)

@Composable
fun CastLiteTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = CastLiteColors, content = content)
}
