package dev.photocountries.data

import dev.photocountries.core.HiddenStay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Хранилище на файлах в приватном каталоге приложения: `token.txt`, `points.tsv`, `hidden.tsv`, `gallery.txt` и `ui.txt`
 * (формат — в `Storage.kt`). Запись атомарная: во временный файл рядом и переименование.
 * Битый или отсутствующий файл читается как null.
 */
class FileStorage(private val dir: File) : AppStorage {
    private val tokenFile = File(dir, "token.txt")
    private val pointsFile = File(dir, "points.tsv")
    private val hiddenFile = File(dir, "hidden.tsv")
    private val galleryFile = File(dir, "gallery.txt")
    private val uiFile = File(dir, "ui.txt")

    override suspend fun loadToken(): String? = withContext(Dispatchers.IO) {
        try {
            if (tokenFile.isFile) tokenFile.readText(StandardCharsets.UTF_8).trim().ifEmpty { null } else null
        } catch (_: Exception) {
            null
        }
    }

    override suspend fun saveToken(token: String?) {
        withContext(Dispatchers.IO) {
            if (token == null) tokenFile.delete() else write(tokenFile, token)
        }
    }

    override suspend fun loadPoints(): CachedPoints? = withContext(Dispatchers.IO) {
        try {
            if (pointsFile.isFile) decodePoints(pointsFile.readLines(StandardCharsets.UTF_8)) else null
        } catch (_: Exception) {
            null
        }
    }

    override suspend fun savePoints(points: CachedPoints) {
        withContext(Dispatchers.IO) { write(pointsFile, encodePoints(points)) }
    }

    override suspend fun loadHidden(): List<HiddenStay> = withContext(Dispatchers.IO) {
        try {
            if (hiddenFile.isFile) decodeHidden(hiddenFile.readLines(StandardCharsets.UTF_8)) else emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    override suspend fun saveHidden(hidden: List<HiddenStay>) {
        withContext(Dispatchers.IO) { write(hiddenFile, encodeHidden(hidden)) }
    }

    override suspend fun loadGallery(): Long? = withContext(Dispatchers.IO) {
        try {
            if (galleryFile.isFile) galleryFile.readText(StandardCharsets.UTF_8).trim().toLongOrNull() else null
        } catch (_: Exception) {
            null
        }
    }

    override suspend fun saveGallery(lastId: Long?) {
        withContext(Dispatchers.IO) {
            if (lastId == null) galleryFile.delete() else write(galleryFile, lastId.toString())
        }
    }

    override suspend fun loadUi(): UiPrefs = withContext(Dispatchers.IO) {
        try {
            if (uiFile.isFile) decodeUi(uiFile.readText(StandardCharsets.UTF_8)) else UiPrefs()
        } catch (_: Exception) {
            UiPrefs()
        }
    }

    override suspend fun saveUi(prefs: UiPrefs) {
        withContext(Dispatchers.IO) { write(uiFile, encodeUi(prefs)) }
    }

    private fun write(file: File, text: String) {
        dir.mkdirs()
        val tmp = File(dir, file.name + ".tmp")
        try {
            tmp.writeText(text, StandardCharsets.UTF_8)
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            tmp.delete()
        }
    }
}
