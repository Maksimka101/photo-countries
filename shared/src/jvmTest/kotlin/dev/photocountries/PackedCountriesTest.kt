package dev.photocountries

import dev.photocountries.core.CountryIndex
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Настоящий countries.bin из ресурсов: читается и узнаёт известные места. Тесты идут из каталога модуля. */
class PackedCountriesTest {
    private val index = CountryIndex.parse(File("src/commonMain/composeResources/files/countries.bin").readBytes())

    @Test
    fun capitals() {
        assertEquals("FR", index.locate(48.8566, 2.3522))
        assertEquals("RS", index.locate(44.8176, 20.4633))
        assertEquals("RU", index.locate(55.7558, 37.6173))
        assertEquals("JP", index.locate(35.6762, 139.6503))
        assertEquals("AR", index.locate(-34.6037, -58.3816))
        assertEquals("NO", index.locate(59.9139, 10.7522))
        assertEquals("XK", index.locate(42.6629, 21.1655))
    }

    @Test
    fun enclaveBelongsToInnerCountry() {
        // Лесото — дыра в полигоне ЮАР.
        assertEquals("LS", index.locate(-29.31, 27.48))
    }

    @Test
    fun openOceanIsNowhere() {
        assertNull(index.locate(30.0, -40.0))
    }
}
