package com.castlite.phone.cast

import android.content.Context
import com.castlite.phone.cast.dlna.DlnaDiscovery
import com.castlite.phone.cast.receiver.ReceiverDiscovery
import com.castlite.phone.data.AppPrefs
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update

/**
 * Merges every discovery source into one device list:
 *  - DLNA renderers found with SSDP (LG webOS etc.)
 *  - CastLite TV apps found with NSD/mDNS
 *  - TVs added by IP (persisted)
 * Google Cast would be a fourth source here (see GoogleCastSupport).
 */
class DeviceRepository(context: Context) {

    private val prefs = AppPrefs(context)
    private val dlna = DlnaDiscovery(context)
    private val receivers = ReceiverDiscovery(context)

    private val _manual = MutableStateFlow(prefs.manualDevices)
    val manual: StateFlow<List<CastDevice>> = _manual.asStateFlow()

    /** Live list while collected (discovery runs only while someone is watching). */
    fun devices(): Flow<List<CastDevice>> = combine(
        receivers.devices().catch { emit(emptyList()) }.onStart { emit(emptyList()) },
        dlna.devices().catch { emit(emptyList()) }.onStart { emit(emptyList()) },
        _manual,
    ) { castlite, renderers, manual ->
        val discoveredIds = (castlite + renderers).map { it.id }.toSet()
        // CastLite TV app first (best experience), then DLNA, then manual entries not seen live.
        castlite + renderers + manual.filter { it.id !in discoveredIds }
    }

    fun addManual(device: CastDevice) = _manual.update { list ->
        (list.filterNot { it.id == device.id } + device).also { prefs.manualDevices = it }
    }

    fun removeManual(device: CastDevice) = _manual.update { list ->
        list.filterNot { it.id == device.id }.also { prefs.manualDevices = it }
    }
}
