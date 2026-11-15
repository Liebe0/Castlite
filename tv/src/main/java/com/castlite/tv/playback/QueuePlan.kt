package com.castlite.tv.playback

import com.castlite.protocol.MediaType
import com.castlite.protocol.QueueItem

/**
 * How a queue is played. Normally every item is shown in order. With slideshow music
 * (photos + songs, no videos) the photos are "shown" and the songs become a background
 * playlist. Indices reported to the phone always refer to the full queue.
 */
data class QueuePlan(
    /** Items that are shown one after another. */
    val shown: List<QueueItem>,
    /** For each shown item, its index in the full queue. */
    val fullIndex: List<Int>,
    /** Songs played in the background (slideshow music); empty normally. */
    val music: List<QueueItem>,
) {
    val musicMode: Boolean get() = music.isNotEmpty()

    /** Position in [shown] for a full-queue index, or null if it's background music. */
    fun shownPosition(fullQueueIndex: Int): Int? = fullIndex.indexOf(fullQueueIndex).takeIf { it >= 0 }

    companion object {
        fun of(items: List<QueueItem>, slideshowMusic: Boolean): QueuePlan {
            val photos = items.withIndex().filter { it.value.type == MediaType.IMAGE }
            val songs = items.filter { it.type == MediaType.AUDIO }
            val hasVideo = items.any { it.type == MediaType.VIDEO }
            return if (slideshowMusic && photos.isNotEmpty() && songs.isNotEmpty() && !hasVideo) {
                QueuePlan(photos.map { it.value }, photos.map { it.index }, songs)
            } else {
                QueuePlan(items, items.indices.toList(), emptyList())
            }
        }
    }
}
