package dev.photocountries.core

import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.daysUntil
import kotlinx.datetime.plus
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/** Дыра без фото внутри пребывания, о которой стоит предупредить, — больше стольких дней. */
const val HOLE_DAYS = 30

/** Пребывание короче этого может быть транзитом или заездом. */
private val SHORT_STAY = 24.hours

/**
 * [Transit] — меньше суток, страны до и после разные, и хотя бы с одной стороны есть фото из другой
 * страны в тот же или соседний день. [ShortVisit] («заезд») — меньше суток, страна до и после одна и та же.
 */
enum class StayKind { Regular, Transit, ShortVisit }

/**
 * Пребывание: подряд идущие фото одной страны. Длительность — диапазон: [minDays] от первого фото
 * до последнего, [maxDays] добавляет дни без фото до соседних пребываний. [holeDays] — самый длинный
 * разрыв между соседними фото внутри.
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
) {
    val startDate: LocalDate get() = start.utcDate()
    val endDate: LocalDate get() = end.utcDate()
    val hasHole: Boolean get() = holeDays > HOLE_DAYS
}

/** Итог по стране: [visits] — без транзитов, [minDays] — сумма минимальных длительностей всех пребываний. */
data class CountryTotal(
    val country: String,
    val visits: Int,
    val minDays: Int,
    val lastVisit: LocalDate,
)

/** Лента пребываний по времени и итоги по странам (по убыванию дней). */
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

    val stays = runs.mapIndexed { i, r ->
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
    }

    val totals = stays.groupBy { it.country }.map { (country, list) ->
        CountryTotal(
            country = country,
            visits = list.count { it.kind != StayKind.Transit },
            minDays = list.sumOf { it.minDays },
            lastVisit = list.last().endDate,
        )
    }.sortedByDescending { it.minDays }

    return Timeline(stays, totals)
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
 * Пребывание, которым в [yearStrips] закрашен день [date]: обычное важнее транзита, из нескольких —
 * более позднее. null — день пустой.
 */
fun stayOn(stays: List<Stay>, date: LocalDate): Stay? {
    val covering = stays.filter { date >= it.startDate && date <= it.endDate }
    return covering.lastOrNull { it.kind != StayKind.Transit } ?: covering.lastOrNull()
}

/** Год как полоса дней: [days] — код страны на каждый день года или null, если фото в этот день не покрыты. */
class YearStrip(val year: Int, val days: List<String?>)

/**
 * Раскладывает пребывания по дням года (минимальные границы — от первого фото до последнего).
 * Транзит занимает день, только если тот больше ничем не занят; годы идут по возрастанию без пропусков.
 */
fun yearStrips(stays: List<Stay>): List<YearStrip> {
    if (stays.isEmpty()) return emptyList()
    val byDay = HashMap<LocalDate, String>()
    val ordered = stays.filter { it.kind == StayKind.Transit } + stays.filter { it.kind != StayKind.Transit }
    for (s in ordered) {
        var d = s.startDate
        val end = s.endDate
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
