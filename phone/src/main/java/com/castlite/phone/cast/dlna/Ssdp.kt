package com.castlite.phone.cast.dlna

import java.io.IOException
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketTimeoutException

/** A reply to an SSDP M-SEARCH (or a NOTIFY alive message). */
data class SsdpResponse(
    val location: String,
    val usn: String?,
    val searchTarget: String?,
    val server: String?,
)

/**
 * SSDP (Simple Service Discovery Protocol), the discovery half of UPnP: we multicast
 * "who is a MediaRenderer?" to 239.255.255.250:1900 and renderers reply by unicast
 * with the URL of their device description XML.
 */
object Ssdp {
    const val ADDRESS = "239.255.255.250"
    const val PORT = 1900
    const val MEDIA_RENDERER = "urn:schemas-upnp-org:device:MediaRenderer:1"
    const val AV_TRANSPORT = "urn:schemas-upnp-org:service:AVTransport:1"
    /** A router that can open ports on request (UPnP IGD). */
    const val INTERNET_GATEWAY = "urn:schemas-upnp-org:device:InternetGatewayDevice:1"

    fun searchMessage(searchTarget: String, mxSeconds: Int = 2, host: String = "$ADDRESS:$PORT"): String =
        "M-SEARCH * HTTP/1.1\r\n" +
            "HOST: $host\r\n" +
            "MAN: \"ssdp:discover\"\r\n" +
            "MX: $mxSeconds\r\n" +
            "ST: $searchTarget\r\n" +
            "USER-AGENT: Android/1 UPnP/1.1 CastLite/1.0\r\n" +
            "\r\n"

    /** Parses a search reply or NOTIFY; returns null for anything without a LOCATION or for byebye. */
    fun parse(packet: String): SsdpResponse? {
        val lines = packet.split("\r\n", "\n")
        val first = lines.firstOrNull()?.trim() ?: return null
        val isReply = first.startsWith("HTTP/1.1 200", ignoreCase = true) || first.startsWith("HTTP/1.0 200", ignoreCase = true)
        val isNotify = first.startsWith("NOTIFY", ignoreCase = true)
        if (!isReply && !isNotify) return null
        val headers = lines.drop(1).mapNotNull { line ->
            val colon = line.indexOf(':')
            if (colon <= 0) null else line.substring(0, colon).trim().uppercase() to line.substring(colon + 1).trim()
        }.toMap()
        if (isNotify && headers["NTS"]?.contains("byebye", ignoreCase = true) == true) return null
        val location = headers["LOCATION"]?.takeIf { it.startsWith("http", ignoreCase = true) } ?: return null
        return SsdpResponse(
            location = location,
            usn = headers["USN"],
            searchTarget = headers["ST"] ?: headers["NT"],
            server = headers["SERVER"],
        )
    }

    /**
     * Sends M-SEARCH for renderers from [localAddress] (the phone's Wi-Fi IP; binding to
     * it keeps the multicast off mobile data) and reports every reply for [timeoutMs].
     * Blocking: call on Dispatchers.IO. The caller must hold a Wi-Fi MulticastLock.
     */
    fun search(
        localAddress: InetAddress?,
        timeoutMs: Int,
        targets: List<String> = listOf(MEDIA_RENDERER, AV_TRANSPORT),
        onResponse: (SsdpResponse) -> Unit,
    ) {
        MulticastSocket(null as java.net.SocketAddress?).use { socket ->
            socket.reuseAddress = true
            socket.bind(InetSocketAddress(localAddress, 0))
            localAddress?.let { addr ->
                NetworkInterface.getByInetAddress(addr)?.let { socket.networkInterface = it }
            }
            socket.timeToLive = 4
            socket.soTimeout = 500
            val group = InetAddress.getByName(ADDRESS)
            // UDP is lossy: send each query twice.
            repeat(2) {
                for (target in targets) {
                    val bytes = searchMessage(target).toByteArray()
                    socket.send(DatagramPacket(bytes, bytes.size, group, PORT))
                }
            }
            val buffer = ByteArray(8 * 1024)
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                } catch (e: SocketTimeoutException) {
                    continue
                } catch (e: IOException) {
                    break
                }
                parse(String(packet.data, 0, packet.length, Charsets.UTF_8))?.let(onResponse)
            }
        }
    }

    /**
     * Asks each of [hosts] directly (unicast M-SEARCH to port 1900). Multicast doesn't pass
     * through routers, but a unicast question does, and most UPnP devices answer it.
     * Blocking; sends a few packets per host and listens for [timeoutMs].
     */
    fun unicastSearch(
        localAddress: InetAddress?,
        hosts: List<String>,
        searchTarget: String,
        timeoutMs: Int,
        onResponse: (from: String, SsdpResponse) -> Unit,
    ) {
        java.net.DatagramSocket(null as java.net.SocketAddress?).use { socket ->
            socket.bind(InetSocketAddress(localAddress, 0))
            socket.soTimeout = 300
            val buffer = ByteArray(8 * 1024)
            fun drain() {
                while (true) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    try {
                        socket.receive(packet)
                    } catch (e: SocketTimeoutException) {
                        return
                    } catch (e: IOException) {
                        return
                    }
                    parse(String(packet.data, 0, packet.length, Charsets.UTF_8))?.let { onResponse(packet.address.hostAddress ?: "", it) }
                }
            }
            repeat(2) { round ->
                for ((i, host) in hosts.withIndex()) {
                    val bytes = searchMessage(searchTarget, mxSeconds = 1, host = "$host:$PORT").toByteArray()
                    try {
                        socket.send(DatagramPacket(bytes, bytes.size, InetAddress.getByName(host), PORT))
                    } catch (e: IOException) {
                        // Unreachable host/network: skip it.
                    }
                    // Pace the burst a little so cheap routers don't drop it.
                    if (i % 32 == 31) Thread.sleep(if (round == 0) 15 else 5)
                }
            }
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) drain()
        }
    }
}
