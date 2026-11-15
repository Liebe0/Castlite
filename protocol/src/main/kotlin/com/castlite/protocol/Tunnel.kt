package com.castlite.protocol

import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Reverse tunnel for a phone that sits behind another router.
 *
 * With routers chained (fiber router → router 2 → router 3), a phone on router 3 can open
 * connections to a TV on router 2 or 1, but the TV can't open connections back: router 3's
 * NAT drops them. Normally the TV *pulls* media from the phone's HTTP server, so in that
 * case the phone opens the connections instead:
 *
 * ```
 * phone ──TCP──► TV:8768   "CLTN1 <token>\n"
 *            ◄──           "OK <localPort>\n"     (the connection now waits in the TV's pool)
 *
 * TV player ──► 127.0.0.1:<localPort> ══(a pooled connection)══► phone ──► phone's media server
 * ```
 *
 * Bytes are copied both ways untouched, so HTTP range requests and keep-alive just work.
 * The phone keeps a few idle connections ready and replaces each one as soon as it's used;
 * the TV recycles idle ones after [TunnelProtocol.IDLE_RECYCLE_MS] so none goes stale.
 */
object TunnelProtocol {
    const val PORT = 8768
    const val MAGIC = "CLTN1"
    internal const val IDLE_RECYCLE_MS = 50_000L
    internal val TOKEN = Regex("[A-Za-z0-9]{8,64}")

    fun newToken(): String {
        val bytes = ByteArray(12).also { SecureRandom().nextBytes(it) }
        return bytes.joinToString("") { "%02x".format(it) }
    }
}

/** TV side: accepts the phone's connections and hands them to the local player. */
class TunnelServer(
    private val port: Int = TunnelProtocol.PORT,
    private val log: (String) -> Unit = {},
) : Closeable {

    private val executor: ExecutorService = daemonPool("tunnel-tv")
    private val pools = ConcurrentHashMap<String, Pool>()
    @Volatile private var server: ServerSocket? = null

    /** The port phones connect to, or -1 when stopped. */
    val listeningPort: Int get() = server?.localPort ?: -1

    @Synchronized
    fun start() {
        if (server != null) return
        val s = ServerSocket()
        s.reuseAddress = true
        s.bind(InetSocketAddress(port))
        server = s
        executor.execute { acceptLoop(s) }
        executor.execute { recycleLoop(s) }
    }

    @Synchronized
    override fun close() {
        server?.closeQuietly()
        server = null
        pools.values.forEach { it.close() }
        pools.clear()
    }

    private fun acceptLoop(s: ServerSocket) {
        while (!s.isClosed) {
            val sock = try {
                s.accept()
            } catch (e: IOException) {
                break
            }
            executor.execute { handshake(sock) }
        }
    }

    private fun handshake(sock: Socket) {
        try {
            sock.soTimeout = 5_000
            sock.tcpNoDelay = true
            val parts = readLine(sock.getInputStream())?.split(' ') ?: throw IOException("no header")
            if (parts.size != 2 || parts[0] != TunnelProtocol.MAGIC || !TunnelProtocol.TOKEN.matches(parts[1])) {
                throw IOException("not a CastLite tunnel")
            }
            val pool = pools.computeIfAbsent(parts[1]) { token ->
                Pool().also { log("Phone tunnel ${token.take(6)} from ${sock.inetAddress.hostAddress} → 127.0.0.1:${it.localPort}") }
            }
            sock.getOutputStream().apply {
                write("OK ${pool.localPort}\n".toByteArray(Charsets.US_ASCII))
                flush()
            }
            sock.soTimeout = 0
            sock.keepAlive = true
            pool.offer(sock)
        } catch (e: Exception) {
            sock.closeQuietly()
        }
    }

    /** Closes idle connections before routers forget them (the phone opens fresh ones). */
    private fun recycleLoop(s: ServerSocket) {
        while (!s.isClosed) {
            try {
                Thread.sleep(5_000)
            } catch (e: InterruptedException) {
                return
            }
            val now = System.currentTimeMillis()
            pools.entries.removeIf { (_, pool) ->
                pool.recycle(now)
                (now - pool.lastActivityMs > POOL_EXPIRY_MS).also { expired -> if (expired) pool.close() }
            }
        }
    }

    /** One phone's connections plus the loopback port the TV's player uses for them. */
    private inner class Pool {
        private val local = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        val localPort: Int = local.localPort
        private val idle = LinkedBlockingDeque<Pair<Socket, Long>>()
        @Volatile var lastActivityMs = System.currentTimeMillis()

        init {
            executor.execute {
                while (!local.isClosed) {
                    val client = try {
                        local.accept()
                    } catch (e: IOException) {
                        break
                    }
                    executor.execute { serve(client) }
                }
            }
        }

        fun offer(sock: Socket) {
            lastActivityMs = System.currentTimeMillis()
            idle.offerLast(sock to lastActivityMs)
        }

        private fun serve(client: Socket) {
            val phone = take(TAKE_TIMEOUT_MS)
            if (phone == null) {
                log("No connection from the phone for a player request")
                client.closeQuietly()
                return
            }
            lastActivityMs = System.currentTimeMillis()
            client.tcpNoDelay = true
            splice(executor, client, phone)
        }

        /** The newest idle connection that's still open, waiting for the phone if needed. */
        private fun take(timeoutMs: Long): Socket? {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (true) {
                val left = deadline - System.currentTimeMillis()
                if (left <= 0) return null
                val (sock, _) = idle.pollLast(left, TimeUnit.MILLISECONDS) ?: return null
                if (isAlive(sock)) return sock
                sock.closeQuietly()
            }
        }

        fun recycle(now: Long) {
            val it = idle.iterator()
            while (it.hasNext()) {
                val (sock, since) = it.next()
                if (now - since > TunnelProtocol.IDLE_RECYCLE_MS) {
                    it.remove()
                    sock.closeQuietly()
                }
            }
        }

        fun close() {
            local.closeQuietly()
            while (true) idle.pollFirst()?.first?.closeQuietly() ?: break
        }
    }

    private companion object {
        const val TAKE_TIMEOUT_MS = 10_000L
        /** A phone that hasn't connected for this long has gone; its loopback port is freed. */
        const val POOL_EXPIRY_MS = 6 * 60 * 60_000L
    }
}

