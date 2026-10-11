package dev.photocountries.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.photocountries.core.CountryTotal
import dev.photocountries.core.Timeline
import dev.photocountries.core.flagEmoji
import dev.photocountries.shared.*
import org.jetbrains.compose.resources.stringResource

/** Карточка «9 стран · 6 лет» и список стран с суммой дней и числом визитов. */
@Composable
fun CountriesScreen(
    timeline: Timeline,
    colors: CountryColors,
    byRecent: Boolean,
    onByRecent: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Страна, где были только транзитом, в заголовок не идёт, но в списке остаётся.
    val visited = remember(timeline) { timeline.totals.count { it.visits > 0 } }
    val years = remember(timeline) { timeline.stays.flatMap { it.startDate.year..it.endDate.year }.distinct().size }
    val rows = remember(timeline, byRecent) {
        if (byRecent) timeline.totals.sortedByDescending { it.lastVisit } else timeline.totals
    }
    LazyColumn(modifier, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        item {
            Surface(
                Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = MaterialTheme.shapes.extraLarge,
            ) {
                Row(Modifier.padding(horizontal = 24.dp, vertical = 20.dp)) {
                    Stat(visited, countriesWord(visited), Modifier.weight(1f))
                    Stat(years, yearsWord(years), Modifier.weight(1f))
                }
            }
        }
        item {
            Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SortChip(!byRecent, stringResource(Res.string.sort_days)) { onByRecent(false) }
                SortChip(byRecent, stringResource(Res.string.sort_recent)) { onByRecent(true) }
            }
        }
        itemsIndexed(rows, key = { _, it -> it.country }) { i, total ->
            CountryRow(total, colors, groupShape(i, rows.size), Modifier.animateItem())
        }
    }
}

@Composable
private fun Stat(value: Int, word: String, modifier: Modifier) {
    Column(modifier) {
        Text(value.toString(), style = MaterialTheme.typography.displaySmall)
        Text(word, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun SortChip(selected: Boolean, label: String, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        leadingIcon = if (selected) ({ Icon(AppIcons.Check, null, Modifier.size(FilterChipDefaults.IconSize)) }) else null,
    )
}

@Composable
private fun CountryRow(total: CountryTotal, colors: CountryColors, shape: Shape, modifier: Modifier) {
    Surface(modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceContainer, shape = shape) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(44.dp).background(MaterialTheme.colorScheme.surfaceContainerHighest, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(flagEmoji(total.country), fontSize = 22.sp)
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(countryName(total.country), style = MaterialTheme.typography.titleMedium)
                val visits = if (total.visits > 0) visitsText(total.visits) else stringResource(Res.string.only_transit)
                Text(
                    "${daysText(total.minDays)} · $visits · ${formatDate(total.lastVisit)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(12.dp))
            // Тот же цвет, что у страны в ленте и на полосах лет.
            Box(Modifier.size(12.dp).background(colors.of(total.country), CircleShape))
        }
    }
}
