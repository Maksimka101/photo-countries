package dev.photocountries.data

import dev.photocountries.core.HiddenStay
import dev.photocountries.core.PhotoPoint
import dev.photocountries.core.PhotoSource
import kotlin.time.Instant

/** Последняя выгрузка точек: с ней приложение открывается без сети. */
class CachedPoints(val fetchedAt: Instant, val points: List<PhotoPoint>)

/** Что на экране запоминается между запусками: открытая вкладка и сортировка списка стран. */
data class UiPrefs(val tab: Int = 0, val sortByRecent: Boolean = false)

/** Приватное хранилище приложения: токен, кэш точек, скрытые пребывания, состояние галереи и экрана. Реализация на файлах — `FileStorage`. */
interface AppStorage {
    suspend fun loadToken(): String?

    /** null — забыть токен. */
    suspend fun saveToken(token: String?)

    /** null — кэша нет или он не читается. */
    suspend fun loadPoints(): CachedPoints?
    suspend fun savePoints(points: CachedPoints)

    /** Пустой список — скрытого нет или файл не читается. */
    suspend fun loadHidden(): List<HiddenStay>
    suspend fun saveHidden(hidden: List<HiddenStay>)

    /** null — галерея не подключена; иначе id последнего просмотренного в ней фото (0 — ещё не смотрели). */
    suspend fun loadGallery(): Long?
    suspend fun saveGallery(lastId: Long?)

    /** Значения по умолчанию — ничего не сохранено или файл не читается. */
    suspend fun loadUi(): UiPrefs
    suspend fun saveUi(prefs: UiPrefs)
}

private const val HEADER = "photo-countries points v1"

/**
 * Текст кэша: заголовок, время выгрузки (секунды эпохи), дальше по строке на точку —
 * `секунды<TAB>широта<TAB>долгота<TAB>источник<TAB>id`.
 */
fun encodePoints(cached: CachedPoints): String = buildString {
    append(HEADER).append('\n')
    append(cached.fetchedAt.epochSeconds).append('\n')
    for (p in cached.points) {
        append(p.time.epochSeconds).append('\t')
        append(p.lat).append('\t')
        append(p.lon).append('\t')
        append(p.source.name).append('\t')
        append(p.id.replace('\t', ' ').replace('\n', ' ')).append('\n')
    }
}

/** Обратное к [encodePoints]. Любая битая строка — null: кэш проще перекачать, чем чинить. */
fun decodePoints(lines: List<String>): CachedPoints? {
    if (lines.size < 2 || lines[0] != HEADER) return null
    val fetchedAt = lines[1].toLongOrNull() ?: return null
    val points = ArrayList<PhotoPoint>(lines.size)
    for (i in 2 until lines.size) {
        val line = lines[i]
        if (line.isEmpty()) continue
        val parts = line.split('\t', limit = 5)
        if (parts.size != 5) return null
        val source = PhotoSource.entries.firstOrNull { it.name == parts[3] } ?: return null
        points += PhotoPoint(
            time = Instant.fromEpochSeconds(parts[0].toLongOrNull() ?: return null),
            lat = parts[1].toDoubleOrNull() ?: return null,
            lon = parts[2].toDoubleOrNull() ?: return null,
            source = source,
            id = parts[4],
        )
    }
    return CachedPoints(Instant.fromEpochSeconds(fetchedAt), points)
}

private const val HIDDEN_HEADER = "photo-countries hidden v1"

/** Текст списка скрытого: заголовок, дальше по строке на пребывание — `страна<TAB>начало<TAB>конец` (секунды эпохи). */
fun encodeHidden(hidden: List<HiddenStay>): String = buildString {
    append(HIDDEN_HEADER).append('\n')
    for (h in hidden) {
        append(h.country).append('\t')
        append(h.start.epochSeconds).append('\t')
        append(h.end.epochSeconds).append('\n')
    }
}

/** Обратное к [encodeHidden]. Битая строка пропускается: остальные скрытия терять незачем. */
fun decodeHidden(lines: List<String>): List<HiddenStay> {
    if (lines.firstOrNull() != HIDDEN_HEADER) return emptyList()
    return lines.drop(1).mapNotNull { line ->
        val parts = line.split('\t')
        if (parts.size != 3 || parts[0].isEmpty()) return@mapNotNull null
        val start = parts[1].toLongOrNull() ?: return@mapNotNull null
        val end = parts[2].toLongOrNull() ?: return@mapNotNull null
        HiddenStay(parts[0], Instant.fromEpochSeconds(start), Instant.fromEpochSeconds(end))
    }
}

/** Текст настроек экрана: номер вкладки и `recent` либо `days` через пробел. */
fun encodeUi(prefs: UiPrefs): String = "${prefs.tab} ${if (prefs.sortByRecent) "recent" else "days"}"

/** Обратное к [encodeUi]; непонятный текст — значения по умолчанию. */
fun decodeUi(text: String): UiPrefs {
    val parts = text.trim().split(' ')
    val tab = parts.getOrNull(0)?.toIntOrNull() ?: return UiPrefs()
    return UiPrefs(tab, parts.getOrNull(1) == "recent")
}
