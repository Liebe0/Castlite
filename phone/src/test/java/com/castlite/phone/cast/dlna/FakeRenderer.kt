package com.castlite.phone.cast.dlna

import fi.iki.elonen.NanoHTTPD
import java.util.Collections

/**
 * A pretend DLNA TV for tests: answers AVTransport / RenderingControl SOAP calls,
 * records them, and lets the test drive the transport state.
 */
class FakeRenderer : NanoHTTPD(0) {

    data class Call(val action: String, val body: String)

    val calls: MutableList<Call> = Collections.synchronizedList(mutableListOf())

    @Volatile var transportState = "NO_MEDIA_PRESENT"
    @Volatile var relTime = "0:00:00"
    @Volatile var duration = "0:05:00"
    @Volatile var volume = 30
    /** If set, SetAVTransportURI fails with this UPnP error code. */
    @Volatile var rejectUriWith: Int? = null
    /** Play fails with 701 this many times first (TV still "transitioning"). */
    @Volatile var playFailures = 0
    /** When true, Play leaves the TV in TRANSITIONING (codec refused) instead of PLAYING. */
    @Volatile var neverStarts = false

    val baseUrl: String get() = "http://127.0.0.1:$listeningPort"

    fun actions(): List<String> = synchronized(calls) { calls.map { it.action } }

    override fun serve(session: IHTTPSession): Response {
        val length = session.headers["content-length"]?.toIntOrNull() ?: 0
        val buf = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = session.inputStream.read(buf, read, length - read)
            if (n < 0) break
            read += n
        }
        val body = String(buf, Charsets.UTF_8)
        val soapAction = session.headers["soapaction"].orEmpty().trim('"')
        val service = soapAction.substringBefore('#')
        val action = soapAction.substringAfter('#')
        calls += Call(action, body)

        val out: Map<String, String> = when (action) {
            "SetAVTransportURI" -> {
                rejectUriWith?.let { return fault(it, "Illegal MIME-type") }
                transportState = "STOPPED"
                emptyMap()
            }
            "Play" -> {
                if (playFailures > 0) {
                    playFailures--
                    return fault(701, "Transition not available")
                }
                transportState = if (neverStarts) "TRANSITIONING" else "PLAYING"
                emptyMap()
            }
            "Pause" -> { transportState = "PAUSED_PLAYBACK"; emptyMap() }
            "Stop" -> { transportState = "STOPPED"; emptyMap() }
            "Seek" -> {
                relTime = Regex("<Target>(.*)</Target>").find(body)?.groupValues?.get(1) ?: relTime
                emptyMap()
            }
            "GetTransportInfo" -> mapOf("CurrentTransportState" to transportState, "CurrentTransportStatus" to "OK", "CurrentSpeed" to "1")
            "GetPositionInfo" -> mapOf("Track" to "1", "TrackDuration" to duration, "RelTime" to relTime, "AbsTime" to relTime)
            "GetVolume" -> mapOf("CurrentVolume" to volume.toString())
            "SetVolume" -> {
                volume = Regex("<DesiredVolume>(\\d+)</DesiredVolume>").find(body)!!.groupValues[1].toInt()
                emptyMap()
            }
            else -> return fault(401, "Invalid Action")
        }
        val xml = buildString {
            append("<?xml version=\"1.0\"?><s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"><s:Body>")
            append("<u:${action}Response xmlns:u=\"$service\">")
            out.forEach { (k, v) -> append("<$k>$v</$k>") }
            append("</u:${action}Response></s:Body></s:Envelope>")
        }
        return newFixedLengthResponse(Response.Status.OK, "text/xml; charset=\"utf-8\"", xml)
    }

    private fun fault(code: Int, description: String): Response = newFixedLengthResponse(
        Response.Status.INTERNAL_ERROR,
        "text/xml",
        "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"><s:Body><s:Fault><faultcode>s:Client</faultcode>" +
            "<faultstring>UPnPError</faultstring><detail><UPnPError xmlns=\"urn:schemas-upnp-org:control-1-0\">" +
            "<errorCode>$code</errorCode><errorDescription>$description</errorDescription></UPnPError></detail>" +
            "</s:Fault></s:Body></s:Envelope>",
    )
}
