package com.castlite.phone.media

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.security.SecureRandom

/**
 * The queue, in order. It is both the UI's model and the HTTP server's allow-list: the
 * server only serves ids that are in here. Saved to disk so it survives app restarts.
 *
 * Process-wide singleton because the Activity (adds files) and the foreground
 * Service (serves them) must share it.
 */
object MediaLibrary {

    private val _items = MutableStateFlow<List<CastMedia>>(emptyList())
    val items: StateFlow<List<CastMedia>> = _items.asStateFlow()

    private val random = SecureRandom()
    private const val ID_ALPHABET = "abcdefghijkmnpqrstuvwxyz23456789"
    private val json = Json { ignoreUnknownKeys = true }
    private var store: File? = null

    /** Loads the saved queue (call once at app start). */
    fun init(file: File) {
        store = file
        val saved = runCatching { json.decodeFromString(ListSerializer(CastMedia.serializer()), file.readText()) }.getOrNull()
        if (saved != null) _items.value = saved
    }

    fun add(newItems: List<CastMedia>) = change { current ->
        // Skip files that are already in the queue (same content URI).
        val known = current.map { it.contentUri }.toSet()
        current + newItems.filter { it.contentUri !in known }
    }

    fun remove(id: String) = change { list -> list.filterNot { it.id == id } }

    fun removeAll(ids: Set<String>) = change { list -> list.filterNot { it.id in ids } }

    fun clear() = change { emptyList() }

    /** Moves an item up (delta < 0) or down (delta > 0) in the queue. */
    fun move(id: String, delta: Int) = change { list ->
        val from = list.indexOfFirst { it.id == id }
        val to = (from + delta).coerceIn(0, list.lastIndex)
        if (from < 0 || from == to) list else list.toMutableList().apply { add(to, removeAt(from)) }
    }

    /** Randomizes the order (one-off; the new order is what gets cast). */
    fun shuffle() = change { it.shuffled(random) }

    fun setSubtitle(id: String, subtitle: SubtitleRef?) = change { list ->
        list.map { if (it.id == id) it.copy(subtitle = subtitle) else it }
    }

    /** Looked up from the server's worker threads; StateFlow.value is thread-safe. */
    fun find(id: String): CastMedia? = _items.value.firstOrNull { it.id == id }

    /** 10 random characters ≈ 50 bits: not guessable by other devices on the LAN. */
    fun newId(): String = buildString {
        repeat(10) { append(ID_ALPHABET[random.nextInt(ID_ALPHABET.length)]) }
    }

    private fun change(transform: (List<CastMedia>) -> List<CastMedia>) {
        _items.update(transform)
        save()
    }

    private fun save() {
        val file = store ?: return
        runCatching { file.writeText(json.encodeToString(ListSerializer(CastMedia.serializer()), _items.value)) }
    }
}
