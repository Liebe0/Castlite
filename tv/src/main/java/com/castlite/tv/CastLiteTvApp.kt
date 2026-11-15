package com.castlite.tv

import android.app.Application
import com.castlite.updater.AppTarget
import com.castlite.updater.AppUpdater
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

class CastLiteTvApp : Application() {

    /**
     * Self-updater (GitHub releases). Installs only when nothing has been playing for a
     * minute, never during playback or mirroring. On Android 12+ that includes while the
     * TV shows another app; older TVs show Android's "Update" dialog on our idle screen.
     */
    val updater: AppUpdater by lazy { AppUpdater(this, BuildConfig.UPDATE_MANIFEST_URL, AppTarget.TV) }

    /** When the receiver last became idle (Long.MAX_VALUE while busy). */
    @Volatile private var idleSinceMs = 0L

    override fun onCreate() {
        super.onCreate()
        ReceiverRuntime.init(this, updater)
        updater.canInstallNow = { System.currentTimeMillis() - idleSinceMs >= IDLE_GRACE_MS }
        updater.startAutoChecks()
        // A waiting update installs once the TV has been idle for a moment (so it doesn't
        // land just as the phone sends the next video).
        MainScope().launch {
            combine(ReceiverRuntime.engine.ui, ReceiverRuntime.mirror.state) { ui, mirror -> ui.isIdle && !mirror.active }
                .distinctUntilChanged()
                .collectLatest { idle ->
                    idleSinceMs = if (idle) System.currentTimeMillis() else Long.MAX_VALUE
                    if (idle) {
                        delay(IDLE_GRACE_MS + 500)
                        updater.installIfReady()
                    }
                }
        }
    }

    private companion object {
        const val IDLE_GRACE_MS = 60_000L
    }
}
