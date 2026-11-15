package com.castlite.protocol

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Thrown when the TV answered but refused a command; [message] is shown to the user. */
class ReceiverException(message: String) : IOException(message)

/**
 * Blocking HTTP client for the receiver API. Used by the phone (call from a
 * background dispatcher). Network failures surface as [IOException].
 */
class ReceiverClient(
    val host: String,
    val port: Int = ReceiverProtocol.PORT,
    private val connectTimeoutMs: Int = 3_000,
    private val readTimeoutMs: Int = 5_000,
) {
    private val base = "http://${formatHost(host)}:$port"

    fun info(): ReceiverInfo =
        ReceiverProtocol.json.decodeFromString(ReceiverInfo.serializer(), get(ReceiverProtocol.PATH_INFO))

    fun status(): ReceiverStatus =
        ReceiverProtocol.json.decodeFromString(ReceiverStatus.serializer(), get(ReceiverProtocol.PATH_STATUS))

    /** The TV app's own log and device info (empty for old TV apps). */
    fun diagnostics(): String = try {
        get(ReceiverProtocol.PATH_DIAGNOSTICS)
    } catch (e: IOException) {
        "(TV diagnostics unavailable: ${e.message})"
    }

    /** Sends [command]; returns the TV's status right after executing it. */
    fun send(command: Command): ReceiverStatus {
        val body = ReceiverProtocol.json.encodeToString(Command.serializer(), command)
        val conn = open(ReceiverProtocol.PATH_COMMAND).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
        }
        try {
            val bytes = body.toByteArray(Charsets.UTF_8)
            conn.setFixedLengthStreamingMode(bytes.size)
            conn.outputStream.use { it.write(bytes) }
            val text = readText(conn)
            val result = try {
                ReceiverProtocol.json.decodeFromString(CommandResult.serializer(), text)
            } catch (e: Exception) {
                throw IOException("Unexpected reply from TV (HTTP ${conn.responseCode})")
            }
            if (!result.ok) throw ReceiverException(result.error ?: "The TV refused the command")
            return result.status ?: status()
        } finally {
            conn.disconnect()
        }
    }

    private fun get(path: String): String {
        val conn = open(path)
        try {
            if (conn.responseCode != 200) throw IOException("TV replied HTTP ${conn.responseCode} for $path")
            return readText(conn)
        } finally {
            conn.disconnect()
        }
    }

    private fun open(path: String): HttpURLConnection =
        (URL(base + path).openConnection() as HttpURLConnection).apply {
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            useCaches = false
        }

    private fun readText(conn: HttpURLConnection): String {
        val stream = if (conn.responseCode < 400) conn.inputStream else conn.errorStream
        return stream?.use { String(it.readBytes(), Charsets.UTF_8) } ?: ""
    }

    private companion object {
        fun formatHost(host: String) = if (host.contains(':') && !host.startsWith("[")) "[$host]" else host
    }
}
