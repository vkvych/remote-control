package com.vkvych.remotecontrol.parent.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vkvych.remotecontrol.parent.controllerContainer
import com.vkvych.remotecontrol.parent.data.PairedDevice
import com.vkvych.remotecontrol.parent.net.ConnectionState
import com.vkvych.remotecontrol.protocol.AudioStream
import com.vkvych.remotecontrol.protocol.Command
import com.vkvych.remotecontrol.protocol.DeviceState
import com.vkvych.remotecontrol.protocol.Failure
import com.vkvych.remotecontrol.protocol.GetState
import com.vkvych.remotecontrol.protocol.RingerMode
import com.vkvych.remotecontrol.protocol.SetMuted
import com.vkvych.remotecontrol.protocol.SetRingerMode
import com.vkvych.remotecontrol.protocol.SetVolume
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DashboardUiState(
    val connection: ConnectionState = ConnectionState.Disconnected,
    val device: DeviceState? = null,
    val pairedName: String = "",
    /**
     * Levels the user is dragging right now, which take precedence over the device's reported
     * value so the slider does not fight the thumb while an update is in flight.
     */
    val pendingLevels: Map<AudioStream, Int> = emptyMap(),
    val message: String? = null,
) {
    fun levelOf(stream: AudioStream): Int? =
        pendingLevels[stream] ?: device?.volumeOf(stream)?.level
}

/** The streams worth exposing to a parent; system beeps and in-call volume only add noise. */
val CONTROLLABLE_STREAMS: List<AudioStream> = listOf(
    AudioStream.MUSIC,
    AudioStream.RING,
    AudioStream.NOTIFICATION,
    AudioStream.ALARM,
)

class DashboardViewModel(application: Application) : AndroidViewModel(application) {

    private val container = application.controllerContainer
    private val client = container.controlClient

    private val pendingLevels = MutableStateFlow<Map<AudioStream, Int>>(emptyMap())
    private val message = MutableStateFlow<String?>(null)
    private val pairedDevice = MutableStateFlow<PairedDevice?>(null)

    val uiState: StateFlow<DashboardUiState> = combine(
        client.connectionState,
        client.deviceState,
        pendingLevels,
        message,
        pairedDevice,
    ) { connection, device, pending, currentMessage, paired ->
        DashboardUiState(
            connection = connection,
            device = device,
            pairedName = device?.deviceName ?: paired?.deviceName.orEmpty(),
            pendingLevels = pending,
            message = currentMessage,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), DashboardUiState())

    init {
        // The store is the source of truth for *which* device to talk to; the client keeps the
        // connection to it alive for as long as one is configured.
        viewModelScope.launch {
            container.deviceStore.pairedDevice.filterNotNull().collect { device ->
                pairedDevice.value = device
                client.connect(device)
            }
        }
    }

    /** Called continuously while a slider is dragged; deliberately sends nothing. */
    fun onVolumeDrag(stream: AudioStream, level: Int) {
        pendingLevels.update { it + (stream to level) }
    }

    /** Called once the thumb is released — one command per gesture instead of dozens. */
    fun onVolumeCommit(stream: AudioStream) {
        val level = pendingLevels.value[stream] ?: return
        viewModelScope.launch {
            run(SetVolume(stream, level))
            pendingLevels.update { it - stream }
        }
    }

    fun setRingerMode(mode: RingerMode) {
        viewModelScope.launch { run(SetRingerMode(mode)) }
    }

    fun setMuted(muted: Boolean) {
        viewModelScope.launch { run(SetMuted(muted)) }
    }

    fun refresh() {
        viewModelScope.launch { run(GetState) }
    }

    fun dismissMessage() {
        message.value = null
    }

    /** Unpairs locally. The agent keeps its token until somebody pairs a new controller. */
    fun forgetDevice() {
        viewModelScope.launch {
            client.disconnect()
            container.deviceStore.clear()
        }
    }

    private suspend fun run(command: Command) {
        val outcome = client.send(command)
        if (outcome is Failure) message.value = outcome.message
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
