package dev.photocountries

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.photocountries.core.HiddenStay
import dev.photocountries.core.Stay
import dev.photocountries.core.Timeline
import dev.photocountries.shared.*
import dev.photocountries.ui.AppIcons
import dev.photocountries.ui.CountriesScreen
import dev.photocountries.ui.PhotoTheme
import dev.photocountries.ui.SourcesScreen
import dev.photocountries.ui.TimelineScreen
import dev.photocountries.ui.YearsScreen
import dev.photocountries.ui.formatDate
import dev.photocountries.ui.rememberCountryColors
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** Приложение целиком. Платформа даёт [model] — то, что живёт дольше экрана. */
@Composable
fun App(model: AppModel) {
    PhotoTheme {
        val state by model.state.collectAsState()
        // Экран источников открывается сам, пока не подключён ни один, и по кнопке — чтобы добавить второй.
        // Закрывает его только «Готово»: после первого подключения можно сразу подключить и второй.
        var sourcesRequested by rememberSaveable { mutableStateOf(false) }
        if (state.started && !state.connected) sourcesRequested = true
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            when {
                !state.started -> Unit
                !state.connected || sourcesRequested -> SourcesScreen(
                    state = state,
                    onToken = model::setToken,
                    onDiskOff = model::logout,
                    onGalleryOn = model::connectGallery,
                    onGalleryOff = model::disconnectGallery,
                    onDone = if (state.connected) ({ sourcesRequested = false }) else null,
                )
                else -> MainScreen(
                    state = state,
                    onReloadAll = model::reloadAll,
                    onSources = { sourcesRequested = true },
                    onTab = model::setTab,
                    onSortByRecent = model::setSortByRecent,
                    onHide = model::hide,
                    onUnhide = model::unhide,
                )
            }
        }
    }
}

private class Destination(val title: StringResource, val icon: ImageVector)

private val DESTINATIONS = listOf(
    Destination(Res.string.tab_countries, AppIcons.Globe),
    Destination(Res.string.tab_timeline, AppIcons.List),
    Destination(Res.string.tab_years, AppIcons.Calendar),
)

/** Шире этого (раскрытый телефон, планшет, окно на desktop) навигация уходит с нижней панели в боковую. */
private val WIDE = 600.dp

