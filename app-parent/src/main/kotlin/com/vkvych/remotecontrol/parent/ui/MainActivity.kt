package com.vkvych.remotecontrol.parent.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vkvych.remotecontrol.parent.controllerContainer

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            RemoteControlTheme {
                ControllerApp()
            }
        }
    }
}

/**
 * Whether a device is paired is the only navigation state there is, so it is read straight from
 * the store rather than wired through a navigation library: pairing saves a device, the flow
 * emits, and the dashboard replaces the pairing screen.
 */
@Composable
private fun ControllerApp() {
    val container = LocalContext.current.controllerContainer
    val pairedDevice by container.deviceStore.pairedDevice.collectAsStateWithLifecycle(initialValue = null)

    if (pairedDevice == null) {
        PairingScreen()
    } else {
        DashboardScreen()
    }
}
