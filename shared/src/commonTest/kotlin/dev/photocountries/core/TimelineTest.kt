package dev.photocountries.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

class TimelineTest {
    private var nextId = 0

    /** Точка с уже известной страной: координаты ленте не нужны. */
    private fun p(time: String, country: String, id: String = "p${nextId++}") =
        LocatedPoint(PhotoPoint(Instant.parse(time + "Z"), 0.0, 0.0, PhotoSource.YandexDisk, id), country)

    @Test
    fun consecutivePhotosOfOneCountryMakeOneStay() {
        val t = buildTimeline(listOf(p("2023-01-03T10:00:00", "RS"), p("2023-01-01T10:00:00", "RS"), p("2023-01-02T10:00:00", "RS")))
        val stay = t.stays.single()
        assertEquals("RS", stay.country)
        assertEquals(3, stay.photos)
        assertEquals(3, stay.minDays)
        assertEquals(3, stay.maxDays)
        assertEquals(StayKind.Regular, stay.kind)
    }

    @Test
    fun maxDaysAddsPhotolessDaysUpToNeighbours() {
        val t = buildTimeline(
            listOf(
                p("2023-01-01T10:00:00", "RS"), p("2023-01-10T10:00:00", "RS"),
                p("2023-01-15T10:00:00", "HU"), p("2023-01-20T10:00:00", "HU"),
                p("2023-02-01T10:00:00", "RS"),
            ),
        )
        assertEquals(listOf("RS", "HU", "RS"), t.stays.map { it.country })
        // Сербия: 1–10 января, после — 4 дня без фото (11–14).
        assertEquals(10 to 14, t.stays[0].minDays to t.stays[0].maxDays)
        // Венгрия: 15–20 января, плюс 4 дня до и 11 дней после (21–31).
        assertEquals(6 to 21, t.stays[1].minDays to t.stays[1].maxDays)
        assertEquals(1 to 12, t.stays[2].minDays to t.stays[2].maxDays)
        assertTrue(t.stays.all { it.kind == StayKind.Regular })
    }

    @Test
    fun sameDayBorderCrossingDoesNotMakeRangeNegative() {
        val t = buildTimeline(listOf(p("2023-01-01T08:00:00", "RS"), p("2023-01-01T18:00:00", "HU")))
        assertEquals(listOf(1 to 1, 1 to 1), t.stays.map { it.minDays to it.maxDays })
    }

    @Test
    fun shortStayBetweenDifferentCountriesOnAdjacentDayIsTransit() {
        val t = buildTimeline(
            listOf(
                p("2023-01-10T12:00:00", "RS"),
                p("2023-01-11T08:00:00", "HU"), p("2023-01-11T10:00:00", "HU"),
                p("2023-01-15T20:00:00", "AT"),
            ),
        )
        assertEquals(listOf(StayKind.Regular, StayKind.Transit, StayKind.Regular), t.stays.map { it.kind })
        val hungary = t.totals.single { it.country == "HU" }
        assertEquals(0, hungary.visits)
        assertEquals(1, hungary.minDays)
    }

    @Test
    fun shortStayFarFromNeighboursIsNotTransit() {
        val t = buildTimeline(
            listOf(p("2023-01-01T12:00:00", "RS"), p("2023-01-10T08:00:00", "HU"), p("2023-01-20T20:00:00", "AT")),
        )
        assertEquals(StayKind.Regular, t.stays[1].kind)
        assertEquals(1, t.totals.single { it.country == "HU" }.visits)
    }

    @Test
    fun stayOfDayOrLongerIsNotTransit() {
        val t = buildTimeline(
            listOf(
                p("2023-01-10T12:00:00", "RS"),
                p("2023-01-11T08:00:00", "HU"), p("2023-01-12T08:00:00", "HU"),
                p("2023-01-12T20:00:00", "AT"),
            ),
        )
        assertEquals(StayKind.Regular, t.stays[1].kind)
    }

    @Test
    fun shortStayWithSameCountryOnBothSidesIsShortVisit() {
        val t = buildTimeline(
            listOf(p("2023-01-01T12:00:00", "RS"), p("2023-01-10T08:00:00", "HU"), p("2023-01-10T20:00:00", "RS")),
        )
        assertEquals(listOf(StayKind.Regular, StayKind.ShortVisit, StayKind.Regular), t.stays.map { it.kind })
        // Заезд — это визит, в отличие от транзита.
        assertEquals(1, t.totals.single { it.country == "HU" }.visits)
        assertEquals(2, t.totals.single { it.country == "RS" }.visits)
    }

    @Test
    fun firstAndLastStaysAreNeverTransit() {
        val t = buildTimeline(listOf(p("2023-01-10T12:00:00", "RS"), p("2023-01-11T08:00:00", "HU")))
        assertTrue(t.stays.all { it.kind == StayKind.Regular })
    }

    @Test
    fun holeIsLongestPhotolessGapAndFlaggedOverThirtyDays() {
        val long = buildTimeline(
            listOf(p("2023-01-01T10:00:00", "RS"), p("2023-01-05T10:00:00", "RS"), p("2023-03-01T10:00:00", "RS")),
        ).stays.single()
        assertEquals(55, long.holeDays)
        assertTrue(long.hasHole)
        assertEquals(60, long.minDays)

        val exact = buildTimeline(listOf(p("2023-01-01T10:00:00", "RS"), p("2023-01-31T10:00:00", "RS"))).stays.single()
        assertEquals(30, exact.holeDays)
        assertFalse(exact.hasHole)
    }

