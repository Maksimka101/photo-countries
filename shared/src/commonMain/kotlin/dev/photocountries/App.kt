package dev.photocountries

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
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
import kotlinx.coroutines.delay
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.todayIn
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Clock

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
                    photos = model::photosOf,
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

/** Список не растягивается на весь широкий экран. */
private val CONTENT_MAX = 640.dp

/** Высота плавающей навигации и её отступ от края экрана. */
private val NAV_HEIGHT = 60.dp
private val NAV_GAP = 12.dp

@Composable
private fun MainScreen(
    state: AppState,
    onReloadAll: () -> Unit,
    onSources: () -> Unit,
    onTab: (Int) -> Unit,
    onSortByRecent: (Boolean) -> Unit,
    onHide: (Stay) -> Unit,
    onUnhide: (HiddenStay) -> Unit,
    photos: (Stay) -> StayPhotos,
) {
    // Вкладка и сортировка стран лежат в модели: они сохраняются между запусками.
    val tab = state.ui.tab.coerceIn(0, DESTINATIONS.lastIndex)
    var askReload by rememberSaveable { mutableStateOf(false) }
    if (askReload) ReloadDialog(onConfirm = { askReload = false; onReloadAll() }, onDismiss = { askReload = false })
    Scaffold(
        topBar = { Header(state, onReload = { askReload = true }, onSources = onSources) },
        containerColor = MaterialTheme.colorScheme.surface,
        // Нижний отступ не здесь: списки уходят под плавающую навигацию и сами оставляют под неё место.
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
    ) { padding ->
        val systemBottom = WindowInsets.safeDrawing.asPaddingValues().calculateBottomPadding()
        val timeline = state.timeline
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
            // В горизонтальной ориентации высоты мало: навигация встаёт слева, содержимое отступает от неё вбок.
            val side = maxWidth > maxHeight
            val navSpace = NAV_HEIGHT + NAV_GAP * 2
            val bottomPad = systemBottom + if (side) 16.dp else navSpace
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
                    tab, timeline, state.ui.sortByRecent, onSortByRecent, state.hidden, onHide, onUnhide, photos, bottomPad,
                    // Полосам лет ширина только на пользу: день шире — страны различимее.
                    Modifier
                        .padding(start = if (side) navSpace else 0.dp)
                        .fillMaxHeight()
                        .widthIn(max = if (tab == 2) Dp.Unspecified else CONTENT_MAX)
                        .fillMaxWidth(),
                )
            }
            if (side) {
                FloatingNav(tab, onTab, vertical = true, Modifier.align(Alignment.CenterStart).padding(start = NAV_GAP))
            } else {
                // Список под навигацией растворяется в фоне, а не обрывается о неё.
                val surface = MaterialTheme.colorScheme.surface
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(bottomPad)
                        .background(Brush.verticalGradient(listOf(surface.copy(alpha = 0f), surface))),
                )
                FloatingNav(tab, onTab, vertical = false, Modifier.align(Alignment.BottomCenter).padding(bottom = systemBottom + NAV_GAP))
            }
        }
    }
}

@Composable
private fun Header(state: AppState, onReload: () -> Unit, onSources: () -> Unit) {
    Column(Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))) {
        Row(Modifier.padding(start = 20.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(Res.string.app_title),
                Modifier.weight(1f),
                style = MaterialTheme.typography.headlineMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            IconButton(onClick = onReload, enabled = !state.loading) {
                Icon(AppIcons.Refresh, stringResource(Res.string.refresh))
            }
            IconButton(onClick = onSources) { Icon(AppIcons.Sources, stringResource(Res.string.sources)) }
        }
        StatusLine(state, Modifier.padding(horizontal = 20.dp).padding(bottom = 8.dp))
        if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
    }
}

/** Сколько имя вкладки висит рядом с боковой навигацией после переключения. */
private const val NAV_HINT_MS = 1200L

/**
 * Навигация — капсула поверх содержимого: снизу или, если [vertical], слева. Снизу выбранная вкладка раскрыта
 * в подпись постоянно. Сбоку подписям места нет: имя вкладки выезжает из капсулы поверх содержимого
 * при открытии вкладки и вскоре прячется.
 */
