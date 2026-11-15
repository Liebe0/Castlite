package com.castlite.tv.playback

/** Did playback stop at the real end of the video, or well before it? Pure, unit-tested. */
object EndCheck {
    /** Containers' durations are approximate; anything closer than this to the end counts as the end. */
    const val TOLERANCE_MS = 5_000L

    fun isPremature(positionMs: Long, durationMs: Long): Boolean =
        durationMs > 0 && positionMs >= 0 && positionMs < durationMs - TOLERANCE_MS
}
