package com.castlite.phone.server

import org.junit.Assert.assertEquals
import org.junit.Test

class RangeRequestTest {

    private val size = 1000L

    @Test fun noHeaderMeansFullFile() =
        assertEquals(RangeRequest.Full, RangeRequest.parse(null, size))

    @Test fun openEndedRange() =
        assertEquals(RangeRequest.Partial(0, 999), RangeRequest.parse("bytes=0-", size))

    @Test fun seekRange() =
        assertEquals(RangeRequest.Partial(500, 999), RangeRequest.parse("bytes=500-", size))

    @Test fun closedRange() =
        assertEquals(RangeRequest.Partial(100, 199), RangeRequest.parse("bytes=100-199", size))

    @Test fun endBeyondFileIsClamped() =
        assertEquals(RangeRequest.Partial(900, 999), RangeRequest.parse("bytes=900-5000", size))

    @Test fun suffixRange() =
        assertEquals(RangeRequest.Partial(800, 999), RangeRequest.parse("bytes=-200", size))

    @Test fun suffixLargerThanFile() =
        assertEquals(RangeRequest.Partial(0, 999), RangeRequest.parse("bytes=-5000", size))

    @Test fun startPastEndIsUnsatisfiable() =
        assertEquals(RangeRequest.Unsatisfiable, RangeRequest.parse("bytes=1000-", size))

    @Test fun malformedIsIgnored() {
        assertEquals(RangeRequest.Full, RangeRequest.parse("bytes=abc-", size))
        assertEquals(RangeRequest.Full, RangeRequest.parse("items=0-10", size))
        assertEquals(RangeRequest.Full, RangeRequest.parse("bytes=50-10", size))
    }

    @Test fun multipleRangesAreIgnored() =
        assertEquals(RangeRequest.Full, RangeRequest.parse("bytes=0-10,20-30", size))

    @Test fun unknownSizeNeverRanges() =
        assertEquals(RangeRequest.Full, RangeRequest.parse("bytes=500-", -1))

    @Test fun whitespaceAndCaseTolerated() =
        assertEquals(RangeRequest.Partial(10, 20), RangeRequest.parse(" Bytes= 10 - 20 ", size))

    @Test fun partialLength() =
        assertEquals(101L, RangeRequest.Partial(100, 200).length)
}
