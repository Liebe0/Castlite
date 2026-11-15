package com.castlite.phone

import com.castlite.phone.cast.MediaRoute
import com.castlite.phone.net.Gateway
import com.castlite.phone.net.IgdDescription
import com.castlite.phone.net.PortMapper
import com.castlite.phone.net.RouteTracer
import com.castlite.phone.net.Subnets
import fi.iki.elonen.NanoHTTPD
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections

class CrossRouterTest {

    // ---- Subnets ------------------------------------------------------------------

    @Test fun subnetMath() {
        assertTrue(Subnets.sameSubnet("192.168.3.20", "192.168.3.200", 24))
        assertFalse(Subnets.sameSubnet("192.168.3.20", "192.168.2.20", 24))
        assertTrue(Subnets.sameSubnet("192.168.3.20", "192.168.2.20", 16))
        assertEquals("192.168.2", Subnets.slash24("192.168.2.105"))
        assertEquals(254, Subnets.hostsOf("10.0.0").size)
        assertEquals("10.0.0.254", Subnets.hostsOf("10.0.0").last())
        assertNull(Subnets.toInt("192.168.1"))
        assertNull(Subnets.toInt("192.168.1.300"))
        assertEquals("172.16.5.4", Subnets.toIp(Subnets.toInt("172.16.5.4")!!))
    }

    @Test fun onlyRealLanAddressesArePrivate() {
        listOf("10.1.2.3", "172.16.0.1", "172.31.255.1", "192.168.0.1").forEach { assertTrue(it, Subnets.isPrivate(it)) }
        // Public, and the ISP's carrier-grade NAT range: never open ports towards those.
        listOf("8.8.8.8", "172.32.0.1", "100.64.0.1", "85.10.20.30").forEach { assertFalse(it, Subnets.isPrivate(it)) }
    }

    // ---- RouteTracer ----------------------------------------------------------------

    @Test fun readsTheRouterThatAnsweredAPing() {
        assertEquals("192.168.3.1", RouteTracer.parseReplyFrom("PING 1.1.1.1 (1.1.1.1) 56(84) bytes of data.\nFrom 192.168.3.1: icmp_seq=1 Time to live exceeded\n"))
        assertEquals("192.168.2.1", RouteTracer.parseReplyFrom("From 192.168.2.1 icmp_seq=1 Time to live exceeded"))
        assertEquals("192.168.1.1", RouteTracer.parseReplyFrom("From router.home (192.168.1.1) icmp_seq=1 Time to live exceeded"))
        assertEquals("1.1.1.1", RouteTracer.parseReplyFrom("64 bytes from 1.1.1.1: icmp_seq=1 ttl=57 time=9.1 ms"))
        assertNull(RouteTracer.parseReplyFrom("PING 1.1.1.1 (1.1.1.1) 56(84) bytes of data.\n\n--- 1.1.1.1 ping statistics ---\n1 packets transmitted, 0 received"))
    }

    // ---- MediaRoute -----------------------------------------------------------------

    @Test fun routeRewritesOnlyThePhonesOwnUrls() {
        val tunnel = MediaRoute("http://192.168.3.20:8090", MediaRoute.Kind.TUNNEL, tvBase = { "http://127.0.0.1:40123" })
        assertEquals("http://127.0.0.1:40123/media/7?x=1", tunnel.map("http://192.168.3.20:8090/media/7?x=1"))
        assertEquals("https://example.com/a.mp4", tunnel.map("https://example.com/a.mp4"))
        val direct = MediaRoute("http://192.168.3.20:8090", MediaRoute.Kind.DIRECT)
        assertEquals("http://192.168.3.20:8090/media/7", direct.map("http://192.168.3.20:8090/media/7"))
    }

    // ---- IGD description --------------------------------------------------------------

    @Test fun findsTheRoutersPortForwardingService() {
        val xml = """<?xml version="1.0"?>
            <root xmlns="urn:schemas-upnp-org:device-1-0"><device>
              <deviceType>urn:schemas-upnp-org:device:InternetGatewayDevice:1</deviceType>
              <deviceList><device><deviceType>urn:schemas-upnp-org:device:WANDevice:1</deviceType>
                <deviceList><device><deviceType>urn:schemas-upnp-org:device:WANConnectionDevice:1</deviceType>
                  <serviceList><service>
                    <serviceType>urn:schemas-upnp-org:service:WANIPConnection:1</serviceType>
                    <controlURL>/ctl/IPConn</controlURL>
                  </service></serviceList>
                </device></deviceList>
              </device></deviceList>
            </device></root>"""
        val gw = IgdDescription.parse(xml, "http://192.168.3.1:5000/rootDesc.xml")!!
        assertEquals("http://192.168.3.1:5000/ctl/IPConn", gw.controlUrl)
        assertEquals("urn:schemas-upnp-org:service:WANIPConnection:1", gw.serviceType)
        assertNull(IgdDescription.parse("<root><device><serviceList/></device></root>", "http://x/"))
    }

    // ---- PortMapper with fake routers ----------------------------------------------------

    /** A pretend UPnP router: answers GetExternalIPAddress and records port mappings. */
    private class FakeRouter(val externalIp: String, val failFirstAdd: Int? = null) : NanoHTTPD("127.0.0.1", 0) {
        val calls: MutableList<Pair<String, Map<String, String>>> = Collections.synchronizedList(mutableListOf())
        private var failed = false
        val gateway get() = Gateway("http://127.0.0.1:$listeningPort/ctl", "urn:schemas-upnp-org:service:WANIPConnection:1")

