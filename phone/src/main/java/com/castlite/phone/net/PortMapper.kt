package com.castlite.phone.net

import com.castlite.phone.cast.dlna.DeviceDescriptionParser
import com.castlite.phone.cast.dlna.SoapClient
import com.castlite.phone.cast.dlna.Ssdp
import com.castlite.phone.cast.dlna.SsdpResponse
import com.castlite.phone.cast.dlna.UpnpException
import com.castlite.phone.cast.dlna.Xml
import com.castlite.phone.cast.dlna.Xml.childText
import com.castlite.phone.cast.dlna.Xml.descendants
import com.castlite.phone.diagnostics.Diag
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import kotlin.random.Random

/** A router's port-forwarding service (UPnP Internet Gateway Device). */
data class Gateway(val controlUrl: String, val serviceType: String) {
    val host: String get() = URL(controlUrl).host
}

object IgdDescription {
    /** Finds the WANIPConnection (or WANPPPConnection) service in a router's description XML. */
    fun parse(xml: String, location: String): Gateway? {
        val root = Xml.parse(xml).documentElement
        val base = root.childText("URLBase") ?: location
        val services = root.descendants("service")
        val service = services.firstOrNull { it.childText("serviceType")?.contains(":WANIPConnection:") == true }
            ?: services.firstOrNull { it.childText("serviceType")?.contains(":WANPPPConnection:") == true }
            ?: return null
        val control = service.childText("controlURL") ?: return null
        return Gateway(DeviceDescriptionParser.resolve(base, control), service.childText("serviceType")!!)
    }
}

/**
 * Lets a DLNA TV on another router fetch the phone's media. A DLNA TV can only *pull*
 * from a URL, and a router's NAT blocks connections from the network above it, so we
 * ask each router on the way (UPnP port mapping) to forward a port:
 *
 * ```
 * TV on router 2's network ──► router 3 (outside address):port ──► phone:server port
 * TV on router 1's network ──► router 2:port ──► router 3:port ──► phone
 * ```
 *
 * Safety: a port is only opened on a router whose outside address is private (another
 * router in the house); never on the one facing the internet. Mappings are removed when
 * casting ends (and expire after two hours anyway).
 *
 * Needs UPnP to be switched on in those routers. Blocking; call from Dispatchers.IO.
 */
