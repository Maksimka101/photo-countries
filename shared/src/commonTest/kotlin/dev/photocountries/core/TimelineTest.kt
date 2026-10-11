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
    fun holeIsLongestPhotolessGapAndStaysInsideStay() {
        val long = buildTimeline(
            listOf(p("2023-01-01T10:00:00", "RS"), p("2023-01-05T10:00:00", "RS"), p("2023-03-01T10:00:00", "RS")),
        ).stays.single()
        assertEquals(55, long.holeDays)
        assertEquals(60, long.minDays)
        assertEquals(60, long.days)
        assertFalse(long.approximate)
    }

    @Test
    fun photolessDaysBetweenStaysAreSplitInHalf() {
        val t = buildTimeline(
            listOf(
                p("2023-01-01T10:00:00", "RS"), p("2023-01-10T10:00:00", "RS"),
                p("2023-01-16T10:00:00", "HU"), p("2023-01-20T10:00:00", "HU"),
                p("2023-01-21T10:00:00", "AT"),
            ),
        )
        // Между Сербией и Венгрией пять дней (11–15): три — Сербии, два — Венгрии.
        assertEquals(listOf("2023-01-01", "2023-01-14", "2023-01-21"), t.stays.map { it.fromDate.toString() })
        assertEquals(listOf("2023-01-13", "2023-01-20", "2023-01-21"), t.stays.map { it.toDate.toString() })
        assertEquals(listOf(13, 7, 1), t.stays.map { it.days })
        assertEquals(listOf(true, true, false), t.stays.map { it.approximate })
        // Даты фото при этом прежние.
        assertEquals("2023-01-10" to "2023-01-16", t.stays[0].endDate.toString() to t.stays[1].startDate.toString())
    }

    @Test
    fun transitTakesNoPhotolessDays() {
        val t = buildTimeline(
            listOf(
                p("2023-01-10T12:00:00", "RS"),
                p("2023-01-11T08:00:00", "HU"),
                p("2023-01-15T20:00:00", "AT"),
            ),
        )
        assertEquals(StayKind.Transit, t.stays[1].kind)
        assertEquals(1, t.stays[1].days)
        // Дни 12–14 января целиком уходят Австрии.
        assertEquals("2023-01-12" to 4, t.stays[2].fromDate.toString() to t.stays[2].days)
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
        // 28 дней января без фото и 18 февраля делятся с Венгрией пополам.
        assertEquals(28 to 33, serbia.days to t.totals[0].days)
        assertTrue(serbia.approximate)
        assertEquals("2023-03-02", serbia.lastVisit.toString())
    }

    @Test
    fun factsCountNewCountriesRecordYearAndLongestStayWithoutTransits() {
        val t = buildTimeline(
            listOf(
                p("2023-01-01T10:00:00", "RS"), p("2023-01-20T10:00:00", "RS"),
                p("2023-03-01T10:00:00", "HU"), p("2023-03-05T10:00:00", "HU"),
                p("2023-06-01T10:00:00", "AT"),
                p("2024-02-01T10:00:00", "RS"), p("2024-02-03T10:00:00", "RS"),
                // Транзит через Италию: ни новой страной, ни страной года не считается.
                p("2024-02-04T08:00:00", "IT"),
                p("2024-02-04T20:00:00", "GE"), p("2024-02-10T10:00:00", "GE"),
            ),
        )
        assertEquals(StayKind.Transit, t.stays.single { it.country == "IT" }.kind)
        val facts = timelineFacts(t.stays)!!
        assertEquals(2024, facts.lastYear)
        assertEquals(listOf("GE"), facts.newCountries)
        assertEquals(2023 to 3, facts.recordYear to facts.recordCountries)
        // Единственное фото из Австрии, но с половиной дней без фото до Венгрии (43) и до Сербии (122).
        assertEquals("AT" to 166, facts.longest.country to facts.longest.days)
        assertEquals(null, timelineFacts(emptyList()))
    }

    @Test
    fun factsPreferLaterYearAmongEqualRecords() {
        val t = buildTimeline(
            listOf(p("2023-01-01T10:00:00", "RS"), p("2023-03-01T10:00:00", "HU"), p("2024-01-05T10:00:00", "RS"), p("2024-03-01T10:00:00", "HU")),
        )
        val facts = timelineFacts(t.stays)!!
        assertEquals(2024 to 2, facts.recordYear to facts.recordCountries)
        assertEquals(emptyList(), facts.newCountries)
    }

    @Test
    fun datesAreCountedInUtc() {
        // 23:30 и 00:30 UTC — разные дни, хотя между фото час.
        val stay = buildTimeline(listOf(p("2023-01-01T23:30:00", "RS"), p("2023-01-02T00:30:00", "RS"))).stays.single()
        assertEquals(2, stay.minDays)
    }

    @Test
    fun yearStripsPaintDaysOfStaysAndSplitGapsBetweenThem() {
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
        // 58 дней без фото (3 января – 29 февраля) делятся пополам: январь — Сербии, февраль — Венгрии.
        assertEquals(List(31) { "RS" } + List(30) { "HU" }, strips[1].days.take(61))
        // После последнего пребывания дни пустые.
        assertEquals(61, strips[1].days.count { it != null })
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
        assertEquals(t.stays[0], stayOn(t.stays, kotlinx.datetime.LocalDate(2024, 1, 31)))
        assertEquals(t.stays[1], stayOn(t.stays, kotlinx.datetime.LocalDate(2024, 2, 1)))
        assertEquals(null, stayOn(t.stays, kotlinx.datetime.LocalDate(2024, 3, 2)))
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

    @Test
    fun stayPhotosAreItsOwnNewestFirstWithoutHidden() {
        val points = listOf(
            p("2024-01-01T10:00:00", "RS", "a"),
            p("2024-01-03T10:00:00", "RS", "c"),
            p("2024-01-02T10:00:00", "RS", "b"),
            p("2024-02-01T10:00:00", "ME", "d"),
            p("2024-03-01T10:00:00", "RS", "e"),
        )
        val first = buildTimeline(points).stays.first()
        assertEquals(listOf("c", "b", "a"), stayPhotos(points, first).map { it.id })
        val hidden = HiddenStay("RS", points[2].point.time, points[2].point.time)
        assertEquals(listOf("c", "a"), stayPhotos(points, first, listOf(hidden)).map { it.id })
    }
}
