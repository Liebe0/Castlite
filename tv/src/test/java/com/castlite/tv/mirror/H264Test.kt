package com.castlite.tv.mirror

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test

class H264Test {
    private val sps = byteArrayOf(0x67, 0x42, 0x00, 0x1f, 0x11)
    private val pps = byteArrayOf(0x68, 0xce.toByte(), 0x3c, 0x80.toByte())
    private fun withStart(nal: ByteArray, long: Boolean = true) = (if (long) byteArrayOf(0, 0, 0, 1) else byteArrayOf(0, 0, 1)) + nal

    @Test fun splitsSpsAndPps() {
        val (s, p) = H264.splitParameterSets(withStart(sps) + withStart(pps))
        assertArrayEquals(withStart(sps), s)
        assertArrayEquals(withStart(pps), p)
    }

    @Test fun handlesShortStartCodesAndNormalizesThem() {
        val (s, p) = H264.splitParameterSets(withStart(sps, long = false) + withStart(pps, long = false))
        assertArrayEquals(withStart(sps), s)
        assertArrayEquals(withStart(pps), p)
    }

    @Test fun missingPps() {
        val (s, p) = H264.splitParameterSets(withStart(sps))
        assertArrayEquals(withStart(sps), s)
        assertNull(p)
    }
}
