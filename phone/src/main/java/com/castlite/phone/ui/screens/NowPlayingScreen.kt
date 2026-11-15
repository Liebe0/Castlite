package com.castlite.phone.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Slideshow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.ClosedCaptionDisabled
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import com.castlite.protocol.RepeatMode
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.castlite.phone.cast.CastSession
import com.castlite.phone.cast.PlayerState
import com.castlite.phone.media.MediaKind
import com.castlite.phone.ui.components.AppCard
import com.castlite.phone.ui.components.AppSlider
import com.castlite.phone.ui.components.ErrorCard
import com.castlite.phone.ui.components.MediaThumbnail
import com.castlite.phone.ui.components.ProtocolBadge
import com.castlite.phone.ui.components.SectionHeader
import com.castlite.phone.ui.components.StatusDot
import com.castlite.phone.ui.components.formatDuration
import com.castlite.phone.ui.components.gradient
import com.castlite.phone.ui.components.label
import com.castlite.phone.ui.theme.Palette
import com.castlite.phone.ui.theme.color
import kotlinx.coroutines.delay

data class PlayerActions(
    val back: () -> Unit = {},
    val togglePlayPause: () -> Unit = {},
    val seekTo: (Long) -> Unit = {},
    val next: () -> Unit = {},
    val previous: () -> Unit = {},
    val stop: () -> Unit = {},
    val setVolume: (Int) -> Unit = {},
    val jumpTo: (Int) -> Unit = {},
    val retry: () -> Unit = {},
    val cycleRepeat: () -> Unit = {},
    val setSubtitles: (Boolean) -> Unit = {},
)