/**
 * Phone side: keeps [poolSize] idle connections open to the TV and, when the TV uses one,
 * connects it to the phone's own media server on [localPort].
 */
class TunnelClient(
    private val tvHost: String,
    private val tvPort: Int,
    private val localPort: Int,
    private val token: String = TunnelProtocol.newToken(),
    private val poolSize: Int = 3,
    private val log: (String) -> Unit = {},
) : Closeable {

    private val executor: ExecutorService = daemonPool("tunnel-phone")
    private val sockets = ConcurrentHashMap.newKeySet<Socket>()
    @Volatile private var closed = false

    /** The TV-side loopback port to put in media URLs (`http://127.0.0.1:<port>/…`), or -1. */
    @Volatile var remotePort: Int = -1
        private set

    val isOpen: Boolean get() = !closed && remotePort > 0
    val mediaServerPort: Int get() = localPort

    /** Connects once (throws if the TV can't be reached) and starts the pool. Blocking. */
    fun open(timeoutMs: Int = 4_000): Int {
        val first = connect(timeoutMs)
        executor.execute { slot(first) }
        repeat(poolSize - 1) { executor.execute { slot(null) } }
        return remotePort
    }

    override fun close() {
        closed = true
        sockets.forEach { it.closeQuietly() }
        sockets.clear()
        executor.shutdown()
    }

    private fun connect(timeoutMs: Int): Socket {
        val sock = Socket()
        try {
            sock.connect(InetSocketAddress(tvHost, tvPort), timeoutMs)
            sock.tcpNoDelay = true
            sock.soTimeout = timeoutMs
            sock.getOutputStream().apply {
                write("${TunnelProtocol.MAGIC} $token\n".toByteArray(Charsets.US_ASCII))
                flush()
            }
            val reply = readLine(sock.getInputStream()) ?: throw IOException("The TV closed the tunnel")
            val port = reply.removePrefix("OK ").toIntOrNull()
            if (!reply.startsWith("OK ") || port == null) throw IOException("Unexpected tunnel reply: $reply")
            if (remotePort != port) {
                if (remotePort > 0) log("TV tunnel port changed $remotePort → $port (TV app restarted?)")
                remotePort = port
            }
            sock.soTimeout = 0
            sock.keepAlive = true
            sockets += sock
            if (closed) sock.closeQuietly()
            return sock
        } catch (e: IOException) {
            sock.closeQuietly()
            throw e
        }
    }

    /** Keeps one idle connection at the TV; when it gets used, serves it and opens another. */
    private fun slot(initial: Socket?) {
        var sock = initial
        var failures = 0
        while (!closed) {
            if (sock == null) {
                sock = try {
                    connect(4_000).also { failures = 0 }
                } catch (e: IOException) {
                    failures++
                    if (failures == 3) log("Tunnel to $tvHost: ${e.message}")
                    sleepQuietly(minOf(1_000L * failures, 10_000L))
                    continue
                }
            }
            val buf = ByteArray(16 * 1024)
            val n = try {
                sock.getInputStream().read(buf)
            } catch (e: IOException) {
                -1
            }
            val used = sock
            sock = null
            if (n <= 0) {
                // Recycled by the TV (or lost): just open a fresh one.
                sockets -= used
                used.closeQuietly()
                continue
            }
            try {
                executor.execute { serve(used, buf, n) }
            } catch (e: Exception) {
                used.closeQuietly() // closed meanwhile
            }
        }
        sock?.closeQuietly()
    }

    private fun serve(tv: Socket, first: ByteArray, length: Int) {
        val server = Socket()
        try {
            server.connect(InetSocketAddress(InetAddress.getByName("127.0.0.1"), localPort), 3_000)
            server.tcpNoDelay = true
            server.getOutputStream().write(first, 0, length)
        } catch (e: IOException) {
            log("Tunnel: media server not reachable: ${e.message}")
            server.closeQuietly()
            tv.closeQuietly()
            sockets -= tv
            return
        }
        sockets += server
        splice(executor, tv, server) {
            sockets -= tv
            sockets -= server
        }
    }
}

