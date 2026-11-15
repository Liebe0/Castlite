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

class ProtocolV2Test {
    private val json = ReceiverProtocol.json

    @Test fun newCommandsAndFields() {
        assertEquals("""{"action":"repeat","mode":"ALL"}""", json.encodeToString(Command.serializer(), Command.SetRepeat(RepeatMode.ALL)))
        assertEquals("""{"action":"subtitles","enabled":false}""", json.encodeToString(Command.serializer(), Command.SetSubtitles(false)))
        val load = Command.Load(
            listOf(QueueItem("u", "t", "video/mp4", MediaType.VIDEO, subtitleUrl = "s", subtitleMimeType = "text/vtt")),
            repeat = RepeatMode.ONE,
            slideshow = SlideshowOptions(3_000, SlideTransition.ZOOM, kenBurns = false, music = true),
        )
        assertEquals(load, json.decodeFromString(Command.serializer(), json.encodeToString(Command.serializer(), load)))
    }

    @Test fun oldPhoneLoadStillParses() {
        // A v1 phone sends no repeat/slideshow: defaults apply.
        val load = json.decodeFromString(Command.serializer(), """{"action":"load","items":[],"slideDurationMs":8000}""") as Command.Load
        assertEquals(RepeatMode.OFF, load.repeat)
        assertEquals(null, load.slideshow)
        assertEquals(8000, load.slideDurationMs)
    }

    @Test fun infoWithCapabilities() {
        val info = ReceiverInfo("TV", "m", "1", capabilities = Capabilities(listOf("video/avc", "video/hevc"), listOf("audio/ac3"), true, 2160), mirrorPort = 8767)
        assertEquals(info, json.decodeFromString(ReceiverInfo.serializer(), json.encodeToString(ReceiverInfo.serializer(), info)))
    }

    @Test fun mirrorFramesRoundTrip() {
        val buf = java.io.ByteArrayOutputStream()
        MirrorProtocol.writeHeader(buf)
        val w = MirrorWriter(buf)
        val csd = byteArrayOf(0, 0, 0, 1, 0x67, 1, 2, 0, 0, 0, 1, 0x68, 3)
        w.write(MirrorProtocol.TYPE_VIDEO_CONFIG, 0, MirrorProtocol.videoConfigPayload(1280, 720, csd))
        val frame = ByteArray(5000) { it.toByte() }
        w.write(MirrorProtocol.TYPE_VIDEO_FRAME, 33_333, frame, 100, 4000, MirrorProtocol.FLAG_KEYFRAME)
        w.write(MirrorProtocol.TYPE_AUDIO_CONFIG, 0, MirrorProtocol.audioConfigPayload(48_000, 2))

        val input = java.io.ByteArrayInputStream(buf.toByteArray())
        MirrorProtocol.readHeader(input)
        val r = MirrorReader(input)
        val config = r.next()!!
        assertEquals(1280 to 720, config.configInts())
        assertTrue(config.videoCsd().contentEquals(csd))
        val f = r.next()!!
        assertTrue(f.isKeyframe)
        assertEquals(33_333, f.ptsUs)
        assertTrue(f.data.contentEquals(frame.copyOfRange(100, 4100)))
        assertEquals(48_000 to 2, r.next()!!.configInts())
        assertEquals(null, r.next()) // clean end of stream
    }

    @Test fun rejectsForeignStreams() {
        try {
            MirrorProtocol.readHeader(java.io.ByteArrayInputStream("HTTP/1.1".toByteArray()))
            fail("expected IOException")
        } catch (e: java.io.IOException) {
            assertTrue(e.message!!.contains("Not a CastLite"))
        }
    }
}
