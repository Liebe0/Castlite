package com.castlite.phone.cast.dlna

import com.castlite.phone.cast.dlna.Xml.children
import com.castlite.phone.cast.dlna.Xml.descendants
import com.castlite.phone.cast.dlna.Xml.localNameOrName
import org.w3c.dom.Element
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** A UPnP error returned by the TV (SOAP fault with an errorCode). */
class UpnpException(val code: Int?, val description: String?) :
    IOException("UPnP error ${code ?: "?"}${description?.let { ": $it" } ?: ""}")

/**
 * SOAP over HTTP, the control half of UPnP. Every action is a POST of an XML envelope
 * to the service's control URL with a SOAPACTION header.
 */
object Soap {

    fun envelope(serviceType: String, action: String, args: List<Pair<String, String>>): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"utf-8\"?>")
        append("<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" ")
        append("s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body>")
        append("<u:$action xmlns:u=\"$serviceType\">")
        for ((name, value) in args) append("<$name>").append(Xml.escape(value)).append("</$name>")
        append("</u:$action></s:Body></s:Envelope>")
    }

    /** Output arguments of `<u:ActionResponse>` as a name → text map. */
    fun parseResponse(xml: String, action: String): Map<String, String> {
        val root = Xml.parse(xml).documentElement
        val response = root.descendants("${action}Response").firstOrNull() ?: return emptyMap()
        return response.children().associate { it.localNameOrName() to (it.textContent ?: "") }
    }

    /** Extracts errorCode / errorDescription from a SOAP fault, if the body is one. */
    fun parseFault(xml: String): UpnpException? = try {
        val root = Xml.parse(xml).documentElement
        if (root.descendants("Fault").isEmpty()) {
            null
        } else {
            val error = root.descendants("UPnPError").firstOrNull()
            UpnpException(
                code = error?.descendants("errorCode")?.firstOrNull()?.textContent?.trim()?.toIntOrNull(),
                description = error?.descendants("errorDescription")?.firstOrNull()?.textContent?.trim()
                    ?: root.descendants("faultstring").firstOrNull()?.textContent?.trim(),
            )
        }
    } catch (e: Exception) {
        null
    }

    private fun Element.children(): List<Element> = buildList {
        val nodes = childNodes
        for (i in 0 until nodes.length) (nodes.item(i) as? Element)?.let(::add)
    }
}

/** Blocking SOAP client; call from Dispatchers.IO. */
class SoapClient(private val connectTimeoutMs: Int = 3_000, private val readTimeoutMs: Int = 6_000) {

    fun call(controlUrl: String, serviceType: String, action: String, args: List<Pair<String, String>>): Map<String, String> {
        val body = Soap.envelope(serviceType, action, args).toByteArray(Charsets.UTF_8)
        val conn = (URL(controlUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            useCaches = false
            setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
            setRequestProperty("SOAPACTION", "\"$serviceType#$action\"")
            setRequestProperty("User-Agent", "Android/1 UPnP/1.1 CastLite/1.0")
            setFixedLengthStreamingMode(body.size)
        }
        try {
            conn.outputStream.use { it.write(body) }
            val code = conn.responseCode
            val stream = if (code < 400) conn.inputStream else conn.errorStream
            val text = stream?.use { String(it.readBytes(), Charsets.UTF_8) }.orEmpty()
            if (code >= 400) throw Soap.parseFault(text) ?: IOException("TV replied HTTP $code to $action")
            return if (text.isBlank()) emptyMap() else Soap.parseResponse(text, action)
        } finally {
            conn.disconnect()
        }
    }
}
