package dev.photocountries

import dev.photocountries.core.CountryIndex
import dev.photocountries.core.HiddenStay
import dev.photocountries.core.LocatedPoint
import dev.photocountries.core.PhotoPoint
import dev.photocountries.core.PhotoSource
import dev.photocountries.core.Stay
import dev.photocountries.core.Timeline
import dev.photocountries.core.buildTimeline
import dev.photocountries.core.hiddenIds
import dev.photocountries.core.locatePoints
import dev.photocountries.core.stayPhotos
import dev.photocountries.core.withoutDuplicates
import dev.photocountries.data.AppStorage
import dev.photocountries.data.CachedPoints
import dev.photocountries.data.UiPrefs
import dev.photocountries.gallery.Gallery
import dev.photocountries.gallery.GalleryDeniedException
import dev.photocountries.shared.Res
import dev.photocountries.yandex.UnauthorizedException
import dev.photocountries.yandex.YandexDisk
import dev.photocountries.yandex.diskGet
import dev.photocountries.yandex.tokenFromRedirect
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.concurrent.Volatile
import kotlin.time.Clock
import kotlin.time.Instant

/** Что пошло не так при обновлении; текст для экрана подбирает UI. */
enum class LoadError { Unauthorized, Failed, GalleryDenied }

/** Галерея телефона: на этой платформе её нет, есть, но не подключена, или подключена. */
enum class GalleryStatus { Unavailable, Off, On }

/** Что сейчас читается: [Checking] — ищем новые фото, [Disk] — диск качается целиком, [Gallery] — читается галерея. */
enum class LoadStage { Checking, Disk, Gallery }

/** Обновление: не идёт, идёт или кончилось ошибкой — одновременно не бывает двух. */
sealed interface LoadStatus {
    data object Idle : LoadStatus

    /** [scanned] — сколько файлов просмотрено на этом этапе. */
    data class Loading(val stage: LoadStage = LoadStage.Checking, val scanned: Int = 0) : LoadStatus

    data class Error(val error: LoadError) : LoadStatus
}

/**
 * Состояние приложения. [started] — токен и кэш уже прочитаны (до этого экран источников не показываем).
 * Источников два, нужен хотя бы один: диск ([hasToken]) и галерея телефона ([gallery]).
 * [timeline] null — данных ещё нет; при первой загрузке она неполная и растёт, пока [loading].
 * [hidden] — пребывания, скрытые вручную: в [timeline] их уже нет.
 * [ui] — открытая вкладка и сортировка стран; переживают перезапуск.
 */
data class AppState(
    val started: Boolean = false,
    val hasToken: Boolean = false,
    val gallery: GalleryStatus = GalleryStatus.Unavailable,
    val load: LoadStatus = LoadStatus.Idle,
    val fetchedAt: Instant? = null,
    val pointCount: Int = 0,
    val lostCount: Int = 0,
    val timeline: Timeline? = null,
    val hidden: List<HiddenStay> = emptyList(),
    val ui: UiPrefs = UiPrefs(),
) {
    /** Подключён хотя бы один источник. */
    val connected: Boolean get() = hasToken || gallery == GalleryStatus.On

    val loading: Boolean get() = load is LoadStatus.Loading
}

/** Фото одного пребывания, свежие первыми. [diskToken] — токен диска: без него его превью не отдаются. */
class StayPhotos(val photos: List<PhotoPoint>, val diskToken: String?)

/**
 * Держит данные дольше экрана: токен, кэш точек, ленту. Живёт в Application (на desktop — в `main`).
 * [gallery] — галерея телефона, null там, где её нет. [loadCountries] по умолчанию читает полигоны из ресурсов.
 */
