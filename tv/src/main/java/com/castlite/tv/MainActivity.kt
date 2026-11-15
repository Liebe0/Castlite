package com.castlite.tv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.castlite.tv.ui.IdleScreen
import com.castlite.tv.ui.TvTheme

/**
 * Receiver entry point. Phase 1 only shows the idle screen; Phase 3 adds the JSON
 * control API, NSD advertising and the ExoPlayer-based player.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            TvTheme {
                IdleScreen()
            }
        }
    }
}
