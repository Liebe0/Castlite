package com.castlite.phone.media

import com.castlite.phone.ui.DevicesViewModel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaLibraryTest {

    private fun m(id: String) = CastMedia(id, "content://x/$id", id, "$id.mp4", "video/mp4", 1)

    @After fun clear() = MediaLibrary.clear()

    @Test fun addSkipsDuplicatesAndMoves() {
        MediaLibrary.add(listOf(m("a"), m("b"), m("c")))
        MediaLibrary.add(listOf(m("a").copy(id = "a2"))) // same URI, new id → ignored
        assertEquals(listOf("a", "b", "c"), MediaLibrary.items.value.map { it.id })
        MediaLibrary.move("c", -1)
        assertEquals(listOf("a", "c", "b"), MediaLibrary.items.value.map { it.id })
        MediaLibrary.move("a", -1) // already first
        assertEquals(listOf("a", "c", "b"), MediaLibrary.items.value.map { it.id })
        MediaLibrary.remove("c")
        assertEquals(listOf("a", "b"), MediaLibrary.items.value.map { it.id })
    }

    @Test fun idsAreRandomAndUrlSafe() {
        val ids = (1..200).map { MediaLibrary.newId() }.toSet()
        assertEquals(200, ids.size)
        assertTrue(ids.all { it.matches(Regex("[a-z0-9]{10}")) })
    }

    @Test fun validatesManualHosts() {
        assertTrue(DevicesViewModel.isValidHost("192.168.1.50"))
        assertTrue(DevicesViewModel.isValidHost("living-room-tv.local"))
        assertFalse(DevicesViewModel.isValidHost("192.168.1.256"))
        assertFalse(DevicesViewModel.isValidHost("192.168.1"))
        assertFalse(DevicesViewModel.isValidHost("http://192.168.1.5"))
        assertFalse(DevicesViewModel.isValidHost(""))
    }
}
