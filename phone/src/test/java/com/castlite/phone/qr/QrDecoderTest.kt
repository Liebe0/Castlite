package com.castlite.phone.qr

import com.castlite.protocol.TvQrCode
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QrDecoderTest {

    /** A camera-like frame: the TV's code, scaled up, with a margin and padded rows. */
    private fun frame(text: String, scale: Int = 5, margin: Int = 40, padding: Int = 16): Triple<ByteArray, Int, Pair<Int, Int>> {
        val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.MARGIN to 1))
        val w = m.width * scale + 2 * margin
        val stride = w + padding
        val bytes = ByteArray(stride * w) { 0xE0.toByte() } // light grey background
        for (y in 0 until w) for (x in 0 until w) {
            val mx = (x - margin) / scale
            val my = (y - margin) / scale
            val dark = x >= margin && y >= margin && mx < m.width && my < m.height && m[mx, my]
            if (dark) bytes[y * stride + x] = 0x20
        }
        return Triple(bytes, stride, w to w)
    }

    @Test fun readsTheTvCode() {
        val link = TvQrCode.build("192.168.0.50", 8766, "Living Room TV")
        val (bytes, stride, size) = frame(link)
        assertEquals(link, QrDecoder.decode(bytes, stride, size.first, size.second))
    }

    @Test fun emptyFrameHasNoCode() {
        assertNull(QrDecoder.decode(ByteArray(640 * 480) { 0x80.toByte() }, 640, 640, 480))
    }
}
