package com.castlite.tv.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.castlite.protocol.ReceiverState
import com.castlite.tv.ReceiverInfoState
import com.castlite.tv.playback.TvUiState
import com.castlite.updater.UpdateState
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** TV-safe margins (overscan): 5% horizontally, ~5% vertically. */
private val SafeH = 56.dp
private val SafeV = 32.dp

// =====================================================================================
// Idle: "Ready to cast"
// =====================================================================================

@Composable
fun IdleScreen(info: ReceiverInfoState, lastMessage: String?, update: UpdateState = UpdateState.Idle, version: String = "") {
    Box(Modifier.fillMaxSize()) {
        AmbientBackground()
        Column(Modifier.fillMaxSize().padding(horizontal = SafeH, vertical = SafeV)) {
            // Top bar: logo + clock.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Logo(36.dp)
                Spacer(Modifier.width(14.dp))
                Text("CastLite", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = TvPalette.TextPrimary)
                if (version.isNotEmpty()) {
                    Spacer(Modifier.width(10.dp))
                    Text("v$version", fontSize = 15.sp, color = TvPalette.TextTertiary)
                }
                Spacer(Modifier.weight(1f))
                UpdatePill(update)
                Spacer(Modifier.width(20.dp))
                Clock()
            }

            Spacer(Modifier.weight(1f))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1.1f)) {
                    StatusPill(info)
                    Spacer(Modifier.height(20.dp))
                    Text(
                        "Ready to cast",
                        fontSize = 64.sp,
                        fontWeight = FontWeight.Bold,
                        color = TvPalette.TextPrimary,
                        letterSpacing = (-1).sp,
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "Open CastLite on your phone and choose “${info.deviceName}”.",
                        fontSize = 22.sp,
                        color = TvPalette.TextSecondary,
                    )
                    if (lastMessage != null) {
                        Spacer(Modifier.height(20.dp))
                        Text(lastMessage, fontSize = 18.sp, color = TvPalette.Cyan)
                    }
                }
                Spacer(Modifier.width(40.dp))
                AddressCard(info, Modifier.weight(0.9f))
            }

            Spacer(Modifier.weight(1f))

            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                StepCard(1, Icons.Filled.PhoneAndroid, "Open CastLite", "on your phone", Modifier.weight(1f))
                StepCard(2, Icons.Filled.VideoLibrary, "Pick media", "video · photo · music", Modifier.weight(1f))
                StepCard(3, Icons.Filled.Tv, "Choose this TV", info.deviceName, Modifier.weight(1f))
            }
        }
    }
}

/** Small status chip for the self-updater; OK on the remote acts on it. */
@Composable
private fun UpdatePill(update: UpdateState) {
    val (text, highlight) = when (update) {
        is UpdateState.Downloading -> "Downloading update ${(update.progress * 100).toInt()}%" to false
        is UpdateState.ReadyToInstall -> "Update ${update.version} ready · press OK" to true
        is UpdateState.NeedsPermission -> "Update ready · press OK to allow installing" to true
        is UpdateState.Installing -> "Installing update…" to false
        is UpdateState.Failed -> "Update check failed · press OK to retry" to false
        else -> return
    }
    val color = if (highlight) TvPalette.Cyan else TvPalette.TextSecondary
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.12f))
            .border(1.dp, color.copy(alpha = 0.4f), RoundedCornerShape(50))
            .padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.SystemUpdate, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, fontSize = 15.sp, color = color, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun StatusPill(info: ReceiverInfoState) {
    val ok = info.listening && info.ipAddress != null
    val color = if (ok) TvPalette.Success else TvPalette.Error
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.12f))
            .border(1.dp, color.copy(alpha = 0.35f), RoundedCornerShape(50))
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PulsingDot(color, animate = ok)
        Spacer(Modifier.width(10.dp))
        Text(
            when {
                info.serverError != null -> info.serverError
                info.ipAddress == null -> "Not connected to a network"
                info.listening -> "Waiting for your phone"
                else -> "Starting…"
            },
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
            color = color,
        )
    }
}