// ---- shared helpers -----------------------------------------------------------------

/** Copies bytes both ways until both directions end, then closes both sockets. */
internal fun splice(executor: ExecutorService, a: Socket, b: Socket, onClosed: () -> Unit = {}) {
    val finished = AtomicInteger(0)
    fun done() {
        if (finished.incrementAndGet() == 2) {
            a.closeQuietly()
            b.closeQuietly()
            onClosed()
        }
    }
    fun pump(from: Socket, to: Socket) {
        try {
            val input = from.getInputStream()
            val output = to.getOutputStream()
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                output.write(buf, 0, n)
            }
            runCatching { to.shutdownOutput() }
        } catch (e: IOException) {
            // One side went away: tear down both directions.
            a.closeQuietly()
            b.closeQuietly()
        } finally {
            done()
        }
    }
    try {
        executor.execute { pump(a, b) }
        executor.execute { pump(b, a) }
    } catch (e: Exception) {
        a.closeQuietly()
        b.closeQuietly()
        onClosed()
    }
}

/** True if the peer hasn't closed an idle connection (it never sends anything unprompted). */
private fun isAlive(sock: Socket): Boolean {
    if (sock.isClosed) return false
    return try {
        sock.soTimeout = 1
        sock.getInputStream().read() // -1: closed; a byte: protocol error
        false
    } catch (e: SocketTimeoutException) {
        true
    } catch (e: IOException) {
        false
    } finally {
        runCatching { sock.soTimeout = 0 }
    }
}

/** Reads one '\n'-terminated ASCII line byte by byte (nothing after it is consumed). */
private fun readLine(input: InputStream, max: Int = 128): String? {
    val sb = StringBuilder()
    while (sb.length < max) {
        val c = input.read()
        if (c < 0) return null
        if (c == '\n'.code) return sb.toString().trimEnd('\r')
        sb.append(c.toChar())
    }
    throw IOException("Header too long")
}

/** Worker threads that never take the app down: a failed connection only ends itself. */
private fun daemonPool(name: String): ExecutorService = Executors.newCachedThreadPool { r ->
    Thread(r, name).apply {
        isDaemon = true
        uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, _ -> }
    }
}

private fun sleepQuietly(ms: Long) = try {
    Thread.sleep(ms)
} catch (e: InterruptedException) {
    Unit
}

private fun Closeable.closeQuietly() = try {
    close()
} catch (e: Exception) {
    Unit
}
