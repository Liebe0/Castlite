package com.castlite.phone.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.castlite.phone.cast.CastProtocol

/** CastLite palette. Dark only: the app is used in dim rooms next to a TV. */
object Palette {
    val Background = Color(0xFF090C12)
    val Surface = Color(0xFF111622)
    val SurfaceHigh = Color(0xFF181F2E)
    val SurfaceHighest = Color(0xFF212A3C)
    val Outline = Color(0xFF232C3E)
    val OutlineStrong = Color(0xFF34405A)

    val Cyan = Color(0xFF4FC3F7)
    val Violet = Color(0xFF8B7CFF)
    val Pink = Color(0xFFE879F9)

    val TextPrimary = Color(0xFFF2F5FA)
    val TextSecondary = Color(0xFF9AA4B8)
    val TextTertiary = Color(0xFF667089)

    val Success = Color(0xFF5BE3A2)
    val Warning = Color(0xFFFFC46B)
    val Error = Color(0xFFFF7A85)
    val ErrorSurface = Color(0xFF2A1219)

    /** Signature gradient used for primary actions and highlights. */
    val Accent = Brush.linearGradient(listOf(Cyan, Violet))
    val AccentHorizontal = Brush.horizontalGradient(listOf(Cyan, Violet))
}

/** Each protocol gets its own colour so you can tell at a glance what's in use. */
val CastProtocol.color: Color
    get() = when (this) {
        CastProtocol.DLNA -> Color(0xFFFFB74D)
        CastProtocol.CASTLITE -> Palette.Cyan
        CastProtocol.GOOGLE_CAST -> Color(0xFF81C784)
    }

/** Kept for the status dots. */
val StatusGood = Palette.Success

private val Colors = darkColorScheme(
    primary = Palette.Cyan,
    onPrimary = Color(0xFF00202E),
    primaryContainer = Color(0xFF0E3446),
    onPrimaryContainer = Color(0xFFCDEFFF),
    secondary = Palette.Violet,
    onSecondary = Color.White,
    tertiary = Palette.Pink,
    background = Palette.Background,
    onBackground = Palette.TextPrimary,
    surface = Palette.Background,
    onSurface = Palette.TextPrimary,
    surfaceVariant = Palette.SurfaceHigh,
    onSurfaceVariant = Palette.TextSecondary,
    surfaceContainerLowest = Palette.Background,
    surfaceContainerLow = Palette.Surface,
    surfaceContainer = Palette.Surface,
    surfaceContainerHigh = Palette.SurfaceHigh,
    surfaceContainerHighest = Palette.SurfaceHighest,
    inverseSurface = Palette.TextPrimary,
    inverseOnSurface = Palette.Background,
    error = Palette.Error,
    onError = Color(0xFF3A0008),
    errorContainer = Palette.ErrorSurface,
    onErrorContainer = Color(0xFFFFD9DC),
    outline = Palette.OutlineStrong,
    outlineVariant = Palette.Outline,
)

private val Type = Typography().let { t ->
    t.copy(
        displaySmall = t.displaySmall.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
        headlineMedium = t.headlineMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
        headlineSmall = t.headlineSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.25).sp),
        titleLarge = t.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = t.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        titleSmall = t.titleSmall.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = t.labelLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.2.sp),
        labelMedium = t.labelMedium.copy(fontWeight = FontWeight.SemiBold),
        labelSmall = t.labelSmall.copy(fontWeight = FontWeight.Medium, letterSpacing = 0.6.sp),
    )
}

/** Small uppercase "overline" used above sections. */
val OverlineStyle = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.4.sp, color = Palette.TextTertiary)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

@Composable
fun CastLiteTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Colors, typography = Type, shapes = AppShapes, content = content)
}
