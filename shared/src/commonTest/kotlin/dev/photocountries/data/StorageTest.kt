package dev.photocountries.data

import dev.photocountries.core.HiddenStay
import dev.photocountries.core.PhotoPoint
import dev.photocountries.core.PhotoSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Instant

class StorageTest {
    private val cached = CachedPoints(
        Instant.parse("2026-10-11T08:00:00Z"),
        listOf(
            PhotoPoint(Instant.parse("2024-05-01T10:20:30Z"), 44.5, 20.25, PhotoSource.YandexDisk, "42:abc"),
            PhotoPoint(Instant.parse("2019-01-01T00:00:00Z"), -33.25, -70.5, PhotoSource.Gallery, "content://media/1 2"),
        ),
    )

    @Test
    fun roundTrip() {
        val decoded = assertNotNull(decodePoints(encodePoints(cached).lines()))
        assertEquals(cached.fetchedAt, decoded.fetchedAt)
        assertEquals(cached.points, decoded.points)
    }

    @Test
    fun emptyListRoundTrips() {
        val decoded = assertNotNull(decodePoints(encodePoints(CachedPoints(cached.fetchedAt, emptyList())).lines()))
        assertEquals(emptyList(), decoded.points)
    }

    @Test
    fun brokenTextIsNoCache() {
        assertNull(decodePoints(emptyList()))
        assertNull(decodePoints(listOf("что-то другое", "1")))
        val lines = encodePoints(cached).lines()
        assertNull(decodePoints(lines.take(2) + "1\t2\t3"))
        assertNull(decodePoints(lines.take(2) + "x\t1.0\t2.0\tYandexDisk\tid"))
    }

    @Test
    fun hiddenRoundTripsAndSkipsBrokenLines() {
        val hidden = listOf(
            HiddenStay("RU", Instant.parse("2025-05-29T10:00:00Z"), Instant.parse("2025-05-29T10:05:00Z")),
            HiddenStay("GE", Instant.parse("2024-01-01T00:00:00Z"), Instant.parse("2024-01-03T00:00:00Z")),
        )
        val lines = encodeHidden(hidden).lines()
        assertEquals(hidden, decodeHidden(lines))
        assertEquals(hidden, decodeHidden(lines + "RU\tx\t1"))
        assertEquals(emptyList(), decodeHidden(listOf("что-то другое")))
        assertEquals(emptyList(), decodeHidden(emptyList()))
    }

    @Test
    fun uiPrefsRoundTripAndFallBackToDefaults() {
        assertEquals(UiPrefs(2, true), decodeUi(encodeUi(UiPrefs(2, true))))
        assertEquals(UiPrefs(1, false), decodeUi(encodeUi(UiPrefs(1, false))))
        assertEquals(UiPrefs(), decodeUi(""))
        assertEquals(UiPrefs(), decodeUi("что-то другое"))
    }
}
