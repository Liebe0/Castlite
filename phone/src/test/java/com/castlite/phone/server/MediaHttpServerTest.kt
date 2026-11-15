package com.castlite.phone.server

import com.castlite.phone.media.CastMedia
import com.castlite.phone.media.MediaLibrary
import fi.iki.elonen.NanoHTTPD
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.random.Random

/**
 * Starts the real [MediaHttpServer] on localhost, backed by a temp file instead of a
 * content:// URI, and checks the HTTP behaviour TVs rely on (Range, HEAD, 416, DLNA headers).
 */
class MediaHttpServerTest {

    private val bytes = Random(42).nextBytes(100_000)
    private lateinit var file: File
    private lateinit var server: MediaHttpServer
    private lateinit var video: CastMedia

    @Before fun setUp() {
        file = File.createTempFile("castlite", ".mp4").apply { writeBytes(bytes) }
        server = MediaHttpServer({ _, offset -> FileInputStream(file).apply { skipFully(offset) } }, 0)
        server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, true)
        video = media("vid1234567", "clip.mp4", "video/mp4", bytes.size.toLong())
        MediaLibrary.add(listOf(video))
    }

    @After fun tearDown() {
        server.stop()
        MediaLibrary.clear()
        file.delete()
    }

    private fun media(id: String, name: String, mime: String, size: Long) =
        CastMedia(id, "content://test/$id", name, name, mime, size)

    private fun request(path: String, method: String = "GET", range: String? = null): HttpURLConnection =
        (URL("http://127.0.0.1:${server.listeningPort}$path").openConnection() as HttpURLConnection).apply {
            requestMethod = method
            range?.let { setRequestProperty("Range", it) }
        }

    private fun HttpURLConnection.body(): ByteArray =
        (if (responseCode < 400) inputStream else errorStream).use { it.readBytes() }

    @Test fun fullFile() {
        val c = request(video.path)
        assertEquals(200, c.responseCode)
        assertEquals("video/mp4", c.contentType)
        assertEquals(bytes.size.toLong(), c.getHeaderField("Content-Length").toLong())
        assertEquals("bytes", c.getHeaderField("Accept-Ranges"))
        assertEquals("Streaming", c.getHeaderField("transferMode.dlna.org"))
        assertTrue(c.getHeaderField("contentFeatures.dlna.org").startsWith("DLNA.ORG_OP=01"))
        assertArrayEquals(bytes, c.body())
    }

    @Test fun closedRange() {
        val c = request(video.path, range = "bytes=1000-1999")
        assertEquals(206, c.responseCode)
        assertEquals("bytes 1000-1999/${bytes.size}", c.getHeaderField("Content-Range"))
        assertEquals("1000", c.getHeaderField("Content-Length"))
        assertArrayEquals(bytes.copyOfRange(1000, 2000), c.body())
    }

    @Test fun seekToMiddle() {
        val c = request(video.path, range = "bytes=60000-")
        assertEquals(206, c.responseCode)
        assertEquals("bytes 60000-99999/${bytes.size}", c.getHeaderField("Content-Range"))
        assertArrayEquals(bytes.copyOfRange(60000, bytes.size), c.body())
    }

    @Test fun suffixRange() {
        val c = request(video.path, range = "bytes=-500")
        assertEquals(206, c.responseCode)
        assertArrayEquals(bytes.copyOfRange(bytes.size - 500, bytes.size), c.body())
    }

    @Test fun rangePastEnd() {
        val c = request(video.path, range = "bytes=${bytes.size}-")
        assertEquals(416, c.responseCode)
        assertEquals("bytes */${bytes.size}", c.getHeaderField("Content-Range"))
    }

    @Test fun headHasLengthButNoBody() {
        val c = request(video.path, method = "HEAD")
        assertEquals(200, c.responseCode)
        assertEquals(bytes.size.toLong(), c.getHeaderField("Content-Length").toLong())
        assertEquals(0, c.inputStream.use { it.readBytes() }.size)
    }

    @Test fun headWithRange() {
        val c = request(video.path, method = "HEAD", range = "bytes=10-19")
        assertEquals(206, c.responseCode)
        assertEquals("10", c.getHeaderField("Content-Length"))
    }

    @Test fun unknownIdIs404() {
        assertEquals(404, request("/media/nope/clip.mp4").responseCode)
    }

    @Test fun postIsRejected() {
        assertEquals(405, request(video.path, method = "POST").responseCode)
    }

    @Test fun unknownSizeStreamsWithoutRanges() {
        val noSize = media("nosize0000", "song.mp3", "audio/mpeg", -1)
        MediaLibrary.add(listOf(noSize))
        val c = request(noSize.path, range = "bytes=100-")
        assertEquals(200, c.responseCode)
        assertNull(c.getHeaderField("Accept-Ranges"))
        assertTrue(c.getHeaderField("contentFeatures.dlna.org").startsWith("DLNA.ORG_OP=00"))
        assertArrayEquals(bytes, c.body())
    }

    @Test fun unreadableFileGivesClearError() {
        val broken = MediaHttpServer({ _, _ -> null }, 0).apply { start(NanoHTTPD.SOCKET_READ_TIMEOUT, true) }
        try {
            val c = URL("http://127.0.0.1:${broken.listeningPort}${video.path}").openConnection() as HttpURLConnection
            assertEquals(500, c.responseCode)
            assertTrue(String(c.body()).contains("pick it again"))
        } finally {
            broken.stop()
        }
    }

    @Test fun indexListsSharedFiles() {
        val c = request("/")
        assertEquals(200, c.responseCode)
        val html = String(c.body())
        assertTrue(html.contains("clip.mp4"))
        assertTrue(html.contains(video.path))
    }

    @Test fun requestsAreLogged() {
        request(video.path, range = "bytes=0-9").body()
        val last = ServerMonitor.requests.value.first()
        assertEquals(206, last.status)
        assertEquals("bytes=0-9", last.range)
        assertEquals(video.path, last.path)
    }
}
