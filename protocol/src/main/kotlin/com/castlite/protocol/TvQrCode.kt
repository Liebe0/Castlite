package com.castlite.protocol

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * What the TV's QR code says: `castlite://connect?host=192.168.1.60&port=8766&name=Living%20Room`.
 * It's a link, so scanning it with the phone's normal camera app also opens CastLite.
 * The phone's own scanner additionally accepts a bare "192.168.1.60[:8766]".
 */
object TvQrCode {
    data class Target(val host: String, val port: Int = ReceiverProtocol.PORT, val name: String? = null)

    fun build(host: String, port: Int = ReceiverProtocol.PORT, name: String? = null): String = buildString {
        append("castlite://connect?host=").append(enc(host)).append("&port=").append(port)
        if (!name.isNullOrBlank()) append("&name=").append(enc(name))
    }

    fun parse(text: String): Target? {
        val t = text.trim()
        return when {
            t.startsWith("castlite://", ignoreCase = true) -> parseLink(t)
            t.startsWith("http://", ignoreCase = true) -> runCatching {
                val uri = URI(t)
                uri.host?.takeIf(::looksLikeHost)?.let { Target(it, if (uri.port > 0) uri.port else ReceiverProtocol.PORT) }
            }.getOrNull()
            else -> {
                val host = t.substringBefore(':')
                val port = t.substringAfter(':', "").toIntOrNull() ?: ReceiverProtocol.PORT
                if (IPV4.matches(host) && port in 1..65535) Target(host, port) else null
            }
        }
    }

    private fun parseLink(t: String): Target? {
        val query = t.substringAfter('?', "")
        if (query.isEmpty()) return null
        val params = query.split('&').mapNotNull { kv ->
            val k = kv.substringBefore('=')
            val v = kv.substringAfter('=', "")
            if (k.isEmpty()) null else k to dec(v)
        }.toMap()
        val host = params["host"]?.takeIf(::looksLikeHost) ?: return null
        val port = params["port"]?.toIntOrNull()?.takeIf { it in 1..65535 } ?: ReceiverProtocol.PORT
        return Target(host, port, params["name"]?.takeIf { it.isNotBlank() })
    }

    private val IPV4 = Regex("""^((25[0-5]|2[0-4]\d|1?\d?\d)\.){3}(25[0-5]|2[0-4]\d|1?\d?\d)$""")
    private val HOSTNAME = Regex("""^[A-Za-z0-9]([A-Za-z0-9-]{0,62})(\.[A-Za-z0-9-]{1,63})*$""")

    private fun looksLikeHost(h: String) = IPV4.matches(h) || HOSTNAME.matches(h)
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
    private fun dec(s: String) = runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)
}
