package com.castlite.phone.ui

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.core.content.ContextCompat
import com.castlite.phone.media.CastMedia
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.castlite.phone.data.PowerChecks
import androidx.lifecycle.viewmodel.compose.viewModel
import com.castlite.phone.ui.screens.DevicesActions
import com.castlite.phone.ui.screens.DevicesScreen
import com.castlite.phone.ui.screens.HomeActions
import com.castlite.phone.ui.screens.HomeScreen
import com.castlite.phone.ui.screens.NowPlayingScreen
import com.castlite.phone.ui.screens.PlayerActions
import com.castlite.phone.ui.screens.ServerActions
import com.castlite.phone.ui.screens.ServerScreen
import com.castlite.phone.ui.screens.ScanScreen
import com.castlite.phone.ui.theme.Palette
import kotlinx.coroutines.delay

/** The app's screens. Navigation is a simple state machine — four screens don't need a nav library. */
enum class Screen { HOME, DEVICES, PLAYER, SERVER, SCAN }

/** MIME filters for the system picker: video, photos and audio; multiple selection allowed. */
private val PICKER_TYPES = arrayOf("video/*", "image/*", "audio/*")

@Composable
fun CastLiteRoot(vm: MainViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var screen by rememberSaveable { mutableStateOf(Screen.HOME) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        vm.onMediaPicked(uris)
    }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { vm.onFolderPicked(it) }
    var subtitleFor by remember { mutableStateOf<CastMedia?>(null) }
    val subtitlePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        subtitleFor?.let { vm.onSubtitlePicked(it, uri) }
        subtitleFor = null
    }

    // Screen mirroring: (optional) sound permission → Android's "Start casting?" dialog → service.
    var mirrorWithAudio by remember { mutableStateOf(false) }
    val projectionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) vm.startMirroring(result.resultCode, data, mirrorWithAudio)
    }
    val launchProjection = {
        val mpm = context.getSystemService(MediaProjectionManager::class.java)
        projectionLauncher.launch(mpm.createScreenCaptureIntent())
    }
    val audioPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        mirrorWithAudio = granted
        launchProjection()
    }
    val startMirror = {
        val problem = vm.mirrorProblem()
        when {
            problem != null -> vm.showMessage(problem)
            Build.VERSION.SDK_INT < Build.VERSION_CODES.Q -> { mirrorWithAudio = false; launchProjection() }
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED -> {
                mirrorWithAudio = true
                launchProjection()
            }
            else -> audioPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }

    // Leave the player when there's no session (it ended, or casting never started).
    // The short delay lets a just-started session reach the UI first.
    LaunchedEffect(screen, state.session == null) {
        if (screen == Screen.PLAYER && state.session == null) {
            delay(600)
            screen = Screen.HOME
        }
    }

    BackHandler(enabled = screen != Screen.HOME) { screen = if (screen == Screen.SCAN) Screen.DEVICES else Screen.HOME }

    // Battery settings may have changed while the user was in system settings.
    LifecycleResumeEffect(Unit) {
        vm.refreshPower()
        onPauseOrDispose { }
    }
    val allowBackground = {
        val power = state.power
        val intent = if (power.backgroundRestricted) PowerChecks.appSettings(context) else PowerChecks.exemptionRequest(context)
        if (!context.tryStart(intent) && !context.tryStart(PowerChecks.batterySettings())) {
            context.tryStart(PowerChecks.appSettings(context))
        }
    }

    Box(Modifier.fillMaxSize()) {
        AnimatedContent(
            targetState = screen,
            transitionSpec = {
                when {
                    targetState == Screen.PLAYER -> slideInVertically(tween(320)) { it / 3 } + fadeIn() togetherWith fadeOut(tween(200))
                    initialState == Screen.PLAYER -> fadeIn(tween(200)) togetherWith slideOutVertically(tween(320)) { it / 3 } + fadeOut()
                    targetState == Screen.HOME -> slideInHorizontally(tween(300)) { -it / 4 } + fadeIn() togetherWith slideOutHorizontally(tween(300)) { it / 4 } + fadeOut()
                    else -> slideInHorizontally(tween(300)) { it / 4 } + fadeIn() togetherWith slideOutHorizontally(tween(300)) { -it / 4 } + fadeOut()
                }
            },
            label = "screens",
        ) { target ->
            when (target) {
                Screen.HOME -> HomeScreen(
                    state = state,
                    actions = HomeActions(
                        pickMedia = { picker.launch(PICKER_TYPES) },
                        pickFolder = { folderPicker.launch(null) },
                        addLink = vm::addLink,
                        chooseDevice = { screen = Screen.DEVICES },
                        cast = { index -> if (vm.requestCast(index)) screen = Screen.PLAYER },
                        shuffle = vm::shuffle,
                        cycleRepeat = vm::cycleRepeat,
                        setSlideshow = vm::setSlideshow,
                        pickSubtitle = { item -> subtitleFor = item; subtitlePicker.launch(arrayOf("*/*")) },
                        removeSubtitle = vm::removeSubtitle,
                        mirror = startMirror,
                        stopMirror = vm::stopMirroring,
                        wakeTv = vm::wakeSelected,
                        answerResume = { resume -> vm.answerResume(resume); screen = Screen.PLAYER },
                        dismissResume = vm::dismissResume,
                        openPlayer = { screen = Screen.PLAYER },
                        openServer = { screen = Screen.SERVER },
                        remove = vm::remove,
                        move = vm::move,
                        clear = vm::clearQueue,
                        togglePlayPause = vm::togglePlayPause,
                        allowBackground = allowBackground,
                        dismissBackgroundPrompt = vm::dismissBackgroundPrompt,
                        installUpdate = vm::installUpdate,
                        allowUpdates = { context.tryStart(vm.updatePermissionIntent()) },
                    ),
                )

                Screen.DEVICES -> {
                    val devicesVm: DevicesViewModel = viewModel()
                    val devicesState by devicesVm.state.collectAsStateWithLifecycle()
                    DevicesScreen(
                        state = devicesState,
                        actions = DevicesActions(
                            back = { screen = Screen.HOME },
                            select = { devicesVm.select(it); screen = Screen.HOME },
                            showManualEntry = devicesVm::showManualEntry,
                            dismissManualEntry = devicesVm::dismissManualEntry,
                            addByIp = { ip -> devicesVm.addByIp(ip) { screen = Screen.HOME } },
                            removeManual = devicesVm::removeManual,
                            toggleExtra = devicesVm::toggleExtra,
                            saveMac = devicesVm::saveMac,
                            wake = devicesVm::wake,
                            scan = { screen = Screen.SCAN },
                            searchOtherRouters = devicesVm::searchOtherRouters,
                        ),
                    )
                }

                Screen.PLAYER -> {
                    val session = state.session
                    if (session != null) {
                        NowPlayingScreen(
                            session = session,
                            slideDurationMs = state.slideDurationMs,
                            actions = PlayerActions(
                                back = { screen = Screen.HOME },
                                togglePlayPause = vm::togglePlayPause,
                                seekTo = vm::seekTo,
                                next = vm::next,
                                previous = vm::previous,
                                stop = { vm.stopCasting(); screen = Screen.HOME },
                                setVolume = vm::setVolume,
                                jumpTo = vm::jumpTo,
                                retry = vm::retry,
                                cycleRepeat = vm::cycleRepeat,
                                setSubtitles = vm::setSubtitles,
                            ),
                        )
                    }
                }

                Screen.SCAN -> {
                    val devicesVm: DevicesViewModel = viewModel()
                    ScanScreen(
                        onCode = { code ->
                            screen = Screen.DEVICES
                            devicesVm.addFromQr(code) { screen = Screen.HOME }
                        },
                        onClose = { screen = Screen.DEVICES },
                        onEnterIp = { screen = Screen.DEVICES; devicesVm.showManualEntry() },
                    )
                }

                Screen.SERVER -> ServerScreen(
                    state = state,
                    actions = ServerActions(
                        back = { screen = Screen.HOME },
                        start = vm::startServer,
                        stop = vm::stopServer,
                        copy = { url -> context.copyToClipboard(url); vm.showMessage("URL copied") },
                        open = { url -> if (!context.openUrl(url)) vm.showMessage("No browser installed") },
                        allowBackground = allowBackground,
                        checkForUpdates = vm::checkForUpdates,
                        sendDiagnostics = { vm.diagnostics { intent -> context.tryStart(intent) } },
                    ),
                )
            }
        }

        SnackbarHost(
            snackbar,
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 96.dp),
        ) { data ->
            Snackbar(data, containerColor = Palette.SurfaceHighest, contentColor = Palette.TextPrimary)
        }
    }
}

private fun Context.copyToClipboard(text: String) {
    getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("CastLite URL", text))
}

private fun Context.tryStart(intent: Intent): Boolean = try {
    startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    true
} catch (e: Exception) {
    false
}

private fun Context.openUrl(url: String): Boolean = try {
    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    true
} catch (e: ActivityNotFoundException) {
    false
}
