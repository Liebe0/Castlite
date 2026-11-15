package com.castlite.tv.playback

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy.LoadErrorInfo
import kotlin.math.min

/**
 * Retries failed downloads from the phone for much longer than ExoPlayer's default.
 *
 * When the phone's screen turns off it may put Wi-Fi into power-save or briefly roam, so
 * a request can time out for a few seconds even though nothing is really wrong. Instead
 * of failing after ~3 quick retries, keep retrying (1 s, 2 s, 3 s, then every 4 s)
 * — about half a minute of retries on top of each request's own timeout.
 * "The file is gone" (HTTP 4xx) still fails immediately.
 */
@OptIn(UnstableApi::class)
class PatientLoadErrorPolicy : DefaultLoadErrorHandlingPolicy(RETRIES) {

    override fun getRetryDelayMsFor(info: LoadErrorInfo): Long {
        val e = info.exception
        if (e is HttpDataSource.InvalidResponseCodeException && e.responseCode in 400..499) return C.TIME_UNSET
        if (super.getRetryDelayMsFor(info) == C.TIME_UNSET) return C.TIME_UNSET // parse errors etc.
        return min(info.errorCount * 1_000L, MAX_DELAY_MS)
    }

    companion object {
        const val RETRIES = 10
        const val MAX_DELAY_MS = 4_000L
    }
}
