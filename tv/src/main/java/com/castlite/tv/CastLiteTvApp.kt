package com.castlite.tv

import android.app.Application
import com.castlite.updater.AppTarget
import com.castlite.updater.AppUpdater

class CastLiteTvApp : Application() {

    /** Self-updater (GitHub releases). The receiver only lets it install while the TV is idle. */
    val updater: AppUpdater by lazy { AppUpdater(this, BuildConfig.UPDATE_MANIFEST_URL, AppTarget.TV) }

    override fun onCreate() {
        super.onCreate()
        updater.startAutoChecks()
    }
}
