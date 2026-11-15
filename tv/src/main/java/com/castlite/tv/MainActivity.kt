package com.castlite.tv

import android.annotation.SuppressLint
import android.os.Bundle
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
    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        vm.handleKey(event) || super.dispatchKeyEvent(event)
}
