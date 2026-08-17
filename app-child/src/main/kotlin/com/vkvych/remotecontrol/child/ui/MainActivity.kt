package com.vkvych.remotecontrol.child.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge

/**
 * Setup and status surface for the child device.
 *
 * Deliberately the only screen: everything a parent does day to day happens from the controller
 * app, and everything here needs somebody physically holding this device.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            RemoteControlTheme {
                SetupScreen()
            }
        }
    }
}
