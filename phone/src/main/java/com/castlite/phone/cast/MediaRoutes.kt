package com.castlite.phone.cast

import com.castlite.phone.diagnostics.Diag
import com.castlite.phone.net.PortMapper
import com.castlite.phone.net.RouteTracer
import com.castlite.phone.net.Subnets
import com.castlite.protocol.ReceiverClient
import com.castlite.protocol.TunnelClient
import com.castlite.protocol.TunnelProtocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * How one TV fetches the phone's media. [map] rewrites the phone's own URLs
 * (`http://<phone>:<port>/…`) into ones that TV can reach.
 */
class MediaRoute internal constructor(
    private val phoneBase: String,
    val kind: Kind,
    /** Base URL the TV should use instead of [phoneBase]; null = the phone's own address. */
    private val tvBase: () -> String? = { null },
    private val onRelease: () -> Unit = {},
) {
    enum class Kind {
        /** Same network: the TV fetches from the phone directly. */
        DIRECT,

        /** Another router, CastLite TV app: the phone opens the connections (reverse tunnel). */
        TUNNEL,

        /** Another router, DLNA TV: the routers forward a port (UPnP). */
        PORT_MAPPING,

        /** Another router and no way through found: tried directly, may fail. */
        DIRECT_ACROSS_ROUTERS,
    }

    fun map(url: String): String {
        val base = tvBase() ?: return url
        return if (url.startsWith(phoneBase)) base + url.substring(phoneBase.length) else url
    }

    fun release() = onRelease()
}

/** Picks the [MediaRoute] for each TV when casting starts. */
object MediaRoutes {

    /** One per app run: the TV keeps the same loopback port for us across casts. */
    private val tunnelToken = TunnelProtocol.newToken()

    suspend fun routeFor(device: CastDevice, phoneIp: String, phonePort: Int): MediaRoute = withContext(Dispatchers.IO) {
        val phoneBase = "http://$phoneIp:$phonePort"
        val prefix = Subnets.prefixLengthOf(phoneIp)
        if (Subnets.toInt(device.host) == null || Subnets.sameSubnet(device.host, phoneIp, prefix)) {
            return@withContext MediaRoute(phoneBase, MediaRoute.Kind.DIRECT)
        }
        Diag.log("route", "${device.name} (${device.host}) is on another network than the phone ($phoneIp/$prefix)")
        when (device.protocol) {
            CastProtocol.CASTLITE -> tunnelRoute(device, phoneBase, phonePort)
            CastProtocol.DLNA -> mappedRoute(device, phoneBase, phoneIp, phonePort)
            CastProtocol.GOOGLE_CAST -> null
        } ?: MediaRoute(phoneBase, MediaRoute.Kind.DIRECT_ACROSS_ROUTERS)
    }

    private fun tunnelRoute(device: CastDevice, phoneBase: String, phonePort: Int): MediaRoute? {
        val tunnelPort = try {
            ReceiverClient(device.host, device.receiverPort, connectTimeoutMs = 2_500, readTimeoutMs = 3_000).info().tunnelPort
        } catch (e: Exception) {
            null
        }
        if (tunnelPort == null) {
            Diag.log("route", "${device.name}: TV app has no tunnel (update it to 1.1.8+); trying directly")
            return null
        }
        val tunnel = TunnelClient(device.host, tunnelPort, phonePort, token = tunnelToken) { Diag.log("tunnel", it) }
        return try {
            val port = tunnel.open()
            Diag.log("route", "${device.name}: reverse tunnel ready (TV port $port)")
            MediaRoute(phoneBase, MediaRoute.Kind.TUNNEL, tvBase = { "http://127.0.0.1:${tunnel.remotePort}" }, onRelease = tunnel::close)
        } catch (e: Exception) {
            tunnel.close()
            Diag.log("route", "${device.name}: tunnel failed: ${e.message}")
            null
        }
    }

    private fun mappedRoute(device: CastDevice, phoneBase: String, phoneIp: String, phonePort: Int): MediaRoute? {
        val mapper = PortMapper(findGateway = PortMapper.discovery(phoneIp))
        val mapping = mapper.expose(device.host, phoneIp, phonePort, RouteTracer.privateHops()) ?: return null
        Diag.log("route", "${device.name}: via router port ${mapping.host}:${mapping.port}")
        return MediaRoute(
            phoneBase,
            MediaRoute.Kind.PORT_MAPPING,
            tvBase = { "http://${mapping.host}:${mapping.port}" },
            onRelease = { Thread { mapper.release(mapping.layers) }.start() },
        )
    }
}
