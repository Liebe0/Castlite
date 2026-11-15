package com.castlite.phone.ui.screens

import com.castlite.phone.ui.RouterScan
import androidx.compose.material.icons.filled.Router
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
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.QrCodeScanner
import com.castlite.phone.ui.components.GradientButton
import androidx.compose.material.icons.filled.AddCircleOutline
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.remember
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
    val toggleExtra: (CastDevice) -> Unit = {},
    /** Returns an error message, or null when saved. */
    val saveMac: (CastDevice, String) -> String? = { _, _ -> null },
    val wake: (CastDevice) -> Unit = {},
    val scan: () -> Unit = {},
    val searchOtherRouters: () -> Unit = {},
)

@Composable
fun DevicesScreen(state: DevicesUiState, actions: DevicesActions) {
    var powerFor by remember { mutableStateOf<CastDevice?>(null) }
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

            val local = state.devices.filterNot { it.otherRouter }
            val groups = listOf(
                "CastLite TV app" to local.filter { it.protocol == CastProtocol.CASTLITE && !it.isManual },
                "Smart TVs · DLNA" to local.filter { it.protocol == CastProtocol.DLNA && !it.isManual },
                "Added by IP" to local.filter { it.isManual },
                "On other routers" to state.devices.filter { it.otherRouter },
            )
            groups.filter { it.second.isNotEmpty() }.forEach { (title, devices) ->
                item(key = "h-$title") { SectionHeader(title) }
                items(devices, key = { it.id + it.isManual }) { device ->
                    DeviceRow(
                        device = device,
                        selected = device.id == state.selectedId,
                        alsoPlaying = device.id in state.extraIds,
                        canPlayAlong = state.selectedId != null && device.id != state.selectedId,
                        hasMac = state.macs.containsKey(device.id),
                        onClick = { actions.select(device) },
                        onToggleExtra = { actions.toggleExtra(device) },
                        onPower = { powerFor = device },
                        onRemove = if (device.isManual || device.otherRouter) ({ actions.removeManual(device) }) else null,
                    )
                }
            }

            if (state.devices.size > 1 && state.selectedId != null) {
                item {
                    Text(
                        "Tip: tap + on another TV to play the same thing there too.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Palette.TextTertiary,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
            }
            if (state.devices.isEmpty()) item { Tips() }

            if (state.lan.isConnected) item { OtherRoutersCard(state.scan, actions.searchOtherRouters) }

            item {
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GradientButton("Scan TV code", actions.scan, icon = Icons.Filled.QrCodeScanner, modifier = Modifier.weight(1f))
                    OutlinedButton(
                        onClick = actions.showManualEntry,
                        modifier = Modifier.weight(1f).height(54.dp),
                        border = BorderStroke(1.dp, Palette.OutlineStrong),
                    ) {
                        Icon(Icons.Filled.Keyboard, contentDescription = null, tint = Palette.Cyan)
                        Spacer(Modifier.width(8.dp))
                        Text("Enter IP", color = Palette.TextPrimary)
                    }
                }
            }
        }

        powerFor?.let { device ->
            PowerDialog(
                device = device,
                currentMac = state.macs[device.id],
                onSave = { text -> actions.saveMac(device, text).also { if (it == null) powerFor = null } },
                onWake = { actions.wake(device); powerFor = null },
                onDismiss = { powerFor = null },
            )
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

/** "TVs on another router?" — searches the networks above the phone's router. */
@Composable
private fun OtherRoutersCard(scan: RouterScan, onSearch: () -> Unit) {
    AppCard(
        Modifier.fillMaxWidth(),
        color = Palette.Surface,
        border = BorderStroke(1.dp, Palette.Outline),
        shape = MaterialTheme.shapes.medium,
        contentPadding = PaddingValues(start = 14.dp, top = 12.dp, bottom = 12.dp, end = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconDisc(Icons.Filled.Router, Palette.Violet)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("TVs on another router?", style = MaterialTheme.typography.titleSmall, color = Palette.TextPrimary)
                Spacer(Modifier.height(2.dp))
                Text(
                    scan.message ?: "Several routers in a row? CastLite can look past yours — no need to switch Wi-Fi.",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (scan.running) Palette.Cyan else Palette.TextSecondary,
                )
            }
            if (scan.running) {
                CircularProgressIndicator(Modifier.padding(horizontal = 12.dp).size(22.dp), color = Palette.Cyan, strokeWidth = 2.dp)
            } else {
                TextButton(onClick = onSearch) { Text("Search", color = Palette.Cyan) }
            }
        }
    }
}

@Composable
private fun DeviceRow(
    device: CastDevice,
    selected: Boolean,
    alsoPlaying: Boolean,
    canPlayAlong: Boolean,
    hasMac: Boolean,
    onClick: () -> Unit,
    onToggleExtra: () -> Unit,
    onPower: () -> Unit,
    onRemove: (() -> Unit)?,
) {
    AppCard(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
        color = if (selected || alsoPlaying) Palette.SurfaceHigh else Palette.Surface,
        border = when {
            selected -> BorderStroke(1.dp, Brush.linearGradient(listOf(Palette.Cyan, Palette.Violet)))
            alsoPlaying -> BorderStroke(1.dp, Palette.Cyan.copy(alpha = 0.4f))
            else -> BorderStroke(1.dp, Palette.Outline)
        },
        shape = MaterialTheme.shapes.medium,
        contentPadding = PaddingValues(start = 14.dp, top = 12.dp, bottom = 12.dp, end = 0.dp),
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
                    Text(
                        when {
                            alsoPlaying -> "Also plays here"
                            device.otherRouter -> device.host
                            else -> device.details
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (alsoPlaying) Palette.Cyan else Palette.TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (selected) {
                Icon(Icons.Filled.CheckCircle, contentDescription = "Selected", tint = Palette.Cyan, modifier = Modifier.padding(horizontal = 4.dp))
            } else if (canPlayAlong) {
                IconButton(onClick = onToggleExtra) {
                    Icon(
                        if (alsoPlaying) Icons.Filled.CheckCircle else Icons.Filled.AddCircleOutline,
                        contentDescription = if (alsoPlaying) "Stop playing here too" else "Also play here",
                        tint = if (alsoPlaying) Palette.Cyan else Palette.TextTertiary,
                    )
                }
            }
            var menu by remember { mutableStateOf(false) }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More", tint = Palette.TextTertiary) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(if (hasMac) "Power on (Wake-on-LAN)" else "Set up power on") },
                        leadingIcon = { Icon(Icons.Filled.PowerSettingsNew, null) },
                        onClick = { menu = false; onPower() },
                    )
                    if (onRemove != null) {
                        DropdownMenuItem(text = { Text("Forget this TV") }, leadingIcon = { Icon(Icons.Filled.Close, null) }, onClick = { menu = false; onRemove() })
                    }
                }
            }
        }
    }
}

