package com.castlite.phone.media

import com.castlite.phone.data.ResumePolicy
import com.castlite.phone.net.WakeOnLan
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CompatibilityTest {

    private fun video(mime: String = "video/x-matroska", info: MediaInfo? = null) =
        CastMedia("v", "content://v", "Movie.mkv", "Movie.mkv", mime, 1, 1, info = info)

    private val hevc10 = MediaInfo("video/hevc", listOf("audio/mp4a-latm"), 3840, 2160, tenBit = true)
    private val dtsOnly = MediaInfo("video/avc", listOf("audio/vnd.dts"), 1920, 1080)

    @Test fun unknownTvMeansNoWarning() = assertTrue(Compatibility.check(video(info = dtsOnly), null).ok)

    @Test fun dlnaContainerListIsChecked() {
        val lg = TvSupport(containers = setOf("video/mp4", "video/x-mkv"), isDlna = true)
        assertTrue(Compatibility.check(video(), lg).ok) // MKV alias accepted
        val noMkv = TvSupport(containers = setOf("video/mp4"), isDlna = true)
        assertEquals("MKV files aren't on this TV's supported list", Compatibility.check(video(), noMkv).summary)
    }

    @Test fun dtsOnlyAudioWarnsEvenWithoutCodecList() {
        val c = Compatibility.check(video(info = dtsOnly), TvSupport(isDlna = true))
        assertTrue(c.summary!!.contains("DTS audio"))
    }

    @Test fun castliteTvCodecsAndLimits() {
        val tv = TvSupport(videoCodecs = setOf("video/avc", "video/hevc"), audioCodecs = setOf("audio/mp4a-latm"), hevcMain10 = false, maxVideoHeight = 1080)
        val c = Compatibility.check(video(info = hevc10), tv)
        assertEquals(2, c.problems.size) // 10-bit + 4K
        assertTrue(c.problems.any { it.contains("10-bit") })
        assertTrue(c.problems.any { it.contains("2160p") })
        val av1 = Compatibility.check(video(info = MediaInfo("video/av01", listOf("audio/opus"), 1280, 720)), tv)
        assertTrue(av1.problems.first().contains("AV1"))
        // One playable audio track is enough.
        assertTrue(Compatibility.check(video(info = MediaInfo("video/avc", listOf("audio/vnd.dts", "audio/mp4a-latm"), 1280, 720)), tv).ok)
    }

    @Test fun parsesDlnaSinkList() = assertEquals(
        setOf("video/mp4", "audio/mpeg"),
        Compatibility.parseSinkProtocolInfo("http-get:*:video/mp4:DLNA.ORG_PN=AVC, http-get:*:audio/mpeg:*,garbage"),
    )

    @Test fun wakeOnLanPacket() {
        val mac = WakeOnLan.parseMac("a1-b2-c3-d4-e5-f6")!!
        assertEquals("A1:B2:C3:D4:E5:F6", WakeOnLan.formatMac("a1b2c3d4e5f6"))
        assertNull(WakeOnLan.parseMac("a1:b2:c3"))
        val packet = WakeOnLan.magicPacket(mac)
        assertEquals(102, packet.size)
        assertArrayEquals(ByteArray(6) { 0xFF.toByte() }, packet.copyOfRange(0, 6))
        assertArrayEquals(mac, packet.copyOfRange(96, 102))
        assertEquals("192.168.1.255", WakeOnLan.subnetBroadcast("192.168.1.23"))
    }

    @Test fun resumeOnlyForLongItemsAwayFromTheEdges() {
        val min = 60_000L
        assertTrue(ResumePolicy.shouldRemember(10 * min, 90 * min))
        assertFalse(ResumePolicy.shouldRemember(10_000, 90 * min)) // just started
        assertFalse(ResumePolicy.shouldRemember(90 * min - 5_000, 90 * min)) // basically finished
        assertFalse(ResumePolicy.shouldRemember(60_000, 2 * min)) // short clip
        assertTrue(ResumePolicy.isFinished(90 * min - 5_000, 90 * min))
    }

    @Test fun foldersSortNaturally() {
        val names = listOf("Episode 10.mkv", "Episode 2.mkv", "episode 1.mkv", "Bonus.mkv")
        assertEquals(listOf("Bonus.mkv", "episode 1.mkv", "Episode 2.mkv", "Episode 10.mkv"), names.sortedWith(MediaResolver.NaturalOrder))
        assertEquals("application/x-subrip", MediaResolver.subtitleMime("Movie.SRT"))
        assertNull(MediaResolver.subtitleMime("Movie.mkv"))
    }
}

class MediaLibraryPersistenceTest {
    private val file = File.createTempFile("queue", ".json")

    @After fun cleanup() {
        MediaLibrary.clear()
        file.delete()
    }

    @Test fun queueSurvivesARestart() {
        MediaLibrary.init(file)
        val sub = SubtitleRef("content://s", "a.vtt", "text/vtt")
        MediaLibrary.add(listOf(CastMedia("a", "content://a", "A", "A.mp4", "video/mp4", 5, 1000, sub, MediaInfo("video/avc", listOf("audio/mp4a-latm"), 1920, 1080))))
        MediaLibrary.add(listOf(CastMedia("b", "https://x/b.mp4", "B", "B.mp4", "video/mp4", -1)))
        val saved = file.readText()
        MediaLibrary.clear()
        file.writeText(saved) // what the next app start finds on disk
        MediaLibrary.init(file)
        assertEquals(listOf("a", "b"), MediaLibrary.items.value.map { it.id })
        assertEquals(sub, MediaLibrary.items.value[0].subtitle)
        assertTrue(MediaLibrary.items.value[1].isRemote)
        assertEquals("/media/a/subtitle.vtt", MediaLibrary.items.value[0].subtitlePath)
    }
}
