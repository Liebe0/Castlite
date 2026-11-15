package com.castlite.phone.screenshots

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.castlite.phone.cast.CastDevice
import com.castlite.phone.cast.CastProtocol
import com.castlite.phone.cast.CastSession
import com.castlite.phone.cast.DlnaEndpoints
import com.castlite.phone.cast.PlaybackStatus
import com.castlite.phone.cast.PlayerState
import com.castlite.phone.media.CastMedia
import com.castlite.phone.net.LanStatus
import com.castlite.phone.server.RequestLogEntry
import com.castlite.phone.server.ServerState
import com.castlite.phone.ui.DevicesUiState
import com.castlite.phone.ui.MainUiState
import com.castlite.phone.ui.ManualEntry
import com.castlite.phone.ui.screens.DevicesActions
import com.castlite.phone.ui.screens.DevicesScreen
import com.castlite.phone.ui.screens.HomeActions
import com.castlite.phone.ui.screens.HomeScreen
import com.castlite.phone.ui.screens.NowPlayingScreen
import com.castlite.phone.ui.screens.PlayerActions
import com.castlite.phone.ui.screens.ServerActions
import com.castlite.phone.ui.screens.ServerScreen
import com.castlite.phone.ui.theme.CastLiteTheme
import org.junit.Rule
import org.junit.Test

/**
 * Renders every phone screen with sample data. `./gradlew :phone:recordPaparazziDebug`
 * writes the PNGs to phone/src/test/snapshots/images; a normal test run just checks
 * that every screen renders without crashing.
 */
