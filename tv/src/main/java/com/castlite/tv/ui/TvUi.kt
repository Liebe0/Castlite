package com.castlite.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.tv.material3.darkColorScheme
import com.castlite.tv.LocalAddress
import kotlinx.coroutines.delay

private val Background = Color(0xFF0F1115)
private val Accent = Color(0xFF4FC3F7)

@Composable
fun TvTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(primary = Accent, background = Background, surface = Background),
        content = content,
    )
}

/** Shown while no phone is casting: tells the user which IP to type on the phone. */
@Composable
fun IdleScreen() {
    var ip by remember { mutableStateOf(LocalAddress.ipv4()) }
    // Re-check now and then: the TV may still be joining Wi-Fi when the app opens.
    LaunchedEffect(Unit) {
        while (true) {
            delay(5_000)
            ip = LocalAddress.ipv4()
        }
    }
    Column(
        modifier = Modifier.fillMaxSize().background(Background),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("CastLite", fontSize = 56.sp, fontWeight = FontWeight.Bold, color = Color.White)
        Spacer(Modifier.height(24.dp))
        Text("Waiting for phone…", fontSize = 28.sp, color = Color(0xFFB4BAC4))
        Spacer(Modifier.height(16.dp))
        Text(
            if (ip != null) "TV address: $ip" else "Not connected to a network",
            fontSize = 24.sp,
            color = if (ip != null) Accent else Color(0xFFFF8A80),
        )
    }
}