@Composable
private fun FloatingNav(tab: Int, onTab: (Int) -> Unit, vertical: Boolean, modifier: Modifier) {
    val scheme = MaterialTheme.colorScheme
    var hint by remember { mutableStateOf(false) }
    LaunchedEffect(tab, vertical) {
        hint = true
        delay(NAV_HINT_MS)
        hint = false
    }
    val items = @Composable {
        DESTINATIONS.forEachIndexed { i, dest ->
            NavItem(dest, selected = tab == i, labeled = tab == i && (!vertical || hint), vertical = vertical, onClick = { onTab(i) })
        }
    }
    Box(modifier) {
        // Подложка отдельно от вкладок: Surface обрезал бы выезжающую подпись по своей форме.
        Surface(
            Modifier.matchParentSize(),
            shape = CircleShape,
            color = scheme.primaryContainer,
            shadowElevation = 8.dp,
            border = BorderStroke(1.dp, scheme.onPrimaryContainer.copy(alpha = 0.08f)),
        ) {}
        if (vertical) {
            Column(Modifier.padding(6.dp).width(NAV_HEIGHT - 12.dp).selectableGroup(), verticalArrangement = Arrangement.spacedBy(4.dp)) { items() }
        } else {
            Row(Modifier.padding(6.dp).selectableGroup(), horizontalArrangement = Arrangement.spacedBy(4.dp)) { items() }
        }
    }
}

@Composable
private fun NavItem(dest: Destination, selected: Boolean, labeled: Boolean, vertical: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val title = stringResource(dest.title)
    val pill by animateColorAsState(scheme.tertiaryContainer.copy(alpha = if (selected) 1f else 0f))
    val content by animateColorAsState(if (selected) scheme.onTertiaryContainer else scheme.onPrimaryContainer)
    Row(
        Modifier
            // Сбоку вкладка с подписью шире капсулы и выходит за её правый край.
            .then(if (vertical) Modifier.wrapContentWidth(Alignment.Start, unbounded = true) else Modifier)
            .height(NAV_HEIGHT - 12.dp)
            .clip(CircleShape)
            .background(pill)
            .selectable(selected, role = Role.Tab, onClick = onClick)
            .padding(horizontal = if (vertical) 12.dp else 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Без подписи имя вкладке даёт значок.
        Icon(dest.icon, if (labeled) null else title, tint = content)
        AnimatedVisibility(labeled) {
            Text(title, Modifier.padding(start = 8.dp, end = if (vertical) 4.dp else 0.dp), style = MaterialTheme.typography.labelLarge, color = content, maxLines = 1)
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
    photos: (Stay) -> StayPhotos,
    bottomPad: Dp,
    modifier: Modifier,
) {
    val colors = rememberCountryColors(timeline)
    when (tab) {
        0 -> CountriesScreen(timeline, colors, sortByRecent, onSortByRecent, photos, bottomPad, modifier)
        1 -> TimelineScreen(timeline, colors, hidden, onHide, onUnhide, photos, bottomPad, modifier)
        else -> YearsScreen(timeline, colors, onHide, photos, bottomPad, modifier)
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

/**
 * Строка под заголовком: идёт загрузка либо сколько точек получено и когда обновлялся диск
 * (галерея читается при каждом открытии, её дата ничего не говорит); ошибка — заметной плашкой.
 */
@Composable
private fun StatusLine(state: AppState, modifier: Modifier) {
    val lost = if (state.lostCount > 0) " · " + stringResource(Res.string.status_lost, state.lostCount) else ""
    val fetchedAt = state.fetchedAt
    val load = state.load
    val (text, isError) = when {
        load is LoadStatus.Loading -> when {
            load.stage == LoadStage.Disk -> stringResource(Res.string.status_loading, load.scanned)
            load.stage == LoadStage.Gallery && load.scanned > 0 -> stringResource(Res.string.status_gallery, load.scanned)
            else -> stringResource(Res.string.status_checking)
        } to false
        load is LoadStatus.Error -> when (load.error) {
            LoadError.Unauthorized -> stringResource(Res.string.error_unauthorized)
            LoadError.Failed -> stringResource(Res.string.error_failed)
            LoadError.GalleryDenied -> stringResource(Res.string.error_gallery)
        } to true
        fetchedAt != null && !state.hasToken -> stringResource(Res.string.status_points, state.pointCount) + lost to false
        fetchedAt != null -> {
            val zone = TimeZone.currentSystemDefault()
            val day = fetchedAt.toLocalDateTime(zone).date
            val today = Clock.System.todayIn(zone)
            val date = when (day) {
                today -> stringResource(Res.string.date_today)
                today.minus(1, DateTimeUnit.DAY) -> stringResource(Res.string.date_yesterday)
                else -> formatDate(day)
            }
            stringResource(Res.string.status_updated, date, state.pointCount) + lost to false
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
