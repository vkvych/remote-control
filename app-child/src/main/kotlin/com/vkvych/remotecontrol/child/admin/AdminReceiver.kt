package com.vkvych.remotecontrol.child.admin

import android.app.admin.DeviceAdminReceiver
import android.content.ComponentName
import android.content.Context
import android.util.Log

/**
 * Device administration entry point.
 *
 * Phase 1 does not exercise any policy — this exists so the component is present in the very first
 * installed build. Device Owner can only be provisioned on a device with no configured accounts,
 * so introducing this receiver in a later release would mean a factory reset of the child device.
 *
 * Provisioning (see docs/SETUP.md):
 * `adb shell dpm set-device-owner com.vkvych.remotecontrol.child/.admin.AdminReceiver`
 */
class AdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: android.content.Intent) {
        Log.i(TAG, "Device admin enabled")
    }

    override fun onDisabled(context: Context, intent: android.content.Intent) {
        Log.w(TAG, "Device admin disabled — app management is unavailable until re-provisioned")
    }

    companion object {
        private const val TAG = "AdminReceiver"

        fun componentName(context: Context): ComponentName =
            ComponentName(context.applicationContext, AdminReceiver::class.java)
    }
}
