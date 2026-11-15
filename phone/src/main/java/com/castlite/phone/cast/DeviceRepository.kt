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

    // Shared by every instance (device picker, QR deep links), loaded once from prefs.
    private val _manual: MutableStateFlow<List<CastDevice>> = sharedManual ?: MutableStateFlow(prefs.manualDevices).also { sharedManual = it }
    val manual: StateFlow<List<CastDevice>> = _manual.asStateFlow()

    /** Live list while collected (discovery runs only while someone is watching). */
    fun devices(): Flow<List<CastDevice>> = combine(
        receivers.devices().catch { emit(emptyList()) }.onStart { emit(emptyList()) },
        dlna.devices().catch { emit(emptyList()) }.onStart { emit(emptyList()) },
        _manual,
    ) { castlite, renderers, manual -> merge(castlite, renderers, manual) }

    fun addManual(device: CastDevice) = _manual.update { list ->
        (list.filterNot { it.id == device.id } + device).also { prefs.manualDevices = it }
    }

    fun removeManual(device: CastDevice) = _manual.update { list ->
        list.filterNot { it.id == device.id }.also { prefs.manualDevices = it }
    }

    companion object {
        @Volatile private var sharedManual: MutableStateFlow<List<CastDevice>>? = null

        /**
         * One entry per TV (pure, unit-tested). CastLite TV apps come first (best
         * experience), then DLNA renderers, then manually added TVs not seen live.
         * Many Android TVs also have a built-in DLNA renderer: when a DLNA entry has the
         * same IP as a CastLite TV app, it's the same TV, so only the CastLite one is shown.
         */
        fun merge(castlite: List<CastDevice>, renderers: List<CastDevice>, manual: List<CastDevice>): List<CastDevice> {
            val castliteHosts = (castlite + manual.filter { it.protocol == CastProtocol.CASTLITE }).map { it.host }.toSet()
            val shownRenderers = renderers.filter { it.host !in castliteHosts }
            val liveIds = (castlite + shownRenderers).map { it.id }.toSet()
            val liveCastliteHosts = castlite.map { it.host }.toSet()
            return castlite + shownRenderers + manual.filter { it.id !in liveIds && it.host !in liveCastliteHosts }
        }
    }
}
