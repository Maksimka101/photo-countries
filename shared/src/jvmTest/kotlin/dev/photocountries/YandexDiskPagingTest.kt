package dev.photocountries

import dev.photocountries.yandex.YandexDisk
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class YandexDiskPagingTest {
    private val good = """{"date_time":"2024-05-01T10:20:30+00:00","gps_latitude":44.5,"gps_longitude":20.25}"""
    private fun item(name: String, exif: String) = """{"path":"disk:/Фотки/$name","resource_id":"$name","exif":$exif}"""
    private fun page(vararg items: String) = """{"items":[${items.joinToString(",")}],"offset":0}"""

    @Test
    fun pagesUntilEmptyPageEvenAfterShortOne() = runBlocking {
        val urls = ArrayList<String>()
        val pages = listOf(
            // Короткая страница не в конце списка.
            page(item("a.jpg", good), item("b.jpg", "{}")),
            page(item("c.jpg", good)),
            page(),
        )
        val disk = YandexDisk(get = { url, token ->
            assertEquals("secret", token)
            urls += url
            pages[urls.size - 1]
        })
        val progress = ArrayList<Int>()
        val pagePoints = ArrayList<List<String>>()
        val points = disk.fetchPoints("secret") { seen, page ->
            progress += seen
            pagePoints += page.map { it.id }
        }

        assertEquals(listOf("a.jpg", "c.jpg"), points.map { it.id })
        assertEquals(listOf(2, 3), progress)
        assertEquals(listOf(listOf("a.jpg"), listOf("c.jpg")), pagePoints)
        // offset идёт шагом limit, а не по числу пришедших файлов.
        assertEquals(listOf("0", "500", "1000"), urls.map { it.substringAfter("offset=").substringBefore('&') })
        assertTrue(urls.all { it.startsWith("https://cloud-api.yandex.net/v1/disk/resources/files?media_type=image&limit=500&") })
        assertTrue(urls.all { "fields=items.path,items.resource_id,items.preview,items.exif." in it })
    }

    @Test
    fun fetchNewWidensUntilKnownPhoto() = runBlocking {
        val urls = ArrayList<String>()
        val pages = listOf(
            // В первой сотне загрузок знакомых нет — выборка расширяется.
            page(item("new2.jpg", good), item("nogps.jpg", "{}")),
            page(item("new2.jpg", good), item("nogps.jpg", "{}"), item("new1.jpg", good), item("old.jpg", good)),
        )
        val disk = YandexDisk(get = { url, _ -> urls += url; pages[urls.size - 1] })

        val fresh = disk.fetchNew("secret", setOf("old.jpg"))

        assertEquals(listOf("new2.jpg", "new1.jpg"), fresh?.map { it.id })
        assertEquals(listOf("100", "1000"), urls.map { it.substringAfter("limit=").substringBefore('&') })
        assertTrue(urls.all { it.startsWith("https://cloud-api.yandex.net/v1/disk/resources/last-uploaded?media_type=image&") })
        assertTrue(urls.all { "fields=path,resource_id,preview,exif." in it })
    }

    @Test
    fun fetchNewGivesUpWhenNothingKnown() = runBlocking {
        var calls = 0
        // Весь диск влез в выборку, а знакомых нет: чужой кэш.
        val disk = YandexDisk(get = { _, _ -> calls++; page(item("a.jpg", good)) })

        assertEquals(null, disk.fetchNew("secret", setOf("old.jpg")))
        assertEquals(2, calls)
    }
}
