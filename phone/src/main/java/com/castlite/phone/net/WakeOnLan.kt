package com.castlite.phone.net

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * Wake-on-LAN: a "magic packet" (6×0xFF then the MAC 16 times) broadcast on the LAN
 * switches on TVs that support it (LG: "Turn on via Wi-Fi", Android TV: "Network standby").
 */
object WakeOnLan {

    /** "AA:BB:CC:DD:EE:FF", "aa-bb-…" or "aabbccddeeff" → 6 bytes; null if invalid. */
    fun parseMac(text: String): ByteArray? {
        val hex = text.trim().replace(":", "").replace("-", "").replace(".", "")
        if (hex.length != 12 || !hex.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) return null
        return ByteArray(6) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }

    fun formatMac(text: String): String? = parseMac(text)?.joinToString(":") { "%02X".format(it) }

    fun magicPacket(mac: ByteArray): ByteArray {
        require(mac.size == 6)
        return ByteArray(6) { 0xFF.toByte() } + ByteArray(16 * 6) { mac[it % 6] }
    }

    /** For a /24 home network: 192.168.1.23 → 192.168.1.255. */
    fun subnetBroadcast(ip: String?): String? {
        val parts = ip?.split('.') ?: return null
        return if (parts.size == 4) "${parts[0]}.${parts[1]}.${parts[2]}.255" else null
    }

    /** Sends the packet a few times to the common broadcast addresses/ports. Blocking. */
    fun send(macText: String, phoneIp: String?) {
        val mac = parseMac(macText) ?: throw IllegalArgumentException("Invalid MAC address")
        val packet = magicPacket(mac)
        DatagramSocket().use { socket ->
            socket.broadcast = true
            val targets = listOfNotNull("255.255.255.255", subnetBroadcast(phoneIp)).distinct()
            repeat(3) {
                for (t in targets) for (port in intArrayOf(9, 7)) {
                    runCatching { socket.send(DatagramPacket(packet, packet.size, InetAddress.getByName(t), port)) }
                }
                Thread.sleep(100)
            }
        }
    }
}