class AppModel(
    private val storage: AppStorage,
    private val scope: CoroutineScope,
    private val disk: YandexDisk = YandexDisk(::diskGet),
    private val gallery: Gallery? = null,
    private val loadCountries: suspend () -> CountryIndex = { CountryIndex.parse(Res.readBytes("files/countries.bin")) },
) {
    private val _state = MutableStateFlow(AppState(gallery = if (gallery != null) GalleryStatus.Off else GalleryStatus.Unavailable))
    val state: StateFlow<AppState> = _state

    // Токен, точки и скрытое читает ещё и экран ([photosOf]), из своего потока.
    @Volatile
    private var token: String? = null

    // null — галерея не подключена; иначе id последнего просмотренного в ней фото.
    private var galleryAfter: Long? = null

    // Кэш как есть: точки обоих источников, дубли между ними убираются только при сборке ленты.
    private var points: List<PhotoPoint> = emptyList()
    private var countries: CountryIndex? = null
    private var startJob: Job? = null
    private var refreshJob: Job? = null

    // Источник подключили, пока шло обновление: после него нужно ещё одно.
    private var queued = false

    // Точки последней полной ленты уже со странами: скрытие пересобирает ленту из них.
    @Volatile
    private var located: List<LocatedPoint> = emptyList()

    @Volatile
    private var hidden: List<HiddenStay> = emptyList()

    /** Читает токен и кэш; если источник есть — сразу обновляет. Повторные вызовы ничего не делают. */
    fun start() {
        if (startJob != null) return
        startJob = scope.launch {
            token = storage.loadToken()
            if (gallery != null) galleryAfter = storage.loadGallery()
            hidden = storage.loadHidden()
            val ui = storage.loadUi()
            _state.update { it.copy(hidden = hidden, ui = ui) }
            val cached = storage.loadPoints()
            if (cached != null) show(cached)
            _state.update { it.copy(started = true, hasToken = token != null, gallery = galleryStatus()) }
            refresh()
        }
    }

    /** Адрес возврата из браузера после входа. false — токена в нём нет. */
    fun handleRedirect(uri: String): Boolean {
        val t = tokenFromRedirect(uri) ?: return false
        setToken(t)
        return true
    }

    /** Подключает диск: запоминает токен (из входа или вставленный руками) и качает точки. */
    fun setToken(value: String) {
        val t = value.trim()
        if (t.isEmpty()) return
        scope.launch {
            startJob?.join()
            token = t
            storage.saveToken(t)
            _state.update { it.copy(hasToken = true, load = it.load.withoutError()) }
            load(full = false, queue = true)
        }
    }

    /**
     * Подключает галерею и читает её. Разрешение к этому моменту уже спрошено экраном; если его нет,
     * обновление кончится ошибкой [LoadError.GalleryDenied]. Для уже подключённой — просто обновление.
     */
    fun connectGallery() {
        if (gallery == null) return
        scope.launch {
            startJob?.join()
            if (galleryAfter == null) {
                galleryAfter = 0
                storage.saveGallery(0)
            }
            _state.update { it.copy(gallery = GalleryStatus.On, load = it.load.withoutError()) }
            load(full = false, queue = true)
        }
    }

    /** Отключает диск: забывает токен и убирает его фото из ленты. */
    fun logout() = disconnect(PhotoSource.YandexDisk)

    /** Отключает галерею и убирает её фото из ленты. */
    fun disconnectGallery() = disconnect(PhotoSource.Gallery)

    private fun disconnect(source: PhotoSource) {
        scope.launch {
            startJob?.join()
            // Идущее обновление держит старые списки точек — сначала останавливаем его.
            queued = false
            refreshJob?.cancelAndJoin()
            when (source) {
                PhotoSource.YandexDisk -> { token = null; storage.saveToken(null) }
                PhotoSource.Gallery -> { galleryAfter = null; storage.saveGallery(null) }
            }
            val cached = CachedPoints(_state.value.fetchedAt ?: Clock.System.now(), points.filter { it.source != source })
            storage.savePoints(cached)
            show(cached)
            _state.update { it.copy(hasToken = token != null, gallery = galleryStatus(), load = LoadStatus.Idle) }
            refresh()
        }
    }

    fun setTab(tab: Int) = setUi { it.copy(tab = tab) }

    fun setSortByRecent(value: Boolean) = setUi { it.copy(sortByRecent = value) }

    /** Экран меняется сразу, запись на диск — следом в корутине модели. */
    private fun setUi(change: (UiPrefs) -> UiPrefs) {
        val before = _state.value.ui
        val ui = _state.updateAndGet { it.copy(ui = change(it.ui)) }.ui
        if (ui != before) scope.launch { storage.saveUi(_state.value.ui) }
    }

    /** Убирает пребывание из ленты и всех итогов — для чужих фото. Вернуть — [unhide]. */
    fun hide(stay: Stay) = setHidden { it + HiddenStay(stay.country, stay.start, stay.end) }

    fun unhide(item: HiddenStay) = setHidden { it - item }

    private fun setHidden(change: (List<HiddenStay>) -> List<HiddenStay>) {
        scope.launch {
            startJob?.join()
            hidden = change(hidden).distinct()
            storage.saveHidden(hidden)
            _state.update { it.copy(hidden = hidden) }
            // Пока идёт первая загрузка, полной ленты ещё нет: скрытие учтёт следующая страница.
            if (located.isNotEmpty()) rebuild()
        }
    }

    /** Фото пребывания — когда его открыли в ленте. Сами картинки грузит экран. */
    fun photosOf(stay: Stay): StayPhotos = StayPhotos(stayPhotos(located, stay, hidden), token)

    /** Добавляет фото, появившиеся в источниках после прошлого обновления; без кэша читает всё. */
    fun refresh() { load(full = false) }

    /** Перечитывает оба источника целиком и заменяет кэш: так из ленты уходят удалённые фото. */
    fun reloadAll() { load(full = true) }

    /**
     * Пока идёт предыдущее обновление, новое не запускается; с [queue] оно встаёт в очередь за ним.
     * Зовут и с главного потока, поэтому проверка и запуск — в корутине модели.
     */
    private fun load(full: Boolean, queue: Boolean = false) = scope.launch {
        if (token == null && galleryAfter == null) return@launch
        if (refreshJob?.isActive == true) {
            if (queue) queued = true
            return@launch
        }
        refreshJob = scope.launch {
            var wantFull = full
            do {
                queued = false
                update(wantFull)
                wantFull = false
            } while (queued)
        }
    }

    /**
     * Одно обновление обоих источников. Ошибка одного не мешает другому: что удалось получить —
     * сохраняется, ошибка остаётся в состоянии.
     */
    private suspend fun update(full: Boolean) {
        val t = token
        val after = galleryAfter
        _state.update { it.copy(load = LoadStatus.Loading()) }
        // Пока показывать нечего, лента растёт по страницам. Поверх прошлой полной ленты
        // неполную не ставим — она сменится разом в конце.
        val preview = if (points.isEmpty()) Preview() else null
        var diskPoints = points.filter { it.source == PhotoSource.YandexDisk }
        var galleryPoints = points.filter { it.source == PhotoSource.Gallery }
        var newAfter = after
        var error: LoadError? = null
        var diskRead = false
        if (t != null) {
            try {
                diskPoints = loadDisk(t, full, diskPoints, preview)
                diskRead = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = if (e is UnauthorizedException) LoadError.Unauthorized else LoadError.Failed
            }
        }
        if (gallery != null && after != null) {
            try {
                val from = if (full) 0 else after
                _state.update { it.copy(load = LoadStatus.Loading(LoadStage.Gallery)) }
                val scan = gallery.scan(from) { seen, page ->
                    _state.update { it.copy(load = LoadStatus.Loading(LoadStage.Gallery, seen)) }
                    preview?.add(page)
                }
                // Просмотр с начала заменяет точки галереи, иначе новые добавляются к прежним.
                val fresh = scan.points.mapTo(HashSet()) { it.id }
                galleryPoints = if (from == 0L) scan.points else galleryPoints.filter { it.id !in fresh } + scan.points
                newAfter = scan.lastId
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (error == null) error = if (e is GalleryDeniedException) LoadError.GalleryDenied else LoadError.Failed
            }
        }
        val merged = diskPoints + galleryPoints
        val same = merged == points
        // С ошибкой и без новых точек сохранять нечего. Дата «обновлено» — про диск: без него она прежняя.
        if (!same || error == null) {
            val now = Clock.System.now()
            val cached = CachedPoints(if (diskRead) now else _state.value.fetchedAt ?: now, merged)
            storage.savePoints(cached)
            if (newAfter != after) {
                galleryAfter = newAfter
                storage.saveGallery(newAfter)
            }
            if (same && preview == null) _state.update { it.copy(fetchedAt = cached.fetchedAt) } else show(cached)
        }
        _state.update { it.copy(load = if (error == null) LoadStatus.Idle else LoadStatus.Error(error)) }
    }

    /** Точки диска после обновления: прежние плюс новые либо, если так не выходит, скачанные заново. */
    private suspend fun loadDisk(t: String, full: Boolean, old: List<PhotoPoint>, preview: Preview?): List<PhotoPoint> {
        // Без кэша добавлять не к чему.
        if (!full && old.isNotEmpty()) {
            val fresh = disk.fetchNew(t, old.mapTo(HashSet()) { it.id })
            if (fresh != null) return old + fresh
            // До знакомых фото не дошли — качаем всё.
        }
        _state.update { it.copy(load = LoadStatus.Loading(LoadStage.Disk)) }
        return disk.fetchPoints(t) { seen, page ->
            _state.update { it.copy(load = LoadStatus.Loading(LoadStage.Disk, seen)) }
            preview?.add(page)
        }
    }

    /** Неполная лента первой загрузки: растёт с каждой страницей точек. */
    private inner class Preview {
        private val partial = ArrayList<LocatedPoint>()
        private var lost = 0

        suspend fun add(page: List<PhotoPoint>) {
            if (page.isEmpty()) return
            val index = countryIndex()
            val hide = hidden
            val timeline = withContext(Dispatchers.Default) {
                val part = locatePoints(page, index)
                partial += part.points
                lost += part.lost
                buildTimeline(partial, hiddenIds(partial, hide))
            }
            _state.update { it.copy(pointCount = partial.size, lostCount = lost, timeline = timeline) }
        }
    }

    private fun galleryStatus() = when {
        gallery == null -> GalleryStatus.Unavailable
        galleryAfter == null -> GalleryStatus.Off
        else -> GalleryStatus.On
    }

    /** Ошибку прошлого обновления убираем, идущее не трогаем. */
    private fun LoadStatus.withoutError() = if (this is LoadStatus.Error) LoadStatus.Idle else this

    private suspend fun countryIndex(): CountryIndex = countries ?: loadCountries().also { countries = it }

    private suspend fun show(cached: CachedPoints) {
        points = cached.points
        val index = countryIndex()
        val result = withContext(Dispatchers.Default) {
            val (fromDisk, fromGallery) = cached.points.partition { it.source == PhotoSource.YandexDisk }
            // Из двух копий остаётся та, что в галерее: её показ не требует сети.
            locatePoints(withoutDuplicates(fromDisk, fromGallery) + fromGallery, index)
        }
        located = result.points
        _state.update { it.copy(fetchedAt = cached.fetchedAt, pointCount = result.points.size, lostCount = result.lost) }
        rebuild()
    }

    private suspend fun rebuild() {
        val points = located
        val hide = hidden
        val timeline = withContext(Dispatchers.Default) { buildTimeline(points, hiddenIds(points, hide)) }
        _state.update { it.copy(timeline = timeline) }
    }
}
