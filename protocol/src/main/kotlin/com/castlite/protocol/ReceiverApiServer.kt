package com.castlite.protocol

import fi.iki.elonen.NanoHTTPD
import kotlinx.serialization.SerializationException
import java.io.IOException
import java.util.logging.Level
import java.util.logging.Logger

/**
 * What the TV app implements. Calls arrive on server worker threads; implementations
 * must hop to their own thread (the TV app posts to the main thread for ExoPlayer).
 */
interface ReceiverHandler {
    fun info(): ReceiverInfo
    fun status(): ReceiverStatus

    /**
     * Executes [command] and returns the new status.
     * @param client IP address of the phone that sent it (shown on the TV).
     * @throws IllegalArgumentException / IllegalStateException with a readable message.
     */
    fun handle(command: Command, client: String): ReceiverStatus

    /** Plain-text diagnostics (device info, capabilities, recent log). */
    fun diagnostics(): String = ""
}

/** HTTP server for the receiver API (see [ReceiverProtocol]). Runs inside the TV app. */
class ReceiverApiServer(
    private val handler: ReceiverHandler,
    port: Int = ReceiverProtocol.PORT,
) : NanoHTTPD(port) {

    init {
        Logger.getLogger(NanoHTTPD::class.java.name).level = Level.OFF
    }

    override fun serve(session: IHTTPSession): Response {
        val response = try {
            when {
                session.method == Method.OPTIONS -> json(Response.Status.NO_CONTENT, "")
                session.method == Method.GET && session.uri == ReceiverProtocol.PATH_INFO ->
                    ok(ReceiverProtocol.json.encodeToString(ReceiverInfo.serializer(), handler.info()))
                session.method == Method.GET && session.uri == ReceiverProtocol.PATH_STATUS ->
                    ok(ReceiverProtocol.json.encodeToString(ReceiverStatus.serializer(), handler.status()))
                session.method == Method.POST && session.uri == ReceiverProtocol.PATH_COMMAND -> command(session)
                session.method == Method.GET && session.uri == ReceiverProtocol.PATH_DIAGNOSTICS ->
                    newFixedLengthResponse(Response.Status.OK, "text/plain; charset=utf-8", handler.diagnostics())
                else -> result(Response.Status.NOT_FOUND, CommandResult(false, "Unknown endpoint ${session.method} ${session.uri}"))
            }
        } catch (e: Exception) {
            result(Response.Status.INTERNAL_ERROR, CommandResult(false, e.message ?: e.javaClass.simpleName))
        }
        response.addHeader("Access-Control-Allow-Origin", "*")
        response.addHeader("Access-Control-Allow-Headers", "Content-Type")
        response.addHeader("Cache-Control", "no-store")
        return response
    }

    private fun command(session: IHTTPSession): Response {
        val body = readBody(session)
        val command = try {
            ReceiverProtocol.json.decodeFromString(Command.serializer(), body)
        } catch (e: SerializationException) {
            return result(Response.Status.BAD_REQUEST, CommandResult(false, "Invalid command: ${e.message}"))
        } catch (e: IllegalArgumentException) {
            return result(Response.Status.BAD_REQUEST, CommandResult(false, "Invalid command: ${e.message}"))
        }
        return try {
            val status = handler.handle(command, session.remoteIpAddress ?: "")
            result(Response.Status.OK, CommandResult(true, status = status))
        } catch (e: IllegalArgumentException) {
            result(Response.Status.BAD_REQUEST, CommandResult(false, e.message))
        } catch (e: IllegalStateException) {
            result(Response.Status.CONFLICT, CommandResult(false, e.message))
        }
    }

    /**
     * Reads the request body as UTF-8. (NanoHTTPD's parseBody() decodes JSON as
     * US-ASCII, which would mangle non-English file names.)
     */
    private fun readBody(session: IHTTPSession): String {
        val length = session.headers["content-length"]?.toIntOrNull() ?: 0
        if (length <= 0) return ""
        require(length <= MAX_BODY) { "Command too large" }
        val buffer = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = session.inputStream.read(buffer, read, length - read)
            if (n < 0) throw IOException("Body ended early")
            read += n
        }
        return String(buffer, Charsets.UTF_8)
    }

    private fun ok(body: String) = json(Response.Status.OK, body)

    private fun result(status: Response.Status, result: CommandResult) =
        json(status, ReceiverProtocol.json.encodeToString(CommandResult.serializer(), result))

    private fun json(status: Response.Status, body: String): Response =
        newFixedLengthResponse(status, "application/json; charset=utf-8", body)

    private companion object {
        const val MAX_BODY = 1_000_000 // a queue of a few thousand items
    }
}
