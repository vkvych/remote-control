package com.vkvych.remotecontrol.parent.ui

import android.app.Application
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vkvych.remotecontrol.parent.controllerContainer
import com.vkvych.remotecontrol.parent.data.PairedDevice
import com.vkvych.remotecontrol.protocol.DEFAULT_PORT
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PairingUiState(
    val host: String = "",
    val port: String = DEFAULT_PORT.toString(),
    val code: String = "",
    val busy: Boolean = false,
    /** Name reported by the agent once the address is confirmed reachable. */
    val foundDeviceName: String? = null,
    val error: String? = null,
) {
    val canProbe: Boolean get() = host.isNotBlank() && port.toIntOrNull() != null && !busy
    val canPair: Boolean get() = canProbe && code.length == PAIRING_CODE_DIGITS
}

private const val PAIRING_CODE_DIGITS = 6

/**
 * Drives the two-step pairing flow: confirm the address is reachable, then redeem the code.
 *
 * Splitting it in two matters for a system addressed by raw IP — a typo in the host would
 * otherwise surface as "wrong code", sending the user back to the child device for a new code that
 * was never the problem.
 */
class PairingViewModel(application: Application) : AndroidViewModel(application) {

    private val container = application.controllerContainer

    private val _uiState = MutableStateFlow(PairingUiState())
    val uiState: StateFlow<PairingUiState> = _uiState.asStateFlow()

    fun onHostChange(value: String) {
        _uiState.update { it.copy(host = value.trim(), foundDeviceName = null, error = null) }
    }

    fun onPortChange(value: String) {
        _uiState.update { it.copy(port = value.filter { char -> char.isDigit() }, foundDeviceName = null) }
    }

    fun onCodeChange(value: String) {
        _uiState.update {
            it.copy(code = value.filter { char -> char.isDigit() }.take(PAIRING_CODE_DIGITS), error = null)
        }
    }

    /** Checks that an agent is actually listening, so a bad address is reported as a bad address. */
    fun probe() {
        val state = _uiState.value
        val port = state.port.toIntOrNull() ?: return

        _uiState.update { it.copy(busy = true, error = null, foundDeviceName = null) }
        viewModelScope.launch {
            container.pairingClient.probe(state.host, port)
                .onSuccess { health ->
                    _uiState.update { it.copy(busy = false, foundDeviceName = health.deviceName) }
                }
                .onFailure { failure ->
                    _uiState.update {
                        it.copy(
                            busy = false,
                            error = "Could not reach an agent at ${state.host}:$port. " +
                                "Check the address on the child device and that Tailscale is up. " +
                                "(${failure.message})",
                        )
                    }
                }
        }
    }

    /**
     * Redeems the code and stores the resulting token. No completion callback: saving the device
     * makes [com.vkvych.remotecontrol.parent.data.DeviceStore.pairedDevice] emit, and the UI
     * switches to the dashboard off the back of that.
     */
    fun pair() {
        val state = _uiState.value
        val port = state.port.toIntOrNull() ?: return

        _uiState.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            container.pairingClient
                .pair(
                    host = state.host,
                    port = port,
                    code = state.code,
                    controllerName = controllerName(),
                )
                .onSuccess { response ->
                    container.deviceStore.save(
                        PairedDevice(
                            host = state.host,
                            port = port,
                            token = response.token,
                            deviceId = response.deviceId,
                            deviceName = response.deviceName,
                        ),
                    )
                    _uiState.update { it.copy(busy = false) }
                }
                .onFailure { failure ->
                    _uiState.update {
                        it.copy(busy = false, error = failure.message ?: "Pairing failed")
                    }
                }
        }
    }

    /** Shown on the child device so it is obvious which phone is in control. */
    private fun controllerName(): String = "${Build.MANUFACTURER} ${Build.MODEL}".trim()
}
