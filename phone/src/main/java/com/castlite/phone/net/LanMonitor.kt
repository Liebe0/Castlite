package com.castlite.phone.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import java.net.Inet4Address
import java.net.NetworkInterface

/** How the phone is attached to the local network that the TV is on. */
data class LanStatus(val kind: Kind, val ipAddress: String?) {
    enum class Kind { WIFI, ETHERNET, HOTSPOT, NONE }

    val isConnected: Boolean get() = kind != Kind.NONE && ipAddress != null

    companion object {
        val Disconnected = LanStatus(Kind.NONE, null)
    }
}

/**
 * Watches Wi-Fi/Ethernet and reports the phone's LAN IPv4 address — the address TVs
 * must use to reach our HTTP server. Emits again whenever Wi-Fi drops, reconnects or
 * the address changes, so the UI can show a clear error and refresh the URLs.
 */
class LanMonitor(context: Context) {

    private val cm = context.applicationContext.getSystemService(ConnectivityManager::class.java)

    /** Current status, computed synchronously. */
    fun current(): LanStatus = findLanNetwork() ?: findHotspot() ?: LanStatus.Disconnected

    /** Hot flow of status changes, starting with the current value. */
    val status: Flow<LanStatus> = callbackFlow {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { trySend(current()) }
            override fun onLost(network: Network) { trySend(current()) }
            override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) { trySend(current()) }
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) { trySend(current()) }
        }
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
            // A LAN without internet access is still fine for casting.
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        trySend(current())
        cm.registerNetworkCallback(request, callback)
        awaitClose { cm.unregisterNetworkCallback(callback) }
    }.distinctUntilChanged()

    @Suppress("DEPRECATION") // allNetworks is the simplest way that works back to API 24.
    private fun findLanNetwork(): LanStatus? {
        for (network in cm.allNetworks) {
            val caps = cm.getNetworkCapabilities(network) ?: continue
            val kind = when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> LanStatus.Kind.WIFI
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> LanStatus.Kind.ETHERNET
                else -> continue
            }
            val ip = cm.getLinkProperties(network)?.linkAddresses
                ?.map { it.address }
                ?.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
                ?.hostAddress
                ?: continue
            return LanStatus(kind, ip)
        }
        return null
    }

    /**
     * When the phone itself is the hotspot (TV joined the phone's Wi-Fi), Android
     * doesn't list that as a network, so look at the interfaces directly.
     */
    private fun findHotspot(): LanStatus? = try {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { it.isUp && !it.isLoopback && HOTSPOT_PREFIXES.any { p -> it.name.startsWith(p) } }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }
            ?.hostAddress
            ?.let { LanStatus(LanStatus.Kind.HOTSPOT, it) }
    } catch (e: Exception) {
        null
    }

    private companion object {
        val HOTSPOT_PREFIXES = listOf("ap", "swlan", "softap", "wlan")
    }
}
