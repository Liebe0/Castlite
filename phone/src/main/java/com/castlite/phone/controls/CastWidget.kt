package com.castlite.phone.controls

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import com.castlite.phone.MainActivity
import com.castlite.phone.R
import com.castlite.phone.cast.CastManager
import com.castlite.phone.cast.CastSession
import com.castlite.phone.cast.PlayerState
import com.castlite.phone.server.MediaServerService

/** Home-screen widget: what's playing on the TV, with previous / play-pause / next. */
class CastWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        update(context, CastManager.session.value)
    }

    companion object {
        /** Redraws every CastLite widget (called by the service when the session changes). */
        fun update(context: Context, session: CastSession?) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, CastWidget::class.java))
            if (ids.isEmpty()) return
            manager.updateAppWidget(ids, views(context, session))
        }

        private fun views(context: Context, session: CastSession?): RemoteViews {
            val v = RemoteViews(context.packageName, R.layout.widget_cast)
            val open = PendingIntent.getActivity(
                context, 20, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE,
            )
            v.setOnClickPendingIntent(R.id.widget_root, open)
            if (session == null) {
                v.setTextViewText(R.id.widget_title, "CastLite")
                v.setTextViewText(R.id.widget_subtitle, "Nothing is casting · tap to open")
                v.setViewVisibility(R.id.widget_controls, View.GONE)
                return v
            }
            val playing = session.status.state == PlayerState.PLAYING || session.status.state == PlayerState.LOADING
            v.setTextViewText(R.id.widget_title, session.current?.displayName ?: "CastLite")
            v.setTextViewText(
                R.id.widget_subtitle,
                "${session.devicesLabel} · ${session.status.state.name.lowercase().replaceFirstChar { it.uppercase() }}",
            )
            v.setViewVisibility(R.id.widget_controls, View.VISIBLE)
            v.setImageViewResource(R.id.widget_play, if (playing) R.drawable.ic_pause else R.drawable.ic_play)
            v.setOnClickPendingIntent(R.id.widget_prev, MediaServerService.controlIntent(context, MediaServerService.ACTION_PREVIOUS, 21))
            v.setOnClickPendingIntent(R.id.widget_play, MediaServerService.controlIntent(context, MediaServerService.ACTION_TOGGLE, 22))
            v.setOnClickPendingIntent(R.id.widget_next, MediaServerService.controlIntent(context, MediaServerService.ACTION_NEXT, 23))
            return v
        }
    }
}
