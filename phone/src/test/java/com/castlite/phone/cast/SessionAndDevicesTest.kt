package com.castlite.phone.cast

import org.junit.Assert.assertEquals
import org.junit.Test

class SessionAndDevicesTest {

    @Test fun sessionEndsWhenTheTvStopsAfterPlaying() {
        val p = SessionEndPolicy(endedGraceMs = 20_000)
        assertEquals(SessionEndPolicy.Verdict.CONTINUE, p.onStatus(PlayerState.CONNECTING, 0))
        assertEquals(SessionEndPolicy.Verdict.CONTINUE, p.onStatus(PlayerState.IDLE, 0)) // not started yet
        assertEquals(SessionEndPolicy.Verdict.CONTINUE, p.onStatus(PlayerState.PLAYING, 1_000))
        assertEquals(SessionEndPolicy.Verdict.END_NOW, p.onStatus(PlayerState.IDLE, 2_000)) // stopped on the TV
    }

    @Test fun finishedSessionLingersBrieflyThenEnds() {
        val p = SessionEndPolicy(endedGraceMs = 20_000)
        p.onStatus(PlayerState.PLAYING, 0)
        assertEquals(SessionEndPolicy.Verdict.CONTINUE, p.onStatus(PlayerState.ENDED, 10_000))
        assertEquals(SessionEndPolicy.Verdict.CONTINUE, p.onStatus(PlayerState.ENDED, 25_000))
        assertEquals(SessionEndPolicy.Verdict.END_NOW, p.onStatus(PlayerState.ENDED, 31_000))
    }

    @Test fun replayingAfterTheEndResetsTheTimer() {
        val p = SessionEndPolicy(endedGraceMs = 20_000)
        p.onStatus(PlayerState.PLAYING, 0)
        p.onStatus(PlayerState.ENDED, 1_000)
        p.onStatus(PlayerState.PLAYING, 5_000)
        assertEquals(SessionEndPolicy.Verdict.CONTINUE, p.onStatus(PlayerState.ENDED, 30_000))
    }

    @Test fun errorsDoNotEndTheSession() {
        val p = SessionEndPolicy()
        p.onStatus(PlayerState.PLAYING, 0)
        assertEquals(SessionEndPolicy.Verdict.CONTINUE, p.onStatus(PlayerState.ERROR, 100_000)) // Retry stays available
    }

    private val castlite = CastDevice("castlite:192.168.0.50", "Living Room TV", CastProtocol.CASTLITE, "192.168.0.50", "CastLite TV app")
    private val sameTvDlna = CastDevice("uuid:tv", "Living Room TV", CastProtocol.DLNA, "192.168.0.50", "SmartTV", "Renderer")
    private val lg = CastDevice("uuid:lg", "LG", CastProtocol.DLNA, "192.168.0.60")

    @Test fun androidTvWithBuiltInDlnaIsListedOnce() {
        val list = DeviceRepository.merge(listOf(castlite), listOf(sameTvDlna, lg), emptyList())
        assertEquals(listOf(castlite, lg), list)
    }

    @Test fun manualEntryHiddenWhenSeenLive() {
        val manual = castlite.copy(isManual = true)
        assertEquals(listOf(castlite, lg), DeviceRepository.merge(listOf(castlite), listOf(lg), listOf(manual)))
        // Not discovered (e.g. mDNS blocked): the manual entry shows, and still hides the TV's DLNA twin.
        assertEquals(listOf(lg, manual), DeviceRepository.merge(emptyList(), listOf(sameTvDlna, lg), listOf(manual)))
    }
}
