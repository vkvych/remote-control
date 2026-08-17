package com.vkvych.remotecontrol.child

import android.app.Application
import android.content.Context
import com.vkvych.remotecontrol.child.control.AudioController
import com.vkvych.remotecontrol.child.control.CommandExecutor
import com.vkvych.remotecontrol.child.control.DeviceInfoProvider
import com.vkvych.remotecontrol.child.control.DeviceStateRepository
import com.vkvych.remotecontrol.child.data.PairingStore
import com.vkvych.remotecontrol.child.server.PairingSession

/**
 * Hand-rolled dependency container.
 *
 * The agent's collaborators are stateful and must be *the same instances* in the setup UI and in
 * the service: the UI shows the pairing code that the server validates, and both read the same
 * device-state snapshot. A single application-scoped container is the simplest way to guarantee
 * that without pulling in a DI framework.
 */
class AgentContainer(context: Context) {
    private val appContext: Context = context.applicationContext

    val pairingStore: PairingStore by lazy { PairingStore(appContext) }
    val pairingSession: PairingSession by lazy { PairingSession() }
    val audioController: AudioController by lazy { AudioController(appContext) }
    val deviceInfo: DeviceInfoProvider by lazy { DeviceInfoProvider(appContext) }
    val stateRepository: DeviceStateRepository by lazy {
        DeviceStateRepository(audioController, deviceInfo, pairingStore)
    }
    val commandExecutor: CommandExecutor by lazy { CommandExecutor(audioController, stateRepository) }
}

class ChildApp : Application() {

    val container: AgentContainer by lazy { AgentContainer(this) }
}

/** Container for this process. Safe from any [Context], including service and receiver contexts. */
val Context.agentContainer: AgentContainer
    get() = (applicationContext as ChildApp).container
