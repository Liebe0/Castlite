package com.castlite.phone.ui.screens

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Slideshow
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.castlite.phone.cast.CastDevice
import com.castlite.phone.cast.PlayerState
import com.castlite.phone.media.CastMedia
import com.castlite.phone.media.MediaKind
import com.castlite.phone.server.ServerState
import com.castlite.phone.ui.MainUiState
import com.castlite.phone.ui.components.AppCard
import com.castlite.phone.ui.components.EmptyState
import com.castlite.phone.ui.components.ErrorCard
import com.castlite.phone.ui.components.GradientButton
import com.castlite.phone.ui.components.IconDisc
import com.castlite.phone.ui.components.MediaThumbnail
import com.castlite.phone.ui.components.ProtocolBadge
import com.castlite.phone.ui.components.SectionHeader
import com.castlite.phone.ui.components.StatusDot
import com.castlite.phone.ui.components.formatDuration
import com.castlite.phone.ui.components.formatSize
import com.castlite.phone.ui.components.label
import com.castlite.phone.ui.theme.Palette
import com.castlite.phone.ui.theme.color

/** Callbacks of the home screen, grouped so previews and tests can pass no-ops. */
data class HomeActions(
    val pickMedia: () -> Unit = {},
    val chooseDevice: () -> Unit = {},
    val cast: (index: Int) -> Unit = {},
    val openPlayer: () -> Unit = {},
    val openServer: () -> Unit = {},
    val remove: (CastMedia) -> Unit = {},
    val move: (CastMedia, Int) -> Unit = { _, _ -> },
    val clear: () -> Unit = {},
    val setSlideDuration: (Long) -> Unit = {},
    val togglePlayPause: () -> Unit = {},
    val allowBackground: () -> Unit = {},
    val dismissBackgroundPrompt: () -> Unit = {},
)

private val SLIDE_OPTIONS = listOf(3_000L, 5_000L, 8_000L, 15_000L)