@Composable
private fun AddressCard(info: ReceiverInfoState, modifier: Modifier) {
    Column(
        modifier.clip(RoundedCornerShape(28.dp))
            .background(TvPalette.Surface.copy(alpha = 0.85f))
            .border(1.dp, Brush.linearGradient(listOf(TvPalette.Cyan.copy(alpha = 0.5f), TvPalette.Violet.copy(alpha = 0.5f))), RoundedCornerShape(28.dp))
            .padding(32.dp),
    ) {
        Text("TV ADDRESS", fontSize = 14.sp, letterSpacing = 2.sp, fontWeight = FontWeight.SemiBold, color = TvPalette.TextTertiary)
        Spacer(Modifier.height(10.dp))
        val ip = info.ipAddress ?: "—"
        Text(
            ip,
            // Long addresses (e.g. 192.168.100.200) get a smaller size so they stay on one line.
            fontSize = if (ip.length > 13) 32.sp else 40.sp,
            fontWeight = FontWeight.Bold,
            color = TvPalette.TextPrimary,
            maxLines = 1,
            softWrap = false,
            letterSpacing = 0.5.sp,
        )
        Spacer(Modifier.height(6.dp))
        Text("port ${info.port}", fontSize = 18.sp, fontFamily = FontFamily.Monospace, color = TvPalette.Cyan)
        Spacer(Modifier.height(18.dp))
        Text(
            "Not listed on the phone? Tap “Enter TV IP address” and type this number.",
            fontSize = 16.sp,
            color = TvPalette.TextSecondary,
        )
    }
}

@Composable
private fun StepCard(number: Int, icon: ImageVector, title: String, subtitle: String, modifier: Modifier) {
    Row(
        modifier.clip(RoundedCornerShape(22.dp)).background(TvPalette.Surface.copy(alpha = 0.7f))
            .border(1.dp, TvPalette.Outline, RoundedCornerShape(22.dp))
            .padding(20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(52.dp).clip(CircleShape).background(TvPalette.Accent),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, contentDescription = null, tint = Color(0xFF0A0D14), modifier = Modifier.size(26.dp)) }
        Spacer(Modifier.width(16.dp))
        Column {
            Text("STEP $number", fontSize = 12.sp, letterSpacing = 1.5.sp, color = TvPalette.TextTertiary, fontWeight = FontWeight.SemiBold)
            Text(title, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = TvPalette.TextPrimary)
            Text(subtitle, fontSize = 15.sp, color = TvPalette.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun Logo(size: Dp) {
    Box(
        Modifier.size(size).clip(RoundedCornerShape(size / 3.5f)).background(TvPalette.Accent),
        contentAlignment = Alignment.Center,
    ) { Icon(Icons.Filled.Cast, contentDescription = null, tint = Color(0xFF0A0D14), modifier = Modifier.size(size * 0.58f)) }
}

@Composable
private fun Clock() {
    val inPreview = LocalInspectionMode.current
    var now by remember { mutableStateOf(if (inPreview) 0L else System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (!inPreview) {
            now = System.currentTimeMillis()
            delay(15_000)
        }
    }
    val text = if (inPreview) "20:45" else SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(now))
    Text(text, fontSize = 24.sp, fontWeight = FontWeight.Medium, color = TvPalette.TextSecondary)
}

@Composable
private fun PulsingDot(color: Color, animate: Boolean) {
    val t = rememberInfiniteTransition(label = "dot")
    val a by t.animateFloat(1f, 0.35f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "a")
    Box(Modifier.size(10.dp).alpha(if (animate) a else 1f).clip(CircleShape).background(color))
}

/** Two big soft colour blobs drifting slowly behind the idle screen. */
@Composable
fun AmbientBackground(colors: List<Color> = listOf(TvPalette.Violet, TvPalette.Cyan)) {
    val t = rememberInfiniteTransition(label = "ambient")
    val drift by t.animateFloat(0f, 1f, infiniteRepeatable(tween(14_000, easing = LinearEasing), RepeatMode.Reverse), label = "drift")
    Canvas(Modifier.fillMaxSize()) {
        drawRect(Brush.verticalGradient(listOf(Color(0xFF0B1020), TvPalette.Background)))
        val w = size.width
        val h = size.height
        drawCircle(
            Brush.radialGradient(
                listOf(colors[0].copy(alpha = 0.30f), Color.Transparent),
                center = Offset(w * (0.78f + 0.08f * drift), h * (0.18f + 0.1f * drift)),
                radius = w * 0.45f,
            ),
            radius = w * 0.45f,
            center = Offset(w * (0.78f + 0.08f * drift), h * (0.18f + 0.1f * drift)),
        )
        drawCircle(
            Brush.radialGradient(
                listOf(colors[1].copy(alpha = 0.18f), Color.Transparent),
                center = Offset(w * (0.12f + 0.1f * drift), h * (0.95f - 0.1f * drift)),
                radius = w * 0.40f,
            ),
            radius = w * 0.40f,
            center = Offset(w * (0.12f + 0.1f * drift), h * (0.95f - 0.1f * drift)),
        )
    }
}

// =====================================================================================
// Video
// =====================================================================================

/** [surface] is ExoPlayer's view; the overlay with title and progress is drawn on top. */
@Composable
fun VideoScreen(ui: TvUiState, surface: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        surface()
        if (ui.state == ReceiverState.LOADING || ui.isBuffering) {
            Box(Modifier.align(Alignment.Center)) { Spinner() }
        }
        AnimatedVisibility(
            visible = ui.overlayVisible || ui.state == ReceiverState.PAUSED,
            enter = fadeIn(tween(250)),
            exit = fadeOut(tween(400)),
            modifier = Modifier.fillMaxSize(),
        ) {
            PlaybackOverlay(ui)
        }
    }
}

@Composable
private fun PlaybackOverlay(ui: TvUiState) {
    Box(Modifier.fillMaxSize()) {
        // Top scrim: title and position in the queue.
        Box(
            Modifier.fillMaxWidth().height(180.dp)
                .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.75f), Color.Transparent))),
        )
        Row(Modifier.padding(horizontal = SafeH, vertical = SafeV), verticalAlignment = Alignment.CenterVertically) {
            Logo(32.dp)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(ui.item?.title ?: "", fontSize = 26.sp, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    listOfNotNull(
                        if (ui.count > 1) "${ui.index + 1} of ${ui.count}" else null,
                        ui.phone?.let { "from phone $it" },
                    ).joinToString("  ·  "),
                    fontSize = 16.sp,
                    color = Color.White.copy(alpha = 0.7f),
                )
            }
        }

        if (ui.state == ReceiverState.PAUSED) {
            Box(
                Modifier.align(Alignment.Center).size(112.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.55f))
                    .border(2.dp, Color.White.copy(alpha = 0.25f), CircleShape),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Filled.Pause, contentDescription = "Paused", tint = Color.White, modifier = Modifier.size(56.dp)) }
        }

        // Bottom scrim: progress bar and remote hints.
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f))))
                .padding(start = SafeH, end = SafeH, bottom = SafeV, top = 80.dp),
        ) {
            ui.warning?.let { Warning(it); Spacer(Modifier.height(16.dp)) }
            ProgressBar(ui.positionMs, ui.durationMs)
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (ui.isPlaying) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(24.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text("${fmt(ui.positionMs)} / ${fmt(ui.durationMs)}", fontSize = 18.sp, color = Color.White, fontFamily = FontFamily.Monospace)
                Spacer(Modifier.weight(1f))
                Hint("OK", "play / pause")
                Spacer(Modifier.width(20.dp))
                Hint("◀ ▶", "10 s")
                Spacer(Modifier.width(20.dp))
                Hint("BACK", "stop")
            }
        }
    }
}

