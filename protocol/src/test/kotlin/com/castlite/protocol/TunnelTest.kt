package com.castlite.protocol

import fi.iki.elonen.NanoHTTPD
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.random.Random

/**
 * The whole path on one machine: "TV player" → TunnelServer's loopback port → pooled
 * connection → TunnelClient → a media server with range support.
 */
class TunnelTest {

    private val file = Random(3).nextBytes(400_000)
    private lateinit var media: NanoHTTPD
    private lateinit var tv: TunnelServer
    private var client: TunnelClient? = null

    @Before fun setUp() {
        media = object : NanoHTTPD("127.0.0.1", 0) {
            override fun serve(session: IHTTPSession): Response {
                if (session.uri != "/media/1") return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "nope")
                val range = session.headers["range"]?.removePrefix("bytes=")?.split("-")
                return if (range != null) {
                    val start = range[0].toInt()
                    val end = range.getOrNull(1)?.takeIf { it.isNotEmpty() }?.toInt() ?: (file.size - 1)
                    newFixedLengthResponse(Response.Status.PARTIAL_CONTENT, "video/mp4", file.copyOfRange(start, end + 1).inputStream(), (end - start + 1).toLong()).apply {
                        addHeader("Content-Range", "bytes $start-$end/${file.size}")
                    }
                } else {
                    newFixedLengthResponse(Response.Status.OK, "video/mp4", file.inputStream(), file.size.toLong())
                }
            }
        }.apply { start(5_000, true) }
        tv = TunnelServer(port = 0).apply { start() }
    }

    @After fun tearDown() {
        client?.close()
        tv.close()
        media.stop()
    }

    private fun openTunnel(poolSize: Int = 2): Int {
        val c = TunnelClient("127.0.0.1", tv.listeningPort, media.listeningPort, poolSize = poolSize)
        client = c
        return c.open()
    }

    private fun get(port: Int, range: String? = null): Pair<Int, ByteArray> {
        val conn = URL("http://127.0.0.1:$port/media/1").openConnection() as HttpURLConnection
        conn.connectTimeout = 5_000
        conn.readTimeout = 10_000
        range?.let { conn.setRequestProperty("Range", it) }
        return try {
            conn.responseCode to conn.inputStream.use { it.readBytes() }
        } finally {
            conn.disconnect()
        }
    }

    @Test fun wholeFileThroughTheTunnel() {
        val port = openTunnel()
        assertTrue(port > 0)
        val (code, body) = get(port)
        assertEquals(200, code)
        assertArrayEquals(file, body)
    }

    @Test fun rangeRequestsPassUntouched() {
        val port = openTunnel()
        val (code, body) = get(port, "bytes=1000-1999")
        assertEquals(206, code)
        assertArrayEquals(file.copyOfRange(1000, 2000), body)
    }

    @Test fun moreParallelRequestsThanIdleConnections() {
        val port = openTunnel(poolSize = 2)
        val pool = Executors.newFixedThreadPool(6)
        try {
            val results = (0 until 6).map { i ->
                pool.submit(Callable { get(port, "bytes=${i * 10_000}-${i * 10_000 + 4_999}") })
            }.map { it.get() }
            results.forEachIndexed { i, (code, body) ->
                assertEquals(206, code)
                assertArrayEquals(file.copyOfRange(i * 10_000, i * 10_000 + 5_000), body)
            }
        } finally {
            pool.shutdownNow()
        }
    }

    @Test fun samePhoneTokenKeepsTheSamePortAfterReconnecting() {
        val token = TunnelProtocol.newToken()
        val a = TunnelClient("127.0.0.1", tv.listeningPort, media.listeningPort, token = token)
        val first = a.open()
        a.close()
        val b = TunnelClient("127.0.0.1", tv.listeningPort, media.listeningPort, token = token)
        client = b
        assertEquals(first, b.open())
        assertEquals(200, get(first).first)
    }

    @Test fun rejectsGarbage() {
        java.net.Socket("127.0.0.1", tv.listeningPort).use { s ->
            s.soTimeout = 3_000
            s.getOutputStream().write("GET / HTTP/1.1\r\n\r\n".toByteArray())
            assertEquals(-1, s.getInputStream().read())
        }
    }
}