@Composable
fun HomeScreen(state: MainUiState, actions: HomeActions) {
    val session = state.session
    Box(Modifier.fillMaxSize().background(Palette.Background)) {
        // Soft coloured glow at the top of the screen.
        Box(
            Modifier.fillMaxWidth().height(320.dp).background(
                Brush.radialGradient(
                    listOf(Palette.Violet.copy(alpha = 0.22f), Color.Transparent),
                    center = androidx.compose.ui.geometry.Offset(900f, -100f),
                    radius = 1100f,
                ),
            ),
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize().statusBarsPadding(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 140.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { Header(state, actions.openServer) }

            if (!state.lan.isConnected) {
                item { ErrorCard("Not connected to Wi-Fi. Join the same network as your TV.", icon = Icons.Filled.WifiOff) }
            }
            (state.server as? ServerState.Failed)?.let { item { ErrorCard(it.message) } }

            item { DeviceCard(state.selectedDevice, actions.chooseDevice) }

            if (state.showBackgroundPrompt) {
                item { BackgroundPrompt(state.power.backgroundRestricted, actions.allowBackground, actions.dismissBackgroundPrompt) }
            }

            item {
                SectionHeader(if (state.media.isEmpty()) "Queue" else "Queue · ${state.media.size}") {
                    if (state.media.any { it.kind == MediaKind.IMAGE }) {
                        SlideshowChip(state.slideDurationMs, actions.setSlideDuration)
                        Spacer(Modifier.width(4.dp))
                    }
                    if (state.media.isNotEmpty()) {
                        IconButton(onClick = actions.pickMedia) {
                            Icon(Icons.Filled.Add, contentDescription = "Add media", tint = Palette.Cyan)
                        }
                    }
                }
            }

            if (state.media.isEmpty()) {
                item {
                    AppCard(Modifier.fillMaxWidth()) {
                        EmptyState(
                            icon = Icons.Filled.VideoLibrary,
                            title = "Pick something to cast",
                            body = "Videos, photos and music from your phone. Pick several to play them as a queue or a slideshow.",
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            GradientButton("Add media", actions.pickMedia, icon = Icons.Filled.Add)
                        }
                    }
                }
            } else {
                itemsIndexed(state.media, key = { _, m -> m.id }) { index, item ->
                    val isCurrent = session != null && session.current?.id == item.id
                    QueueRow(
                        item = item,
                        isCurrent = isCurrent,
                        isPlaying = isCurrent && session?.status?.state == PlayerState.PLAYING,
                        isFirst = index == 0,
                        isLast = index == state.media.lastIndex,
                        onClick = { if (state.selectedDevice == null) actions.chooseDevice() else actions.cast(index) },
                        onRemove = { actions.remove(item) },
                        onMove = { actions.move(item, it) },
                    )
                }
                item {
                    Text(
                        "Tap an item to start casting from it",
                        style = MaterialTheme.typography.bodySmall,
                        color = Palette.TextTertiary,
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
            }
        }

        // Bottom: mini player while casting, otherwise the main "Cast" button.
        Box(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Palette.Background, Palette.Background)))
                .navigationBarsPadding()
                .padding(start = 16.dp, end = 16.dp, top = 28.dp, bottom = 12.dp),
        ) {
            when {
                session != null -> MiniPlayer(state, actions.openPlayer, actions.togglePlayPause)
                state.media.isNotEmpty() -> GradientButton(
                    text = if (state.selectedDevice == null) "Choose a TV to cast to" else "Cast ${countLabel(state.media)} to ${state.selectedDevice.name}",
                    onClick = { if (state.selectedDevice == null) actions.chooseDevice() else actions.cast(0) },
                    icon = Icons.Filled.Cast,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

private fun countLabel(media: List<CastMedia>) = if (media.size == 1) "1 item" else "${media.size} items"

@Composable
private fun Header(state: MainUiState, onOpenServer: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(Palette.Accent),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Filled.Cast, contentDescription = null, tint = Color(0xFF0A0D14), modifier = Modifier.size(24.dp)) }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("CastLite", style = MaterialTheme.typography.headlineSmall, color = Palette.TextPrimary)
            Text("Your phone, on the big screen", style = MaterialTheme.typography.bodySmall, color = Palette.TextSecondary)
        }
        val running = state.server is ServerState.Running
        val ok = running && state.lan.isConnected
        Row(
            Modifier.clip(RoundedCornerShape(50))
                .background(Palette.Surface)
                .border(1.dp, Palette.Outline, RoundedCornerShape(50))
                .clickable(onClick = onOpenServer)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusDot(if (ok) Palette.Success else if (state.lan.isConnected) Palette.TextTertiary else Palette.Error)
            Spacer(Modifier.width(8.dp))
            Text(
                when {
                    !state.lan.isConnected -> "Offline"
                    running -> "Ready"
                    else -> "Idle"
                },
                style = MaterialTheme.typography.labelMedium,
                color = Palette.TextSecondary,
            )
        }
    }
}

@Composable
private fun DeviceCard(device: CastDevice?, onClick: () -> Unit) {
    AppCard(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
        border = if (device != null) BorderStroke(1.dp, Brush.linearGradient(listOf(Palette.Cyan.copy(alpha = 0.6f), Palette.Violet.copy(alpha = 0.6f)))) else BorderStroke(1.dp, Palette.Outline),
        color = Palette.Surface,
        contentPadding = PaddingValues(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconDisc(Icons.Filled.Tv, device?.protocol?.color ?: Palette.Violet, size = 52.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (device != null) "CASTING TO" else "NO TV SELECTED",
                    style = MaterialTheme.typography.labelSmall,
                    color = Palette.TextTertiary,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    device?.name ?: "Choose a TV",
                    style = MaterialTheme.typography.titleMedium,
                    color = Palette.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                if (device != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ProtocolBadge(device.protocol)
                        Spacer(Modifier.width(8.dp))
                        Text(device.details, style = MaterialTheme.typography.bodySmall, color = Palette.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                } else {
                    Text("LG, Samsung, Sony via DLNA, or any Android TV with CastLite", style = MaterialTheme.typography.bodySmall, color = Palette.TextSecondary)
                }
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Change TV", tint = Palette.TextTertiary)
        }
    }
}

/** Asks to exempt CastLite from battery optimization so casting survives a locked phone. */
@Composable
private fun BackgroundPrompt(restricted: Boolean, onAllow: () -> Unit, onDismiss: () -> Unit) {
    AppCard(
        Modifier.fillMaxWidth(),
        color = Palette.Surface,
        border = BorderStroke(1.dp, (if (restricted) Palette.Error else Palette.Warning).copy(alpha = 0.4f)),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            IconDisc(Icons.Filled.Bedtime, if (restricted) Palette.Error else Palette.Warning, size = 44.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (restricted) "Background use is blocked" else "Keep casting when your phone locks",
                    style = MaterialTheme.typography.titleSmall,
                    color = Palette.TextPrimary,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    if (restricted) {
                        "Android will stop CastLite soon after the screen turns off. Open settings → Battery → allow background use."
                    } else {
                        "Allow CastLite to run in the background so long videos don't stop after the screen turns off."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = Palette.TextSecondary,
                )
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.clip(RoundedCornerShape(50)).background(Palette.AccentHorizontal)
                            .clickable(onClick = onAllow).padding(horizontal = 16.dp, vertical = 8.dp),
                    ) {
                        Text(if (restricted) "Open settings" else "Allow", style = MaterialTheme.typography.labelLarge, color = Color(0xFF0A0D14))
                    }
                    if (!restricted) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Not now",
                            style = MaterialTheme.typography.labelLarge,
                            color = Palette.TextSecondary,
                            modifier = Modifier.clip(RoundedCornerShape(50)).clickable(onClick = onDismiss).padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SlideshowChip(current: Long, onChange: (Long) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.clip(RoundedCornerShape(50))
                .background(Palette.Surface)
                .border(1.dp, Palette.Outline, RoundedCornerShape(50))
                .clickable { open = true }
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Slideshow, contentDescription = null, tint = Palette.TextSecondary, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("${current / 1000}s / photo", style = MaterialTheme.typography.labelMedium, color = Palette.TextSecondary)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            SLIDE_OPTIONS.forEach { ms ->
                DropdownMenuItem(
                    text = { Text("${ms / 1000} seconds per photo") },
                    onClick = { onChange(ms); open = false },
                )
            }
        }
    }
}

@Composable
private fun QueueRow(
    item: CastMedia,
    isCurrent: Boolean,
    isPlaying: Boolean,
    isFirst: Boolean,
    isLast: Boolean,
    onClick: () -> Unit,
    onRemove: () -> Unit,
    onMove: (Int) -> Unit,
) {
    AppCard(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
        color = if (isCurrent) Palette.SurfaceHigh else Palette.Surface,
        border = BorderStroke(1.dp, if (isCurrent) Palette.Cyan.copy(alpha = 0.5f) else Palette.Outline),
        shape = MaterialTheme.shapes.medium,
        contentPadding = PaddingValues(start = 10.dp, top = 10.dp, bottom = 10.dp, end = 0.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MediaThumbnail(item, Modifier.size(56.dp))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    item.displayName,
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                    color = Palette.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                val parts = buildList {
                    add(item.kind.label())
                    if (item.durationMs > 0) add(formatDuration(item.durationMs))
                    if (item.sizeBytes >= 0) add(formatSize(item.sizeBytes))
                }
                Text(parts.joinToString("  ·  "), style = MaterialTheme.typography.bodySmall, color = Palette.TextSecondary)
            }
            if (isCurrent) Equalizer(animate = isPlaying)
            var menu by remember { mutableStateOf(false) }
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "More", tint = Palette.TextTertiary)
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Play from here") }, leadingIcon = { Icon(Icons.Filled.PlayArrow, null) }, onClick = { menu = false; onClick() })
                    if (!isFirst) DropdownMenuItem(text = { Text("Move up") }, leadingIcon = { Icon(Icons.Filled.ArrowUpward, null) }, onClick = { menu = false; onMove(-1) })
                    if (!isLast) DropdownMenuItem(text = { Text("Move down") }, leadingIcon = { Icon(Icons.Filled.ArrowDownward, null) }, onClick = { menu = false; onMove(1) })
                    DropdownMenuItem(text = { Text("Remove") }, leadingIcon = { Icon(Icons.Filled.DeleteOutline, null) }, onClick = { menu = false; onRemove() })
                }
            }
        }
    }
}

