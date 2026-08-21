package com.vkvych.remotecontrol.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Why a command could not be carried out. */
@Serializable
enum class ErrorCode {
    /** Missing or invalid token / pairing code. */
    UNAUTHORIZED,

    /** Peer speaks a protocol version this side cannot handle. */
    PROTOCOL_VERSION_MISMATCH,

    /** Well-formed command with unusable arguments. */
    INVALID_ARGUMENT,

    /** The agent needs a permission the user has not granted yet (see [Capabilities]). */
    PERMISSION_REQUIRED,

    /** The agent does not know this command — typically a newer controller. */
    UNSUPPORTED,

    /** Anything unexpected on the agent side. */
    INTERNAL,
}

/**
 * Controller -> agent. [id] correlates the eventual [CommandReply]; the controller generates it and
 * the agent echoes it back verbatim.
 */
@Serializable
data class ClientMessage(
    val id: String,
    val command: Command,
    val protocolVersion: Int = PROTOCOL_VERSION,
)

/** Result of one command. */
@Serializable
sealed interface CommandOutcome

/** Command applied. [state] carries the post-command snapshot for mutating commands. */
@Serializable
@SerialName("ok")
data class Success(
    val state: DeviceState? = null,
) : CommandOutcome

@Serializable
@SerialName("error")
data class Failure(
    val code: ErrorCode,
    val message: String,
) : CommandOutcome

/** Agent -> controller. */
@Serializable
sealed interface ServerMessage {
    val protocolVersion: Int
}

/** Answer to the [ClientMessage] with the same [id]. */
@Serializable
@SerialName("reply")
data class CommandReply(
    val id: String,
    val outcome: CommandOutcome,
    override val protocolVersion: Int = PROTOCOL_VERSION,
) : ServerMessage

/**
 * Unsolicited snapshot, pushed when the device changes on its own — for example the child pressing
 * the hardware volume keys. Keeps the controller's sliders honest without polling.
 */
@Serializable
@SerialName("state")
data class StateUpdate(
    val state: DeviceState,
    override val protocolVersion: Int = PROTOCOL_VERSION,
) : ServerMessage
