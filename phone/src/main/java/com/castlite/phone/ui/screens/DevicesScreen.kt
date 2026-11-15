package com.castlite.phone.ui.screens

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.castlite.phone.cast.CastDevice
import com.castlite.phone.cast.CastProtocol
import com.castlite.phone.ui.DevicesUiState
import com.castlite.phone.ui.ManualEntry
import com.castlite.phone.ui.components.AppCard
import com.castlite.phone.ui.components.ErrorCard
import com.castlite.phone.ui.components.IconDisc
import com.castlite.phone.ui.components.ProtocolBadge
import com.castlite.phone.ui.components.RadarPulse
import com.castlite.phone.ui.components.SectionHeader
import com.castlite.phone.ui.theme.Palette
import com.castlite.phone.ui.theme.color

data class DevicesActions(
    val back: () -> Unit = {},
    val select: (CastDevice) -> Unit = {},
    val showManualEntry: () -> Unit = {},
    val dismissManualEntry: () -> Unit = {},
    val addByIp: (String) -> Unit = {},
    val removeManual: (CastDevice) -> Unit = {},
)

@Composable
fun DevicesScreen(state: DevicesUiState, actions: DevicesActions) {
    Box(Modifier.fillMaxSize().background(Palette.Background)) {
        LazyColumn(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = actions.back) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Palette.TextPrimary)
                    }
                    Text("Choose a TV", style = MaterialTheme.typography.titleLarge, color = Palette.TextPrimary)
                }
            }

            item { ScanHeader(state) }

            if (!state.lan.isConnected) {
                item { ErrorCard("Connect your phone to Wi-Fi to find TVs.", icon = Icons.Filled.WifiOff) }
            }

            val groups = listOf(
                "CastLite TV app" to state.devices.filter { it.protocol == CastProtocol.CASTLITE && !it.isManual },
                "Smart TVs · DLNA" to state.devices.filter { it.protocol == CastProtocol.DLNA },
                "Added by IP" to state.devices.filter { it.isManual },
            )
            groups.filter { it.second.isNotEmpty() }.forEach { (title, devices) ->
                item(key = "h-$title") { SectionHeader(title) }
                items(devices, key = { it.id + it.isManual }) { device ->
                    DeviceRow(
                        device = device,
                        selected = device.id == state.selectedId,
                        onClick = { actions.select(device) },
                        onRemove = if (device.isManual) ({ actions.removeManual(device) }) else null,
                    )
                }
            }

            if (state.devices.isEmpty()) item { Tips() }

            item {
                OutlinedButton(
                    onClick = actions.showManualEntry,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp).height(52.dp),
                    border = BorderStroke(1.dp, Palette.OutlineStrong),
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null, tint = Palette.Cyan)
                    Spacer(Modifier.width(8.dp))
                    Text("Enter TV IP address", color = Palette.TextPrimary)
                }
            }
        }

        when (val m = state.manual) {
            is ManualEntry.Editing -> ManualIpDialog(error = m.error, checking = false, actions)
            ManualEntry.Checking -> ManualIpDialog(error = null, checking = true, actions)
            ManualEntry.Hidden -> Unit
        }
    }
}

@Composable
private fun ScanHeader(state: DevicesUiState) {
    AppCard(
        Modifier.fillMaxWidth(),
        color = Palette.Surface,
        contentPadding = PaddingValues(20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadarPulse(Modifier.size(84.dp), color = if (state.lan.isConnected) Palette.Cyan else Palette.TextTertiary) {
                Box(
                    Modifier.size(40.dp).clip(CircleShape).background(Palette.Accent),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Filled.Wifi, contentDescription = null, tint = Color(0xFF0A0D14), modifier = Modifier.size(22.dp)) }
            }
            Spacer(Modifier.width(16.dp))
            Column {
                Text(
                    when {
                        !state.lan.isConnected -> "Waiting for Wi-Fi"
                        state.devices.isEmpty() -> "Looking for TVs…"
                        state.devices.size == 1 -> "1 TV found"
                        else -> "${state.devices.size} TVs found"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    color = Palette.TextPrimary,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    state.lan.ipAddress?.let { "Searching the network from $it. Keep the TV switched on." }
                        ?: "Discovery starts when the phone joins Wi-Fi.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Palette.TextSecondary,
                )
            }
        }
    }
}

