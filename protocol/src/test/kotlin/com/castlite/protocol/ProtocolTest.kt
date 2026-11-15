package com.castlite.protocol

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL

/** Round-trips the JSON format and runs the real client against the real server. */
class ProtocolTest {

    private val json = ReceiverProtocol.json

    @Test fun commandsUseActionField() {
        assertEquals("""{"action":"seek","positionMs":90000}""", json.encodeToString(Command.serializer(), Command.Seek(90_000)))
        assertEquals("""{"action":"play"}""", json.encodeToString(Command.serializer(), Command.Play))
        assertEquals(Command.Pause, json.decodeFromString(Command.serializer(), """{"action":"pause"}"""))
    }

    @Test fun loadRoundTrips() {
        val load = Command.Load(
            items = listOf(QueueItem("http://1.2.3.4:8765/media/a/x.mp4", "Ünïcødé 🎬", "video/mp4", MediaType.VIDEO, 1000)),
            startIndex = 0, startPositionMs = 5, slideDurationMs = 3000,
        )
        assertEquals(load, json.decodeFromString(Command.serializer(), json.encodeToString(Command.serializer(), load)))
    }

    @Test fun unknownFieldsAreIgnored() {
        val status = json.decodeFromString(ReceiverStatus.serializer(), """{"state":"PLAYING","index":2,"future":true}""")
        assertEquals(ReceiverState.PLAYING, status.state)
        assertEquals(2, status.index)
    }

    // ---- end-to-end over HTTP -------------------------------------------------

    private val received = mutableListOf<Command>()
    private var current = ReceiverStatus()
    private lateinit var server: ReceiverApiServer
    private lateinit var client: ReceiverClient

    @Before fun startServer() {
        server = ReceiverApiServer(object : ReceiverHandler {
            override fun info() = ReceiverInfo("Living Room", "Test TV", "1.0")
            override fun status() = current
            override fun handle(command: Command, client: String): ReceiverStatus {
                received += command
                current = when (command) {
                    is Command.Load -> {
                        require(command.items.isNotEmpty()) { "Queue is empty" }
                        ReceiverStatus(ReceiverState.PLAYING, command.startIndex, command.items.size, title = command.items[command.startIndex].title)
                    }
                    is Command.SetVolume -> current.copy(volumePercent = command.percent)
                    Command.Next -> check(current.index + 1 < current.count) { "Already at the last item" }.let { current }
                    else -> current
                }
                return current
            }
        }, port = 0)
        server.start(5000, true)
        client = ReceiverClient("127.0.0.1", server.listeningPort)
    }

    @After fun stopServer() = server.stop()

    @Test fun infoAndStatus() {
        assertEquals("Living Room", client.info().name)
        assertEquals(ReceiverState.IDLE, client.status().state)
    }

    @Test fun commandsReachHandlerAndReturnStatus() {
        val item = QueueItem("http://p/1", "Holiday – día 1", "video/mp4", MediaType.VIDEO)
        val after = client.send(Command.Load(listOf(item, item.copy(title = "2"))))
        assertEquals(ReceiverState.PLAYING, after.state)
        assertEquals("Holiday – día 1", after.title) // UTF-8 survives the trip
        assertEquals(42, client.send(Command.SetVolume(42)).volumePercent)
        assertEquals(listOf("load", "volume"), received.map { it.javaClass.simpleName.lowercase().replace("setvolume", "volume") })
    }

    @Test fun handlerErrorsBecomeReadableExceptions() {
        try {
            client.send(Command.Load(emptyList()))
            fail("expected error")
        } catch (e: ReceiverException) {
            assertEquals("Queue is empty", e.message)
        }
        client.send(Command.Load(listOf(QueueItem("u", "t", "image/jpeg", MediaType.IMAGE))))
        try {
            client.send(Command.Next)
            fail("expected error")
        } catch (e: ReceiverException) {
            assertTrue(e.message!!.contains("last item"))
        }
    }

    @Test fun malformedJsonIs400() {
        val conn = URL("http://127.0.0.1:${server.listeningPort}${ReceiverProtocol.PATH_COMMAND}").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.outputStream.use { it.write("""{"action":"dance"}""".toByteArray()) }
        assertEquals(400, conn.responseCode)
    }
}
