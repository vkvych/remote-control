package com.vkvych.remotecontrol.child.control

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.os.BatteryManager
import android.os.Build
import android.provider.Settings
import com.vkvych.remotecontrol.protocol.BatteryState

/** Identity and health of the controlled device, for display in the controller app. */
class DeviceInfoProvider(context: Context) {

    private val appContext = context.applicationContext
    private val batteryManager = appContext.getSystemService(BatteryManager::class.java)
    private val devicePolicyManager = appContext.getSystemService(DevicePolicyManager::class.java)

    /** The name the user gave the device in Settings, falling back to the model. */
    fun deviceName(): String =
        Settings.Global.getString(appContext.contentResolver, Settings.Global.DEVICE_NAME)
            ?.takeIf { it.isNotBlank() }
            ?: Build.MODEL

    fun battery(): BatteryState? {
        val percent = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        if (percent < 0) return null
        return BatteryState(percent = percent, charging = batteryManager.isCharging)
    }

    /** Whether this app has been provisioned as Device Owner, which unlocks Phase 2 app management. */
    fun isDeviceOwner(): Boolean = devicePolicyManager.isDeviceOwnerApp(appContext.packageName)
}
