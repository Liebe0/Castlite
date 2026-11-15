package com.castlite.phone.media

import org.junit.Assert.assertEquals
import org.junit.Test

class FileNamesTest {

    @Test fun keepsSafeNames() =
        assertEquals("holiday.mp4", FileNames.sanitize("holiday.mp4", "mp4"))

    @Test fun replacesUnsafeCharacters() =
        assertEquals("Fam_lia_2024_1_.MOV", FileNames.sanitize("Família 2024 (1).MOV", "mov"))

    @Test fun addsExtensionFromMime() =
        assertEquals("VID_0001.mp4", FileNames.sanitize("VID_0001", "mp4"))

    @Test fun fallbackForEmptyName() =
        assertEquals("media.jpg", FileNames.sanitize("ééé", "jpg"))
}
