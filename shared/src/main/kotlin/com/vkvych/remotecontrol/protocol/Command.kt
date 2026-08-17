package com.vkvych.remotecontrol.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A single instruction from the controller to the agent.
 *
 * Phase 1 covers audio only. Later phases add app management (`ListApps`, `HideApp`,
 * `UninstallApp`, ...) as new subtypes; agents that predate a command answer
 * [ErrorCode.UNSUPPORTED], so a newer controller stays usable against an older agent.
 */
@Serializable
sealed interface Command

/** Ask for a fresh [DeviceState] snapshot without changing anything. */
@Serializable
@SerialName("get_state")
data object GetState : Command

/** Set [stream] to an absolute [level]. The agent clamps to the stream's supported range. */
@Serializable
@SerialName("set_volume")
data class SetVolume(
    val stream: AudioStream,
    val level: Int,
) : Command

/** Switch the ringer profile. */
@Serializable
@SerialName("set_ringer_mode")
data class SetRingerMode(
    val mode: RingerMode,
) : Command

/**
 * Convenience "panic button": mute or restore every stream the controller can address.
 *
 * The agent remembers the levels it muted from, so unmuting restores them rather than picking an
 * arbitrary default.
 */
@Serializable
@SerialName("set_muted")
data class SetMuted(
    val muted: Boolean,
) : Command
