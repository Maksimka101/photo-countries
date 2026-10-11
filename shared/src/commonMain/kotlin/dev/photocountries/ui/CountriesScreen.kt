package dev.photocountries.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.photocountries.StayPhotos
import dev.photocountries.core.CountryTotal
import dev.photocountries.core.Stay
import dev.photocountries.core.Timeline
import dev.photocountries.core.TimelineFacts
import dev.photocountries.core.flagEmoji
import dev.photocountries.core.timelineFacts
import dev.photocountries.shared.*
import org.jetbrains.compose.resources.stringResource

/**
 * Сводка «9 стран · 30 визитов», факты о ленте и список стран с суммой дней и числом визитов.
 * Нажатие на страну открывает шторку с её поездками и фото ([photos]).
 */
@Composable
fun CountriesScreen(
    timeline: Timeline,
    colors: CountryColors,
    byRecent: Boolean,
    onByRecent: (Boolean) -> Unit,
    photos: (Stay) -> StayPhotos,
    bottomPad: Dp,
    modifier: Modifier = Modifier,
) {
    // Страна, где были только транзитом, в заголовок не идёт, но в списке остаётся.
    val visited = remember(timeline) { timeline.totals.count { it.visits > 0 } }
    val visits = remember(timeline) { timeline.totals.sumOf { it.visits } }
    val facts = remember(timeline) { timelineFacts(timeline.stays) }
    val rows = remember(timeline, byRecent) {
        if (byRecent) timeline.totals.sortedByDescending { it.lastVisit } else timeline.totals
    }
    // Полоска доли у каждой страны меряется от самой «долгой».
    val maxDays = remember(timeline) { timeline.totals.maxOfOrNull { it.days } ?: 1 }
    // Страна, чья шторка с поездками и фото открыта.
    var opened by remember { mutableStateOf<String?>(null) }
    LazyColumn(
        modifier,
        contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = bottomPad),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        item { Summary(visited, visits, timeline.totals, colors) }
        if (facts != null) {
            item { Facts(facts, Modifier.padding(top = 4.dp)) }
        }
        item { SortToggle(byRecent, onByRecent, Modifier.padding(top = 12.dp, bottom = 8.dp)) }
        items(rows, key = { it.country }) { total ->
            CountryRow(total, maxDays, colors, onClick = { opened = total.country }, Modifier.animateItem())
        }
    }
    val total = opened?.let { code -> timeline.totals.firstOrNull { it.country == code } }
    if (total != null) {
        val stays = remember(timeline, total.country) { timeline.stays.filter { it.country == total.country } }
        CountrySheet(total, stays, photos, onDismiss = { opened = null })
    }
}

/** Тёмная сводка: два крупных числа и лента «на что ушли дни» цветами стран. */
@Composable
private fun Summary(visited: Int, visits: Int, totals: List<CountryTotal>, colors: CountryColors) {
    Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.extraLarge) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Row {
                Stat(visited, countriesWord(visited), Modifier.weight(1f))
                Stat(visits, visitsWord(visits), Modifier.weight(1f))
            }
            // Страны без своего цвета сливаются в один серый отрезок.
            val own = totals.filter { colors.hasOwn(it.country) && it.days > 0 }
            val otherDays = totals.sumOf { if (colors.hasOwn(it.country)) 0 else it.days }
            Row(Modifier.fillMaxWidth().height(10.dp).clip(CircleShape), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                own.forEach { Box(Modifier.weight(it.days.toFloat()).fillMaxHeight().background(colors.of(it.country))) }
                if (otherDays > 0) Box(Modifier.weight(otherDays.toFloat()).fillMaxHeight().background(colors.other))
            }
        }
    }
}

@Composable
private fun Stat(value: Int, word: String, modifier: Modifier) {
    Column(modifier) {
        Text(value.toString(), style = MaterialTheme.typography.displayMedium)
        Text(
            word.uppercase(),
            style = MaterialTheme.typography.labelLarge,
            letterSpacing = 1.5.sp,
            color = MaterialTheme.colorScheme.tertiaryContainer,
        )
    }
}

/** Столько новых стран показываем флагами, больше — числом. */
private const val MAX_NEW_FLAGS = 6

@Composable
private fun Facts(facts: TimelineFacts, modifier: Modifier) {
    val new = facts.newCountries
    val newText = when {
        new.isEmpty() -> stringResource(Res.string.none)
        new.size <= MAX_NEW_FLAGS -> new.joinToString(" ") { flagEmoji(it) }
        else -> "${new.size} ${countriesWord(new.size)}"
    }
    val longest = facts.longest
    Surface(modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.extraLarge) {
        Column(Modifier.padding(horizontal = 24.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Fact(stringResource(Res.string.fact_new, facts.lastYear), newText)
            Fact(
                stringResource(Res.string.fact_record_year),
                "${facts.recordYear} · ${facts.recordCountries} ${countriesWord(facts.recordCountries)}",
            )
            Fact(
                stringResource(Res.string.fact_longest),
                "${flagEmoji(longest.country)} ${countryName(longest.country)} · ${if (longest.approximate) "≈ " else ""}${daysText(longest.days)}",
            )
        }
    }
}

@Composable
private fun Fact(label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(12.dp))
        Text(value, style = MaterialTheme.typography.titleSmall)
    }
}

/** Переключатель сортировки: две подписи в общей капсуле, выбранная залита. */
@Composable
private fun SortToggle(byRecent: Boolean, onByRecent: (Boolean) -> Unit, modifier: Modifier) {
    Surface(modifier, color = MaterialTheme.colorScheme.surfaceContainer, shape = CircleShape) {
        Row(Modifier.padding(4.dp).selectableGroup()) {
            SortOption(!byRecent, stringResource(Res.string.sort_days)) { onByRecent(false) }
            SortOption(byRecent, stringResource(Res.string.sort_recent)) { onByRecent(true) }
        }
    }
}

@Composable
private fun SortOption(selected: Boolean, label: String, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val fill by animateColorAsState(scheme.primary.copy(alpha = if (selected) 1f else 0f))
    val text by animateColorAsState(if (selected) scheme.onPrimary else scheme.onSurfaceVariant)
    Text(
        label,
        Modifier
            .clip(CircleShape)
            .background(fill)
            .selectable(selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        style = MaterialTheme.typography.labelLarge,
        color = text,
    )
}

@Composable
private fun CountryRow(total: CountryTotal, maxDays: Int, colors: CountryColors, onClick: () -> Unit, modifier: Modifier) {
    val color = colors.of(total.country)
    val visits = if (total.visits > 0) visitsText(total.visits) else stringResource(Res.string.only_transit)
    PlaceCard(
        country = total.country,
        color = color,
        subtitle = "$visits · ${formatDate(total.lastVisit)}",
        days = total.days,
        approximate = total.approximate,
        modifier = modifier.fillMaxWidth(),
        stripe = false,
        onClick = onClick,
    ) {
        // Доля дней относительно самой «долгой» страны.
        val track = MaterialTheme.colorScheme.surfaceContainerHighest
        Box(Modifier.padding(top = 8.dp).fillMaxWidth().height(4.dp).background(track, CircleShape)) {
            Box(
                Modifier
                    .fillMaxWidth((total.days.toFloat() / maxDays).coerceIn(0.02f, 1f))
                    .fillMaxHeight()
                    .background(color, CircleShape),
            )
        }
    }
}
