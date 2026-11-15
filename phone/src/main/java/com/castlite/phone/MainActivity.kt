package com.castlite.phone

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.content.Intent
import android.os.Bundle
import androidx.activity.viewModels
import com.castlite.phone.ui.MainViewModel
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.castlite.phone.ui.CastLiteRoot
import com.castlite.phone.ui.theme.CastLiteTheme

class MainActivity : ComponentActivity() {

    private val vm: MainViewModel by viewModels()

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* optional either way */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        askForNotificationPermission()
        // Files or links shared to CastLite / "Open with CastLite".
        if (savedInstanceState == null) vm.handleIncoming(intent)
        vm.pruneMissing()
        setContent {
            CastLiteTheme {
                CastLiteRoot()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        vm.handleIncoming(intent)
    }

    override fun onStart() {
        super.onStart()
        (application as CastLiteApp).isUiVisible = true
    }

    override fun onStop() {
        (application as CastLiteApp).isUiVisible = false
        super.onStop()
    }

    /**
     * Android 13+ hides the foreground-service notification (and its Stop button)
     * unless we're allowed to post notifications. The server works without it.
     */
    private fun askForNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
