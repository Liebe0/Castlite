package com.castlite.tv.playback

import com.castlite.protocol.MediaType
import com.castlite.protocol.QueueItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QueuePlanTest {
    private fun item(t: MediaType, n: Int) = QueueItem("u$n", "t$n", "x", t)
    private val photo0 = item(MediaType.IMAGE, 0)
    private val song1 = item(MediaType.AUDIO, 1)
    private val photo2 = item(MediaType.IMAGE, 2)
    private val video3 = item(MediaType.VIDEO, 3)

    @Test fun photosWithMusicBecomeASlideshowWithBackgroundSongs() {
        val plan = QueuePlan.of(listOf(photo0, song1, photo2), slideshowMusic = true)
        assertTrue(plan.musicMode)
        assertEquals(listOf(photo0, photo2), plan.shown)
        assertEquals(listOf(0, 2), plan.fullIndex)
        assertEquals(listOf(song1), plan.music)
        assertEquals(1, plan.shownPosition(2))
        assertNull(plan.shownPosition(1)) // the song isn't "shown"
    }

    @Test fun musicOptionOffOrVideosPresentPlaysEverythingInOrder() {
        assertFalse(QueuePlan.of(listOf(photo0, song1, photo2), slideshowMusic = false).musicMode)
        val withVideo = QueuePlan.of(listOf(photo0, song1, video3), slideshowMusic = true)
        assertFalse(withVideo.musicMode)
        assertEquals(listOf(0, 1, 2), withVideo.fullIndex)
    }

    @Test fun noSongsMeansNoMusicMode() =
        assertFalse(QueuePlan.of(listOf(photo0, photo2), slideshowMusic = true).musicMode)
}
