package com.vkvych.remotecontrol.parent.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.vkvych.remotecontrol.protocol.DEFAULT_PORT
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** A child device this controller has been paired with. */
data class PairedDevice(
    val host: String,
    val port: Int = DEFAULT_PORT,
    val token: String,
    val deviceId: String,
    val deviceName: String,
) {
    val webSocketUrl: String get() = "ws://$host:$port"
}

private val Context.dataStore by preferencesDataStore(name = "paired_device")

/**
 * Remembers the paired child device, token included.
 *
 * The token is app-private and never leaves the device, so it is stored as-is: unlike the agent,
 * the controller has to be able to present the original value on every connection, which rules out
 * storing only a hash.
 *
 * Phase 1 tracks a single device. When more arrive, this becomes a keyed collection and the
 * dashboard grows a picker — nothing above this layer assumes there is only one.
 */
class DeviceStore(context: Context) {

    private val dataStore = context.applicationContext.dataStore

    val pairedDevice: Flow<PairedDevice?> = dataStore.data.map { preferences ->
        val host = preferences[KEY_HOST] ?: return@map null
        val token = preferences[KEY_TOKEN] ?: return@map null
        PairedDevice(
            host = host,
            port = preferences[KEY_PORT] ?: DEFAULT_PORT,
            token = token,
            deviceId = preferences[KEY_DEVICE_ID].orEmpty(),
            deviceName = preferences[KEY_DEVICE_NAME].orEmpty(),
        )
    }

    suspend fun save(device: PairedDevice) {
        dataStore.edit { preferences ->
            preferences[KEY_HOST] = device.host
            preferences[KEY_PORT] = device.port
            preferences[KEY_TOKEN] = device.token
            preferences[KEY_DEVICE_ID] = device.deviceId
            preferences[KEY_DEVICE_NAME] = device.deviceName
        }
    }

    suspend fun clear() {
        dataStore.edit { it.clear() }
    }

    private companion object {
        val KEY_HOST = stringPreferencesKey("host")
        val KEY_PORT = intPreferencesKey("port")
        val KEY_TOKEN = stringPreferencesKey("token")
        val KEY_DEVICE_ID = stringPreferencesKey("device_id")
        val KEY_DEVICE_NAME = stringPreferencesKey("device_name")
    }
}
