package com.castlite.phone.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.castlite.phone.ui.ResumePrompt
import com.castlite.phone.ui.components.formatDuration
import com.castlite.phone.ui.theme.Palette
import com.castlite.protocol.SlideTransition
import com.castlite.protocol.SlideshowOptions

@Composable
fun LinkDialog(onAdd: (String) -> Unit, onDismiss: () -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Palette.SurfaceHigh,
        title = { Text("Cast a web link") },
        text = {
            Column {
                Text(
                    "Paste a direct link to a video, song or photo (e.g. ending in .mp4, .mp3, .jpg) or a live stream (.m3u8). The TV plays it straight from the internet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Palette.TextSecondary,
                )
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.trim() },
                    placeholder = { Text("https://…/video.mp4") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (text.isNotBlank()) onAdd(text) }),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Palette.Cyan, cursorColor = Palette.Cyan),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onAdd(text) }, enabled = text.isNotBlank()) { Text("Add", color = Palette.Cyan) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = Palette.TextSecondary) } },
    )
}

@Composable
fun SlideshowDialog(options: SlideshowOptions, onChange: (SlideshowOptions) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Palette.SurfaceHigh,
        title = { Text("Photo slideshow") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Label("Each photo stays")
                ChipRow(listOf(3_000L, 5_000L, 8_000L, 15_000L), options.durationMs, { "${it / 1000} s" }) { onChange(options.copy(durationMs = it)) }
                Label("Transition (CastLite TV app)")
                ChipRow(SlideTransition.entries, options.transition, { it.name.lowercase().replaceFirstChar(Char::uppercase) }) { onChange(options.copy(transition = it)) }
                ToggleRow("Slow pan & zoom", "Gentle Ken Burns motion on each photo", options.kenBurns) { onChange(options.copy(kenBurns = it)) }
                ToggleRow("Music during slideshow", "Songs in the queue play behind the photos (CastLite TV app)", options.music) { onChange(options.copy(music = it)) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done", color = Palette.Cyan) } },
    )
}

@Composable
private fun Label(text: String) = Text(text, style = MaterialTheme.typography.labelLarge, color = Palette.TextSecondary)

@Composable
private fun <T> ChipRow(values: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        values.forEach { v ->
            FilterChip(
                selected = v == selected,
                onClick = { onSelect(v) },
                label = { Text(label(v)) },
                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Palette.Cyan.copy(alpha = 0.2f), selectedLabelColor = Palette.Cyan),
            )
        }
    }
}

@Composable
private fun ToggleRow(title: String, body: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = Palette.TextPrimary)
            Text(body, style = MaterialTheme.typography.bodySmall, color = Palette.TextTertiary)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange, colors = SwitchDefaults.colors(checkedTrackColor = Palette.Cyan))
    }
}

@Composable
fun ResumeDialog(prompt: ResumePrompt, onAnswer: (Boolean) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Palette.SurfaceHigh,
        title = { Text("Continue watching?") },
        text = {
            Text(
                "You stopped \"${prompt.title}\" at ${formatDuration(prompt.positionMs)}.",
                style = MaterialTheme.typography.bodyMedium,
                color = Palette.TextSecondary,
            )
        },
        confirmButton = { TextButton(onClick = { onAnswer(true) }) { Text("Resume at ${formatDuration(prompt.positionMs)}", color = Palette.Cyan) } },
        dismissButton = { TextButton(onClick = { onAnswer(false) }) { Text("Start over", color = Palette.TextSecondary) } },
    )
}
