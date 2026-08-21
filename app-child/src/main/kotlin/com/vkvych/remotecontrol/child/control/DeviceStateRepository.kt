package com.vkvych.remotecontrol.child.control

import com.vkvych.remotecontrol.child.data.PairingStore
import com.vkvych.remotecontrol.protocol.Capabilities
import com.vkvych.remotecontrol.protocol.DeviceState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The single source of truth for what the controller sees.
 *
 * Connected controllers collect [state], so anything that changes the device — a command from the
 * parent, or the child pressing the hardware volume keys — becomes a push simply by calling
 * [refresh].
 */
class DeviceStateRepository(
    private val audioController: AudioController,
    private val deviceInfo: DeviceInfoProvider,
    private val pairingStore: PairingStore,
) {

    private val _state = MutableStateFlow(snapshot(timestamp = System.currentTimeMillis()))

    val state: StateFlow<DeviceState> = _state.asStateFlow()

    /**
     * Re-reads the device and publishes the result if anything meaningful changed.
     *
     * The timestamp is deliberately excluded from the comparison and carried over when nothing
     * else moved: otherwise every poll would look like a change and wake up every connected
     * controller for nothing.
     */
    fun refresh(): DeviceState {
        val current = _state.value
        val fresh = snapshot(timestamp = System.currentTimeMillis())

        if (fresh.copy(timestamp = current.timestamp) == current) return current

        _state.value = fresh
        return fresh
    }

    private fun snapshot(timestamp: Long) = DeviceState(
        deviceId = pairingStore.deviceId,
        deviceName = deviceInfo.deviceName(),
        volumes = audioController.volumes(),
        ringerMode = audioController.ringerMode(),
        battery = deviceInfo.battery(),
        capabilities = Capabilities(
            dndAccess = audioController.hasDndAccess(),
            deviceOwner = deviceInfo.isDeviceOwner(),
            knoxActive = false,
        ),
        timestamp = timestamp,
    )
}
