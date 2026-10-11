package dev.photocountries.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.photocountries.StayPhotos
import dev.photocountries.core.Stay
import dev.photocountries.core.StayKind
import dev.photocountries.core.Timeline
import dev.photocountries.core.YearStrip
import dev.photocountries.core.flagEmoji
import dev.photocountries.core.stayOn
import dev.photocountries.core.yearStrips
import dev.photocountries.shared.*
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus
import org.jetbrains.compose.resources.stringResource

private val YEAR_LABEL = 48.dp
private val BAR_HEIGHT = 28.dp
private val MONTH_DAYS = listOf(31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)

/**
 * Год — горизонтальная полоса из дней, закрашенных цветами стран, с флагами на отрезках; свежие годы сверху.
 * Дни без фото между пребываниями поделены между соседями. Тап по стране подписывает её пребывание —
 * даты фото и длительность, тап по пустому дню — дату. Нажатие на подпись пребывания открывает ту же
 * шторку с фото ([photos]) и «Скрыть», что и в ленте.
 */
@Composable
fun YearsScreen(
    timeline: Timeline,
    colors: CountryColors,
    onHide: (Stay) -> Unit,
    photos: (Stay) -> StayPhotos,
    bottomPad: Dp,
    modifier: Modifier = Modifier,
) {
    val strips = remember(timeline) { yearStrips(timeline.stays).reversed() }
    // Выбранный день: год и номер дня в нём.
    var selected by remember(timeline) { mutableStateOf<Pair<Int, Int>?>(null) }
    var opened by remember(timeline) { mutableStateOf<Stay?>(null) }
    LazyColumn(
        modifier,
        contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = bottomPad),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Legend(timeline, colors) }
        item { MonthLabels() }
        items(strips, key = { it.year }) { strip ->
            YearRow(
                strip = strip,
                stays = timeline.stays,
                colors = colors,
                selectedDay = selected?.takeIf { it.first == strip.year }?.second,
                onSelect = { day -> selected = (strip.year to day).takeIf { it != selected } },
                onOpen = { opened = it },
            )
        }
    }
    opened?.let { stay ->
        StaySheet(stay, remember(stay) { photos(stay) }, onHide = { opened = null; onHide(stay) }, onDismiss = { opened = null })
    }
}

/** Цвет сам по себе страну не называет — рядом всегда флаг и название. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Legend(timeline: Timeline, colors: CountryColors) {
    val own = remember(timeline, colors) { timeline.totals.map { it.country }.filter(colors::hasOwn) }
    val hasOther = remember(timeline, colors) { timeline.totals.any { !colors.hasOwn(it.country) } }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        own.forEach { code -> LegendItem(colors.of(code), "${flagEmoji(code)} ${countryName(code)}") }
        if (hasOther) LegendItem(colors.other, stringResource(Res.string.other_countries))
    }
}

@Composable
private fun LegendItem(color: androidx.compose.ui.graphics.Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(12.dp).background(color, RoundedCornerShape(3.dp)))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun MonthLabels() {
    val letters = stringResource(Res.string.months)
    Row(Modifier.fillMaxWidth().padding(start = YEAR_LABEL)) {
        MONTH_DAYS.forEachIndexed { i, days ->
            Text(
                letters.getOrNull(i)?.toString().orEmpty(),
                modifier = Modifier.weight(days.toFloat()),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** Подряд идущие дни одной страны: с [from] по [to] (не включая). */
private class DayRun(val from: Int, val to: Int, val code: String)

private fun dayRuns(days: List<String?>): List<DayRun> {
    val runs = ArrayList<DayRun>()
    var from = 0
    while (from < days.size) {
        val code = days[from]
        var to = from + 1
        while (to < days.size && days[to] == code) to++
        if (code != null) runs += DayRun(from, to, code)
        from = to
    }
    return runs
}

