package com.vkvych.remotecontrol.protocol

import kotlinx.serialization.Serializable

/**
 * Response of [Routes.HEALTH]. Unauthenticated on purpose: the controller uses it to confirm it
 * typed the right host before asking the user for a pairing code.
 *
 * It exposes the device's display name, which is acceptable because the agent is only ever
 * reachable on the family's own tailnet or LAN — but it is the reason nothing more sensitive
 * belongs in this response.
 */
@Serializable
data class HealthResponse(
    val ok: Boolean = true,
    val deviceName: String,
    val paired: Boolean,
    val protocolVersion: Int = PROTOCOL_VERSION,
)

/** Body of `POST` [Routes.PAIR]. */
@Serializable
data class PairRequest(
    /** The [PAIRING_CODE_LENGTH]-digit code currently displayed by the agent app. */
    val code: String,
    /** Human-readable name of the controlling device, shown in the agent's UI. */
    val controllerName: String,
    val protocolVersion: Int = PROTOCOL_VERSION,
)

/** Success body of `POST` [Routes.PAIR]. The controller must persist [token]. */
@Serializable
data class PairResponse(
    val token: String,
    val deviceId: String,
    val deviceName: String,
    val protocolVersion: Int = PROTOCOL_VERSION,
)

/** Error body returned by the HTTP routes. */
@Serializable
data class ErrorResponse(
    val code: ErrorCode,
    val message: String,
)
