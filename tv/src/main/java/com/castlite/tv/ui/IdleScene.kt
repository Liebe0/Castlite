package com.castlite.tv.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.castlite.tv.ReceiverInfoState
import com.castlite.updater.UpdateState
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.random.Random

/*
 * The "waiting for a phone" scene: a slow, living background (aurora light, silk ribbons,
 * drifting stars), broadcast waves around the connect card, and after a couple of quiet
 * minutes an ambient mode with a big clock that wanders slightly so nothing burns in.
 *
 * Everything animates from one clock read only while drawing, so the screen redraws at
 * ~30 fps without recomposing.
 */

/** Seconds of scene time at ~30 fps (starts at a pleasant moment rather than 0). */
@Composable
fun rememberSceneTime(): State<Float> = produceState(START_TIME) {
    val start = withFrameNanos { it }
    var last = 0L
    while (true) {
        withFrameNanos { now ->
            if (now - last >= FRAME_NANOS) {
                last = now
                value = START_TIME + (now - start) / 1e9f
            }
        }
    }
}

private const val START_TIME = 14f
private const val FRAME_NANOS = 33_000_000L

private class Blob(
    val x: Float, val y: Float, val ax: Float, val ay: Float,
    val fx: Float, val fy: Float, val phase: Float,
    val radius: Float, val color: Color, val alpha: Float,
)

private class Ribbon(
    val y: Float, val amp: Float, val waves: Float, val speed: Float, val phase: Float,
    val colors: List<Color>,
)

private class Star(val x: Float, val y: Float, val vx: Float, val vy: Float, val size: Float, val twinkle: Float, val phase: Float, val brightness: Float)

private val Indigo = Color(0xFF3B5BDB)

private val blobs = listOf(
    Blob(0.78f, 0.22f, 0.10f, 0.12f, 0.031f, 0.023f, 0.0f, 0.48f, TvPalette.Violet, 0.42f),
    Blob(0.15f, 0.85f, 0.12f, 0.10f, 0.027f, 0.035f, 1.7f, 0.42f, TvPalette.Cyan, 0.30f),
    Blob(0.55f, 0.65f, 0.18f, 0.14f, 0.019f, 0.029f, 3.1f, 0.36f, TvPalette.Pink, 0.18f),
    Blob(0.30f, 0.15f, 0.14f, 0.08f, 0.024f, 0.017f, 4.4f, 0.40f, Indigo, 0.26f),
)

private val ribbons = listOf(
    Ribbon(0.62f, 0.07f, 1.3f, 0.23f, 0.0f, listOf(TvPalette.Cyan, TvPalette.Violet)),
    Ribbon(0.70f, 0.05f, 1.8f, -0.17f, 2.0f, listOf(TvPalette.Violet, TvPalette.Pink)),
    Ribbon(0.55f, 0.09f, 0.9f, 0.11f, 4.0f, listOf(Indigo, TvPalette.Cyan)),
)

private val stars = Random(42).let { r ->
    List(80) {
        Star(
            x = r.nextFloat(), y = r.nextFloat(),
            vx = (r.nextFloat() - 0.5f) * 0.004f, vy = 0.002f + r.nextFloat() * 0.006f,
            size = 0.6f + r.nextFloat() * 1.6f,
            twinkle = 0.4f + r.nextFloat() * 1.4f, phase = r.nextFloat() * 6.28f,
            brightness = 0.25f + r.nextFloat() * 0.6f,
        )
    }
}

