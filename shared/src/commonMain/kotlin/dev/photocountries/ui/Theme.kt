package dev.photocountries.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import dev.photocountries.core.StayKind
import dev.photocountries.core.Timeline

/** Цвета темы: на Android 12+ — из обоев, иначе стандартные. */
@Composable
expect fun platformColorScheme(dark: Boolean): ColorScheme

/** Название страны по ISO-коду на языке устройства — от платформы, а не из набора полигонов. */
expect fun countryName(code: String): String

/**
 * Запрос доступа к галерее телефона: возвращает действие «спросить разрешение», итог приходит в [onResult].
 * null — на этой платформе галереи нет.
 */
@Composable
expect fun rememberGalleryAccessRequest(onResult: (granted: Boolean) -> Unit): (() -> Unit)?

@Composable
fun PhotoTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = platformColorScheme(isSystemInDarkTheme()), content = content)
}

/** Форма строки в слитном списке-карточке: у группы скруглены только внешние углы. */
fun groupShape(index: Int, count: Int): Shape {
    val top = if (index == 0) 20.dp else 4.dp
    val bottom = if (index == count - 1) 20.dp else 4.dp
    return RoundedCornerShape(topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom)
}

// Восемь различимых цветов (в том числе при дальтонизме), свой набор для светлой и тёмной темы.
private val LIGHT = listOf(0xFF2A78D6, 0xFFEB6834, 0xFF1BAF7A, 0xFFEDA100, 0xFFE87BA4, 0xFF008300, 0xFF6250D6, 0xFFE34948)
private val DARK = listOf(0xFF3987E5, 0xFFD95926, 0xFF199E70, 0xFFC98500, 0xFFD55181, 0xFF008300, 0xFF9085E9, 0xFFE66767)
private const val OTHER_LIGHT = 0xFF9A9A93
private const val OTHER_DARK = 0xFF6F6F69

/**
 * Цвет страны, один на все экраны. Цветов всего восемь, девятый оттенок уже не отличить, поэтому свой цвет
 * получают восемь стран с наибольшим числом дней, остальные — общий серый ([other]). Цвет — только подспорье:
 * рядом всегда флаг и название.
 */
class CountryColors(private val byCountry: Map<String, Color>, val other: Color) {
    fun of(code: String): Color = byCountry[code] ?: other
    fun hasOwn(code: String): Boolean = code in byCountry
}

@Composable
fun rememberCountryColors(timeline: Timeline): CountryColors {
    val dark = isSystemInDarkTheme()
    return remember(timeline, dark) {
        val palette = if (dark) DARK else LIGHT
        val top = timeline.totals.filter { it.visits > 0 }.take(palette.size).map { it.country }.toSet()
        // Внутри восьмёрки цвета раздаются по порядку первого появления в ленте: пока состав тот же,
        // новые поездки ничего не перекрашивают.
        val order = timeline.stays.filter { it.kind != StayKind.Transit && it.country in top }.map { it.country }.distinct()
        CountryColors(
            order.withIndex().associate { (i, code) -> code to Color(palette[i]) },
            Color(if (dark) OTHER_DARK else OTHER_LIGHT),
        )
    }
}