// =====================================================================================
// Audio
// =====================================================================================

@Composable
fun AudioScreen(ui: TvUiState) {
    Box(Modifier.fillMaxSize()) {
        AmbientBackground(listOf(TvPalette.Pink, TvPalette.Violet))
        Row(
            Modifier.fillMaxSize().padding(horizontal = SafeH * 1.5f, vertical = SafeV),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SpinningDisc(spinning = ui.isPlaying, size = 340.dp)
            Spacer(Modifier.width(64.dp))
            Column(Modifier.weight(1f)) {
                Text("NOW PLAYING", fontSize = 15.sp, letterSpacing = 2.5.sp, fontWeight = FontWeight.SemiBold, color = TvPalette.Pink)
                Spacer(Modifier.height(12.dp))
                Text(
                    ui.item?.title ?: "",
                    fontSize = 46.sp,
                    fontWeight = FontWeight.Bold,
                    color = TvPalette.TextPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    lineHeight = 52.sp,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    listOfNotNull(if (ui.count > 1) "Track ${ui.index + 1} of ${ui.count}" else null, "from your phone").joinToString("  ·  "),
                    fontSize = 20.sp,
                    color = TvPalette.TextSecondary,
                )
                Spacer(Modifier.height(40.dp))
                ui.warning?.let { Warning(it); Spacer(Modifier.height(16.dp)) }
                ProgressBar(ui.positionMs, ui.durationMs)
                Spacer(Modifier.height(12.dp))
                Row {
                    Text(fmt(ui.positionMs), fontSize = 18.sp, color = TvPalette.TextSecondary, fontFamily = FontFamily.Monospace)
                    Spacer(Modifier.weight(1f))
                    Text(fmt(ui.durationMs), fontSize = 18.sp, color = TvPalette.TextSecondary, fontFamily = FontFamily.Monospace)
                }
                Spacer(Modifier.height(32.dp))
                Row {
                    Hint("OK", if (ui.isPlaying) "pause" else "play")
                    Spacer(Modifier.width(24.dp))
                    Hint("◀ ▶", "10 s")
                    Spacer(Modifier.width(24.dp))
                    Hint("CH ▲▼", "track")
                }
            }
        }
        if (ui.state == ReceiverState.LOADING || ui.isBuffering) {
            Box(Modifier.align(Alignment.TopEnd).padding(horizontal = SafeH, vertical = SafeV)) { Spinner(36.dp) }
        }
    }
}

