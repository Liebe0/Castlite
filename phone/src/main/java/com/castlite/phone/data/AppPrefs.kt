package com.castlite.phone.data

import android.content.Context
import com.castlite.phone.cast.CastDevice
import com.castlite.protocol.RepeatMode
import com.castlite.protocol.SlideshowOptions
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** Small persistent settings: TVs, slideshow and repeat options, prompts. */
class AppPrefs(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("castlite", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    var lastDevice: CastDevice?
        get() = prefs.getString(KEY_LAST_DEVICE, null)?.let { runCatching { json.decodeFromString(CastDevice.serializer(), it) }.getOrNull() }
        set(value) = prefs.edit().apply {
            if (value == null) remove(KEY_LAST_DEVICE) else putString(KEY_LAST_DEVICE, json.encodeToString(CastDevice.serializer(), value))
        }.apply()

    var manualDevices: List<CastDevice>
        get() = prefs.getString(KEY_MANUAL, null)
            ?.let { runCatching { json.decodeFromString(ListSerializer(CastDevice.serializer()), it) }.getOrNull() }
            .orEmpty()
        set(value) = prefs.edit().putString(KEY_MANUAL, json.encodeToString(ListSerializer(CastDevice.serializer()), value)).apply()

    var slideshow: SlideshowOptions
        get() = prefs.getString(KEY_SLIDESHOW, null)?.let { runCatching { json.decodeFromString(SlideshowOptions.serializer(), it) }.getOrNull() }
            ?: SlideshowOptions(durationMs = prefs.getLong(KEY_SLIDE, 5_000L))
        set(value) = prefs.edit().putString(KEY_SLIDESHOW, json.encodeToString(SlideshowOptions.serializer(), value)).apply()

    var repeat: RepeatMode
        get() = runCatching { RepeatMode.valueOf(prefs.getString(KEY_REPEAT, null) ?: "OFF") }.getOrDefault(RepeatMode.OFF)
        set(value) = prefs.edit().putString(KEY_REPEAT, value.name).apply()

    /** Device id → MAC address, for Wake-on-LAN. */
    var macs: Map<String, String>
        get() = prefs.getString(KEY_MACS, null)
            ?.let { runCatching { json.decodeFromString(MapSerializer(String.serializer(), String.serializer()), it) }.getOrNull() }
            .orEmpty()
        set(value) = prefs.edit().putString(KEY_MACS, json.encodeToString(MapSerializer(String.serializer(), String.serializer()), value)).apply()

    var batteryPromptDismissed: Boolean
        get() = prefs.getBoolean(KEY_BATTERY_DISMISSED, false)
        set(value) = prefs.edit().putBoolean(KEY_BATTERY_DISMISSED, value).apply()

    private companion object {
        const val KEY_BATTERY_DISMISSED = "battery_prompt_dismissed"
        const val KEY_LAST_DEVICE = "last_device"
        const val KEY_MANUAL = "manual_devices"
        const val KEY_SLIDE = "slide_duration_ms"
        const val KEY_SLIDESHOW = "slideshow"
        const val KEY_REPEAT = "repeat"
        const val KEY_MACS = "macs"
    }
}