@Composable
private fun YearRow(
    strip: YearStrip,
    stays: List<Stay>,
    colors: CountryColors,
    selectedDay: Int?,
    onSelect: (Int) -> Unit,
    onOpen: (Stay) -> Unit,
) {
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    val marker = MaterialTheme.colorScheme.onSurface
    val surface = MaterialTheme.colorScheme.surface
    val runs = remember(strip) { dayRuns(strip.days) }
    val measurer = rememberTextMeasurer()
    val flagStyle = MaterialTheme.typography.labelLarge
    val flags = remember(runs, flagStyle) {
        runs.map { it.code }.distinct().associateWith { measurer.measure(flagEmoji(it), flagStyle) }
    }
    Column {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                strip.year.toString(),
                Modifier.width(YEAR_LABEL).height(BAR_HEIGHT).wrapContentHeight(),
                style = MaterialTheme.typography.titleMedium,
                fontFamily = DisplayFont,
            )
            BoxWithConstraints(Modifier.weight(1f)) {
                val density = LocalDensity.current
                val dayWidth = constraints.maxWidth.toFloat() / strip.days.size
                val flagPad = with(density) { 2.dp.toPx() }
                // Флаг рисуется на самом отрезке, если влезает; для коротких отрезков — в ряду над полосой.
                val (wide, narrow) = runs.partition { (it.to - it.from) * dayWidth >= flags.getValue(it.code).size.width + flagPad }
                Column {
                    if (narrow.isNotEmpty()) {
                        val laneHeight = with(density) { flags.values.maxOf { it.size.height }.toDp() }
                        Canvas(Modifier.fillMaxWidth().height(laneHeight)) {
                            // Флаги, которые налезли бы на соседа слева, пропускаются: страну покажет тап по дню.
                            var free = 0f
                            for (run in narrow) {
                                val flag = flags.getValue(run.code)
                                val x = ((run.from + run.to) / 2f * dayWidth - flag.size.width / 2f)
                                    .coerceIn(0f, (size.width - flag.size.width).coerceAtLeast(0f))
                                if (x < free) continue
                                drawText(flag, topLeft = Offset(x, size.height - flag.size.height))
                                free = x + flag.size.width
                            }
                        }
                    }
                    Canvas(
                        Modifier
                            .fillMaxWidth()
                            .height(BAR_HEIGHT)
                            .clip(RoundedCornerShape(8.dp))
                            .pointerInput(strip) {
                                detectTapGestures { pos ->
                                    val day = (pos.x / size.width * strip.days.size).toInt().coerceIn(0, strip.days.lastIndex)
                                    onSelect(day)
                                }
                            },
                    ) {
                        drawRect(track)
                        // Между разными странами зазор цвета фона.
                        for (run in runs) {
                            val gap = if (run.to < strip.days.size && strip.days[run.to] != null) 1.dp.toPx() else 0f
                            val width = ((run.to - run.from) * dayWidth - gap).coerceAtLeast(1.dp.toPx())
                            drawRect(colors.of(run.code), Offset(run.from * dayWidth, 0f), Size(width, size.height))
                            if (gap > 0f) drawRect(surface, Offset(run.from * dayWidth + width, 0f), Size(gap, size.height))
                        }
                        // Границы месяцев — короткие засечки снизу.
                        var day = 0
                        for (m in 0 until 11) {
                            day += MONTH_DAYS[m] + if (m == 1 && strip.days.size == 366) 1 else 0
                            drawRect(surface, Offset(day * dayWidth - 0.5.dp.toPx(), size.height - 5.dp.toPx()), Size(1.dp.toPx(), 5.dp.toPx()))
                        }
                        for (run in wide) {
                            val flag = flags.getValue(run.code)
                            drawText(
                                flag,
                                topLeft = Offset(
                                    (run.from + run.to) / 2f * dayWidth - flag.size.width / 2f,
                                    (size.height - flag.size.height) / 2f,
                                ),
                            )
                        }
                        if (selectedDay != null) {
                            drawRect(marker, Offset((selectedDay + 0.5f) * dayWidth - 1.dp.toPx(), 0f), Size(2.dp.toPx(), size.height))
                        }
                    }
                }
            }
        }
        if (selectedDay != null) {
            val date = LocalDate(strip.year, 1, 1).plus(DatePeriod(days = selectedDay))
            val stay = remember(stays, date) { stayOn(stays, date) }
            val text = if (stay != null) {
                val name = "${flagEmoji(stay.country)} ${countryName(stay.country)}"
                val kind = if (stay.kind == StayKind.Transit) stringResource(Res.string.kind_transit) + " · " else ""
                // Стрелка — знак, что подпись нажимается.
                "$kind$name · ${formatDateRange(stay.startDate, stay.endDate)} · ${daysLabel(stay)} ›"
            } else {
                "${formatDate(date)} — ${stringResource(Res.string.no_stay)}"
            }
            val chip = Modifier.padding(start = YEAR_LABEL, top = 6.dp)
            val color = MaterialTheme.colorScheme.secondaryContainer
            val shape = MaterialTheme.shapes.small
            val label = @Composable {
                Text(text, Modifier.padding(horizontal = 10.dp, vertical = 4.dp), style = MaterialTheme.typography.labelLarge)
            }
            if (stay != null) {
                Surface(onClick = { onOpen(stay) }, modifier = chip, color = color, shape = shape, content = label)
            } else {
                Surface(chip, color = color, shape = shape, content = label)
            }
        }
    }
}
