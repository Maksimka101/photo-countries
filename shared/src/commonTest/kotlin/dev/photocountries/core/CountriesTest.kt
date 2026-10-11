package dev.photocountries.core

import kotlin.math.roundToLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class CountriesTest {
    private fun ring(vararg xy: Double) = doubleArrayOf(*xy)

    /** Квадрат 10×10 от (0,0) с дырой 4..6 посередине и квадрат поменьше правее. */
    private val polygons = listOf(
        CountryPolygon("AA", listOf(
            ring(0.0, 0.0, 10.0, 0.0, 10.0, 10.0, 0.0, 10.0, 0.0, 0.0),
            ring(4.0, 4.0, 6.0, 4.0, 6.0, 6.0, 4.0, 6.0, 4.0, 4.0),
        )),
        CountryPolygon("BB", listOf(ring(20.0, 0.0, 22.0, 0.0, 22.0, 2.0, 20.0, 2.0, 20.0, 0.0))),
        // Перекрывает AA: побеждает тот, кто раньше в списке.
        CountryPolygon("CC", listOf(ring(1.0, 1.0, 2.0, 1.0, 2.0, 2.0, 1.0, 2.0, 1.0, 1.0))),
    )
    private val index = CountryIndex(polygons)

    @Test
    fun pointInsidePolygon() {
        assertEquals("AA", index.locate(lat = 8.0, lon = 3.0))
        assertEquals("BB", index.locate(lat = 1.0, lon = 21.0))
        assertEquals("AA", index.locate(lat = 1.5, lon = 1.5))
    }

    @Test
    fun pointInHoleIsOutside() {
        assertNull(index.locate(lat = 5.0, lon = 5.0))
    }

    @Test
    fun pointJustOutsideSnapsToNearestVertexWithinTolerance() {
        assertEquals("BB", index.locate(lat = 2.2, lon = 22.1))
        assertEquals("AA", index.locate(lat = -0.2, lon = 10.1))
        assertNull(index.locate(lat = 2.5, lon = 22.5))
        assertNull(index.locate(lat = 50.0, lon = 50.0))
    }

    @Test
    fun parsesPackedFile() {
        val parsed = CountryIndex.parse(pack(polygons))
        assertEquals("AA", parsed.locate(lat = 8.0, lon = 3.0))
        assertNull(parsed.locate(lat = 5.0, lon = 5.0))
        assertEquals("BB", parsed.locate(lat = 1.0, lon = 21.0))
    }

    @Test
    fun parsesNegativeCoordinates() {
        val west = CountryPolygon("WW", listOf(ring(-70.5, -33.5, -70.0, -33.5, -70.0, -33.0, -70.5, -33.0, -70.5, -33.5)))
        assertEquals("WW", CountryIndex.parse(pack(listOf(west))).locate(lat = -33.25, lon = -70.25))
    }

    @Test
    fun rejectsForeignOrTruncatedFile() {
        assertFailsWith<IllegalArgumentException> { CountryIndex.parse(byteArrayOf(1, 2, 3, 4, 5)) }
        val packed = pack(polygons)
        assertFailsWith<IllegalArgumentException> { CountryIndex.parse(packed.copyOf(packed.size - 3)) }
    }

    @Test
    fun flagIsTwoRegionalIndicators() {
        assertEquals("🇷🇸", flagEmoji("RS"))
        assertEquals("🇦🇿", flagEmoji("AZ"))
        assertEquals("🏳", flagEmoji("-99"))
    }

    /** То же, что делает prototype/pack_countries.py. */
    private fun pack(polygons: List<CountryPolygon>): ByteArray {
        val out = ArrayList<Byte>()
        fun varint(value: Long) {
            var n = value
            while (true) {
                val b = (n and 0x7F).toInt()
                n = n ushr 7
                if (n != 0L) out += (b or 0x80).toByte() else { out += b.toByte(); return }
            }
        }
        fun zigzag(n: Long) = varint((n shl 1) xor (n shr 63))
        "PCB1".forEach { out += it.code.toByte() }
        varint(polygons.size.toLong())
        for (p in polygons) {
            p.code.forEach { out += it.code.toByte() }
            varint(p.rings.size.toLong())
            for (r in p.rings) {
                varint((r.size / 2).toLong())
                var px = 0L
                var py = 0L
                for (i in r.indices step 2) {
                    val x = (r[i] * 100_000).roundToLong()
                    val y = (r[i + 1] * 100_000).roundToLong()
                    zigzag(x - px); zigzag(y - py)
                    px = x; py = y
                }
            }
        }
        return out.toByteArray()
    }
}
