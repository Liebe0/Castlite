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
import com.castlite.phone.net.WakeOnLan
import com.castlite.protocol.ReceiverProtocol
import com.castlite.protocol.TvQrCode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
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
)

/** Runs discovery while the device picker is on screen. */
class DevicesViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = DeviceRepository(app)
    private val manual = MutableStateFlow<ManualEntry>(ManualEntry.Hidden)

    val state: StateFlow<DevicesUiState> = combine(
        repository.devices(),
        CastManager.selectedDevice,
        LanMonitor(app).status,
        manual,
        combine(CastManager.extraDevices, CastManager.macs) { extras, macs -> extras.map { it.id }.toSet() to macs },
    ) { devices, selected, lan, entry, (extras, macs) ->
        DevicesUiState(devices, selected?.id, lan, entry, extras, macs)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(3_000), DevicesUiState())

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
                val device = ReceiverDiscovery.probe(host, port)
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
        private val IPV4 = Regex("""^((25[0-5]|2[0-4]\d|1?\d?\d)\.){3}(25[0-5]|2[0-4]\d|1?\d?\d)$""")
        private val HOSTNAME = Regex("""^[A-Za-z0-9]([A-Za-z0-9-]{0,62})(\.[A-Za-z0-9-]{1,63})*$""")

        fun isValidHost(s: String): Boolean = IPV4.matches(s) || (HOSTNAME.matches(s) && s.any { it.isLetter() })
    }
}