/** Three bouncing bars marking the item that's on the TV. */
@Composable
private fun Equalizer(animate: Boolean) {
    val transition = rememberInfiniteTransition(label = "eq")
    val heights = listOf(0, 180, 360).map { delayMs ->
        transition.animateFloat(
            initialValue = 0.3f, targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(520, delayMillis = delayMs), RepeatMode.Reverse),
            label = "bar$delayMs",
        )
    }
    Row(Modifier.height(18.dp).padding(end = 4.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        heights.forEachIndexed { i, h ->
            val fraction = if (animate) h.value else listOf(0.5f, 0.8f, 0.4f)[i]
            Box(Modifier.width(3.dp).fillMaxHeight(fraction).clip(RoundedCornerShape(2.dp)).background(Palette.Cyan))
        }
    }
}

@Composable
private fun MiniPlayer(state: MainUiState, onOpen: () -> Unit, onToggle: () -> Unit) {
    val session = state.session ?: return
    val media = session.current
    val status = session.status
    Surface(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).clickable(onClick = onOpen),
        color = Palette.SurfaceHigh,
        shape = RoundedCornerShape(24.dp),
        border = BorderStroke(1.dp, Palette.OutlineStrong),
        shadowElevation = 12.dp,
    ) {
        Column {
            val progress = if (status.durationMs > 0) (status.positionMs.toFloat() / status.durationMs).coerceIn(0f, 1f) else 0f
            Box(Modifier.fillMaxWidth().height(3.dp).background(Palette.Outline)) {
                Box(Modifier.fillMaxWidth(progress).fillMaxHeight().background(Palette.AccentHorizontal))
            }
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                if (media != null) MediaThumbnail(media, Modifier.size(48.dp), iconSize = 20.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(media?.displayName ?: "…", style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold), color = Palette.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusDot(if (session.networkProblem != null) Palette.Error else stateColor(status.state), size = 6.dp)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            if (session.networkProblem != null) "Waiting for Wi-Fi…" else "${stateLabel(status.state)} on ${session.device.name}",
                            style = MaterialTheme.typography.bodySmall,
                            color = Palette.TextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Box(
                    Modifier.size(44.dp).clip(CircleShape).background(Palette.Accent).clickable(onClick = onToggle),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (status.state == PlayerState.PLAYING || status.state == PlayerState.LOADING) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = "Play/pause",
                        tint = Color(0xFF0A0D14),
                    )
                }
            }
        }
    }
}

fun stateLabel(state: PlayerState): String = when (state) {
    PlayerState.IDLE -> "Stopped"
    PlayerState.CONNECTING -> "Connecting"
    PlayerState.LOADING -> "Loading"
    PlayerState.PLAYING -> "Playing"
    PlayerState.PAUSED -> "Paused"
    PlayerState.ENDED -> "Finished"
    PlayerState.ERROR -> "Problem"
}

fun stateColor(state: PlayerState): Color = when (state) {
    PlayerState.PLAYING -> Palette.Success
    PlayerState.LOADING, PlayerState.CONNECTING -> Palette.Warning
    PlayerState.ERROR -> Palette.Error
    else -> Palette.TextTertiary
}
