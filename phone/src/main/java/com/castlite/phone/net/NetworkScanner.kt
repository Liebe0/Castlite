package com.castlite.phone.net

import android.content.Context
import com.castlite.phone.cast.CastDevice
import com.castlite.phone.cast.CastProtocol
import com.castlite.phone.cast.dlna.DlnaDiscovery
import com.castlite.phone.cast.dlna.Ssdp
import com.castlite.phone.cast.receiver.ReceiverDiscovery
import com.castlite.phone.diagnostics.Diag
import com.castlite.protocol.ReceiverProtocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

/**
 * "Search other routers": finds TVs that normal discovery can't see.
 *
 * mDNS and SSDP use multicast, which stops at every router. With chained routers
 * (fiber router → router 2 → router 3) a phone on router 3 can still *connect* to TVs on
 * routers 2 and 1, it just can't hear them. So we ask every address directly:
 *
 * 1. Which networks? The phone's own, plus the ones above it, found by tracing the
 *    route out ([RouteTracer]) and by asking the phone's router for its outside address.
 * 2. On each network, every address is asked "are you a CastLite TV?" (port 8766) and
 *    "are you a media renderer?" (unicast SSDP), in parallel. ~5–10 s per network.
 */
class NetworkScanner(context: Context) {

    private val lan = LanMonitor(context.applicationContext)

    data class Result(val devices: List<CastDevice>, val networks: List<String>)

    suspend fun scan(onProgress: (String) -> Unit = {}): Result = withContext(Dispatchers.IO) {
        val phoneIp = lan.current().ipAddress ?: return@withContext Result(emptyList(), emptyList())
        val prefix = Subnets.prefixLengthOf(phoneIp)
        val own = Subnets.slash24(phoneIp) ?: return@withContext Result(emptyList(), emptyList())

        onProgress("Looking for other routers…")
        val networks = LinkedHashSet<String>().apply { add(own) }
        val hops = RouteTracer.privateHops()
        hops.mapNotNullTo(networks) { Subnets.slash24(it) }
        // The router's outside address shows the network above it, even if ping is blocked.
        if (networks.size == 1) {
            runCatching {
                PortMapper.discovery(phoneIp)(null)?.let { gw -> PortMapper(findGateway = { null }).externalIp(gw) }
            }.getOrNull()?.takeIf { Subnets.isPrivate(it) }?.let { Subnets.slash24(it) }?.let(networks::add)
        }
        val targets = networks.take(MAX_NETWORKS)
        Diag.log("scan", "Searching ${targets.joinToString { "$it.x" }}")

        val found = ConcurrentHashMap<String, CastDevice>()
        for ((i, net) in targets.withIndex()) {
            onProgress(if (i == 0) "Searching your network ($net.x)…" else "Searching router ${i + 1} ($net.x)…")
            val hosts = Subnets.hostsOf(net).filter { it != phoneIp }
            coroutineScope {
                launch { scanCastLite(hosts, found) }
                launch { scanDlna(phoneIp, hosts, found) }
            }
        }
        // One entry per TV: an Android TV with the CastLite app often has a DLNA renderer too.
        val castliteHosts = found.values.filter { it.protocol == CastProtocol.CASTLITE }.map { it.host }.toSet()
        val devices = found.values
            .filter { it.protocol == CastProtocol.CASTLITE || it.host !in castliteHosts }
            .map { it.copy(otherRouter = !Subnets.sameSubnet(it.host, phoneIp, prefix)) }
            .sortedWith(compareBy({ it.otherRouter }, { it.name.lowercase() }))
        Diag.log("scan", "Found ${devices.size}: ${devices.joinToString { "${it.name} ${it.host} ${it.protocol.shortLabel}" }}")
        Result(devices, targets)
    }

    private suspend fun scanCastLite(hosts: List<String>, found: MutableMap<String, CastDevice>) = coroutineScope {
        val gate = Semaphore(PARALLEL_CONNECTS)
        hosts.map { host ->
            async {
                gate.withPermit {
                    if (portOpen(host, ReceiverProtocol.PORT)) {
                        runCatching { ReceiverDiscovery.probe(host).copy(isManual = false) }.getOrNull()?.let { found[it.id] = it }
                    }
                }
            }
        }.awaitAll()
    }

    private suspend fun scanDlna(phoneIp: String, hosts: List<String>, found: MutableMap<String, CastDevice>) = coroutineScope {
        val replies = ConcurrentHashMap<String, com.castlite.phone.cast.dlna.SsdpResponse>()
        runCatching {
            Ssdp.unicastSearch(InetAddress.getByName(phoneIp), hosts, Ssdp.MEDIA_RENDERER, DLNA_LISTEN_MS) { _, reply ->
                replies.putIfAbsent(reply.location, reply)
            }
        }
        replies.values.map { reply ->
            async { DlnaDiscovery.describe(reply)?.let { found[it.id] = it } }
        }.awaitAll()
    }

    private fun portOpen(host: String, port: Int): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS) }
        true
    } catch (e: Exception) {
        false
    }

    private companion object {
        const val MAX_NETWORKS = 4
        const val PARALLEL_CONNECTS = 48
        const val CONNECT_TIMEOUT_MS = 600
        const val DLNA_LISTEN_MS = 2_500
    }
}
