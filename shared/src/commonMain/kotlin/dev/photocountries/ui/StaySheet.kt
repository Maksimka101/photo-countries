package dev.photocountries.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import androidx.compose.ui.window.Dialog
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import coil3.request.crossfade
import dev.photocountries.StayPhotos
import dev.photocountries.core.CountryTotal
import dev.photocountries.core.PhotoPoint
import dev.photocountries.core.PhotoSource
import dev.photocountries.core.Stay
import dev.photocountries.core.StayKind
import dev.photocountries.core.flagEmoji
import dev.photocountries.core.utcDate
import dev.photocountries.shared.*
import kotlin.math.abs
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/** Размеры превью диска: длинная сторона 500 пикселей для сетки и 1280 — на весь экран. */
private const val THUMB = "L"
private const val FULL = "XXXL"

private val SIZE_PARAM = Regex("([?&]size=)[^&]*")

/**
 * Адрес картинки для Coil. Фото галереи — `content://` по номеру из MediaStore (он лежит в id после `media:`),
 * фото диска — ссылка превью с размером [diskSize]. null — показать нечем.
 */
internal fun PhotoPoint.imageUrl(diskSize: String): String? = when (source) {
    PhotoSource.Gallery -> "content://media/external/images/media/${id.removePrefix("media:")}"
    PhotoSource.YandexDisk -> preview?.let { url -> SIZE_PARAM.replace(url) { it.groupValues[1] + diskSize } }
}

/**
 * Пока фото грузится, на его месте [spinner] — тонкое кольцо (для фото на весь экран) либо, в сетке, мерцающая
 * подложка: полсотни колец разом рябят. Не загрузилось (удалено, нет сети) — остаётся пустая подложка.
 */
@Composable
private fun PhotoImage(
    photo: PhotoPoint,
    diskSize: String,
    diskToken: String?,
    scale: ContentScale,
    spinner: Boolean,
    modifier: Modifier,
) {
    val context = LocalPlatformContext.current
    val request = remember(photo, diskSize, diskToken) {
        ImageRequest.Builder(context)
            .data(photo.imageUrl(diskSize))
            .apply {
                if (photo.source == PhotoSource.YandexDisk && diskToken != null) {
                    httpHeaders(NetworkHeaders.Builder().set("Authorization", "OAuth $diskToken").build())
                }
            }
            .crossfade(true)
            .build()
    }
    var loading by remember(request) { mutableStateOf(true) }
    Box(modifier) {
        AsyncImage(
            request,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = scale,
            onSuccess = { loading = false },
            onError = { loading = false },
        )
        if (loading) {
            if (spinner) {
                CircularProgressIndicator(
                    Modifier.align(Alignment.Center).size(28.dp),
                    color = Color.White.copy(alpha = 0.7f),
                    strokeWidth = 2.dp,
                )
            } else {
                val pulse by rememberInfiniteTransition().animateFloat(
                    0f, 0.1f, infiniteRepeatable(tween(700), RepeatMode.Reverse),
                )
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.onSurface.copy(alpha = pulse)))
            }
        }
    }
}

/**
 * Шторка пребывания: сведения, «Скрыть» и его фото по дням, свежие сверху (дни по UTC, как вся лента). Фото грузятся, только пока
 * шторка открыта; нажатие открывает фото на весь экран.
 */
@Composable
internal fun StaySheet(stay: Stay, photos: StayPhotos, onHide: () -> Unit, onDismiss: () -> Unit) {
    val sections = remember(photos) {
        photos.photos.withIndex().groupBy { it.value.time.utcDate() }.map { (day, list) -> PhotoSection(formatDate(day), null, list) }
    }
    PhotoSheet(sections, photos, onDismiss) { StayHeader(stay, onHide) }
}

/**
 * Шторка страны: итоги и все её фото по поездкам, свежие сверху. [stays] — пребывания этой страны в порядке
 * ленты; транзиты идут наравне с поездками, с пометкой.
 */
@Composable
internal fun CountrySheet(total: CountryTotal, stays: List<Stay>, photos: (Stay) -> StayPhotos, onDismiss: () -> Unit) {
    val trips = remember(stays) { stays.reversed().map { it to photos(it) } }
    val all = remember(trips) { StayPhotos(trips.flatMap { it.second.photos }, trips.firstOrNull()?.second?.diskToken) }
    var index = 0
    val sections = trips.map { (stay, own) ->
        val kind = when (stay.kind) {
            StayKind.Transit -> stringResource(Res.string.kind_transit) + " · "
            StayKind.ShortVisit -> stringResource(Res.string.kind_short_visit) + " · "
            StayKind.Regular -> ""
        }
        val first = index
        index += own.photos.size
        PhotoSection(
            title = formatDateRange(stay.startDate, stay.endDate),
            note = kind + stringResource(Res.string.stay_line, daysLabel(stay), stay.photos),
            photos = own.photos.mapIndexed { i, photo -> IndexedValue(first + i, photo) },
        )
    }
    PhotoSheet(sections, all, onDismiss) { CountryHeader(total, stays, all.photos.size) }
}

