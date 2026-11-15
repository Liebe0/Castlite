package com.castlite.phone.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Movie
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.runtime.remember
import androidx.compose.ui.draw.shadow
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.castlite.phone.cast.CastProtocol
import com.castlite.phone.media.CastMedia
import com.castlite.phone.media.MediaKind
import com.castlite.phone.ui.theme.Palette
import com.castlite.phone.ui.theme.color

/** Rounded surface card with a hairline border — the basic building block of every screen. */
@Composable
fun AppCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    border: BorderStroke = BorderStroke(1.dp, Palette.Outline),
    color: Color = Palette.Surface,
    shape: Shape = MaterialTheme.shapes.large,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val clickable = if (onClick != null) Modifier.clip(shape).clickable(onClick = onClick) else Modifier
    Surface(modifier = modifier.then(clickable), shape = shape, color = color, border = border) {
        Column(Modifier.padding(contentPadding), content = content)
    }
}

/** Pill button filled with the accent gradient. The main call to action on a screen. */
@Composable
fun GradientButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    val shape = RoundedCornerShape(50)
    Box(
        modifier = modifier
            .clip(shape)
            .background(if (enabled) Palette.AccentHorizontal else Brush.horizontalGradient(listOf(Palette.SurfaceHighest, Palette.SurfaceHighest)))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(icon, contentDescription = null, tint = if (enabled) Color(0xFF0A0D14) else Palette.TextTertiary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
            }
            Text(
                text,
                color = if (enabled) Color(0xFF0A0D14) else Palette.TextTertiary,
                style = MaterialTheme.typography.labelLarge.copy(fontSize = 16.sp, fontWeight = FontWeight.Bold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Round icon in a tinted disc (device rows, empty states). */
@Composable
fun IconDisc(icon: ImageVector, tint: Color, modifier: Modifier = Modifier, size: Dp = 44.dp) {
    Box(
        modifier.size(size).clip(CircleShape).background(tint.copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(size * 0.5f))
    }
}

/** Small coloured label naming the protocol ("DLNA", "CastLite"). */
@Composable
fun ProtocolBadge(protocol: CastProtocol, modifier: Modifier = Modifier, long: Boolean = false) {
    val c = protocol.color
    Box(
        modifier
            .clip(RoundedCornerShape(50))
            .background(c.copy(alpha = 0.14f))
            .border(1.dp, c.copy(alpha = 0.35f), RoundedCornerShape(50))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(
            if (long) protocol.label else protocol.shortLabel,
            color = c,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

@Composable
fun StatusDot(color: Color, modifier: Modifier = Modifier, size: Dp = 8.dp) {
    Box(modifier.size(size).clip(CircleShape).background(color))
}

/** "QUEUE · 4" style section header with an optional action on the right. */
@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, trailing: @Composable (() -> Unit)? = null) {
    Row(modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            title.uppercase(),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 1.4.sp),
            color = Palette.TextTertiary,
        )
        trailing?.invoke()
    }
}

/** Gradient-filled tile with the media kind's icon; the thumbnail loads on top when possible. */
@Composable
fun MediaThumbnail(media: CastMedia, modifier: Modifier = Modifier, shape: Shape = RoundedCornerShape(14.dp), iconSize: Dp = 24.dp) {
    Box(modifier.clip(shape).background(media.kind.gradient()), contentAlignment = Alignment.Center) {
        Icon(media.kind.icon(), contentDescription = null, tint = Color.White.copy(alpha = 0.9f), modifier = Modifier.size(iconSize))
        if (media.kind == MediaKind.IMAGE || media.kind == MediaKind.VIDEO) {
            AsyncImage(
                model = media.contentUri,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

fun MediaKind.icon(): ImageVector = when (this) {
    MediaKind.VIDEO -> Icons.Filled.Movie
    MediaKind.IMAGE -> Icons.Filled.Image
    MediaKind.AUDIO -> Icons.Filled.Headphones
    MediaKind.OTHER -> Icons.Filled.ErrorOutline
}

fun MediaKind.label(): String = when (this) {
    MediaKind.VIDEO -> "Video"
    MediaKind.IMAGE -> "Photo"
    MediaKind.AUDIO -> "Audio"
    MediaKind.OTHER -> "File"
}

fun MediaKind.gradient(): Brush = when (this) {
    MediaKind.VIDEO -> Brush.linearGradient(listOf(Color(0xFF2B5876), Color(0xFF4E4376)))
    MediaKind.IMAGE -> Brush.linearGradient(listOf(Color(0xFF134E5E), Color(0xFF2F8F83)))
    MediaKind.AUDIO -> Brush.linearGradient(listOf(Color(0xFF6A3093), Color(0xFFA044FF)))
    MediaKind.OTHER -> Brush.linearGradient(listOf(Palette.SurfaceHigh, Palette.SurfaceHighest))
}

/** Expanding rings: "we're searching the network". */
@Composable
fun RadarPulse(modifier: Modifier = Modifier, color: Color = Palette.Cyan, content: @Composable BoxScope.() -> Unit = {}) {
    val transition = rememberInfiniteTransition(label = "radar")
    val phase by transition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2400, easing = LinearEasing), RepeatMode.Restart),
        label = "phase",
    )
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val maxR = size.minDimension / 2
            for (i in 0 until 3) {
                val p = (phase + i / 3f) % 1f
                drawCircle(
                    color = color.copy(alpha = (1f - p) * 0.45f),
                    radius = maxR * (0.3f + 0.7f * p),
                    center = Offset(size.width / 2, size.height / 2),
                    style = Stroke(width = 2.dp.toPx()),
                )
            }
        }
        content()
    }
}

/** Centered block for empty lists and first-run screens. */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier.padding(vertical = 24.dp, horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier.size(88.dp).clip(CircleShape)
                .background(Brush.radialGradient(listOf(Palette.Violet.copy(alpha = 0.35f), Color.Transparent))),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier.size(60.dp).clip(CircleShape).background(Palette.Accent),
                contentAlignment = Alignment.Center,
            ) { Icon(icon, contentDescription = null, tint = Color(0xFF0A0D14), modifier = Modifier.size(30.dp)) }
        }
        Text(title, style = MaterialTheme.typography.titleLarge, color = Palette.TextPrimary)
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = Palette.TextSecondary,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        if (action != null) {
            Spacer(Modifier.height(4.dp))
            action()
        }
    }
}

