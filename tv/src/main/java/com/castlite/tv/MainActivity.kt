package com.castlite.tv

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.castlite.tv.ui.ReceiverApp
import com.castlite.tv.ui.TvTheme
import kotlinx.coroutines.launch

/**
 * Receiver entry point. While this activity is visible the TV listens for the phone
 * (HTTP API on port 8766 + mDNS advertisement) and plays whatever it sends.
 */
class MainActivity : ComponentActivity() {

    private val vm: ReceiverViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            TvTheme {
                ReceiverApp(vm)
            }
        }
        // Keep the screen awake while something is playing.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.engine.ui.collect { state ->
                    if (state.isIdle) {
                        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    } else {
                        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        vm.startReceiving()
    }

    override fun onStop() {
        vm.stopReceiving()
        super.onStop()
    }

    /**
     * Remote and media keys go to the player first; unhandled ones (e.g. Back when idle)
     * behave normally. Overriding dispatchKeyEvent (rather than onKeyDown) sees keys before
     * Compose's focus system can swallow D-pad presses. Lint flags ComponentActivity's
     * override as restricted, but overriding the platform method is supported.
     */
    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (vm.handleKey(event)) return true
        // On the idle screen, OK acts on a pending app update (allow / install / retry).
        if (event.action == KeyEvent.ACTION_DOWN && vm.engine.ui.value.isIdle &&
            (event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || event.keyCode == KeyEvent.KEYCODE_ENTER)
        ) {
            vm.onIdleOk()?.let { intent ->
                try {
                    startActivity(intent)
                } catch (e: Exception) {
                    // Some TVs hide this screen; fall back to the app's own settings page.
                    runCatching {
                        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                    }
                }
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }
}
