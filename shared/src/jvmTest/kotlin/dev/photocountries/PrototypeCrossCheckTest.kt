package dev.photocountries

import dev.photocountries.core.CountryIndex
import dev.photocountries.core.JsonReader
import dev.photocountries.core.StayKind
import dev.photocountries.core.buildTimeline
import dev.photocountries.core.locatePoints
import dev.photocountries.core.utcDate
import dev.photocountries.yandex.parseDiskPage
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Локальная сверка с прототипом: лента из Kotlin должна дословно совпасть с выводом `python3 prototype/trips.py`
 * на настоящей выгрузке `prototype/meta.json`. Файл личный и в репозиторий не входит — без него
 * (или без python3) тест молча пропускается.
 */
class PrototypeCrossCheckTest {
    private val prototype = File("../prototype").absoluteFile.normalize()

    @Test
    fun timelineMatchesPrototype() {
        val meta = File(prototype, "meta.json")
        val geojson = File(prototype, "countries.geojson")
        if (!meta.isFile || !geojson.isFile) return skip("нет meta.json или countries.geojson")
        val expected = runPrototype() ?: return skip("python3 trips.py не запустился")

        val names = russianNames(geojson)
        val index = CountryIndex.parse(File("src/commonMain/composeResources/files/countries.bin").readBytes())
        val located = locatePoints(parseDiskPage("{\"items\":" + meta.readText() + "}").points, index)

        // Прототип игнорирует по дате и стране; здесь то же самое выражается набором id фото.
        val ignores = File(prototype, "ignore.txt").takeIf { it.isFile }?.readLines().orEmpty()
            .map { it.substringBefore('#').trim() }.filter { it.isNotEmpty() }
            .map { line ->
                val (span, country) = line.split(Regex("\\s+"), limit = 2)
                Triple(span.substringBefore(".."), span.substringAfter(".."), country)
            }
        val ignoredIds = located.points.filter { p ->
            val date = p.point.time.utcDate().toString()
            ignores.any { (a, b, country) -> date in a..b && names[p.country] == country }
        }.map { it.point.id }.toSet()

        val timeline = buildTimeline(located.points, ignoredIds)
        val actual = buildString {
            append("точек: ${located.points.size - ignoredIds.size}, вне стран: ${located.lost}, в игноре: ${ignoredIds.size}\n\n")
            var year: Int? = null
            for (s in timeline.stays) {
                val note = when {
                    s.kind == StayKind.Transit -> " · транзит?"
                    s.kind == StayKind.ShortVisit -> " · заезд?"
                    s.hasHole -> " · дыра ${s.holeDays} дн."
                    else -> ""
                }
                val d = s.startDate
                if (d.year != year) {
                    year = d.year
                    append("$year\n")
                }
                val span = if (s.minDays == s.maxDays) "${s.minDays}" else "${s.minDays}–${s.maxDays}"
                append("  %02d-%02d %s: %s дн., %d фото%s\n".format(d.month.ordinal + 1, d.day, names[s.country], span, s.photos, note))
            }
            append("\nстраны (визитов / дней минимум):\n")
            for (t in timeline.totals) append("  ${names[t.country]}: ${t.visits} / ${t.minDays}\n")
        }
        assertEquals(expected, actual)
        println("сверка с прототипом: совпало, пребываний ${timeline.stays.size}")
    }

    private fun skip(why: String) = println("сверка с прототипом пропущена: $why")

    private fun runPrototype(): String? = try {
        val out = File.createTempFile("trips", ".txt")
        try {
            val process = ProcessBuilder("python3", "trips.py").directory(prototype)
                .redirectErrorStream(true).redirectOutput(out).start()
            if (process.waitFor(5, TimeUnit.MINUTES) && process.exitValue() == 0) out.readText() else null
        } finally {
            out.delete()
        }
    } catch (_: IOException) {
        null
    }

    /** Код страны → NAME_RU (или NAME), как их выбирает trips.py; код — по правилу pack_countries.py. */
    private fun russianNames(geojson: File): Map<String, String> {
        val names = HashMap<String, String>()
        val r = JsonReader(geojson.readText())
        r.beginObject()
        while (true) {
            if ((r.nextName() ?: break) != "features") { r.skipValue(); continue }
            r.beginArray()
            while (r.nextElement()) {
                r.beginObject()
                while (true) {
                    if ((r.nextName() ?: break) != "properties") { r.skipValue(); continue }
                    val props = HashMap<String, String>()
                    r.beginObject()
                    while (true) {
                        val key = r.nextName() ?: break
                        if (key in setOf("NAME", "NAME_RU", "ISO_A2", "ISO_A2_EH")) r.readStringOrNull()?.let { props[key] = it } else r.skipValue()
                    }
                    val code = listOfNotNull(props["ISO_A2"], props["ISO_A2_EH"]).firstOrNull { c -> c.length == 2 && c.all { it.isLetter() } }
                    val name = props["NAME_RU"]?.ifEmpty { null } ?: props["NAME"]
                    if (code != null && name != null) names.putIfAbsent(code, name)
                }
            }
        }
        return names
    }
}
