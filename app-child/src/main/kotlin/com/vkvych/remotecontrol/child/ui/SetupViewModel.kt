package com.vkvych.remotecontrol.child.ui

import android.app.Application
import android.os.PowerManager
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vkvych.remotecontrol.child.agentContainer
import com.vkvych.remotecontrol.child.service.AgentService
import com.vkvych.remotecontrol.child.util.LocalAddress
import com.vkvych.remotecontrol.child.util.NetworkAddresses
import com.vkvych.remotecontrol.protocol.DEFAULT_PORT
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max

/** Everything the setup screen renders. */
data class SetupUiState(
    val agentRunning: Boolean = false,
    val deviceName: String = "",
    val port: Int = DEFAULT_PORT,
    val paired: Boolean = false,
    val controllerName: String? = null,
    val pairingCode: String? = null,
    val pairingSecondsLeft: Int = 0,
    val addresses: List<LocalAddress> = emptyList(),
    val notificationsGranted: Boolean = false,
    val dndAccessGranted: Boolean = false,
    val batteryUnrestricted: Boolean = false,
    val deviceOwner: Boolean = false,
)

class SetupViewModel(application: Application) : AndroidViewModel(application) {

    private val container = application.agentContainer
    private val powerManager = application.getSystemService(PowerManager::class.java)

    private val environment = MutableStateFlow(Environment())

    /**
     * Drives the pairing countdown and, just as importantly, expires the code even when nobody
     * ever calls [PairingSession.consume]. Only ticks while the screen is being observed.
     */
    private val secondTicker: Flow<Long> = flow {
        while (true) {
            container.pairingSession.pruneExpired()
            emit(System.currentTimeMillis())
            delay(1_000)
        }
    }

    val uiState: StateFlow<SetupUiState> = combine(
        AgentService.running,
        container.pairingSession.activeCode,
        environment,
        secondTicker,
    ) { running, activeCode, env, now ->
        SetupUiState(
            agentRunning = running,
            deviceName = env.deviceName,
            paired = env.paired,
            controllerName = env.controllerName,
            pairingCode = activeCode?.code,
            pairingSecondsLeft = activeCode
                ?.let { max(0L, (it.expiresAt - now + 999) / 1_000).toInt() }
                ?: 0,
            addresses = env.addresses,
            notificationsGranted = env.notificationsGranted,
            dndAccessGranted = env.dndAccessGranted,
            batteryUnrestricted = env.batteryUnrestricted,
            deviceOwner = env.deviceOwner,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), SetupUiState())

    init {
        refresh()
    }

    /** Re-reads permissions and addresses. Called whenever the screen comes back to the front. */
    fun refresh() {
        viewModelScope.launch {
            environment.value = withContext(Dispatchers.IO) { readEnvironment() }
        }
    }

    fun startAgent() {
        AgentService.start(getApplication())
        refresh()
    }

    fun stopAgent() {
        AgentService.stop(getApplication())
        container.pairingSession.cancel()
    }

    /**
     * Shows a fresh pairing code. The agent has to be serving for the controller to be able to
     * redeem it, so this starts it if needed rather than leaving the user with a code that cannot
     * work.
     */
    fun showPairingCode() {
        AgentService.start(getApplication())
        container.pairingSession.start()
        refresh()
    }

    fun hidePairingCode() {
        container.pairingSession.cancel()
    }

    fun unpair() {
        container.pairingStore.clearPairing()
        container.pairingSession.cancel()
        refresh()
    }

    private fun readEnvironment(): Environment {
        val application = getApplication<Application>()
        return Environment(
            deviceName = container.deviceInfo.deviceName(),
            paired = container.pairingStore.isPaired,
            controllerName = container.pairingStore.controllerName,
            addresses = NetworkAddresses.reachableAddresses(),
            notificationsGranted = NotificationManagerCompat.from(application).areNotificationsEnabled(),
            dndAccessGranted = container.audioController.hasDndAccess(),
            batteryUnrestricted = powerManager.isIgnoringBatteryOptimizations(application.packageName),
            deviceOwner = container.deviceInfo.isDeviceOwner(),
        )
    }

    private data class Environment(
        val deviceName: String = "",
        val paired: Boolean = false,
        val controllerName: String? = null,
        val addresses: List<LocalAddress> = emptyList(),
        val notificationsGranted: Boolean = false,
        val dndAccessGranted: Boolean = false,
        val batteryUnrestricted: Boolean = false,
        val deviceOwner: Boolean = false,
    )

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
