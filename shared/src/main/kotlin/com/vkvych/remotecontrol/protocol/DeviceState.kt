package com.vkvych.remotecontrol.protocol

import kotlinx.serialization.Serializable

/**
 * Audio streams the controller can address.
 *
 * Deliberately named after the concept rather than the Android constant, so the agent side owns
 * the mapping to `AudioManager.STREAM_*` and other platforms can map them their own way.
 */
@Serializable
enum class AudioStream {
    MUSIC,
    RING,
    NOTIFICATION,
    ALARM,
    SYSTEM,
    VOICE_CALL,
}

/** Ringer profile, mirroring Android's three ringer modes. */
@Serializable
enum class RingerMode {
    NORMAL,
    VIBRATE,
    SILENT,
}

/** Volume of a single [AudioStream]. [level] is always within `min..max`. */
@Serializable
data class StreamVolume(
    val stream: AudioStream,
    val level: Int,
    val max: Int,
    val min: Int = 0,
) {
    /** Volume as a 0f..1f fraction, for driving a slider. */
    val fraction: Float
        get() = if (max <= min) 0f else (level - min).toFloat() / (max - min).toFloat()
}

@Serializable
data class BatteryState(
    val percent: Int,
    val charging: Boolean,
)

/**
 * Capabilities the agent currently has. The controller uses these to explain *why* an action is
 * unavailable rather than silently failing.
 */
@Serializable
data class Capabilities(
    /** `ACCESS_NOTIFICATION_POLICY` granted — required to change ring volume while DND is on. */
    val dndAccess: Boolean = false,
    /** The agent is provisioned as Device Owner (unlocks app management in Phase 2). */
    val deviceOwner: Boolean = false,
    /** A Samsung Knox license has been activated (Phase 3). */
    val knoxActive: Boolean = false,
)

/**
 * Full snapshot of the controlled device. Sent in reply to [Command.GetState], after every
 * successful mutating command, and pushed unsolicited when something changes on the device itself.
 */
@Serializable
data class DeviceState(
    val deviceId: String,
    val deviceName: String,
    val volumes: List<StreamVolume>,
    val ringerMode: RingerMode,
    val battery: BatteryState? = null,
    val capabilities: Capabilities = Capabilities(),
    /** Agent clock, epoch millis. Used only to order state updates, never for authorization. */
    val timestamp: Long = 0L,
    val protocolVersion: Int = PROTOCOL_VERSION,
) {
    fun volumeOf(stream: AudioStream): StreamVolume? = volumes.firstOrNull { it.stream == stream }
}
