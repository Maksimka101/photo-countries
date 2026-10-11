package dev.photocountries.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.photocountries.core.HiddenStay
import dev.photocountries.core.Stay
import dev.photocountries.core.StayKind
import dev.photocountries.core.Timeline
import dev.photocountries.core.flagEmoji
import dev.photocountries.shared.*
import org.jetbrains.compose.resources.stringResource
import kotlin.math.sqrt

/**
 * Вертикальная лента по годам, свежее сверху. Пребывание, перешедшее через Новый год, стоит в году начала.
 * Пребывание можно скрыть из его карточки; скрытые ([hidden]) возвращаются из списка над лентой.
 */
@Composable
fun TimelineScreen(
    timeline: Timeline,
    colors: CountryColors,
    hidden: List<HiddenStay>,
    onHide: (Stay) -> Unit,
    onUnhide: (HiddenStay) -> Unit,
    modifier: Modifier = Modifier,
) {
    val byYear = remember(timeline) {
        timeline.stays.withIndex().reversed().groupBy { it.value.startDate.year }
    }
    var selected by remember(timeline) { mutableStateOf<Stay?>(null) }
    var showHidden by remember { mutableStateOf(false) }
    LazyColumn(modifier, contentPadding = PaddingValues(bottom = 24.dp)) {
        if (hidden.isNotEmpty()) {
            item(key = "hidden") {
                AssistChip(
                    onClick = { showHidden = true },
                    label = { Text(stringResource(Res.string.hidden_count, hidden.size)) },
                    modifier = Modifier.padding(start = 16.dp, top = 8.dp),
                )
            }
        }
        byYear.forEach { (year, stays) ->
            item(key = "y$year") {
                Text(
                    year.toString(),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 28.dp, top = 20.dp, bottom = 8.dp),
                )
            }
            items(stays.size, key = { stays[it].index }) { i ->
                val stay = stays[i].value
                StayRow(stay, colors.of(stay.country), onClick = { selected = stay })
            }
        }
    }
    selected?.let { stay -> StayDialog(stay, onHide = { selected = null; onHide(stay) }, onDismiss = { selected = null }) }
    if (showHidden && hidden.isNotEmpty()) HiddenDialog(hidden, onUnhide, onDismiss = { showHidden = false })
}

/** Высота полосы растёт с длительностью, но медленно: год не должен занимать десять экранов. */
// Вместе с полями карточки строка выходит той же высоты, что и полоса без карточки.
private fun barHeight(days: Int): Dp = (20 + 8 * sqrt(days.toDouble())).coerceAtMost(204.0).dp

@Composable
private fun StayRow(stay: Stay, color: Color, onClick: () -> Unit) {
    val transit = stay.kind == StayKind.Transit
    val title = "${flagEmoji(stay.country)} ${countryName(stay.country)}"
    val dates = formatDateRange(stay.startDate, stay.endDate)
    // Пребывание — карточка; транзит остаётся строкой без подложки.
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
        color = if (transit) Color.Transparent else MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.large,
    ) {
        Column(
            Modifier
                .padding(horizontal = 12.dp, vertical = if (transit) 2.dp else 10.dp)
                .heightIn(min = if (transit) 24.dp else barHeight(stay.minDays))
                .drawBehind { drawStayBar(color, thin = transit, hole = stay.hasHole) }
                .padding(start = 20.dp, top = 4.dp, bottom = 4.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            val muted = MaterialTheme.colorScheme.onSurfaceVariant
            if (transit) {
                // Транзит — тонкая строка: в счёт визитов не идёт.
                Text("${stringResource(Res.string.kind_transit)} · $title · $dates", style = MaterialTheme.typography.bodySmall, color = muted)
            } else {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(Res.string.stay_line, dates, daysRange(stay), stay.photos),
                    style = MaterialTheme.typography.bodySmall,
                    color = muted,
                )
                if (stay.kind == StayKind.ShortVisit) {
                    Text(stringResource(Res.string.kind_short_visit), style = MaterialTheme.typography.bodySmall, color = muted)
                }
                if (stay.hasHole) {
                    Text("┆ " + stringResource(Res.string.hole, stay.holeDays), style = MaterialTheme.typography.bodySmall, color = muted)
                }
            }
        }
    }
}

@Composable
internal fun daysRange(stay: Stay): String =
    if (stay.minDays == stay.maxDays) stringResource(Res.string.days_exact, stay.minDays)
    else stringResource(Res.string.days_range, stay.minDays, stay.maxDays)

/** Цветная полоса слева во всю высоту строки; у пребывания с дырой середина — пунктир. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawStayBar(color: Color, thin: Boolean, hole: Boolean) {
    val w = (if (thin) 2.dp else 8.dp).toPx()
    val x = (8.dp.toPx() - w) / 2
    val radius = CornerRadius(w / 2)
    if (!hole) {
        drawRoundRect(color, Offset(x, 0f), Size(w, size.height), radius)
        return
    }
    val part = size.height * 0.3f
    drawRoundRect(color, Offset(x, 0f), Size(w, part), radius)
    drawRoundRect(color, Offset(x, size.height - part), Size(w, part), radius)
    val gap = 4.dp.toPx()
    drawLine(
        color,
        Offset(x + w / 2, part + gap),
        Offset(x + w / 2, size.height - part - gap),
        strokeWidth = 2.dp.toPx(),
        cap = StrokeCap.Round,
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(gap, gap)),
    )
}

/** Сведения и «Скрыть»; фото и «транзит / не транзит» — следующая очередь. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StayDialog(stay: Stay, onHide: () -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "${flagEmoji(stay.country)} ${countryName(stay.country)}",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            Text(stringResource(Res.string.detail_dates, formatDateRange(stay.startDate, stay.endDate)))
            Text(
                if (stay.minDays == stay.maxDays) stringResource(Res.string.detail_days_exact, stay.minDays)
                else stringResource(Res.string.detail_days_range, stay.minDays, stay.maxDays),
            )
            Text(stringResource(Res.string.detail_photos, stay.photos))
            if (stay.hasHole) Text(stringResource(Res.string.detail_hole, stay.holeDays))
            when (stay.kind) {
                StayKind.Transit -> Text(stringResource(Res.string.detail_transit))
                StayKind.ShortVisit -> Text(stringResource(Res.string.detail_short_visit))
                StayKind.Regular -> Unit
            }
            Text(
                stringResource(Res.string.hide_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
            OutlinedButton(onClick = onHide, modifier = Modifier.align(Alignment.End)) { Text(stringResource(Res.string.hide)) }
        }
    }
}

/** Скрытые пребывания, свежее сверху; «Вернуть» возвращает фото в ленту. */
@Composable
private fun HiddenDialog(hidden: List<HiddenStay>, onUnhide: (HiddenStay) -> Unit, onDismiss: () -> Unit) {
    val sorted = remember(hidden) { hidden.sortedByDescending { it.start } }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.close)) } },
        title = { Text(stringResource(Res.string.hidden_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                sorted.forEach { item ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("${flagEmoji(item.country)} ${countryName(item.country)}")
                            Text(
                                formatDateRange(item.startDate, item.endDate),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(onClick = { onUnhide(item) }) { Text(stringResource(Res.string.unhide)) }
                    }
                }
            }
        },
    )
}
