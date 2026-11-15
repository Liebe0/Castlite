package com.castlite.phone.server

import com.castlite.phone.cast.AutoResume
import com.castlite.phone.cast.PlayerState
import com.castlite.phone.net.LanStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeepAwakePolicyTest {

    @Test fun staysAwakeAfterActivityThenReleasesWhenIdle() {
        val p = KeepAwakePolicy(idleGraceMs = 30 * 60_000L)
        assertFalse("nothing happened yet", p.shouldStayAwake(1_000))
        p.onActivity(nowMs = 1_000)
        assertTrue(p.shouldStayAwake(1_000 + 29 * 60_000L))
        assertFalse(p.shouldStayAwake(1_000 + 31 * 60_000L))
        p.onActivity(nowMs = 1_000 + 31 * 60_000L) // e.g. the TV requested more data
        assertTrue(p.shouldStayAwake(1_000 + 40 * 60_000L))
    }

    private val wifiA = LanStatus(LanStatus.Kind.WIFI, "192.168.1.23")
    private val wifiB = LanStatus(LanStatus.Kind.WIFI, "192.168.1.77")

    @Test fun autoResumeOnlyAfterReconnectOrAddressChange() {
        assertFalse(AutoResume.shouldCheck(null, wifiA)) // first reading
        assertFalse(AutoResume.shouldCheck(wifiA, wifiA)) // nothing changed
        assertFalse(AutoResume.shouldCheck(wifiA, LanStatus.Disconnected)) // dropped: wait
        assertTrue(AutoResume.shouldCheck(LanStatus.Disconnected, wifiA)) // back
        assertTrue(AutoResume.shouldCheck(wifiA, wifiB)) // new address
    }

    @Test fun resumeWhenUrlsBrokeOrTvFailed() {
        assertTrue(AutoResume.shouldResume(addressChanged = true, state = PlayerState.PLAYING))
        assertTrue(AutoResume.shouldResume(addressChanged = false, state = PlayerState.ERROR))
        // Same address and the TV played on from its buffer: leave it alone.
        assertFalse(AutoResume.shouldResume(addressChanged = false, state = PlayerState.PLAYING))
        assertFalse(AutoResume.shouldResume(addressChanged = false, state = PlayerState.PAUSED))
    }
}
