package com.castlite.phone.server

import android.util.Log
import com.castlite.phone.media.CastMedia
import com.castlite.phone.media.MediaLibrary
import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.logging.Level
import java.util.logging.Logger

/**
 * Minimal HTTP/1.1 server that streams picked files to TVs on the LAN.
 *
 * Routes:
 *  - `GET|HEAD /media/<id>/<name>` → the file, with `Range` support (206) so players can seek.
 *  - `GET /`                       → an HTML index of the queue, for testing in a browser.
 *
 * Only ids present in [MediaLibrary] are served, so nothing else on the phone is exposed.
 * NanoHTTPD handles each connection on its own thread.
 */
class MediaHttpServer(
    private val opener: MediaOpener,
    port: Int,
) : NanoHTTPD(port) {

    init {
        // NanoHTTPD logs every client disconnect (TVs probe and drop connections all the
        // time) as SEVERE. We log what matters ourselves.
        Logger.getLogger(NanoHTTPD::class.java.name).level = Level.OFF
    }

    override fun serve(session: IHTTPSession): Response {
        val response = try {
            route(session)
        } catch (e: Exception) {
            Log.e(TAG, "Request failed: ${session.uri}", e)
            text(Response.Status.INTERNAL_ERROR, "Internal error: ${e.message}")
        }
        // NanoHTTPD lower-cases all request header names.
        ServerMonitor.record(
            RequestLogEntry(
                timeMillis = System.currentTimeMillis(),
                client = session.remoteIpAddress ?: "?",
                method = session.method?.name ?: "?",
                path = session.uri ?: "",
                range = session.headers["range"],
                status = response.status.requestStatus,
                userAgent = session.headers["user-agent"],
            )
        )
        return response
    }

    private fun route(session: IHTTPSession): Response {
        val method = session.method
        if (method != Method.GET && method != Method.HEAD) {
            return text(Response.Status.METHOD_NOT_ALLOWED, "Only GET and HEAD are supported")
        }
        val path = session.uri ?: "/"
        return when {
            path == "/" || path == "/index.html" -> indexPage()
            path.startsWith("/media/") -> {
                val id = path.removePrefix("/media/").substringBefore('/')
                val media = MediaLibrary.find(id)
                    ?: return text(Response.Status.NOT_FOUND, "This file is no longer shared")
                serveMedia(session, media)
            }
            else -> text(Response.Status.NOT_FOUND, "Not found")
        }
    }

    /** Streams [media], honouring a single-range `Range` header. */
    private fun serveMedia(session: IHTTPSession, media: CastMedia): Response {
        val isHead = session.method == Method.HEAD
        val total = media.sizeBytes
        val seekable = total >= 0

        val response: Response = when (val range = RangeRequest.parse(session.headers["range"], total)) {
            is RangeRequest.Unsatisfiable -> {
                text(Response.Status.RANGE_NOT_SATISFIABLE, "Range not satisfiable").also {
                    it.addHeader("Content-Range", "bytes */$total")
                }
            }

            is RangeRequest.Partial -> {
                val body = if (isHead) emptyBody() else opener.open(media, range.start)
                    ?: return unreadable(media)
                newFixedLengthResponse(Response.Status.PARTIAL_CONTENT, media.mimeType, body, range.length).also {
                    it.addHeader("Content-Range", "bytes ${range.start}-${range.endInclusive}/$total")
                }
            }

            is RangeRequest.Full -> {
                val body = if (isHead) emptyBody() else opener.open(media, 0)
                    ?: return unreadable(media)
                if (seekable) {
                    newFixedLengthResponse(Response.Status.OK, media.mimeType, body, total)
                } else {
                    // Size unknown: stream with chunked encoding; seeking won't be possible.
                    newChunkedResponse(Response.Status.OK, media.mimeType, body)
                }
            }
        }

        if (seekable) response.addHeader("Accept-Ranges", "bytes")
        response.addHeader("transferMode.dlna.org", Dlna.transferMode(media.kind))
        response.addHeader("contentFeatures.dlna.org", Dlna.contentFeatures(media.kind, seekable))
        response.addHeader("Access-Control-Allow-Origin", "*")
        response.addHeader("Cache-Control", "no-cache")
        // Lets browsers show the real name when downloading; inline so they play it.
        response.addHeader("Content-Disposition", "inline; filename=\"${media.fileName}\"")
        return response
    }

    private fun unreadable(media: CastMedia): Response = text(
        Response.Status.INTERNAL_ERROR,
        "Can't read \"${media.displayName}\". It may have been moved or deleted — pick it again.",
    )

    /** HEAD responses carry the real Content-Length but no body. */
    private fun emptyBody(): InputStream = ByteArrayInputStream(ByteArray(0))

    private fun text(status: Response.Status, message: String): Response =
        newFixedLengthResponse(status, "text/plain; charset=utf-8", message)

    /** A tiny HTML page so the server can be tested from any browser on the same Wi-Fi. */
    private fun indexPage(): Response {
        val rows = MediaLibrary.items.value.joinToString("\n") { m ->
            val size = if (m.sizeBytes >= 0) "%.1f MB".format(m.sizeBytes / 1_048_576.0) else "unknown size"
            "<li><a href=\"${m.path}\">${html(m.displayName)}</a> <small>${html(m.mimeType)} · $size</small></li>"
        }.ifEmpty { "<li>No files shared yet. Pick some in the CastLite app.</li>" }
        val page = """
            <!doctype html><html><head><meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <title>CastLite</title>
            <style>body{background:#0f1115;color:#e6e6e6;font-family:sans-serif;padding:16px}
            a{color:#4fc3f7}li{margin:8px 0}small{color:#999}</style></head>
            <body><h2>CastLite media server</h2><ul>$rows</ul></body></html>
        """.trimIndent()
        return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", page)
    }

    private fun html(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    companion object {
        private const val TAG = "MediaHttpServer"

        /** Preferred port; if it's taken we try the next few. */
        const val PREFERRED_PORT = 8765
        const val PORT_ATTEMPTS = 10
    }
}
