package com.castlite.tv.playback

import com.castlite.protocol.QueueItem

/** Pure queue navigation, kept separate from ExoPlayer so it can be unit-tested. */
class QueueCursor(val items: List<QueueItem> = emptyList(), startIndex: Int = 0) {

    var index: Int = if (items.isEmpty()) -1 else startIndex.coerceIn(items.indices)
        private set

    val current: QueueItem? get() = items.getOrNull(index)
    val hasNext: Boolean get() = index + 1 < items.size
    val hasPrevious: Boolean get() = index > 0

    /** Moves forward; returns false at the end. */
    fun next(): Boolean {
        if (!hasNext) return false
        index++
        return true
    }

    /**
     * Standard "previous" behaviour: more than [restartThresholdMs] into the item restarts
     * it; otherwise go back one. Returns true if the index changed.
     */
    fun previous(positionMs: Long, restartThresholdMs: Long = 5_000): Boolean {
        if (positionMs > restartThresholdMs || !hasPrevious) return false
        index--
        return true
    }

    fun jumpTo(i: Int): Boolean {
        require(i in items.indices) { "No item $i in a queue of ${items.size}" }
        val changed = i != index
        index = i
        return changed
    }
}
