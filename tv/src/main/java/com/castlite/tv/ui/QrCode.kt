package com.castlite.tv.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/** QR code helpers (pure, unit-tested). */
object Qr {
    /** One module per cell, with a 1-module quiet zone (the white card adds more). */
    fun matrix(text: String): BitMatrix = QRCodeWriter().encode(
        text, BarcodeFormat.QR_CODE, 0, 0,
        mapOf(EncodeHintType.MARGIN to 1, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M),
    )
}

/** Black-on-white QR code on a rounded white card (phones scan dark-on-light best). */
@Composable
fun QrCode(text: String, modifier: Modifier = Modifier) {
    val matrix = remember(text) { runCatching { Qr.matrix(text) }.getOrNull() }
    Canvas(modifier.clip(RoundedCornerShape(16.dp)).background(Color.White).padding(10.dp)) {
        val m = matrix ?: return@Canvas
        val cell = size.minDimension / m.width
        for (y in 0 until m.height) {
            for (x in 0 until m.width) {
                if (m[x, y]) drawRect(Color.Black, Offset(x * cell, y * cell), Size(cell + 0.5f, cell + 0.5f))
            }
        }
    }
}
