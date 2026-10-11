package dev.photocountries.yandex

import dev.photocountries.core.PhotoSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.time.Instant

class YandexDiskTest {
    private fun item(path: String, exif: String, id: String = "1:$path") =
        """{"path":"$path","resource_id":"$id","exif":$exif}"""

    private fun page(vararg items: String) = """{"items":[${items.joinToString(",")}],"offset":0}"""

    private val good = """{"date_time":"2024-05-01T10:20:30+00:00","gps_latitude":44.5,"gps_longitude":20.25}"""

    @Test
    fun takesTimeAndCoordinatesFromExif() {
        val parsed = parseDiskPage(page(item("disk:/Фотокамера/a.jpg", good, id = "42:abc")))
        assertEquals(1, parsed.itemCount)
        val p = parsed.points.single()
        assertEquals(Instant.parse("2024-05-01T10:20:30Z"), p.time)
        assertEquals(44.5, p.lat)
        assertEquals(20.25, p.lon)
        assertEquals(PhotoSource.YandexDisk, p.source)
        assertEquals("42:abc", p.id)
        assertEquals(null, p.preview)
    }

    @Test
    fun skipsItemsWithoutUsableGeotag() {
        val parsed = parseDiskPage(
            page(
                item("disk:/Фотокамера/no-exif.jpg", "{}"),
                item("disk:/Фотокамера/null-exif.jpg", "null"),
                item("disk:/Фотокамера/no-gps.jpg", """{"date_time":"2024-05-01T10:20:30+00:00"}"""),
                item("disk:/Фотокамера/null-gps.jpg", """{"date_time":"2024-05-01T10:20:30+00:00","gps_latitude":null,"gps_longitude":null}"""),
                item("disk:/Фотокамера/zero.jpg", """{"date_time":"2024-05-01T10:20:30+00:00","gps_latitude":0,"gps_longitude":0.0}"""),
                item("disk:/Фотокамера/no-time.jpg", """{"gps_latitude":44.5,"gps_longitude":20.25}"""),
                item("disk:/Фотокамера/bad-time.jpg", """{"date_time":"вчера","gps_latitude":44.5,"gps_longitude":20.25}"""),
                item("disk:/Чужое/a.jpg", good),
                item("disk:/Фотки/2020/b.jpg", good),
            ),
        )
        assertEquals(9, parsed.itemCount)
        assertEquals(listOf("1:disk:/Фотки/2020/b.jpg"), parsed.points.map { it.id })
    }

    @Test
    fun ignoresUnknownFieldsAndEscapes() {
        val json = """{"limit":1000,"items":[{"name":"a\"b\\.jpg","sizes":[{"url":"x","n":[1,2,{"k":null}]}],"preview":"p",
            "path":"disk:/Фотки/aA.jpg","exif":{"gps_longitude":-70.5,"x":true,"gps_latitude":-33.25e0,"date_time":"2020-01-01T00:00:00+00:00"}}]}"""
        val p = parseDiskPage(json).points.single()
        assertEquals(-33.25, p.lat)
        assertEquals(-70.5, p.lon)
        // Без resource_id точку опознаём по пути.
        assertEquals("disk:/Фотки/aA.jpg", p.id)
        assertEquals("p", p.preview)
    }

    @Test
    fun rejectsResponseWithoutItems() {
        assertFailsWith<IllegalArgumentException> { parseDiskPage("""{"error":"UnauthorizedError"}""") }
        assertFailsWith<IllegalArgumentException> { parseDiskPage("""{"items":[{"path":""") }
    }

    @Test
    fun readsTokenFromRedirectFragment() {
        assertEquals("abc123", tokenFromRedirect("photocountries://auth#access_token=abc123&token_type=bearer&expires_in=31536000"))
        assertEquals("abc123", tokenFromRedirect("photocountries://auth#state=x&access_token=abc123"))
        assertNull(tokenFromRedirect("photocountries://auth#error=access_denied&error_description=x"))
        assertNull(tokenFromRedirect("photocountries://auth"))
        assertNull(tokenFromRedirect("https://example.com/#access_token=abc123"))
    }

    @Test
    fun authorizeUrlNeedsClientId() {
        assertNull(authorizeUrl(""))
        assertEquals("https://oauth.yandex.ru/authorize?response_type=token&client_id=id1", authorizeUrl("id1"))
    }
}
