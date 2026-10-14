package dev.photocountries

import dev.photocountries.core.CountryIndex
import dev.photocountries.core.HiddenStay
import dev.photocountries.core.PhotoPoint
import dev.photocountries.core.PhotoSource
import dev.photocountries.data.AppStorage
import dev.photocountries.data.CachedPoints
import dev.photocountries.data.UiPrefs
import dev.photocountries.gallery.Gallery
import dev.photocountries.gallery.GalleryDeniedException
import dev.photocountries.gallery.GalleryScan
import dev.photocountries.yandex.YandexDisk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/** Источники в модели: галерея без диска, диск вместе с галереей, отключение. */
class AppModelSourcesTest {
    private val index = CountryIndex.parse(File("src/commonMain/composeResources/files/countries.bin").readBytes())

    private class MemoryStorage(var token: String? = null, var points: CachedPoints? = null) : AppStorage {
        var gallery: Long? = null
        var ui = UiPrefs()
        override suspend fun loadToken() = token
        override suspend fun saveToken(token: String?) { this.token = token }
        override suspend fun loadPoints() = points
        override suspend fun savePoints(points: CachedPoints) { this.points = points }
        override suspend fun loadHidden() = emptyList<HiddenStay>()
        override suspend fun saveHidden(hidden: List<HiddenStay>) = Unit
        override suspend fun loadGallery() = gallery
        override suspend fun saveGallery(lastId: Long?) { gallery = lastId }
        override suspend fun loadUi() = ui
        override suspend fun saveUi(prefs: UiPrefs) { ui = prefs }
    }

    /** Галерея из готовых фото: id — номер по порядку с единицы. */
    private class FakeGallery(var photos: List<PhotoPoint>, var denied: Boolean = false) : Gallery {
        val asked = ArrayList<Long>()
        override suspend fun scan(afterId: Long, onPage: suspend (Int, List<PhotoPoint>) -> Unit): GalleryScan {
            if (denied) throw GalleryDeniedException()
            asked += afterId
            val fresh = photos.drop(afterId.toInt())
            onPage(fresh.size, fresh)
            return GalleryScan(fresh, photos.size.toLong())
        }
    }

    private fun photo(time: String, lat: Double, lon: Double, source: PhotoSource, id: String) =
        PhotoPoint(Instant.parse(time + "Z"), lat, lon, source, id)

    private val paris = photo("2024-05-01T10:00:00", 48.8566, 2.3522, PhotoSource.Gallery, "media:1")
    private val belgrade = photo("2024-06-01T10:00:00", 44.8176, 20.4633, PhotoSource.Gallery, "media:2")

    private fun run(storage: MemoryStorage, gallery: Gallery?, disk: YandexDisk = YandexDisk({ _, _ -> error("диск не подключён") }), body: suspend (AppModel) -> Unit) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))
        try {
            val model = AppModel(storage, scope, disk, gallery, loadCountries = { index })
            model.start()
            runBlocking { withTimeout(20_000) { body(model) } }
        } finally {
            scope.cancel()
        }
    }

    private suspend fun AppModel.await(condition: (AppState) -> Boolean): AppState =
        state.first { it.started && !it.loading && condition(it) }

    @Test
    fun galleryAloneFillsTimelineAndLaterScansOnlyNewPhotos() {
        val storage = MemoryStorage()
        val gallery = FakeGallery(listOf(paris))
        run(storage, gallery) { model ->
            val idle = model.await { true }
            assertFalse(idle.connected)
            assertEquals(GalleryStatus.Off, idle.gallery)

            model.connectGallery()
            val first = model.await { it.timeline?.stays?.isNotEmpty() == true }
            assertTrue(first.connected)
            assertEquals(listOf("FR"), first.timeline!!.stays.map { it.country })
            assertEquals(1L, storage.gallery)

            gallery.photos = listOf(paris, belgrade)
            model.refresh()
            val second = model.await { it.timeline?.stays?.size == 2 }
            assertEquals(listOf("FR", "RS"), second.timeline!!.stays.map { it.country })
            assertEquals(listOf(0L, 1L), gallery.asked)
            assertEquals(listOf(paris, belgrade), storage.points!!.points)

            model.disconnectGallery()
            val off = model.await { it.gallery == GalleryStatus.Off }
            assertFalse(off.connected)
            assertNull(storage.gallery)
            assertEquals(emptyList(), storage.points!!.points)
        }
    }

    @Test
    fun galleryAddedToDiskDoesNotCountSharedPhotosTwice() {
        // На диске — то же парижское фото, что и в галерее; обновление диска новых не находит.
        val onDisk = paris.copy(source = PhotoSource.YandexDisk, id = "a.jpg")
        val storage = MemoryStorage("secret", CachedPoints(Instant.parse("2024-07-01T00:00:00Z"), listOf(onDisk)))
        val exif = """{"date_time":"2024-05-01T10:00:00+00:00","gps_latitude":48.8566,"gps_longitude":2.3522}"""
        val disk = YandexDisk({ _, _ -> """{"items":[{"path":"disk:/Фотки/a.jpg","resource_id":"a.jpg","exif":$exif}]}""" })
        run(storage, FakeGallery(listOf(paris, belgrade)), disk) { model ->
            model.await { it.hasToken }
            model.connectGallery()
            val both = model.await { it.timeline?.stays?.size == 2 }
            assertEquals(listOf(1, 1), both.timeline!!.stays.map { it.photos })
            assertEquals(2, both.pointCount)

            // Диск отключили — парижское фото остаётся за галереей.
            model.logout()
            val alone = model.await { !it.hasToken }
            assertTrue(alone.connected)
            assertEquals(listOf("FR", "RS"), alone.timeline!!.stays.map { it.country })
        }
    }

    @Test
    fun revokedPermissionIsReportedAndKeepsPoints() {
        val storage = MemoryStorage(points = CachedPoints(Instant.parse("2024-07-01T00:00:00Z"), listOf(paris)))
        storage.gallery = 1
        run(storage, FakeGallery(listOf(paris), denied = true)) { model ->
            val state = model.await { it.load is LoadStatus.Error }
            assertEquals(LoadStatus.Error(LoadError.GalleryDenied), state.load)
            assertEquals(listOf("FR"), state.timeline!!.stays.map { it.country })
            assertEquals(1L, storage.gallery)
        }
    }

    @Test
    fun tabAndSortAreRestoredAndSaved() {
        val storage = MemoryStorage()
        storage.ui = UiPrefs(tab = 2, sortByRecent = true)
        run(storage, gallery = null) { model ->
            assertEquals(UiPrefs(2, true), model.await { true }.ui)
            model.setTab(1)
            model.setSortByRecent(false)
            assertEquals(UiPrefs(1, false), model.state.value.ui)
            // Запись идёт следом в корутине модели.
            while (storage.ui != UiPrefs(1, false)) delay(10)
        }
    }
}
