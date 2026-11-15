package com.castlite.phone.cast.dlna

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import com.castlite.phone.cast.CastDevice
import com.castlite.phone.cast.CastProtocol
import com.castlite.phone.cast.DlnaEndpoints
import com.castlite.phone.diagnostics.Diag
import com.castlite.phone.net.LanMonitor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * Finds DLNA MediaRenderers with repeated SSDP searches while collected.
 * Each reply's description XML is fetched once and cached per LOCATION.
 */
class DlnaDiscovery(context: Context) {

    private val app = context.applicationContext
    private val lan = LanMonitor(app)

    fun devices(): Flow<List<CastDevice>> = channelFlow {
        val wifi = app.getSystemService(WifiManager::class.java)
        // Android drops incoming multicast/SSDP packets unless a multicast lock is held.
        val lock = wifi?.createMulticastLock("CastLite:ssdp")?.apply { setReferenceCounted(false); acquire() }
        val found = ConcurrentHashMap<String, Pair<CastDevice, Long>>() // udn -> device, last seen
        val described = ConcurrentHashMap<String, CastDevice?>()          // location -> device (null = not a renderer)
        val inFlight = ConcurrentHashMap.newKeySet<String>()

        fun publish() {
            val cutoff = System.currentTimeMillis() - STALE_AFTER_MS
            found.entries.removeIf { it.value.second < cutoff }
            trySend(found.values.map { it.first }.sortedBy { it.name.lowercase() })
        }

        try {
            send(emptyList())
            while (isActive) {
                val local = lan.current().ipAddress?.let { runCatching { InetAddress.getByName(it) }.getOrNull() }
                if (local == null) {
                    delay(2_000)
                    continue
                }
                try {
                    Ssdp.search(local, SEARCH_WINDOW_MS) { reply ->
                        val cached = described[reply.location]
                        if (cached != null) {
                            found[cached.id] = cached to System.currentTimeMillis()
                        } else if (!described.containsKey(reply.location) && inFlight.add(reply.location)) {
                            launch(Dispatchers.IO) {
                                val device = describe(reply)
                                described[reply.location] = device
                                inFlight.remove(reply.location)
                                if (device != null) {
                                    found[device.id] = device to System.currentTimeMillis()
                                    publish()
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "SSDP search failed", e)
                }
                publish()
                delay(REPEAT_EVERY_MS)
            }
        } finally {
            lock?.takeIf { it.isHeld }?.release()
        }
    }.flowOn(Dispatchers.IO)

    /** Downloads and parses a device description; null if it isn't a usable renderer. */
    private fun describe(reply: SsdpResponse): CastDevice? = try {
        val conn = (URL(reply.location).openConnection() as HttpURLConnection).apply {
            connectTimeout = 3_000
            readTimeout = 4_000
        }
        val xml = try {
            conn.inputStream.use { String(it.readBytes(), Charsets.UTF_8) }
        } finally {
            conn.disconnect()
        }
        DeviceDescriptionParser.parse(xml, reply.location)?.let { d ->
            CastDevice(
                id = d.udn,
                name = d.friendlyName,
                protocol = CastProtocol.DLNA,
                host = URL(reply.location).host,
                manufacturer = d.manufacturer,
                model = d.modelName,
                dlna = DlnaEndpoints(reply.location, d.avTransportControlUrl, d.renderingControlUrl, d.connectionManagerUrl),
            ).also { Diag.log("ssdp", "Found ${it.name} (${it.details}) at ${reply.location}, server=${reply.server}") }
        }
    } catch (e: Exception) {
        Diag.log("ssdp", "Ignoring ${reply.location}: ${e.message}")
        null
    }

    private companion object {
        const val TAG = "DlnaDiscovery"
        const val SEARCH_WINDOW_MS = 3_000
        const val REPEAT_EVERY_MS = 6_000L
        const val STALE_AFTER_MS = 45_000L
    }
}
