package com.vkvych.remotecontrol.child.data

import android.content.Context
import androidx.core.content.edit
import com.vkvych.remotecontrol.protocol.Credentials
import java.util.UUID

/**
 * Durable record of which controller this device is paired with.
 *
 * Only the SHA-256 hash of the issued token is kept, so the file is useless to anyone who reads it
 * — it cannot be replayed as a credential. The plaintext token exists only in the pairing response
 * and in the controller's own storage.
 *
 * A device is paired with exactly one controller: pairing again replaces the previous token, which
 * immediately locks out the old controller. That is the intended way to recover from a lost or
 * reinstalled parent phone.
 */
class PairingStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("pairing", Context.MODE_PRIVATE)

    /** Stable identifier for this device, generated on first use. */
    val deviceId: String
        get() = synchronized(this) {
            prefs.getString(KEY_DEVICE_ID, null) ?: UUID.randomUUID().toString().also { generated ->
                prefs.edit { putString(KEY_DEVICE_ID, generated) }
            }
        }

    val isPaired: Boolean
        get() = prefs.getString(KEY_TOKEN_HASH, null) != null

    val controllerName: String?
        get() = prefs.getString(KEY_CONTROLLER_NAME, null)

    val pairedAt: Long
        get() = prefs.getLong(KEY_PAIRED_AT, 0L)

    /** Records the pairing, storing only the token's hash. */
    fun savePairing(token: String, controllerName: String) {
        prefs.edit {
            putString(KEY_TOKEN_HASH, Credentials.hashToken(token))
            putString(KEY_CONTROLLER_NAME, controllerName)
            putLong(KEY_PAIRED_AT, System.currentTimeMillis())
        }
    }

    fun clearPairing() {
        prefs.edit {
            remove(KEY_TOKEN_HASH)
            remove(KEY_CONTROLLER_NAME)
            remove(KEY_PAIRED_AT)
        }
    }

    /** True when [token] is the token issued at pairing time. */
    fun verifyToken(token: String): Boolean {
        val stored = prefs.getString(KEY_TOKEN_HASH, null) ?: return false
        return Credentials.secretsMatch(Credentials.hashToken(token), stored)
    }

    private companion object {
        const val KEY_DEVICE_ID = "device_id"
        const val KEY_TOKEN_HASH = "token_hash"
        const val KEY_CONTROLLER_NAME = "controller_name"
        const val KEY_PAIRED_AT = "paired_at"
    }
}
