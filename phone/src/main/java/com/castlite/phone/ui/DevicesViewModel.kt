package com.castlite.phone.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.castlite.phone.cast.CastDevice
import com.castlite.phone.cast.CastManager
import com.castlite.phone.cast.DeviceRepository
import com.castlite.phone.cast.receiver.ReceiverDiscovery
import com.castlite.phone.net.LanMonitor
import com.castlite.phone.net.LanStatus
import com.castlite.phone.net.NetworkScanner
import com.castlite.phone.net.Subnets
import com.castlite.phone.net.WakeOnLan
import com.castlite.protocol.ReceiverProtocol
import com.castlite.protocol.TvQrCode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** State of the "Enter TV IP" dialog. */
sealed interface ManualEntry {
    data object Hidden : ManualEntry
    data class Editing(val error: String? = null) : ManualEntry
    data object Checking : ManualEntry
}

data class DevicesUiState(
    val devices: List<CastDevice> = emptyList(),
    val selectedId: String? = null,
    val lan: LanStatus = LanStatus.Disconnected,
    val manual: ManualEntry = ManualEntry.Hidden,
    /** Other TVs that play along with the selected one. */
    val extraIds: Set<String> = emptySet(),
    /** Device id → MAC for "Turn on TV". */
    val macs: Map<String, String> = emptyMap(),
    val scan: RouterScan = RouterScan(),
)

/** Progress of "Search other routers". */
data class RouterScan(val running: Boolean = false, val message: String? = null)

/** Runs discovery while the device picker is on screen. */
class DevicesViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = DeviceRepository(app)
    private val manual = MutableStateFlow<ManualEntry>(ManualEntry.Hidden)
    private val scan = MutableStateFlow(RouterScan())

    val state: StateFlow<DevicesUiState> = combine(
        repository.devices(),
        CastManager.selectedDevice,
        LanMonitor(app).status,
        combine(manual, scan) { entry, scan -> entry to scan },
        combine(CastManager.extraDevices, CastManager.macs) { extras, macs -> extras.map { it.id }.toSet() to macs },
    ) { devices, selected, lan, (entry, scan), (extras, macs) ->
        DevicesUiState(devices, selected?.id, lan, entry, extras, macs, scan)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(3_000), DevicesUiState())

    init {
        // Once per network per app run, look past the phone's router automatically.
        viewModelScope.launch {
            val ip = LanMonitor(app).current().ipAddress ?: return@launch
            val network = Subnets.slash24(ip) ?: return@launch
            if (autoSearched.add(network)) {
                delay(1_500) // let normal discovery go first
                searchOtherRouters()
            }
        }
    }

    /**
     * Finds TVs on routers chained above (or beside) the phone's and remembers them, so
     * they show up in the list from now on.
     */
    fun searchOtherRouters() {
        if (scan.value.running) return
        viewModelScope.launch {
            scan.value = RouterScan(running = true, message = "Looking for other routers…")
            val result = try {
                NetworkScanner(getApplication()).scan { msg -> scan.value = RouterScan(running = true, message = msg) }
            } catch (e: Exception) {
                scan.value = RouterScan(message = "Search failed: ${e.message}")
                return@launch
            }
            val liveIds = state.value.devices.map { it.id }.toSet()
            for (d in result.devices) {
                when {
                    d.otherRouter -> repository.addManual(d)
                    // Same network but invisible to discovery (routers that block multicast).
                    d.id !in liveIds -> repository.addManual(d.copy(isManual = true))
                }
            }
            val others = result.devices.count { it.otherRouter }
            val routers = result.networks.size - 1
            scan.value = RouterScan(
                message = when {
                    result.networks.isEmpty() -> "Connect to Wi-Fi first."
                    others == 1 -> "Found 1 TV on another router."
                    others > 1 -> "Found $others TVs on other routers."
                    routers > 0 -> "No TVs found on the other ${if (routers == 1) "router" else "$routers routers"} (${result.networks.drop(1).joinToString { "$it.x" }}). Make sure they're on."
                    else -> "No other routers found above this one."
                },
            )
        }
    }

    /** Also play on [device] (party mode), or stop doing so. */
    fun toggleExtra(device: CastDevice) = CastManager.toggleExtraDevice(device)

    /** Saves a MAC address for Wake-on-LAN; returns an error message if it's invalid. */
    fun saveMac(device: CastDevice, text: String): String? {
        if (text.isBlank()) {
            CastManager.setMac(device, null)
            return null
        }
        val mac = WakeOnLan.formatMac(text) ?: return "That's not a MAC address (it looks like A1:B2:C3:D4:E5:F6)"
        CastManager.setMac(device, mac)
        return null
    }

    fun wake(device: CastDevice) = CastManager.wake(device)

    fun select(device: CastDevice) = CastManager.select(device)

    fun showManualEntry() {
        manual.value = ManualEntry.Editing()
    }

    fun dismissManualEntry() {
        manual.value = ManualEntry.Hidden
    }

    /** A code scanned from the TV's start screen. */
    fun addFromQr(text: String, onAdded: () -> Unit) {
        val target = TvQrCode.parse(text)
        if (target == null) {
            manual.value = ManualEntry.Editing("That code isn't from the CastLite TV app.")
            return
        }
        connect(target.host, target.port, onAdded)
    }

    /** Checks that [input] ("192.168.1.50" or "192.168.1.50:8766") runs the CastLite TV app, then adds and selects it. */
    fun addByIp(input: String, onAdded: () -> Unit) {
        val host = input.trim().substringBefore(':')
        val port = input.trim().substringAfter(':', "").toIntOrNull() ?: ReceiverProtocol.PORT
        if (!isValidHost(host)) {
            manual.value = ManualEntry.Editing("That doesn't look like an IP address (e.g. 192.168.1.50)")
            return
        }
        connect(host, port, onAdded)
    }

    private fun connect(host: String, port: Int, onAdded: () -> Unit) {
        manual.value = ManualEntry.Checking
        viewModelScope.launch {
            try {
                val probed = ReceiverDiscovery.probe(host, port)
                val phoneIp = LanMonitor(getApplication()).current().ipAddress
                val device = probed.copy(
                    otherRouter = phoneIp != null && Subnets.toInt(host) != null &&
                        !Subnets.sameSubnet(host, phoneIp, Subnets.prefixLengthOf(phoneIp)),
                )
                repository.addManual(device)
                CastManager.select(device)
                manual.value = ManualEntry.Hidden
                onAdded()
            } catch (e: Exception) {
                manual.value = ManualEntry.Editing(
                    "No CastLite TV app answered at $host. Open the app on the TV and check the address it shows.",
                )
            }
        }
    }

    fun removeManual(device: CastDevice) = repository.removeManual(device)

    companion object {
        /** Networks already searched automatically in this app run. */
        private val autoSearched = java.util.Collections.synchronizedSet(mutableSetOf<String>())

        private val IPV4 = Regex("""^((25[0-5]|2[0-4]\d|1?\d?\d)\.){3}(25[0-5]|2[0-4]\d|1?\d?\d)$""")
        private val HOSTNAME = Regex("""^[A-Za-z0-9]([A-Za-z0-9-]{0,62})(\.[A-Za-z0-9-]{1,63})*$""")

        fun isValidHost(s: String): Boolean = IPV4.matches(s) || (HOSTNAME.matches(s) && s.any { it.isLetter() })
    }
}
