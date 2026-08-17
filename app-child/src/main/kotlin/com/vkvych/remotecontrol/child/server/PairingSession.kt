package com.vkvych.remotecontrol.child.server

import com.vkvych.remotecontrol.protocol.Credentials
import com.vkvych.remotecontrol.protocol.PAIRING_CODE_VALIDITY_MILLIS
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.SecureRandom

/** A pairing code that is currently accepted, together with when it stops being accepted. */
data class ActiveCode(
    val code: String,
    val expiresAt: Long,
)

/**
 * Lifecycle of the short-lived pairing code shown on the child device.
 *
 * The code is the only thing standing between a device on the tailnet and a token, so it is
 * deliberately hard to guess at scale despite being only six digits:
 *  - it exists only while somebody is physically looking at the child device's screen,
 *  - it expires after [PAIRING_CODE_VALIDITY_MILLIS],
 *  - it is single-use, and
 *  - [MAX_ATTEMPTS] wrong guesses burn it, forcing a human back to the device.
 *
 * Kept in memory only: a code must never outlive the process that displayed it.
 */
class PairingSession(
    private val random: SecureRandom = SecureRandom(),
    private val now: () -> Long = System::currentTimeMillis,
) {

    private val lock = Any()
    private var attemptsLeft: Int = MAX_ATTEMPTS

    private val _activeCode = MutableStateFlow<ActiveCode?>(null)

    /** The code being displayed, or `null` when there is none. Drives the setup UI. */
    val activeCode: StateFlow<ActiveCode?> = _activeCode.asStateFlow()

    /** Generates and starts displaying a fresh code, replacing any previous one. */
    fun start(): ActiveCode = synchronized(lock) {
        val code = ActiveCode(
            code = Credentials.generatePairingCode(random),
            expiresAt = now() + PAIRING_CODE_VALIDITY_MILLIS,
        )
        attemptsLeft = MAX_ATTEMPTS
        _activeCode.value = code
        code
    }

    fun cancel() = synchronized(lock) {
        _activeCode.value = null
    }

    /**
     * Drops the code once it has expired. Called both by the UI's countdown and before every
     * validation, so an expired code can never be accepted even if nothing is watching.
     */
    fun pruneExpired() {
        synchronized(lock) {
            val current = _activeCode.value
            if (current != null && now() >= current.expiresAt) _activeCode.value = null
        }
    }

    /**
     * Validates [candidate] and, on success, consumes the code so it cannot be used twice.
     *
     * Wrong guesses decrement the attempt budget; exhausting it clears the code.
     */
    fun consume(candidate: String): Boolean = synchronized(lock) {
        pruneExpired()
        val current = _activeCode.value
        when {
            current == null -> false

            Credentials.secretsMatch(candidate, current.code) -> {
                _activeCode.value = null
                true
            }

            else -> {
                attemptsLeft--
                if (attemptsLeft <= 0) _activeCode.value = null
                false
            }
        }
    }

    private companion object {
        const val MAX_ATTEMPTS = 5
    }
}
