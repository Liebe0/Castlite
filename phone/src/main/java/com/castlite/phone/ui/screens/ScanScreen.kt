package com.castlite.phone.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.NoPhotography
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.castlite.phone.qr.QrDecoder
import com.castlite.phone.ui.components.GradientButton
import com.castlite.phone.ui.theme.Palette
import com.castlite.protocol.TvQrCode
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Full-screen scanner for the QR code on the CastLite TV app's start screen.
 * Calls [onCode] once with the first CastLite code it sees.
 */
@Composable
fun ScanScreen(
    onCode: (String) -> Unit,
    onClose: () -> Unit,
    onEnterIp: () -> Unit,
    /** false = draw the screen without starting the camera (previews, screenshot tests). */
    camera: Boolean = true,
) {
    val context = LocalContext.current
    val preview = LocalInspectionMode.current || !camera
    var granted by remember {
        mutableStateOf(preview || ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    var denied by remember { mutableStateOf(false) }
    var hint by remember { mutableStateOf<String?>(null) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok
        denied = !ok
    }
    LaunchedEffect(Unit) { if (!granted) permission.launch(Manifest.permission.CAMERA) }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (granted && !preview) {
            CameraPreview(onCode = onCode, onOtherCode = { hint = "That's not a CastLite TV code. Scan the one on the TV's start screen." })
        }
        if (granted) Viewfinder()

        // Top bar
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = "Close", tint = Color.White) }
            Text("Scan the TV's code", style = MaterialTheme.typography.titleLarge, color = Color.White)
        }

        // Bottom
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when {
                denied -> {
                    Icon(Icons.Filled.NoPhotography, contentDescription = null, tint = Palette.TextSecondary, modifier = Modifier.size(40.dp))
                    Text(
                        "CastLite needs the camera to scan the code. Nothing is recorded or sent anywhere.",
                        color = Color.White, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium,
                    )
                    GradientButton("Allow camera", { permission.launch(Manifest.permission.CAMERA) })
                }
                else -> Text(
                    hint ?: "Open CastLite on the TV and point your phone at the code on its start screen.",
                    color = if (hint != null) Palette.Warning else Color.White,
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Row(
                Modifier.background(Color.White.copy(alpha = 0.12f), MaterialTheme.shapes.extraLarge).padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onEnterIp) { Icon(Icons.Filled.Keyboard, contentDescription = null, tint = Color.White) }
                Text("Enter the IP address instead", color = Color.White, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(end = 16.dp))
            }
        }
    }
}

@Composable
private fun CameraPreview(onCode: (String) -> Unit, onOtherCode: () -> Unit) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val delivered = remember { AtomicBoolean(false) }
    val executor = remember { Executors.newSingleThreadExecutor() }
    val main = remember { Handler(Looper.getMainLooper()) }
    var provider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    DisposableEffect(Unit) {
        onDispose {
            provider?.unbindAll()
            executor.shutdown()
        }
    }
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            val view = PreviewView(ctx).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
            val future = ProcessCameraProvider.getInstance(ctx)
            future.addListener({
                val p = future.get()
                provider = p
                val previewUseCase = Preview.Builder().build().also { it.setSurfaceProvider(view.surfaceProvider) }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                var lastOther = 0L
                analysis.setAnalyzer(executor) { image ->
                    try {
                        if (delivered.get()) return@setAnalyzer
                        val plane = image.planes[0]
                        val buffer = plane.buffer
                        val bytes = ByteArray(buffer.remaining()).also { buffer.get(it) }
                        val text = QrDecoder.decode(bytes, plane.rowStride, image.width, image.height) ?: return@setAnalyzer
                        if (TvQrCode.parse(text) != null) {
                            if (delivered.compareAndSet(false, true)) main.post { onCode(text) }
                        } else if (System.currentTimeMillis() - lastOther > 2_000) {
                            lastOther = System.currentTimeMillis()
                            main.post(onOtherCode)
                        }
                    } finally {
                        image.close()
                    }
                }
                try {
                    p.unbindAll()
                    p.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, previewUseCase, analysis)
                } catch (e: Exception) {
                    // No back camera / camera busy: the "Enter IP" button still works.
                }
            }, ContextCompat.getMainExecutor(ctx))
            view
        },
    )
}

/** Dimmed screen with a clear rounded square, cyan corners and a moving scan line. */
@Composable
private fun Viewfinder() {
    val t = rememberInfiniteTransition(label = "scan")
    val line by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1_800, easing = LinearEasing), RepeatMode.Reverse), label = "line")
    Canvas(Modifier.fillMaxSize().graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }) {
        val side = size.minDimension * 0.68f
        val left = (size.width - side) / 2
        val top = (size.height - side) / 2 - size.height * 0.04f
        val radius = CornerRadius(28.dp.toPx())
        drawRect(Color.Black.copy(alpha = 0.55f))
        drawRoundRect(Color.Transparent, Offset(left, top), Size(side, side), radius, blendMode = BlendMode.Clear)
        val arm = side * 0.16f
        val w = 5.dp.toPx()
        val corners = listOf(
            Offset(left, top) to Offset(1f, 1f), Offset(left + side, top) to Offset(-1f, 1f),
            Offset(left, top + side) to Offset(1f, -1f), Offset(left + side, top + side) to Offset(-1f, -1f),
        )
        for ((p, d) in corners) {
            drawLine(Palette.Cyan, p, Offset(p.x + d.x * arm, p.y), w, StrokeCap.Round)
            drawLine(Palette.Cyan, p, Offset(p.x, p.y + d.y * arm), w, StrokeCap.Round)
        }
        val y = top + side * (0.1f + 0.8f * line)
        drawLine(Palette.Violet.copy(alpha = 0.9f), Offset(left + side * 0.08f, y), Offset(left + side * 0.92f, y), 3.dp.toPx(), StrokeCap.Round)
        drawRoundRect(Color.White.copy(alpha = 0.18f), Offset(left, top), Size(side, side), radius, style = Stroke(1.dp.toPx()))
    }
}

