package com.vkvych.remotecontrol.child.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.vkvych.remotecontrol.child.agentContainer

/**
 * Brings the agent back after a reboot.
 *
 * Only starts once a controller is paired: before that there is nothing to serve, and the setup
 * screen starts the service itself when the user asks for a pairing code.
 *
 * `specialUse` is one of the foreground-service types Android still permits to start from
 * `BOOT_COMPLETED`; once the app is provisioned as Device Owner it is exempt from these background
 * restrictions entirely.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        if (!context.agentContainer.pairingStore.isPaired) {
            Log.i(TAG, "Not paired yet — leaving the agent stopped after boot")
            return
        }

        Log.i(TAG, "Restarting agent after boot")
        AgentService.start(context)
    }

    private companion object {
        const val TAG = "BootReceiver"
    }
}
