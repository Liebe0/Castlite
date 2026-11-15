package com.castlite.tv.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EndCheckTest {
    @Test fun endedWellBeforeTheEndIsPremature() {
        assertTrue(EndCheck.isPremature(positionMs = 80_000, durationMs = 600_000))
    }

    @Test fun endingAtOrNearTheEndIsReal() {
        assertFalse(EndCheck.isPremature(600_000, 600_000))
        assertFalse(EndCheck.isPremature(596_000, 600_000)) // containers' durations are approximate
    }

    @Test fun unknownDurationIsNeverPremature() {
        assertFalse(EndCheck.isPremature(80_000, 0))
        assertFalse(EndCheck.isPremature(80_000, Long.MIN_VALUE + 1)) // C.TIME_UNSET
    }
}
