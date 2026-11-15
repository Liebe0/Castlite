package com.castlite.phone.net

import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

/** IPv4 address arithmetic for home networks (pure; unit-tested). */
object Subnets {

    fun toInt(ip: String): Int? {
        val parts = ip.trim().split('.')
        if (parts.size != 4) return null
        var v = 0
        for (p in parts) {
            val n = p.toIntOrNull() ?: return null
            if (n !in 0..255) return null
            v = (v shl 8) or n
        }
        return v
    }

    fun toIp(v: Int): String = "${v ushr 24 and 255}.${v ushr 16 and 255}.${v ushr 8 and 255}.${v and 255}"

    /** True if both addresses are in the same network of [prefixLength] bits. */
    fun sameSubnet(a: String, b: String, prefixLength: Int): Boolean {
        val x = toInt(a) ?: return false
        val y = toInt(b) ?: return false
        val mask = maskOf(prefixLength)
        return x and mask == y and mask
    }

    /** "192.168.2.17" → "192.168.2" (the /24 a home router hands out). */
    fun slash24(ip: String): String? = toInt(ip)?.let { toIp(it).substringBeforeLast('.') }

    /** The 254 host addresses of a /24 given as "192.168.2". */
    fun hostsOf(slash24: String): List<String> = (1..254).map { "$slash24.$it" }

    /**
     * Private LAN addresses (10/8, 172.16/12, 192.168/16). Carrier-grade NAT (100.64/10)
     * is deliberately *not* included: that's the ISP's network, shared with other customers.
     */
    fun isPrivate(ip: String): Boolean {
        val v = toInt(ip) ?: return false
        val a = v ushr 24 and 255
        val b = v ushr 16 and 255
        return a == 10 || (a == 172 && b in 16..31) || (a == 192 && b == 168)
    }

    private fun maskOf(prefixLength: Int): Int = if (prefixLength <= 0) 0 else -1 shl (32 - prefixLength.coerceAtMost(32))

    /** Prefix length of the phone's address (e.g. 24), from its network interface; 24 if unknown. */
    fun prefixLengthOf(localIp: String): Int = try {
        val addr = InetAddress.getByName(localIp)
        NetworkInterface.getByInetAddress(addr)?.interfaceAddresses
            ?.firstOrNull { it.address is Inet4Address && it.address == addr }
            ?.networkPrefixLength?.toInt()
            ?.takeIf { it in 8..30 }
            ?: 24
    } catch (e: Exception) {
        24
    }
}
