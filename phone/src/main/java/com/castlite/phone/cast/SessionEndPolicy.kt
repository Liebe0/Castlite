package com.castlite.phone.cast

/**
 * Decides when a casting session is over, so the phone doesn't keep showing a mini
 * player for something the TV stopped long ago (pure logic, unit-tested).
 *
 *  - The TV went idle after it had been playing (stopped with its remote, CastLite TV app
 *    closed, LG switched input…) → the session ends right away.
 *  - The queue finished → the session ends [endedGraceMs] later (time to see "Finished"
 *    and press Play again).
 */
class SessionEndPolicy(private val endedGraceMs: Long = 20_000) {
    private var sawActive = false
    private var endedSince = 0L

    enum class Verdict { CONTINUE, END_NOW }

    fun onStatus(state: PlayerState, nowMs: Long): Verdict {
        when (state) {
            PlayerState.LOADING, PlayerState.PLAYING, PlayerState.PAUSED -> {
                sawActive = true
                endedSince = 0
            }
            PlayerState.IDLE -> if (sawActive) return Verdict.END_NOW
            PlayerState.ENDED -> {
                if (endedSince == 0L) endedSince = nowMs
                if (nowMs - endedSince >= endedGraceMs) return Verdict.END_NOW
            }
            PlayerState.CONNECTING, PlayerState.ERROR -> Unit
        }
        return Verdict.CONTINUE
    }

    /** For the timer that re-checks a finished session (no new status arrives while ENDED). */
    fun endedFor(nowMs: Long): Long = if (endedSince == 0L) 0 else nowMs - endedSince
}
