package com.vkvych.remotecontrol.protocol

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive

/**
 * JSON configuration used on the wire by both apps.
 *
 * `ignoreUnknownKeys` lets an older peer accept messages from a newer one that added optional
 * fields; `encodeDefaults` guarantees `protocolVersion` is always present even when it equals the
 * default.
 */
val ProtocolJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    classDiscriminator = "type"
}

/** Outcome of parsing an inbound controller message. */
sealed interface DecodedClientMessage {
    data class Parsed(val message: ClientMessage) : DecodedClientMessage

    /**
     * The payload could not be turned into a [ClientMessage]. [id] is recovered from the raw JSON
     * whenever possible so the agent can still answer with a correlated [CommandReply] instead of
     * leaving the controller waiting.
     */
    data class Undecodable(
        val id: String?,
        val code: ErrorCode,
        val reason: String,
    ) : DecodedClientMessage
}

/**
 * Encodes and decodes protocol messages.
 *
 * Decoding of controller messages is deliberately defensive: an agent must never drop a connection
 * because it met a command from a newer controller.
 */
object MessageCodec {

    fun encode(message: ClientMessage): String = ProtocolJson.encodeToString(message)

    fun encode(message: ServerMessage): String = ProtocolJson.encodeToString(message)

    /** Throws [SerializationException] — the controller only ever talks to an agent it paired with. */
    fun decodeServerMessage(text: String): ServerMessage = ProtocolJson.decodeFromString(text)

    fun decodeClientMessage(text: String): DecodedClientMessage {
        val root = try {
            ProtocolJson.parseToJsonElement(text) as? JsonObject
                ?: return DecodedClientMessage.Undecodable(
                    id = null,
                    code = ErrorCode.INVALID_ARGUMENT,
                    reason = "Expected a JSON object",
                )
        } catch (e: SerializationException) {
            return DecodedClientMessage.Undecodable(
                id = null,
                code = ErrorCode.INVALID_ARGUMENT,
                reason = e.message ?: "Malformed JSON",
            )
        }

        val id = (root["id"] as? JsonPrimitive)?.takeIf { it.isString }?.content

        val version = try {
            root["protocolVersion"]?.jsonPrimitive?.int
        } catch (_: IllegalArgumentException) {
            null
        }
        if (version != null && version != PROTOCOL_VERSION) {
            return DecodedClientMessage.Undecodable(
                id = id,
                code = ErrorCode.PROTOCOL_VERSION_MISMATCH,
                reason = "Controller speaks protocol v$version, agent speaks v$PROTOCOL_VERSION",
            )
        }

        return try {
            DecodedClientMessage.Parsed(ProtocolJson.decodeFromJsonElement(ClientMessage.serializer(), root))
        } catch (e: SerializationException) {
            // Arguments that are merely out of range are validated by the command handlers, so a
            // failure here means the command shape itself is unknown to this agent.
            DecodedClientMessage.Undecodable(
                id = id,
                code = ErrorCode.UNSUPPORTED,
                reason = e.message ?: "Unrecognised command",
            )
        }
    }
}