@Composable
private fun SpinningDisc(spinning: Boolean, size: Dp) {
    val t = rememberInfiniteTransition(label = "disc")
    val angle by t.animateFloat(0f, 360f, infiniteRepeatable(tween(9_000, easing = LinearEasing)), label = "angle")
    var frozen by remember { mutableStateOf(0f) }
    if (spinning) frozen = angle
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        // Glow
        Box(Modifier.size(size).clip(CircleShape).background(Brush.radialGradient(listOf(TvPalette.Violet.copy(alpha = 0.45f), Color.Transparent))))
        Canvas(Modifier.size(size * 0.86f).rotate(frozen)) {
            val r = this.size.minDimension / 2
            drawCircle(Brush.sweepGradient(listOf(Color(0xFF1B1530), Color(0xFF2B2050), Color(0xFF1B1530), Color(0xFF30205A), Color(0xFF1B1530))), r)
            for (i in 1..9) {
                drawCircle(Color.White.copy(alpha = 0.05f), r * (0.42f + i * 0.06f), style = Stroke(1.5f))
            }
            drawCircle(TvPalette.Pink.copy(alpha = 0.55f), r * 0.98f, style = Stroke(3f))
        }
        Box(
            Modifier.size(size * 0.34f).clip(CircleShape).background(Brush.linearGradient(listOf(TvPalette.Pink, TvPalette.Violet))),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Filled.MusicNote, contentDescription = null, tint = Color.White, modifier = Modifier.size(size * 0.15f)) }
    }
}

// =====================================================================================
// Photos
// =====================================================================================

@Composable
fun PhotoScreen(ui: TvUiState, onShown: (String) -> Unit, onFailed: (String, String?) -> Unit) {
    val url = ui.item?.url ?: return
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Crossfade(targetState = url, animationSpec = tween(700), label = "photo") { current ->
            // A new attempt number re-requests the photo after a network hiccup.
            key(ui.imageAttempt) { Box(Modifier.fillMaxSize()) {
                // Dimmed, cropped copy fills the letterbox bars with the photo's own colours.
                AsyncImage(
                    model = current,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().alpha(0.28f),
                )
                AsyncImage(
                    model = current,
                    contentDescription = ui.item.title,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                    onSuccess = { onShown(current) },
                    onError = { onFailed(current, it.result.throwable.message) },
                )
            } }
        }
        ui.warning?.let {
            Box(Modifier.align(Alignment.TopCenter).padding(top = SafeV)) { Warning(it) }
        }
        if (ui.state == ReceiverState.LOADING) Box(Modifier.align(Alignment.Center)) { Spinner() }

        AnimatedVisibility(
            visible = ui.overlayVisible || ui.state == ReceiverState.PAUSED || ui.state == ReceiverState.ENDED,
            enter = fadeIn(tween(250)),
            exit = fadeOut(tween(500)),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Row(
                Modifier.fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.8f))))
                    .padding(start = SafeH, end = SafeH, bottom = SafeV, top = 64.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(ui.item.title, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        when (ui.state) {
                            ReceiverState.PAUSED -> "Slideshow paused · press OK to continue"
                            ReceiverState.ENDED -> "End of slideshow"
                            else -> "Slideshow · ${ui.slideDurationMs / 1000} s per photo"
                        },
                        fontSize = 16.sp,
                        color = Color.White.copy(alpha = 0.7f),
                    )
                }
                Hint("◀ ▶", "photo")
                Spacer(Modifier.width(20.dp))
                Hint("OK", "pause")
                Spacer(Modifier.width(28.dp))
                if (ui.count > 1) Counter("${ui.index + 1} / ${ui.count}")
            }
        }
        // Thin progress line showing time until the next photo.
        if (ui.state == ReceiverState.PLAYING && ui.index + 1 < ui.count) {
            SlideTimer(key = ui.index, durationMs = ui.slideDurationMs, modifier = Modifier.align(Alignment.BottomStart))
        }
    }
}

