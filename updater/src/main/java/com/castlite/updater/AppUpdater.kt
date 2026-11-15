package com.castlite.updater

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.pm.PackageInfoCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** What the updater is doing; shown as a small banner/pill in each app. */
sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data class UpToDate(val checkedAtMs: Long) : UpdateState
    data class Downloading(val version: String, val progress: Float) : UpdateState
    /** Downloaded and verified; waiting for a good moment (not while casting/playing). */
    data class ReadyToInstall(val version: String) : UpdateState
    /** Downloaded, but the user must allow "Install unknown apps" for CastLite once. */
    data class NeedsPermission(val version: String) : UpdateState
    data class Installing(val version: String) : UpdateState
    data class Failed(val message: String) : UpdateState
}

/**
 * Keeps the app up to date from GitHub releases:
 *
 * 1. Fetches `update.json` from the latest release (on start and every few hours).
 * 2. If it's newer than the installed version, downloads this app's APK and verifies
 *    its SHA-256.
 * 3. Installs it with [PackageInstaller] as soon as [canInstallNow] allows (the apps
 *    say "not while casting / playing"). On Android 12+ an app may update itself
 *    without a prompt; otherwise Android shows its one-tap "Update" dialog.
 *
 * Updates install over the old version because every build is signed with the same key.
 */
class AppUpdater(
    context: Context,
    private val manifestUrl: String,
    private val target: AppTarget,
    private val downloader: UpdateDownloader = UpdateDownloader(),
) {
    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    /** The app decides when installing is OK (e.g. not while something is on the TV). */
    @Volatile var canInstallNow: () -> Boolean = { true }

    val installedVersionCode: Long = PackageInfoCompat.getLongVersionCode(app.packageManager.getPackageInfo(app.packageName, 0))
    val installedVersionName: String = app.packageManager.getPackageInfo(app.packageName, 0).versionName ?: "?"

    private var pending: Pair<UpdateManifest, File>? = null
    private var checkJob: Job? = null
    private var periodicJob: Job? = null
    private var lastCheckMs = 0L
    /** The user cancelled the system install dialog for this version: don't nag until they ask. */
    private var declinedVersion: Long = -1

    init {
        instance = this
    }

    /** Checks now, then every [intervalMs] while the process lives. */
    fun startAutoChecks(intervalMs: Long = 6 * 60 * 60_000L) {
        if (periodicJob?.isActive == true) return
        periodicJob = scope.launch {
            while (isActive) {
                check()
                delay(intervalMs)
            }
        }
    }

    /** Call when the app comes to the foreground: re-checks (rate-limited) and installs a ready update. */
    fun onForeground() {
        if (System.currentTimeMillis() - lastCheckMs > MIN_RECHECK_MS) check() else installIfReady()
    }

    /** Fetches the manifest and, if there's a newer version, downloads it. */
    fun check(userInitiated: Boolean = false) {
        if (checkJob?.isActive == true) return
        if (userInitiated) declinedVersion = -1
        checkJob = scope.launch {
            val ready = pending
            if (ready != null) {
                installIfReady()
                return@launch
            }
            lastCheckMs = System.currentTimeMillis()
            _state.value = UpdateState.Checking
            try {
                val manifest = withContext(Dispatchers.IO) { downloader.fetchManifest(manifestUrl) }
                val asset = manifest.assetFor(target)
                if (!manifest.isUpdateFor(target, installedVersionCode) || asset == null) {
                    _state.value = UpdateState.UpToDate(System.currentTimeMillis())
                    cleanup(keep = null)
                    return@launch
                }
                val file = File(updateDir(), "castlite-${target.name.lowercase()}-${manifest.versionCode}.apk")
                cleanup(keep = file)
                _state.value = UpdateState.Downloading(manifest.versionName, 0f)
                withContext(Dispatchers.IO) {
                    downloader.download(asset, file) { p ->
                        _state.value = UpdateState.Downloading(manifest.versionName, p)
                    }
                }
                Log.i(TAG, "Update ${manifest.versionName} downloaded")
                pending = manifest to file
                _state.value = UpdateState.ReadyToInstall(manifest.versionName)
                installIfReady()
            } catch (e: Exception) {
                Log.w(TAG, "Update check failed", e)
                _state.value = UpdateState.Failed(e.message ?: "Update check failed")
            }
        }
    }

    /** Installs the downloaded update if the app says it's a good moment. */
    fun installIfReady(userInitiated: Boolean = false) {
        val (manifest, file) = pending ?: return
        if (!userInitiated && (manifest.versionCode == declinedVersion || !canInstallNow())) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !app.packageManager.canRequestPackageInstalls()) {
            _state.value = UpdateState.NeedsPermission(manifest.versionName)
            return
        }
        _state.value = UpdateState.Installing(manifest.versionName)
        scope.launch {
            try {
                withContext(Dispatchers.IO) { commit(file, manifest.versionCode) }
            } catch (e: Exception) {
                Log.w(TAG, "Install failed", e)
                _state.value = UpdateState.Failed("Couldn't install the update: ${e.message}")
            }
        }
    }

    /** Opens the system screen where the user allows CastLite to install updates. */
    fun permissionSettingsIntent(): Intent =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${app.packageName}"))
        } else {
            Intent(Settings.ACTION_SECURITY_SETTINGS)
        }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Hands the APK to PackageInstaller. The result arrives in [InstallResultReceiver]. */
    @SuppressLint("RequestInstallPackagesPolicy")
    private fun commit(apk: File, versionCode: Long) {
        val installer = app.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(app.packageName)
            setSize(apk.length())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // An app updating itself may skip the confirmation dialog on Android 12+.
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            apk.inputStream().use { input ->
                session.openWrite("base.apk", 0, apk.length()).use { out ->
                    input.copyTo(out)
                    session.fsync(out)
                }
            }
            val intent = Intent(app, InstallResultReceiver::class.java)
                .setAction(InstallResultReceiver.ACTION)
                .putExtra(InstallResultReceiver.EXTRA_VERSION, versionCode)
            // PackageInstaller adds the status extras, so the PendingIntent must be mutable.
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
            val pi = PendingIntent.getBroadcast(app, sessionId, intent, flags)
            session.commit(pi.intentSender)
        }
    }

    internal fun onInstallFinished(status: Int, message: String?, versionCode: Long) {
        val name = pending?.first?.versionName ?: "update"
        _state.value = when (status) {
            PackageInstaller.STATUS_SUCCESS -> UpdateState.UpToDate(System.currentTimeMillis())
            PackageInstaller.STATUS_FAILURE_ABORTED -> {
                // User pressed Cancel: keep the download, don't ask again until next time.
                declinedVersion = versionCode
                UpdateState.ReadyToInstall(name)
            }
            else -> UpdateState.Failed(message ?: "Installation failed ($status)")
        }
    }

    private fun updateDir() = File(app.cacheDir, "updates").apply { mkdirs() }

    /** Deletes old downloads (everything except [keep] and its partial file). */
    private fun cleanup(keep: File?) {
        updateDir().listFiles()
            ?.filter { keep == null || (it.path != keep.path && it.path != keep.path + ".part") }
            ?.forEach { it.delete() }
    }

    companion object {
        private const val TAG = "AppUpdater"
        private const val MIN_RECHECK_MS = 30 * 60_000L

        /** The live updater, so [InstallResultReceiver] can report back. */
        @Volatile internal var instance: AppUpdater? = null
    }
}
