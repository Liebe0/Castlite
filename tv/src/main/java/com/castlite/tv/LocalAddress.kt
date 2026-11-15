package com.castlite.tv

import java.net.Inet4Address
import java.net.NetworkInterface

/** Finds this TV's LAN IPv4 address so it can be shown for the manual "enter TV IP" fallback. */
object LocalAddress {
    fun ipv4(): String? = try {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { it.isUp && !it.isLoopback }
            // Prefer Ethernet/Wi-Fi over VPN or other virtual interfaces.
            .sortedBy { if (it.name.startsWith("eth") || it.name.startsWith("wlan")) 0 else 1 }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }
            ?.hostAddress
    } catch (e: Exception) {
        null
    }
}