@Composable
private fun CountryHeader(total: CountryTotal, stays: List<Stay>, photos: Int) {
    val visits = if (total.visits > 0) visitsText(total.visits) else stringResource(Res.string.only_transit)
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            "${flagEmoji(total.country)} ${countryName(total.country)}",
            Modifier.padding(bottom = 4.dp),
            style = MaterialTheme.typography.headlineSmall,
        )
        Text("$visits · ${if (total.approximate) "≈ " else ""}${daysText(total.days)}")
        if (stays.isNotEmpty()) {
            Text(stringResource(Res.string.country_span, formatDate(stays.first().startDate), formatDate(stays.last().endDate)))
        }
        Text(stringResource(Res.string.detail_photos, photos))
    }
}

/** Раздел сетки: заголовок, приписка под ним и фото с их номерами в общем списке просмотра. */
private class PhotoSection(val title: String, val note: String?, val photos: List<IndexedValue<PhotoPoint>>)

/** Общая шторка с фото: [header], дальше [sections]; просмотр на весь экран листает все фото [photos] подряд. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PhotoSheet(sections: List<PhotoSection>, photos: StayPhotos, onDismiss: () -> Unit, header: @Composable () -> Unit) {
    var opened by remember { mutableStateOf<Int?>(null) }
    // Сетка уходит под системную навигацию; место под неё — отступ в конце списка, а не обрез шторки.
    val systemBottom = WindowInsets.safeDrawing.asPaddingValues().calculateBottomPadding()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        contentWindowInsets = { WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal) },
    ) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(96.dp),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp + systemBottom),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) { header() }
            sections.forEach { section ->
                val list = section.photos
                item(span = { GridItemSpan(maxLineSpan) }) {
                    val muted = MaterialTheme.colorScheme.onSurfaceVariant
                    // С припиской это поездка — заголовок крупнее, чем у дня.
                    Column(Modifier.padding(start = 8.dp, top = if (section.note != null) 20.dp else 12.dp, bottom = 4.dp)) {
                        if (section.note != null) {
                            Text(section.title, style = MaterialTheme.typography.titleMedium)
                            Text(section.note, style = MaterialTheme.typography.bodySmall, color = muted)
                        } else {
                            Text(section.title, style = MaterialTheme.typography.labelLarge, color = muted)
                        }
                    }
                }
                items(list.size) { i ->
                    PhotoImage(
                        list[i].value, THUMB, photos.diskToken, ContentScale.Crop, spinner = false,
                        Modifier
                            .aspectRatio(1f)
                            .clip(MaterialTheme.shapes.extraSmall)
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                            .clickable { opened = list[i].index },
                    )
                }
            }
        }
    }
    opened?.let { PhotoViewer(photos, it, onDismiss = { opened = null }) }
}

/** Что значит «Скрыть», объясняет подсказка под знаком вопроса: постоянно она только занимала бы место. */
@Composable
private fun StayHeader(stay: Stay, onHide: () -> Unit) {
    var help by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${flagEmoji(stay.country)} ${countryName(stay.country)}",
                Modifier.weight(1f),
                style = MaterialTheme.typography.headlineSmall,
            )
            TextButton(onClick = onHide) { Text(stringResource(Res.string.hide)) }
            IconButton(onClick = { help = !help }) {
                Icon(AppIcons.Help, stringResource(Res.string.hide_help), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        AnimatedVisibility(help) {
            Text(
                stringResource(Res.string.hide_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(stringResource(Res.string.detail_dates, formatDateRange(stay.startDate, stay.endDate)))
        Text(stringResource(if (stay.approximate) Res.string.detail_days_approx else Res.string.detail_days_exact, stay.days))
        Text(stringResource(Res.string.detail_photos, stay.photos))
        when (stay.kind) {
            StayKind.Transit -> Text(stringResource(Res.string.detail_transit))
            StayKind.ShortVisit -> Text(stringResource(Res.string.detail_short_visit))
            StayKind.Regular -> Unit
        }
    }
}

/** Во сколько раз увеличивает двойное нажатие и предел для щипка. */
private const val DOUBLE_TAP_ZOOM = 2.5f
private const val MAX_ZOOM = 6f

/** Сколько протащить фото вверх или вниз, чтобы просмотр закрылся. */
private val DISMISS_DRAG = 96.dp

/**
 * Фото на весь экран, начиная с [start]; листаются вбок по всему пребыванию. Закрывает просмотр свайп
 * вверх или вниз (и «назад»), нажатие — нет: двойное увеличивает.
 */
@Composable
private fun PhotoViewer(photos: StayPhotos, start: Int, onDismiss: () -> Unit) {
    val list = photos.photos
    Dialog(onDismissRequest = onDismiss, properties = fullScreenDialogProperties()) {
        val pager = rememberPagerState(start) { list.size }
        val scope = rememberCoroutineScope()
        val limit = with(LocalDensity.current) { DISMISS_DRAG.toPx() }
        var drag by remember { mutableFloatStateOf(0f) }
        // Фон светлеет вслед за пальцем: видно, что фото сейчас закроется.
        val fade = (1f - abs(drag) / (limit * 4)).coerceIn(0.4f, 1f)
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = fade))) {
            HorizontalPager(pager, Modifier.fillMaxSize().graphicsLayer { translationY = drag }) { page ->
                ZoomablePhoto(
                    list[page], photos.diskToken,
                    onDrag = { drag += it },
                    onDragEnd = {
                        if (abs(drag) > limit) onDismiss() else scope.launch { animate(drag, 0f) { value, _ -> drag = value } }
                    },
                )
            }
            Text(
                stringResource(Res.string.photo_position, formatDate(list[pager.currentPage].time.utcDate()), pager.currentPage + 1, list.size),
                Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.safeDrawing).padding(24.dp),
                style = MaterialTheme.typography.labelLarge,
                color = Color.White,
            )
        }
    }
}

