package com.castlite.tv

import android.app.Application
import com.castlite.updater.AppTarget
import com.castlite.updater.AppUpdater
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

class CastLiteTvApp : Application() {

    /** Self-updater (GitHub releases). Installs only on the idle screen, never during playback. */
    val updater: AppUpdater by lazy { AppUpdater(this, BuildConfig.UPDATE_MANIFEST_URL, AppTarget.TV) }

    override fun onCreate() {
        super.onCreate()
        ReceiverRuntime.init(this)
        updater.canInstallNow = {
            ReceiverRuntime.activityVisible.value && ReceiverRuntime.engine.ui.value.isIdle && !ReceiverRuntime.mirror.state.value.active
        }
        updater.startAutoChecks()
        // Install a waiting update as soon as the TV is idle on our screen.
        MainScope().launch {
            combine(ReceiverRuntime.activityVisible, ReceiverRuntime.engine.ui, ReceiverRuntime.mirror.state) { visible, ui, mirror ->
                visible && ui.isIdle && !mirror.active
            }.distinctUntilChanged().collect { ready -> if (ready) updater.installIfReady() }
        }
    }
}