@Composable
private fun SlideTimer(key: Int, durationMs: Long, modifier: Modifier) {
    var progress by remember(key) { mutableStateOf(0f) }
    LaunchedEffect(key) {
        val start = System.currentTimeMillis()
        while (progress < 1f) {
            progress = ((System.currentTimeMillis() - start).toFloat() / durationMs).coerceIn(0f, 1f)
            delay(50)
        }
    }
    Box(modifier.fillMaxWidth().height(3.dp)) {
        Box(Modifier.fillMaxWidth(progress).fillMaxHeight().background(TvPalette.AccentHorizontal))
    }
}

// =====================================================================================
// Errors
// =====================================================================================

@Composable
fun ErrorOverlay(message: String, hasNext: Boolean) {
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.7f)), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 720.dp).clip(RoundedCornerShape(28.dp)).background(TvPalette.Surface)
                .border(1.dp, TvPalette.Error.copy(alpha = 0.4f), RoundedCornerShape(28.dp))
                .padding(40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                Modifier.size(72.dp).clip(CircleShape).background(TvPalette.Error.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Filled.ErrorOutline, contentDescription = null, tint = TvPalette.Error, modifier = Modifier.size(40.dp)) }
            Spacer(Modifier.height(20.dp))
            Text("Can't play this file", fontSize = 30.sp, fontWeight = FontWeight.Bold, color = TvPalette.TextPrimary)
            Spacer(Modifier.height(12.dp))
            Text(message, fontSize = 20.sp, color = TvPalette.TextSecondary, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Spacer(Modifier.height(28.dp))
            Row {
                if (hasNext) {
                    Hint("▶", "next item")
                    Spacer(Modifier.width(28.dp))
                }
                Hint("BACK", "stop")
            }
        }
    }
}

// =====================================================================================
// Shared bits
// =====================================================================================

@Composable
private fun ProgressBar(positionMs: Long, durationMs: Long) {
    val fraction = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(Color.White.copy(alpha = 0.18f))) {
        Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().clip(RoundedCornerShape(3.dp)).background(TvPalette.AccentHorizontal))
    }
}

@Composable
private fun Hint(key: String, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.clip(RoundedCornerShape(6.dp)).background(Color.White.copy(alpha = 0.14f))
                .border(1.dp, Color.White.copy(alpha = 0.2f), RoundedCornerShape(6.dp))
                .padding(horizontal = 8.dp, vertical = 3.dp),
        ) { Text(key, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White) }
        Spacer(Modifier.width(8.dp))
        Text(label, fontSize = 15.sp, color = Color.White.copy(alpha = 0.75f))
    }
}

@Composable
private fun Counter(text: String) {
    Box(
        Modifier.clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.14f)).padding(horizontal = 14.dp, vertical = 6.dp),
    ) { Text(text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Color.White) }
}

@Composable
private fun Warning(text: String) {
    Row(
        Modifier.clip(RoundedCornerShape(12.dp)).background(Color(0x33FFC46B)).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.WarningAmber, contentDescription = null, tint = Color(0xFFFFC46B), modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Text(text, fontSize = 16.sp, color = Color(0xFFFFE2B0))
    }
}

@Composable
private fun Spinner(size: Dp = 64.dp) {
    val t = rememberInfiniteTransition(label = "spin")
    val angle by t.animateFloat(0f, 360f, infiniteRepeatable(tween(1_000, easing = LinearEasing)), label = "angle")
    Canvas(Modifier.size(size).rotate(angle)) {
        drawArc(
            brush = Brush.sweepGradient(listOf(Color.Transparent, TvPalette.Cyan, TvPalette.Violet)),
            startAngle = 0f, sweepAngle = 300f, useCenter = false,
            style = Stroke(width = size.toPx() / 12f, cap = androidx.compose.ui.graphics.StrokeCap.Round),
        )
    }
}

private fun fmt(ms: Long): String {
    if (ms <= 0) return "0:00"
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s / 60) % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}
