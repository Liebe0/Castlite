package com.castlite.phone.ui

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.castlite.phone.media.CastMedia
import com.castlite.phone.media.MediaKind
import com.castlite.phone.net.LanStatus
import com.castlite.phone.server.RequestLogEntry
import com.castlite.phone.server.ServerState
import com.castlite.phone.ui.theme.StatusGood
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** MIME filters for the system picker: video, photos and audio; multiple selection allowed. */
private val PICKER_TYPES = arrayOf("video/*", "image/*", "audio/*")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(vm: HomeViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        vm.onMediaPicked(uris)
    }
    val pickMedia = { picker.launch(PICKER_TYPES) }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            vm.messageShown()
        }
    }

    val copy: (String) -> Unit = { url ->
        context.copyToClipboard(url)
        scope.launch { snackbar.showSnackbar("URL copied") }
    }
    val open: (String) -> Unit = { url ->
        if (!context.openUrl(url)) scope.launch { snackbar.showSnackbar("No browser installed") }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("CastLite", fontWeight = FontWeight.SemiBold) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!state.lan.isConnected) {
                item { ErrorBanner("Not connected to Wi-Fi. Join the same Wi-Fi network as your TV.") }
            }
            (state.server as? ServerState.Failed)?.let { failed ->
                item { ErrorBanner(failed.message) }
            }

            item {
                StatusCard(
                    state = state,
                    onStart = vm::startServer,
                    onStop = vm::stopServer,
                    onOpen = open,
                    onCopy = copy,
                )
            }

            item { DevicesPlaceholder() }

            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SectionTitle("Media queue", Modifier.weight(1f))
                    FilledTonalButton(onClick = pickMedia) {
                        Icon(Icons.Filled.Add, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Add media")
                    }
                }
            }

            if (state.media.isEmpty()) {
                item { EmptyMedia(onPick = pickMedia) }
            } else {
                items(state.media, key = { it.id }) { item ->
                    MediaRow(
                        item = item,
                        url = state.urlFor(item),
                        onCopy = copy,
                        onOpen = open,
                        onRemove = { vm.remove(item) },
                    )
                }
            }

            if (state.requests.isNotEmpty()) {
                item { SectionTitle("Recent requests") }
                items(state.requests) { RequestRow(it) }
            }
        }
    }
}

@Composable
private fun StatusCard(
    state: HomeUiState,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onOpen: (String) -> Unit,
    onCopy: (String) -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val lan = state.lan
            StatusLine(
                ok = lan.isConnected,
                label = when (lan.kind) {
                    LanStatus.Kind.WIFI -> "Wi-Fi · ${lan.ipAddress}"
                    LanStatus.Kind.ETHERNET -> "Ethernet · ${lan.ipAddress}"
                    LanStatus.Kind.HOTSPOT -> "Phone hotspot · ${lan.ipAddress}"
                    LanStatus.Kind.NONE -> "No Wi-Fi"
                },
            )
            val server = state.server
            StatusLine(
                ok = server is ServerState.Running,
                label = when (server) {
                    is ServerState.Running -> "Media server running on port ${server.port}"
                    is ServerState.Failed -> "Media server failed"
                    ServerState.Stopped -> "Media server stopped"
                },
            )
            // Phase 1 has no TV protocol yet; Phase 2/3 will show DLNA / CastLite receiver here.
            Text(
                "Protocol: direct HTTP (open the URL in any browser)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            state.baseUrl?.let { url ->
                UrlLine(url = url, onCopy = onCopy, onOpen = onOpen)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (server is ServerState.Running) {
                    OutlinedButton(onClick = onStop) { Text("Stop server") }
                } else {
                    Button(onClick = onStart) { Text("Start server") }
                }
            }
        }
    }
}

@Composable
private fun StatusLine(ok: Boolean, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(10.dp).background(
                if (ok) StatusGood else MaterialTheme.colorScheme.error,
                CircleShape,
            )
        )
        Spacer(Modifier.width(10.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun UrlLine(url: String, onCopy: (String) -> Unit, onOpen: (String) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        SelectionContainer(Modifier.weight(1f)) {
            Text(
                url,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        IconButton(onClick = { onCopy(url) }) { Icon(Icons.Filled.ContentCopy, "Copy URL") }
        IconButton(onClick = { onOpen(url) }) { Icon(Icons.Filled.OpenInBrowser, "Open in browser") }
    }
}

@Composable
private fun DevicesPlaceholder() {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Tv, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(12.dp))
            Column {
                Text("Devices", style = MaterialTheme.typography.titleSmall)
                Text(
                    "TV discovery (DLNA and the CastLite TV app) is added in the next phases.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun EmptyMedia(onPick: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(
            Modifier.fillMaxWidth().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Nothing shared yet", style = MaterialTheme.typography.titleMedium)
            Text(
                "Pick videos, photos or music. Each file gets a URL your TV (or a browser) can open.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onPick) { Text("Pick media") }
        }
    }
}

@Composable
private fun MediaRow(
    item: CastMedia,
    url: String?,
    onCopy: (String) -> Unit,
    onOpen: (String) -> Unit,
    onRemove: () -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(item.kind.icon(), contentDescription = item.kind.name, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(item.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "${item.mimeType} · ${formatSize(item.sizeBytes)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onRemove) { Icon(Icons.Filled.Close, "Remove") }
            }
            if (url != null) {
                UrlLine(url = url, onCopy = onCopy, onOpen = onOpen)
            } else {
                Text(
                    "Start the server and join Wi-Fi to get a URL",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun RequestRow(entry: RequestLogEntry) {
    val time = remember(entry.timeMillis) {
        SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(entry.timeMillis))
    }
    val statusColor = if (entry.status < 400) StatusGood else MaterialTheme.colorScheme.error
    Column {
        Row {
            Text("$time  ${entry.client}  ${entry.method} ", style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
            Text("${entry.status}", style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, color = statusColor)
            entry.range?.let {
                Text("  $it", style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
            }
        }
        Text(
            entry.path,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ErrorBanner(text: String) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.WifiOff, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
            Spacer(Modifier.width(12.dp))
            Text(text, color = MaterialTheme.colorScheme.onErrorContainer)
        }
    }
}

@Composable
private fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier.padding(top = 8.dp),
        style = MaterialTheme.typography.titleMedium,
        color = Color.White,
    )
}

private fun MediaKind.icon(): ImageVector = when (this) {
    MediaKind.VIDEO -> Icons.Filled.Movie
    MediaKind.IMAGE -> Icons.Filled.Image
    MediaKind.AUDIO -> Icons.Filled.AudioFile
    MediaKind.OTHER -> Icons.AutoMirrored.Filled.InsertDriveFile
}

private fun formatSize(bytes: Long): String = when {
    bytes < 0 -> "unknown size"
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.0f KB".format(bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(bytes / 1_048_576.0)
    else -> "%.2f GB".format(bytes / 1_073_741_824.0)
}

private fun Context.copyToClipboard(text: String) {
    getSystemService(ClipboardManager::class.java)
        ?.setPrimaryClip(ClipData.newPlainText("CastLite URL", text))
}

private fun Context.openUrl(url: String): Boolean = try {
    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    true
} catch (e: ActivityNotFoundException) {
    false
}
