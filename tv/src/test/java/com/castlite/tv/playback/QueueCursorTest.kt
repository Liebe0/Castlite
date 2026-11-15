package com.castlite.tv.playback

import com.castlite.protocol.MediaType
import com.castlite.protocol.QueueItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QueueCursorTest {
    private val items = (0 until 3).map { QueueItem("u$it", "t$it", "video/mp4", MediaType.VIDEO) }

    @Test fun emptyQueue() {
        val c = QueueCursor()
        assertEquals(-1, c.index)
        assertNull(c.current)
        assertFalse(c.next())
    }

    @Test fun startIndexIsClamped() = assertEquals(2, QueueCursor(items, 9).index)

    @Test fun nextStopsAtEnd() {
        val c = QueueCursor(items, 1)
        assertTrue(c.next())
        assertEquals("t2", c.current!!.title)
        assertFalse(c.next())
        assertEquals(2, c.index)
    }

    @Test fun previousRestartsWhenPastThreshold() {
        val c = QueueCursor(items, 2)
        assertFalse(c.previous(positionMs = 30_000)) // restart current
        assertEquals(2, c.index)
        assertTrue(c.previous(positionMs = 1_000))
        assertEquals(1, c.index)
    }

    @Test fun jumpValidatesIndex() {
        val c = QueueCursor(items)
        assertTrue(c.jumpTo(2))
        try {
            c.jumpTo(3)
            throw AssertionError("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("No item 3"))
        }
    }
}
