package com.vkvych.remotecontrol.protocol

/**
 * Wire protocol shared by the controller ("parent") app and the agent ("child") app.
 *
 * This module is deliberately free of Android APIs so that future controllers for other
 * device kinds (a Samsung TV bridge, a desktop client) can reuse the same message types.
 *
 * Compatibility rules:
 *  - Both sides stamp [PROTOCOL_VERSION] on every message.
 *  - Adding optional fields or new command types is a *minor* change: peers ignore unknown
 *    fields, and an agent that receives a command it does not understand answers with
 *    [ErrorCode.UNSUPPORTED] instead of dropping the connection.
 *  - Renaming or removing a field, or changing the meaning of one, requires bumping
 *    [PROTOCOL_VERSION] and handling the older version explicitly.
 */
const val PROTOCOL_VERSION: Int = 1

/** Default TCP port the agent listens on. Fixed so pairing only needs a host name. */
const val DEFAULT_PORT: Int = 8765

/** HTTP routes exposed by the agent. */
object Routes {
    /** Unauthenticated liveness probe, used by the controller before pairing. */
    const val HEALTH: String = "/health"

    /** One-shot pairing exchange: a valid pairing code is traded for a long-lived token. */
    const val PAIR: String = "/pair"

    /** Authenticated WebSocket endpoint carrying commands and state updates. */
    const val CONTROL: String = "/control"
}

/** Authorization header scheme used on [Routes.CONTROL]. */
const val AUTH_SCHEME: String = "Bearer"

/** Number of digits in a pairing code. */
const val PAIRING_CODE_LENGTH: Int = 6

/** How long a generated pairing code stays valid. */
const val PAIRING_CODE_VALIDITY_MILLIS: Long = 5 * 60 * 1000L
