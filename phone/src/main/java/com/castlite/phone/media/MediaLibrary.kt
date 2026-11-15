package com.castlite.phone.media

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.security.SecureRandom

/**
 * In-memory list of picked files, in queue order. It is both the UI's model and the
 * HTTP server's allow-list: the server only serves ids that are in here.
 *
 * Process-wide singleton because the Activity (adds files) and the foreground
 * Service (serves them) must share it.
 */
object MediaLibrary {

    private val _items = MutableStateFlow<List<CastMedia>>(emptyList())
    val items: StateFlow<List<CastMedia>> = _items.asStateFlow()

    private val random = SecureRandom()
    private const val ID_ALPHABET = "abcdefghijkmnpqrstuvwxyz23456789"

    fun add(newItems: List<CastMedia>) = _items.update { current ->
        // Skip files that are already in the queue (same content URI).
        val known = current.map { it.contentUri }.toSet()
        current + newItems.filter { it.contentUri !in known }
    }

    fun remove(id: String) = _items.update { list -> list.filterNot { it.id == id } }

    fun clear() = _items.update { emptyList() }

    /** Moves an item up (delta < 0) or down (delta > 0) in the queue. */
    fun move(id: String, delta: Int) = _items.update { list ->
        val from = list.indexOfFirst { it.id == id }
        val to = (from + delta).coerceIn(0, list.lastIndex)
        if (from < 0 || from == to) list else list.toMutableList().apply { add(to, removeAt(from)) }
    }

    /** Looked up from the server's worker threads; StateFlow.value is thread-safe. */
    fun find(id: String): CastMedia? = _items.value.firstOrNull { it.id == id }

    /** 10 random characters ≈ 50 bits: not guessable by other devices on the LAN. */
    fun newId(): String = buildString {
        repeat(10) { append(ID_ALPHABET[random.nextInt(ID_ALPHABET.length)]) }
    }
}
