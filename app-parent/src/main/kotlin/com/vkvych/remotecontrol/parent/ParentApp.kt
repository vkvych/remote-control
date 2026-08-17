package com.vkvych.remotecontrol.parent

import android.app.Application
import android.content.Context
import com.vkvych.remotecontrol.parent.data.DeviceStore
import com.vkvych.remotecontrol.parent.net.ControlClient
import com.vkvych.remotecontrol.parent.net.PairingClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob

/**
 * Application-scoped collaborators.
 *
 * [ControlClient] in particular must outlive any single screen: its whole job is to hold a
 * connection open and keep reconnecting, which it cannot do if it dies with a ViewModel on
 * rotation.
 */
class ControllerContainer(context: Context) {

    private val appContext = context.applicationContext
    private val appScope = CoroutineScope(SupervisorJob())

    val deviceStore: DeviceStore by lazy { DeviceStore(appContext) }
    val pairingClient: PairingClient by lazy { PairingClient() }
    val controlClient: ControlClient by lazy { ControlClient(appScope) }
}

class ParentApp : Application() {

    val container: ControllerContainer by lazy { ControllerContainer(this) }
}

val Context.controllerContainer: ControllerContainer
    get() = (applicationContext as ParentApp).container