        override fun serve(session: IHTTPSession): Response {
            val files = HashMap<String, String>()
            session.parseBody(files)
            val action = session.headers["soapaction"].orEmpty().trim('"').substringAfter('#')
            val args = Regex("<(New\\w+)>([^<]*)</New\\w+>").findAll(files["postData"].orEmpty()).associate { it.groupValues[1] to it.groupValues[2] }
            calls += action to args
            if (action == "AddPortMapping" && failFirstAdd != null && !failed) {
                failed = true
                return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/xml", fault(failFirstAdd))
            }
            val out = if (action == "GetExternalIPAddress") "<NewExternalIPAddress>$externalIp</NewExternalIPAddress>" else ""
            return newFixedLengthResponse(
                Response.Status.OK, "text/xml",
                """<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body><u:${action}Response xmlns:u="urn:schemas-upnp-org:service:WANIPConnection:1">$out</u:${action}Response></s:Body></s:Envelope>""",
            )
        }

        private fun fault(code: Int) = """<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body><s:Fault>
            <faultcode>s:Client</faultcode><faultstring>UPnPError</faultstring><detail><UPnPError xmlns="urn:schemas-upnp-org:control-1-0">
            <errorCode>$code</errorCode><errorDescription>nope</errorDescription></UPnPError></detail></s:Fault></s:Body></s:Envelope>"""

        fun adds() = calls.filter { it.first == "AddPortMapping" }.map { it.second }
    }

    private val routers = mutableListOf<FakeRouter>()
    private fun router(ip: String, failFirstAdd: Int? = null) = FakeRouter(ip, failFirstAdd).also { it.start(5_000, true); routers += it }

    @After fun stopRouters() = routers.forEach { it.stop() }

    /** Phone 192.168.3.20 on router 3 (outside 192.168.2.105), router 2 (outside 192.168.1.50), fiber router. */
    private fun chain(router3: FakeRouter, router2: FakeRouter?, seen: MutableList<List<String>?> = mutableListOf()) =
        PortMapper(findGateway = { candidates ->
            seen += candidates
            when {
                candidates == null -> router3.gateway
                "192.168.2.1" in candidates -> router2?.gateway
                else -> null
            }
        }, log = {})

    @Test fun tvOnTheRouterAboveGetsOnePort() {
        val r3 = router("192.168.2.105")
        val mapping = chain(r3, null).expose("192.168.2.40", "192.168.3.20", 8090, routerHops = listOf("192.168.3.1", "192.168.2.1"))!!
        assertEquals("192.168.2.105", mapping.host)
        assertEquals(8090, mapping.port)
        val add = r3.adds().single()
        assertEquals("192.168.3.20", add["NewInternalClient"])
        assertEquals("8090", add["NewInternalPort"])
        assertEquals("TCP", add["NewProtocol"])
    }

    @Test fun tvTwoRoutersUpGetsAChainOfPorts() {
        val r3 = router("192.168.2.105")
        val r2 = router("192.168.1.50")
        val seen = mutableListOf<List<String>?>()
        val mapper = chain(r3, r2, seen)
        val mapping = mapper.expose("192.168.1.20", "192.168.3.20", 8090, routerHops = listOf("192.168.3.1", "192.168.2.1", "192.168.1.1"))!!
        assertEquals("192.168.1.50", mapping.host)
        assertEquals(2, mapping.layers.size)
        // Router 2 forwards to router 3's outside address, which forwards to the phone.
        val add2 = r2.adds().single()
        assertEquals("192.168.2.105", add2["NewInternalClient"])
        assertEquals("8090", add2["NewInternalPort"])
        // Only routers on the network just above were asked.
        assertEquals(listOf("192.168.2.1"), seen[1])

        mapper.release(mapping.layers)
        assertTrue(r2.calls.any { it.first == "DeletePortMapping" })
        assertTrue(r3.calls.any { it.first == "DeletePortMapping" })
    }

    @Test fun neverOpensAPortOnTheRouterFacingTheInternet() {
        val fiber = router("85.10.20.30")
        assertNull(chain(fiber, null).expose("192.168.2.40", "192.168.1.20", 8090, emptyList()))
        assertTrue(fiber.adds().isEmpty())
    }

    @Test fun takenPortTriesAnother() {
        val r3 = router("192.168.2.105", failFirstAdd = 718)
        val mapping = chain(r3, null).expose("192.168.2.40", "192.168.3.20", 8090, emptyList())!!
        assertNotEquals(8090, mapping.port)
        assertTrue(mapping.port in 41_000..49_000)
    }

    @Test fun routerThatOnlyAllowsPermanentLeases() {
        val r3 = router("192.168.2.105", failFirstAdd = 725)
        val mapping = chain(r3, null).expose("192.168.2.40", "192.168.3.20", 8090, emptyList())!!
        assertEquals(8090, mapping.port)
        assertEquals("0", r3.adds().last()["NewLeaseDuration"])
    }

    @Test fun unreachableNetworkCleansUp() {
        val r3 = router("192.168.2.105")
        // TV on a network no router leads to: the mapping made on the way is removed again.
        assertNull(chain(r3, null).expose("10.9.9.9", "192.168.3.20", 8090, emptyList()))
        assertTrue(r3.calls.any { it.first == "DeletePortMapping" })
    }
}
