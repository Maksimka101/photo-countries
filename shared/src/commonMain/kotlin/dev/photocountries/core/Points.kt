package dev.photocountries.core

import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/** Откуда пришла точка. */
enum class PhotoSource { YandexDisk, Gallery }

/**
 * Всё, что нужно от фото: когда и где снято. [id] — идентификатор фото в источнике, к нему
 * привязываются ручные игноры. [preview] — ссылка на уменьшенную копию на диске (отдаётся только
 * с токеном); у фото из галереи её нет, они показываются по [id].
 */
data class PhotoPoint(
    val time: Instant,
    val lat: Double,
    val lon: Double,
    val source: PhotoSource,
    val id: String,
    val preview: String? = null,
)

/** Точка с уже определённой страной (ISO 3166 alpha-2). */
data class LocatedPoint(val point: PhotoPoint, val country: String)

/** Результат привязки точек к странам; [lost] — сколько точек не попало ни в одну страну. */
class Located(val points: List<LocatedPoint>, val lost: Int)

fun locatePoints(points: List<PhotoPoint>, countries: CountryIndex): Located {
    val located = points.mapNotNull { p -> countries.locate(p.lat, p.lon)?.let { LocatedPoint(p, it) } }
    return Located(located, points.size - located.size)
}

/** Для [withoutDuplicates]: расхождение координат одного и того же фото в двух источниках — меньше этого. */
private const val SAME_PLACE_DEG = 0.001

/**
 * Фото из [points], которых нет в [other]. Автозагрузка кладёт на диск те же снимки, что лежат в галерее,
 * и без этого каждый считался бы дважды. Одно и то же фото — та же секунда съёмки и то же место.
 */
fun withoutDuplicates(points: List<PhotoPoint>, other: List<PhotoPoint>): List<PhotoPoint> {
    if (points.isEmpty() || other.isEmpty()) return points
    val bySecond = other.groupBy { it.time.epochSeconds }
    return points.filter { p ->
        bySecond[p.time.epochSeconds].orEmpty().none {
            kotlin.math.abs(it.lat - p.lat) < SAME_PLACE_DEG && kotlin.math.abs(it.lon - p.lon) < SAME_PLACE_DEG
        }
    }
}

/** Все даты в ленте считаются по UTC, как в прототипе. */
fun Instant.utcDate(): LocalDate = toLocalDateTime(TimeZone.UTC).date
