package com.castlite.phone.cast.dlna

import com.castlite.phone.cast.CastDevice
import com.castlite.phone.cast.CastException
import com.castlite.phone.cast.CastProtocol
import com.castlite.phone.cast.DlnaEndpoints
import com.castlite.phone.cast.PlayerState
import com.castlite.phone.cast.QueueEntry
import com.castlite.phone.media.CastMedia
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

/** Drives [DlnaController] against [FakeRenderer] over real HTTP. */
class DlnaControllerTest {

    private lateinit var tv: FakeRenderer
    private lateinit var scope: CoroutineScope
    private lateinit var controller: DlnaController

    private fun media(id: String, mime: String, durationMs: Long = 300_000) =
        CastMedia(id, "content://t/$id", "$id title", "$id.bin", mime, 1_000, durationMs)

    private val video1 = media("v1", "video/mp4")
    private val video2 = media("v2", "video/x-matroska")
    private val photo1 = media("p1", "image/jpeg", 0)
    private val photo2 = media("p2", "image/jpeg", 0)

    private fun entries(vararg m: CastMedia) = m.map { QueueEntry(it, "http://192.168.1.2:8765${it.path}") }

    @Before fun setUp() {
        tv = FakeRenderer().apply { start(5_000, true) }
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val device = CastDevice(
            id = "uuid:lg", name = "LG TV", protocol = CastProtocol.DLNA, host = "127.0.0.1",
            dlna = DlnaEndpoints("${tv.baseUrl}/d.xml", "${tv.baseUrl}/AVTransport/control", "${tv.baseUrl}/RenderingControl/control"),
        )
        controller = DlnaController(device, scope, pollIntervalMs = 50, retryDelayMs = 20, startTimeoutMs = 600)
    }

    @After fun tearDown() {
        controller.release()
        scope.cancel()
        tv.stop()
    }

    private fun awaitState(state: PlayerState, timeoutMs: Long = 3_000) = runBlocking {
        withTimeout(timeoutMs) { controller.status.first { it.state == state } }
    }

    @Test fun loadSendsUriWithDidlThenPlays() = runBlocking {
        controller.load(entries(video1, video2), 0, 0, 5_000)
        assertEquals(listOf("Stop", "SetAVTransportURI", "Play"), tv.actions().take(3))
        val setUri = tv.calls.first { it.action == "SetAVTransportURI" }.body
        assertTrue(setUri.contains("<CurrentURI>http://192.168.1.2:8765/media/v1/v1.bin</CurrentURI>"))
        // DIDL-Lite travels escaped inside CurrentURIMetaData, with the right MIME type.
        assertTrue(setUri.contains("http-get:*:video/mp4:DLNA.ORG_OP=01"))
        assertTrue(setUri.contains("&lt;upnp:class&gt;object.item.videoItem&lt;/upnp:class&gt;"))
        awaitState(PlayerState.PLAYING)
        Unit
    }

    @Test fun pollingReportsPositionDurationAndVolume() {
        runBlocking { controller.load(entries(video1), 0, 0, 5_000) }
        tv.relTime = "0:01:02"
        tv.duration = "0:10:00"
        val s = runBlocking { withTimeout(3_000) { controller.status.first { it.positionMs == 62_000L && it.volume == 30 } } }
        assertEquals(600_000, s.durationMs)
    }

    @Test fun controlsMapToSoapActions() = runBlocking {
        controller.load(entries(video1), 0, 0, 5_000)
        controller.pause()
        assertEquals(PlayerState.PAUSED, controller.status.value.state)
        controller.seekTo(83_000)
        assertTrue(tv.calls.last { it.action == "Seek" }.body.contains("<Target>00:01:23</Target>"))
        assertTrue(tv.calls.last { it.action == "Seek" }.body.contains("<Unit>REL_TIME</Unit>"))
        controller.setVolume(55)
        assertEquals(55, tv.volume)
        controller.play()
        controller.stop()
        assertEquals("STOPPED", tv.transportState)
        assertEquals(PlayerState.IDLE, controller.status.value.state)
    }