    @Test
    fun ignoredPhotosAreDroppedBeforeBuildingStays() {
        val points = listOf(
            p("2023-01-01T10:00:00", "RS"),
            p("2023-01-05T10:00:00", "RU", id = "fake"),
            p("2023-01-09T10:00:00", "RS"),
        )
        assertEquals(3, buildTimeline(points).stays.size)
        val t = buildTimeline(points, ignoredIds = setOf("fake"))
        assertEquals(listOf("RS"), t.stays.map { it.country })
        assertEquals(9, t.stays.single().minDays)
        assertEquals(listOf("RS"), t.totals.map { it.country })
    }

    @Test
    fun totalsSumMinimumDaysAndSortByThem() {
        val t = buildTimeline(
            listOf(
                p("2023-01-01T10:00:00", "RS"), p("2023-01-03T10:00:00", "RS"),
                p("2023-02-01T10:00:00", "HU"), p("2023-02-10T10:00:00", "HU"),
                p("2023-03-01T10:00:00", "RS"), p("2023-03-02T10:00:00", "RS"),
            ),
        )
        assertEquals(listOf("HU", "RS"), t.totals.map { it.country })
        val serbia = t.totals[1]
        assertEquals(2, serbia.visits)
        assertEquals(5, serbia.minDays)
        assertEquals("2023-03-02", serbia.lastVisit.toString())
    }

    @Test
    fun datesAreCountedInUtc() {
        // 23:30 и 00:30 UTC — разные дни, хотя между фото час.
        val stay = buildTimeline(listOf(p("2023-01-01T23:30:00", "RS"), p("2023-01-02T00:30:00", "RS"))).stays.single()
        assertEquals(2, stay.minDays)
    }

    @Test
    fun yearStripsPaintDaysOfStaysAndLeaveGapsEmpty() {
        val t = buildTimeline(
            listOf(
                p("2023-12-30T10:00:00", "RS"), p("2024-01-02T10:00:00", "RS"),
                p("2024-03-01T10:00:00", "HU"),
            ),
        )
        val strips = yearStrips(t.stays)
        assertEquals(listOf(2023, 2024), strips.map { it.year })
        assertEquals(listOf(365, 366), strips.map { it.days.size })
        assertEquals(listOf(null, "RS", "RS"), strips[0].days.takeLast(3))
        assertEquals(listOf("RS", "RS", null), strips[1].days.take(3))
        // 1 марта високосного года — 61-й день.
        assertEquals("HU", strips[1].days[60])
        assertEquals(3, strips[1].days.count { it != null })
        assertEquals(emptyList(), yearStrips(emptyList()))
    }

    @Test
    fun hiddenStayDropsItsPhotosAndNeighboursMerge() {
        val points = listOf(
            p("2023-01-01T10:00:00", "RS"),
            p("2023-01-05T10:00:00", "RU"), p("2023-01-05T12:00:00", "RU"),
            p("2023-01-09T10:00:00", "RS"),
            p("2023-03-01T10:00:00", "RU"),
        )
        val alien = buildTimeline(points).stays[1]
        val hidden = listOf(HiddenStay(alien.country, alien.start, alien.end))
        val t = buildTimeline(points, hiddenIds(points, hidden))
        // Вторая поездка в ту же страну остаётся: скрыт только отрезок времени.
        assertEquals(listOf("RS", "RU"), t.stays.map { it.country })
        assertEquals(9, t.stays[0].minDays)
        assertEquals(emptySet(), hiddenIds(points, emptyList()))
    }

    @Test
    fun stayOnFindsStayPaintedOnDay() {
        val t = buildTimeline(
            listOf(
                p("2023-12-30T10:00:00", "RS"), p("2024-01-02T10:00:00", "RS"),
                p("2024-03-01T10:00:00", "HU"),
            ),
        )
        assertEquals(t.stays[0], stayOn(t.stays, kotlinx.datetime.LocalDate(2024, 1, 1)))
        assertEquals(t.stays[1], stayOn(t.stays, kotlinx.datetime.LocalDate(2024, 3, 1)))
        assertEquals(null, stayOn(t.stays, kotlinx.datetime.LocalDate(2024, 2, 1)))
    }

    @Test
    fun galleryPhotoAlreadyOnDiskIsDuplicate() {
        fun photo(time: String, lat: Double, source: PhotoSource) =
            PhotoPoint(Instant.parse(time + "Z"), lat, 20.0, source, "$source $time $lat")
        val disk = listOf(photo("2023-01-01T10:00:00", 44.5, PhotoSource.YandexDisk))
        val same = photo("2023-01-01T10:00:00", 44.50001, PhotoSource.Gallery)
        val otherTime = photo("2023-01-01T10:00:01", 44.5, PhotoSource.Gallery)
        val otherPlace = photo("2023-01-01T10:00:00", 45.5, PhotoSource.Gallery)
        assertEquals(listOf(otherTime, otherPlace), withoutDuplicates(listOf(same, otherTime, otherPlace), disk))
        assertEquals(listOf(same), withoutDuplicates(listOf(same), emptyList()))
    }
}
