package dev.photocountries.core

/** Допуск в градусах для точек у берега или границы, не попавших ни в один полигон. */
private const val NEAR_DEG = 0.3

/** Единица координат в countries.bin: 1e-5°. */
private const val SCALE = 100_000.0

/**
 * Полигон страны: [code] — ISO 3166 alpha-2, [rings] — кольца, первое внешнее, остальные дыры.
 * Кольцо — подряд идущие пары «долгота, широта».
 */
class CountryPolygon(val code: String, val rings: List<DoubleArray>) {
    internal val minX: Double
    internal val minY: Double
    internal val maxX: Double
    internal val maxY: Double

    init {
        val outer = rings[0]
        var x0 = Double.POSITIVE_INFINITY
        var y0 = Double.POSITIVE_INFINITY
        var x1 = Double.NEGATIVE_INFINITY
        var y1 = Double.NEGATIVE_INFINITY
        for (i in outer.indices step 2) {
            x0 = minOf(x0, outer[i]); x1 = maxOf(x1, outer[i])
            y0 = minOf(y0, outer[i + 1]); y1 = maxOf(y1, outer[i + 1])
        }
        minX = x0; minY = y0; maxX = x1; maxY = y1
    }
}

/**
 * Офлайн-определение страны по координате: «точка в полигоне» по границам Natural Earth.
 * Порядок полигонов важен: побеждает первый подошедший.
 */
class CountryIndex(private val polygons: List<CountryPolygon>) {

    /** Код страны для точки или null, если рядом (в пределах 0.3°) стран нет. */
    fun locate(lat: Double, lon: Double): String? {
        for (p in polygons) {
            if (lon < p.minX || lon > p.maxX || lat < p.minY || lat > p.maxY) continue
            if (!inRing(lon, lat, p.rings[0])) continue
            if ((1 until p.rings.size).any { inRing(lon, lat, p.rings[it]) }) continue
            return p.code
        }
        // Мимо полигонов: ближайшая вершина внешнего кольца в пределах допуска.
        var best = NEAR_DEG * NEAR_DEG
        var code: String? = null
        for (p in polygons) {
            if (lon < p.minX - NEAR_DEG || lon > p.maxX + NEAR_DEG ||
                lat < p.minY - NEAR_DEG || lat > p.maxY + NEAR_DEG
            ) continue
            val ring = p.rings[0]
            for (i in ring.indices step 2) {
                val dx = ring[i] - lon
                val dy = ring[i + 1] - lat
                val d = dx * dx + dy * dy
                if (d < best) {
                    best = d
                    code = p.code
                }
            }
        }
        return code
    }

    companion object {
        /**
         * Разбирает countries.bin; формат описан в `prototype/pack_countries.py`.
         * @throws IllegalArgumentException файл не того формата или обрезан.
         */
        fun parse(bytes: ByteArray): CountryIndex {
            require(bytes.size >= 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'C'.code.toByte() &&
                bytes[2] == 'B'.code.toByte() && bytes[3] == '1'.code.toByte()) { "Не файл полигонов стран" }
            var pos = 4

            fun varint(): Long {
                var result = 0L
                var shift = 0
                while (true) {
                    require(pos < bytes.size && shift < 64) { "Файл полигонов обрезан" }
                    val b = bytes[pos++].toInt()
                    result = result or ((b and 0x7F).toLong() shl shift)
                    if (b and 0x80 == 0) return result
                    shift += 7
                }
            }

            fun count(): Int {
                val n = varint()
                require(n in 0..bytes.size) { "Файл полигонов повреждён" }
                return n.toInt()
            }

            fun zigzag(): Long = varint().let { (it ushr 1) xor -(it and 1) }

            val polygons = List(count()) {
                require(pos + 2 <= bytes.size) { "Файл полигонов обрезан" }
                val code = "${bytes[pos].toInt().toChar()}${bytes[pos + 1].toInt().toChar()}"
                pos += 2
                val rings = List(count()) {
                    val n = count()
                    val ring = DoubleArray(n * 2)
                    var x = 0L
                    var y = 0L
                    for (i in 0 until n) {
                        x += zigzag()
                        y += zigzag()
                        ring[i * 2] = x / SCALE
                        ring[i * 2 + 1] = y / SCALE
                    }
                    ring
                }
                require(rings.isNotEmpty() && rings[0].isNotEmpty()) { "Пустой полигон в файле" }
                CountryPolygon(code, rings)
            }
            return CountryIndex(polygons)
        }
    }
}

/** Чётность пересечений луча из точки с рёбрами кольца. */
private fun inRing(x: Double, y: Double, ring: DoubleArray): Boolean {
    var inside = false
    val n = ring.size / 2
    var j = n - 1
    for (i in 0 until n) {
        val xi = ring[i * 2]
        val yi = ring[i * 2 + 1]
        val xj = ring[j * 2]
        val yj = ring[j * 2 + 1]
        if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) inside = !inside
        j = i
    }
    return inside
}

/** Флаг-эмодзи из кода страны: две буквы → два «региональных индикатора». */
fun flagEmoji(code: String): String {
    if (code.length != 2 || code.any { it !in 'A'..'Z' }) return "🏳"
    // U+1F1E6 «A» в UTF-16 — пара D83C DDE6; вся латиница индикаторов лежит в одной старшей половине.
    return buildString {
        for (c in code) {
            append('\uD83C')
            append((0xDDE6 + (c - 'A')).toChar())
        }
    }
}
