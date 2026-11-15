package com.castlite.tv.playback

import android.content.Context
import android.view.KeyEvent
import androidx.media3.common.Player
import androidx.media3.test.utils.robolectric.RobolectricUtil
import androidx.media3.test.utils.robolectric.ShadowMediaCodecConfig
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.castlite.protocol.Command
import com.castlite.protocol.MediaType
import com.castlite.protocol.QueueItem
import com.castlite.protocol.TunnelClient
import com.castlite.protocol.TunnelServer
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileInputStream
import java.util.Collections

/**
 * The real [PlaybackEngine] (ExoPlayer, simulated decoders) streaming a real 2:30 video
 * over HTTP the way the phone serves it, and seeking from the phone and the remote.
 */
@RunWith(AndroidJUnit4::class)
class PlaybackEngineSeekTest {

    @get:Rule val codecs: ShadowMediaCodecConfig = ShadowMediaCodecConfig.forAllSupportedMimeTypes()

    private val ranges: MutableList<String?> = Collections.synchronizedList(mutableListOf())
    private lateinit var http: NanoHTTPD
    private var tunnelServer: TunnelServer? = null
    private var tunnelClient: TunnelClient? = null
    private lateinit var engine: PlaybackEngine

    /** Like the phone's media server: 200 with a length, 206 for ranges. [slow] = Wi-Fi-like speed. */
    private fun serve(resource: String, slow: Boolean = false): String {
        // Gradle runs unit tests from the module directory.
        val file = File("src/test/resources/media/$resource").takeIf { it.isFile } ?: File("tv/src/test/resources/media/$resource")
        http = object : NanoHTTPD("127.0.0.1", 0) {
            override fun serve(session: IHTTPSession): Response {
                val total = file.length()
                val range = session.headers["range"]
                ranges += range
                val parts = range?.removePrefix("bytes=")?.split("-")
                val r = if (parts != null) {
                    val start = parts[0].toLong()
                    val end = parts.getOrNull(1)?.takeIf { it.isNotEmpty() }?.toLong() ?: (total - 1)
                    newFixedLengthResponse(Response.Status.PARTIAL_CONTENT, "video/mp4", body(FileInputStream(file).apply { skip(start) }, slow), end - start + 1)
                        .apply { addHeader("Content-Range", "bytes $start-$end/$total") }
                } else {
                    newFixedLengthResponse(Response.Status.OK, "video/mp4", body(FileInputStream(file), slow), total)
                }
                r.addHeader("Accept-Ranges", "bytes")
                return r
            }
        }.apply { start(5_000, true) }
        return "http://127.0.0.1:${http.listeningPort}/media/abc/$resource"
    }

    /** ~100 KB/s, so the player can't have the whole file buffered when it seeks. */
    private fun body(input: java.io.InputStream, slow: Boolean): java.io.InputStream = if (!slow) input else object : java.io.FilterInputStream(input) {
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            Thread.sleep(20)
            return super.read(b, off, minOf(len, 2_048))
        }
    }

    @After fun tearDown() {
        if (::engine.isInitialized) engine.player.release()
        tunnelClient?.close()
        tunnelServer?.close()
        http.stop()
    }

    private fun play(url: String) {
        engine = PlaybackEngine(ApplicationProvider.getApplicationContext<Context>(), CoroutineScope(Dispatchers.Main.immediate))
        engine.execute(Command.Load(listOf(QueueItem(url, "Test", "video/mp4", MediaType.VIDEO, 150_000))), "phone")
        RobolectricUtil.runMainLooperUntil { engine.player.playbackState == Player.STATE_READY }
    }

    private fun awaitReady() = RobolectricUtil.runMainLooperUntil { engine.player.playbackState == Player.STATE_READY }

    @Test fun phoneSeekBarAndRemoteMoveThePlayback() {
        play(serve("seek.mp4"))
        assertTrue(engine.player.isCurrentMediaItemSeekable)

        engine.execute(Command.Seek(80_000), "phone") // "put the video on 1:20"
        awaitReady()
        assertEquals(80_000, engine.player.currentPosition)
        assertEquals(80_000, engine.ui.value.positionMs)

        engine.handleKey(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT)) // +10 s on the remote
        awaitReady()
        assertEquals(90_000, engine.player.currentPosition)

        engine.handleKey(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_LEFT)) // -10 s
        awaitReady()
        assertEquals(80_000, engine.player.currentPosition)
    }

    @Test fun seekBeyondTheBufferFetchesThatPartOfTheFile() {
        play(serve("seek.mp4", slow = true))
        engine.execute(Command.Seek(140_000), "phone")
        awaitReady()
        assertTrue("at ${engine.player.currentPosition}", engine.player.currentPosition in 140_000..141_500)
        // The player asked the phone for bytes near the end, not the start again.
        assertTrue("ranges: $ranges", ranges.any { it != null && it.removePrefix("bytes=").substringBefore('-').toLong() > 300_000 })
    }

    @Test fun seekingWorksThroughTheOtherRouterTunnel() {
        val direct = serve("seek.mp4")
        val tv = TunnelServer(port = 0).apply { start() }.also { tunnelServer = it }
        val phone = TunnelClient("127.0.0.1", tv.listeningPort, http.listeningPort).also { tunnelClient = it }
        val port = phone.open()
        play(direct.replace(":${http.listeningPort}/", ":$port/"))
        engine.execute(Command.Seek(140_000), "phone")
        awaitReady()
        assertEquals(140_000, engine.player.currentPosition)
    }

    @Test fun aFileWithoutAnIndexKeepsPlayingInsteadOfRestarting() {
        play(serve("noindex.mkv"))
        RobolectricUtil.runMainLooperUntil { !engine.player.currentTimeline.isEmpty }
        assertFalse("test file should have no seek index", engine.player.isCurrentMediaItemSeekable)
        val before = engine.player.currentPosition
        engine.execute(Command.Seek(80_000), "phone")
        // Not sent back to 0:00, and the viewer is told why.
        assertTrue(engine.player.currentPosition >= before)
        assertNotNull(engine.ui.value.warning)
    }
}