/** Saves a TV's MAC address and switches it on with Wake-on-LAN. */
@Composable
private fun PowerDialog(device: CastDevice, currentMac: String?, onSave: (String) -> String?, onWake: () -> Unit, onDismiss: () -> Unit) {
    var text by rememberSaveable { mutableStateOf(currentMac.orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Palette.SurfaceHigh,
        title = { Text("Turn on ${device.name}") },
        text = {
            Column {
                Text(
                    "CastLite can switch the TV on over Wi-Fi. Enter its MAC address once — find it on the TV under " +
                        "Settings → Network (LG: Network → Wi-Fi → Advanced). Also turn on \"Turn on via Wi-Fi\" (LG) or " +
                        "\"Network standby\" (Android TV).",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Palette.TextSecondary,
                )
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.trim(); error = null },
                    placeholder = { Text("A1:B2:C3:D4:E5:F6") },
                    singleLine = true,
                    isError = error != null,
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Palette.Cyan, cursorColor = Palette.Cyan),
                    modifier = Modifier.fillMaxWidth(),
                )
                error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = Palette.Error, style = MaterialTheme.typography.bodySmall)
                }
                if (currentMac != null) {
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(onClick = onWake, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Filled.PowerSettingsNew, contentDescription = null, tint = Palette.Cyan)
                        Spacer(Modifier.width(8.dp))
                        Text("Turn on now", color = Palette.TextPrimary)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { error = onSave(text) }) { Text("Save", color = Palette.Cyan) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close", color = Palette.TextSecondary) } },
    )
}

@Composable
private fun Tips() {
    AppCard(Modifier.fillMaxWidth(), color = Palette.Surface) {
        Text("Don't see your TV?", style = MaterialTheme.typography.titleSmall, color = Palette.TextPrimary)
        Spacer(Modifier.height(10.dp))
        listOf(
            "Phone and TV must be on the same Wi-Fi network (not a guest network).",
            "LG webOS: turn on Settings → General → Devices → \"LG Connect Apps\" (older models: Network → LG Connect Apps).",
            "Android TV: install and open the CastLite TV app, then tap \"Scan TV code\" and point at the code on the TV.",
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
                    "Open CastLite on the TV — its address is on the start screen (or scan the code there instead).",
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
