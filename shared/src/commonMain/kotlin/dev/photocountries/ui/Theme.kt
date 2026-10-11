package dev.photocountries.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.window.DialogProperties
import dev.photocountries.core.StayKind
import dev.photocountries.core.Timeline

/** Название страны по ISO-коду на языке устройства — от платформы, а не из набора полигонов. */
expect fun countryName(code: String): String

/**
 * Запрос доступа к галерее телефона: возвращает действие «спросить разрешение», итог приходит в [onResult].
 * null — на этой платформе галереи нет.
 */
@Composable
expect fun rememberGalleryAccessRequest(onResult: (granted: Boolean) -> Unit): (() -> Unit)?

/** Засечки — для заголовков и крупных чисел; текст остаётся системным гротеском. */
val DisplayFont: FontFamily = FontFamily.Serif

// Своя палитра вместо цветов из обоев: тёплая бумага, чернильный синий и песочный акцент.
// primaryContainer — тёмная «чернильная» подложка (сводка, навигация) в обеих темах.
private val INK = Color(0xFF17273D)
private val CREAM = Color(0xFFF3EAD8)
private val SAND = Color(0xFFEBCB8F)
private val ON_SAND = Color(0xFF2B1F08)

private val LIGHT_SCHEME = lightColorScheme(
    primary = Color(0xFF1F3A5F),
    onPrimary = Color(0xFFFBF6EC),
    primaryContainer = INK,
    onPrimaryContainer = CREAM,
    inversePrimary = Color(0xFFA9C4E8),
    secondary = Color(0xFF5B5446),
    onSecondary = Color(0xFFFBF6EC),
    secondaryContainer = Color(0xFFE3D8BF),
    onSecondaryContainer = Color(0xFF2A2418),
    tertiary = Color(0xFF8A5A12),
    onTertiary = Color(0xFFFBF6EC),
    tertiaryContainer = SAND,
    onTertiaryContainer = ON_SAND,
    background = Color(0xFFF7F2E8),
    onBackground = Color(0xFF1E1A14),
    surface = Color(0xFFF7F2E8),
    onSurface = Color(0xFF1E1A14),
    surfaceVariant = Color(0xFFE7DFCD),
    onSurfaceVariant = Color(0xFF5F574A),
    surfaceTint = Color(0xFF1F3A5F),
    inverseSurface = Color(0xFF2F2B24),
    inverseOnSurface = Color(0xFFF4EEE2),
    outline = Color(0xFF8A8172),
    outlineVariant = Color(0xFFD3C9B4),
    surfaceBright = Color(0xFFF7F2E8),
    surfaceDim = Color(0xFFDFD6C2),
    surfaceContainerLowest = Color(0xFFFFFCF5),
    surfaceContainerLow = Color(0xFFF2ECDF),
    surfaceContainer = Color(0xFFEDE6D6),
    surfaceContainerHigh = Color(0xFFE7DFCD),
    surfaceContainerHighest = Color(0xFFDFD6C2),
)

private val DARK_SCHEME = darkColorScheme(
    primary = Color(0xFFE6C78F),
    onPrimary = ON_SAND,
    primaryContainer = Color(0xFF24364F),
    onPrimaryContainer = CREAM,
    inversePrimary = Color(0xFF1F3A5F),
    secondary = Color(0xFFCBC2AE),
    onSecondary = Color(0xFF2A2418),
    secondaryContainer = Color(0xFF3A3F4A),
    onSecondaryContainer = Color(0xFFEDE6D8),
    tertiary = Color(0xFFE6C78F),
    onTertiary = ON_SAND,
    tertiaryContainer = SAND,
    onTertiaryContainer = ON_SAND,
    background = Color(0xFF13161B),
    onBackground = Color(0xFFEDE6D8),
    surface = Color(0xFF13161B),
    onSurface = Color(0xFFEDE6D8),
    surfaceVariant = Color(0xFF313742),
    onSurfaceVariant = Color(0xFFB3AC9E),
    surfaceTint = Color(0xFFE6C78F),
    inverseSurface = Color(0xFFEDE6D8),
    inverseOnSurface = Color(0xFF2F2B24),
    outline = Color(0xFF857F73),
    outlineVariant = Color(0xFF3C424D),
    surfaceBright = Color(0xFF373D48),
    surfaceDim = Color(0xFF13161B),
    surfaceContainerLowest = Color(0xFF0D0F13),
    surfaceContainerLow = Color(0xFF191D23),
    surfaceContainer = Color(0xFF1E222A),
    surfaceContainerHigh = Color(0xFF272C35),
    surfaceContainerHighest = Color(0xFF313742),
)

private val TYPOGRAPHY = Typography().run {
    copy(
        displayLarge = displayLarge.copy(fontFamily = DisplayFont),
        displayMedium = displayMedium.copy(fontFamily = DisplayFont),
        displaySmall = displaySmall.copy(fontFamily = DisplayFont),
        headlineLarge = headlineLarge.copy(fontFamily = DisplayFont),
        headlineMedium = headlineMedium.copy(fontFamily = DisplayFont),
        headlineSmall = headlineSmall.copy(fontFamily = DisplayFont),
        titleLarge = titleLarge.copy(fontFamily = DisplayFont),
    )
}

@Composable
fun PhotoTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DARK_SCHEME else LIGHT_SCHEME,
        typography = TYPOGRAPHY,
        content = content,
    )
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

/** Свойства диалога на весь экран: содержимое заходит под системные панели и само следит за безопасной зоной. */
expect fun fullScreenDialogProperties(): DialogProperties
