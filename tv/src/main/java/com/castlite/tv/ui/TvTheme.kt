package com.castlite.tv.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme

/** Same palette as the phone app so the two feel like one product. */
object TvPalette {
    val Background = Color(0xFF070A10)
    val Surface = Color(0xFF111622)
    val SurfaceHigh = Color(0xFF1A2131)
    val Outline = Color(0xFF263048)
    val Cyan = Color(0xFF4FC3F7)
    val Violet = Color(0xFF8B7CFF)
    val Pink = Color(0xFFE879F9)
    val TextPrimary = Color(0xFFF2F5FA)
    val TextSecondary = Color(0xFFA3ADC2)
    val TextTertiary = Color(0xFF6B7590)
    val Success = Color(0xFF5BE3A2)
    val Error = Color(0xFFFF7A85)
    val Accent = Brush.linearGradient(listOf(Cyan, Violet))
    val AccentHorizontal = Brush.horizontalGradient(listOf(Cyan, Violet))
}

@Composable
fun TvTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = TvPalette.Cyan,
            secondary = TvPalette.Violet,
            background = TvPalette.Background,
            surface = TvPalette.Surface,
            onSurface = TvPalette.TextPrimary,
            onBackground = TvPalette.TextPrimary,
            error = TvPalette.Error,
        ),
        content = content,
    )
}