class PhoneScreensTest {

    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_6.copy(softButtons = false), showSystemUi = false, maxPercentDifference = 0.5)

    private val lan = LanStatus(LanStatus.Kind.WIFI, "192.168.1.23")
    private val media = listOf(
        CastMedia("a1", "content://m/1", "Summer in Lisbon.mp4", "Summer_in_Lisbon.mp4", "video/mp4", 734_003_200, 754_000,
            subtitle = com.castlite.phone.media.SubtitleRef("content://s", "Summer in Lisbon.srt", "application/x-subrip"),
            info = com.castlite.phone.media.MediaInfo("video/avc", listOf("audio/mp4a-latm"), 1920, 1080)),
        CastMedia("a2", "content://m/2", "Beach sunset.jpg", "Beach_sunset.jpg", "image/jpeg", 4_200_000),
        CastMedia("a3", "content://m/3", "Birthday party.mkv", "Birthday_party.mkv", "video/x-matroska", 1_288_490_188, 2_710_000,
            info = com.castlite.phone.media.MediaInfo("video/hevc", listOf("audio/vnd.dts"), 3840, 2160, tenBit = true)),
        CastMedia("a4", "content://m/4", "Road trip playlist – 01.mp3", "Road_trip_playlist_01.mp3", "audio/mpeg", 8_100_000, 214_000),
        CastMedia("a5", "content://m/5", "Family portrait.heic", "Family_portrait.heic", "image/heic", 2_900_000),
    )
    private val lg = CastDevice(
        "uuid:lg", "[LG] webOS TV OLED55C1", CastProtocol.DLNA, "192.168.1.50", "LG Electronics", "OLED55C1",
        dlna = DlnaEndpoints("http://x", "http://x", "http://x"),
    )
    private val shield = CastDevice("castlite:192.168.1.60", "Living Room Shield", CastProtocol.CASTLITE, "192.168.1.60", "CastLite TV app", "NVIDIA SHIELD")

    private fun shot(content: @androidx.compose.runtime.Composable () -> Unit) =
        paparazzi.snapshot { CastLiteTheme { content() } }

    @Test fun home_empty() = shot {
        HomeScreen(MainUiState(lan = lan, server = ServerState.Stopped), HomeActions())
    }

    @Test fun home_queue() = shot {
        HomeScreen(
            MainUiState(
                lan = lan, server = ServerState.Running(8765), media = media, selectedDevice = lg,
                macs = mapOf(lg.id to "A1:B2:C3:D4:E5:F6"), repeat = com.castlite.protocol.RepeatMode.ALL,
                support = com.castlite.phone.media.TvSupport(containers = setOf("video/mp4", "audio/mpeg", "image/jpeg", "image/heic"), isDlna = true),
            ),
            HomeActions(),
        )
    }

    @Test fun home_resume_prompt() = shot {
        HomeScreen(
            MainUiState(lan = lan, server = ServerState.Running(8765), media = media, selectedDevice = shield,
                resumePrompt = com.castlite.phone.ui.ResumePrompt(0, 312_000, "Summer in Lisbon.mp4")),
            HomeActions(),
        )
    }

    @Test fun home_mirroring() = shot {
        HomeScreen(
            MainUiState(lan = lan, server = ServerState.Running(8765), media = media.take(2), selectedDevice = shield,
                mirror = com.castlite.phone.mirror.MirrorUiState.Active("Living Room Shield", 720, 1280, true)),
            HomeActions(),
        )
    }

    @Test fun home_casting() = shot {
        HomeScreen(
            MainUiState(
                lan = lan, server = ServerState.Running(8765), media = media, selectedDevice = shield,
                session = CastSession(shield, media, PlaybackStatus(PlayerState.PLAYING, index = 0, positionMs = 312_000, durationMs = 754_000, volume = 40)),
            ),
            HomeActions(),
        )
    }

    @Test fun home_background_prompt() = shot {
        HomeScreen(
            MainUiState(lan = lan, server = ServerState.Running(8765), media = media.take(3), selectedDevice = lg,
                power = com.castlite.phone.data.PowerStatus(unrestrictedBattery = false)),
            HomeActions(),
        )
    }

    @Test fun home_update_ready() = shot {
        HomeScreen(
            MainUiState(lan = lan, server = ServerState.Running(8765), media = media.take(3), selectedDevice = shield,
                update = com.castlite.updater.UpdateState.ReadyToInstall("1.1.12")),
            HomeActions(),
        )
    }

    @Test fun player_wifi_lost() = shot {
        NowPlayingScreen(
            CastSession(lg, media, PlaybackStatus(PlayerState.PLAYING, index = 0, positionMs = 312_000, durationMs = 754_000, volume = 35),
                networkProblem = "Wi-Fi disconnected. Casting resumes automatically when it's back."),
            5_000, PlayerActions(),
        )
    }

    @Test fun home_offline() = shot {
        HomeScreen(MainUiState(lan = LanStatus.Disconnected, media = media.take(2)), HomeActions())
    }

    @Test fun player_video() = shot {
        NowPlayingScreen(
            CastSession(shield, media, PlaybackStatus(PlayerState.PLAYING, index = 0, positionMs = 312_000, durationMs = 754_000, volume = 35,
                repeat = com.castlite.protocol.RepeatMode.ALL, subtitlesEnabled = true), extraDevices = listOf(lg)),
            5_000, PlayerActions(),
        )
    }

    @Test fun player_error() = shot {
        NowPlayingScreen(
            CastSession(lg, media, PlaybackStatus(PlayerState.ERROR, index = 2, durationMs = 2_710_000, volume = 35,
                error = "[LG] webOS TV OLED55C1 doesn't support this file type (video/x-matroska).")),
            5_000, PlayerActions(),
        )
    }

    @Test fun player_slideshow() = shot {
        NowPlayingScreen(CastSession(shield, media, PlaybackStatus(PlayerState.PLAYING, index = 1, volume = null)), 5_000, PlayerActions())
    }

    @Test fun devices_found() = shot {
        DevicesScreen(
            DevicesUiState(
                devices = listOf(
                    shield,
                    lg,
                    CastDevice("uuid:sam", "Samsung Q60 Series (55)", CastProtocol.DLNA, "192.168.1.51", "Samsung Electronics", "QE55Q60"),
                    CastDevice("castlite:192.168.1.70", "Bedroom TV", CastProtocol.CASTLITE, "192.168.1.70", "CastLite TV app", "Chromecast HD", isManual = true),
                    CastDevice("castlite:192.168.0.40", "Kitchen TV", CastProtocol.CASTLITE, "192.168.0.40", "CastLite TV app", otherRouter = true),
                ),
                selectedId = lg.id, lan = lan, extraIds = setOf(shield.id),
                scan = com.castlite.phone.ui.RouterScan(message = "Found 1 TV on another router."),
            ),
            DevicesActions(),
        )
    }

    @Test fun devices_searching() = shot {
        DevicesScreen(DevicesUiState(lan = lan), DevicesActions())
    }

    @Test fun devices_manual_ip() = shot {
        DevicesScreen(
            DevicesUiState(lan = lan, manual = ManualEntry.Editing("No CastLite TV app answered at 192.168.1.80. Open the app on the TV and check the address it shows.")),
            DevicesActions(),
        )
    }

    /** The scanner asks for camera permission via the activity; give it a no-op stand-in. */
    private val fakeResults = object : androidx.activity.result.ActivityResultRegistryOwner {
        override val activityResultRegistry = object : androidx.activity.result.ActivityResultRegistry() {
            override fun <I, O> onLaunch(
                requestCode: Int,
                contract: androidx.activity.result.contract.ActivityResultContract<I, O>,
                input: I,
                options: androidx.core.app.ActivityOptionsCompat?,
            ) = Unit
        }
    }

    @Test fun scanner() = shot {
        androidx.compose.runtime.CompositionLocalProvider(
            androidx.activity.compose.LocalActivityResultRegistryOwner provides fakeResults,
        ) {
            com.castlite.phone.ui.screens.ScanScreen(onCode = {}, onClose = {}, onEnterIp = {}, camera = false)
        }
    }

    @Test fun server() = shot {
        ServerScreen(
            MainUiState(
                lan = lan, server = ServerState.Running(8765), media = media.take(2),
                requests = listOf(
                    RequestLogEntry(1_700_000_000_000, "192.168.1.50", "GET", "/media/k3j9x2a1mq/Summer_in_Lisbon.mp4", "bytes=104857600-", 206, "LG"),
                    RequestLogEntry(1_700_000_000_000, "192.168.1.50", "HEAD", "/media/k3j9x2a1mq/Summer_in_Lisbon.mp4", null, 200, "LG"),
                    RequestLogEntry(1_700_000_000_000, "192.168.1.60", "GET", "/media/zzz/gone.mp4", null, 404, "ExoPlayer"),
                ),
            ),
            ServerActions(),
        )
    }
}
