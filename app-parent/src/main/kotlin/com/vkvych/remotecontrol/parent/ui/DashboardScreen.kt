package com.vkvych.remotecontrol.parent.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vkvych.remotecontrol.parent.net.ConnectionState
import com.vkvych.remotecontrol.protocol.AudioStream
import com.vkvych.remotecontrol.protocol.RingerMode
import com.vkvych.remotecontrol.protocol.StreamVolume
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(viewModel: DashboardViewModel = viewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.message) {
        state.message?.let { message ->
            snackbarHostState.showSnackbar(message)
            viewModel.dismissMessage()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.pairedName.ifBlank { "Child device" }) },
                actions = {
                    TextButton(onClick = viewModel::refresh) { Text("Refresh") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(vertical = 12.dp),
        ) {
            item { ConnectionCard(state) }

            val device = state.device
            if (device != null) {
                if (!device.capabilities.dndAccess) {
                    item { DndWarningCard() }
                }

                item {
                    VolumeCard(
                        state = state,
                        volumes = device.volumes,
                        onDrag = viewModel::onVolumeDrag,
                        onCommit = viewModel::onVolumeCommit,
                    )
                }

                item {
                    RingerCard(
                        current = device.ringerMode,
                        onSelect = viewModel::setRingerMode,
                        onMute = { viewModel.setMuted(true) },
                        onUnmute = { viewModel.setMuted(false) },
                    )
                }
            }

            item { ForgetCard(onForget = viewModel::forgetDevice) }
        }
    }
}

@Composable
private fun ConnectionCard(state: DashboardUiState) {
    val connection = state.connection
    val (ok, label) = when (connection) {
        ConnectionState.Connected -> true to "Connected"
        ConnectionState.Connecting -> false to "Connecting…"
        ConnectionState.Disconnected -> false to "Disconnected"
        is ConnectionState.Reconnecting -> false to "Reconnecting — ${connection.message}"
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    Modifier
                        .size(10.dp)
                        .background(
                            color = if (ok) OkGreen else MaterialTheme.colorScheme.error,
                            shape = CircleShape,
                        ),
                )
                Text(label, style = MaterialTheme.typography.bodyMedium)
            }

            state.device?.battery?.let { battery ->
                Spacer(Modifier.height(8.dp))
                Text(
                    "Battery ${battery.percent}%" + if (battery.charging) " · charging" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun DndWarningCard() {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Do Not Disturb access missing", fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(
                "Ring and notification volume cannot be changed while Do Not Disturb is on. " +
                    "Grant \"Do Not Disturb access\" in the agent app on the child device.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun VolumeCard(
    state: DashboardUiState,
    volumes: List<StreamVolume>,
    onDrag: (AudioStream, Int) -> Unit,
    onCommit: (AudioStream) -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Volume", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))

            CONTROLLABLE_STREAMS.forEach { stream ->
                val volume = volumes.firstOrNull { it.stream == stream } ?: return@forEach
                VolumeSlider(
                    stream = stream,
                    volume = volume,
                    level = state.levelOf(stream) ?: volume.level,
                    onDrag = onDrag,
                    onCommit = onCommit,
                )
            }
        }
    }
}

@Composable
private fun VolumeSlider(
    stream: AudioStream,
    volume: StreamVolume,
    level: Int,
    onDrag: (AudioStream, Int) -> Unit,
    onCommit: (AudioStream) -> Unit,
) {
    Column(Modifier.padding(vertical = 4.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(stream.label, style = MaterialTheme.typography.bodyMedium)
            Text("$level / ${volume.max}", style = MaterialTheme.typography.bodySmall)
        }
        Slider(
            value = level.toFloat(),
            onValueChange = { onDrag(stream, it.roundToInt()) },
            // Sending on every pixel of the drag would flood the link; one command per gesture is
            // enough, and the agent answers with the level it actually applied.
            onValueChangeFinished = { onCommit(stream) },
            valueRange = volume.min.toFloat()..volume.max.toFloat(),
            steps = (volume.max - volume.min - 1).coerceAtLeast(0),
        )
    }
}

@Composable
private fun RingerCard(
    current: RingerMode,
    onSelect: (RingerMode) -> Unit,
    onMute: () -> Unit,
    onUnmute: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Ringer", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RingerMode.entries.forEach { mode ->
                    if (mode == current) {
                        Button(onClick = { onSelect(mode) }) { Text(mode.label) }
                    } else {
                        OutlinedButton(onClick = { onSelect(mode) }) { Text(mode.label) }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            Text("Quick actions", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onMute) { Text("Silence everything") }
                OutlinedButton(onClick = onUnmute) { Text("Restore") }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "Alarms are left alone on purpose.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ForgetCard(onForget: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Paired device", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                "Forgetting the device only clears this phone. To stop it accepting this app " +
                    "entirely, unpair from the agent app on the child device.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onForget) { Text("Forget device") }
        }
    }
}

private val AudioStream.label: String
    get() = when (this) {
        AudioStream.MUSIC -> "Media"
        AudioStream.RING -> "Ringtone"
        AudioStream.NOTIFICATION -> "Notifications"
        AudioStream.ALARM -> "Alarm"
        AudioStream.SYSTEM -> "System"
        AudioStream.VOICE_CALL -> "Calls"
    }

private val RingerMode.label: String
    get() = when (this) {
        RingerMode.NORMAL -> "Normal"
        RingerMode.VIBRATE -> "Vibrate"
        RingerMode.SILENT -> "Silent"
    }

private val OkGreen = Color(0xFF2E7D32)
