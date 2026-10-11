package dev.photocountries.yandex

import dev.photocountries.core.JsonReader
import dev.photocountries.core.PhotoPoint
import dev.photocountries.core.PhotoSource
import kotlin.time.Instant

private const val API = "https://cloud-api.yandex.net/v1/disk/resources/files"
private const val LAST_UPLOADED = "https://cloud-api.yandex.net/v1/disk/resources/last-uploaded"
private const val PAGE = 500

/** Из ответа нужны только путь, id и три поля EXIF — остальное (превью, размеры, хеши) диск не присылает. */
private const val FIELDS =
    "items.path,items.resource_id,items.exif.gps_latitude,items.exif.gps_longitude,items.exif.date_time"

/** Предохранитель от бесконечного листания, если диск вдруг перестанет отдавать пустую страницу. */
private const val MAX_PAGES = 2000

/**
 * Сколько последних загрузок просить у `last-uploaded`, по нарастающей. Листать этот метод нельзя
 * (`offset` он не слушает), а `limit` считает файлы всех типов — картинок приходит меньше.
 */
private val NEW_LIMITS = listOf(100, 1000, 10000)

/** Свои папки автозагрузки: в остальных чужое или без геометок. Заготовка под настройку. */
val DEFAULT_FOLDERS = listOf("disk:/Фотокамера/", "disk:/Фотки/")

/** Страница списка: [itemCount] — сколько файлов пришло всего, [points] — годные точки из своих папок. */
class DiskPage(val itemCount: Int, val points: List<PhotoPoint>)

/**
 * Разбирает страницу `GET /v1/disk/resources/files`. Точка годная, если файл лежит в одной из [folders]
 * и в `exif` есть обе координаты (не 0,0) и время; `date_time` диск отдаёт уже в UTC.
 *
 * @throws IllegalArgumentException битый JSON или нет `items`.
 */
fun parseDiskPage(json: String, folders: List<String> = DEFAULT_FOLDERS): DiskPage {
    val r = JsonReader(json)
    var count = -1
    val points = ArrayList<PhotoPoint>()
    r.beginObject()
    while (true) {
        val name = r.nextName() ?: break
        if (name != "items") { r.skipValue(); continue }
        count = 0
        r.beginArray()
        while (r.nextElement()) {
            count++
            readItem(r, folders)?.let(points::add)
        }
    }
    require(count >= 0) { "В ответе диска нет поля items" }
    return DiskPage(count, points)
}

private fun readItem(r: JsonReader, folders: List<String>): PhotoPoint? {
    var path: String? = null
    var id: String? = null
    var lat: Double? = null
    var lon: Double? = null
    var time: String? = null
    r.beginObject()
    while (true) {
        when (r.nextName() ?: break) {
            "path" -> path = r.readStringOrNull()
            "resource_id" -> id = r.readStringOrNull()
            "exif" -> if (r.beginObjectOrSkip()) {
                while (true) {
                    when (r.nextName() ?: break) {
                        "gps_latitude" -> lat = r.readDoubleOrNull()
                        "gps_longitude" -> lon = r.readDoubleOrNull()
                        "date_time" -> time = r.readStringOrNull()
                        else -> r.skipValue()
                    }
                }
            }
            else -> r.skipValue()
        }
    }
    if (path == null || folders.none { path.startsWith(it) }) return null
    if (lat == null || lon == null || time.isNullOrEmpty() || (lat == 0.0 && lon == 0.0)) return null
    val instant = Instant.parseOrNull(time) ?: return null
    return PhotoPoint(instant, lat, lon, PhotoSource.YandexDisk, id ?: path)
}

/**
 * Источник точек — Я.Диск. Сами фото не качаются: координаты и время приходят в метаданных списка.
 * [get] — HTTP GET с OAuth-токеном (в приложении — [diskGet]).
 */
class YandexDisk(
    private val get: suspend (url: String, token: String) -> String,
    private val folders: List<String> = DEFAULT_FOLDERS,
) {
    /**
     * Все годные точки. [onPage] вызывается после каждой страницы: сколько файлов уже просмотрено
     * и годные точки этой страницы.
     */
    suspend fun fetchPoints(
        token: String,
        onPage: suspend (seen: Int, points: List<PhotoPoint>) -> Unit = { _, _ -> },
    ): List<PhotoPoint> {
        val points = ArrayList<PhotoPoint>()
        var seen = 0
        for (page in 0 until MAX_PAGES) {
            val url = "$API?media_type=image&limit=$PAGE&offset=${page * PAGE}&fields=$FIELDS"
            val parsed = parseDiskPage(get(url, token), folders)
            // Страница бывает короче limit и до конца списка (offset при этом всё равно идёт шагом limit) —
            // листаем до пустой.
            if (parsed.itemCount == 0) break
            points += parsed.points
            seen += parsed.itemCount
            onPage(seen, parsed.points)
        }
        return points
    }

    /**
     * Годные точки, загруженные на диск позже тех, что уже есть в кэше ([known] — их id). Берёт последние
     * загрузки (новые идут первыми), расширяя выборку, пока в ней не встретится знакомое фото.
     * null — до знакомых не дошли (другой диск или слишком давний кэш): нужен [fetchPoints].
     */
    suspend fun fetchNew(token: String, known: Set<String>): List<PhotoPoint>? {
        var prevCount = -1
        for (limit in NEW_LIMITS) {
            val url = "$LAST_UPLOADED?media_type=image&limit=$limit&fields=${FIELDS.replace("items.", "")}"
            val parsed = parseDiskPage(get(url, token), folders)
            val fresh = parsed.points.filter { it.id !in known }
            if (fresh.size < parsed.points.size) return fresh
            // Выборка не выросла — это уже весь диск.
            if (parsed.itemCount == prevCount) break
            prevCount = parsed.itemCount
        }
        return null
    }
}
