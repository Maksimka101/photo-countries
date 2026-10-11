package dev.photocountries.gallery

import android.Manifest
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import dev.photocountries.core.PhotoPoint
import dev.photocountries.core.PhotoSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.time.Instant

/** Сколько фото читать между отчётами о ходе. */
private const val BATCH = 100

/** Свои снимки лежат в папке камеры; в остальных (мессенджеры, загрузки) — чужое или без геометок. */
private const val CAMERA_DIR = "DCIM/"

/** Что спрашивать у пользователя: сами фото и отдельно их геоданные (без второго координаты из EXIF вырезаются). */
val GALLERY_PERMISSIONS: Array<String> = when {
    Build.VERSION.SDK_INT >= 34 -> arrayOf(
        Manifest.permission.READ_MEDIA_IMAGES,
        Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
        Manifest.permission.ACCESS_MEDIA_LOCATION,
    )
    Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.ACCESS_MEDIA_LOCATION)
    Build.VERSION.SDK_INT >= 29 -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.ACCESS_MEDIA_LOCATION)
    else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
}

/** Доступ «только к выбранным фото» (Android 14+) тоже годится: читаем то, что разрешили. */
fun hasGalleryAccess(context: Context): Boolean {
    fun granted(permission: String) = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    val photos = when {
        Build.VERSION.SDK_INT >= 34 ->
            granted(Manifest.permission.READ_MEDIA_IMAGES) || granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        Build.VERSION.SDK_INT >= 33 -> granted(Manifest.permission.READ_MEDIA_IMAGES)
        else -> granted(Manifest.permission.READ_EXTERNAL_STORAGE)
    }
    return photos && (Build.VERSION.SDK_INT < 29 || granted(Manifest.permission.ACCESS_MEDIA_LOCATION))
}

/**
 * Галерея через MediaStore. Время съёмки — колонка `DATE_TAKEN` (UTC), координаты в MediaStore с Android 10
 * не лежат, поэтому у каждого фото читается EXIF; из-за этого первый просмотр большой галереи небыстрый.
 */
class AndroidGallery(private val context: Context) : Gallery {
    override suspend fun scan(afterId: Long, onPage: suspend (seen: Int, points: List<PhotoPoint>) -> Unit): GalleryScan {
        if (!hasGalleryAccess(context)) throw GalleryDeniedException()
        val resolver = context.contentResolver
        val collection = if (Build.VERSION.SDK_INT >= 29) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }
        val inCamera = if (Build.VERSION.SDK_INT >= 29) {
            "${MediaStore.Images.Media.RELATIVE_PATH} LIKE '$CAMERA_DIR%'"
        } else {
            @Suppress("DEPRECATION")
            "${MediaStore.Images.Media.DATA} LIKE '%/$CAMERA_DIR%'"
        }
        val points = ArrayList<PhotoPoint>()
        var lastId = afterId
        var seen = 0
        val cursor = withContext(Dispatchers.IO) {
            try {
                resolver.query(
                    collection,
                    arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DATE_TAKEN),
                    "${MediaStore.Images.Media._ID} > ? AND $inCamera",
                    arrayOf(afterId.toString()),
                    "${MediaStore.Images.Media._ID} ASC",
                )
            } catch (_: SecurityException) {
                throw GalleryDeniedException()
            }
        } ?: return GalleryScan(points, lastId)
        cursor.use {
            // Читаем пачками на IO, а [onPage] зовём в потоке вызвавшего: модель живёт в одном потоке.
            while (true) {
                val page = ArrayList<PhotoPoint>()
                val read = withContext(Dispatchers.IO) {
                    var n = 0
                    while (n < BATCH && cursor.moveToNext()) {
                        ensureActive()
                        n++
                        lastId = cursor.getLong(0)
                        readPoint(resolver, collection, cursor)?.let(page::add)
                    }
                    n
                }
                if (read == 0) break
                seen += read
                points += page
                onPage(seen, page)
            }
        }
        return GalleryScan(points, lastId)
    }

    private fun readPoint(resolver: ContentResolver, collection: Uri, cursor: Cursor): PhotoPoint? {
        val id = cursor.getLong(0)
        val taken = if (cursor.isNull(1)) 0L else cursor.getLong(1)
        if (taken <= 0L) return null
        val latLon = FloatArray(2)
        try {
            var uri = ContentUris.withAppendedId(collection, id)
            // Без «оригинала» система отдаёт файл с вырезанными координатами.
            if (Build.VERSION.SDK_INT >= 29) uri = MediaStore.setRequireOriginal(uri)
            val found = resolver.openInputStream(uri)?.use { ExifInterface(it).getLatLong(latLon) } ?: false
            if (!found) return null
        } catch (_: Exception) {
            // Файл пропал, не читается или формат без EXIF — фото просто не даёт точки.
            return null
        }
        val lat = latLon[0].toDouble()
        val lon = latLon[1].toDouble()
        if (lat == 0.0 && lon == 0.0) return null
        // Секунды, как в кэше: точка после чтения из кэша равна только что прочитанной.
        return PhotoPoint(Instant.fromEpochSeconds(taken / 1000), lat, lon, PhotoSource.Gallery, "media:$id")
    }
}
