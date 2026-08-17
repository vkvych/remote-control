package com.vkvych.remotecontrol.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CredentialsTest {

    @Test
    fun `pairing codes are always the advertised length and numeric`() {
        repeat(500) {
            val code = Credentials.generatePairingCode()

            assertEquals(PAIRING_CODE_LENGTH, code.length, "bad length: $code")
            assertTrue(code.all { it.isDigit() }, "not numeric: $code")
        }
    }

    @Test
    fun `pairing codes cover the whole range including leading zeros`() {
        val codes = List(2_000) { Credentials.generatePairingCode() }

        assertTrue(codes.any { it.startsWith("0") }, "never produced a leading zero")
        assertTrue(codes.toSet().size > 1_500, "suspiciously low entropy")
    }

    @Test
    fun `tokens are unique and url-safe`() {
        val tokens = List(1_000) { Credentials.generateToken() }

        assertEquals(1_000, tokens.toSet().size)
        assertTrue(tokens.all { token -> token.all { it.isLetterOrDigit() || it == '-' || it == '_' } })
        assertTrue(tokens.all { it.length >= 40 }, "token shorter than expected")
    }

    @Test
    fun `hashing is stable, hex encoded and dependent on the input`() {
        val token = Credentials.generateToken()
        val hash = Credentials.hashToken(token)

        assertEquals(hash, Credentials.hashToken(token))
        assertEquals(64, hash.length)
        assertTrue(hash.all { it in '0'..'9' || it in 'a'..'f' }, hash)
        assertFalse(hash == Credentials.hashToken(Credentials.generateToken()))
    }

    @Test
    fun `hashing matches a known vector`() {
        // SHA-256("abc"), the standard test vector.
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            Credentials.hashToken("abc"),
        )
    }

    @Test
    fun `secret comparison accepts equal values and rejects near misses`() {
        assertTrue(Credentials.secretsMatch("123456", "123456"))
        assertFalse(Credentials.secretsMatch("123456", "123457"))
        assertFalse(Credentials.secretsMatch("123456", "12345"))
        assertFalse(Credentials.secretsMatch("", "123456"))
    }

    @Test
    fun `a token verifies against its stored hash and nothing else`() {
        val issued = Credentials.generateToken()
        val stored = Credentials.hashToken(issued)

        assertTrue(Credentials.secretsMatch(Credentials.hashToken(issued), stored))
        assertFalse(Credentials.secretsMatch(Credentials.hashToken(Credentials.generateToken()), stored))
    }
}