@Composable
private fun DeviceRow(device: CastDevice, selected: Boolean, onClick: () -> Unit, onRemove: (() -> Unit)?) {
    AppCard(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
        color = if (selected) Palette.SurfaceHigh else Palette.Surface,
        border = if (selected) {
            BorderStroke(1.dp, Brush.linearGradient(listOf(Palette.Cyan, Palette.Violet)))
        } else {
            BorderStroke(1.dp, Palette.Outline)
        },
        shape = MaterialTheme.shapes.medium,
        contentPadding = PaddingValues(start = 14.dp, top = 12.dp, bottom = 12.dp, end = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconDisc(Icons.Filled.Tv, device.protocol.color)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(device.name, style = MaterialTheme.typography.titleSmall, color = Palette.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ProtocolBadge(device.protocol)
                    Spacer(Modifier.width(8.dp))
                    Text(device.details, style = MaterialTheme.typography.bodySmall, color = Palette.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (selected) {
                Icon(Icons.Filled.CheckCircle, contentDescription = "Selected", tint = Palette.Cyan, modifier = Modifier.padding(horizontal = 8.dp))
            }
            if (onRemove != null) {
                IconButton(onClick = onRemove) { Icon(Icons.Filled.Close, contentDescription = "Forget", tint = Palette.TextTertiary) }
            }
        }
    }
}

@Composable
private fun Tips() {
    AppCard(Modifier.fillMaxWidth(), color = Palette.Surface) {
        Text("Don't see your TV?", style = MaterialTheme.typography.titleSmall, color = Palette.TextPrimary)
        Spacer(Modifier.height(10.dp))
        listOf(
            "Phone and TV must be on the same Wi-Fi network (not a guest network).",
            "LG webOS: turn on Settings → General → Devices → \"LG Connect Apps\" (older models: Network → LG Connect Apps).",
            "Android TV: install and open the CastLite TV app. It shows its IP address if you need to add it by hand.",
            "Samsung / Sony / Philips: DLNA is usually on by default. Wake the TV first.",
        ).forEach { tip ->
            Row(Modifier.padding(vertical = 4.dp)) {
                Box(Modifier.padding(top = 7.dp).size(6.dp).clip(CircleShape).background(Palette.Violet))
                Spacer(Modifier.width(10.dp))
                Text(tip, style = MaterialTheme.typography.bodySmall, color = Palette.TextSecondary)
            }
        }
    }
}

@Composable
private fun ManualIpDialog(error: String?, checking: Boolean, actions: DevicesActions) {
    var text by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = actions.dismissManualEntry,
        containerColor = Palette.SurfaceHigh,
        title = { Text("Add a TV by IP") },
        text = {
            Column {
                Text(
                    "Open CastLite on the TV — it shows its address on the start screen.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Palette.TextSecondary,
                )
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.trim() },
                    placeholder = { Text("192.168.1.50") },
                    singleLine = true,
                    enabled = !checking,
                    isError = error != null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { actions.addByIp(text) }),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Palette.Cyan, cursorColor = Palette.Cyan),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (error != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(error, color = Palette.Error, style = MaterialTheme.typography.bodySmall)
                }
                if (checking) {
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Palette.Cyan)
                        Spacer(Modifier.width(10.dp))
                        Text("Checking…", style = MaterialTheme.typography.bodySmall, color = Palette.TextSecondary)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { actions.addByIp(text) }, enabled = !checking && text.isNotBlank()) { Text("Add TV", color = Palette.Cyan) }
        },
        dismissButton = {
            TextButton(onClick = actions.dismissManualEntry) { Text("Cancel", color = Palette.TextSecondary) }
        },
    )
}
