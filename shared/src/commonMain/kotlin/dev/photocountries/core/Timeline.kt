package dev.photocountries.core

import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.daysUntil
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/** Пребывание короче этого может быть транзитом или заездом. */
private val SHORT_STAY = 24.hours

/**
 * [Transit] — меньше суток, страны до и после разные, и хотя бы с одной стороны есть фото из другой
 * страны в тот же или соседний день. [ShortVisit] («заезд») — меньше суток, страна до и после одна и та же.
 */
enum class StayKind { Regular, Transit, ShortVisit }

/**
 * Пребывание: подряд идущие фото одной страны. [minDays] — от первого фото до последнего, [maxDays]
 * добавляет все дни без фото до соседних пребываний. [holeDays] — самый длинный разрыв между соседними
 * фото внутри. [fromDate]..[toDate] — дни, отданные пребыванию: к дням с фото добавлена его доля дней
 * без фото до соседей (см. [spreadGaps]); по ним считаются [days], итоги стран и полосы лет.
 */
data class Stay(
    val country: String,
    val start: Instant,
    val end: Instant,
    val photos: Int,
    val minDays: Int,
    val maxDays: Int,
    val holeDays: Int,
    val kind: StayKind,
    val fromDate: LocalDate = start.utcDate(),
    val toDate: LocalDate = end.utcDate(),
) {
    val startDate: LocalDate get() = start.utcDate()
    val endDate: LocalDate get() = end.utcDate()
    val days: Int get() = fromDate.daysUntil(toDate) + 1

    /** В [days] вошли дни без фото — число показывается как примерное. */
    val approximate: Boolean get() = days != minDays
}

/**
 * Итог по стране: [visits] — без транзитов, [days] — сумма [Stay.days] всех пребываний, [minDays] — сумма
 * минимальных длительностей; [approximate] — в [days] вошли дни без фото.
 */
data class CountryTotal(
    val country: String,
    val visits: Int,
    val days: Int,
    val minDays: Int,
    val lastVisit: LocalDate,
) {
    val approximate: Boolean get() = days != minDays
}

/** Лента пребываний по времени и итоги по странам (по убыванию [CountryTotal.days]). */
data class Timeline(val stays: List<Stay>, val totals: List<CountryTotal>) {
    companion object {
        val EMPTY = Timeline(emptyList(), emptyList())
    }
}

private class Run(val country: String, val start: Instant) {
    var end = start
    var photos = 1
    var hole = 0
}

/**
 * Строит ленту. «Дома» нет, авто-правил для плохих данных тоже: вместо них [ignoredIds] — фото,
 * исключённые вручную; они выбрасываются до сборки пребываний.
 */
fun buildTimeline(points: List<LocatedPoint>, ignoredIds: Set<String> = emptySet()): Timeline {
    val sorted = points
        .filter { it.point.id !in ignoredIds }
        .sortedWith(compareBy<LocatedPoint> { it.point.time }.thenBy { it.country })

    val runs = ArrayList<Run>()
    for (p in sorted) {
        val t = p.point.time
        val last = runs.lastOrNull()
        if (last != null && last.country == p.country) {
            last.hole = maxOf(last.hole, last.end.utcDate().daysUntil(t.utcDate()))
            last.end = t
            last.photos++
        } else {
            runs += Run(p.country, t)
        }
    }

    val stays = spreadGaps(runs.mapIndexed { i, r ->
        val prev = runs.getOrNull(i - 1)
        val next = runs.getOrNull(i + 1)
        val startDate = r.start.utcDate()
        val endDate = r.end.utcDate()
        val gapBefore = prev?.let { it.end.utcDate().daysUntil(startDate) }
        val gapAfter = next?.let { endDate.daysUntil(it.start.utcDate()) }

        val lo = startDate.daysUntil(endDate) + 1
        // Верхняя граница: от дня после последнего фото до него до дня перед первым фото после.
        var hi = lo
        if (gapBefore != null) hi += gapBefore - 1
        if (gapAfter != null) hi += gapAfter - 1
        hi = maxOf(hi, lo)

        val short = prev != null && next != null && r.end - r.start < SHORT_STAY
        val close = short && minOf(gapBefore!!, gapAfter!!) <= 1
        val kind = when {
            close && prev.country != next.country -> StayKind.Transit
            short && prev.country == next.country -> StayKind.ShortVisit
            else -> StayKind.Regular
        }
        Stay(r.country, r.start, r.end, r.photos, lo, hi, r.hole, kind)
    })

    val totals = stays.groupBy { it.country }.map { (country, list) ->
        CountryTotal(
            country = country,
            visits = list.count { it.kind != StayKind.Transit },
            days = list.sumOf { it.days },
            minDays = list.sumOf { it.minDays },
            lastVisit = list.last().endDate,
        )
    }.sortedByDescending { it.days }

    return Timeline(stays, totals)
}

/**
 * Раздаёт дни без фото между соседними пребываниями: пополам, нечётный день — более раннему.
 * Транзит таких дней не берёт, они целиком уходят соседу — если тот сам не транзит.
 */
private fun spreadGaps(stays: List<Stay>): List<Stay> = stays.mapIndexed { i, s ->
    val before = stays.getOrNull(i - 1)?.let { gapShares(it, s).second } ?: 0
    val after = stays.getOrNull(i + 1)?.let { gapShares(s, it).first } ?: 0
    s.copy(fromDate = s.startDate.minus(DatePeriod(days = before)), toDate = s.endDate.plus(DatePeriod(days = after)))
}

