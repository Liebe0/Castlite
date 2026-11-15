package com.castlite.phone.cast.dlna

import com.castlite.phone.cast.CastDevice
import com.castlite.phone.cast.CastOptions
import com.castlite.protocol.RepeatMode
import com.castlite.protocol.SlideshowOptions
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

    private fun opts(slide: Long = 5_000, repeat: RepeatMode = RepeatMode.OFF) = CastOptions(repeat, SlideshowOptions(durationMs = slide))

    @Before fun setUp() {
        tv = FakeRenderer().apply { start(5_000, true) }
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val device = CastDevice(
            id = "uuid:lg", name = "LG TV", protocol = CastProtocol.DLNA, host = "127.0.0.1",
            dlna = DlnaEndpoints("${tv.baseUrl}/d.xml", "${tv.baseUrl}/AVTransport/control", "${tv.baseUrl}/RenderingControl/control", "${tv.baseUrl}/ConnectionManager/control"),
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
        controller.load(entries(video1, video2), 0, 0, opts())
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
        runBlocking { controller.load(entries(video1), 0, 0, opts()) }
        tv.relTime = "0:01:02"
        tv.duration = "0:10:00"
        val s = runBlocking { withTimeout(3_000) { controller.status.first { it.positionMs == 62_000L && it.volume == 30 } } }
        assertEquals(600_000, s.durationMs)
    }

    @Test fun controlsMapToSoapActions() = runBlocking {
        controller.load(entries(video1), 0, 0, opts())
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

    /** Plays until the fake TV reports a position near the end of a 5-minute video, then stops. */
    private fun finishCurrentItem() {
        tv.relTime = "0:04:55"
        runBlocking { withTimeout(3_000) { controller.status.first { it.positionMs == 295_000L } } }
        Thread.sleep(150) // one more poll so the controller records the position while PLAYING
        tv.transportState = "STOPPED"
    }

    @Test fun advancesToNextItemWhenOneFinishes() {
        runBlocking { controller.load(entries(video1, video2), 0, 0, opts()) }
        awaitState(PlayerState.PLAYING)
        finishCurrentItem() // the TV reached the end of video 1
        runBlocking { withTimeout(3_000) { controller.status.first { it.index == 1 && it.state == PlayerState.PLAYING } } }
        val uris = tv.calls.filter { it.action == "SetAVTransportURI" }.map { it.body }
        assertEquals(2, uris.size)
        assertTrue(uris[1].contains("/media/v2/"))
        assertTrue(uris[1].contains("video/x-matroska"))
    }

    @Test fun endsAfterLastItem() {
        runBlocking { controller.load(entries(video1), 0, 0, opts()) }
        awaitState(PlayerState.PLAYING)
        finishCurrentItem()
        awaitState(PlayerState.ENDED)
    }

    @Test fun stopMidVideoIsAnInterruptionNotTheEnd() {
        runBlocking { controller.load(entries(video1, video2), 0, 0, opts()) }
        awaitState(PlayerState.PLAYING)
        tv.relTime = "0:01:40"
        runBlocking { withTimeout(3_000) { controller.status.first { it.positionMs == 100_000L } } }
        Thread.sleep(150)
        tv.transportState = "STOPPED" // e.g. the TV lost the stream when Wi-Fi dropped
        val s = runBlocking { withTimeout(3_000) { controller.status.first { it.state == PlayerState.ERROR } } }
        assertEquals(0, s.index) // did not skip to video 2
        assertEquals(100_000, s.positionMs) // retry resumes from here
        assertTrue(s.error!!, s.error!!.contains("stopped on LG TV at 00:01:40"))
    }

    @Test fun renderersWithoutPositionsStillAdvance() {
        runBlocking { controller.load(entries(video1, video2), 0, 0, opts()) }
        awaitState(PlayerState.PLAYING)
        Thread.sleep(150) // RelTime stays 0:00:00 ("not implemented")
        tv.transportState = "STOPPED"
        runBlocking { withTimeout(3_000) { controller.status.first { it.index == 1 } } }
    }

    @Test fun userStopDoesNotAdvance() = runBlocking {
        controller.load(entries(video1, video2), 0, 0, opts())
        awaitState(PlayerState.PLAYING)
        controller.stop()
        Thread.sleep(300)
        assertEquals(0, controller.status.value.index)
        assertEquals(1, tv.actions().count { it == "SetAVTransportURI" })
    }

    @Test fun photoSlideshowAdvancesOnTimer() {
        runBlocking { controller.load(entries(photo1, photo2), 0, 0, opts(slide = 200)) }
        assertTrue(tv.calls.first { it.action == "SetAVTransportURI" }.body.contains("object.item.imageItem.photo"))
        runBlocking { withTimeout(3_000) { controller.status.first { it.index == 1 } } }
        awaitState(PlayerState.ENDED)
    }

    @Test fun pausedSlideshowStays() = runBlocking {
        controller.load(entries(photo1, photo2), 0, 0, opts(slide = 200))
        controller.pause()
        Thread.sleep(500)
        assertEquals(0, controller.status.value.index)
    }

    @Test fun retriesPlayWhileTransitioning() = runBlocking {
        tv.playFailures = 2
        controller.load(entries(video1), 0, 0, opts())
        assertEquals(3, tv.actions().count { it == "Play" })
    }

    @Test fun unsupportedFormatGivesReadableError() = runBlocking {
        tv.rejectUriWith = 714
        try {
            controller.load(entries(video2), 0, 0, opts())
            fail("expected CastException")
        } catch (e: CastException) {
            assertTrue(e.message!!, e.message!!.contains("doesn't support this file type (video/x-matroska)"))
        }
    }

    @Test fun reportsWhenTvNeverStartsPlaying() {
        tv.neverStarts = true
        runBlocking { controller.load(entries(video1), 0, 0, opts()) }
        tv.transportState = "STOPPED" // LG shows "file not supported" and drops back to STOPPED
        val s = runBlocking { withTimeout(3_000) { controller.status.first { it.state == PlayerState.ERROR } } }
        assertTrue(s.error!!, s.error!!.contains("can't play this format"))
    }

    @Test fun reportsLostConnection() {
        runBlocking { controller.load(entries(video1), 0, 0, opts()) }
        tv.stop() // TV switched off / Wi-Fi gone
        val s = runBlocking { withTimeout(10_000) { controller.status.first { it.state == PlayerState.ERROR } } }
        assertTrue(s.error!!.contains("Lost connection"))
    }

    @Test fun nextAndPreviousNavigateTheQueue() = runBlocking {
        controller.load(entries(video1, video2), 0, 0, opts())
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

    @Test fun repeatAllWrapsToTheStart() {
        runBlocking { controller.load(entries(video1, video2), 1, 0, opts(repeat = RepeatMode.ALL)) }
        awaitState(PlayerState.PLAYING)
        finishCurrentItem()
        runBlocking { withTimeout(3_000) { controller.status.first { it.index == 0 && it.state == PlayerState.PLAYING } } }
        runBlocking { controller.next() } // and Next at the end wraps too
        assertEquals(1, controller.status.value.index)
        runBlocking { controller.next() }
        assertEquals(0, controller.status.value.index)
    }

    @Test fun repeatOneReplaysTheSameItem() {
        runBlocking { controller.load(entries(video1, video2), 0, 0, opts(repeat = RepeatMode.ONE)) }
        awaitState(PlayerState.PLAYING)
        finishCurrentItem()
        runBlocking { withTimeout(3_000) { while (tv.actions().count { it == "SetAVTransportURI" } < 2) kotlinx.coroutines.delay(20) } }
        assertEquals(0, controller.status.value.index)
        assertTrue(tv.calls.filter { it.action == "SetAVTransportURI" }.all { it.body.contains("/media/v1/") })
    }

    @Test fun subtitlesTravelInDidl() = runBlocking {
        val withSubs = video1.copy(subtitle = com.castlite.phone.media.SubtitleRef("content://s", "v1.srt", "application/x-subrip"))
        controller.load(listOf(QueueEntry(withSubs, "http://p/v1.mp4", "http://p/media/v1/subtitle.srt")), 0, 0, opts())
        val body = tv.calls.first { it.action == "SetAVTransportURI" }.body
        assertTrue(body.contains("sec:CaptionInfoEx"))
        assertTrue(body.contains("http://p/media/v1/subtitle.srt"))
    }

    @Test fun asksTheTvWhichFormatsItAccepts() {
        val types = DlnaController.supportedTypes(controller.device.dlna!!)
        assertEquals(setOf("video/mp4", "video/x-matroska", "audio/mpeg", "image/jpeg"), types)
    }
}
