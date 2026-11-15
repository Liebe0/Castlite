package com.castlite.phone.cast.googlecast

import com.castlite.phone.cast.CastController
import com.castlite.phone.cast.CastDevice
import com.castlite.phone.cast.CastException
import kotlinx.coroutines.CoroutineScope

/**
 * Placeholder for Google Cast. Deliberately NOT implemented — CastLite must work
 * without Google Play services.
 *
 * To add it later:
 *  1. Add `com.google.android.gms:play-services-cast-framework` to the phone module and
 *     an `OptionsProvider` using the Default Media Receiver app id.
 *  2. Write a discovery source that maps `MediaRouter` routes to [CastDevice]s with
 *     `protocol = CastProtocol.GOOGLE_CAST`, and add it to `DeviceRepository`.
 *  3. Implement [CastController] with `RemoteMediaClient` (queueLoad, play, pause,
 *     seek, setStreamVolume, queueNext/queuePrev) and return it from [createController].
 * The phone's HTTP server, queue, UI and notification need no changes.
 */
object GoogleCastSupport {
    const val IMPLEMENTED = false

    fun createController(device: CastDevice, scope: CoroutineScope): CastController =
        throw CastException("Google Cast isn't supported yet. Use DLNA or the CastLite TV app.")
}
