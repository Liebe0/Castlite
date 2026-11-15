package com.castlite.tv.ui

import com.castlite.protocol.TvQrCode
import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import org.junit.Assert.assertEquals
import org.junit.Test

class QrTest {
    /** The TV's code, rendered as pixels, must decode back to the same connect link. */
    @Test fun tvCodeDecodes() {
        val link = TvQrCode.build("192.168.1.60", 8766, "Living Room TV")
        val m = Qr.matrix(link)
        val scale = 6
        val w = m.width * scale
        val pixels = IntArray(w * w) { i ->
            val x = (i % w) / scale
            val y = (i / w) / scale
            if (m[x, y]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }
        val decoded = QRCodeReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(w, w, pixels)))).text
        assertEquals(link, decoded)
        assertEquals("Living Room TV", TvQrCode.parse(decoded)!!.name)
    }
}
