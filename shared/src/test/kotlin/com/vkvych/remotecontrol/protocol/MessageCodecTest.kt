package com.vkvych.remotecontrol.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MessageCodecTest {

    private val sampleState = DeviceState(
        deviceId = "device-1",
        deviceName = "Galaxy Tab S9",
        volumes = listOf(
            StreamVolume(AudioStream.MUSIC, level = 7, max = 15),
            StreamVolume(AudioStream.RING, level = 3, max = 7),
        ),
        ringerMode = RingerMode.NORMAL,
        battery = BatteryState(percent = 62, charging = true),
        capabilities = Capabilities(dndAccess = true),
        timestamp = 1_700_000_000_000L,
    )

    @Test
    fun `client messages round-trip for every command`() {
        val commands = listOf(
            GetState,
            SetVolume(AudioStream.MUSIC, level = 4),
            SetRingerMode(RingerMode.VIBRATE),
            SetMuted(muted = true),
        )

        for (command in commands) {
            val original = ClientMessage(id = "req-1", command = command)
            val decoded = MessageCodec.decodeClientMessage(MessageCodec.encode(original))

            assertIs<DecodedClientMessage.Parsed>(decoded, "failed for $command")
            assertEquals(original, decoded.message)
        }
    }

    @Test
    fun `server messages round-trip`() {
        val messages = listOf<ServerMessage>(
            CommandReply(id = "req-1", outcome = Success(sampleState)),
            CommandReply(id = "req-2", outcome = Success()),
            CommandReply(id = "req-3", outcome = Failure(ErrorCode.PERMISSION_REQUIRED, "DND access missing")),
            StateUpdate(sampleState),
        )

        for (message in messages) {
            assertEquals(message, MessageCodec.decodeServerMessage(MessageCodec.encode(message)))
        }
    }

    @Test
    fun `protocol version is always on the wire`() {
        val encoded = MessageCodec.encode(ClientMessage(id = "req-1", command = GetState))

        assertTrue(encoded.contains("\"protocolVersion\":$PROTOCOL_VERSION"), encoded)
    }

    @Test
    fun `unknown fields from a newer peer are ignored`() {
        val json = """
            {"id":"req-1","protocolVersion":$PROTOCOL_VERSION,
             "command":{"type":"set_volume","stream":"MUSIC","level":4,"fadeMillis":250},
             "issuedBy":"future-controller"}
        """.trimIndent()

        val decoded = MessageCodec.decodeClientMessage(json)

        assertIs<DecodedClientMessage.Parsed>(decoded)
        assertEquals(SetVolume(AudioStream.MUSIC, level = 4), decoded.message.command)
    }

    @Test
    fun `unknown command keeps the correlation id and reports UNSUPPORTED`() {
        val json = """
            {"id":"req-9","protocolVersion":$PROTOCOL_VERSION,
             "command":{"type":"uninstall_app","packageName":"com.example"}}
        """.trimIndent()

        val decoded = MessageCodec.decodeClientMessage(json)

        assertIs<DecodedClientMessage.Undecodable>(decoded)
        assertEquals("req-9", decoded.id)
        assertEquals(ErrorCode.UNSUPPORTED, decoded.code)
    }

    @Test
    fun `mismatched protocol version is rejected before decoding the command`() {
        val json = """
            {"id":"req-4","protocolVersion":${PROTOCOL_VERSION + 1},
             "command":{"type":"get_state"}}
        """.trimIndent()

        val decoded = MessageCodec.decodeClientMessage(json)

        assertIs<DecodedClientMessage.Undecodable>(decoded)
        assertEquals("req-4", decoded.id)
        assertEquals(ErrorCode.PROTOCOL_VERSION_MISMATCH, decoded.code)
    }

    @Test
    fun `malformed payloads do not throw`() {
        for (payload in listOf("not json at all", "[1,2,3]", "{", "")) {
            val decoded = MessageCodec.decodeClientMessage(payload)

            assertIs<DecodedClientMessage.Undecodable>(decoded, "failed for '$payload'")
            assertEquals(ErrorCode.INVALID_ARGUMENT, decoded.code)
        }
    }

    @Test
    fun `a message missing its id still decodes to an error rather than throwing`() {
        val decoded = MessageCodec.decodeClientMessage("""{"command":{"type":"nope"}}""")

        assertIs<DecodedClientMessage.Undecodable>(decoded)
        assertEquals(null, decoded.id)
    }
}