/** Aurora light, silk ribbons and slowly rising stars. */
@Composable
fun LivingBackground(time: State<Float>, modifier: Modifier = Modifier) {
    val path = remember { Path() }
    Canvas(modifier.fillMaxSize()) {
        val t = time.value
        val w = size.width
        val h = size.height
        drawRect(Brush.verticalGradient(listOf(Color(0xFF0B1022), Color(0xFF080B16), Color(0xFF05070D))))

        // Aurora: big soft lights drifting on slow, never-repeating paths.
        for (b in blobs) {
            val c = Offset(w * (b.x + b.ax * sin(t * b.fx * 2f + b.phase)), h * (b.y + b.ay * cos(t * b.fy * 2f + b.phase * 0.7f)))
            val r = w * b.radius * (1f + 0.08f * sin(t * 0.13f + b.phase))
            drawCircle(Brush.radialGradient(listOf(b.color.copy(alpha = b.alpha), b.color.copy(alpha = b.alpha * 0.35f), Color.Transparent), c, r), r, c)
        }

        // Silk ribbons of light flowing across the lower half.
        for (rb in ribbons) {
            path.reset()
            val steps = 64
            for (i in 0..steps) {
                val fx = i / steps.toFloat()
                val y = h * (
                    rb.y +
                        rb.amp * sin((fx * rb.waves * 2f * PI.toFloat()) + t * rb.speed + rb.phase) +
                        rb.amp * 0.4f * sin((fx * rb.waves * 3.1f * PI.toFloat()) - t * rb.speed * 0.7f)
                    )
                if (i == 0) path.moveTo(0f, y) else path.lineTo(fx * w, y)
            }
            val glow = Brush.horizontalGradient(listOf(Color.Transparent, rb.colors[0].copy(alpha = 0.10f), rb.colors[1].copy(alpha = 0.10f), Color.Transparent))
            val line = Brush.horizontalGradient(listOf(Color.Transparent, rb.colors[0].copy(alpha = 0.45f), rb.colors[1].copy(alpha = 0.45f), Color.Transparent))
            drawPath(path, glow, style = Stroke(width = 22.dp.toPx(), cap = StrokeCap.Round))
            drawPath(path, line, style = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round))
        }

        // Stars: drift upwards and twinkle.
        for (s in stars) {
            val x = ((s.x + t * s.vx) % 1f + 1f) % 1f * w
            val y = ((s.y - t * s.vy) % 1f + 1f) % 1f * h
            val a = s.brightness * (0.55f + 0.45f * sin(t * s.twinkle + s.phase))
            drawCircle(Color.White.copy(alpha = a.coerceIn(0f, 1f)), s.size.dp.toPx(), Offset(x, y))
        }

        // Vignette keeps the edges calm and the text readable.
        drawRect(Brush.radialGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.55f)), center, max(w, h) * 0.8f))
    }
}

/** Rings spreading out like a signal, behind the connect card. */
@Composable
fun CastWaves(time: State<Float>, modifier: Modifier = Modifier, color: Color = TvPalette.Cyan) {
    Canvas(modifier) {
        val t = time.value
        val maxR = size.maxDimension * 0.62f
        for (i in 0 until 4) {
            val p = ((t / 7f) + i / 4f) % 1f
            val r = maxR * (0.42f + 0.58f * p)
            val a = (1f - p) * (1f - p) * 0.30f
            drawCircle(if (i % 2 == 0) color.copy(alpha = a) else TvPalette.Violet.copy(alpha = a), r, center, style = Stroke(1.5.dp.toPx()))
        }
    }
}

/**
 * Ambient mode: a big clock, the date and a small "ready" card with the QR code. The
 * whole group wanders a few dozen pixels over minutes (burn-in protection on OLED TVs).
 */
@Composable
fun AmbientScene(info: ReceiverInfoState, update: UpdateState, time: State<Float>) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(10_000)
        }
    }
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.align(Alignment.Center).graphicsLayer {
                val t = time.value
                translationX = 48.dp.toPx() * sin(t / 97f)
                translationY = 32.dp.toPx() * cos(t / 131f)
            },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(now)),
                style = TextStyle(
                    brush = Brush.linearGradient(listOf(TvPalette.TextPrimary, Color(0xFFCFE9FF), TvPalette.Cyan.copy(alpha = 0.9f))),
                    fontSize = 168.sp,
                    fontWeight = FontWeight.Light,
                    letterSpacing = (-4).sp,
                ),
            )
            Text(
                SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(Date(now)),
                fontSize = 28.sp,
                color = TvPalette.TextSecondary,
                fontWeight = FontWeight.Normal,
            )
            Spacer(Modifier.height(48.dp))
            ReadyCard(info)
            if (update is UpdateState.ReadyToInstall || update is UpdateState.NeedsPermission) {
                Spacer(Modifier.height(20.dp))
                UpdatePill(update)
            }
        }
    }
}

@Composable
private fun ReadyCard(info: ReceiverInfoState) {
    Row(
        Modifier.clip(RoundedCornerShape(24.dp))
            .background(TvPalette.Surface.copy(alpha = 0.55f))
            .border(1.dp, Brush.linearGradient(listOf(TvPalette.Cyan.copy(alpha = 0.35f), TvPalette.Violet.copy(alpha = 0.35f))), RoundedCornerShape(24.dp))
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(TvPalette.Accent),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Filled.Cast, contentDescription = null, tint = Color(0xFF0A0D14), modifier = Modifier.size(24.dp)) }
        Spacer(Modifier.width(16.dp))
        Column {
            Text("Ready to cast", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = TvPalette.TextPrimary)
            Text(
                listOfNotNull(info.deviceName, info.ipAddress).joinToString("  ·  "),
                fontSize = 16.sp,
                color = TvPalette.TextSecondary,
            )
        }
        info.ipAddress?.let { ip ->
            Spacer(Modifier.width(22.dp))
            QrCode(com.castlite.protocol.TvQrCode.build(ip, info.port, info.deviceName), Modifier.size(92.dp))
        }
    }
}
