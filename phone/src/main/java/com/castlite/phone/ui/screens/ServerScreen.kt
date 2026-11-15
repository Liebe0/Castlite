package com.castlite.phone.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.castlite.phone.net.LanStatus
import com.castlite.phone.server.RequestLogEntry
import com.castlite.phone.server.ServerState
import com.castlite.phone.ui.MainUiState
import com.castlite.phone.ui.components.AppCard
import com.castlite.phone.ui.components.GradientButton
import com.castlite.phone.ui.components.SectionHeader
import com.castlite.phone.ui.components.StatusDot
import com.castlite.phone.ui.theme.Palette
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ServerActions(
    val back: () -> Unit = {},
    val start: () -> Unit = {},
    val stop: () -> Unit = {},
    val copy: (String) -> Unit = {},
    val open: (String) -> Unit = {},
    val allowBackground: () -> Unit = {},
)

/** Diagnostics: the phone's media server, its address, and what TVs have requested. */
@Composable
fun ServerScreen(state: MainUiState, actions: ServerActions) {
    Box(Modifier.fillMaxSize().background(Palette.Background)) {
        LazyColumn(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = actions.back) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Palette.TextPrimary)
                    }
                    Text("Network & server", style = MaterialTheme.typography.titleLarge, color = Palette.TextPrimary)
                }
            }
            item {
                AppCard(Modifier.fillMaxWidth()) {
                    val lan = state.lan
                    StatusRow(
                        ok = lan.isConnected,
                        title = when (lan.kind) {
                            LanStatus.Kind.WIFI -> "Wi-Fi"
                            LanStatus.Kind.ETHERNET -> "Ethernet"
                            LanStatus.Kind.HOTSPOT -> "Phone hotspot"
                            LanStatus.Kind.NONE -> "No network"
                        },
                        detail = lan.ipAddress ?: "Not connected",
                    )
                    Spacer(Modifier.height(12.dp))
                    val server = state.server
                    StatusRow(
                        ok = server is ServerState.Running,
                        title = "Media server",
                        detail = when (server) {
                            is ServerState.Running -> "Running on port ${server.port}"
                            is ServerState.Failed -> server.message
                            ServerState.Stopped -> "Stopped"
                        },
                    )
                    Spacer(Modifier.height(12.dp))
                    val p = state.power
                    StatusRow(
                        ok = p.unrestrictedBattery && !p.backgroundRestricted,
                        title = "Casting with the screen off",
                        detail = when {
                            p.backgroundRestricted -> "Blocked: background use is restricted for CastLite"
                            !p.unrestrictedBattery -> "Battery optimization may pause CastLite on long videos"
                            else -> "Allowed — CastLite keeps running when the phone locks"
                        },
                    )
                    if (p.backgroundRestricted || !p.unrestrictedBattery) {
                        TextButton(onClick = actions.allowBackground, modifier = Modifier.padding(start = 14.dp)) {
                            Text(if (p.backgroundRestricted) "Open settings" else "Allow background use", color = Palette.Cyan)
                        }
                    }
                    state.baseUrl?.let { url ->
                        Spacer(Modifier.height(16.dp))
                        Row(
                            Modifier.fillMaxWidth().background(Palette.SurfaceHigh, MaterialTheme.shapes.small).padding(start = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            SelectionContainer(Modifier.weight(1f)) {
                                Text(url, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium, color = Palette.Cyan)
                            }
                            IconButton(onClick = { actions.copy(url) }) { Icon(Icons.Filled.ContentCopy, "Copy URL", tint = Palette.TextSecondary) }
                            IconButton(onClick = { actions.open(url) }) { Icon(Icons.Filled.OpenInBrowser, "Open in browser", tint = Palette.TextSecondary) }
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Open this address in any browser on the same Wi-Fi to test playback without a TV.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Palette.TextTertiary,
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                    if (state.server is ServerState.Running) {
                        OutlinedButton(onClick = actions.stop, modifier = Modifier.fillMaxWidth()) { Text("Stop server and casting", color = Palette.TextPrimary) }
                    } else {
                        GradientButton("Start server", actions.start, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
            if (state.media.isNotEmpty() && state.baseUrl != null) {
                item { SectionHeader("File URLs") }
                items(state.media, key = { "u" + it.id }) { m ->
                    val url = state.urlFor(m) ?: return@items
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(m.displayName, style = MaterialTheme.typography.bodyMedium, color = Palette.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(m.mimeType, style = MaterialTheme.typography.bodySmall, color = Palette.TextTertiary)
                        }
                        IconButton(onClick = { actions.copy(url) }) { Icon(Icons.Filled.ContentCopy, "Copy URL", tint = Palette.TextSecondary) }
                    }
                }
            }
            item { SectionHeader("Recent requests") }
            if (state.requests.isEmpty()) {
                item { Text("Nothing has been requested yet.", style = MaterialTheme.typography.bodySmall, color = Palette.TextTertiary) }
            } else {
                items(state.requests) { RequestRow(it) }
            }
        }
    }
}

@Composable
private fun StatusRow(ok: Boolean, title: String, detail: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        StatusDot(if (ok) Palette.Success else Palette.Error, size = 10.dp)
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleSmall, color = Palette.TextPrimary)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = Palette.TextSecondary)
        }
    }
}

@Composable
private fun RequestRow(entry: RequestLogEntry) {
    val time = remember(entry.timeMillis) { SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(entry.timeMillis)) }
    val ok = entry.status < 400
    AppCard(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.small, contentPadding = PaddingValues(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${entry.status}",
                style = MaterialTheme.typography.labelLarge,
                fontFamily = FontFamily.Monospace,
                color = if (ok) Palette.Success else Palette.Error,
            )
            Spacer(Modifier.width(10.dp))
            Text("${entry.method}  ${entry.range ?: "full"}", style = MaterialTheme.typography.labelMedium, fontFamily = FontFamily.Monospace, color = Palette.TextPrimary, modifier = Modifier.weight(1f))
            Text(time, style = MaterialTheme.typography.labelSmall, color = Palette.TextTertiary)
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "${entry.client}  ${entry.path}",
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = Palette.TextTertiary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