class PortMapper(
    private val soap: SoapClient = SoapClient(connectTimeoutMs = 2_500, readTimeoutMs = 4_000),
    /** Finds the router above [innerIp]: null [candidates] = the phone's own router. */
    private val findGateway: (candidates: List<String>?) -> Gateway?,
    private val log: (String) -> Unit = { Diag.log("upnp", it) },
) {
    data class Layer(val gateway: Gateway, val externalIp: String, val externalPort: Int)

    /** The address the TV should use, and the mappings to remove later. */
    data class Mapping(val host: String, val port: Int, val layers: List<Layer>)

    /** Opens ports router by router until [tvHost]'s network is reached; null if it can't. */
    fun expose(tvHost: String, phoneIp: String, phonePort: Int, routerHops: List<String>): Mapping? {
        val layers = mutableListOf<Layer>()
        var innerIp = phoneIp
        var innerPort = phonePort
        for (depth in 0 until MAX_ROUTERS) {
            val candidates = if (depth == 0) {
                null
            } else {
                (routerHops + listOfNotNull(Subnets.slash24(innerIp)?.let { "$it.1" }))
                    .filter { Subnets.sameSubnet(it, innerIp, 24) && it != innerIp }
                    .distinct()
            }
            val gateway = findGateway(candidates)
            if (gateway == null) {
                log("No UPnP router found above $innerIp")
                break
            }
            val outside = externalIp(gateway)
            if (outside == null || !Subnets.isPrivate(outside)) {
                log("${gateway.host} faces the internet (${outside ?: "?"}) — not opening a port there")
                break
            }
            val port = addMapping(gateway, innerIp, innerPort)
            if (port == null) {
                log("${gateway.host} refused to open a port")
                break
            }
            layers += Layer(gateway, outside, port)
            log("${gateway.host}: $outside:$port → $innerIp:$innerPort")
            if (Subnets.sameSubnet(tvHost, outside, 24)) return Mapping(outside, port, layers)
            innerIp = outside
            innerPort = port
        }
        release(layers)
        return null
    }

    fun release(layers: List<Layer>) {
        for (layer in layers.asReversed()) {
            runCatching {
                soap.call(
                    layer.gateway.controlUrl, layer.gateway.serviceType, "DeletePortMapping",
                    listOf("NewRemoteHost" to "", "NewExternalPort" to layer.externalPort.toString(), "NewProtocol" to "TCP"),
                )
            }
        }
    }

    /** The router's own address on the network above it. */
    fun externalIp(gateway: Gateway): String? = try {
        soap.call(gateway.controlUrl, gateway.serviceType, "GetExternalIPAddress", emptyList())["NewExternalIPAddress"]
            ?.trim()?.takeIf { Subnets.toInt(it) != null && it != "0.0.0.0" }
    } catch (e: Exception) {
        log("GetExternalIPAddress on ${gateway.host} failed: ${e.message}")
        null
    }

    private fun addMapping(gateway: Gateway, internalIp: String, internalPort: Int): Int? {
        val ports = listOf(internalPort) + List(3) { Random.nextInt(41_000, 49_000) }
        for (port in ports) {
            for (lease in listOf(LEASE_SECONDS, 0)) {
                try {
                    soap.call(
                        gateway.controlUrl, gateway.serviceType, "AddPortMapping",
                        listOf(
                            "NewRemoteHost" to "",
                            "NewExternalPort" to port.toString(),
                            "NewProtocol" to "TCP",
                            "NewInternalPort" to internalPort.toString(),
                            "NewInternalClient" to internalIp,
                            "NewEnabled" to "1",
                            "NewPortMappingDescription" to "CastLite",
                            "NewLeaseDuration" to lease.toString(),
                        ),
                    )
                    return port
                } catch (e: UpnpException) {
                    when (e.code) {
                        725 -> continue // only permanent leases: retry with 0
                        718, 727 -> break // port taken: try another
                        else -> {
                            log("AddPortMapping on ${gateway.host}: ${e.message}")
                            return null
                        }
                    }
                } catch (e: Exception) {
                    log("AddPortMapping on ${gateway.host}: ${e.message}")
                    return null
                }
            }
        }
        return null
    }

    companion object {
        private const val MAX_ROUTERS = 3
        private const val LEASE_SECONDS = 2 * 60 * 60

        /** Real router discovery: SSDP to the phone's router, unicast SSDP to the ones above. */
        fun discovery(localIp: String): (List<String>?) -> Gateway? = { candidates ->
            val local = runCatching { InetAddress.getByName(localIp) }.getOrNull()
            val replies = mutableListOf<SsdpResponse>()
            if (candidates == null) {
                runCatching { Ssdp.search(local, 2_500, listOf(Ssdp.INTERNET_GATEWAY)) { synchronized(replies) { replies += it } } }
            } else if (candidates.isNotEmpty()) {
                for (st in listOf(Ssdp.INTERNET_GATEWAY, "upnp:rootdevice")) {
                    runCatching { Ssdp.unicastSearch(local, candidates, st, 1_500) { _, r -> synchronized(replies) { replies += r } } }
                    if (replies.isNotEmpty()) break
                }
            }
            val locations = synchronized(replies) { replies.map { it.location }.distinct() }
            // Prefer the router on the phone's own network (or among the candidates).
            val ordered = locations.sortedByDescending { loc ->
                val host = runCatching { URL(loc).host }.getOrDefault("")
                if (candidates == null) Subnets.sameSubnet(host, localIp, 24) else host in candidates
            }
            ordered.firstNotNullOfOrNull { loc -> runCatching { IgdDescription.parse(httpGet(loc), loc) }.getOrNull() }
        }

        private fun httpGet(url: String): String {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 2_500
                readTimeout = 3_000
            }
            return try {
                conn.inputStream.use { String(it.readBytes(), Charsets.UTF_8) }
            } finally {
                conn.disconnect()
            }
        }
    }
}