/** Чем стал жест одним пальцем: ещё неясно, увеличение или сдвиг, свайп закрытия, листание (его ведёт пейджер). */
private enum class Gesture { Undecided, Zoom, Dismiss, Page }

/**
 * Одно фото просмотра. Щипок увеличивает до [MAX_ZOOM], двойное нажатие — до [DOUBLE_TAP_ZOOM] и обратно;
 * увеличенное фото двигается пальцем и не листается. Без увеличения вертикальный жест уходит в [onDrag]
 * (сдвиг в пикселях) и [onDragEnd], горизонтальный остаётся пейджеру.
 */
@Composable
private fun ZoomablePhoto(photo: PhotoPoint, diskToken: String?, onDrag: (Float) -> Unit, onDragEnd: () -> Unit) {
    var scale by remember { mutableFloatStateOf(1f) }
    // Сдвиг увеличенного фото от центра.
    var offset by remember { mutableStateOf(Offset.Zero) }
    val scope = rememberCoroutineScope()
    val drag by rememberUpdatedState(onDrag)
    val dragEnd by rememberUpdatedState(onDragEnd)

    // Сдвиг после смены масштаба на [target]: точка [around] остаётся под пальцем, края фото не уходят внутрь экрана.
    fun offsetAt(target: Float, around: Offset, pan: Offset, size: IntSize): Offset {
        val fromCenter = around - Offset(size.width / 2f, size.height / 2f)
        val moved = (offset - fromCenter) * (target / scale) + fromCenter + pan
        val maxX = size.width * (target - 1f) / 2f
        val maxY = size.height * (target - 1f) / 2f
        return Offset(moved.x.coerceIn(-maxX, maxX), moved.y.coerceIn(-maxY, maxY))
    }

    PhotoImage(
        photo, FULL, diskToken, ContentScale.Fit, spinner = true,
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = { tap ->
                    val fromScale = scale
                    val fromOffset = offset
                    val toScale = if (scale > 1f) 1f else DOUBLE_TAP_ZOOM
                    val toOffset = if (toScale == 1f) Offset.Zero else offsetAt(toScale, tap, Offset.Zero, size)
                    scope.launch {
                        animate(0f, 1f) { part, _ ->
                            scale = lerp(fromScale, toScale, part)
                            offset = lerp(fromOffset, toOffset, part)
                        }
                    }
                })
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    var gesture = if (scale > 1f) Gesture.Zoom else Gesture.Undecided
                    var moved = Offset.Zero
                    do {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.count { it.pressed }
                        // Второй палец — всегда щипок, даже если первый уже начал листать.
                        if (pressed > 1 && gesture != Gesture.Dismiss) gesture = Gesture.Zoom
                        val pan = event.calculatePan()
                        if (gesture == Gesture.Undecided) {
                            moved += pan
                            if (moved.getDistance() > viewConfiguration.touchSlop) {
                                gesture = if (abs(moved.y) > abs(moved.x)) Gesture.Dismiss else Gesture.Page
                            }
                        }
                        when (gesture) {
                            Gesture.Zoom -> if (pressed > 0) {
                                val target = (scale * event.calculateZoom()).coerceIn(1f, MAX_ZOOM)
                                offset = offsetAt(target, event.calculateCentroid(useCurrent = false), pan, size)
                                scale = target
                                event.changes.forEach { if (it.positionChanged()) it.consume() }
                            }
                            Gesture.Dismiss -> {
                                drag(pan.y)
                                event.changes.forEach { if (it.positionChanged()) it.consume() }
                            }
                            Gesture.Undecided, Gesture.Page -> Unit
                        }
                    } while (event.changes.any { it.pressed })
                    if (gesture == Gesture.Dismiss) dragEnd()
                }
            }
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offset.x
                translationY = offset.y
            },
    )
}