@Composable
fun NowPlayingScreen(session: CastSession, slideDurationMs: Long, actions: PlayerActions) {
    val status = session.status
    val media = session.current
    val accent = session.device.protocol.color

    Box(Modifier.fillMaxSize().background(Palette.Background)) {
        // Large tinted glow behind the artwork, coloured by media type.
        Box(
            Modifier.fillMaxWidth().aspectRatio(0.9f).background(
                Brush.verticalGradient(listOf(accent.copy(alpha = 0.16f), Palette.Violet.copy(alpha = 0.08f), Color.Transparent)),
            ),
        )
        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
        ) {
            // --- Top bar -----------------------------------------------------
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = actions.back) {
                    Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Close player", tint = Palette.TextPrimary)
                }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("CASTING TO", style = MaterialTheme.typography.labelSmall, color = Palette.TextTertiary)
                    Text(session.devicesLabel, style = MaterialTheme.typography.titleSmall, color = Palette.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                ProtocolBadge(session.device.protocol, Modifier.padding(end = 8.dp))
            }

            Spacer(Modifier.height(20.dp))

            // --- Artwork -----------------------------------------------------
            Box(Modifier.fillMaxWidth().aspectRatio(1f), contentAlignment = Alignment.Center) {
                Box(
                    Modifier.fillMaxSize(0.96f).clip(RoundedCornerShape(32.dp))
                        .background(media?.kind?.gradient() ?: Palette.Accent),
                ) {
                    if (media != null) {
                        MediaThumbnail(media, Modifier.fillMaxSize(), shape = RoundedCornerShape(32.dp), iconSize = 96.dp)
                    }
                    if (status.state == PlayerState.LOADING || status.state == PlayerState.CONNECTING) {
                        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator(color = Color.White, strokeWidth = 3.dp)
                                Spacer(Modifier.height(12.dp))
                                Text(
                                    if (status.state == PlayerState.CONNECTING) "Connecting to TV…" else "Starting on the TV…",
                                    color = Color.White,
                                    style = MaterialTheme.typography.labelLarge,
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))

            // --- Title ---------------------------------------------------------
            Text(
                media?.displayName ?: "Nothing playing",
                style = MaterialTheme.typography.headlineSmall,
                color = Palette.TextPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(stateColor(status.state))
                Spacer(Modifier.width(8.dp))
                Text(
                    buildString {
                        append(stateLabel(status.state))
                        media?.let { append("  ·  ").append(it.kind.label()) }
                        if (session.queue.size > 1) append("  ·  ${status.index + 1} of ${session.queue.size}")
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = Palette.TextSecondary,
                )
            }

            session.problem?.let { problem ->
                Spacer(Modifier.height(16.dp))
                ErrorCard(problem, actionLabel = if (session.networkProblem == null) "Retry" else null, onAction = actions.retry)
            }

            Spacer(Modifier.height(20.dp))

            // --- Seek bar ------------------------------------------------------
            if (media?.kind == MediaKind.IMAGE) {
                AppCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Slideshow, contentDescription = null, tint = Palette.Cyan)
                        Spacer(Modifier.width(12.dp))
                        Text(
                            if (status.state == PlayerState.PAUSED) "Slideshow paused" else "Slideshow · ${slideDurationMs / 1000} s per photo",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Palette.TextSecondary,
                        )
                    }
                }
            } else {
                SeekBar(
                    positionMs = smoothPosition(status.positionMs, status.durationMs, status.state == PlayerState.PLAYING),
                    durationMs = status.durationMs,
                    onSeek = actions.seekTo,
                )
            }

            Spacer(Modifier.height(16.dp))

            // --- Transport controls -----------------------------------------
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RoundIcon(
                    if (status.repeat == RepeatMode.ONE) Icons.Filled.RepeatOne else Icons.Filled.Repeat,
                    "Repeat: ${status.repeat.name.lowercase()}",
                    actions.cycleRepeat,
                    size = 48.dp,
                    tint = if (status.repeat == RepeatMode.OFF) Palette.TextSecondary else Palette.Cyan,
                )
                RoundIcon(Icons.Filled.SkipPrevious, "Previous", actions.previous, size = 56.dp, enabled = session.queue.size > 1 || status.positionMs > 0)
                val playing = status.state == PlayerState.PLAYING || status.state == PlayerState.LOADING
                Box(
                    Modifier.size(80.dp).clip(CircleShape).background(Palette.Accent).clickable(onClick = actions.togglePlayPause),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (playing) "Pause" else "Play",
                        tint = Color(0xFF0A0D14),
                        modifier = Modifier.size(40.dp),
                    )
                }
                RoundIcon(Icons.Filled.SkipNext, "Next", actions.next, size = 56.dp, enabled = status.index + 1 < session.queue.size)
                RoundIcon(Icons.Filled.Refresh, "Re-send to TV", actions.retry, size = 48.dp, tint = Palette.TextSecondary)
            }

            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                SmallChip(Icons.Filled.Stop, "Stop casting", onClick = actions.stop)
                status.subtitlesEnabled?.let { on ->
                    Spacer(Modifier.width(8.dp))
                    SmallChip(if (on) Icons.Filled.ClosedCaption else Icons.Filled.ClosedCaptionDisabled, if (on) "Subtitles on" else "Subtitles off") {
                        actions.setSubtitles(!on)
                    }
                }
            }

            Spacer(Modifier.height(20.dp))

            // --- Volume ----------------------------------------------------------
            VolumeCard(status.volume, actions.setVolume)

            // --- Queue -------------------------------------------------------------
            if (session.queue.size > 1) {
                Spacer(Modifier.height(16.dp))
                SectionHeader("Up next")
                Spacer(Modifier.height(8.dp))
                session.queue.forEachIndexed { i, item ->
                    val current = i == status.index
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                            .background(if (current) Palette.SurfaceHigh else Color.Transparent)
                            .clickable { actions.jumpTo(i) }
                            .padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        MediaThumbnail(item, Modifier.size(40.dp), shape = RoundedCornerShape(10.dp), iconSize = 18.dp)
                        Spacer(Modifier.width(12.dp))
                        Text(
                            item.displayName,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal),
                            color = if (current) Palette.Cyan else Palette.TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            if (item.durationMs > 0) formatDuration(item.durationMs) else item.kind.label(),
                            style = MaterialTheme.typography.bodySmall,
                            color = Palette.TextTertiary,
                        )
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
            Text(
                "Using ${session.device.protocol.label}",
                style = MaterialTheme.typography.bodySmall,
                color = Palette.TextTertiary,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SeekBar(positionMs: Long, durationMs: Long, onSeek: (Long) -> Unit) {
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    val fraction = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    Column {
        AppSlider(
            value = if (dragging) dragValue else fraction,
            onValueChange = { dragging = true; dragValue = it },
            onValueChangeFinished = {
                dragging = false
                if (durationMs > 0) onSeek((dragValue * durationMs).toLong())
            },
            enabled = durationMs > 0,
        )
        Row(Modifier.fillMaxWidth()) {
            val shown = if (dragging) (dragValue * durationMs).toLong() else positionMs
            Text(formatDuration(shown), style = MaterialTheme.typography.labelMedium, color = Palette.TextSecondary)
            Spacer(Modifier.weight(1f))
            Text(if (durationMs > 0) formatDuration(durationMs) else "--:--", style = MaterialTheme.typography.labelMedium, color = Palette.TextSecondary)
        }
    }
}

@Composable
private fun VolumeCard(volume: Int?, onVolume: (Int) -> Unit) {
    AppCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)) {
        if (volume == null) {
            Row(Modifier.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = null, tint = Palette.TextTertiary)
                Spacer(Modifier.width(12.dp))
                Text("Volume isn't reported by this TV — use its remote", style = MaterialTheme.typography.bodySmall, color = Palette.TextTertiary)
            }
        } else {
            var local by remember(volume) { mutableFloatStateOf(volume / 100f) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { onVolume((volume - 5).coerceAtLeast(0)) }) {
                    Icon(Icons.AutoMirrored.Filled.VolumeDown, contentDescription = "Volume down", tint = Palette.TextSecondary)
                }
                AppSlider(
                    value = local,
                    onValueChange = { local = it },
                    onValueChangeFinished = { onVolume((local * 100).toInt()) },
                    modifier = Modifier.weight(1f),
                    gradient = listOf(Palette.Violet, Palette.Pink),
                )
                IconButton(onClick = { onVolume((volume + 5).coerceAtMost(100)) }) {
                    Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = "Volume up", tint = Palette.TextSecondary)
                }
                Text("${(local * 100).toInt()}", style = MaterialTheme.typography.labelLarge, color = Palette.TextSecondary, modifier = Modifier.width(32.dp), textAlign = TextAlign.End)
            }
        }
    }
}

@Composable
private fun RoundIcon(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    size: Dp,
    enabled: Boolean = true,
    tint: Color = Palette.TextPrimary,
) {
    Box(
        Modifier.size(size).clip(CircleShape)
            .background(Palette.Surface)
            .border(BorderStroke(1.dp, Palette.Outline), CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = description, tint = if (enabled) tint else Palette.TextTertiary.copy(alpha = 0.5f), modifier = Modifier.size(size * 0.48f))
    }
}

/**
 * The TV reports its position about once a second; between reports, advance the
 * clock locally so the seek bar moves smoothly.
 */
@Composable
private fun smoothPosition(reported: Long, duration: Long, playing: Boolean): Long {
    var base by remember { mutableLongStateOf(reported) }
    var baseTime by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(reported) {
        base = reported
        baseTime = System.currentTimeMillis()
        now = baseTime
    }
    LaunchedEffect(playing) {
        while (playing) {
            delay(250)
            now = System.currentTimeMillis()
        }
    }
    val pos = if (playing) base + (now - baseTime) else base
    return if (duration > 0) pos.coerceIn(0, duration) else pos
}
