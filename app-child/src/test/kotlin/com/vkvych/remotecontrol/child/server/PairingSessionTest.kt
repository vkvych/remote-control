package com.vkvych.remotecontrol.child.server

import com.vkvych.remotecontrol.protocol.PAIRING_CODE_LENGTH
import com.vkvych.remotecontrol.protocol.PAIRING_CODE_VALIDITY_MILLIS
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The pairing code is the only thing between a device on the tailnet and a durable token, so its
 * expiry, single-use and attempt-limit behaviour are all worth pinning down.
 */
class PairingSessionTest {

    private var now = 1_000_000L
    private val session = PairingSession(now = { now })

    @Test
    fun `a started code is displayed and well formed`() {
        val active = session.start()

        assertEquals(PAIRING_CODE_LENGTH, active.code.length)
        assertTrue(active.code.all { it.isDigit() })
        assertEquals(active, session.activeCode.value)
    }

    @Test
    fun `the right code is accepted exactly once`() {
        val code = session.start().code

        assertTrue(session.consume(code))
        assertNull(session.activeCode.value, "code should be consumed")
        assertFalse(session.consume(code), "a consumed code must not work twice")
    }

    @Test
    fun `a wrong code is rejected but leaves the real one usable`() {
        val code = session.start().code

        assertFalse(session.consume(anythingBut(code)))
        assertNotNull(session.activeCode.value)
        assertTrue(session.consume(code))
    }

    @Test
    fun `repeated guessing burns the code`() {
        val code = session.start().code
        val wrong = anythingBut(code)

        repeat(5) { assertFalse(session.consume(wrong)) }

        assertNull(session.activeCode.value, "the code should be burned after 5 wrong guesses")
        assertFalse(session.consume(code), "even the right code must not work after the budget ran out")
    }

    @Test
    fun `the attempt budget resets when a new code is shown`() {
        val exhausted = session.start().code
        repeat(4) { session.consume(anythingBut(exhausted)) }

        val code = session.start().code
        val wrong = anythingBut(code)

        repeat(4) { assertFalse(session.consume(wrong)) }
        assertTrue(session.consume(code), "a freshly shown code should have a full attempt budget")
    }

    @Test
    fun `an expired code is refused even if nothing pruned it`() {
        val code = session.start().code

        now += PAIRING_CODE_VALIDITY_MILLIS

        assertFalse(session.consume(code))
        assertNull(session.activeCode.value)
    }

    @Test
    fun `a code stays valid right up to its expiry`() {
        val code = session.start().code

        now += PAIRING_CODE_VALIDITY_MILLIS - 1

        assertTrue(session.consume(code))
    }

    @Test
    fun `pruning clears an expired code so the ui stops showing it`() {
        session.start()

        session.pruneExpired()
        assertNotNull(session.activeCode.value, "still valid, should survive pruning")

        now += PAIRING_CODE_VALIDITY_MILLIS
        session.pruneExpired()
        assertNull(session.activeCode.value)
    }

    @Test
    fun `cancelling hides the code immediately`() {
        val code = session.start().code

        session.cancel()

        assertNull(session.activeCode.value)
        assertFalse(session.consume(code))
    }

    @Test
    fun `starting again replaces the previous code`() {
        val first = session.start().code
        var second = session.start().code
        // Codes are random, so on the rare collision just draw another one.
        while (second == first) second = session.start().code

        assertFalse(session.consume(first), "the replaced code must stop working")
        assertTrue(session.consume(second))
    }

    /** A code that is guaranteed to be wrong, whatever was actually generated. */
    private fun anythingBut(code: String): String =
        if (code == "000000") "111111" else "000000"
}
