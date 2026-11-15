package com.castlite.phone.controls

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.castlite.phone.MainActivity
import com.castlite.phone.R
import com.castlite.phone.cast.CastManager
import com.castlite.phone.cast.PlayerState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Quick Settings tile: shows what's on the TV; tap = play/pause (or open CastLite when
 * nothing is casting).
 */
class CastTileService : TileService() {

    private var job: Job? = null

    override fun onStartListening() {
        job = CoroutineScope(Dispatchers.Main.immediate).launch {
            CastManager.session.collect { render() }
        }
    }

    override fun onStopListening() {
        job?.cancel()
        job = null
    }

    override fun onClick() {
        if (CastManager.session.value != null) {
            CastManager.togglePlayPause()
            return
        }
        val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
            startActivityAndCollapse(intent)
        }
    }

    private fun render() {
        val tile = qsTile ?: return
        val session = CastManager.session.value
        val playing = session?.status?.state == PlayerState.PLAYING
        tile.state = when {
            session == null -> Tile.STATE_INACTIVE
            else -> Tile.STATE_ACTIVE
        }
        tile.label = session?.current?.displayName ?: "CastLite"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = when {
                session == null -> "Tap to open"
                playing -> "Playing · tap to pause"
                else -> "Paused · tap to play"
            }
        }
        tile.icon = Icon.createWithResource(this, if (session == null) R.drawable.ic_stat_cast else if (playing) R.drawable.ic_pause else R.drawable.ic_play)
        tile.updateTile()
    }

    companion object {
        /** Ask the system to refresh the tile (called when the session changes). */
        fun refresh(context: Context) {
            runCatching { requestListeningState(context, ComponentName(context, CastTileService::class.java)) }
        }
    }
}
