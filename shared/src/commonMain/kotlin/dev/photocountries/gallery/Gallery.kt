package dev.photocountries.gallery

import dev.photocountries.core.PhotoPoint

/** Итог просмотра галереи: годные точки и id последнего просмотренного фото — с него начнётся следующий просмотр. */
class GalleryScan(val points: List<PhotoPoint>, val lastId: Long)

/** Разрешения на фото или на их геоданные нет (не дали или отозвали). */
class GalleryDeniedException : Exception("Нет доступа к галерее")

/**
 * Источник точек — галерея телефона. Фото никуда не уходят: из каждого читаются только время и координаты.
 * Есть только на Android; на desktop модель живёт без него.
 */
interface Gallery {
    /**
     * Фото с id больше [afterId] (0 — все), по возрастанию id. [onPage] вызывается пачками: сколько фото
     * уже просмотрено и годные точки этой пачки.
     *
     * @throws GalleryDeniedException нет разрешения.
     */
    suspend fun scan(afterId: Long, onPage: suspend (seen: Int, points: List<PhotoPoint>) -> Unit): GalleryScan
}
