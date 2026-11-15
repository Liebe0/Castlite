package com.castlite.updater

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdatePolicyTest {

    private val min = CheckSchedule.MINUTE
    private val t0 = 1_700_000_000_000L

    @Test fun firstCheckIsDueImmediately() {
        val s = CheckSchedule()
        assertTrue(s.isDue(t0))
        assertTrue(s.nudgeAllowed(t0))
    }

    @Test fun regularChecksEveryFifteenMinutesOfRealTime() {
        val s = CheckSchedule()
        s.onAttempt(t0); s.onSuccess(t0)
        assertFalse(s.isDue(t0 + 14 * min))
        assertTrue(s.isDue(t0 + 15 * min))
        // A TV that slept for hours is overdue as soon as it wakes.
        assertTrue(s.isDue(t0 + 9 * 60 * min))
    }

    @Test fun failedChecksRetrySoonWithBackoff() {
        val s = CheckSchedule()
        s.onAttempt(t0); s.onFailure()
        assertEquals(1 * min, s.retryDelayMs())
        assertTrue(s.isDue(t0 + 1 * min))
        s.onAttempt(t0); s.onFailure()
        assertEquals(2 * min, s.retryDelayMs())
        s.onFailure(); assertEquals(4 * min, s.retryDelayMs())
        s.onFailure(); assertEquals(8 * min, s.retryDelayMs())
        s.onFailure(); assertEquals(15 * min, s.retryDelayMs()) // capped at the regular interval
        repeat(20) { s.onFailure() }
        assertEquals(15 * min, s.retryDelayMs())
        s.onSuccess(t0)
        assertEquals(0, s.failures)
    }

    @Test fun nudgesAreRateLimited() {
        val s = CheckSchedule()
        s.onAttempt(t0); s.onSuccess(t0)
        assertFalse(s.nudgeAllowed(t0 + 30_000))
        assertTrue(s.nudgeAllowed(t0 + 2 * min))
    }

    @Test fun afterAFailureWiFiComingBackChecksAlmostAtOnce() {
        val s = CheckSchedule()
        s.onAttempt(t0); s.onFailure()
        assertFalse(s.nudgeAllowed(t0 + 5_000))
        assertTrue(s.nudgeAllowed(t0 + 10_000))
    }

    @Test fun clockSetBackDoesNotBlockChecks() {
        val s = CheckSchedule()
        s.onAttempt(t0); s.onSuccess(t0)
        assertTrue(s.isDue(t0 - 60 * min))
    }

    // ---- InstallGate --------------------------------------------------------------

    private fun gate(
        userInitiated: Boolean = false,
        appIdle: Boolean = true,
        declined: Boolean = false,
        visible: Boolean = false,
        silentPossible: Boolean = true,
        msSinceHidden: Long = Long.MAX_VALUE,
    ) = InstallGate.allowed(userInitiated, appIdle, declined, visible, silentPossible, msSinceHidden)

    @Test fun neverInstallsWhileCastingUnlessAsked() {
        assertFalse(gate(appIdle = false, visible = true))
        assertFalse(gate(appIdle = false))
        assertTrue(gate(appIdle = false, userInitiated = true))
    }

    @Test fun onScreenAndIdleInstallsEvenIfAndroidWillAsk() {
        assertTrue(gate(visible = true, silentPossible = false))
    }

    @Test fun inTheBackgroundOnlyWhenAndroidWontAsk() {
        // Android 12+: e.g. a TV showing another app updates CastLite silently.
        assertTrue(gate(visible = false, silentPossible = true))
        // Older Android needs a tap on its dialog, which can't be shown in the background.
        assertFalse(gate(visible = false, silentPossible = false))
    }

    @Test fun waitsAFewMinutesAfterTheUserLeftTheApp() {
        assertFalse(gate(msSinceHidden = 30_000))
        assertTrue(gate(msSinceHidden = InstallGate.BACKGROUND_DELAY_MS))
    }

    @Test fun aDeclinedVersionIsNotOfferedAgainUntilAsked() {
        assertFalse(gate(declined = true, visible = true))
        assertTrue(gate(declined = true, userInitiated = true))
    }
}
