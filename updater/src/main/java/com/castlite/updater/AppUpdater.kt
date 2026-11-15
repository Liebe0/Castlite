package com.castlite.updater

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.ConnectivityManager
import android.net.Network
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.pm.PackageInfoCompat
import kotlinx.coroutines.CancellationException
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
 * 1. Fetches `update.json` from the latest release: at start-up, every 15 minutes, and
 *    whenever something suggests it ([onForeground], Wi-Fi coming back, [nudge]).
 * 2. If it's newer than the installed version, downloads this app's APK and verifies
 *    its SHA-256.
 * 3. Installs it with [PackageInstaller] as soon as [canInstallNow] allows (the apps say
 *    "not while casting / playing"):
 *    - On Android 12+ an app may update itself without a prompt, so this also happens
 *      in the background (e.g. while the TV shows another app).
 *    - Otherwise Android shows its one-tap "Update" dialog, so it waits until the app is
 *      on screen.
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
    private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val schedule = CheckSchedule()

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    /** The app decides when installing is OK (e.g. not while something is on the TV). */
    @Volatile var canInstallNow: () -> Boolean = { true }

    val installedVersionCode: Long = PackageInfoCompat.getLongVersionCode(app.packageManager.getPackageInfo(app.packageName, 0))
    val installedVersionName: String = app.packageManager.getPackageInfo(app.packageName, 0).versionName ?: "?"

    private var pending: Pair<UpdateManifest, File>? = null
    private var checkJob: Job? = null
    private var periodicJob: Job? = null
    /** The user cancelled the system install dialog for this version: don't nag until they ask. */
    private var declinedVersion: Long = -1
    /** When the current install was handed to Android (0 = none in progress). */
    private var installStartedMs = 0L
    @Volatile private var visible = false
    private var hiddenAtMs = 0L
    private var lastError: String? = null

    init {
        instance = this
    }

    /** Checks now, then on [CheckSchedule]'s timetable while the process lives. */
    fun startAutoChecks() {
        if (periodicJob?.isActive == true) return
        periodicJob = scope.launch {
            while (isActive) {
                if (schedule.isDue(now())) check()
                delay(TICK_MS)
            }
        }
        watchNetwork()
    }

    /** The app came on screen: check (unless it just did) and install a ready update. */
    fun onForeground() {
        visible = true
        if (schedule.nudgeAllowed(now())) check() else installIfReady()
    }

    /** The app left the screen. If it stays idle, a silent update may install a bit later. */
    fun onBackground() {
        visible = false
        hiddenAtMs = now()
        scope.launch {
            delay(InstallGate.BACKGROUND_DELAY_MS + 1_000)
            installIfReady()
        }
    }

    /**
     * Something makes a check worthwhile (a phone connected, Wi-Fi came back). Rate-limited;
     * safe to call from any thread.
     */
    fun nudge() {
        scope.launch { if (schedule.nudgeAllowed(now())) check() }
    }

    /** Fetches the manifest and, if there's a newer version, downloads it. */
    fun check(userInitiated: Boolean = false) {
        if (checkJob?.isActive == true) return
        if (userInitiated) declinedVersion = -1
        checkJob = scope.launch {
            schedule.onAttempt(now())
            // Background checks are quiet: the screen keeps showing e.g. "update ready".
            if (userInitiated) _state.value = UpdateState.Checking
            try {
                val manifest = withContext(Dispatchers.IO) { downloader.fetchManifest(manifestUrl) }
                schedule.onSuccess(now())
                lastError = null
                val asset = manifest.assetFor(target)
                if (!manifest.isUpdateFor(target, installedVersionCode) || asset == null) {
                    pending = null
                    _state.value = UpdateState.UpToDate(now())
                    cleanup(keep = null)
                    return@launch
                }
                val held = pending
                if (held == null || held.first.versionCode != manifest.versionCode || !held.second.exists()) {
                    // New version (or a newer one than the download we were holding).
                    val file = File(updateDir(), "castlite-${target.name.lowercase()}-${manifest.versionCode}.apk")
                    pending = null
                    cleanup(keep = file)
                    _state.value = UpdateState.Downloading(manifest.versionName, 0f)
                    withContext(Dispatchers.IO) {
                        downloader.download(asset, file) { p ->
                            _state.value = UpdateState.Downloading(manifest.versionName, p)
                        }
                    }
                    Log.i(TAG, "Update ${manifest.versionName} downloaded")
                    pending = manifest to file
                }
                if (!installInProgress()) _state.value = UpdateState.ReadyToInstall(manifest.versionName)
                installIfReady()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Update check failed", e)
                schedule.onFailure()
                lastError = e.message ?: e.javaClass.simpleName
                val ready = pending
                when {
                    // Keep offering the update we already have; it doesn't need the network.
                    ready != null -> {
                        if (!installInProgress()) _state.value = UpdateState.ReadyToInstall(ready.first.versionName)
                        installIfReady()
                    }
                    // A single failed background check (e.g. Wi-Fi still connecting) isn't worth
                    // showing: it retries within a minute.
                    userInitiated || schedule.failures >= 3 ->
                        _state.value = UpdateState.Failed(e.message ?: "Update check failed")
                }
            }
        }
    }

    /** Installs the downloaded update if the app says it's a good moment. */
    fun installIfReady(userInitiated: Boolean = false) {
        val (manifest, file) = pending ?: return
        if (installInProgress()) return
        val allowed = InstallGate.allowed(
            userInitiated = userInitiated,
            appIdle = canInstallNow(),
            declined = manifest.versionCode == declinedVersion,
            visible = visible,
            silentPossible = silentInstallPossible(),
            msSinceHidden = now() - hiddenAtMs,
        )
        if (!allowed) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !app.packageManager.canRequestPackageInstalls()) {
            _state.value = UpdateState.NeedsPermission(manifest.versionName)
            return
        }
        _state.value = UpdateState.Installing(manifest.versionName)
        installStartedMs = now()
        // Read by the new version (UpdatedReceiver): reopen the app only if it was on screen.
        prefs.edit().putBoolean(KEY_REOPEN, visible).commit()
        Log.i(TAG, "Installing ${manifest.versionName} (on screen: $visible)")
        scope.launch {
            try {
                withContext(Dispatchers.IO) { commit(file, manifest.versionCode) }
            } catch (e: Exception) {
                Log.w(TAG, "Install failed", e)
                installStartedMs = 0
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

    /** One line for diagnostics reports. */
    fun describe(): String {
        val fmt = SimpleDateFormat("HH:mm", Locale.US)
        val lastOk = schedule.lastSuccessMs.takeIf { it > 0 }?.let { fmt.format(Date(it)) } ?: "never"
        val allowedToInstall = Build.VERSION.SDK_INT < Build.VERSION_CODES.O || app.packageManager.canRequestPackageInstalls()
        return "Updater: ${state.value}; last good check $lastOk, failed checks ${schedule.failures}" +
            (lastError?.let { " (last error: $it)" } ?: "") +
            "; may install apps: $allowedToInstall; silent install: ${silentInstallPossible()}; on screen: $visible"
    }

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

    /**
     * Android wants the user to confirm (always before Android 12). Shows its dialog if
     * the app is on screen; otherwise drops this attempt and asks next time it's opened.
     * Returns false if the dialog couldn't be shown.
     */
    internal fun onUserActionRequired(confirm: Intent, sessionId: Int): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12+ asked anyway (e.g. another store owns updates): stop trying silently.
            prefs.edit().putLong(KEY_SILENT_REFUSED, installedVersionCode).apply()
        }
        val name = pending?.first?.versionName ?: "update"
        if (visible) {
            try {
                app.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                // If the dialog is closed without an answer, "press OK" can bring it back.
                _state.value = UpdateState.ReadyToInstall(name)
                return true
            } catch (e: Exception) {
                Log.w(TAG, "Couldn't show the install dialog", e)
            }
        }
        if (sessionId > 0) runCatching { app.packageManager.packageInstaller.abandonSession(sessionId) }
        installStartedMs = 0
        _state.value = UpdateState.ReadyToInstall(name)
        return false
    }

    internal fun onInstallFinished(status: Int, message: String?, versionCode: Long) {
        installStartedMs = 0
        val name = pending?.first?.versionName ?: "update"
        _state.value = when (status) {
            PackageInstaller.STATUS_SUCCESS -> UpdateState.UpToDate(now())
            PackageInstaller.STATUS_FAILURE_ABORTED -> {
                // User pressed Cancel: keep the download, don't ask again until next time.
                declinedVersion = versionCode
                UpdateState.ReadyToInstall(name)
            }
            else -> UpdateState.Failed(message ?: "Installation failed ($status)")
        }
    }

    /** A dialog may still be open for a while; after that, assume it was dismissed. */
    private fun installInProgress(): Boolean = installStartedMs > 0 && now() - installStartedMs < INSTALL_TIMEOUT_MS

    private fun silentInstallPossible(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && prefs.getLong(KEY_SILENT_REFUSED, -1) != installedVersionCode

    /** Wi-Fi came (back): a good moment to check, e.g. right after the TV boots. */
    private fun watchNetwork() {
        val cm = app.getSystemService(ConnectivityManager::class.java) ?: return
        try {
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = nudge()
            })
        } catch (e: Exception) {
            Log.w(TAG, "Can't watch the network", e)
        }
    }

    private fun now() = System.currentTimeMillis()

    private fun updateDir() = File(app.cacheDir, "updates").apply { mkdirs() }

    /** Deletes old downloads (everything except [keep] and its partial file). */
    private fun cleanup(keep: File?) {
        updateDir().listFiles()
            ?.filter { keep == null || (it.path != keep.path && it.path != keep.path + ".part") }
            ?.forEach { it.delete() }
    }

    companion object {
        private const val TAG = "AppUpdater"
        private const val TICK_MS = 60_000L
        private const val INSTALL_TIMEOUT_MS = 10 * 60_000L
        internal const val PREFS = "castlite_updater"
        /** Written before installing: was the app on screen (so the new version reopens it)? */
        internal const val KEY_REOPEN = "reopen_after_update"
        private const val KEY_SILENT_REFUSED = "silent_refused_at_version"

        /** The live updater, so [InstallResultReceiver] can report back. */
        @Volatile internal var instance: AppUpdater? = null
    }
}
