package com.castlite.tv.screenshots

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.Density
import com.android.resources.ScreenOrientation
import com.castlite.protocol.MediaType
import com.castlite.protocol.QueueItem
import com.castlite.protocol.ReceiverState
import com.castlite.tv.ReceiverInfoState
import com.castlite.tv.playback.TvUiState
import com.castlite.tv.ui.AudioScreen
import com.castlite.tv.ui.ErrorOverlay
import com.castlite.tv.ui.IdleScreen
import com.castlite.tv.ui.PhotoScreen
import com.castlite.tv.ui.TvTheme
import com.castlite.tv.ui.VideoScreen
import org.junit.Rule
import org.junit.Test

/** Renders the TV screens at 1080p (960×540 dp). Record with `./gradlew :tv:recordPaparazziDebug`. */
class TvScreensTest {

    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_5.copy(
            screenWidth = 1920, screenHeight = 1080, xdpi = 320, ydpi = 320,
            density = Density.XHIGH, orientation = ScreenOrientation.LANDSCAPE, softButtons = false,
        ),
        showSystemUi = false,
        maxPercentDifference = 0.5,
    )

    private val info = ReceiverInfoState("Living Room TV", "192.168.1.60", 8766, listening = true)
    private val video = QueueItem("http://p/v.mp4", "Summer in Lisbon.mp4", "video/mp4", MediaType.VIDEO, 754_000)
    private val song = QueueItem("http://p/s.mp3", "Road trip playlist – 01 Golden Hour", "audio/mpeg", MediaType.AUDIO, 214_000)
    private val photo = QueueItem("http://p/p.jpg", "Beach sunset.jpg", "image/jpeg", MediaType.IMAGE)

    private fun shot(content: @Composable () -> Unit) = paparazzi.snapshot { TvTheme { content() } }

    /** Stand-in for a video frame (ExoPlayer can't render in a JVM test). */
    @Composable
    private fun FakeFrame() = Box(
        Modifier.fillMaxSize().background(
            Brush.linearGradient(listOf(Color(0xFF1E3C72), Color(0xFF2A5298), Color(0xFFF2994A))),
        ),
    )

    @Test fun idle() = shot { IdleScreen(info, lastMessage = null) }

    @Test fun idle_after_session() = shot { IdleScreen(info, lastMessage = "Finished playing") }

    @Test fun idle_offline() = shot { IdleScreen(info.copy(ipAddress = null, listening = false), lastMessage = null) }

    @Test fun video_overlay() = shot {
        VideoScreen(
            TvUiState(item = video, index = 0, count = 5, state = ReceiverState.PLAYING, positionMs = 312_000, durationMs = 754_000, overlayVisible = true, phone = "192.168.1.23"),
        ) { FakeFrame() }
    }

    @Test fun video_paused_with_warning() = shot {
        VideoScreen(
            TvUiState(item = video, index = 1, count = 5, state = ReceiverState.PAUSED, positionMs = 61_000, durationMs = 754_000,
                warning = "This TV can't decode the video track — playing sound only.", phone = "192.168.1.23"),
        ) { FakeFrame() }
    }

    @Test fun video_reconnecting() = shot {
        VideoScreen(
            TvUiState(item = video, index = 0, count = 5, state = ReceiverState.LOADING, isBuffering = true, positionMs = 312_000, durationMs = 754_000,
                overlayVisible = true, warning = "Reconnecting to your phone…", phone = "192.168.1.23"),
        ) { FakeFrame() }
    }

    @Test fun audio() = shot {
        AudioScreen(TvUiState(item = song, index = 3, count = 5, state = ReceiverState.PLAYING, positionMs = 81_000, durationMs = 214_000))
    }

    @Test fun photo_overlay() = shot {
        Box {
            PhotoScreen(TvUiState(item = photo, index = 1, count = 5, state = ReceiverState.PAUSED, overlayVisible = true), { }, { _, _ -> })
        }
    }

    @Test fun error() = shot {
        Box {
            FakeFrame()
            ErrorOverlay("This TV can't decode this file (video/hevc 3840×2160). Try a different file, or convert it to H.264 / AAC MP4.", hasNext = true)
        }
    }
}
