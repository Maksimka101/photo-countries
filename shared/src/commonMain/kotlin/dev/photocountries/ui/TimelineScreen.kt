package dev.photocountries.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.photocountries.StayPhotos
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
 * По нажатию открывается шторка пребывания с его фото ([photos]); оттуда же его можно скрыть.
 * Скрытые ([hidden]) возвращаются из списка над лентой.
 */
@Composable
fun TimelineScreen(
    timeline: Timeline,
    colors: CountryColors,
    hidden: List<HiddenStay>,
    onHide: (Stay) -> Unit,
    onUnhide: (HiddenStay) -> Unit,
    photos: (Stay) -> StayPhotos,
    bottomPad: Dp,
    modifier: Modifier = Modifier,
) {
    val byYear = remember(timeline) {
        timeline.stays.withIndex().reversed().groupBy { it.value.startDate.year }
    }
    var selected by remember(timeline) { mutableStateOf<Stay?>(null) }
    var showHidden by remember { mutableStateOf(false) }
    LazyColumn(modifier, contentPadding = PaddingValues(bottom = bottomPad)) {
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
                // Год — крупной цифрой, от неё линия до края: лента читается как разделы.
                Row(
                    Modifier.padding(start = 20.dp, end = 16.dp, top = 24.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(year.toString(), style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.primary)
                    Box(Modifier.padding(start = 16.dp).weight(1f).height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
                }
            }
            items(stays.size, key = { stays[it].index }) { i ->
                val stay = stays[i].value
                StayRow(stay, colors.of(stay.country), onClick = { selected = stay })
            }
        }
    }
    selected?.let { stay -> StaySheet(stay, remember(stay) { photos(stay) }, onHide = { selected = null; onHide(stay) }, onDismiss = { selected = null }) }
    if (showHidden && hidden.isNotEmpty()) HiddenDialog(hidden, onUnhide, onDismiss = { showHidden = false })
}

/** Высота полосы растёт с длительностью, но медленно: год не должен занимать десять экранов. */
// Вместе с полями карточки строка выходит той же высоты, что и полоса без карточки.
private fun barHeight(days: Int): Dp = (20 + 8 * sqrt(days.toDouble())).coerceAtMost(204.0).dp

@Composable
private fun StayRow(stay: Stay, color: Color, onClick: () -> Unit) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val dates = formatDateRange(stay.startDate, stay.endDate)
    val modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp)
    if (stay.kind == StayKind.Transit) {
        // Транзит — тонкая строка без подложки: в счёт визитов не идёт.
        Surface(onClick, modifier, color = Color.Transparent, shape = MaterialTheme.shapes.large) {
            Text(
                "${stringResource(Res.string.kind_transit)} · ${flagEmoji(stay.country)} ${countryName(stay.country)} · $dates",
                Modifier
                    .padding(horizontal = 12.dp, vertical = 2.dp)
                    .heightIn(min = 24.dp)
                    .drawBehind { drawRect(color, Offset(2.dp.toPx(), 0f), Size(2.dp.toPx(), size.height)) }
                    .padding(start = 18.dp, top = 4.dp, bottom = 4.dp),
                style = MaterialTheme.typography.bodySmall,
                color = muted,
            )
        }
    } else {
        // Та же карточка, что в списке стран, только высота растёт с длительностью.
        PlaceCard(
            country = stay.country,
            color = color,
            subtitle = stringResource(Res.string.stay_line, dates, stay.photos),
            days = stay.days,
            approximate = stay.approximate,
            modifier = modifier,
            minHeight = barHeight(stay.days),
            onClick = onClick,
        ) {
            if (stay.kind == StayKind.ShortVisit) {
                Text(stringResource(Res.string.kind_short_visit), style = MaterialTheme.typography.bodySmall, color = muted)
            }
        }
    }
}

/** «12 дн.» или, если в счёт вошли дни без фото, «примерно 12 дн.». */
@Composable
internal fun daysLabel(stay: Stay): String =
    stringResource(if (stay.approximate) Res.string.days_approx else Res.string.days_exact, stay.days)

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