    @Test fun advancesToNextItemWhenOneFinishes() {
        runBlocking { controller.load(entries(video1, video2), 0, 0, 5_000) }
        awaitState(PlayerState.PLAYING)
        tv.transportState = "STOPPED" // the TV reached the end of video 1
        runBlocking { withTimeout(3_000) { controller.status.first { it.index == 1 && it.state == PlayerState.PLAYING } } }
        val uris = tv.calls.filter { it.action == "SetAVTransportURI" }.map { it.body }
        assertEquals(2, uris.size)
        assertTrue(uris[1].contains("/media/v2/"))
        assertTrue(uris[1].contains("video/x-matroska"))
    }

    @Test fun endsAfterLastItem() {
        runBlocking { controller.load(entries(video1), 0, 0, 5_000) }
        awaitState(PlayerState.PLAYING)
        tv.transportState = "STOPPED"
        awaitState(PlayerState.ENDED)
    }

    @Test fun userStopDoesNotAdvance() = runBlocking {
        controller.load(entries(video1, video2), 0, 0, 5_000)
        awaitState(PlayerState.PLAYING)
        controller.stop()
        Thread.sleep(300)
        assertEquals(0, controller.status.value.index)
        assertEquals(1, tv.actions().count { it == "SetAVTransportURI" })
    }

    @Test fun photoSlideshowAdvancesOnTimer() {
        runBlocking { controller.load(entries(photo1, photo2), 0, 0, slideDurationMs = 200) }
        assertTrue(tv.calls.first { it.action == "SetAVTransportURI" }.body.contains("object.item.imageItem.photo"))
        runBlocking { withTimeout(3_000) { controller.status.first { it.index == 1 } } }
        awaitState(PlayerState.ENDED)
    }

    @Test fun pausedSlideshowStays() = runBlocking {
        controller.load(entries(photo1, photo2), 0, 0, slideDurationMs = 200)
        controller.pause()
        Thread.sleep(500)
        assertEquals(0, controller.status.value.index)
    }

    @Test fun retriesPlayWhileTransitioning() = runBlocking {
        tv.playFailures = 2
        controller.load(entries(video1), 0, 0, 5_000)
        assertEquals(3, tv.actions().count { it == "Play" })
    }

    @Test fun unsupportedFormatGivesReadableError() = runBlocking {
        tv.rejectUriWith = 714
        try {
            controller.load(entries(video2), 0, 0, 5_000)
            fail("expected CastException")
        } catch (e: CastException) {
            assertTrue(e.message!!, e.message!!.contains("doesn't support this file type (video/x-matroska)"))
        }
    }

    @Test fun reportsWhenTvNeverStartsPlaying() {
        tv.neverStarts = true
        runBlocking { controller.load(entries(video1), 0, 0, 5_000) }
        tv.transportState = "STOPPED" // LG shows "file not supported" and drops back to STOPPED
        val s = runBlocking { withTimeout(3_000) { controller.status.first { it.state == PlayerState.ERROR } } }
        assertTrue(s.error!!, s.error!!.contains("can't play this format"))
    }

    @Test fun reportsLostConnection() {
        runBlocking { controller.load(entries(video1), 0, 0, 5_000) }
        tv.stop() // TV switched off / Wi-Fi gone
        val s = runBlocking { withTimeout(10_000) { controller.status.first { it.state == PlayerState.ERROR } } }
        assertTrue(s.error!!.contains("Lost connection"))
    }

    @Test fun nextAndPreviousNavigateTheQueue() = runBlocking {
        controller.load(entries(video1, video2), 0, 0, 5_000)
        controller.next()
        assertEquals(1, controller.status.value.index)
        try {
            controller.next()
            fail("expected end of queue")
        } catch (e: CastException) {
            assertEquals("This is the last item", e.message)
        }
        controller.previous()
        assertEquals(0, controller.status.value.index)
    }
}