/** Сколько дней без фото между [a] и следующим за ним [b] достаётся каждому из них. */
private fun gapShares(a: Stay, b: Stay): Pair<Int, Int> {
    val free = (a.endDate.daysUntil(b.startDate) - 1).coerceAtLeast(0)
    val aTransit = a.kind == StayKind.Transit
    val first = when {
        aTransit == (b.kind == StayKind.Transit) -> (free + 1) / 2
        aTransit -> 0
        else -> free
    }
    return first to free - first
}

/**
 * Пребывание, скрытое вручную (например, чужие фото): фото страны [country] со временем от [start]
 * до [end] включительно в ленту не идут. Границы — с точностью до секунды, как в кэше точек.
 */
data class HiddenStay(val country: String, val start: Instant, val end: Instant) {
    val startDate: LocalDate get() = start.utcDate()
    val endDate: LocalDate get() = end.utcDate()

    fun covers(p: LocatedPoint): Boolean =
        p.country == country && p.point.time.epochSeconds in start.epochSeconds..end.epochSeconds
}

/** Id фото, попавших под [hidden], — для `ignoredIds` в [buildTimeline]. */
fun hiddenIds(points: List<LocatedPoint>, hidden: List<HiddenStay>): Set<String> {
    if (hidden.isEmpty()) return emptySet()
    return points.filter { p -> hidden.any { it.covers(p) } }.mapTo(HashSet()) { it.point.id }
}

/**
 * Фото пребывания [stay] для показа, свежие первыми — как пребывания в ленте. Попавшие под [hidden] не входят: после скрытия
 * соседние пребывания той же страны сливаются, и скрытые фото оказались бы внутри.
 */
fun stayPhotos(points: List<LocatedPoint>, stay: Stay, hidden: List<HiddenStay> = emptyList()): List<PhotoPoint> =
    points
        .filter { p -> p.country == stay.country && p.point.time in stay.start..stay.end && hidden.none { it.covers(p) } }
        .map { it.point }
        .sortedByDescending { it.time }

/**
 * Пребывание, которым в [yearStrips] закрашен день [date]: обычное важнее транзита, из нескольких —
 * более позднее. null — день раньше первого пребывания или позже последнего.
 */
fun stayOn(stays: List<Stay>, date: LocalDate): Stay? {
    val covering = stays.filter { date >= it.fromDate && date <= it.toDate }
    return covering.lastOrNull { it.kind != StayKind.Transit } ?: covering.lastOrNull()
}

/**
 * Факты для экрана стран, все без транзитов. [newCountries] — страны, первый визит в которые пришёлся
 * на [lastYear], последний год ленты. [recordYear] — год с наибольшим числом стран ([recordCountries]),
 * из равных — более поздний. [longest] — самое долгое пребывание по [Stay.days].
 */
data class TimelineFacts(
    val lastYear: Int,
    val newCountries: List<String>,
    val recordYear: Int,
    val recordCountries: Int,
    val longest: Stay,
)

/** null — пребываний нет. */
fun timelineFacts(stays: List<Stay>): TimelineFacts? {
    val visits = stays.filter { it.kind != StayKind.Transit }
    if (visits.isEmpty()) return null
    val lastYear = visits.maxOf { it.endDate.year }
    val firstYear = LinkedHashMap<String, Int>()
    val byYear = HashMap<Int, MutableSet<String>>()
    for (s in visits) {
        firstYear.getOrPut(s.country) { s.startDate.year }
        for (year in s.startDate.year..s.endDate.year) byYear.getOrPut(year) { HashSet() } += s.country
    }
    val record = byYear.entries.maxWith(compareBy({ it.value.size }, { it.key }))
    return TimelineFacts(
        lastYear = lastYear,
        newCountries = firstYear.filterValues { it == lastYear }.keys.toList(),
        recordYear = record.key,
        recordCountries = record.value.size,
        longest = visits.maxBy { it.days },
    )
}

/** Год как полоса дней: [days] — код страны на каждый день года или null до первого пребывания и после последнего. */
class YearStrip(val year: Int, val days: List<String?>)

/**
 * Раскладывает пребывания по дням года — от [Stay.fromDate] до [Stay.toDate], так что между пребываниями
 * пустых дней нет. Транзит занимает день, только если тот больше ничем не занят; годы идут по возрастанию
 * без пропусков.
 */
fun yearStrips(stays: List<Stay>): List<YearStrip> {
    if (stays.isEmpty()) return emptyList()
    val byDay = HashMap<LocalDate, String>()
    val ordered = stays.filter { it.kind == StayKind.Transit } + stays.filter { it.kind != StayKind.Transit }
    for (s in ordered) {
        var d = s.fromDate
        val end = s.toDate
        while (d <= end) {
            byDay[d] = s.country
            d = d.plus(DatePeriod(days = 1))
        }
    }
    val first = stays.minOf { it.startDate.year }
    val last = stays.maxOf { it.endDate.year }
    return (first..last).map { year ->
        val start = LocalDate(year, 1, 1)
        val length = start.daysUntil(LocalDate(year + 1, 1, 1))
        YearStrip(year, List(length) { byDay[start.plus(DatePeriod(days = it))] })
    }
}
