package com.castlite.tv.ui

import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.castlite.protocol.MediaType
import com.castlite.protocol.ReceiverState
import com.castlite.tv.ReceiverViewModel
import kotlinx.coroutines.delay

/** Top-level TV UI: picks the screen for the current state. */
@Composable
fun ReceiverApp(vm: ReceiverViewModel) {
    val ui by vm.engine.ui.collectAsStateWithLifecycle()
    val info by vm.info.collectAsStateWithLifecycle()

    // The TV may still be joining Wi-Fi when the app opens: keep the shown IP fresh.
    LaunchedEffect(Unit) {
        while (true) {
            delay(5_000)
            vm.refreshAddress()
        }
    }

    Box(Modifier.fillMaxSize().background(TvPalette.Background)) {
        val item = ui.item
        when {
            item == null -> IdleScreen(info, ui.lastMessage)
            item.type == MediaType.IMAGE -> PhotoScreen(
                ui = ui,
                onShown = vm.engine::onImageShown,
                onFailed = vm.engine::onImageFailed,
            )
            item.type == MediaType.AUDIO -> AudioScreen(ui)
            else -> VideoScreen(ui) { PlayerSurface(vm) }
        }
        if (item != null && ui.state == ReceiverState.ERROR) {
            ErrorOverlay(ui.error ?: "Something went wrong", hasNext = ui.index + 1 < ui.count)
        }
    }
}

/** ExoPlayer's video surface; all controls are drawn in Compose on top of it. */
@OptIn(UnstableApi::class)
@Composable
private fun PlayerSurface(vm: ReceiverViewModel) {
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { context ->
            PlayerView(context).apply {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                useController = false
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                setShutterBackgroundColor(android.graphics.Color.BLACK)
                setKeepContentOnPlayerReset(false)
                player = vm.engine.player
            }
        },
        onRelease = { it.player = null },
    )
}
