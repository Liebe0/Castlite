package com.castlite.tv.playback

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.ParserException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy.LoadErrorInfo
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException

class PatientLoadErrorPolicyTest {

    private val policy = PatientLoadErrorPolicy()
    private val spec = DataSpec(Uri.EMPTY)

    private fun info(e: IOException, count: Int) = LoadErrorInfo(
        LoadEventInfo(1, spec, Uri.EMPTY, emptyMap(), 0, 0, 0),
        MediaLoadData(C.DATA_TYPE_MEDIA),
        e,
        count,
    )

    @Test fun keepsRetryingTimeoutsWithBackoff() {
        val timeout = HttpDataSource.HttpDataSourceException(
            SocketTimeoutException(), spec, androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
            HttpDataSource.HttpDataSourceException.TYPE_OPEN,
        )
        assertEquals(1_000, policy.getRetryDelayMsFor(info(timeout, 1)))
        assertEquals(3_000, policy.getRetryDelayMsFor(info(timeout, 3)))
        assertEquals(4_000, policy.getRetryDelayMsFor(info(timeout, 9)))
        assertEquals(PatientLoadErrorPolicy.RETRIES, policy.getMinimumLoadableRetryCount(C.DATA_TYPE_MEDIA))
    }

    @Test fun missingFileFailsFast() {
        val notFound = HttpDataSource.InvalidResponseCodeException(404, "Not Found", null, emptyMap(), spec, ByteArray(0))
        assertEquals(C.TIME_UNSET, policy.getRetryDelayMsFor(info(notFound, 1)))
    }

    @Test fun brokenFileFailsFast() {
        assertEquals(C.TIME_UNSET, policy.getRetryDelayMsFor(info(ParserException.createForMalformedContainer("bad", null), 1)))
    }
}
