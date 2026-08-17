package com.vkvych.remotecontrol.protocol

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Generation and comparison of the two secrets in the system.
 *
 * The *pairing code* is short, human-transcribable and short-lived; it only ever authorises the
 * single exchange that issues a *token*. The token is long, random and durable, and is what
 * authenticates every later WebSocket session.
 *
 * The agent stores only [hashToken] of the token it issued, so reading the agent's private storage
 * does not yield a usable credential.
 */
object Credentials {

    private val secureRandom = SecureRandom()

    /** A zero-padded [PAIRING_CODE_LENGTH]-digit code, uniformly distributed. */
    fun generatePairingCode(random: SecureRandom = secureRandom): String {
        var bound = 1
        repeat(PAIRING_CODE_LENGTH) { bound *= 10 }
        return random.nextInt(bound).toString().padStart(PAIRING_CODE_LENGTH, '0')
    }

    /** A 256-bit token, URL-safe Base64 without padding. */
    fun generateToken(random: SecureRandom = secureRandom): String {
        val bytes = ByteArray(TOKEN_BYTES)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    /** Lowercase hex SHA-256 of [token]. */
    fun hashToken(token: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(token.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    /**
     * Compares two secrets without leaking their common prefix through timing.
     *
     * Note that [MessageDigest.isEqual] still returns early on a length mismatch; that is fine
     * here because the lengths of both secrets are fixed and public.
     */
    fun secretsMatch(a: String, b: String): Boolean =
        MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))

    private const val TOKEN_BYTES = 32
}
