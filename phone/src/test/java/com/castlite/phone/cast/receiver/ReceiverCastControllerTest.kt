package com.castlite.phone.cast.receiver

import com.castlite.phone.cast.CastDevice
import com.castlite.phone.cast.CastOptions
import com.castlite.protocol.RepeatMode
import com.castlite.protocol.SlideshowOptions
import com.castlite.phone.cast.CastException
import com.castlite.phone.cast.CastProtocol
import com.castlite.phone.cast.PlayerState
import com.castlite.phone.cast.QueueEntry
import com.castlite.phone.media.CastMedia
import com.castlite.protocol.Command
import com.castlite.protocol.MediaType
import com.castlite.protocol.ReceiverApiServer
import com.castlite.protocol.ReceiverHandler
import com.castlite.protocol.ReceiverInfo
import com.castlite.protocol.ReceiverState
import com.castlite.protocol.ReceiverStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.Collections

/** Phone-side controller against the real TV-side API server (with a fake player behind it). */
class ReceiverCastControllerTest {

    private val received = Collections.synchronizedList(mutableListOf<Command>())
    @Volatile private var status = ReceiverStatus()
    private lateinit var server: ReceiverApiServer
    private lateinit var scope: CoroutineScope
    private lateinit var controller: ReceiverCastController

    @Before fun setUp() {
        server = ReceiverApiServer(object : ReceiverHandler {
            override fun info() = ReceiverInfo("Bedroom TV", "Test", "1")
            override fun status() = status
            override fun handle(command: Command, client: String): ReceiverStatus {
                received += command
                status = when (command) {
                    is Command.Load -> ReceiverStatus(ReceiverState.PLAYING, command.startIndex, command.items.size, durationMs = 1000, volumePercent = 20)
                    Command.Pause -> status.copy(state = ReceiverState.PAUSED)
                    Command.Next -> { check(status.index + 1 < status.count) { "Already at the last item" }; status.copy(index = status.index + 1) }
                    is Command.SetVolume -> status.copy(volumePercent = command.percent)
                    else -> status
                }
                return status
            }
        }, port = 0).apply { start(5_000, true) }
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val device = CastDevice("castlite:127.0.0.1", "Bedroom TV", CastProtocol.CASTLITE, "127.0.0.1", receiverPort = server.listeningPort)
        controller = ReceiverCastController(device, scope, pollIntervalMs = 50)
    }

    @After fun tearDown() {
        controller.release()
        scope.cancel()
        server.stop()
    }

    private val photo = CastMedia("p", "content://p", "Beach.jpg", "Beach.jpg", "image/jpeg", 10)
    private val song = CastMedia("s", "content://s", "Song.mp3", "Song.mp3", "audio/mpeg", 10, 200_000)

    @Test fun loadSendsWholeQueueWithTypes() = runBlocking {
        controller.load(listOf(QueueEntry(photo, "http://p/1"), QueueEntry(song, "http://p/2")), 1, 0, CastOptions(RepeatMode.ALL, SlideshowOptions(8_000, music = true)))
        val load = received.first() as Command.Load
        assertEquals(listOf(MediaType.IMAGE, MediaType.AUDIO), load.items.map { it.type })
        assertEquals(1, load.startIndex)
        assertEquals(8_000, load.slideDurationMs)
        assertEquals(RepeatMode.ALL, load.repeat)
        assertTrue(load.slideshow!!.music)
        assertEquals(200_000, load.items[1].durationMs)
        assertEquals(PlayerState.PLAYING, controller.status.value.state)
    }

    @Test fun commandsAndPolling() = runBlocking {
        controller.load(listOf(QueueEntry(photo, "u1"), QueueEntry(song, "u2")), 0, 0, CastOptions())
        controller.pause()
        assertEquals(PlayerState.PAUSED, controller.status.value.state)
        controller.setVolume(150)
        assertEquals(100, (received.last() as Command.SetVolume).percent) // clamped
        status = status.copy(positionMs = 4_242)
        withTimeout(3_000) { controller.status.first { it.positionMs == 4_242L } }
        Unit
    }

    @Test fun tvErrorsBecomeCastExceptions() = runBlocking {
        controller.load(listOf(QueueEntry(photo, "u1")), 0, 0, CastOptions())
        try {
            controller.next()
            fail("expected error")
        } catch (e: CastException) {
            assertEquals("Already at the last item", e.message)
        }
    }

    @Test fun lostConnectionIsReported() {
        runBlocking { controller.load(listOf(QueueEntry(song, "u")), 0, 0, CastOptions()) }
        server.stop()
        val s = runBlocking { withTimeout(10_000) { controller.status.first { it.state == PlayerState.ERROR } } }
        assertTrue(s.error!!.contains("Lost connection to Bedroom TV"))
    }
}
