package com.vkvych.remotecontrol.parent.net

import android.util.Log
import com.vkvych.remotecontrol.parent.data.PairedDevice
import com.vkvych.remotecontrol.protocol.AUTH_SCHEME
import com.vkvych.remotecontrol.protocol.ClientMessage
import com.vkvych.remotecontrol.protocol.Command
import com.vkvych.remotecontrol.protocol.CommandOutcome
import com.vkvych.remotecontrol.protocol.CommandReply
import com.vkvych.remotecontrol.protocol.DeviceState
import com.vkvych.remotecontrol.protocol.ErrorCode
import com.vkvych.remotecontrol.protocol.Failure
import com.vkvych.remotecontrol.protocol.MessageCodec
import com.vkvych.remotecontrol.protocol.Routes
import com.vkvych.remotecontrol.protocol.StateUpdate
import com.vkvych.remotecontrol.protocol.Success
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.min

/** What the controller can currently do with the child device. */
sealed interface ConnectionState {
    data object Disconnected : ConnectionState
    data object Connecting : ConnectionState
    data object Connected : ConnectionState

    /** Still trying — [message] explains the most recent attempt's failure. */
    data class Reconnecting(val message: String) : ConnectionState
}

/**
 * The long-lived link to one child device.
 *
 * Reconnects on its own with exponential backoff, because the interesting failure is mundane: the
 * child's phone went into a tunnel, or the parent switched from Wi-Fi to mobile data. A parent
 * reaching for the volume should find the app already connected rather than have to restart it.
 */
class ControlClient(private val scope: CoroutineScope) {

    private val httpClient = OkHttpClient.Builder()
        // Detects a silently dead link — a tailnet path can vanish without a FIN ever arriving.
        .pingInterval(PING_INTERVAL_SECONDS, TimeUnit.SECONDS)
        .build()

    private val pendingCommands = ConcurrentHashMap<String, CompletableDeferred<CommandOutcome>>()

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _deviceState = MutableStateFlow<DeviceState?>(null)
    val deviceState: StateFlow<DeviceState?> = _deviceState.asStateFlow()

    @Volatile
    private var target: PairedDevice? = null

    @Volatile
    private var webSocket: WebSocket? = null

    private var reconnectJob: Job? = null
    private var failedAttempts = 0

    /** Connects, and keeps reconnecting until [disconnect] is called. */
    @Synchronized
    fun connect(device: PairedDevice) {
        if (target == device && webSocket != null) return

        disconnect()
        target = device
        failedAttempts = 0
        openSocket(device)
    }

    @Synchronized
    fun disconnect() {
        target = null
        reconnectJob?.cancel()
        reconnectJob = null
        webSocket?.close(NORMAL_CLOSURE, "Controller disconnected")
        webSocket = null
        failAllPending("Disconnected")
        _connectionState.value = ConnectionState.Disconnected
        _deviceState.value = null
    }

    /**
     * Sends [command] and waits for the agent's answer.
     *
     * Always returns an outcome rather than throwing: every caller is a button press whose only
     * sensible reaction to a failure is to show the message.
     */
    suspend fun send(command: Command): CommandOutcome {
        val socket = webSocket
            ?: return Failure(ErrorCode.INTERNAL, "Not connected to the device")

        val id = UUID.randomUUID().toString()
        val answer = CompletableDeferred<CommandOutcome>()
        pendingCommands[id] = answer

        return try {
            val queued = socket.send(MessageCodec.encode(ClientMessage(id = id, command = command)))
            if (!queued) {
                Failure(ErrorCode.INTERNAL, "The connection is closing — try again in a moment")
            } else {
                withTimeout(COMMAND_TIMEOUT_MILLIS) { answer.await() }
            }
        } catch (e: TimeoutCancellationException) {
            Log.w(TAG, "No answer to $command within ${COMMAND_TIMEOUT_MILLIS}ms", e)
            Failure(ErrorCode.INTERNAL, "The device did not answer in time")
        } finally {
            pendingCommands.remove(id)
        }
    }

    private fun openSocket(device: PairedDevice) {
        _connectionState.value = ConnectionState.Connecting

        val request = Request.Builder()
            .url("${device.webSocketUrl}${Routes.CONTROL}")
            .header("Authorization", "$AUTH_SCHEME ${device.token}")
            .build()

        webSocket = httpClient.newWebSocket(request, SocketListener(device))
    }

    private fun scheduleReconnect(device: PairedDevice, reason: String) {
        synchronized(this) {
            // A disconnect() between the failure and here means the user is done with this device.
            if (target != device) return

            reconnectJob?.cancel()
            _connectionState.value = ConnectionState.Reconnecting(reason)

            val backoffMillis = min(
                INITIAL_BACKOFF_MILLIS shl min(failedAttempts, MAX_BACKOFF_SHIFT),
                MAX_BACKOFF_MILLIS,
            )
            failedAttempts++

            reconnectJob = scope.launch {
                delay(backoffMillis)
                synchronized(this@ControlClient) {
                    if (target == device) openSocket(device)
                }
            }
        }
    }

    private fun failAllPending(reason: String) {
        val outcome = Failure(ErrorCode.INTERNAL, reason)
        pendingCommands.values.forEach { it.complete(outcome) }
        pendingCommands.clear()
    }

    private fun handleMessage(payload: String) {
        val message = try {
            MessageCodec.decodeServerMessage(payload)
        } catch (e: Exception) {
            Log.w(TAG, "Ignoring an unreadable message from the agent", e)
            return
        }

        when (message) {
            is StateUpdate -> _deviceState.value = message.state

            is CommandReply -> {
                // Mutating commands answer with the resulting state, so the UI converges on what
                // the device actually did — which may differ from what was asked, since the agent
                // clamps to the range the hardware supports.
                (message.outcome as? Success)?.state?.let { _deviceState.value = it }
                pendingCommands.remove(message.id)?.complete(message.outcome)
            }
        }
    }

    private inner class SocketListener(private val device: PairedDevice) : WebSocketListener() {

        override fun onOpen(webSocket: WebSocket, response: Response) {
            Log.i(TAG, "Connected to ${device.host}")
            synchronized(this@ControlClient) {
                if (target != device) {
                    webSocket.close(NORMAL_CLOSURE, "Superseded")
                    return
                }
                failedAttempts = 0
                _connectionState.value = ConnectionState.Connected
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            handleMessage(text)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            val reason = when {
                response?.code == 401 || response?.code == 403 ->
                    "The device rejected this app's token — pair again."

                else -> t.message ?: "Could not reach the device"
            }
            Log.w(TAG, "Connection to ${device.host} failed: $reason", t)
            failAllPending(reason)
            scheduleReconnect(device, reason)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Log.i(TAG, "Connection to ${device.host} closed: $code $reason")
            failAllPending("Connection closed")
            if (code != NORMAL_CLOSURE) {
                scheduleReconnect(device, reason.ifBlank { "The device closed the connection" })
            }
        }
    }

    private companion object {
        const val TAG = "ControlClient"
        const val NORMAL_CLOSURE = 1000
        const val PING_INTERVAL_SECONDS = 20L
        const val COMMAND_TIMEOUT_MILLIS = 8_000L
        const val INITIAL_BACKOFF_MILLIS = 1_000L
        const val MAX_BACKOFF_MILLIS = 30_000L
        const val MAX_BACKOFF_SHIFT = 5
    }
}
