package com.castlite.updater

/**
 * When to look for an update. Pure (times are passed in) so it can be unit-tested.
 *
 * - A regular check every [intervalMs] of *wall-clock* time. Android's timers pause while
 *   a TV is in standby, so the app ticks every minute and compares real times: after the
 *   TV wakes up, an overdue check runs within a minute.
 * - After a failed check (e.g. Wi-Fi not up yet after boot) it retries soon: 1, 2, 4, 8 min…
 * - "Nudges" (app opened, Wi-Fi back, a phone connected) check right away unless the last
 *   check was very recent.
 */
internal class CheckSchedule(
    val intervalMs: Long = 15 * MINUTE,
    private val nudgeGapMs: Long = 2 * MINUTE,
) {
    private var lastAttemptMs = 0L // 0 = never
    var failures = 0
        private set
    var lastSuccessMs = 0L
        private set

    fun onAttempt(now: Long) {
        lastAttemptMs = now
    }

    fun onSuccess(now: Long) {
        lastSuccessMs = now
        failures = 0
    }

    fun onFailure() {
        failures++
    }

    /** Should the regular timer check now? */
    fun isDue(now: Long): Boolean = elapsedSinceAttempt(now) >= (if (failures > 0) retryDelayMs() else intervalMs)

    /** Should an event (app opened, network back, phone connected) trigger a check now? */
    fun nudgeAllowed(now: Long): Boolean = elapsedSinceAttempt(now) >= (if (failures > 0) RETRY_NUDGE_GAP_MS else nudgeGapMs)

    /** 1, 2, 4, 8… minutes after consecutive failures, never more than the regular interval. */
    fun retryDelayMs(): Long = minOf(intervalMs, MINUTE shl (failures - 1).coerceIn(0, 10))

    private fun elapsedSinceAttempt(now: Long): Long = when {
        lastAttemptMs == 0L -> Long.MAX_VALUE
        now < lastAttemptMs -> Long.MAX_VALUE // the clock was set back: don't wait forever
        else -> now - lastAttemptMs
    }

    companion object {
        const val MINUTE = 60_000L
        private const val RETRY_NUDGE_GAP_MS = 10_000L
    }
}

/** Whether a downloaded update may be installed right now. Pure, unit-tested. */
internal object InstallGate {

    /** After the user leaves the app, wait this long before a background install. */
    const val BACKGROUND_DELAY_MS = 3 * CheckSchedule.MINUTE

    /**
     * @param appIdle the app's own rule: nothing is casting / playing / mirroring.
     * @param declined the user cancelled Android's install dialog for this version.
     * @param visible the app is on screen (Android's dialog, if needed, can be shown).
     * @param silentPossible Android can install without asking (Android 12+, and it didn't
     *   ask for a tap last time).
     * @param msSinceHidden how long ago the app left the screen.
     */
    fun allowed(
        userInitiated: Boolean,
        appIdle: Boolean,
        declined: Boolean,
        visible: Boolean,
        silentPossible: Boolean,
        msSinceHidden: Long,
    ): Boolean = when {
        userInitiated -> true
        !appIdle || declined -> false
        visible -> true
        // Off screen: only when no dialog is needed, and not right after the user left
        // (they may be in a file picker or a settings screen and about to come back).
        else -> silentPossible && msSinceHidden >= BACKGROUND_DELAY_MS
    }
}
