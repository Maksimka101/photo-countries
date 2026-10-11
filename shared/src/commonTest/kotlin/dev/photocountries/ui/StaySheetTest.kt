package dev.photocountries.ui

import dev.photocountries.core.PhotoPoint
import dev.photocountries.core.PhotoSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class StaySheetTest {
    private val time = Instant.parse("2024-05-01T10:20:30Z")

    @Test
    fun diskPreviewGetsRequestedSize() {
        val photo = PhotoPoint(time, 44.5, 20.25, PhotoSource.YandexDisk, "42:abc", "https://d.example/preview/k?uid=1&size=S&crop=0")
        assertEquals("https://d.example/preview/k?uid=1&size=XXXL&crop=0", photo.imageUrl("XXXL"))
        assertEquals(null, photo.copy(preview = null).imageUrl("L"))
    }

    @Test
    fun galleryPhotoIsShownByMediaStoreId() {
        val photo = PhotoPoint(time, 44.5, 20.25, PhotoSource.Gallery, "media:7")
        assertEquals("content://media/external/images/media/7", photo.imageUrl("L"))
    }
}
