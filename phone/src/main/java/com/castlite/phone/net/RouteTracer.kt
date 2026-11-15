package com.castlite.phone.net

import com.castlite.phone.diagnostics.Diag

/**
 * Finds the routers between the phone and the internet, like `traceroute`: a ping with
 * TTL 1 is answered by the first router, TTL 2 by the second, and so on. With chained
 * routers (fiber router → router 2 → router 3) that reveals the networks *above* the
 * phone's own, where other TVs may live.
 *
 * Uses Android's `ping` tool (no root needed). Stops at the first public address, so the
 * probes never leave the house.
 */
object RouteTracer {

    /** Private router addresses, nearest first (e.g. [192.168.3.1, 192.168.2.1, 192.168.1.1]). */
    fun privateHops(maxHops: Int = 4, target: String = "1.1.1.1"): List<String> {
        val hops = mutableListOf<String>()
        for (ttl in 1..maxHops) {
            val hop = probe(ttl, target) ?: break
            if (!Subnets.isPrivate(hop)) break
            if (hop !in hops) hops += hop
        }
        Diag.log("scan", "Routers on the way out: ${hops.ifEmpty { listOf("none found") }}")
        return hops
    }

    private fun probe(ttl: Int, target: String): String? = try {
        val process = ProcessBuilder("ping", "-c", "1", "-W", "1", "-t", ttl.toString(), target)
            .redirectErrorStream(true)
            .start()
        // Some ping builds ignore -W: never wait more than 3 s for one hop.
        val watchdog = Thread {
            try {
                Thread.sleep(3_000)
                process.destroy()
            } catch (e: InterruptedException) {
                Unit
            }
        }.apply {
            isDaemon = true
            start()
        }
        val output = process.inputStream.bufferedReader().use { it.readText() }
        watchdog.interrupt()
        process.destroy()
        parseReplyFrom(output)
    } catch (e: Exception) {
        null
    }

    private val FROM = Regex("""(?i)\bfrom\s+(?:[^\s(]+\s+\()?(\d{1,3}(?:\.\d{1,3}){3})""")

    /** The address that answered, from ping's "From 192.168.1.1: icmp_seq=1 Time to live exceeded". */
    fun parseReplyFrom(output: String): String? = FROM.find(output)?.groupValues?.get(1)?.takeIf { Subnets.toInt(it) != null }
}