/** Список не растягивается на весь широкий экран. */
private val CONTENT_MAX = 640.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScreen(
    state: AppState,
    onReloadAll: () -> Unit,
    onSources: () -> Unit,
    onTab: (Int) -> Unit,
    onSortByRecent: (Boolean) -> Unit,
    onHide: (Stay) -> Unit,
    onUnhide: (HiddenStay) -> Unit,
) {
    // Вкладка и сортировка стран лежат в модели: они сохраняются между запусками.
    val tab = state.ui.tab.coerceIn(0, DESTINATIONS.lastIndex)
    var askReload by rememberSaveable { mutableStateOf(false) }
    if (askReload) ReloadDialog(onConfirm = { askReload = false; onReloadAll() }, onDismiss = { askReload = false })
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= WIDE
        Scaffold(
            topBar = {
                Column {
                    TopAppBar(
                        title = { Text(stringResource(Res.string.app_title), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        actions = {
                            IconButton(onClick = { askReload = true }, enabled = !state.loading) {
                                Icon(AppIcons.Refresh, stringResource(Res.string.refresh))
                            }
                            IconButton(onClick = onSources) { Icon(AppIcons.Sources, stringResource(Res.string.sources)) }
                        },
                        windowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
                    )
                    StatusLine(
                        state,
                        Modifier
                            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                            .padding(horizontal = 16.dp)
                            .padding(bottom = 8.dp),
                    )
                    if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            },
            bottomBar = {
                if (!wide) {
                    NavigationBar {
                        DESTINATIONS.forEachIndexed { i, dest ->
                            NavigationBarItem(
                                selected = tab == i,
                                onClick = { onTab(i) },
                                icon = { Icon(dest.icon, null) },
                                label = { Text(stringResource(dest.title)) },
                            )
                        }
                    }
                }
            },
            contentWindowInsets = WindowInsets.safeDrawing,
        ) { padding ->
            Row(Modifier.fillMaxSize().padding(padding)) {
                if (wide) {
                    // Отступы от системных панелей уже учтены в padding.
                    NavigationRail(windowInsets = WindowInsets(0, 0, 0, 0)) {
                        DESTINATIONS.forEachIndexed { i, dest ->
                            NavigationRailItem(
                                selected = tab == i,
                                onClick = { onTab(i) },
                                icon = { Icon(dest.icon, null) },
                                label = { Text(stringResource(dest.title)) },
                            )
                        }
                    }
                }
                val timeline = state.timeline
                Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                    when {
                        // Во время загрузки пустая лента — ещё не «ничего не нашлось».
                        timeline == null || (state.loading && timeline.stays.isEmpty()) ->
                            if (state.loading) CircularProgressIndicator()
                        // Если скрыто всё, вкладки остаются: вернуть скрытое можно только с таймлайна.
                        timeline.stays.isEmpty() && state.hidden.isEmpty() -> Text(
                            stringResource(Res.string.empty),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        else -> Tabs(
                            tab, timeline, state.ui.sortByRecent, onSortByRecent, state.hidden, onHide, onUnhide,
                            Modifier.fillMaxHeight().widthIn(max = CONTENT_MAX).fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Tabs(
    tab: Int,
    timeline: Timeline,
    sortByRecent: Boolean,
    onSortByRecent: (Boolean) -> Unit,
    hidden: List<HiddenStay>,
    onHide: (Stay) -> Unit,
    onUnhide: (HiddenStay) -> Unit,
    modifier: Modifier,
) {
    val colors = rememberCountryColors(timeline)
    when (tab) {
        0 -> CountriesScreen(timeline, colors, sortByRecent, onSortByRecent, modifier)
        1 -> TimelineScreen(timeline, colors, hidden, onHide, onUnhide, modifier)
        else -> YearsScreen(timeline, colors, modifier)
    }
}

/** Полная перекачка — отдельное действие: новые фото приходят сами, а она нужна ради удалённых. */
@Composable
private fun ReloadDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(Res.string.reload_confirm)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.cancel)) } },
        title = { Text(stringResource(Res.string.reload_title)) },
        text = { Text(stringResource(Res.string.reload_text)) },
    )
}

/** Строка под заголовком: идёт загрузка либо когда и сколько точек получено; ошибка — заметной плашкой. */
@Composable
private fun StatusLine(state: AppState, modifier: Modifier) {
    val fetchedAt = state.fetchedAt
    val (text, isError) = when {
        state.loading && state.full -> stringResource(Res.string.status_loading, state.scanned) to false
        state.loading && state.scanningGallery && state.scanned > 0 ->
            stringResource(Res.string.status_gallery, state.scanned) to false
        state.loading -> stringResource(Res.string.status_checking) to false
        state.error == LoadError.Unauthorized -> stringResource(Res.string.error_unauthorized) to true
        state.error == LoadError.Failed -> stringResource(Res.string.error_failed) to true
        state.error == LoadError.GalleryDenied -> stringResource(Res.string.error_gallery) to true
        fetchedAt != null -> {
            val date = formatDate(fetchedAt.toLocalDateTime(TimeZone.currentSystemDefault()).date)
            val base = stringResource(Res.string.status_updated, date, state.pointCount)
            (if (state.lostCount > 0) "$base · " + stringResource(Res.string.status_lost, state.lostCount) else base) to false
        }
        else -> return
    }
    if (isError) {
        Surface(modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.medium) {
            Text(text, Modifier.padding(horizontal = 16.dp, vertical = 10.dp), style = MaterialTheme.typography.bodyMedium)
        }
    } else {
        Text(
            text,
            modifier = modifier,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
        )
    }
}