/** Red-tinted inline alert with an optional action. */
@Composable
fun ErrorCard(message: String, modifier: Modifier = Modifier, icon: ImageVector = Icons.Filled.ErrorOutline, actionLabel: String? = null, onAction: (() -> Unit)? = null) {
    AppCard(
        modifier = modifier,
        color = Palette.ErrorSurface,
        border = BorderStroke(1.dp, Palette.Error.copy(alpha = 0.35f)),
        contentPadding = PaddingValues(start = 16.dp, top = 14.dp, bottom = 14.dp, end = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = Palette.Error)
            Spacer(Modifier.width(12.dp))
            Text(message, modifier = Modifier.weight(1f), color = Color(0xFFFFD9DC), style = MaterialTheme.typography.bodyMedium)
            if (actionLabel != null && onAction != null) {
                TextButton(onClick = onAction) { Text(actionLabel, color = Palette.Error) }
            }
        }
    }
}

/**
 * Slim slider used for seeking and volume: a 4 dp gradient track and a round white
 * thumb (instead of Material's default thick track with a bar thumb).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    onValueChangeFinished: (() -> Unit)? = null,
    enabled: Boolean = true,
    gradient: List<Color> = listOf(Palette.Cyan, Palette.Violet),
) {
    val interaction = remember { MutableInteractionSource() }
    Slider(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        enabled = enabled,
        onValueChangeFinished = onValueChangeFinished,
        interactionSource = interaction,
        thumb = {
            Box(
                Modifier.size(18.dp).shadow(6.dp, CircleShape).clip(CircleShape)
                    .background(if (enabled) Color.White else Palette.TextTertiary),
            )
        },
        track = { state ->
            val range = state.valueRange.endInclusive - state.valueRange.start
            val fraction = if (range > 0f) ((state.value - state.valueRange.start) / range).coerceIn(0f, 1f) else 0f
            Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(Palette.SurfaceHighest)) {
                if (enabled) {
                    Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().background(Brush.horizontalGradient(gradient)))
                }
            }
        },
    )
}

fun formatDuration(ms: Long): String {
    if (ms <= 0) return "0:00"
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s / 60) % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

fun formatSize(bytes: Long): String = when {
    bytes < 0 -> "?"
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.0f KB".format(bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(bytes / 1_048_576.0)
    else -> "%.2f GB".format(bytes / 1_073_741_824.0)
}
