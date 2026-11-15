package com.castlite.tv

import android.app.Application
import android.content.Intent
import android.view.KeyEvent
import androidx.lifecycle.AndroidViewModel
import com.castlite.tv.mirror.MirrorState
import com.castlite.tv.playback.PlaybackEngine
import com.castlite.updater.AppUpdater
import com.castlite.updater.UpdateState
import kotlinx.coroutines.flow.StateFlow

/** Thin bridge between the TV screens and the always-on [ReceiverRuntime]. */
class ReceiverViewModel(app: Application) : AndroidViewModel(app) {

    val engine: PlaybackEngine get() = ReceiverRuntime.engine
    val info: StateFlow<ReceiverInfoState> get() = ReceiverRuntime.info
    val mirror: StateFlow<MirrorState> get() = ReceiverRuntime.mirror.state
    val updater: AppUpdater = (app as CastLiteTvApp).updater

    fun onScreenVisible() {
        ReceiverService.start(getApplication())
        ReceiverRuntime.start() // idempotent; works even if a TV refuses the background service
        ReceiverRuntime.activityVisible.value = true
        updater.onForeground()
        refreshAddress()
    }

    fun onScreenHidden() = ReceiverRuntime.onActivityHidden()

    fun refreshAddress() = ReceiverRuntime.refreshAddress()

    fun handleKey(event: KeyEvent): Boolean {
        if (mirror.value.active) {
            if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                if (event.action == KeyEvent.ACTION_UP) ReceiverRuntime.mirror.disconnect()
                return true
            }
            return false
        }
        return engine.handleKey(event)
    }

    /**
     * OK pressed on the idle screen: act on a pending update. Returns a settings screen to
     * open (to allow installing updates), or null.
     */
    fun onIdleOk(): Intent? = when (updater.state.value) {
        is UpdateState.NeedsPermission -> updater.permissionSettingsIntent()
        is UpdateState.ReadyToInstall -> { updater.installIfReady(userInitiated = true); null }
        is UpdateState.Failed -> { updater.check(userInitiated = true); null }
        else -> null
    }
}
